(ns graphden.crud.inheritance.overlay
  "Read-only projected graph used by the ordinary structural validators."
  (:require
    [graphden.storage.protocol.core :as sp]))


(defn- matches?
  [row where]
  (every? (fn [[key value]]
            (if (sequential? value) (contains? (set value) (get row key))
                (= value (get row key)))) where))


(defn- projected-query
  [base rows removed entity where]
  (let [changed (get rows entity {})
        excluded (into (get removed entity #{}) (keys changed))]
    (into (filterv #(not (contains? excluded (:id %)))
                   (sp/query-entities base entity where))
          (filter #(matches? % where) (vals changed)))))


(defn- dependencies
  [storage id]
  (let [row (sp/read-entity storage :fn id)
        bindings (sp/query-entities storage :binding {:fn-id id})
        items (when (seq bindings)
                (sp/query-entities storage :binding-list-item {:binding-id (mapv :id bindings)}))]
    (into (set (:parent-ids row))
          (keep identity)
          (concat (mapcat (juxt :ref-fn-id :type-override-fn-id :resolver-fn-id) bindings)
                  (map :ref-fn-id items)))))


(defrecord ProjectedStorage
  [base rows removed branch-id]

  sp/StorageCRUD

  (create-entity [_ _entity _data] (throw (UnsupportedOperationException. "Read-only inheritance projection")))


  (update-entity [_ _entity _id _data] (throw (UnsupportedOperationException. "Read-only inheritance projection")))


  (delete-entity [_ _entity _id] (throw (UnsupportedOperationException. "Read-only inheritance projection")))


  (read-entity
    [_ entity id]
    (when-not (contains? (get removed entity #{}) id)
      (or (get-in rows [entity id]) (sp/read-entity base entity id))))


  (query-entities [_ entity where] (projected-query base rows removed entity where))


  (query-entities [_ entity where _opts] (projected-query base rows removed entity where))


  (query-latest-per-group
    [_ entity where group-cols]
    (sp/query-latest-per-group base entity where group-cols))


  sp/StorageBatchCRUD

  (create-entities [_ _entity _data] (throw (UnsupportedOperationException. "Read-only inheritance projection")))


  (update-entities [_ _entity _data] (throw (UnsupportedOperationException. "Read-only inheritance projection")))


  (upsert-entities [_ _entity _data] (throw (UnsupportedOperationException. "Read-only inheritance projection")))


  (delete-entities [_ _entity _ids] (throw (UnsupportedOperationException. "Read-only inheritance projection")))


  (read-entities
    [this entity ids]
    (into {} (keep (fn [id] (when-let [row (sp/read-entity this entity id)] [id row]))) ids))


  (query-ref-many-owners
    [this entity field target]
    (into [] (keep #(when (some #{target} (get % field)) (:id %)))
          (sp/query-entities this entity {})))


  sp/ConstraintHelpers

  (collect-dependency-chain
    [this fn-id]
    (loop [seen #{} frontier #{fn-id}]
      (if (empty? frontier) seen
          (let [dependencies-found (into #{} (mapcat #(dependencies this %)) frontier)]
            (recur (into seen frontier) (reduce disj dependencies-found (into seen frontier)))))))


  sp/GraphConstraints

  (validate-no-dependency-cycle!
    [this owner ref]
    (sp/validate-no-dependency-cycle-impl this owner ref)))


(defn project
  [storage rows removed]
  (->ProjectedStorage storage rows removed (:branch-id storage)))
