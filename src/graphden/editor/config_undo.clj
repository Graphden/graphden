(ns graphden.editor.config-undo
  "Atomic LIFO undo of an ordinary editor activation. Receipt state is
   untrusted: actual home, source, write and reference guards still apply."
  (:require
    [clojure.string :as str]
    [graphden.crud.entities.delete-batch :as deletion]
    [graphden.crud.request :as request]
    [graphden.editor.components :as components]
    [graphden.editor.config-activation :as activation]
    [graphden.editor.config-preparation :as preparation]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.preference-write :as preferences]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.graph.metadata :as metadata]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.resolution :as branch-resolution]))


(defn- refuse!
  [reason]
  (throw (ex-info "Editor configuration undo was refused"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- parse-identity
  [value]
  (or (when (uuid? value) value)
      (when (string? value) (parse-uuid value))
      (refuse! :invalid-request)))


(defn parse-command
  [input]
  (when-not (and (map? input)
                 (= #{:current-id :expected-configuration-revision :cohort-revision :previous :functions} (set (keys input)))
                 (activation/revision? (:expected-configuration-revision input))
                 (activation/revision? (:cohort-revision input)))
    (refuse! :invalid-request))
  (let [previous (:previous input)
        current-id (parse-identity (:current-id input))
        receipts (deletion/parse-receipts {:functions (:functions input)})
        ids (set (map :id receipts))]
    (when (and (some? previous)
               (not (and (map? previous) (= #{:id :name :namespace-id :revision} (set (keys previous)))
                         (string? (:name previous)) (not (str/blank? (:name previous)))
                         (<= (count (:name previous)) 1000) (activation/revision? (:revision previous)))))
      (refuse! :invalid-request))
    (let [previous (when (some? previous) (-> previous (update :id parse-identity) (update :namespace-id parse-identity)))]
      ;; LIFO only: never restore an identity included in this delete receipt.
      (when (or (not (contains? ids current-id)) (contains? ids (:id previous)))
        (refuse! :invalid-request))
      (assoc input :current-id current-id :previous previous :functions receipts))))


(defn- previous-row!
  [storage previous home scope authorize! policy]
  (when previous
    (let [row (sp/read-entity storage :fn (:id previous))]
      (when-not (and row (= (:id home) (:namespace-id previous) (:namespace-id row))
                     (= (:name previous) (:name row)))
        (refuse! :stale-configuration))
      (writer/assert-write-authorized! storage :fn row (:id row))
      (let [selected (activation/selected-source storage (:id row) authorize! policy)]
        (when-not (= (:revision previous) (resolution/source-revision (:id row) (:snapshot selected) scope))
          (refuse! :stale-configuration)))
      row)))


(defn- undo-locked!
  [storage input policy]
  (let [authorize! (tenancy/browser-source-policy storage)
        current (resolution/capture storage authorize! policy)
        home (when-let [path (get-in current [:home :namespace-path])]
               (metadata/namespace-row storage path (tenancy/current-org)))]
    (when-not (and home (= (:current-id input) (:id current))
                   (every? #(= (:id home) (:namespace-id %)) (:functions input))
                   (= (:expected-configuration-revision input) (:revision current)))
      (refuse! :stale-configuration))
    (let [previous (previous-row! storage (:previous input) home (:scope current) authorize! policy)
          cohort {:graph (source/collect-manifests storage (mapv :id (:functions input)) authorize!)}
          _ (when-not (= (:cohort-revision input)
                         (resolution/cohort-revision (map :id (:functions input)) cohort (:scope current)))
              (refuse! :stale-configuration))
          deleted (deletion/delete-locked! storage (:functions input))]
      ;; The replacement tombstone frees the versioned name. Any refusal of
      ;; the following rename rolls back ALL deletions in this transaction.
      (when previous
        (when-not (= "editor" (:name (sp/update-entity storage :fn (:id previous) {:name "editor"})))
          (refuse! :write-refused)))
      {:rows (:rows deleted)
       :result (assoc (:result deleted) :id (or (:id previous) resolution/defaults-id)
                      :functions [] :changed-functions (if previous [(:id previous)] []))})))


(defn undo!
  [ctx input]
  (let [input (parse-command input)
        storage (request/require-storage ctx)
        context-epoch (context/invalidation-epoch ctx)
        policy (snapshot/capture-context-policy ctx)]
    (tx/assert-owns-commit! storage)
    (let [{:keys [rows result]}
          (writer/with-write [storage :graph]
                             (preferences/call-with-lock
                               storage "components"
                               (fn [storage]
                                 (branch-resolution/call-with-fresh-memos
                                   #(do
                                      (components/assert-current-policy! storage)
                                      (let [result (undo-locked! storage input policy)]
                                        (when-not (= context-epoch (context/invalidation-epoch ctx))
                                          (refuse! :policy-refresh-required))
                                        result))))))]
      (deletion/publish! ctx storage rows)
      (preparation/publish! ctx storage result))))
