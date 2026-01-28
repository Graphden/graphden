(ns graphden.cached-storage.interface
  "Decorator that wraps any storage with execution graph caching.

   ## Usage

   (def storage (postgres/create-storage config))
   (def cache (cache-postgres/create-cache config))
   (def cached (wrap-with-cache storage cache))

   The wrapped storage:
   - Delegates all CRUD operations to the base storage
   - Uses cache for resolve-execution-graph (O(1) instead of O(depth))
   - Invalidates affected caches after mutations

   ## Cache Invalidation Strategy

   Cache invalidation is data-driven via `invalidation-strategies` map.
   Each entity type defines handlers for :on-create, :on-update, :on-delete.

   ### Invalidation Flow (ASCII State Diagram)

   ```
   MUTATION EVENT
        │
        ▼
   ┌─────────────┐
   │ Get Strategy │ ◄── invalidation-strategies map
   │ for Entity   │     {:fn {:on-create ...} ...}
   └──────┬──────┘
          │
          ▼
   ┌─────────────────┐    no strategy
   │ Strategy exists? │───────────────► NO-OP (passthrough)
   └──────┬──────────┘
          │ yes
          ▼
   ┌──────────────────┐
   │ Execute Strategy │
   │ (invalidate/     │
   │  rebuild caches) │
   └──────┬───────────┘
          │
          ▼
   ┌─────────────────────┐
   │ find-caches-by-*-dep│ ◄── Find all dependent caches
   └──────┬──────────────┘
          │
          ▼
   ┌─────────────────┐     ┌──────────────────┐
   │ batch-delete-   │────►│ batch-rebuild-   │
   │ caches!         │     │ existing-caches! │
   └─────────────────┘     └──────────────────┘
   ```

   ### Entity-Specific Strategies

   | Entity          | Create              | Update                       | Delete                  |
   |-----------------|---------------------|------------------------------|-------------------------|
   | :fn             | rebuild own cache   | if fn-schema-id changed:     | delete + invalidate     |
   |                 |                     | invalidate fn + dependents   | all dependents          |
   | :fn-arg         | invalidate fn       | invalidate fn                | invalidate fn           |
   |                 | + dependents        | + dependents                 | + dependents            |
   | :arg-value      | no-op               | find fn-args, invalidate fns | find fn-args, invalidate|
   | :fn-schema      | no-op               | invalidate all fns using it  | invalidate all dependents|
   | :arg-schema     | no-op               | invalidate all fns using it  | invalidate all dependents|
   | :call-site| invalidate deps     | invalidate all dependents    | invalidate all dependents|
   | :call-site-arg  | invalidate cs deps  | invalidate cs dependents     | invalidate cs deps      |

   ### Complex Scenarios

   **Scenario 1: arg-value changed for function**
   ```
   fn-A (has arg-value) ──► fn-B (references fn-A)

   CREATE/UPDATE/DELETE arg-value for fn-A
   1. Invalidate fn-A cache
   2. Find caches depending on fn-A (fn-B)
   3. Invalidate fn-B cache
   4. Rebuild fn-A, fn-B (if they exist)
   ```

   **Scenario 2: fn-schema updated**
   ```
   fn-schema-1 used by fn-A, fn-B, fn-C

   UPDATE fn-schema-1
   1. Find all caches depending on fn-schema-1
   2. Delete all found caches
   3. Rebuild caches for fns that still exist
   ```

   **Scenario 3: fn references another fn**
   ```
   fn-A (referenced by arg-value of fn-B)

   DELETE fn-A
   1. Delete cache for fn-A
   2. Find caches depending on fn-A (fn-B)
   3. Invalidate fn-B cache
   4. Rebuild fn-B (if it still exists)
   ```

   ### Consistency Guarantees

   - **Atomicity**: Each mutation triggers invalidation before returning
   - **No stale reads**: After write returns, subsequent reads see fresh data
   - **Cascading**: Dependencies are followed transitively (fn → child-fn → grandchild-fn)

   ### Troubleshooting

   **Q: Cache returns stale data after update**
   A: Check that entity type has strategy in `invalidation-strategies` map.
      Only :fn, :arg-value, :fn-schema, :arg-schema, :call-site trigger invalidation.

   **Q: Performance degradation after large batch update**
   A: Batch updates invalidate caches one-by-one. For bulk migrations,
      consider clearing all caches first, then rebuilding lazily.

   **Q: Cache not rebuilt after invalidation**
   A: Caches are only rebuilt for fns that exist. If fn was deleted
      during invalidation, its cache is not rebuilt (expected behavior)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


;; === Cache dependency computation ===

(defn- compute-dependencies
  "Computes dependency counts from an execution graph.
   Returns {:fn-ids {fn-id -> count}
            :fn-schema-ids {schema-id -> count}
            :arg-schema-ids {arg-schema-id -> count}
            :call-site-ids {call-site-id -> count}}"
  [graph]
  {:fn-ids (frequencies (keys (:fns graph)))
   :fn-schema-ids (frequencies (keys (:fn-schemas graph)))
   :arg-schema-ids (frequencies (keys (:arg-schemas graph)))
   :call-site-ids (frequencies (keys (:call-sites graph)))})


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


(defn- invalidate-call-site-dependents!
  "Invalidates all caches that depend on a call-site."
  [base-storage cache-storage call-site-id]
  (log/debug "Invalidating call-site dependents" {:call-site-id call-site-id})
  (invalidate-dependents! base-storage cache-storage
                          #(cache/find-caches-by-call-site-dep % call-site-id)))


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
      ;; Invalidate if fn-schema-id changed (rare but affects execution graph)
      (when (and (contains? data :fn-schema-id)
                 (not= (:fn-schema-id old-record) (:fn-schema-id data)))
        (invalidate-fn-and-dependents! base-storage cache-storage id)))

    :on-delete
    (fn [base-storage cache-storage id _record]
      (cache/delete-cache! cache-storage id)
      (invalidate-fn-and-dependents! base-storage cache-storage id))}

   ;; arg-value is now a pure value (no owner-fn-id)
   ;; To find affected fns, we query fn-arg bindings that reference this arg-value
   :arg-value
   {:on-create
    (fn [_base-storage _cache-storage _result]
      ;; arg-value creation alone doesn't affect caches
      ;; (the fn-arg binding creation will trigger invalidation)
      nil)

    :on-update
    (fn [base-storage cache-storage id _data _result _old-record]
      ;; Find all fn-arg bindings that reference this arg-value and invalidate those fns
      (let [fn-args (sp/query-entities base-storage :fn-arg {:arg-value-id id})]
        (doseq [fn-arg fn-args]
          (invalidate-fn-and-dependents! base-storage cache-storage (:fn-id fn-arg)))))

    :on-delete
    (fn [base-storage cache-storage id _record]
      ;; Find all fn-arg bindings that referenced this arg-value
      ;; Note: fn-arg records should be cascade-deleted first, but we check anyway
      (let [fn-args (sp/query-entities base-storage :fn-arg {:arg-value-id id})]
        (doseq [fn-arg fn-args]
          (invalidate-fn-and-dependents! base-storage cache-storage (:fn-id fn-arg)))))}

   ;; fn-arg is the binding from fn to arg-value
   ;; When fn-arg changes, invalidate the owning fn
   :fn-arg
   {:on-create
    (fn [base-storage cache-storage result]
      (invalidate-fn-and-dependents! base-storage cache-storage (:fn-id result)))

    :on-update
    (fn [base-storage cache-storage _id _data result _old-record]
      (invalidate-fn-and-dependents! base-storage cache-storage (:fn-id result)))

    :on-delete
    (fn [base-storage cache-storage _id record]
      (when record
        (invalidate-fn-and-dependents! base-storage cache-storage (:fn-id record))))}

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
      (invalidate-arg-schema-dependents! base-storage cache-storage id))}

   :call-site
   {:on-create
    (fn [base-storage cache-storage result]
      (invalidate-call-site-dependents! base-storage cache-storage (:id result)))

    :on-update
    (fn [base-storage cache-storage id _data _result _old-record]
      (invalidate-call-site-dependents! base-storage cache-storage id))

    :on-delete
    (fn [base-storage cache-storage id _record]
      (invalidate-call-site-dependents! base-storage cache-storage id))}

   ;; call-site-arg is the binding from call-site to arg-value
   ;; When call-site-arg changes, invalidate dependents of the call-site
   :call-site-arg
   {:on-create
    (fn [base-storage cache-storage result]
      (invalidate-call-site-dependents! base-storage cache-storage (:call-site-id result)))

    :on-update
    (fn [base-storage cache-storage _id _data result _old-record]
      (invalidate-call-site-dependents! base-storage cache-storage (:call-site-id result)))

    :on-delete
    (fn [base-storage cache-storage _id record]
      (when record
        (invalidate-call-site-dependents! base-storage cache-storage (:call-site-id record))))}})


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

  (validate-arg-schema-belongs-to-fn!
    [_ fn-id arg-schema-id]
    (sp/validate-arg-schema-belongs-to-fn! base-storage fn-id arg-schema-id))


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


;; Forward declarations for unwrap/get-cache (CachedStorageWithMetrics defined below)
(declare unwrap get-cache)


;; === Cache Metrics ===

(defrecord CacheMetrics
  [hits misses invalidations])


(defn create-metrics
  "Creates a new metrics tracker for cache operations.
   Returns an atom containing {:hits 0 :misses 0 :invalidations 0}."
  []
  (atom (->CacheMetrics 0 0 0)))


(defn get-metrics
  "Returns current metrics snapshot as a map.
   Includes :hits, :misses, :invalidations, :hit-rate."
  [metrics]
  (let [{:keys [hits misses invalidations]} @metrics
        total (+ hits misses)
        hit-rate (if (pos? total) (double (/ hits total)) 0.0)]
    {:hits hits
     :misses misses
     :invalidations invalidations
     :total-requests total
     :hit-rate hit-rate}))


(defn reset-metrics!
  "Resets all metrics counters to zero. Returns previous metrics."
  [metrics]
  (let [prev @metrics]
    (reset! metrics (->CacheMetrics 0 0 0))
    {:hits (:hits prev)
     :misses (:misses prev)
     :invalidations (:invalidations prev)}))


(defn- inc-hits!
  [metrics]
  (swap! metrics update :hits inc))


(defn- inc-misses!
  [metrics]
  (swap! metrics update :misses inc))


(defn- inc-invalidations!
  [metrics]
  (swap! metrics update :invalidations inc))


;; === Cached Storage with Metrics ===
;; Uses composition: wraps CachedStorage and adds metrics tracking.
;; This eliminates 180+ lines of duplicated protocol implementations.

(defrecord CachedStorageWithMetrics
  [delegate metrics]
  ;; delegate is a CachedStorage instance

  sp/Storage

  (initialize [_ schema] (sp/initialize delegate schema))


  (close [_] (sp/close delegate))


  sp/StorageIntrospection

  (current-entities [_] (sp/current-entities delegate))


  (current-fields [_ entity-name] (sp/current-fields delegate entity-name))


  (current-enums [_] (sp/current-enums delegate))


  (current-enum-values [_ enum-name] (sp/current-enum-values delegate enum-name))


  (schema-metadata [_] (sp/schema-metadata delegate))


  sp/StorageCRUD

  (create-entity
    [_ entity-name data]
    (when (get-strategy entity-name :on-create)
      (inc-invalidations! metrics))
    (sp/create-entity delegate entity-name data))


  (read-entity
    [_ entity-name id]
    (sp/read-entity delegate entity-name id))


  (update-entity
    [_ entity-name id data]
    (when (get-strategy entity-name :on-update)
      (inc-invalidations! metrics))
    (sp/update-entity delegate entity-name id data))


  (delete-entity
    [_ entity-name id]
    (let [result (sp/delete-entity delegate entity-name id)]
      (when (and result (get-strategy entity-name :on-delete))
        (inc-invalidations! metrics))
      result))


  (query-entities
    [_ entity-name where]
    (sp/query-entities delegate entity-name where))


  sp/StorageBatchCRUD

  (create-entities
    [_ entity-name data-seq]
    (let [results (sp/create-entities delegate entity-name data-seq)]
      (when (and (seq results) (get-strategy entity-name :on-create))
        (dotimes [_ (count results)]
          (inc-invalidations! metrics)))
      results))


  (read-entities
    [_ entity-name ids]
    (sp/read-entities delegate entity-name ids))


  (delete-entities
    [_ entity-name ids]
    (let [result (sp/delete-entities delegate entity-name ids)]
      (when (and (pos? result) (get-strategy entity-name :on-delete))
        (dotimes [_ result]
          (inc-invalidations! metrics)))
      result))


  sp/GraphConstraints

  (validate-arg-schema-belongs-to-fn!
    [_ fn-id arg-schema-id]
    (sp/validate-arg-schema-belongs-to-fn! delegate fn-id arg-schema-id))


  (validate-no-dependency-cycle!
    [_ owner-fn-id value-fn-id]
    (sp/validate-no-dependency-cycle! delegate owner-fn-id value-fn-id))


  sp/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_ fn-id]
    (sp/get-fn-schema-id-for-fn delegate fn-id))


  (get-fn-schema-id-for-arg-schema
    [_ arg-schema-id]
    (sp/get-fn-schema-id-for-arg-schema delegate arg-schema-id))


  (collect-dependency-chain
    [_ fn-id]
    (sp/collect-dependency-chain delegate fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_ fn-id]
    ;; This is the only method that differs: track hits/misses
    (let [cache-storage (:cache-storage delegate)
          base-storage (:base-storage delegate)]
      (if-let [cached-graph (cache/get-cached-graph cache-storage fn-id)]
        (do
          (inc-hits! metrics)
          cached-graph)
        (do
          (inc-misses! metrics)
          (let [graph (sp/resolve-execution-graph base-storage fn-id)
                deps (compute-dependencies graph)]
            (cache/save-cache! cache-storage fn-id graph deps)
            graph))))))


(defn wrap-with-cache-and-metrics
  "Wraps a storage with caching and metrics tracking.

   Arguments:
   - base-storage: Any storage implementing GraphStorage protocols
   - cache-storage: Cache implementation
   - metrics: Metrics atom from create-metrics (optional, creates new if nil)

   Returns a new storage that:
   - Delegates CRUD to base-storage
   - Uses cache for resolve-execution-graph
   - Invalidates cache on mutations
   - Tracks cache hit/miss/invalidation metrics

   Example:
   (def metrics (create-metrics))
   (def cached-storage (wrap-with-cache-and-metrics storage cache metrics))
   ;; Later:
   (get-metrics metrics) ; => {:hits 10 :misses 2 :hit-rate 0.833...}"
  ([base-storage cache-storage]
   (wrap-with-cache-and-metrics base-storage cache-storage (create-metrics)))
  ([base-storage cache-storage metrics]
   ;; Compose: first wrap with caching, then add metrics
   (let [cached (->CachedStorage base-storage cache-storage)]
     (->CachedStorageWithMetrics cached (or metrics (create-metrics))))))


(defn get-storage-metrics
  "Returns metrics from a CachedStorageWithMetrics wrapper.
   Returns nil if storage doesn't have metrics."
  [storage]
  (when (instance? CachedStorageWithMetrics storage)
    (get-metrics (:metrics storage))))


;; === Utility functions (after CachedStorageWithMetrics is defined) ===

(defn unwrap
  "Returns the base storage from a CachedStorage or CachedStorageWithMetrics wrapper.
   Returns the storage unchanged if it's not wrapped."
  [storage]
  (cond
    (instance? CachedStorageWithMetrics storage)
    (:base-storage (:delegate storage))

    (cached-storage? storage)
    (:base-storage storage)

    :else storage))


(defn get-cache
  "Returns the cache-storage from a CachedStorage or CachedStorageWithMetrics wrapper.
   Returns nil if storage is not wrapped."
  [storage]
  (cond
    (instance? CachedStorageWithMetrics storage)
    (:cache-storage (:delegate storage))

    (cached-storage? storage)
    (:cache-storage storage)))
