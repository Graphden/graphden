(ns graphden.editor.config-activation
  "Explicit versioned replacement of an ordinary named editor root. The old
   root remains a real graph identity; no existing parent junction is changed."
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]
    [graphden.crud.request :as request]
    [graphden.editor.component-config :as config]
    [graphden.editor.components :as components]
    [graphden.editor.config-preparation :as preparation]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.preference-write :as preferences]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.executor.source-contract :as contract]
    [graphden.graph.metadata :as metadata]
    [graphden.graph.personal-home :as personal-home]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.remote.core :as remote]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check.provenance :as provenance]
    [graphden.versioning.storage.resolution :as branch-resolution]))


(defn- refuse!
  [reason]
  (throw (ex-info "Editor configuration activation was refused"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- source-mismatch!
  [stage]
  ;; The stage is a server-owned enum. Never log source rows, revisions,
  ;; submitted commands or an exception object from this refusal.
  (log/warn "Editor configuration source mismatch" (name stage))
  (throw (ex-info "Editor configuration activation was refused"
                  {:type :browser-plan/unsupported :reason :write-source-mismatch
                   :stage stage})))


(defn revision?
  "Whether an untrusted expected-state revision has the finite digest shape."
  [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))


(defn- parse-cas-input
  [input fields]
  (when-not (and (map? input)
                 (= fields (set (keys input)))
                 (map? (:expected-preference input))
                 (= #{:present :value} (set (keys (:expected-preference input))))
                 (boolean? (get-in input [:expected-preference :present]))
                 (revision? (:expected-configuration-revision input)))
    (refuse! :invalid-request))
  input)


(defn parse-command
  "Validate client expected-state only; this creates no authority or graph
   identities. Selection and revisions are revalidated inside the writer."
  [input]
  (parse-cas-input input #{:selection-id :expected-preference :expected-configuration-revision})
  (let [id (:selection-id input)
        id (if (uuid? id) id (when (string? id) (parse-uuid id)))]
    (when-not id (refuse! :invalid-request))
    (assoc input :selection-id id)))


(defn parse-personalize-command
  [input]
  (parse-cas-input input #{:component :expected-preference :expected-configuration-revision})
  (when-not (and (string? (:component input))
                 (contains? (set (map name (keys config/component-contracts))) (:component input)))
    (refuse! :invalid-request))
  (update input :component keyword))


(defn- operation-command
  [operation command]
  (case operation
    "activate" (parse-command command)
    "personalize" (parse-personalize-command command)
    (refuse! :invalid-request)))


(defn parse-preview-command
  [input]
  (when-not (and (map? input) (= #{:operation :command} (set (keys input))))
    (refuse! :invalid-request))
  (assoc input :command (operation-command (:operation input) (:command input))))


(defn parse-scope
  "Finite expected ingress scope. Submitted identities never grant authority."
  [input]
  (when-not (and (map? input) (= #{:principal-id :org :branch-id} (set (keys input)))
                 (every? #(and (string? %) (<= 1 (count %) 1000)) ((juxt :principal-id :org) input))
                 (string? (:branch-id input)) (parse-uuid (:branch-id input)))
    (refuse! :invalid-request))
  input)


(defn parse-proposal-command
  [input]
  (when-not (and (map? input)
                 (= #{:operation :command :nonce :scope :expected-proposal} (set (keys input)))
                 (revision? (:expected-proposal input)))
    (refuse! :invalid-request))
  (parse-scope (:scope input))
  (let [nonce (:nonce input)
        nonce (if (uuid? nonce) nonce (when (string? nonce) (parse-uuid nonce)))]
    (when-not nonce (refuse! :invalid-request))
    (assoc input :nonce nonce :command (operation-command (:operation input) (:command input)))))


(defn- assert-preference!
  [storage expected]
  (let [rows (sp/query-entities storage :ui-pref
                                {:owner-id (tenancy/current-user-id) :key "components"} {:limit 2})]
    (when-not (and (<= (count rows) 1)
                   (= expected {:present (boolean (first rows)) :value (:value (first rows))}))
      (refuse! :stale-configuration))))


(defn selected-source
  "Capture and validate one actual configuration under the caller's frozen
   snapshot policy. Identity metadata does not grant implementation access."
  [storage id authorize! policy]
  (let [row (sp/read-entity storage :fn id)]
    (when-not row (refuse! :source-missing))
    (authorize! row {:execute? true})
    (let [graph (source/collect-manifest storage id authorize!)
          selected {:graph graph :namespaces (source/collect-namespaces storage graph)}]
      (snapshot/validate-source! selected id policy)
      {:snapshot selected
       :format (:format (config/configuration-info graph id))})))


(defn- renamed-snapshot
  [selected id private-name]
  (update-in selected [:graph :fns]
             #(mapv (fn [row] (if (= id (:id row)) (assoc row :name private-name) row)) %)))


(defn- expected-revisions
  [prepared prior-row private-name scope authorize!]
  (let [expected (cond-> (:snapshot prepared)
                   prior-row (renamed-snapshot (:id prior-row) private-name))
        graph (:graph expected)
        own-ids (mapv :id (filter #(= :fn (:kind %)) (:records prepared)))
        fixed (remote/from-bundle {:fn (:fns graph) :slot (:slots graph)
                                   :fn-slot (:fn-slots graph) :binding (:bindings graph)
                                   :binding-list-item (:list-items graph)})
        normal (source/collect-manifest fixed (:id prepared) authorize!)]
    ;; This is a projection of exact, already prechecked expected rows, not a
    ;; certificate of current DB contents or a new source-authority channel.
    {:configuration-revision (resolution/source-revision (:id prepared) {:graph normal} scope)
     :cohort-revision (resolution/cohort-revision own-ids expected scope)}))


(defn- committed-revision!
  [storage prepared prior-row private-name scope authorize!]
  (let [expected (cond-> (:snapshot prepared)
                   prior-row (renamed-snapshot (:id prior-row) private-name))
        own-ids (mapv :id (filter #(= :fn (:kind %)) (:records prepared)))
        graph (source/collect-manifests storage own-ids authorize!)
        actual {:graph graph}
        ids (set (map :id (get-in expected [:graph :fns])))
        revision (resolution/source-revision (:id prepared) actual scope)]
    ;; Exact prechecked rows and previously authorized external source are the
    ;; witness. Reading the current DB alone never creates a source certificate.
    (when-not (= ids (set (map :id (:fns graph))))
      (source-mismatch! :function-identities))
    (when-not (contract/matches? (:graph expected) graph ids)
      (source-mismatch! :structural-rows))
    (when-not (= revision (resolution/source-revision (:id prepared) expected scope))
      (source-mismatch! :source-revision))
    ;; Normal resolution deliberately leaves role bodies terminal. Its root
    ;; stamp must use that same view; the cohort stamp covers new own bodies.
    (let [revisions {:configuration-revision (resolution/source-revision
                                               (:id prepared)
                                               {:graph (source/collect-manifest storage (:id prepared) authorize!)} scope)
                     :cohort-revision (resolution/cohort-revision own-ids actual scope)}]
      (when-not (= revisions (expected-revisions prepared prior-row private-name scope authorize!))
        (source-mismatch! :projected-revisions))
      revisions)))


(defn- selection-for-write
  [storage input current prior-row authorize! policy]
  (let [selected (if-let [component (:component input)]
                   (let [roles (get-in current [:configuration component])]
                     (when-not roles (refuse! :component-missing))
                     {:component component :roles roles})
                   (let [selected (selected-source storage (:selection-id input) authorize! policy)]
                     {:id (:selection-id input)
                      :record? (case (:format selected) :record true :list false :map :map)}))]
    (cond-> selected prior-row (assoc :prior-id (:id prior-row) :description (:description prior-row)))))


(defn- configuration-plan
  [storage input policy nonce read-only?]
  (let [authorize! (tenancy/browser-source-policy storage)
        current (resolution/capture storage authorize! policy)
        _ (assert-preference! storage (:expected-preference input))
        _ (when-not (= (:revision current) (:expected-configuration-revision input))
            (refuse! :stale-configuration))
        selected-id (:selection-id input)
        existing-component? (when-let [component (:component input)]
                              (resolution/owned-writable-view? storage current component))]
    (if (or (= (:id current) selected-id) existing-component?)
      {:scope (:scope current)
       :receipt {:id (:id current) :created false :functions [] :namespaces [] :configuration-revision (:revision current)
                 :entry-id (when (:component input) (get-in current [:configuration (:component input) :view]))}}
      (let [home (:home current)
            _ (when-not home (refuse! :personal-home-unavailable))
            existing-home (metadata/namespace-row storage (:namespace-path home) (tenancy/current-org))
            _ (when (and read-only? (nil? existing-home)) (refuse! :personal-home-unavailable))
            namespace-row (or existing-home (personal-home/ensure-current! storage))
            prior? (not= resolution/defaults-id (:id current))
            prior-row (when prior? (sp/read-entity storage :fn (:id current)))
            _ (when prior? (writer/assert-write-authorized! storage :fn prior-row (:id prior-row)))
            selected (selection-for-write storage input current prior-row authorize! policy)
            prepared (preparation/prepared-records
                       storage namespace-row selected authorize! policy nonce)
            private-name (str "_editor-prior-" nonce)
            ids (mapv :id (filter #(= :fn (:kind %)) (:records prepared)))
            prior-snapshot (when prior?
                             (renamed-snapshot (:snapshot current) (:id prior-row) private-name))
            revisions (expected-revisions prepared prior-row private-name (:scope current) authorize!)]
        {:prepared prepared :prior-row prior-row :private-name private-name :scope (:scope current)
         :authorize! authorize!
         :receipt (merge revisions
                         {:id (:id prepared) :created true :functions ids
                          :restore-revision (:revision current)
                          :restore-id (:id current)
                          :entry-id (when-let [component (:component input)]
                                      (get-in (config/configuration (get-in prepared [:snapshot :graph]) (:id prepared))
                                              [component :view]))
                          :namespaces (if existing-home [] [(:id namespace-row)])
                          :function-receipts (mapv #(select-keys % [:id :name :namespace-id])
                                                   (filter #(= :fn (:kind %)) (:records prepared)))
                          :changed-functions (if prior? [(:id prior-row)] [])
                          :previous (when prior?
                                      {:id (:id prior-row) :name private-name :namespace-id (:id namespace-row)
                                       :revision (resolution/source-revision (:id prior-row) prior-snapshot (:scope current))})})}))))


(defn- commit-plan!
  [storage {:keys [prepared prior-row private-name scope authorize! receipt]}]
  (if-not (:created receipt)
    receipt
    (do
      ;; Change only versioned name metadata. Identity-plane parents, slots
      ;; and every existing body remain untouched.
      (when prior-row
        (let [renamed (sp/update-entity storage :fn (:id prior-row) {:name private-name})]
          (when-not (= private-name (:name renamed)) (refuse! :write-refused))))
      (preparation/create-records! storage (:records prepared))
      (let [revisions (committed-revision! storage prepared prior-row private-name scope authorize!)]
        (when-not (= revisions (select-keys receipt [:configuration-revision :cohort-revision]))
          (source-mismatch! :preview-revisions))
        (assoc receipt :committed true)))))


(defn call-with-current-configuration
  "Freeze source policy and epoch, then run an editor configuration operation
   under the graph writer and current owner's preference lock."
  [ctx operation]
  (let [storage (request/require-storage ctx)
        context-epoch (context/invalidation-epoch ctx)
        policy (snapshot/capture-context-policy ctx)]
    (tx/assert-owns-commit! storage)
    (writer/with-write [storage :graph]
                       (preferences/call-with-lock
                         storage "components"
                         (fn [storage]
                           (branch-resolution/call-with-fresh-memos
                             #(do
                                (components/assert-current-policy! storage)
                                (let [result (operation storage policy)]
                                  (when-not (= context-epoch (context/invalidation-epoch ctx))
                                    (refuse! :policy-refresh-required))
                                  result))))))))


(defn wire-value
  "Encode the exact JSON value that a client can send back as a proposal."
  [value]
  ;; Match the actual API encoding, including keyword enum values. Converting
  ;; UUIDs alone would produce a Clojure-only request the JSON parser rejects.
  (json/parse-string (json/generate-string value) true))


(defn request-scope
  "Canonical wire representation of the authenticated ingress scope."
  [scope]
  (wire-value (select-keys scope [:principal-id :org :branch-id])))


(defn- proposal-seal
  [plan operation command nonce policy]
  (let [{:keys [prepared prior-row private-name scope receipt]} plan
        expected (cond-> (:snapshot prepared)
                   prior-row (renamed-snapshot (:id prior-row) private-name))
        ids (mapv :id (get-in expected [:graph :fns]))]
    (provenance/fingerprint-data
      {:operation operation :command command :nonce nonce :scope scope :receipt receipt
       :source (when prepared (resolution/source-revision (:id prepared) expected scope))
       ;; Freeze the original semantic upper bounds as well as source rows.
       ;; Compiled functions and mutable registry objects are never serialized.
       :policy (into {} (map (fn [id]
                               [id {:class (get-in policy [:classes id])
                                    :signature (select-keys (get-in policy [:rich :by-id id])
                                                            [:return :args :effects :taint-policy])}])) ids)})))


(defn preview!
  "Read-only proposal with exact fresh identities and ORIGINAL cleanup stamps.
   The home must already exist. A nonce/digest is expected state, not authority."
  [ctx input]
  (let [{:keys [operation command]} (parse-preview-command input)
        nonce (random-uuid)]
    (call-with-current-configuration
      ctx
      (fn [storage policy]
        (let [plan (configuration-plan storage command policy nonce true)]
          {:request (wire-value {:operation operation :command command :nonce nonce
                                 :scope (request-scope (:scope plan))
                                 :expected-proposal (proposal-seal plan operation command nonce policy)})
           :receipt (wire-value (dissoc (:receipt plan) :changed-functions))})))))


(defn apply-proposal!
  "Rebuild the complete canonical proposal before writing. Tombstoned reserved
   identities are refused; a lost response must never trigger blind replay."
  [ctx input]
  (let [{:keys [operation command nonce scope expected-proposal]} (parse-proposal-command input)
        storage (request/require-storage ctx)
        result (call-with-current-configuration
                 ctx
                 (fn [storage policy]
                   (let [plan (configuration-plan storage command policy nonce true)]
                     (when-not (and (= scope (request-scope (:scope plan)))
                                    (= expected-proposal (proposal-seal plan operation command nonce policy)))
                       (refuse! :stale-configuration))
                     (commit-plan! storage plan))))]
    (preparation/publish! ctx storage result)))


(defn- write-configuration!
  "CAS the authenticated preference and ordinary root inside one decorated
   writer transaction. The legacy preference remains a compatibility source."
  [ctx input]
  (let [storage (request/require-storage ctx)
        result (call-with-current-configuration
                 ctx #(commit-plan! %1 (configuration-plan %1 input %2 (random-uuid) false)))]
    (preparation/publish! ctx storage result)))


(defn activate!
  "Explicit overlay in order defaults → prior → selected. This is not an
   implicit full replacement of keys omitted by a partial selected manifest."
  [ctx input]
  (write-configuration! ctx (parse-command input)))


(defn personalize!
  "Create only inherited role children for one current component, then an
   ordinary overlay root. Existing custom views open without another cohort."
  [ctx input]
  (write-configuration! ctx (parse-personalize-command input)))
