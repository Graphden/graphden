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
    [clojure.tools.logging :as log]
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
  (log/debug "Rebuilding cache" {:fn-id fn-id})
  (let [graph (sp/resolve-execution-graph base-storage fn-id)
        deps (compute-dependencies graph)]
    (cache/save-cache! cache-storage fn-id graph deps)
    (log/debug "Cache rebuilt" {:fn-id fn-id
                                :fns-count (count (:fns graph))
                                :fn-schemas-count (count (:fn-schemas graph))})))


(defn- batch-delete-caches!
  "Deletes multiple caches. Returns the set of deleted cache-ids."
  [cache-storage cache-ids]
  (doseq [cache-id cache-ids]
    (cache/delete-cache! cache-storage cache-id))
  cache-ids)


(defn- batch-rebuild-existing-caches!
  "Rebuilds caches only for fns that still exist in storage.
   Uses parallel reads to check existence, then sequential rebuilds.
   Returns the set of cache-ids that were rebuilt."
  [base-storage cache-storage cache-ids]
  (when (seq cache-ids)
    ;; Batch read to check which fns exist
    (let [existing-fns (sp/read-entities base-storage :fn (vec cache-ids))
          existing-fn-ids (set (keys existing-fns))]
      (when (seq existing-fn-ids)
        (log/debug "Rebuilding caches for existing fns"
                   {:total (count cache-ids)
                    :existing (count existing-fn-ids)})
        (doseq [cache-id existing-fn-ids]
          (rebuild-cache! base-storage cache-storage cache-id)))
      existing-fn-ids)))


(defn- invalidate-dependents!
  "Invalidates all caches returned by find-fn and rebuilds them if the fn exists.
   find-fn should be a function that takes cache-storage and returns a set of cache-ids.

   Optimization: Uses batch read to check existence of all fns at once,
   then rebuilds only those that exist. This reduces N+1 queries."
  [base-storage cache-storage find-fn]
  (let [dependent-cache-ids (find-fn cache-storage)]
    (when (seq dependent-cache-ids)
      (log/debug "Invalidating dependent caches" {:count (count dependent-cache-ids)
                                                  :cache-ids dependent-cache-ids})
      ;; Phase 1: Delete all caches
      (batch-delete-caches! cache-storage dependent-cache-ids)
      ;; Phase 2: Rebuild caches for existing fns
      (batch-rebuild-existing-caches! base-storage cache-storage dependent-cache-ids))))


(defn- invalidate-fn-and-dependents!
  "Invalidates cache for fn-id and all caches that depend on it."
  [base-storage cache-storage fn-id]
  (log/debug "Invalidating fn and dependents" {:fn-id fn-id})
  ;; Invalidate and rebuild all dependent caches
  (invalidate-dependents! base-storage cache-storage
                          #(cache/find-caches-by-fn-dep % fn-id))
  ;; Rebuild cache for the fn itself if it exists
  (when (sp/read-entity base-storage :fn fn-id)
    (rebuild-cache! base-storage cache-storage fn-id)))


(defn- invalidate-fn-schema-dependents!
  "Invalidates all caches that depend on a fn-schema."
  [base-storage cache-storage fn-schema-id]
  (log/debug "Invalidating fn-schema dependents" {:fn-schema-id fn-schema-id})
  (invalidate-dependents! base-storage cache-storage
                          #(cache/find-caches-by-fn-schema-dep % fn-schema-id)))


(defn- invalidate-arg-schema-dependents!
  "Invalidates all caches that depend on an arg-schema."
  [base-storage cache-storage arg-schema-id]
  (log/debug "Invalidating arg-schema dependents" {:arg-schema-id arg-schema-id})
  (invalidate-dependents! base-storage cache-storage
                          #(cache/find-caches-by-arg-schema-dep % arg-schema-id)))


;; === Cache invalidation strategy map ===
;; Centralized invalidation strategies grouped by entity type.
;; Each entity can define :on-create, :on-update, :on-delete handlers.
;;
;; Benefits over multimethods:
;; - All strategies for an entity visible in one place
;; - Easier to see what entities have what invalidation behavior
;; - Simple to extend for new entities
;; - Data-driven: can be introspected/tested declaratively

(def ^:private invalidation-strategies
  "Map of entity-name -> {:on-create fn, :on-update fn, :on-delete fn}.

   Handler signatures:
   - :on-create (fn [base-storage cache-storage result])
   - :on-update (fn [base-storage cache-storage id data result old-record])
   - :on-delete (fn [base-storage cache-storage id record])"
  {:fn
   {:on-create
    (fn [base-storage cache-storage result]
      (rebuild-cache! base-storage cache-storage (:id result)))

    :on-update
    (fn [base-storage cache-storage id data _result old-record]
      (when (or (contains? data :parent-fn-id)
                (not= (:fn-schema-id old-record) (:fn-schema-id data)))
        (invalidate-fn-and-dependents! base-storage cache-storage id)))

    :on-delete
    (fn [base-storage cache-storage id _record]
      (cache/delete-cache! cache-storage id)
      (invalidate-fn-and-dependents! base-storage cache-storage id))}

   :arg-value
   {:on-create
    (fn [base-storage cache-storage result]
      (invalidate-fn-and-dependents! base-storage cache-storage (:owner-fn-id result)))

    :on-update
    (fn [base-storage cache-storage _id _data result _old-record]
      (invalidate-fn-and-dependents! base-storage cache-storage (:owner-fn-id result)))

    :on-delete
    (fn [base-storage cache-storage _id record]
      (when record
        (invalidate-fn-and-dependents! base-storage cache-storage (:owner-fn-id record))))}

   :fn-schema
   {:on-update
    (fn [base-storage cache-storage id _data _result _old-record]
      (invalidate-fn-schema-dependents! base-storage cache-storage id))

    :on-delete
    (fn [base-storage cache-storage id _record]
      (invalidate-fn-schema-dependents! base-storage cache-storage id))}

   :arg-schema
   {:on-update
    (fn [base-storage cache-storage id _data _result _old-record]
      (invalidate-arg-schema-dependents! base-storage cache-storage id))

    :on-delete
    (fn [base-storage cache-storage id _record]
      (invalidate-arg-schema-dependents! base-storage cache-storage id))}})


(defn- get-strategy
  "Gets invalidation strategy for entity and operation.
   Returns nil if no strategy defined (no-op)."
  [entity-name operation]
  (get-in invalidation-strategies [entity-name operation]))


(defn- invalidate-after-create!
  "Invokes :on-create strategy for entity if defined."
  [base-storage cache-storage entity-name result]
  (when-let [handler (get-strategy entity-name :on-create)]
    (handler base-storage cache-storage result)))


(defn- invalidate-after-update!
  "Invokes :on-update strategy for entity if defined."
  [base-storage cache-storage entity-name id data result old-record]
  (when-let [handler (get-strategy entity-name :on-update)]
    (handler base-storage cache-storage id data result old-record)))


(defn- invalidate-after-delete!
  "Invokes :on-delete strategy for entity if defined."
  [base-storage cache-storage entity-name id record]
  (when-let [handler (get-strategy entity-name :on-delete)]
    (handler base-storage cache-storage id record)))


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
