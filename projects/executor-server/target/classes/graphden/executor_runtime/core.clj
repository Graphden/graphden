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
   - STORAGE_TYPE: 'memory' or 'postgres' (default: memory)

   For PostgreSQL mode:
   - JDBC_URL: JDBC connection URL (required for postgres)
   - DB_USERNAME: database username (required for postgres)
   - DB_PASSWORD: database password (required for postgres)
   - DB_POOL_SIZE: connection pool size (default: 10)

   ## Storage Stack (PostgreSQL mode)

   When STORAGE_TYPE=postgres, the full stack is enabled:

   CachedStorageWithMetrics
       └── CachedStorage
               └── VersionedStorage
                       └── PostgresStorage

   This provides:
   - Execution graph caching (O(1) instead of O(depth))
   - Git-like versioning with branches
   - Cache hit/miss metrics

   ## Running

   ```bash
   # Memory mode (default)
   clojure -M -m graphden.executor-runtime.core

   # PostgreSQL mode
   STORAGE_TYPE=postgres JDBC_URL=jdbc:postgresql://localhost:5432/graphden \\
     DB_USERNAME=graphden DB_PASSWORD=graphden \\
     clojure -M -m graphden.executor-runtime.core
   ```"
  (:gen-class)
  (:require
    [clojure.tools.logging :as log]
    [graphden.base-functions.interface :as bf]
    [graphden.cache-data-schema.interface :as cds]
    [graphden.cache-postgres.interface :as cache-pg]
    [graphden.cached-storage.interface :as cs]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.executor.interface :as exec]
    [graphden.fn-composition.interface :as fn-composition]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-data-schema.interface :as gds]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.http-kit-fns.interface :as http-kit-fns]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.interface :as pg]
    [graphden.reitit-fns.interface :as reitit-fns]
    [graphden.storage-protocol.interface :as sp]
    [graphden.versioned-data-schema.interface :as vds]
    [graphden.versioned-storage.interface :as vs]
    [graphden.web-server-fns.interface :as web-server-fns]))


;; === Configuration ===

(def default-config
  {:port 8080
   :storage-type :memory
   :db-pool-size 10})


(defn get-config
  "Gets configuration from environment variables or returns defaults."
  []
  {:port (Integer/parseInt (or (System/getenv "PORT") "8080"))
   :storage-type (keyword (or (System/getenv "STORAGE_TYPE") "memory"))
   ;; PostgreSQL settings
   :jdbc-url (System/getenv "JDBC_URL")
   :db-username (System/getenv "DB_USERNAME")
   :db-password (System/getenv "DB_PASSWORD")
   :db-pool-size (Integer/parseInt (or (System/getenv "DB_POOL_SIZE") "10"))})


;; === Storage Initialization ===

(defn- build-full-schema
  "Builds the complete schema combining graph, cache, and versioned entities."
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (cds/extend-builder)
      (vds/extend-builder)
      (ds/build)))


(defn- create-postgres-storage
  "Creates PostgreSQL storage with full stack: versioning + caching + metrics.

   Stack architecture:
   CachedStorageWithMetrics
       └── CachedStorage
               └── VersionedStorage
                       └── PostgresStorage"
  [config]
  (let [{:keys [jdbc-url db-username db-password db-pool-size]} config
        _ (when-not jdbc-url
            (throw (ex-info "JDBC_URL is required for postgres storage"
                            {:type :configuration-error})))
        _ (log/info "Connecting to PostgreSQL..." jdbc-url)
        ;; Build schema with all entities
        schema (build-full-schema)
        ;; Create and initialize PostgreSQL storage
        pg-config {:jdbc-url jdbc-url
                   :username db-username
                   :password db-password
                   :pool-size db-pool-size}
        pg-storage (-> (pg/create-storage pg-config)
                       (sp/initialize-with-cleanup! schema))
        _ (log/info "PostgreSQL storage initialized")
        ;; Wrap with versioning (creates 'main' branch)
        versioned (vs/wrap-with-versioning pg-storage)
        _ (log/info "Versioning enabled, branch:" (vs/current-branch-id versioned))
        ;; Create cache using same connection pool
        cache (cache-pg/create-cache (:pool pg-storage))
        ;; Wrap with caching + metrics
        metrics (cs/create-metrics)
        cached (cs/wrap-with-cache-and-metrics versioned cache metrics)]
    (log/info "Full storage stack initialized: PostgreSQL + Versioning + Cache + Metrics")
    {:storage cached
     :metrics metrics
     :pg-storage pg-storage}))


(defn create-storage
  "Creates storage based on configuration.

   Returns a map with:
   - :storage - the storage instance to use
   - :metrics - cache metrics (only for postgres mode)
   - :pg-storage - underlying PostgreSQL storage (only for postgres mode)"
  [config]
  (case (:storage-type config)
    :memory {:storage (gsm/create-storage)
             :metrics nil
             :pg-storage nil}
    :postgres (create-postgres-storage config)
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
   - :metrics - cache metrics (only for postgres mode)
   - :pg-storage - underlying PostgreSQL storage (for cleanup)"
  [config]
  (println "Starting Graphden Executor Runtime...")
  (println "  Port:" (:port config))
  (println "  Storage:" (:storage-type config))

  ;; 1. Create and initialize storage
  (let [{:keys [storage metrics pg-storage]} (create-storage config)
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
    (when metrics
      (println "  Storage: PostgreSQL + Versioning + Cache + Metrics"))

    {:storage storage
     :server server
     :context ctx
     :fns fns
     :metrics metrics
     :pg-storage pg-storage}))


(defn stop-server!
  "Stops the server and closes storage."
  [{:keys [server storage metrics]}]
  (println "Stopping server...")
  (when server
    ;; http-kit server is a function - calling it stops the server
    (server))
  (when metrics
    (let [{:keys [hits misses hit-rate]} (cs/get-metrics metrics)]
      (println "Cache metrics - Hits:" hits "Misses:" misses "Hit rate:" (format "%.2f" (* 100.0 hit-rate)) "%")))
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
