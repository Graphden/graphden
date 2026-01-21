(ns graphden.http-kit-fns.core
  "HTTP server base functions using http-kit.

   These functions provide low-level HTTP server primitives:
   - http-server: Start server with handler function
   - http-stop: Stop a running server

   The server returned by http-server is an opaque handle (stored in context).
   It cannot be serialized to storage - these functions are meant for
   runtime-only execution.

   ## Important Notes

   1. The `http-server` function blocks indefinitely (server runs until stopped).
      Executor should be prepared to wait for it.

   2. The handler argument is a :fn type - a reference to another function
      that will be called for each request.

   3. Request/response format follows Ring spec:
      Request:  {:method :get, :uri \"/path\", :headers {...}, :body ...}
      Response: {:status 200, :headers {...}, :body ...}"
  (:require
    [graphden.fn-registry.macros :refer [defbase]]
    [org.httpkit.server :as http-kit]))


;; === Server Management ===

(defbase http-server
  "Starts an HTTP server on the specified port with the given handler.

   Arguments:
   - handler: Ring handler function (Clojure fn that takes request map, returns response map).
              This should be the RESULT of executing a router function, not a fn-id.
              Use :router-fn> syntax in fn-defs to pass the executed result.
   - port: Port number to listen on

   The handler function must accept a single argument (request map) and return
   a response map with :status, :headers, and :body.

   Request map format:
   {:request-method :get/:post/...
    :uri \"/path\"
    :query-string \"a=1&b=2\"
    :headers {\"content-type\" \"application/json\"}
    :body <InputStream or nil>}

   Response map format:
   {:status 200
    :headers {\"Content-Type\" \"text/plain\"}
    :body \"Hello World\"}

   Returns the server instance (opaque handle).
   The server runs until http-stop is called on it.

   NOTE: This function starts the server but returns immediately.
   The server runs in background threads managed by http-kit."
  {:args {:handler :any  ; Changed from :fn - handler is already a Clojure fn, not fn-id
          :port :int}
   :return-type :any}
  (let [ring-handler (fn [request]
                       ;; Convert request to jsonb-compatible format
                       (let [req-map {:method (name (:request-method request))
                                      :uri (:uri request)
                                      :query-string (:query-string request)
                                      :headers (:headers request)
                                      :body (when-let [b (:body request)]
                                              (slurp b))}
                             ;; Call the Clojure handler function directly
                             response (handler req-map)]
                         ;; Ensure response has required keys
                         {:status (or (:status response) 200)
                          :headers (or (:headers response) {})
                          :body (or (:body response) "")}))]
    (http-kit/run-server ring-handler {:port port})))


(defbase http-stop
  "Stops a running HTTP server.

   Arguments:
   - server: Server instance returned by http-server

   Gracefully shuts down the server. After calling this,
   the server will stop accepting new connections and
   existing connections will be allowed to complete."
  {:args {:server :any}
   :return-type :any}
  (when server
    (server)  ; http-kit server is a function - calling it stops the server
    nil))


;; === All Definitions ===

(def all-defs
  "All http-kit base function definitions."
  {:http-server http-server
   :http-stop http-stop})
