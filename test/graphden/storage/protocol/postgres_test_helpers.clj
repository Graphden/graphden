(ns graphden.storage.protocol.postgres-test-helpers
  "Shared test utilities for PostgreSQL-based test suites.

   Provides testcontainer setup and database cleanup functions to eliminate
   boilerplate duplication across postgres-storage, cache-postgres,
   graph-storage-postgres, and other PostgreSQL test namespaces.

   Key utilities:
   - with-postgres-container: Fixture that starts/stops a PostgreSQL container
   - with-clean-database: Fixture that cleans database between tests
   - clean-database-fast!: Fast cleanup via DROP SCHEMA CASCADE (recommended)
   - clean-database-iterative!: Slower cleanup iterating tables/enums
   - get-container-config: Get connection config from container

   Usage:
     (def ^:dynamic *container* nil)

     (use-fixtures :once (create-container-fixture #'*container*))
     (use-fixtures :each (create-clean-db-fixture *container*))

   PERFORMANCE NOTE:
   By default, create-container-fixture uses a global shared container
   managed by graphden.test-infra.shared-container. This dramatically
   reduces test startup time (1 container vs 38+ containers).
   Use :use-shared false to create a dedicated container per namespace."
  (:require
    [graphden.test-infra.shared-container :as sc]
    [next.jdbc :as jdbc])
  (:import
    (org.testcontainers.containers
      PostgreSQLContainer)))


;; ============================================================================
;; Container Configuration
;; ============================================================================

(def default-postgres-image
  "Default PostgreSQL Docker image for tests."
  "postgres:16-alpine")


(defn get-container-config
  "Returns connection configuration map from a running container.

   Arguments:
   - container: A running PostgreSQLContainer instance

   Returns map with:
   - :jdbc-url - JDBC connection URL
   - :username - Database username
   - :password - Database password
   - :pool-size - Default pool size for tests (2)"
  [container]
  {:jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
   :username (PostgreSQLContainer/.getUsername container)
   :password (PostgreSQLContainer/.getPassword container)
   :pool-size 2})


;; ============================================================================
;; Database Cleanup Functions
;; ============================================================================

(defn clean-database-fast!
  "Drops all user-created objects in public schema using DROP SCHEMA CASCADE.

   This is the fastest method - single DDL operation vs N operations.
   Use this when you don't need to preserve any schema objects between tests.

   Arguments:
   - container: A running PostgreSQLContainer instance"
  [container]
  (let [{:keys [jdbc-url username password]} (get-container-config container)]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      (jdbc/execute! conn ["DROP SCHEMA public CASCADE"])
      (jdbc/execute! conn ["CREATE SCHEMA public"])
      (jdbc/execute! conn ["GRANT ALL ON SCHEMA public TO PUBLIC"])))
  ;; In-memory caches survive schema drops — clear them so cached
  ;; branch chains from a previous test fixture don't leak into the
  ;; fresh DB. (UUID collisions are improbable but the chains also
  ;; reference nonexistent ancestors after this point, so even a
  ;; hypothetical miss isn't worth risking.)
  (require 'graphden.versioning.storage.resolution)
  ((resolve 'graphden.versioning.storage.resolution/invalidate-chain-cache!)))


(defn clean-database-iterative!
  "Drops all user-created tables and enums by iterating through them.

   Slower than clean-database-fast! but useful if you need fine-grained control.
   Iterates pg_tables and pg_type to find and drop objects.

   Arguments:
   - container: A running PostgreSQLContainer instance"
  [container]
  (let [{:keys [jdbc-url username password]} (get-container-config container)]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      ;; Drop tables
      (let [tables (jdbc/execute! conn ["SELECT tablename FROM pg_tables WHERE schemaname = 'public'"])]
        (doseq [{:pg_tables/keys [tablename]} tables]
          (jdbc/execute! conn [(str "DROP TABLE IF EXISTS \"" tablename "\" CASCADE")])))
      ;; Drop enums
      (let [enums (jdbc/execute! conn
                                 ["SELECT t.typname FROM pg_type t
                                    JOIN pg_namespace n ON n.oid = t.typnamespace
                                    WHERE n.nspname = 'public' AND t.typtype = 'e'"])]
        (doseq [{:pg_type/keys [typname]} enums]
          (jdbc/execute! conn [(str "DROP TYPE IF EXISTS \"" typname "\" CASCADE")]))))))


;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn create-container-fixture
  "Creates a :once fixture that binds a PostgreSQL container to a var.

   By default, uses the global shared container for fast test startup.
   Set :use-shared false to create a dedicated container per namespace.

   Arguments:
   - container-var: A var to bind the container to (e.g., #'*container*)
   - opts: Optional map with:
     - :use-shared - Use shared container (default: true)
     - :image - Docker image (default: postgres:16-alpine, only for dedicated)
     - :startup-attempts - Number of retry attempts (default: 3, only for dedicated)

   Returns a fixture function suitable for use-fixtures :once.

   Example:
     (def ^:dynamic *container* nil)
     (use-fixtures :once (create-container-fixture #'*container*))"
  ([container-var]
   (create-container-fixture container-var {}))
  ([container-var {:keys [use-shared image startup-attempts]
                   :or {use-shared true
                        image default-postgres-image
                        startup-attempts 3}}]
   (if use-shared
     ;; Use global shared container (fast path - no start/stop per namespace)
     (sc/shared-container-fixture container-var)
     ;; Create dedicated container per namespace (slow path)
     (fn [f]
       (let [container (doto (PostgreSQLContainer. ^String image)
                         (PostgreSQLContainer/.withStartupAttempts startup-attempts)
                         ;; Increase max_connections for parallel test execution
                         (PostgreSQLContainer/.withCommand "postgres -c max_connections=500"))]
         (PostgreSQLContainer/.start container)
         (when-not (PostgreSQLContainer/.isRunning container)
           (throw (ex-info "Failed to start PostgreSQL test container"
                           {:image image :attempts startup-attempts})))
         (try
           (with-bindings {container-var container}
             (f))
           (finally
             (PostgreSQLContainer/.stop container))))))))


(defn create-clean-db-fixture
  "Creates an :each fixture that cleans the database before each test.

   Arguments:
   - container-var: A var containing the running container (e.g., #'*container*)
   - opts: Optional map with:
     - :clean-fn - Cleanup function (default: clean-database-fast!)

   Returns a fixture function suitable for use-fixtures :each.

   Example:
     (use-fixtures :each (create-clean-db-fixture #'*container*))"
  ([container-var]
   (create-clean-db-fixture container-var {}))
  ([container-var {:keys [clean-fn]
                   :or {clean-fn clean-database-fast!}}]
   (fn [f]
     (clean-fn @container-var)
     (f))))


;; ============================================================================
;; Convenience Macros
;; ============================================================================

(defmacro with-postgres-container
  "Executes body with a running PostgreSQL container bound to sym.

   Arguments:
   - binding: Vector of [sym] or [sym opts-map]
   - body: Forms to execute with container available

   Options map:
   - :image - Docker image (default: postgres:16-alpine)
   - :startup-attempts - Number of retry attempts (default: 3)

   Example:
     (with-postgres-container [container]
       (let [config (get-container-config container)]
         (create-storage config)))"
  [[sym & [opts]] & body]
  `(let [opts# (merge {:image default-postgres-image :startup-attempts 3} ~opts)
         container# (doto (PostgreSQLContainer. ^String (:image opts#))
                      (PostgreSQLContainer/.withStartupAttempts (:startup-attempts opts#))
                      ;; Increase max_connections for parallel test execution
                      (PostgreSQLContainer/.withCommand "postgres -c max_connections=500"))]
     (PostgreSQLContainer/.start container#)
     (when-not (PostgreSQLContainer/.isRunning container#)
       (throw (ex-info "Failed to start PostgreSQL test container"
                       {:image (:image opts#) :attempts (:startup-attempts opts#)})))
     (try
       (let [~sym container#]
         ~@body)
       (finally
         (PostgreSQLContainer/.stop container#)))))
