(ns graphden.crud.entities.delete-batch
  "Exact-receipt function deletion. All decisive reads and tombstones share
   one decorated writer transaction; derived state is published after commit."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.entities.invalidation :as invalidation]
    [graphden.crud.package-guard :as package-guard]
    [graphden.crud.request :as request]
    [graphden.crud.secret-shape :as secret-shape]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.types.diagnostics :as diagnostics]
    [graphden.util.abort-shield :as shield]
    [graphden.versioning.storage.core :as versioned]
    [graphden.versioning.storage.resolution :as resolution]))


(defn- refuse!
  [type]
  ;; Never echo row contents, foreign identities or storage exception data.
  (throw (ex-info "The function deletion batch was refused. Refresh and retry."
                  {:type type})))


(defn- receipt
  [row]
  (when-not (and (map? row) (= #{:id :name :namespace-id} (set (keys row)))
                 (string? (:name row)) (not (str/blank? (:name row)))
                 (<= (count (:name row)) 1000))
    (refuse! :validation-error/delete-receipts))
  (let [id (if (uuid? (:id row)) (:id row) (request/parse-uuid-or-clear (:id row)))
        ns-id (if (uuid? (:namespace-id row)) (:namespace-id row)
                  (request/parse-uuid-or-clear (:namespace-id row)))]
    (when-not (and id ns-id) (refuse! :validation-error/delete-receipts))
    {:id id :name (:name row) :namespace-id ns-id}))


(defn parse-receipts
  "Validate an untrusted JSON envelope into 1–1000 distinct exact identities.
   Does not read storage. The mutation revalidates even direct graph callers."
  [input]
  (when-not (and (map? input) (= #{:functions} (set (keys input)))
                 (vector? (:functions input)) (<= 1 (count (:functions input)) 1000))
    (refuse! :validation-error/delete-receipts))
  (let [rows (mapv receipt (:functions input))]
    (when-not (= (count rows) (count (set (map :id rows))))
      (refuse! :validation-error/delete-receipts))
    rows))


(defn- validate-identities!
  [storage receipts]
  (let [rows (sp/read-entities storage :fn (mapv :id receipts))
        leaf-names (mapv name (registry/fn-names-with-tag :secret-shape))
        leaf-ids (when (seq leaf-names)
                   (map :id (sp/query-entities storage :fn {:name leaf-names})))]
    (doseq [expected receipts
            :let [row (get rows (:id expected))]
            :when row]
      (writer/assert-write-authorized! storage :fn row (:id row))
      (when-not (= expected (select-keys row [:id :name :namespace-id]))
        (refuse! :graph-write/stale-delete-receipt))
      (when (package-guard/delete-rejection storage :fn row)
        (refuse! :authz/forbidden))
      (when (some #(secret-shape/secret-fn? row %) leaf-ids)
        (refuse! :graph-write/secret-delete)))
    rows))


(defn- external-referrers
  "Same live-owner rule as web.crud's singular DELETE. Versioned queries
   resolve current branch refs (including refs changed since creation)."
  [storage ids]
  (let [targets (vec ids)
        bindings (concat (sp/query-entities storage :binding {:ref-fn-id targets})
                         (sp/query-entities storage :binding {:resolver-fn-id targets}))
        items (sp/query-entities storage :binding-list-item {:ref-fn-id targets})
        item-bindings (sp/read-entities storage :binding (vec (set (map :binding-id items))))
        owner-ids (into #{} (keep :fn-id) (concat bindings (vals item-bindings)))
        external-ids (vec (remove ids owner-ids))]
    (when (seq external-ids)
      (seq (sp/read-entities storage :fn external-ids)))))


(defn- validate-references!
  [storage ids]
  ;; Parent ownership is an indexed ref-many query; the existing protocol is
  ;; singular. Keep its branch/liveness semantics rather than unwrap storage.
  (when (or (some (fn [id]
                    (some #(not (contains? ids %))
                          (sp/query-ref-many-owners storage :fn :parent-ids id))) ids)
            (external-referrers storage ids))
    (refuse! :graph-write/functions-in-use)))


(defn- delete-locked!
  [storage receipts]
  (let [rows (validate-identities! storage receipts)
        ids (set (keys rows))]
    (when (seq ids)
      (validate-references! storage ids)
      (binding [versioned/*tombstone-delete?* true]
        (doseq [id ids]
          ;; Keep actual decorated per-identity authorization and branch/merge
          ;; guards. A no-op/refusal after validation rolls back the WHOLE set.
          (when-not (sp/delete-entity storage :fn id)
            (refuse! :authz/forbidden)))))
    {:rows rows
     :result {:deleted (filterv ids (mapv :id receipts))
              :already-absent (filterv #(not (contains? ids %)) (mapv :id receipts))}}))


(defn- notify-deletion!
  [ctx storage rows repair?]
  (doseq [[org group] (group-by :org-id (vals rows))]
    (invalidation/notify-after-write!
      ctx storage :fn :delete
      (cond-> {:ids (mapv :id group) :org-id org}
        repair? (assoc :invalidate-origin? true)))))


(defn- publish!
  [ctx storage rows]
  (when (seq rows)
    (let [ids (set (keys rows))]
      (try
        (doseq [[id row] rows]
          (when (:name row) (registry/unregister-rich-type! (keyword (:name row)) id))
          (diagnostics/clear-fn! (versioned/current-branch-id storage) id))
        (type-check/recheck-deleted-fns! ctx storage ids)
        (invalidation/invalidate! ctx storage :fn {:ids ids})
        (notify-deletion! ctx storage rows false)
        (catch Exception _
          ;; The tombstones committed. Do not report rollback or invite the
          ;; client to retain a false live ledger. The graph epoch also heals.
          (log/warn "Committed function deletion needs publication repair")
          (try (notify-deletion! ctx storage rows true)
               (catch Exception _
                 (log/warn "Function deletion notification repair failed"))))))))


(defn delete-receipts!
  "Atomic exact-identity deletion, never a namespace/name sweep. The writer
   lock covers fresh identities, ACLs, foreign references and all tombstones;
   publishing inside the transaction would leak state on rollback."
  [ctx receipts]
  (let [receipts (parse-receipts {:functions receipts})]
    (shield/run!
      (fn []
        (let [storage (request/require-storage ctx)
              _ (tx/assert-owns-commit! storage)
              {:keys [rows result]}
              (writer/with-write [storage :graph]
                                 (resolution/call-with-fresh-memos #(delete-locked! storage receipts)))]
          (publish! ctx storage rows)
          result)))))
