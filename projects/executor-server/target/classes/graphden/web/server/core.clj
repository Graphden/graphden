(ns graphden.web.server.core
  "Web server component - defines fn entities for HTTP server setup.

   This component defines fn entities (NOT base-fns) that compose
   base functions from other components to create a working web server.

   ## Architecture

   Routes are built compositionally using base-fns:
   - const: creates Ring handler functions from response maps
   - assoc: builds route data structures {:handler fn}
   - vector: combines path + route-data into route tuples

   web-server-fn
     └── http-server with handler=router-fn, port=8080

   router-fn
     └── router with routes=[hello-route, health-route]

   Each route is built as: [path {:method {:handler handler-fn}}]

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


(def health-response
  "Static health check response"
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"status\":\"healthy\"}"})


;; === Fn Definitions ===

(def fn-defs
  "Fn definitions for creating web server.

   Routes are built compositionally using conj to build vectors:
   1. const creates Ring handler fn from response map
   2. assoc builds {:handler fn} and {:get {...}} maps
   3. conj builds route tuple: [] -> [\"/\"] -> [\"/\" {...}]
   4. conj collects routes: [] -> [[route1]] -> [[route1] [route2]]

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

   ;; === Health Route ===
   ;; Ring handler: (fn [_] health-response)
   {:name :health-handler-fn
    :parent :const
    :args {:x health-response}}

   ;; {:handler <fn>} - execute health-handler-fn to get Clojure fn, then put in map
   {:name :health-handler-map-fn
    :parent :assoc
    :args {:m {}, :k "handler", :v :health-handler-fn>}}

   ;; {:get {:handler <fn>}} - execute health-handler-map-fn to get the map
   {:name :health-method-map-fn
    :parent :assoc
    :args {:m {}, :k "get", :v :health-handler-map-fn>}}

   ;; Build route tuple: [] -> ["/health"] -> ["/health" {...}]
   {:name :health-route-path-fn
    :parent :conj
    :args {:coll [], :x "/health"}}

   {:name :health-route-fn
    :parent :conj
    :args {:coll :health-route-path-fn>, :x :health-method-map-fn>}}

   ;; === Routes Collection ===
   ;; Build routes: [] -> [[route1]] -> [[route1] [route2]]
   {:name :routes-with-hello-fn
    :parent :conj
    :args {:coll [], :x :hello-route-fn>}}

   {:name :routes-fn
    :parent :conj
    :args {:coll :routes-with-hello-fn>, :x :health-route-fn>}}

   ;; === Router & Server ===
   ;; Router receives routes vector (executed)
   {:name :router-fn
    :parent :router
    :args {:routes :routes-fn>}}

   ;; Server receives router RESULT (executed Clojure fn) as handler
   ;; Note: :router-fn> means execute router-fn and use the result (Ring handler)
   {:name :web-server-fn
    :parent :http-server
    :args {:handler :router-fn>
           :port 8080}}])


(def startup-fn-name
  "Name of the function to execute at startup."
  :web-server-fn)
