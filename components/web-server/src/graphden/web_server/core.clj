(ns graphden.web-server.core
  "Web server component - defines fn entities for HTTP server setup.

   This component defines fn entities (NOT base-fns) that compose
   base functions from other components to create a working web server.

   ## Architecture

   web-server-fn
     ├── parent: http-server (from http-kit-fns)
     ├── handler -> router-fn
     └── port -> 8080

   router-fn
     ├── parent: router (from reitit-fns)
     └── routes -> [[path {:method {:handler handler-fn}}] ...]

   hello-handler-fn
     ├── parent: constantly (from base-functions)
     └── x -> hello response map

   health-handler-fn
     ├── parent: constantly (from base-functions)
     └── x -> health response map

   ## Key Principle

   Base-fns are wrappers around pure functions (Clojure core or libraries).
   All concrete values and function compositions are done via fn entities.
   This component has NO base-fns - only fn-defs.")


;; === Response Data ===

(def hello-response
  "Static hello response - returned for all requests to /"
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body "<html><body><h1>Hello from Graphden!</h1></body></html>"})


(def health-response
  "Static health check response"
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"status\":\"healthy\"}"})


;; === Fn Definitions ===

(def fn-defs
  "Fn definitions for creating web server.

   All handlers use 'constantly' base-fn to return static responses.
   No base-fns are defined in this component - we only compose
   existing base-fns from base-functions, http-kit-fns, and reitit-fns."
  [;; Handler that returns static hello response
   {:name :hello-handler-fn
    :parent :constantly
    :args {:x hello-response}}

   ;; Handler that returns static health response
   {:name :health-handler-fn
    :parent :constantly
    :args {:x health-response}}

   ;; Router with routes containing handlers inline
   {:name :router-fn
    :parent :router
    :args {:routes [["/" {:get {:handler :hello-handler-fn}}]
                    ["/health" {:get {:handler :health-handler-fn}}]]}}

   ;; HTTP server using the router
   {:name :web-server-fn
    :parent :http-server
    :args {:handler :router-fn
           :port 8080}}])


(def startup-fn-name
  "Name of the function to execute at startup."
  :web-server-fn)
