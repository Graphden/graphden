(ns graphden.web-server.core
  "Web server implementation combining http-kit and reitit.

   Provides a unified interface for running web servers with routing."
  (:require
    [graphden.fn-registry.interface :as registry]
    [graphden.fn-registry.macros :refer [defbase]]
    [graphden.http-kit-fns.interface :as http-kit-fns]
    [graphden.reitit-fns.interface :as reitit-fns]))


;; === Composite Base Functions ===

(defbase web-server
  "Starts a web server with routing.

   This is a convenience function that combines reitit routing with http-kit.
   It creates a router from routes, then starts an HTTP server with a handler
   that matches requests against the router and calls the appropriate handler.

   Arguments:
   - routes: Vector of route definitions [[method path handler] ...]
   - port: Port number to listen on

   Route format:
   [[\"GET\" \"/\" home-handler]
    [\"GET\" \"/api/users\" list-users]
    [\"POST\" \"/api/users\" create-user]]

   The handler functions receive the request map and should return a response map.

   Returns the server instance (can be stopped with http-stop)."
  {:args {:routes :jsonb
          :port :int}
   :return-type :any}
  ;; Build the router and handler inline
  ;; Note: This uses reitit/http-kit directly since we can't easily compose
  ;; base functions at this level. The composition happens in the graph.
  (let [reitit-router-impl (:impl (get (reitit-fns/get-all-defs) :reitit-router))
        reitit-match-impl (:impl (get (reitit-fns/get-all-defs) :reitit-match))
        http-server-impl (:impl (get (http-kit-fns/get-all-defs) :http-server))
        ;; Create router
        router (reitit-router-impl {:routes (delay routes)} ctx)
        ;; Create the ring handler that uses routing
        ring-handler (fn [request]
                       (let [match (reitit-match-impl
                                     {:router (delay router)
                                      :request (delay request)}
                                     ctx)]
                         (if match
                           ;; Call the matched handler
                           ;; The handler is stored in :handler of the match
                           (let [handler-fn (:handler match)
                                 ;; Add path-params to request
                                 enriched-request (assoc request
                                                         :path-params (:path-params match))]
                             ;; handler-fn should be a graphden fn callable
                             (if (fn? handler-fn)
                               (handler-fn enriched-request)
                               ;; If it's a keyword or other value, it's a placeholder
                               {:status 500
                                :headers {"Content-Type" "text/plain"}
                                :body "Handler not callable"}))
                           ;; No route matched
                           {:status 404
                            :headers {"Content-Type" "text/plain"}
                            :body "Not Found"})))]
    ;; Start the server with the routing handler
    (http-server-impl {:handler (delay ring-handler)
                       :port (delay port)}
                      ctx)))


;; === All Definitions ===

(defn get-all-defs
  "Returns all web server base function definitions.
   Includes http-kit, reitit, and composite functions."
  []
  (merge (http-kit-fns/get-all-defs)
         (reitit-fns/get-all-defs)
         {:web-server web-server}))


;; === Storage Initialization ===

(defn initialize-storage!
  "Initializes storage with web server base functions.

   This function:
   1. Registers all web server functions in the executor
   2. Syncs function schemas to storage

   Arguments:
   - storage: an initialized storage instance

   Returns the storage instance."
  [storage]
  (let [defs (get-all-defs)]
    (registry/register-base-fns! defs)
    (registry/sync-defs-to-storage! storage defs)
    storage))
