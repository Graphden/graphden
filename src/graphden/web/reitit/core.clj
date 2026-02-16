(ns graphden.web.reitit.core
  "Reitit routing base functions.

   Provides a router that creates Ring handlers from route definitions."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [graphden.executor.registry.macros :refer [defbase]]
    [reitit.core :as r]))


(defn- keywordize-map-keys
  "Recursively converts string map keys to keywords.
   This allows routes to be built with string keys (to avoid fn-defs
   resolving keywords as fn refs) and still work with reitit."
  [m]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into {}
              (map (fn [[k v]]
                     [(if (string? k) (keyword k) k) v])
                   x))
        x))
    m))


(defbase router
  "Creates a Ring handler from routes.

   Arguments:
   - routes: Vector of routes in reitit format with handlers inline:
             [[path {:method {:handler handler-fn}}] ...]

   Example:
   [[\"/\" {:get {:handler hello-fn}}]
    [\"/health\" {:get {:handler health-fn}}]
    [\"/users/:id\" {:get {:handler get-user-fn}
                     :delete {:handler delete-user-fn}}]]

   Returns a Ring handler function that:
   1. Matches request URI and method
   2. Calls the handler with request (+ path-params)
   3. Returns 404 if no match"
  {:args {:routes :any}
   :return-type :fn}
  (let [;; Convert string keys to keywords (fn-defs uses strings for literal keys)
        normalized-routes (keywordize-map-keys routes)
        compiled-router (r/router normalized-routes)]
    (fn [request]
      (if-let [match (r/match-by-path compiled-router (:uri request))]
        (let [method (if (keyword? (:method request))
                       (:method request)
                       (keyword (str/lower-case (str (:method request)))))
              route-data (:data match)
              method-data (get route-data method)]
          (if method-data
            (if-let [handler-fn (:handler method-data)]
              (handler-fn (assoc request :path-params (:path-params match)))
              {:status 500
               :headers {"Content-Type" "text/plain"}
               :body "Handler not configured"})
            {:status 405
             :headers {"Content-Type" "text/plain"}
             :body "Method Not Allowed"}))
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "Not Found"}))))


;; === All Definitions ===

(def all-defs
  "All reitit base function definitions."
  {:router router})
