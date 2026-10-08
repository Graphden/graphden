(ns graphden.storage.bounded-query
  "Owner predicates and conservative identity-candidate budgets."
  (:require
    [graphden.storage.protocol.core :as sp]))


(def ^:private owner-fields
  {:fn-slot :fn-id :binding :fn-id :binding-list-item :binding-id})


(defn candidate-where
  [entity where max-candidates]
  (let [owner (get owner-fields entity)]
    (when-not (and owner (map? where) (some? (get where owner))
                   (integer? max-candidates) (<= 0 max-candidates 2147483646))
      (throw (ex-info "Unsupported bounded owner query"
                      {:type :storage-error/bounded-query-unsupported :entity-name entity})))
    (select-keys where [owner :id])))


(defn check-candidate-count!
  [rows max-candidates]
  (when (> (count rows) max-candidates)
    (throw (ex-info "Identity candidate budget exceeded"
                    {:type :storage-error/candidate-limit})))
  rows)


(defn candidate-ids
  [storage entity where version-source max-candidates]
  (candidate-where entity where max-candidates)
  (when-not (satisfies? sp/StorageBoundedQuery storage)
    (throw (ex-info "Storage cannot bound identity candidates"
                    {:type :storage-error/bounded-query-unsupported :entity-name entity})))
  (let [candidates (sp/query-identity-candidates storage entity where version-source max-candidates)]
    (with-meta (mapv :id candidates) (meta candidates))))
