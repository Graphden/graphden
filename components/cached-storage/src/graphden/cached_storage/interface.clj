(ns graphden.cached-storage.interface
  "Decorator that wraps any storage with execution graph caching.

   Usage:
   (def storage (postgres/create-storage config))
   (def cache (cache-postgres/create-cache config))
   (def cached (wrap-with-cache storage cache))

   The wrapped storage:
   - Delegates all CRUD operations to the base storage
   - Uses cache for resolve-execution-graph (O(1) instead of O(depth))
   - Invalidates affected caches after mutations

   Cache invalidation strategy:
   - fn created: create cache for new fn
   - fn updated (parent-fn-id changed): invalidate fn + all dependents
   - fn deleted: delete cache + invalidate all dependents
   - arg-value created/updated/deleted: invalidate owner-fn + dependents
   - fn-result-value created/deleted: invalidate referencing caches
   - fn-schema updated: invalidate all caches depending on it
   - arg-schema updated: invalidate all caches depending on it"
  (:require
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


;; === Cache dependency computation ===

(defn- compute-dependencies
  "Computes dependency counts from an execution graph.
   Returns {:fn-ids {fn-id -> count}
            :fn-schema-ids {schema-id -> count}
            :arg-schema-ids {arg-schema-id -> count}}"
  [graph]
  {:fn-ids (frequencies (keys (:fns graph)))
   :fn-schema-ids (frequencies (keys (:fn-schemas graph)))
   :arg-schema-ids (frequencies (keys (:arg-schemas graph)))})


(defn- rebuild-cache!
  "Rebuilds cache for a single fn-id using base storage's resolve-execution-graph."
  [base-storage cache-storage fn-id]
  (let [graph (sp/resolve-execution-graph base-storage fn-id)
        deps (compute-dependencies graph)]
    (cache/save-cache! cache-storage fn-id graph deps)))


(defn- invalidate-dependents!
  "Invalidates all caches returned by find-fn and rebuilds them if the fn exists.
   find-fn should be a function that takes cache-storage and returns a set of cache-ids."
  [base-storage cache-storage find-fn]
  (let [dependent-cache-ids (find-fn cache-storage)]
    (doseq [cache-id dependent-cache-ids]
      (cache/delete-cache! cache-storage cache-id)
      (when (sp/read-entity base-storage :fn cache-id)
        (rebuild-cache! base-storage cache-storage cache-id)))))


(defn- invalidate-fn-and-dependents!
  "Invalidates cache for fn-id and all caches that depend on it."
  [base-storage cache-storage fn-id]
  ;; Invalidate and rebuild all dependent caches
  (invalidate-dependents! base-storage cache-storage
                          #(cache/find-caches-by-fn-dep % fn-id))
  ;; Rebuild cache for the fn itself if it exists
  (when (sp/read-entity base-storage :fn fn-id)
    (rebuild-cache! base-storage cache-storage fn-id)))


(defn- invalidate-fn-schema-dependents!
  "Invalidates all caches that depend on a fn-schema."
  [base-storage cache-storage fn-schema-id]
  (invalidate-dependents! base-storage cache-storage
                          #(cache/find-caches-by-fn-schema-dep % fn-schema-id)))


(defn- invalidate-arg-schema-dependents!
  "Invalidates all caches that depend on an arg-schema."
  [base-storage cache-storage arg-schema-id]
  (invalidate-dependents! base-storage cache-storage
                          #(cache/find-caches-by-arg-schema-dep % arg-schema-id)))


;; === Cache invalidation multimethods ===
;; Extensible invalidation strategies for different entity types and operations.

(defmulti invalidate-after-create!
  "Invalidates caches after entity creation.
   Dispatches on entity-name keyword."
  (fn [_base-storage _cache-storage entity-name _result] entity-name))


(defmethod invalidate-after-create! :fn
  [base-storage cache-storage _entity-name result]
  (rebuild-cache! base-storage cache-storage (:id result)))


(defmethod invalidate-after-create! :arg-value
  [base-storage cache-storage _entity-name result]
  (invalidate-fn-and-dependents! base-storage cache-storage (:owner-fn-id result)))


(defmethod invalidate-after-create! :default
  [_base-storage _cache-storage _entity-name _result]
  nil)


(defmulti invalidate-after-update!
  "Invalidates caches after entity update.
   Dispatches on entity-name keyword.
   For :fn entities, old-record contains the pre-update state."
  (fn [_base-storage _cache-storage entity-name _id _data _result _old-record] entity-name))


(defmethod invalidate-after-update! :fn
  [base-storage cache-storage _entity-name id data _result old-record]
  (when (or (contains? data :parent-fn-id)
            (not= (:fn-schema-id old-record) (:fn-schema-id data)))
    (invalidate-fn-and-dependents! base-storage cache-storage id)))


(defmethod invalidate-after-update! :arg-value
  [base-storage cache-storage _entity-name _id _data result _old-record]
  (invalidate-fn-and-dependents! base-storage cache-storage (:owner-fn-id result)))


(defmethod invalidate-after-update! :fn-schema
  [base-storage cache-storage _entity-name id _data _result _old-record]
  (invalidate-fn-schema-dependents! base-storage cache-storage id))


(defmethod invalidate-after-update! :arg-schema
  [base-storage cache-storage _entity-name id _data _result _old-record]
  (invalidate-arg-schema-dependents! base-storage cache-storage id))


(defmethod invalidate-after-update! :default
  [_base-storage _cache-storage _entity-name _id _data _result _old-record]
  nil)


(defmulti invalidate-after-delete!
  "Invalidates caches after entity deletion.
   Dispatches on entity-name keyword.
   Record contains the entity data before deletion."
  (fn [_base-storage _cache-storage entity-name _id _record] entity-name))


(defmethod invalidate-after-delete! :fn
  [base-storage cache-storage _entity-name id _record]
  (cache/delete-cache! cache-storage id)
  (invalidate-fn-and-dependents! base-storage cache-storage id))


(defmethod invalidate-after-delete! :arg-value
  [base-storage cache-storage _entity-name _id record]
  (when record
    (invalidate-fn-and-dependents! base-storage cache-storage (:owner-fn-id record))))


(defmethod invalidate-after-delete! :fn-schema
  [base-storage cache-storage _entity-name id _record]
  (invalidate-fn-schema-dependents! base-storage cache-storage id))


(defmethod invalidate-after-delete! :arg-schema
  [base-storage cache-storage _entity-name id _record]
  (invalidate-arg-schema-dependents! base-storage cache-storage id))


(defmethod invalidate-after-delete! :default
  [_base-storage _cache-storage _entity-name _id _record]
  nil)


;; === Cached Storage Record ===

(defrecord CachedStorage
  [base-storage cache-storage]

  sp/Storage

  (initialize
    [_ schema]
    (sp/initialize base-storage schema))


  (close
    [_]
    (sp/close base-storage))


  sp/StorageIntrospection

  (current-entities
    [_]
    (sp/current-entities base-storage))


  (current-fields
    [_ entity-name]
    (sp/current-fields base-storage entity-name))


  (current-enums
    [_]
    (sp/current-enums base-storage))


  (current-enum-values
    [_ enum-name]
    (sp/current-enum-values base-storage enum-name))


  (schema-metadata
    [_]
    (sp/schema-metadata base-storage))


  sp/StorageCRUD

  (create-entity
    [_ entity-name data]
    (let [result (sp/create-entity base-storage entity-name data)]
      (invalidate-after-create! base-storage cache-storage entity-name result)
      result))


  (read-entity
    [_ entity-name id]
    (sp/read-entity base-storage entity-name id))


  (update-entity
    [_ entity-name id data]
    (let [;; For fn updates, need old record to check if fn-schema-id changed
          old-record (when (= entity-name :fn)
                       (sp/read-entity base-storage entity-name id))
          result (sp/update-entity base-storage entity-name id data)]
      (invalidate-after-update! base-storage cache-storage entity-name id data result old-record)
      result))


  (delete-entity
    [_ entity-name id]
    ;; Before deletion, capture info needed for cache invalidation
    (let [record (sp/read-entity base-storage entity-name id)
          result (sp/delete-entity base-storage entity-name id)]
      (when result
        (invalidate-after-delete! base-storage cache-storage entity-name id record))
      result))


  (query-entities
    [_ entity-name where]
    (sp/query-entities base-storage entity-name where))


  sp/StorageBatchCRUD

  (create-entities
    [_ entity-name data-seq]
    (let [results (sp/create-entities base-storage entity-name data-seq)]
      ;; Invalidate caches for each created entity
      (doseq [result results]
        (invalidate-after-create! base-storage cache-storage entity-name result))
      results))


  (read-entities
    [_ entity-name ids]
    (sp/read-entities base-storage entity-name ids))


  (delete-entities
    [_ entity-name ids]
    ;; Before deletion, capture info for cache invalidation
    (let [records (sp/read-entities base-storage entity-name ids)
          result (sp/delete-entities base-storage entity-name ids)]
      (when (pos? result)
        ;; Invalidate caches for each deleted entity
        (doseq [id ids]
          (invalidate-after-delete! base-storage cache-storage entity-name id (get records id))))
      result))


  sp/GraphConstraints

  (validate-parent-same-schema!
    [_ fn-id parent-fn-id]
    (sp/validate-parent-same-schema! base-storage fn-id parent-fn-id))


  (validate-no-arg-override!
    [_ fn-id arg-schema-id]
    (sp/validate-no-arg-override! base-storage fn-id arg-schema-id))


  (validate-arg-schema-belongs-to-fn!
    [_ fn-id arg-schema-id]
    (sp/validate-arg-schema-belongs-to-fn! base-storage fn-id arg-schema-id))


  (validate-no-inheritance-cycle!
    [_ fn-id parent-fn-id]
    (sp/validate-no-inheritance-cycle! base-storage fn-id parent-fn-id))


  (validate-no-dependency-cycle!
    [_ owner-fn-id value-fn-id]
    (sp/validate-no-dependency-cycle! base-storage owner-fn-id value-fn-id))


  sp/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_ fn-id]
    (sp/get-fn-schema-id-for-fn base-storage fn-id))


  (get-fn-schema-id-for-arg-schema
    [_ arg-schema-id]
    (sp/get-fn-schema-id-for-arg-schema base-storage arg-schema-id))


  (get-parent-fn-id
    [_ fn-id]
    (sp/get-parent-fn-id base-storage fn-id))


  (collect-parent-chain
    [_ fn-id]
    (sp/collect-parent-chain base-storage fn-id))


  (collect-arg-schema-ids-in-chain
    [_ fn-id]
    (sp/collect-arg-schema-ids-in-chain base-storage fn-id))


  (collect-dependency-chain
    [_ fn-id]
    (sp/collect-dependency-chain base-storage fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_ fn-id]
    ;; Fast path: check cache first
    (if-let [cached-graph (cache/get-cached-graph cache-storage fn-id)]
      cached-graph
      ;; Cache miss: resolve using base storage, then cache
      (let [graph (sp/resolve-execution-graph base-storage fn-id)
            deps (compute-dependencies graph)]
        (cache/save-cache! cache-storage fn-id graph deps)
        graph))))


(defn wrap-with-cache
  "Wraps a storage with caching capabilities.

   Arguments:
   - base-storage: Any storage implementing GraphStorage protocols
   - cache-storage: Cache implementation (from cache-postgres, cache-memory, etc.)

   Returns a new storage that:
   - Delegates CRUD to base-storage
   - Uses cache for resolve-execution-graph
   - Invalidates cache on mutations

   Example:
   (def storage (postgres/create-storage config))
   (def cache (cache-postgres/create-cache config))
   (def cached-storage (wrap-with-cache storage cache))"
  [base-storage cache-storage]
  (->CachedStorage base-storage cache-storage))


(defn cached-storage?
  "Returns true if storage is a CachedStorage wrapper."
  [storage]
  (instance? CachedStorage storage))


(defn unwrap
  "Returns the base storage from a CachedStorage wrapper.
   Returns the storage unchanged if it's not wrapped."
  [storage]
  (if (cached-storage? storage)
    (:base-storage storage)
    storage))


(defn get-cache
  "Returns the cache-storage from a CachedStorage wrapper.
   Returns nil if storage is not wrapped."
  [storage]
  (when (cached-storage? storage)
    (:cache-storage storage)))
