(ns graphden.executor-runtime.core
  "Executor runtime - main entry point for running graphden as a web server.

   Uses Integrant for component lifecycle management.

   ## Architecture

   The system is composed of these components (managed by Integrant):

   :db/schema        → Schema builder (pure)
   :db/age           → Apache AGE storage
   :db/versioned     → Versioned storage decorator
   :exec/base-fns    → Base function registry
   :exec/fn-entities → Fn definitions (web server routes)
   :exec/context     → Executor context
   :exec/compiled-registry  → Compiled-at-startup closures (hot path)
   :exec/service-reconciler → Supervises enabled :service rows
                              (replaces standalone :http/server;
                              legacy fallback spawns the package's
                              :startup-fn when no :service rows exist)
   :exec/cleanup-scheduler  → Hourly :fn-execution TTL sweep

   ## Configuration

   Configuration is loaded from EDN files via Aero:
   - resources/system-prod.edn  (production)
   - resources/system-dev.edn   (development)
   - resources/system-test.edn  (testing)

   Environment variables override defaults in prod:
   - PORT: HTTP server port (default: 8080)
   - JDBC_URL: JDBC connection URL
   - DB_USERNAME: database username
   - DB_PASSWORD: database password
   - DB_POOL_SIZE: connection pool size (default: 10)

   ## Running

   ```bash
   JDBC_URL=jdbc:postgresql://localhost:5432/graphden \\
     DB_USERNAME=graphden DB_PASSWORD=graphden \\
     clojure -M -m graphden.executor-runtime.core
   ```"
  (:gen-class)
  (:require
    [clojure.tools.logging :as log]
    [graphden.system.interface :as sys]))


;; =============================================================================
;; System State
;; =============================================================================

;; Holds the running system state.
(defonce ^:private system (atom nil))


;; =============================================================================
;; Lifecycle Functions
;; =============================================================================

(defn start!
  "Starts the system with given profile (:dev, :test, :prod).
   Returns the running system map."
  ([]
   (start! :prod))
  ([profile]
   (log/info "Starting Graphden Executor Runtime with profile:" profile)
   (let [sys (sys/start! profile)]
     (reset! system sys)
     (log/info "Graphden started successfully")
     sys)))


(defn stop!
  "Stops the running system."
  []
  (when-let [sys @system]
    (log/info "Stopping Graphden Executor Runtime...")
    (sys/stop! sys)
    (reset! system nil)
    (log/info "Graphden stopped")))


(defn restart!
  "Restarts the system with given profile."
  ([]
   (restart! :prod))
  ([profile]
   (stop!)
   (start! profile)))


;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn install-shutdown-hook!
  "Register a JVM shutdown hook that stops the running system. Pulled
   out of -main so tests can stub it without leaking real hooks."
  []
  (Runtime/.addShutdownHook (Runtime/getRuntime)
                            (Thread. #(stop!))))


(defn block-forever!
  "Block the calling thread indefinitely. Pulled out of -main so tests
   can stub it instead of waiting on a real promise."
  []
  @(promise))


(defn -main
  "Main entry point for the executor runtime."
  [& _args]
  (start! :prod)
  (install-shutdown-hook!)
  (log/info "Server running. Press Ctrl+C to stop.")
  (block-forever!))
