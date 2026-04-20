(ns graphden.packages.web.reitit.impls
  "Implementations for web/reitit base functions."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [graphden.executor.defbase :refer [defbase]]
    [reitit.core :as r]))


(defn- keywordize-map-keys
  "Adapts map keys to reitit keyword format (string keys → keywords, recursive)."
  [m]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v]) x))
        x))
    m))


(defbase router
  [routes not-found-response method-not-allowed-response error-response]
  (let [non-nil-routes (vec (remove nil? routes))
        normalized-routes (keywordize-map-keys non-nil-routes)
        compiled-router (r/router normalized-routes)
        resp-404 not-found-response
        resp-405 method-not-allowed-response
        resp-500 error-response]
    (fn [request]
      (let [uri (:uri request)]
        (if-let [match (r/match-by-path compiled-router uri)]
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
          resp-404)))))


;; === Registry ===

(def impls
  {:router router})
