(ns graphden.packages.web.http.impls
  "Implementations for web/http base functions using http-kit."
  (:require
    [org.httpkit.server :as http-kit]))


(defn- stringify-keys
  "Adapts map keys to Ring string format (keyword keys → their name)."
  [m]
  (when m
    (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) m))))


(defn http-server
  [{:keys [handler port default-headers]}]
  (let [
        base-headers (stringify-keys (or default-headers {}))
        ring-handler (fn [request]
                       (let [req-map {:method (name (:request-method request))
                                      :uri (:uri request)
                                      :query-string (:query-string request)
                                      :headers (:headers request)
                                      :body (when-let [b (:body request)]
                                              (slurp b))}
                             response (handler req-map)]
                         {:status (or (:status response) 200)
                          :headers (merge base-headers
                                          (stringify-keys (or (:headers response) {})))
                          :body (or (:body response) "")}))]
    (http-kit/run-server ring-handler {:port port})))


(defn http-stop
  [{:keys [server]}]
  (when server
    (server)
    nil))


;; === Registry ===

(def impls
  {:http-server http-server
   :http-stop http-stop})
