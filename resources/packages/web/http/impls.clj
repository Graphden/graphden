(ns graphden.packages.web.http.impls
  "Implementations for web/http base functions using http-kit."
  (:require
    [clojure.string :as str]
    [org.httpkit.server :as http-kit]))


(defn- stringify-keys [m]
  (when m
    (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) m))))


(defn- extract-bearer [headers]
  (let [header (get headers "authorization" "")]
    (when (str/starts-with? header "Bearer ")
      (subs header 7))))


(defn ring-adapter
  "Wraps an internal handler into a Ring handler.
   Adapts request/response formats, merges default headers,
   optionally checks Bearer auth for paths matching prefix."
  [{:keys [handler default-headers auth-token auth-path-prefix auth-fail-response]}]
  (let [base-headers (stringify-keys (or default-headers {}))]
    (fn [ring-request]
      (let [uri (:uri ring-request)
            headers (:headers ring-request)]
        (if (and auth-token auth-path-prefix
                 (str/starts-with? uri auth-path-prefix)
                 (not= (extract-bearer headers) auth-token))
          {:status (or (get auth-fail-response "status") 401)
           :headers (stringify-keys (or (get auth-fail-response "headers") {}))
           :body (or (get auth-fail-response "body") "Unauthorized")}
          (let [response (handler {:method (name (:request-method ring-request))
                                   :uri uri
                                   :query-string (:query-string ring-request)
                                   :headers headers
                                   :body (when-let [b (:body ring-request)] (slurp b))})]
            {:status (or (:status response) 200)
             :headers (merge base-headers (stringify-keys (or (:headers response) {})))
             :body (or (:body response) "")}))))))


(defn http-server
  [{:keys [handler port]}]
  (http-kit/run-server handler {:port port}))


(defn http-stop
  [{:keys [server]}]
  (when server (server) nil))


;; === Registry ===

(def impls
  {:ring-adapter ring-adapter
   :http-server http-server
   :http-stop http-stop})
