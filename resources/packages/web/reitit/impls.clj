(ns graphden.packages.web.reitit.impls
  "Implementations for web/reitit base functions."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [clojure.walk :as walk]
    [reitit.core :as r]))


(defn- keywordize-map-keys
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


(defn router
  [{:keys [routes not-found-response method-not-allowed-response error-response]}]
  (let [non-nil-routes (vec (remove nil? routes))
        normalized-routes (keywordize-map-keys non-nil-routes)
        compiled-router (r/router normalized-routes)
        default-404 {:status 404
                     :headers {"Content-Type" "text/plain"}
                     :body "Not Found"}
        default-405 {:status 405
                     :headers {"Content-Type" "text/plain"}
                     :body "Method Not Allowed"}
        default-500 {:status 500
                     :headers {"Content-Type" "text/plain"}
                     :body "Handler not configured"}
        resp-404 (or not-found-response default-404)
        resp-405 (or method-not-allowed-response default-405)
        resp-500 (or error-response default-500)]
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
