(ns graphden.executor-runtime.handlers
  "HTTP handlers for the executor runtime.

   These are base functions that handle HTTP requests.
   They are registered in the executor and can be referenced
   from fn entities in storage."
  (:require
    [graphden.fn-registry.macros :refer [defbase]]
    [graphden.reitit-fns.interface :as reitit-fns]))


;; === Simple Handlers ===

(defbase hello-handler
  "Simple hello world handler.
   Returns an HTML page with request info.

   Arguments:
   - request: Ring request map with :method, :uri, etc.

   Returns response map with :status, :headers, :body."
  {:args {:request :jsonb}
   :return-type :jsonb}
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<html><body>"
              "<h1>Hello from Graphden!</h1>"
              "<p>Request URI: " (:uri request) "</p>"
              "<p>Method: " (:method request) "</p>"
              "</body></html>")})


(defbase health-handler
  "Health check handler.
   Returns a simple JSON health status.

   Arguments:
   - request: Ring request map (ignored)

   Returns response map with JSON body."
  {:args {:request :jsonb}
   :return-type :jsonb}
  (let [_ request]  ; Acknowledge request arg (unused but required by handler signature)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"status\":\"healthy\"}"}))


;; === Default Router Handler ===
;;
;; For MVP, routes and handlers are hardcoded.
;; In future, this will be built from fn entities in storage.

(def ^:private default-routes
  "Default routes for the executor runtime in native reitit format."
  [["/" {:get {:handler :hello}}]
   ["/health" {:get {:handler :health}}]])


(defbase default-router-handler
  "Default HTTP router handler for the executor runtime.

   Routes:
   - GET / -> hello-handler
   - GET /health -> health-handler

   Arguments:
   - request: Ring request map

   Returns the response from the matched handler, or 404."
  {:args {:request :jsonb}
   :return-type :jsonb}
  (let [;; Create matcher from routes
        matcher-impl (:impl (get reitit-fns/all-defs :reitit-matcher))
        matcher (matcher-impl {:routes (delay default-routes)} nil)
        ;; Handler implementations
        hello-impl (:impl hello-handler)
        health-impl (:impl health-handler)
        handlers {:hello (fn [req] (hello-impl {:request (delay req)} nil))
                  :health (fn [req] (health-impl {:request (delay req)} nil))}]
    ;; Match request
    (if-let [match (matcher request)]
      ;; Found match - call the handler
      (let [handler-key (:handler match)
            handler-fn (get handlers handler-key)]
        (if handler-fn
          ;; Add path-params to request and call handler
          (let [enriched-request (assoc request :path-params (:path-params match))]
            (handler-fn enriched-request))
          ;; Handler not found in map
          {:status 500
           :headers {"Content-Type" "text/plain"}
           :body (str "Handler not found: " handler-key)}))
      ;; No route matched
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not Found"})))


;; === All Definitions ===

(def all-defs
  "All handler base function definitions."
  {:hello-handler hello-handler
   :health-handler health-handler
   :default-router-handler default-router-handler})
