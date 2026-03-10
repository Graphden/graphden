(ns graphden.library.fn-defs.web.server
  "Web server fn-defs - minimal API server setup.

   This component defines fn entities (NOT base-fns) that compose
   base functions from other components to create a working web server.

   ## Route Hierarchy (Pass-Through Args Pattern)

   This uses the pass-through args mechanism where nested fn args
   are exposed to children through ref-id chains:

   Base building blocks:
   - assoc-empty: assoc-any with m={} (free: k, v)
   - assoc-handler: assoc-empty with k=\"handler\" (free: v)

   Route composition (using pass-through args):
   - method-map: assoc-empty with v=:assoc-handler
     Free args: k (from assoc-empty), v (from assoc-handler via pass-through)
   - route: pair with b=:method-map
     Free args: a (path), k (HTTP method), v (handler via pass-through)
   - get-route: route with k=\"get\"
     Free args: a (path), v (handler)
   - hello-route: get-route with a=\"/\", v=:hello-handler-fn

   This pattern reduces boilerplate: adding a new GET route requires only
   2 fn-defs (handler-fn + entity-route) instead of 4.

   ## Endpoints

   - GET / - Welcome page (HTML)
   - GET /health - Health check (JSON: {status, timestamp})
   - GET /metrics - System metrics (JSON: {jvm, memory, threads, os})

   ## Key Principle

   Base-fns are wrappers around pure functions (Clojure core or libraries).
   All concrete values and function compositions are done via fn entities.
   This component has NO base-fns - only fn-defs.")


;; === Response Data ===
;; NOTE: All response data is now defined as fn-defs using the composition pattern.
;; See HELLO ROUTE section below.


;; === Fn Definitions ===



(def fn-defs
  "Fn definitions for creating web server.

   ## Route Hierarchy (Pass-Through Args Pattern)

   Building blocks:
   - assoc-empty: assoc-any with m={} (free: k, v)
   - assoc-handler: assoc-empty with k=\"handler\" (free: v)
   - method-map: assoc-empty with v=:assoc-handler (free: k, v via pass-through)
   - route: pair with b=:method-map (free: a, k, v)
   - get-route/post-route: route with k preset (free: a, v)

   To add a new route:
   1. Create handler-fn (const for static, make-handler for dynamic)
   2. Create entity-route inheriting from get-route/post-route with a (path) and v (handler)

   Example (2 fn-defs total):
   {:name :my-handler-fn :parent :const :args {:x {:status 200 :body \"ok\"}}}
   {:name :my-route :parent :get-route :args {:a \"/my-path\" :v :my-handler-fn}}"
  [;; ============================================================
   ;; BUILDING BLOCKS (reusable abstractions)
   ;; These create the route composition hierarchy
   ;; ============================================================

   ;; assoc-empty: assoc-any with m={}
   ;; Free args: k, v
   {:name :assoc-empty
    :parent :assoc-any
    :args {:m {}}}

   ;; assoc-handler: assoc-empty with k="handler"
   ;; Free args: v (the handler function)
   {:name :assoc-handler
    :parent :assoc-empty
    :args {:k "handler"}}

   ;; ============================================================
   ;; HEALTH STATUS (fn-def composition, not base-fn)
   ;; Replaces hardcoded health-status base-fn
   ;; ============================================================

   ;; assoc-status: assoc-empty with k="status" (free: v)
   {:name :assoc-status
    :parent :assoc-empty
    :args {:k "status"}}

   ;; assoc-timestamp: assoc with k="timestamp" (free: m, v)
   {:name :assoc-timestamp
    :parent :assoc
    :args {:k "timestamp"}}

   ;; health-status-base: {"status": "healthy"}
   {:name :health-status-base
    :parent :assoc-status
    :args {:v "healthy"}}

   ;; health-status: {"status": "healthy", "timestamp": <current-time-ms>}
   ;; This fn-def replaces the hardcoded base-fn
   {:name :health-status
    :parent :assoc-timestamp
    :args {:m :health-status-base
           :v :current-time-ms}}

   ;; method-map: assoc-empty with v=:assoc-handler
   ;; The key insight: assoc-handler's free arg (v) is exposed via pass-through!
   ;; Free args: k (HTTP method), v (handler via pass-through from assoc-handler)
   {:name :method-map
    :parent :assoc-empty
    :args {:v :assoc-handler}}

   ;; route: pair with b=:method-map
   ;; Free args: a (path), k (HTTP method via pass-through), v (handler via pass-through)
   {:name :route
    :parent :pair
    :args {:b :method-map}}

   ;; ============================================================
   ;; HTTP METHOD ROUTES
   ;; These inherit from route and preset the HTTP method
   ;; ============================================================

   ;; GET route: route with k="get" (free: a, v)
   {:name :get-route
    :parent :route
    :args {:k "get"}}

   ;; POST route: route with k="post" (free: a, v)
   {:name :post-route
    :parent :route
    :args {:k "post"}}

   ;; PUT route: route with k="put" (free: a, v)
   {:name :put-route
    :parent :route
    :args {:k "put"}}

   ;; DELETE route: route with k="delete" (free: a, v)
   {:name :delete-route
    :parent :route
    :args {:k "delete"}}

   ;; PATCH route: route with k="patch" (free: a, v)
   {:name :patch-route
    :parent :route
    :args {:k "patch"}}

   ;; ============================================================
   ;; RESPONSE STATUS HIERARCHY
   ;; Each level sets one thing, enabling clean inheritance
   ;; ============================================================

   ;; Status codes (free: headers, body)
   {:name :ok-response
    :parent :ring-response
    :args {:status 200}}

   {:name :not-found-response
    :parent :ring-response
    :args {:status 404}}

   {:name :error-response
    :parent :ring-response
    :args {:status 500}}

   ;; ============================================================
   ;; CONTENT-TYPE RESPONSE HIERARCHY
   ;; Inherit from status, set content-type (free: body)
   ;; ============================================================

   ;; JSON responses
   {:name :json-ok-response
    :parent :ok-response
    :args {:headers {"Content-Type" "application/json"}}}

   ;; HTML responses
   {:name :html-ok-response
    :parent :ok-response
    :args {:headers {"Content-Type" "text/html; charset=utf-8"}}}

   ;; Plain text responses (for error messages)
   {:name :text-ok-response
    :parent :ok-response
    :args {:headers {"Content-Type" "text/plain"}}}

   {:name :text-not-found-response
    :parent :not-found-response
    :args {:headers {"Content-Type" "text/plain"}}}

   {:name :text-error-response
    :parent :error-response
    :args {:headers {"Content-Type" "text/plain"}}}

   ;; ============================================================
   ;; HELLO ROUTE (Static HTML using composition)
   ;; Uses html-ok-response hierarchy for proper inheritance
   ;; ============================================================

   ;; HTML body content (reusable const)
   {:name :hello-body
    :parent :const
    :args {:x "<html><body><h1>Graphden Executor v2</h1><p>PostgreSQL + Versioning + Cache + Metrics</p></body></html>"}}

   ;; Response inherits status=200, headers=html from html-ok-response
   {:name :hello-response-fn
    :parent :html-ok-response
    :args {:body :hello-body}}

   ;; Handler wraps response in Ring handler function
   {:name :hello-handler-fn
    :parent :make-handler
    :args {:response :hello-response-fn}}

   ;; GET / - uses pass-through args: a (path), v (handler)
   {:name :hello-route
    :parent :get-route
    :args {:a "/" :v :hello-handler-fn}}

   ;; ============================================================
   ;; HEALTH ROUTE (Dynamic JSON)
   ;; 4 fn-defs: json-body -> response -> handler + route
   ;; ============================================================

   ;; Compositional JSON handler: to-json-string -> ring-response -> make-handler
   {:name :health-json-body-fn
    :parent :to-json-string
    :args {:data :health-status}}

   {:name :health-response-fn
    :parent :json-ok-response
    :args {:body :health-json-body-fn}}

   {:name :health-handler-fn
    :parent :make-handler
    :args {:response :health-response-fn}}

   ;; GET /health - uses pass-through args
   {:name :health-route
    :parent :get-route
    :args {:a "/health" :v :health-handler-fn}}

   ;; ============================================================
   ;; METRICS ROUTE (JVM Info)
   ;; 4 fn-defs: json-body -> response -> handler + route
   ;; ============================================================

   ;; Compositional JSON handler: to-json-string -> ring-response -> make-handler
   {:name :metrics-json-body-fn
    :parent :to-json-string
    :args {:data :jvm-info}}

   {:name :metrics-response-fn
    :parent :json-ok-response
    :args {:body :metrics-json-body-fn}}

   {:name :metrics-handler-fn
    :parent :make-handler
    :args {:response :metrics-response-fn}}

   ;; GET /metrics - uses pass-through args
   {:name :metrics-route
    :parent :get-route
    :args {:a "/metrics" :v :metrics-handler-fn}}

   ;; ============================================================
   ;; ROUTES COLLECTION & SERVER
   ;; ============================================================

   ;; Build routes vector using conj-any (works with non-JSON values)
   {:name :routes-with-hello
    :parent :conj-any
    :args {:coll [], :x :hello-route}}

   {:name :routes-with-health
    :parent :conj-any
    :args {:coll :routes-with-hello, :x :health-route}}

   {:name :routes-fn
    :parent :conj-any
    :args {:coll :routes-with-health, :x :metrics-route}}

   ;; Router receives routes vector (executed)
   {:name :router-fn
    :parent :router
    :args {:routes :routes-fn}}

   ;; Server receives router RESULT (executed Clojure fn) as handler
   {:name :web-server
    :parent :http-server
    :args {:handler :router-fn
           :port 8080}}])


(def startup-fn-name
  "Name of the function to execute at startup."
  :web-server)
