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

   Fn entities compose base-fns with concrete values and references.
   All configuration and data binding happens via fn entities.

   ## Configuration

   The runtime is configured via environment variables:
   - PORT: HTTP server port (default: 8080)
   - JDBC_URL: JDBC connection URL (required)
   - DB_USERNAME: database username (required)
   - DB_PASSWORD: database password (required)
   - DB_POOL_SIZE: connection pool size (default: 10)

   ## Storage Stack

   Apache AGE provides graph-optimized storage with O(1) graph resolution:

   VersionedStorage
       └── AGEStorage

   This provides:
   - O(1) graph resolution via single Cypher query (no cache needed!)
   - Git-like versioning with branches
   - Merge conflict detection
   - Merge protection for sensitive values (via merge-protection module)

   ## Running

   ```bash
   JDBC_URL=jdbc:postgresql://localhost:5432/graphden \\
     DB_USERNAME=graphden DB_PASSWORD=graphden \\
     clojure -M -m graphden.executor-runtime.core
   ```"
  (:gen-class)
  (:require
    [clojure.tools.logging :as log]
    [graphden.base-functions.interface :as bf]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.executor.interface :as exec]
    [graphden.fn-composition.interface :as fn-composition]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-data-schema.interface :as gds]
    [graphden.graph-storage-age.interface :as age]
    [graphden.http-kit-fns.interface :as http-kit-fns]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.reitit-fns.interface :as reitit-fns]
    [graphden.storage-protocol.interface :as sp]
    [graphden.value-traits-schema.interface :as vts]
    [graphden.versioned-data-schema.interface :as vds]
    [graphden.versioned-storage.interface :as vs]
    [graphden.web-server-fns.interface :as web-server-fns]))


;; === Configuration ===

(def default-config
  {:port 8080
   :db-pool-size 10})


(defn get-config
  "Gets configuration from environment variables or returns defaults."
  []
  {:port (Integer/parseInt (or (System/getenv "PORT") "8080"))
   :jdbc-url (System/getenv "JDBC_URL")
   :db-username (System/getenv "DB_USERNAME")
   :db-password (System/getenv "DB_PASSWORD")
   :db-pool-size (Integer/parseInt (or (System/getenv "DB_POOL_SIZE") "10"))})


;; === Storage Initialization ===

(defn- build-schema
  "Builds the complete schema combining graph and versioned entities."
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (ds/build)))


(defn create-storage
  "Creates Apache AGE storage with versioning.

   Stack architecture:
   VersionedStorage
       └── AGEStorage

   For merge protection, use graphden.merge-protection.interface functions
   when performing branch merges.

   Returns a map with:
   - :storage - the storage instance to use
   - :age-storage - underlying AGE storage (for cleanup)"
  [config]
  (let [{:keys [jdbc-url db-username db-password db-pool-size]} config
        _ (when-not jdbc-url
            (throw (ex-info "JDBC_URL is required"
                            {:type :configuration-error})))
        _ (log/info "Connecting to Apache AGE..." jdbc-url)
        ;; Build schema
        schema (build-schema)
        ;; Create and initialize AGE storage
        age-config {:jdbc-url jdbc-url
                    :username db-username
                    :password db-password
                    :pool-size db-pool-size}
        age-storage (-> (age/create-storage age-config)
                        (sp/initialize-with-cleanup! schema))
        _ (log/info "AGE storage initialized")
        ;; Wrap with versioning (creates 'main' branch)
        versioned (vs/wrap-with-versioning age-storage)
        _ (log/info "Versioning enabled, branch:" (vs/current-branch-id versioned))
        ;; Seed value traits (for merge-protection)
        _ (vts/seed-traits! age-storage)]
    (log/info "Storage stack initialized: AGE + Versioning")
    {:storage versioned
     :age-storage age-storage}))


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
  (fn-composition/sync-fns-to-storage! storage web-server-fns/fn-defs))


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
   - :context - the executor context
   - :age-storage - underlying AGE storage (for cleanup)"
  [config]
  (println "Starting Graphden Executor Runtime...")
  (println "  Port:" (:port config))

  ;; 1. Create and initialize storage
  (let [{:keys [storage age-storage]} (create-storage config)
        storage (initialize-base-fns! storage)
        ;; 2. Create fn entities
        fns (create-fn-entities! storage)
        ;; 3. Create executor context
        ctx (exec/create-context {:storage storage})
        ;; 4. Execute web-server-fn
        startup-fn (name web-server-fns/startup-fn-name)
        _ (println "Executing" startup-fn "...")
        server (exec/execute-by-name ctx startup-fn nil)]

    (println "Server started on port" (:port config))
    (println "  http://localhost:" (:port config) "/")
    (println "  http://localhost:" (:port config) "/health")

    {:storage storage
     :server server
     :context ctx
     :fns fns
     :age-storage age-storage}))


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
