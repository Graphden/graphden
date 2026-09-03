(ns graphden.storage.postgres.pool
  "HikariCP connection pool management for PostgreSQL.

   Provides functions for:
   - Connection pool creation with validated configuration
   - Graceful pool shutdown
   - Pool health and metrics (via HikariCP)"
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp])
  (:import
    (com.zaxxer.hikari
      HikariConfig
      HikariDataSource)))


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
      ;; Allow the pool to be suspended/resumed. Costs nothing in normal
      ;; operation (a gate that's only closed on an explicit suspend), but lets
      ;; a CRaC checkpoint drain every physical connection before snapshotting
      ;; and block new acquisitions until restore (graphden.crac). HikariCP has
      ;; no built-in CRaC support, so we drive suspend/evict/resume ourselves.
      (HikariConfig/.setAllowPoolSuspension config true)
      (HikariDataSource. config))))


(defn- hikari-of
  "The `HikariDataSource` behind `pool`, which may be the pool itself or a
   `DataSource` wrapped around it — the tenancy addon's `:datasource-wrap`
   seam hands `:db/postgres` a `reify` that sets the RLS session variable
   on every borrow, and that wrapper is what reaches `close-pool` at halt.
   The JDBC `Wrapper` protocol is the contract: a wrap that delegates
   `isWrapperFor` / `unwrap` (as Hikari itself does) closes through; one
   that does not is left alone, with a warning, rather than cast — the
   cast is what threw `ClassCastException` out of the shutdown hook and
   aborted the rest of the halt (prod, 2026-09-03)."
  ^HikariDataSource [pool]
  (cond
    (nil? pool) nil
    (instance? HikariDataSource pool) pool
    (and (instance? java.sql.Wrapper pool)
         (java.sql.Wrapper/.isWrapperFor pool HikariDataSource))
    (java.sql.Wrapper/.unwrap pool HikariDataSource)
    :else (do (log/warn "close-pool: not a HikariDataSource and does not unwrap to one — leaving it open"
                        {:type (type pool)})
              nil)))


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
  [pool]
  (let [pool (hikari-of pool)]
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
      true)))
