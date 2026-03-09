(ns graphden.library.fn-defs.web.server
  "Web server fn-defs - minimal API server setup.

   This component defines fn entities (NOT base-fns) that compose
   base functions from other components to create a working web server.

   ## Architecture

   Routes are built compositionally using multi-level inheritance:

   Reusable building blocks (inherit from base-fns):
   - assoc-empty = assoc-any with m={}
   - assoc-handler = assoc-empty with k=\"handler\"
   - assoc-get = assoc-empty with k=\"get\"

   Route definition pattern:
   - handler-map = assoc-handler with v=handler-fn
   - method-map = assoc-get with v=handler-map
   - route = pair path method-map

   This way, when viewing a route in the UI, only the arg that was
   specifically set on that fn is shown (the inherited args are hidden).

   ## Endpoints

   - GET / - Welcome page (HTML)
   - GET /health - Health check (JSON: {status, timestamp})
   - GET /metrics - System metrics (JSON: {jvm, memory, threads, os})

   ## Key Principle

   Base-fns are wrappers around pure functions (Clojure core or libraries).
   All concrete values and function compositions are done via fn entities.
   This component has NO base-fns - only fn-defs.")


;; === Response Data ===

(def hello-response
  "Static hello response - returned for all requests to /"
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body "<html><body><h1>Graphden Executor v2</h1><p>PostgreSQL + Versioning + Cache + Metrics</p></body></html>"})


;; === Fn Definitions ===

;; JSON content type header for reuse
(def json-headers
  "Headers for JSON responses."
  {"Content-Type" "application/json"})


(def fn-defs
  "Fn definitions for creating web server.

   ## Multi-Level Inheritance

   Building blocks are created via fn inheritance:
   1. assoc-empty inherits from assoc-any, sets m={}
   2. assoc-handler inherits from assoc-empty, sets k=\"handler\"
   3. assoc-get inherits from assoc-empty, sets k=\"get\"

   Route definitions then inherit from these building blocks,
   setting only the specific arg (v or path).

   Arg value syntax:
   - :fn-name = reference to fn (stored in ref-id)
   Behavior (execute vs pass fn-id) determined by is-fn on parent arg."
  [;; ============================================================
   ;; REUSABLE BUILDING BLOCKS
   ;; These create a multi-level inheritance chain
   ;; ============================================================

   ;; Level 1: assoc with empty map preset
   ;; assoc-empty shows only (k, v) in UI - m={} is inherited
   {:name :assoc-empty
    :parent :assoc-any
    :args {:m {}}}

   ;; Level 2: assoc with k="handler"
   ;; assoc-handler shows only v in UI - m={}, k="handler" are inherited
   {:name :assoc-handler
    :parent :assoc-empty
    :args {:k "handler"}}

   ;; Level 2: assoc with k="get"
   ;; assoc-get shows only v in UI - m={}, k="get" are inherited
   {:name :assoc-get
    :parent :assoc-empty
    :args {:k "get"}}

   ;; ============================================================
   ;; HELLO ROUTE
   ;; ============================================================

   ;; Ring handler: (fn [_] hello-response)
   {:name :hello-handler-fn
    :parent :const
    :args {:x hello-response}}

   ;; {"handler" <fn>} - inherits k="handler", m={} from assoc-handler
   ;; In UI shows only: {:v :hello-handler-fn}
   {:name :hello-handler-map
    :parent :assoc-handler
    :args {:v :hello-handler-fn}}

   ;; {"get" {"handler" <fn>}} - inherits k="get", m={} from assoc-get
   ;; In UI shows only: {:v :hello-handler-map}
   {:name :hello-method-map
    :parent :assoc-get
    :args {:v :hello-handler-map}}

   ;; ["/" {:get {:handler fn}}] - pair creates 2-element vector
   ;; In UI shows only: {:a "/" :b :hello-method-map}
   {:name :hello-route
    :parent :pair
    :args {:a "/" :b :hello-method-map}}

   ;; ============================================================
   ;; HEALTH ROUTE (Dynamic JSON)
   ;; ============================================================

   ;; Compositional JSON handler: to-json-string -> ring-response -> make-handler
   {:name :health-json-body-fn
    :parent :to-json-string
    :args {:data :health-status}}

   {:name :health-response-fn
    :parent :ring-response
    :args {:status 200
           :headers json-headers
           :body :health-json-body-fn}}

   {:name :health-handler-fn
    :parent :make-handler
    :args {:response :health-response-fn}}

   ;; {"handler" <fn>} - only shows {:v :health-handler-fn}
   {:name :health-handler-map
    :parent :assoc-handler
    :args {:v :health-handler-fn}}

   ;; {"get" ...} - only shows {:v :health-handler-map}
   {:name :health-method-map
    :parent :assoc-get
    :args {:v :health-handler-map}}

   ;; ["/health" ...] - only shows path and method-map
   {:name :health-route
    :parent :pair
    :args {:a "/health" :b :health-method-map}}

   ;; ============================================================
   ;; METRICS ROUTE (JVM Info)
   ;; ============================================================

   ;; Compositional JSON handler: to-json-string -> ring-response -> make-handler
   {:name :metrics-json-body-fn
    :parent :to-json-string
    :args {:data :jvm-info}}

   {:name :metrics-response-fn
    :parent :ring-response
    :args {:status 200
           :headers json-headers
           :body :metrics-json-body-fn}}

   {:name :metrics-handler-fn
    :parent :make-handler
    :args {:response :metrics-response-fn}}

   ;; {"handler" <fn>} - only shows {:v :metrics-handler-fn}
   {:name :metrics-handler-map
    :parent :assoc-handler
    :args {:v :metrics-handler-fn}}

   ;; {"get" ...} - only shows {:v :metrics-handler-map}
   {:name :metrics-method-map
    :parent :assoc-get
    :args {:v :metrics-handler-map}}

   ;; ["/metrics" ...] - only shows path and method-map
   {:name :metrics-route
    :parent :pair
    :args {:a "/metrics" :b :metrics-method-map}}

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
