(ns graphden.reitit-fns.core
  "Reitit routing base functions.

   Provides primitives for building HTTP routers with reitit.

   ## Route Format

   Routes are defined as a vector of [path data] pairs:
   [[\"GET\" \"/api/users\" handler-fn]
    [\"POST\" \"/api/users\" create-fn]
    [\"GET\" \"/api/users/:id\" get-user-fn]]

   ## Match Result

   When matching a request, returns a map with:
   {:handler fn       ; The handler function for this route
    :path-params {}   ; Extracted path parameters
    :method \"GET\"}  ; HTTP method

   Or nil if no match found."
  (:require
    [graphden.fn-registry.macros :refer [defbase]]
    [reitit.core :as r]))


;; === Router Creation ===

(defbase reitit-router
  "Creates a reitit router from route definitions.

   Arguments:
   - routes: Vector of route definitions, each is [method path handler]
             where method is \"GET\", \"POST\", etc.
             path is the URL pattern (e.g., \"/api/users/:id\")
             handler is the handler function (graphden fn reference)

   Returns an opaque router object that can be used with reitit-match.

   Example routes:
   [[\"GET\" \"/\" home-handler]
    [\"GET\" \"/api/users\" list-users]
    [\"POST\" \"/api/users\" create-user]
    [\"GET\" \"/api/users/:id\" get-user]]"
  {:args {:routes :jsonb}
   :return-type :any}
  (let [;; Transform routes from [method path handler] to reitit format
        ;; reitit format: [path {:methods {method {:handler handler}}}]
        grouped (group-by second routes)  ; group by path
        reitit-routes (mapv (fn [[path method-routes]]
                              [path {:methods (into {}
                                                    (map (fn [[method _ handler]]
                                                           [(keyword (.toLowerCase ^String method))
                                                            {:handler handler}])
                                                         method-routes))}])
                            grouped)]
    (r/router reitit-routes)))


(defbase reitit-match
  "Matches a request against a router.

   Arguments:
   - router: Router created by reitit-router
   - request: Request map with at least :method and :uri keys

   Returns a map with:
   - :handler - The handler function for the matched route
   - :path-params - Map of path parameters extracted from the URL
   - :method - The HTTP method

   Returns nil if no route matches."
  {:args {:router :any
          :request :jsonb}
   :return-type :jsonb}
  (when-let [match (r/match-by-path router (:uri request))]
    (let [method (keyword (.toLowerCase ^String (:method request)))
          route-data (:data match)
          method-data (get-in route-data [:methods method])]
      (when method-data
        {:handler (:handler method-data)
         :path-params (:path-params match)
         :method (name method)}))))


(defbase reitit-routes
  "Returns the routes from a router as data (for debugging/introspection).

   Arguments:
   - router: Router created by reitit-router

   Returns a vector of route paths."
  {:args {:router :any}
   :return-type :jsonb}
  (mapv first (r/routes router)))


;; === All Definitions ===

(defonce ^:private all-defs-cache
  (delay {:reitit-router reitit-router
          :reitit-match reitit-match
          :reitit-routes reitit-routes}))


(defn get-all-defs
  "Returns all reitit base function definitions."
  []
  @all-defs-cache)
