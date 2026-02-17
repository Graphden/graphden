(ns graphden.library.base-fns.web.reitit
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
   - not-found-response: (optional) Response for 404, default plain text
   - method-not-allowed-response: (optional) Response for 405, default plain text
   - error-response: (optional) Response for 500, default plain text

   Example:
   [[\"/\" {:get {:handler hello-fn}}]
    [\"/health\" {:get {:handler health-fn}}]
    [\"/users/:id\" {:get {:handler get-user-fn}
                     :delete {:handler delete-user-fn}}]]

   Returns a Ring handler function that:
   1. Matches request URI and method
   2. Calls the handler with request (+ path-params)
   3. Returns configured error responses for 404/405/500"
  {:args {:routes :any
          :not-found-response {:type :jsonb :required false}
          :method-not-allowed-response {:type :jsonb :required false}
          :error-response {:type :jsonb :required false}}
   :return-type :fn}
  (let [;; Convert string keys to keywords (fn-defs uses strings for literal keys)
        normalized-routes (keywordize-map-keys routes)
        compiled-router (r/router normalized-routes)
        ;; Default responses (used when not provided)
        default-404 {:status 404
                     :headers {"Content-Type" "text/plain"}
                     :body "Not Found"}
        default-405 {:status 405
                     :headers {"Content-Type" "text/plain"}
                     :body "Method Not Allowed"}
        default-500 {:status 500
                     :headers {"Content-Type" "text/plain"}
                     :body "Handler not configured"}
        ;; Use provided responses or defaults
        resp-404 (or not-found-response default-404)
        resp-405 (or method-not-allowed-response default-405)
        resp-500 (or error-response default-500)]
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
              resp-500)
            resp-405))
        resp-404))))


;; === All Definitions ===

(def all-defs
  "All reitit base function definitions."
  {:router router})
