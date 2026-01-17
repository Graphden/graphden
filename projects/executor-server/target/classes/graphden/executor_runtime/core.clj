(ns graphden.executor-runtime.core
  "Executor runtime - main entry point for running graphden as a web server.

   This is the entry point for the executor runtime. It:
   1. Initializes storage with all base functions
   2. Creates user-defined functions from configuration
   3. Starts the web server with configured routes

   ## Configuration

   The runtime is configured via environment variables or config map:
   - PORT: HTTP server port (default: 8080)
   - STORAGE_TYPE: 'memory', 'postgres', or 'datomic' (default: memory)

   ## Running

   ```bash
   clojure -M -m graphden.executor-runtime.core
   ```"
  (:require
    [graphden.base-functions.interface :as bf]
    [graphden.executor.interface :as executor]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp]
    [graphden.web-server.interface :as web-server]
    [org.httpkit.server :as http-kit])
  (:gen-class))


;; === Configuration ===

(def default-config
  {:port 8080
   :storage-type :memory})


(defn get-config
  "Gets configuration from environment variables or returns defaults."
  []
  {:port (Integer/parseInt (or (System/getenv "PORT") "8080"))
   :storage-type (keyword (or (System/getenv "STORAGE_TYPE") "memory"))})


;; === Hello World Handler ===

(defn hello-handler
  "Simple hello world handler for testing."
  [request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<html><body>"
              "<h1>Hello from Graphden!</h1>"
              "<p>Request URI: " (:uri request) "</p>"
              "<p>Method: " (:method request) "</p>"
              "</body></html>")})


(defn health-handler
  "Health check endpoint."
  [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"status\":\"healthy\"}"})


;; === Storage Initialization ===

(defn create-storage
  "Creates storage based on configuration."
  [config]
  (case (:storage-type config)
    :memory (gsm/create-storage)
    (throw (ex-info "Unsupported storage type" {:type (:storage-type config)}))))


(defn initialize-storage!
  "Initializes storage with all base functions."
  [storage]
  ;; Register and sync standard base functions
  (registry/register-base-fns! (bf/get-all-defs))
  (registry/sync-defs-to-storage! storage (bf/get-all-defs))

  ;; Register and sync web server functions
  (web-server/initialize-storage! storage)

  storage)


;; === Server Startup ===

(defn create-ring-handler
  "Creates a Ring handler with routing."
  [_storage]
  ;; For MVP, we use a simple static routing
  ;; In the future, this will be built from functions in storage
  (fn [request]
    (let [uri (:uri request)
          method (:request-method request)]
      (cond
        (and (= uri "/") (= method :get))
        (hello-handler {:uri uri :method "GET"})

        (and (= uri "/health") (= method :get))
        (health-handler {:uri uri :method "GET"})

        :else
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "Not Found"}))))


(defn start-server!
  "Starts the HTTP server.

   Returns a map with:
   - :server - the server stop function
   - :storage - the initialized storage"
  [config]
  (println "Starting Graphden Executor Runtime...")
  (println "  Port:" (:port config))
  (println "  Storage:" (:storage-type config))

  (let [storage (-> (create-storage config)
                    (initialize-storage!))
        handler (create-ring-handler storage)
        server (http-kit/run-server handler {:port (:port config)})]

    (println "Server started on port" (:port config))
    (println "  http://localhost:" (:port config) "/")
    (println "  http://localhost:" (:port config) "/health")

    {:server server
     :storage storage}))


(defn stop-server!
  "Stops the server and closes storage."
  [{:keys [server storage]}]
  (println "Stopping server...")
  (when server
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
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(stop-server! state)))

    ;; Block main thread to keep server running
    (println "Press Ctrl+C to stop...")
    @(promise)))  ; Block forever
