(ns graphden.postgres-storage.core
  "PostgreSQL implementation of Storage protocol.

   This module provides connection pool management and the main Storage record.
   DDL, migration, and metadata operations are delegated to sub-modules:
   - util.clj - Type mapping and SQL helpers
   - metadata.clj - Metadata table operations
   - introspection.clj - Database introspection
   - ddl.clj - DDL operations (CREATE/ALTER)
   - migration.clj - Schema migration logic
   - crud.clj - CRUD operations
   - constraints.clj - Graph constraints validation"
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.postgres-storage.constraints :as constraints]
    [graphden.postgres-storage.crud :as crud]
    [graphden.postgres-storage.graph :as graph]
    [graphden.postgres-storage.introspection :as introspection]
    [graphden.postgres-storage.metadata :as metadata]
    [graphden.postgres-storage.migration :as migration]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (com.zaxxer.hikari
      HikariConfig
      HikariDataSource)
    (java.sql
      SQLException)
    (java.util.concurrent.locks
      ReentrantReadWriteLock)))


;; === Connection pool ===

(defn- validate-pool-options!
  "Validates connection pool options. Throws on invalid configuration.
   Extracted for clarity and testability."
  [{:keys [jdbc-url username password pool-size min-idle
           connection-timeout idle-timeout max-lifetime]}]
  ;; Required fields
  (when-not jdbc-url
    (throw (ex-info "jdbc-url is required for postgres connection pool"
                    {:type :config-error/missing-jdbc-url})))
  (when-not (string? jdbc-url)
    (throw (ex-info "jdbc-url must be a string"
                    {:type :config-error/invalid-jdbc-url
                     :jdbc-url-type (type jdbc-url)})))
  (when-not (str/starts-with? jdbc-url "jdbc:postgresql://")
    (throw (ex-info "jdbc-url must start with 'jdbc:postgresql://' for PostgreSQL connections"
                    {:type :config-error/invalid-jdbc-url
                     :hint "Expected format: jdbc:postgresql://host:port/database"})))
  (when-not (and username (seq (str/trim username)))
    (throw (ex-info "username is required and cannot be empty"
                    {:type :config-error/missing-username})))
  (when-not (and password (seq (str/trim password)))
    (throw (ex-info "password is required and cannot be empty"
                    {:type :config-error/missing-password})))
  ;; Security: validate credential lengths and content
  (sp/validate-jdbc-url! jdbc-url)
  (sp/validate-credentials! username password)
  ;; Pool size configuration
  (when-not (pos-int? pool-size)
    (throw (ex-info "pool-size must be a positive integer"
                    {:type :config-error/invalid-pool-size
                     :pool-size pool-size})))
  (when (> pool-size 100)
    (throw (ex-info "pool-size exceeds maximum allowed value of 100"
                    {:type :config-error/invalid-pool-size
                     :pool-size pool-size
                     :max-allowed 100})))
  (when-not (pos-int? min-idle)
    (throw (ex-info "min-idle must be a positive integer"
                    {:type :config-error/invalid-min-idle
                     :min-idle min-idle})))
  (when (> min-idle pool-size)
    (throw (ex-info "min-idle cannot exceed pool-size"
                    {:type :config-error/invalid-pool-config
                     :min-idle min-idle
                     :pool-size pool-size})))
  ;; Timeout configuration
  (when-not (pos-int? connection-timeout)
    (throw (ex-info "connection-timeout must be a positive integer (ms)"
                    {:type :config-error/invalid-timeout
                     :connection-timeout connection-timeout})))
  (when (and (pos? idle-timeout) (>= idle-timeout max-lifetime))
    (throw (ex-info "idle-timeout must be less than max-lifetime"
                    {:type :config-error/invalid-pool-config
                     :idle-timeout idle-timeout
                     :max-lifetime max-lifetime}))))


(defn create-pool
  "Creates a HikariCP connection pool.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username (required, non-empty)
   - :password - database password (required, non-empty)
   - :pool-size - maximum pool size (default 10)
   - :min-idle - minimum idle connections (default 2)
   - :connection-timeout - connection timeout in ms (default 30000)
   - :idle-timeout - idle connection timeout in ms (default 600000)
   - :max-lifetime - maximum connection lifetime in ms (default 1800000)
   - :leak-detection-threshold - connection leak detection in ms (default 60000)

   Tuning Guidelines:
   - pool-size: Start with (2 * CPU cores) + effective_spindle_count for OLTP workloads.
     For most cloud databases, 10-20 is a good starting point. Larger pools don't always
     mean better performance - see HikariCP's 'About Pool Sizing' documentation.
   - min-idle: Set equal to pool-size for consistent latency, or lower (e.g., 2) to
     reduce idle resource usage. HikariCP recommends keeping min-idle = pool-size.
   - connection-timeout: How long to wait for a connection from the pool. 30s is
     generous; reduce to 5-10s for faster failure detection in high-load scenarios.
   - idle-timeout: Connections idle longer than this are retired. Must be less than
     max-lifetime. Set to 0 to never retire idle connections (not recommended).
   - max-lifetime: Maximum connection lifetime. Should be several minutes less than
     database/infrastructure timeout (e.g., PostgreSQL wait_timeout, load balancer idle).
   - leak-detection-threshold: Log warning if connection not returned within this time.
     Set to 0 to disable. Good for development; consider disabling in production."
  [{:keys [jdbc-url username password pool-size min-idle
           connection-timeout idle-timeout max-lifetime leak-detection-threshold]
    :or {pool-size 10
         min-idle 2
         connection-timeout 30000
         idle-timeout 600000
         max-lifetime 1800000
         leak-detection-threshold 60000}}]
  ;; Construct opts map with defaults applied for validation
  (let [opts {:jdbc-url jdbc-url
              :username username
              :password password
              :pool-size pool-size
              :min-idle min-idle
              :connection-timeout connection-timeout
              :idle-timeout idle-timeout
              :max-lifetime max-lifetime
              :leak-detection-threshold leak-detection-threshold}]
    (validate-pool-options! opts)
    (log/info "Creating PostgreSQL connection pool" {:pool-size pool-size :min-idle min-idle})
    ;; Note: HikariCP validates connectivity on first connection acquisition.
    ;; Configuration errors (wrong password, unreachable host) will be detected
    ;; when the first query is executed, not during pool creation.
    (let [config (HikariConfig.)]
      (HikariConfig/.setJdbcUrl config jdbc-url)
      (HikariConfig/.setUsername config username)
      (HikariConfig/.setPassword config password)
      (HikariConfig/.setMaximumPoolSize config pool-size)
      (HikariConfig/.setMinimumIdle config min-idle)
      (HikariConfig/.setConnectionTimeout config connection-timeout)
      (HikariConfig/.setIdleTimeout config idle-timeout)
      (HikariConfig/.setMaxLifetime config max-lifetime)
      (HikariConfig/.setLeakDetectionThreshold config leak-detection-threshold)
      (HikariDataSource. config))))


(defn close-pool
  "Closes a HikariCP connection pool. Idempotent - safe to call multiple times.
   HikariDataSource.close() is itself thread-safe and idempotent.

   Concurrent behavior:
   - Queries in-flight will complete or fail depending on timing
   - New connection acquisitions after close() will fail immediately
   - Connections already checked out will work until returned to pool

   Note: When called from PostgresStorage.close(), synchronization is handled
   by the storage's lock. Direct callers should ensure proper synchronization.

   Returns true if pool was closed successfully, false if close failed.
   Exceptions are logged but not thrown to allow cleanup to continue."
  [^HikariDataSource pool]
  (if (and pool (not (HikariDataSource/.isClosed pool)))
    (do
      (log/info "Closing PostgreSQL connection pool")
      (try
        (HikariDataSource/.close pool)
        (log/debug "PostgreSQL connection pool closed successfully")
        true
        (catch Exception e
          (log/error e "Failed to close PostgreSQL connection pool gracefully")
          false)))
    true))


;; === Storage record ===

(defn- get-cached-metadata
  "Gets metadata from cache or reads from database.
   Thread-safe: uses ReentrantReadWriteLock for concurrent read access.
   Write lock is only acquired when cache needs to be populated.
   Cache is invalidated on schema changes (initialize).

   NOTE: Returns nil without caching if table not found (not initialized yet).
   This prevents caching stale nil values during initialization race conditions.

   Cache safety: reset! only happens after parse-metadata-lenient successfully returns.
   If parsing throws, cache remains unchanged (Clojure let-binding evaluation order)."
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
                                  (let [result (metadata/parse-metadata-lenient (metadata/read-metadata-rows pool))]
                                    (reset! metadata-cache result)
                                    result)
                                  (catch SQLException e
                                    ;; Don't cache nil - table might be created soon
                                    (when-not (util/table-not-found? e)
                                      (throw e)))))))))


(defn- extract-entity-fields
  "Extracts field specs for an entity from cached metadata.
   Returns a map of field-name -> {:type type :nullable? nullable? :enum-name kw}."
  [cached-metadata entity-name]
  (when cached-metadata
    (->> (:fields cached-metadata)
         (vals)
         (filter #(= (:entity %) entity-name))
         (map (fn [{:keys [field nullable? enum-name] field-type :type}]
                [field (cond-> {:type field-type :nullable? nullable?}
                         enum-name (assoc :enum-name enum-name))]))
         (into {}))))


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
                          (close-pool pool)))
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (set (map util/snake->kw
              (introspection/current-tables pool))))


  (current-fields
    [_this entity-name]
    (when-let [cached-metadata (get-cached-metadata pool metadata-cache rw-lock)]
      (when (some #(= % entity-name) (vals (:entities cached-metadata)))
        (let [entity-fields (->> (:fields cached-metadata)
                                 (vals)
                                 (filter #(= (:entity %) entity-name)))]
          (into {}
                (map (fn [{:keys [field nullable?] :as f}]
                       [field {:type (:type f) :nullable? nullable?}])
                     entity-fields))))))


  (current-enums
    [_this]
    (set (map util/snake->kw
              (introspection/current-pg-enums pool))))


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
    (let [cached-metadata (get-cached-metadata pool metadata-cache rw-lock)
          fields (extract-entity-fields cached-metadata entity-name)]
      (crud/create-entity pool entity-name data fields)))


  (read-entity
    [_this entity-name id]
    (crud/read-entity pool entity-name id))


  (update-entity
    [_this entity-name id data]
    (let [cached-metadata (get-cached-metadata pool metadata-cache rw-lock)
          fields (extract-entity-fields cached-metadata entity-name)]
      (crud/update-entity pool entity-name id data fields)))


  (delete-entity
    [_this entity-name id]
    (crud/delete-entity pool entity-name id))


  (query-entities
    [_this entity-name where]
    (crud/query-entities pool entity-name where))


  sp/StorageBatchCRUD

  (create-entities
    [_this entity-name data-seq]
    (let [cached-metadata (get-cached-metadata pool metadata-cache rw-lock)
          fields (extract-entity-fields cached-metadata entity-name)]
      (crud/create-entities pool entity-name data-seq fields)))


  (read-entities
    [_this entity-name ids]
    (crud/read-entities pool entity-name ids))


  (delete-entities
    [_this entity-name ids]
    (crud/delete-entities pool entity-name ids))


  sp/GraphConstraints

  (validate-parent-same-schema!
    [_this fn-id parent-fn-id]
    (constraints/validate-parent-same-schema! pool fn-id parent-fn-id))


  (validate-no-arg-override!
    [_this fn-id arg-schema-id]
    (constraints/validate-no-arg-override! pool fn-id arg-schema-id))


  (validate-arg-schema-belongs-to-fn!
    [_this fn-id arg-schema-id]
    (constraints/validate-arg-schema-belongs-to-fn! pool fn-id arg-schema-id))


  (validate-no-inheritance-cycle!
    [_this fn-id parent-fn-id]
    (constraints/validate-no-inheritance-cycle! pool fn-id parent-fn-id))


  (validate-no-dependency-cycle!
    [_this owner-fn-id value-fn-id]
    (constraints/validate-no-dependency-cycle! pool owner-fn-id value-fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_this fn-id]
    (graph/resolve-execution-graph pool fn-id))


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
  (->PostgresStorage (create-pool opts) (atom nil) (ReentrantReadWriteLock.)))
