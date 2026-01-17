(ns graphden.reitit-fns.core
  "Reitit routing base functions.

   Provides a simple matcher function for HTTP routing.

   ## Route Format

   Routes use native reitit format - vector of [path data] pairs:
   [[\"/\" {:get {:handler home-handler}}]
    [\"/api/users\" {:get {:handler list-users}
                     :post {:handler create-user}}]
    [\"/api/users/:id\" {:get {:handler get-user}}]]

   ## Usage

   Create a matcher once, then use it for each request:
   (let [matcher (reitit-matcher routes)]
     (matcher request))  ; returns match or nil"
  (:require
    [clojure.string :as str]
    [graphden.fn-registry.macros :refer [defbase]]
    [reitit.core :as r]))


(defbase reitit-matcher
  "Creates a route matcher from route definitions.

   Arguments:
   - routes: Vector of routes in native reitit format: [[path data] ...]

   Example:
   [[\"/\" {:get {:handler home-handler}}]
    [\"/api/users\" {:get {:handler list-users}
                     :post {:handler create-user}}]
    [\"/api/users/:id\" {:get {:handler get-user}}]]

   Returns a function that takes a request and returns a match map:
   {:handler - The handler for the matched route
    :path-params - Map of path parameters
    :method - HTTP method as keyword}

   Or nil if no route matches."
  {:args {:routes :jsonb}
   :return-type :fn}
  (let [router (r/router routes)]
    (fn [request]
      (when-let [match (r/match-by-path router (:uri request))]
        (let [method (if (keyword? (:method request))
                       (:method request)
                       (keyword (str/lower-case (:method request))))
              route-data (:data match)
              method-data (get route-data method)]
          (when method-data
            {:handler (:handler method-data)
             :path-params (:path-params match)
             :method method}))))))


;; === All Definitions ===

(def all-defs
  "All reitit base function definitions."
  {:reitit-matcher reitit-matcher})
