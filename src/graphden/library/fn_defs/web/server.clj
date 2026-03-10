(ns graphden.library.fn-defs.web.server
  "Web server fn-defs - minimal API server setup.

   This component defines fn entities (NOT base-fns) that compose
   base functions from other components to create a working web server.

   Uses common building blocks from common.clj for:
   - Route composition (get-route, post-route, etc.)
   - Response hierarchy (ok-response, json-ok-response, etc.)
   - Health status composition

   ## Endpoints

   - GET / - Welcome page (HTML)
   - GET /health - Health check (JSON: {status, timestamp})
   - GET /metrics - System metrics (JSON: {jvm, memory, threads, os})

   ## Key Principle

   Base-fns are wrappers around pure functions (Clojure core or libraries).
   All concrete values and function compositions are done via fn entities.
   This component has NO base-fns - only fn-defs."
  (:require
    [graphden.library.fn-defs.web.common :as common]))


;; =============================================================================
;; SERVER-SPECIFIC FN-DEFS
;; =============================================================================

(def server-fn-defs
  "Server-specific fn-defs (endpoints, routes, router, server).

   Uses building blocks from common.clj:
   - get-route, json-ok-response, html-ok-response, health-status, etc."
  [;; ============================================================
   ;; HELLO ROUTE (Static HTML)
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


;; =============================================================================
;; COMBINED FN-DEFS
;; =============================================================================

(def fn-defs
  "All fn-defs for web server.

   Includes:
   - Common building blocks (23 fn-defs from common.clj)
   - Server-specific fn-defs (17 fn-defs)

   Total: 40 fn-defs"
  (into common/fn-defs server-fn-defs))


(def startup-fn-name
  "Name of the function to execute at startup."
  :web-server)
