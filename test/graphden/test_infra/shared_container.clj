(ns graphden.test-infra.shared-container
  "Global shared PostgreSQL container for all tests.

   This module provides a single PostgreSQL container that is shared across
   all test namespaces, dramatically reducing test startup time.

   Instead of 38+ containers (one per test namespace), we use ONE container
   and clean the database between tests.

   Usage in tests:
     (require '[graphden.test-infra.shared-container :as sc])

     ;; Get the shared container (starts if not running)
     (sc/get-container)

     ;; Get connection config
     (sc/get-config)

     ;; Create fixture that uses shared container
     (use-fixtures :once (sc/shared-container-fixture #'*container*))

   The container is started lazily on first access and stopped via
   kaocha hooks plugin or JVM shutdown hook."
  (:require
    [clojure.tools.logging :as log])
  (:import
    (org.testcontainers.containers
      PostgreSQLContainer)))


;; =============================================================================
;; Global State
;; =============================================================================

(def ^:private container-atom
  "Atom holding the shared PostgreSQL container.
   nil = not started, PostgreSQLContainer = running."
  (atom nil))


(def ^:private container-lock
  "Lock for thread-safe container initialization."
  (Object.))


(def default-postgres-image
  "Default PostgreSQL Docker image for tests."
  "postgres:16-alpine")


;; =============================================================================
;; Container Lifecycle
;; =============================================================================

(defn- create-container
  "Creates and starts a new PostgreSQL container.
   Configured for high concurrency (500 connections) to support parallel tests."
  []
  (log/info "Starting shared PostgreSQL test container...")
  (let [start-time (System/currentTimeMillis)
        container (doto (PostgreSQLContainer. ^String default-postgres-image)
                    (PostgreSQLContainer/.withStartupAttempts 3)
                    (PostgreSQLContainer/.withCommand "postgres -c max_connections=500")
                    (PostgreSQLContainer/.start))]
    (when-not (PostgreSQLContainer/.isRunning container)
      (throw (ex-info "Failed to start shared PostgreSQL test container"
                      {:image default-postgres-image})))
    (log/info "Shared PostgreSQL container started in"
              (- (System/currentTimeMillis) start-time) "ms")
    container))


(defn get-container
  "Returns the shared PostgreSQL container, starting it if necessary.
   Thread-safe: only one container will be created even with concurrent calls."
  []
  (or @container-atom
      (locking container-lock
        (or @container-atom
            (let [container (create-container)]
              (reset! container-atom container)
              container)))))


(defn stop-container!
  "Stops the shared container if running.
   Called by kaocha hooks plugin after all tests complete."
  []
  (when-let [container @container-atom]
    (log/info "Stopping shared PostgreSQL test container...")
    (PostgreSQLContainer/.stop container)
    (reset! container-atom nil)
    (log/info "Shared PostgreSQL container stopped")))


(defn running?
  "Returns true if the shared container is running."
  []
  (when-let [container @container-atom]
    (PostgreSQLContainer/.isRunning container)))


;; =============================================================================
;; Configuration
;; =============================================================================

(defn get-config
  "Returns connection configuration map for the shared container.
   Starts the container if not already running.

   Returns map with:
   - :jdbc-url - JDBC connection URL
   - :username - Database username
   - :password - Database password
   - :pool-size - Default pool size for tests (2)"
  []
  (let [container (get-container)]
    {:jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
     :username (PostgreSQLContainer/.getUsername container)
     :password (PostgreSQLContainer/.getPassword container)
     :pool-size 2}))


;; =============================================================================
;; Fixtures
;; =============================================================================

(defn shared-container-fixture
  "Creates a :once fixture that binds the shared container to a var.

   Unlike per-namespace container fixtures, this reuses the same container
   across all test namespaces that use this fixture.

   Arguments:
   - container-var: A var to bind the container to (e.g., #'*container*)

   Example:
     (def ^:dynamic *container* nil)
     (use-fixtures :once (shared-container-fixture #'*container*))"
  [container-var]
  (fn [f]
    (with-bindings {container-var (get-container)}
      (f))))


;; =============================================================================
;; JVM Shutdown Hook (fallback cleanup)
;; =============================================================================

(Runtime/.addShutdownHook
  (Runtime/getRuntime)
  (Thread.
    ^Runnable
    (fn []
      (when (running?)
        (log/info "JVM shutdown: stopping shared PostgreSQL container")
        (stop-container!)))))
