(ns graphden.executor-runtime.core
  "Executor runtime - main entry point for running graphden as a web server.

   Uses Integrant for component lifecycle management.

   ## Architecture

   The system is composed of these components (managed by Integrant):

   :db/schema        → Schema builder (pure)
   :db/postgres      → PostgreSQL storage (recursive-CTE graph traversal)
   :db/versioned     → Versioned storage decorator
   :exec/base-fns    → Base function registry
   :exec/fn-entities → Fn definitions (web server routes)
   :exec/context     → Executor context
   :exec/compiled-registry  → Compiled-at-startup closures (hot path)
   :exec/service-reconciler → Supervises enabled :service rows
                              (a package's :services seed the initial
                              set — e.g. app seeds :web-server)
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
    [clojure.string :as str]
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

;; The runtime-lifecycle entry points carry `^:dynamic` so the parallel
;; kaocha runner can rebind them via `binding` from inside an
;; integration test without mutating a root binding (which would race
;; across NSes — `executor_runtime.core_test` and
;; `executor_runtime.interface_test` both stub these). Production
;; callers (`-main`, REPL `(go)`/`(halt)`) read the Var the same way.
(defn ^:dynamic start!
  "Starts the system with given profile (:dev, :test, :prod).
   Returns the running system map.

   - 1-arity: `(start! :test)` — start ALL components.
   - 2-arity: `(start! :test overrides)` — merge per-key `overrides`
     into the integrant config before init. Cleaner replacement for
     the test pattern `(with-redefs [sys/read-config …] (start! :test))`
     (which mutates a global var and races on concurrent invocations)."
  ([]
   (start! :prod))
  ([profile]
   (log/info "Starting Graphden Executor Runtime with profile:" profile)
   (let [sys (sys/start! profile)]
     (reset! system sys)
     (log/info "Graphden started successfully")
     sys))
  ([profile overrides]
   (log/info "Starting Graphden Executor Runtime with profile:"
             profile "(with overrides)")
   (let [sys (sys/start-with-overrides! profile overrides)]
     (reset! system sys)
     (log/info "Graphden started successfully")
     sys)))


(defn ^:dynamic stop!
  "Stops the running system."
  []
  (when-let [sys @system]
    (log/info "Stopping Graphden Executor Runtime...")
    (sys/stop! sys)
    (reset! system nil)
    (log/info "Graphden stopped")))


(defn ^:dynamic restart!
  "Restarts the system with given profile.
   3-arity accepts integrant config overrides — see `start!`."
  ([]
   (restart! :prod))
  ([profile]
   (stop!)
   (start! profile))
  ([profile overrides]
   (stop!)
   (start! profile overrides)))


;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn ^:dynamic install-shutdown-hook!
  "Register a JVM shutdown hook that stops the running system. Pulled
   out of -main so tests can stub it without leaking real hooks."
  []
  (Runtime/.addShutdownHook (Runtime/getRuntime)
                            (Thread. #(stop!))))


(defn ^:dynamic block-forever!
  "Block the calling thread indefinitely. Pulled out of -main so tests
   can stub it instead of waiting on a real promise."
  []
  @(promise))


(defn- maybe-start-nrepl!
  "Start a plain-nREPL server on `GRAPHDEN_NREPL_PORT` when set —
   debugging access into the live executor. No cider/refactor
   middleware (those live only in dev aliases); plain `nrepl.server`
   is enough for `clojure_eval`-driven inspection.

   Treats absent AND blank env vars as 'off' — docker-compose / k8s
   YAMLs that thread the var with a `:default \"\"` substitution
   (e.g. `GRAPHDEN_NREPL_PORT: \\${GRAPHDEN_NREPL_PORT:-}`) deliver
   the literal empty string, which `Integer/parseInt` would crash on.
   The crash kills the `main` thread but http-kit's listener has
   already started in another thread, so the executor keeps serving
   — masking the bug as a stack trace in the boot log with no
   user-visible consequence. Guard `str/blank?` BEFORE parse."
  []
  (let [raw (System/getenv "GRAPHDEN_NREPL_PORT")]
    (when-not (str/blank? raw)
      (let [p (Integer/parseInt raw)
            start-server (requiring-resolve 'nrepl.server/start-server)]
        (start-server :bind "0.0.0.0" :port p)
        (log/info (str "nREPL listening on 0.0.0.0:" p))))))


(defn -main
  "Main entry point for the executor runtime."
  [& _args]
  (start! :prod)
  (install-shutdown-hook!)
  (maybe-start-nrepl!)
  (log/info "Server running. Press Ctrl+C to stop.")
  (block-forever!))
