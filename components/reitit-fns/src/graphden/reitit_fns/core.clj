(ns graphden.reitit-fns.core
  "Reitit routing base functions.

   Provides a router that creates Ring handlers from route definitions."
  (:require
    [clojure.string :as str]
    [graphden.fn-registry.macros :refer [defbase]]
    [reitit.core :as r]))


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
  {:args {:routes :jsonb}
   :return-type :fn}
  (let [compiled-router (r/router routes)]
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
