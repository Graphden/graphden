(ns graphden.storage.postgres.core
  "PostgreSQL implementation of Storage protocol.

   This module provides the main Storage record.
   DDL, migration, and metadata operations are delegated to sub-modules:
   - pool.clj - Connection pool management
   - util.clj - Type mapping and SQL helpers
   - metadata.clj - Metadata table operations
   - introspection.clj - Database introspection
   - ddl.clj - DDL operations (CREATE/ALTER)
   - migration.clj - Schema migration logic
   - crud.clj - CRUD operations"
  (:require
    [graphden.storage.postgres.crud :as crud]
    [graphden.storage.postgres.graph :as graph]
    [graphden.storage.postgres.introspection :as introspection]
    [graphden.storage.postgres.junction :as junction]
    [graphden.storage.postgres.metadata :as metadata]
    [graphden.storage.postgres.migration :as migration]
    [graphden.storage.postgres.pool :as pool]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-constraints :as gc])
  (:import
    (java.sql
      SQLException)
    (java.util.concurrent.locks
      ReentrantReadWriteLock)))


;; === Pool re-exports (for API compatibility) ===

(def create-pool
  "Creates a HikariCP connection pool. See pool/create-pool for details."
  pool/create-pool)


(def close-pool
  "Closes a HikariCP connection pool. See pool/close-pool for details."
  pool/close-pool)


(def with-query-timeout
  "Executes f with a custom query timeout (in milliseconds).
   See util/with-query-timeout for details."
  util/with-query-timeout)


;; === Storage record ===

(defn- build-entity-fields-index
  "Builds an index of entity-name -> field-specs from raw metadata.
   This is O(n) once at cache time instead of O(n) on every CRUD call.
   Returns {entity-name {field-name {:type t :nullable? n :enum-name e}}}."
  [cached-metadata]
  (when cached-metadata
    (->> (:fields cached-metadata)
         vals
         (group-by :entity)
         (reduce-kv
           (fn [acc entity-name fields]
             (assoc acc entity-name
                    (into {}
                          (map (fn [{:keys [field nullable? enum-name] field-type :type}]
                                 [field (cond-> {:type field-type :nullable? nullable?}
                                          enum-name (assoc :enum-name enum-name))])
                               fields))))
           {}))))


(defn- get-cached-metadata
  "Gets metadata from cache or reads from database.
   Thread-safe: uses ReentrantReadWriteLock for concurrent read access.
   Write lock is only acquired when cache needs to be populated.
   Cache is invalidated on schema changes (initialize).

   ## Double-Check Locking Pattern

   State machine:
   ```
   [nil] ---(first access)---> [loading] ---(success)---> [cached]
     ^                            |                           |
     |                            v                           |
     +-------(table not found)----+                           |
     +-------(initialize called)------------------------------+
   ```

   Thread safety:
   1. Fast path: Read @metadata-cache without lock (safe for concurrent reads)
   2. Slow path: Acquire write lock, double-check cache (another thread may have populated it)
   3. On success: Cache result; on table-not-found: return nil without caching

   NOTE: Returns nil without caching if table not found (not initialized yet).
   This prevents caching stale nil values during initialization race conditions.

   Cache safety: reset! only happens after parse-metadata-lenient successfully returns.
   If parsing throws, cache remains unchanged (Clojure let-binding evaluation order).

   Optimization: Adds :fields-by-entity index for O(1) entity field lookups."
  [pool metadata-cache ^ReentrantReadWriteLock rw-lock]
  ;; Fast path: check cache without lock
  (or @metadata-cache
      ;; Slow path: acquire write lock to populate cache
      ;; Note: We use write lock here because we need to modify cache.
      ;; This is rare (only on first access or after cache invalidation).
      (sp/with-write-lock rw-lock
                          (fn []
                            ;; Double-check after acquiring lock
                            (or @metadata-cache
                                (try
                                  ;; Parse first, cache only on success (let ensures order)
                                  (let [raw-metadata (metadata/parse-metadata-lenient (metadata/read-metadata-rows pool))
                                        ;; Add entity->fields index for O(1) lookups
                                        result (when raw-metadata
                                                 (assoc raw-metadata
                                                        :fields-by-entity (build-entity-fields-index raw-metadata)))]
                                    (reset! metadata-cache result)
                                    result)
                                  (catch SQLException e
                                    ;; Don't cache nil - table might be created soon
                                    (when-not (util/table-not-found? e)
                                      (throw e)))))))))


(defn- get-entity-fields
  "Gets field specs for an entity using cached index.
   O(1) lookup via pre-built :fields-by-entity index."
  [pool metadata-cache rw-lock entity-name]
  (when-let [cached-metadata (get-cached-metadata pool metadata-cache rw-lock)]
    (get (:fields-by-entity cached-metadata) entity-name)))


(defrecord PostgresStorage
  [pool metadata-cache ^ReentrantReadWriteLock rw-lock]

  sp/Storage

  (initialize
    [_this schema]
    (sp/with-write-lock rw-lock
                        (fn []
                          ;; Invalidate cache BEFORE migration to prevent stale reads.
                          ;;
                          ;; Why invalidate before, not after?
                          ;; - If we invalidate after and migration partially fails (DDL succeeds
                          ;;   but metadata update fails), concurrent readers during recovery
                          ;;   would still see old cached metadata while DB has new schema.
                          ;; - By invalidating before, any read during/after migration will
                          ;;   fetch fresh state from DB, ensuring consistency.
                          ;; - The write lock prevents concurrent reads during the critical section,
                          ;;   so this is safe even with eager invalidation.
                          ;;
                          ;; If migration throws, cache stays nil - next read refreshes from DB.
                          ;; This is correct: DB state is authoritative, cache is just optimization.
                          (reset! metadata-cache nil)
                          (migration/do-initialize pool schema))))


  (close
    [_this]
    (sp/with-write-lock rw-lock
                        (fn []
                          ;; Clear cache first to prevent memory leak from stale references
                          (reset! metadata-cache nil)
                          (pool/close-pool pool)))
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (into #{} (map util/snake->kw) (introspection/current-tables pool)))


  (current-fields
    [_this entity-name]
    ;; O(1) lookup via pre-built :fields-by-entity index
    ;; Index already contains {field-name {:type t :nullable? n}} format
    ;; get returns nil if entity doesn't exist - no redundant check needed
    (when-let [cached-metadata (get-cached-metadata pool metadata-cache rw-lock)]
      (get (:fields-by-entity cached-metadata) entity-name)))


  (current-enums
    [_this]
    (into #{} (map util/snake->kw) (introspection/current-pg-enums pool)))


  (current-enum-values
    [_this enum-name]
    (let [enum-vals (introspection/current-enum-values-pg pool (util/kw->snake-case enum-name))]
      (when (seq enum-vals) enum-vals)))


  (schema-metadata
    [_this]
    (get-cached-metadata pool metadata-cache rw-lock))


  sp/StorageCRUD

  (create-entity
    [_this entity-name data]
    (crud/create-entity pool entity-name data
                        (get-entity-fields pool metadata-cache rw-lock entity-name)))


  (read-entity
    [_this entity-name id]
    (crud/read-entity pool entity-name id
                      (get-entity-fields pool metadata-cache rw-lock entity-name)))


  (update-entity
    [_this entity-name id data]
    (crud/update-entity pool entity-name id data
                        (get-entity-fields pool metadata-cache rw-lock entity-name)))


  (delete-entity
    [_this entity-name id]
    (crud/delete-entity pool entity-name id))


  (query-entities
    [_this entity-name where]
    (crud/query-entities pool entity-name where
                         (get-entity-fields pool metadata-cache rw-lock entity-name)))


  (query-entities
    [_this entity-name where opts]
    (crud/query-entities pool entity-name where
                         (get-entity-fields pool metadata-cache rw-lock entity-name)
                         opts))


  sp/StorageBatchCRUD

  (create-entities
    [_this entity-name data-seq]
    (crud/create-entities pool entity-name data-seq
                          (get-entity-fields pool metadata-cache rw-lock entity-name)))


  (read-entities
    [_this entity-name ids]
    (crud/read-entities pool entity-name ids
                        (get-entity-fields pool metadata-cache rw-lock entity-name)))


  (update-entities
    [_this entity-name data-seq]
    (crud/update-entities pool entity-name data-seq
                          (get-entity-fields pool metadata-cache rw-lock entity-name)))


  (upsert-entities
    [_this entity-name data-seq]
    (crud/upsert-entities pool entity-name data-seq
                          (get-entity-fields pool metadata-cache rw-lock entity-name)))


  (delete-entities
    [_this entity-name ids]
    (crud/delete-entities pool entity-name ids))


  (query-ref-many-owners
    [_this entity-name field-name target-id]
    (junction/read-junction-owners pool entity-name field-name target-id))


  sp/GraphConstraints

  (validate-no-dependency-cycle!
    [this owner-fn-id ref-fn-id]
    (gc/validate-no-dependency-cycle! this owner-fn-id ref-fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [this fn-id]
    (graph/resolve-execution-graph pool this fn-id))


  sp/StorageErrorClassifier

  (classify-error
    [_this exception]
    (sp/classify-error (util/create-error-classifier) exception))


  (wrap-error
    [_this exception operation context]
    (sp/wrap-error (util/create-error-classifier) exception operation context)))


(defn create-storage
  "Creates a new PostgreSQL storage instance.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username
   - :password - database password
   - :pool-size - maximum pool size (default 10)
   - :min-idle - minimum idle connections (default 2)
   - :connection-timeout - connection timeout in ms (default 30000)
   - :idle-timeout - idle connection timeout in ms (default 600000)
   - :max-lifetime - maximum connection lifetime in ms (default 1800000)

   Thread safety:
   - Uses ReentrantReadWriteLock for metadata cache access
   - Multiple concurrent reads allowed, writes are exclusive
   - CRUD operations use PostgreSQL's own transaction isolation"
  [opts]
  (->PostgresStorage (pool/create-pool opts) (atom nil) (ReentrantReadWriteLock.)))
