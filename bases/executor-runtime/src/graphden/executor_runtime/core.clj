(ns graphden.executor-runtime.core
  "Executor runtime - main entry point for running graphden as a web server.

   This is the entry point for the executor runtime. It:
   1. Initializes storage with all base functions
   2. Creates fn entities from definitions
   3. Executes startup functions (web-server-fn)

   ## Architecture

   The web server is built as a graph of fn entities:

   web-server-fn
     ├── parent: http-server (base-fn)
     ├── handler -> router-handler-fn
     └── port -> 8080

   router-handler-fn
     ├── parent: reitit-ring-handler (base-fn)
     ├── routes -> hello-routes
     └── handlers -> {:hello hello-handler-fn, :health health-handler-fn}

   hello-handler-fn
     └── parent: constantly (base-fn, returns static response)

   health-handler-fn
     └── parent: constantly (base-fn, returns static response)

   ## Base Functions vs Fn Entities

   Base-fns are wrappers around pure functions (Clojure core or libraries).
   They are low-level building blocks added by experienced developers.

   Fn entities compose base-fns with concrete values through inheritance.
   All configuration and data binding happens via fn entities.

   ## Configuration

   The runtime is configured via environment variables:
   - PORT: HTTP server port (default: 8080)
   - STORAGE_TYPE: 'memory', 'postgres', or 'datomic' (default: memory)

   ## Running

   ```bash
   clojure -M -m graphden.executor-runtime.core
   ```"
  (:gen-class)
  (:require
    [graphden.base-functions.interface :as bf]
    [graphden.executor.interface :as exec]
    [graphden.fn-defs.interface :as fn-defs]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.http-kit-fns.interface :as http-kit-fns]
    [graphden.reitit-fns.interface :as reitit-fns]
    [graphden.storage-protocol.interface :as sp]
    [graphden.web-server.interface :as web-server]))


;; === Configuration ===

(def default-config
  {:port 8080
   :storage-type :memory})


(defn get-config
  "Gets configuration from environment variables or returns defaults."
  []
  {:port (Integer/parseInt (or (System/getenv "PORT") "8080"))
   :storage-type (keyword (or (System/getenv "STORAGE_TYPE") "memory"))})


;; === Storage Initialization ===

(defn create-storage
  "Creates storage based on configuration."
  [config]
  (case (:storage-type config)
    :memory (gsm/create-storage)
    (throw (ex-info "Unsupported storage type" {:type (:storage-type config)}))))


(defn initialize-base-fns!
  "Registers and syncs all base functions to storage.

   Base-fns come from:
   - base-functions: arithmetic, strings, collections, HOF (constantly, etc.)
   - http-kit-fns: http-server, http-stop
   - reitit-fns: router

   Note: web-server component has NO base-fns, only fn-defs.

   Returns the storage instance."
  [storage]
  (registry/initialize-all! storage
                            [(bf/get-all-defs)       ; arithmetic, strings, HOF (constantly), etc.
                             http-kit-fns/all-defs   ; http-server, http-stop
                             reitit-fns/all-defs]))  ; router


(defn create-fn-entities!
  "Creates fn entities in storage from definitions.
   Returns a map of created fn entities."
  [storage]
  (fn-defs/sync-fns-to-storage! storage web-server/fn-defs))


;; === Server Startup ===

(defn start-server!
  "Starts the HTTP server by executing the web-server-fn.

   Steps:
   1. Create storage
   2. Initialize base functions
   3. Create fn entities
   4. Execute web-server-fn via executor

   Returns a map with:
   - :storage - the initialized storage
   - :server - the server instance (for stopping)
   - :context - the executor context"
  [config]
  (println "Starting Graphden Executor Runtime...")
  (println "  Port:" (:port config))
  (println "  Storage:" (:storage-type config))

  ;; 1. Create and initialize storage
  (let [storage (-> (create-storage config)
                    (initialize-base-fns!))
        ;; 2. Create fn entities
        fns (create-fn-entities! storage)
        ;; 3. Create executor context
        ctx (exec/create-context {:storage storage})
        ;; 4. Execute web-server-fn
        startup-fn (name web-server/startup-fn-name)
        _ (println "Executing" startup-fn "...")
        server (exec/execute-by-name ctx startup-fn nil)]

    (println "Server started on port" (:port config))
    (println "  http://localhost:" (:port config) "/")
    (println "  http://localhost:" (:port config) "/health")

    {:storage storage
     :server server
     :context ctx
     :fns fns}))


(defn stop-server!
  "Stops the server and closes storage."
  [{:keys [server storage]}]
  (println "Stopping server...")
  (when server
    ;; http-kit server is a function - calling it stops the server
    (server))
  (when storage
    (sp/close storage))
  (println "Server stopped."))


;; === Main Entry Point ===

(defn -main
  "Main entry point for the executor runtime."
  [& _args]
  (let [config (get-config)
        state (start-server! config)]

    ;; Add shutdown hook for graceful shutdown
    (Runtime/.addShutdownHook (Runtime/getRuntime)
                              (Thread. #(stop-server! state)))

    ;; Block main thread to keep server running
    (println "Press Ctrl+C to stop...")
    @(promise)))  ; Block forever
