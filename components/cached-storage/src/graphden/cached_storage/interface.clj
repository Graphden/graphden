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

   Cache invalidation is data-driven via a rule registry in
   `graphden.cached-storage.invalidation`. Rules are matched by entity type
   and event type (:create, :update, :delete). Default rules are registered
   at namespace load time. Extensions can register additional rules.

   ### Invalidation Flow

   ```
   MUTATION EVENT
        │
        ▼
   ┌──────────────────┐
   │ Find matching    │ ◄── invalidation rule registry
   │ rules by entity  │
   │ type + event     │
   └──────┬───────────┘
          │
          ▼
   ┌─────────────────┐    no rules
   │ Rules found?    │───────────────► NO-OP (passthrough)
   └──────┬──────────┘
          │ yes
          ▼
   ┌──────────────────┐
   │ Execute rules    │
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
   A: Check that entity type has rules in the invalidation registry.
      Only :fn, :arg-value, :fn-schema, :arg-schema, :call-site trigger invalidation.

   **Q: Performance degradation after large batch update**
   A: Batch updates invalidate caches one-by-one. For bulk migrations,
      consider clearing all caches first, then rebuilding lazily.

   **Q: Cache not rebuilt after invalidation**
   A: Caches are only rebuilt for fns that exist. If fn was deleted
      during invalidation, its cache is not rebuilt (expected behavior)."
  (:require
    [graphden.cache-protocol.interface :as cache]
    [graphden.cached-storage.invalidation :as inv]
    [graphden.storage-protocol.interface :as sp]))


(defn- invalidate-after-create!
  "Invokes :create rules for entity if defined."
  [base-storage cache-storage entity-name result]
  (inv/process-invalidation! entity-name :create
                             {:base-storage base-storage
                              :cache-storage cache-storage
                              :entity-name entity-name
                              :result result}))


(defn- invalidate-after-update!
  "Invokes :update rules for entity if defined."
  [base-storage cache-storage entity-name id data result old-record]
  (inv/process-invalidation! entity-name :update
                             {:base-storage base-storage
                              :cache-storage cache-storage
                              :entity-name entity-name
                              :id id
                              :data data
                              :result result
                              :old-record old-record}))


(defn- invalidate-after-delete!
  "Invokes :delete rules for entity if defined."
  [base-storage cache-storage entity-name id record]
  (inv/process-invalidation! entity-name :delete
                             {:base-storage base-storage
                              :cache-storage cache-storage
                              :entity-name entity-name
                              :id id
                              :record record}))


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


  sp/ExecutionGraph

  (resolve-execution-graph
    [_ fn-id]
    ;; Fast path: check cache first
    (if-let [cached-graph (cache/get-cached-graph cache-storage fn-id)]
      ;; Cache hit: need to load call-sites since they're not stored in cache
      ;; The cached graph has resolved-args but no call-sites map
      ;; We need call-sites so executor can distinguish call-site refs from fn refs
      (if (empty? (:call-sites cached-graph))
        (let [call-site-ids (inv/extract-call-site-ids-from-resolved-args (:resolved-args cached-graph))
              call-sites (if (empty? call-site-ids)
                           {}
                           (sp/read-entities base-storage :call-site (vec call-site-ids)))]
          (assoc cached-graph :call-sites call-sites))
        cached-graph)
      ;; Cache miss: resolve using base storage, then cache
      (let [graph (sp/resolve-execution-graph base-storage fn-id)
            deps (inv/compute-dependencies graph)]
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
    (when (inv/has-strategy? entity-name :create)
      (inc-invalidations! metrics))
    (sp/create-entity delegate entity-name data))


  (read-entity
    [_ entity-name id]
    (sp/read-entity delegate entity-name id))


  (update-entity
    [_ entity-name id data]
    (when (inv/has-strategy? entity-name :update)
      (inc-invalidations! metrics))
    (sp/update-entity delegate entity-name id data))


  (delete-entity
    [_ entity-name id]
    (let [result (sp/delete-entity delegate entity-name id)]
      (when (and result (inv/has-strategy? entity-name :delete))
        (inc-invalidations! metrics))
      result))


  (query-entities
    [_ entity-name where]
    (sp/query-entities delegate entity-name where))


  sp/StorageBatchCRUD

  (create-entities
    [_ entity-name data-seq]
    (let [results (sp/create-entities delegate entity-name data-seq)]
      (when (and (seq results) (inv/has-strategy? entity-name :create))
        (dotimes [_ (count results)]
          (inc-invalidations! metrics)))
      results))


  (read-entities
    [_ entity-name ids]
    (sp/read-entities delegate entity-name ids))


  (delete-entities
    [_ entity-name ids]
    (let [result (sp/delete-entities delegate entity-name ids)]
      (when (and (pos? result) (inv/has-strategy? entity-name :delete))
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


  sp/ExecutionGraph

  (resolve-execution-graph
    [_ fn-id]
    ;; This is the only method that differs: track hits/misses
    (let [cache-storage (:cache-storage delegate)
          base-storage (:base-storage delegate)]
      (if-let [cached-graph (cache/get-cached-graph cache-storage fn-id)]
        (do
          (inc-hits! metrics)
          ;; Cache hit: need to load call-sites since they're not stored in cache
          (if (empty? (:call-sites cached-graph))
            (let [call-site-ids (inv/extract-call-site-ids-from-resolved-args (:resolved-args cached-graph))
                  call-sites (if (empty? call-site-ids)
                               {}
                               (sp/read-entities base-storage :call-site (vec call-site-ids)))]
              (assoc cached-graph :call-sites call-sites))
            cached-graph))
        (do
          (inc-misses! metrics)
          (let [graph (sp/resolve-execution-graph base-storage fn-id)
                deps (inv/compute-dependencies graph)]
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
