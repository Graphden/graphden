(ns graphden.packages.web.http.impls
  "Implementations for web/http base functions using http-kit."
  (:require
    [graphden.packages.core.collections.impls :as collections]
    [org.httpkit.server :as http-kit]))


(defn http-server
  [{:keys [handler port default-headers]}]
  (let [stringify-keys #(collections/stringify-map-keys-fn {:m %})
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
