(ns graphden.library.fn-defs.web.server
  "Web server fn-defs - minimal API server setup.

   This component defines fn entities (NOT base-fns) that compose
   base functions from other components to create a working web server.

   ## Architecture

   Routes are built compositionally using base-fns:
   - const: creates Ring handler functions from response maps
   - to-json-string + ring-response + make-handler: composable JSON handler
   - assoc: builds route data structures {:handler fn}
   - vector: combines path + route-data into route tuples

   web-server-fn
     |-- http-server with handler=router-fn, port=8080

   router-fn
     |-- router with routes=[hello-route, health-route, metrics-route]

   Each route is built as: [path {:method {:handler handler-fn}}]

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

   Routes are built compositionally using conj to build vectors:
   1. const/make-handler creates Ring handler fn
   2. assoc builds {:handler fn} and {:get {...}} maps
   3. conj builds route tuple: [] -> [\"/\"] -> [\"/\" {...}]
   4. conj collects routes: [] -> [[route1]] -> [[route1] [route2]]

   JSON handlers are built compositionally:
   - to-json-string: converts data to JSON string
   - ring-response: creates {:status :headers :body} map
   - make-handler: creates (fn [_request] response)

   Arg value syntax:
   - :fn-name = pass fn as callable (don't execute)
   - :fn-name> = execute fn and use result"
  [;; === Hello Route ===
   ;; Ring handler: (fn [_] hello-response)
   {:name :hello-handler-fn
    :parent :const
    :args {:x hello-response}}

   ;; {:handler <fn>} - execute hello-handler-fn to get Clojure fn, then put in map
   {:name :hello-handler-map-fn
    :parent :assoc
    :args {:m {}, :k "handler", :v :hello-handler-fn>}}

   ;; {:get {:handler <fn>}} - execute hello-handler-map-fn to get the map
   {:name :hello-method-map-fn
    :parent :assoc
    :args {:m {}, :k "get", :v :hello-handler-map-fn>}}

   ;; Build route tuple: [] -> ["/"] -> ["/" {...}]
   {:name :hello-route-path-fn
    :parent :conj
    :args {:coll [], :x "/"}}

   {:name :hello-route-fn
    :parent :conj
    :args {:coll :hello-route-path-fn>, :x :hello-method-map-fn>}}

   ;; === Health Route (Dynamic JSON) ===
   ;; Compositional JSON handler: to-json-string -> ring-response -> make-handler
   {:name :health-json-body-fn
    :parent :to-json-string
    :args {:data :health-status>}}

   {:name :health-response-fn
    :parent :ring-response
    :args {:status 200
           :headers json-headers
           :body :health-json-body-fn>}}

   {:name :health-handler-fn
    :parent :make-handler
    :args {:response :health-response-fn>}}

   ;; {:handler <fn>}
   {:name :health-handler-map-fn
    :parent :assoc
    :args {:m {}, :k "handler", :v :health-handler-fn>}}

   ;; {:get {:handler <fn>}}
   {:name :health-method-map-fn
    :parent :assoc
    :args {:m {}, :k "get", :v :health-handler-map-fn>}}

   ;; Build route tuple: ["/health" {...}]
   {:name :health-route-path-fn
    :parent :conj
    :args {:coll [], :x "/health"}}

   {:name :health-route-fn
    :parent :conj
    :args {:coll :health-route-path-fn>, :x :health-method-map-fn>}}

   ;; === Metrics Route (JVM Info) ===
   ;; Compositional JSON handler: to-json-string -> ring-response -> make-handler
   {:name :metrics-json-body-fn
    :parent :to-json-string
    :args {:data :jvm-info>}}

   {:name :metrics-response-fn
    :parent :ring-response
    :args {:status 200
           :headers json-headers
           :body :metrics-json-body-fn>}}

   {:name :metrics-handler-fn
    :parent :make-handler
    :args {:response :metrics-response-fn>}}

   ;; {:handler <fn>}
   {:name :metrics-handler-map-fn
    :parent :assoc
    :args {:m {}, :k "handler", :v :metrics-handler-fn>}}

   ;; {:get {:handler <fn>}}
   {:name :metrics-method-map-fn
    :parent :assoc
    :args {:m {}, :k "get", :v :metrics-handler-map-fn>}}

   ;; Build route tuple: ["/metrics" {...}]
   {:name :metrics-route-path-fn
    :parent :conj
    :args {:coll [], :x "/metrics"}}

   {:name :metrics-route-fn
    :parent :conj
    :args {:coll :metrics-route-path-fn>, :x :metrics-method-map-fn>}}

   ;; === Routes Collection ===
   ;; Build routes: [] -> [[hello]] -> [[hello] [health]] -> [[hello] [health] [metrics]]
   {:name :routes-with-hello-fn
    :parent :conj
    :args {:coll [], :x :hello-route-fn>}}

   {:name :routes-with-health-fn
    :parent :conj
    :args {:coll :routes-with-hello-fn>, :x :health-route-fn>}}

   {:name :routes-fn
    :parent :conj
    :args {:coll :routes-with-health-fn>, :x :metrics-route-fn>}}

   ;; === Router & Server ===
   ;; Router receives routes vector (executed)
   {:name :router-fn
    :parent :router
    :args {:routes :routes-fn>}}

   ;; Server receives router RESULT (executed Clojure fn) as handler
   ;; Note: :router-fn> means execute router-fn and use the result (Ring handler)
   {:name :web-server
    :parent :http-server
    :args {:handler :router-fn>
           :port 8080}}])


(def startup-fn-name
  "Name of the function to execute at startup."
  :web-server)
