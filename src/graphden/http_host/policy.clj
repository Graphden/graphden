(ns graphden.http-host.policy
  "The finite HTTP host's transport boundary. Hosted handlers receive an
   ordinary request without editor credentials and return bounded text/JSON.
   These rules also apply on an isolated app origin, keeping the contract
   identical when an installation serves the endpoint on its editor origin."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]))


(def max-body-bytes (* 64 1024))


(def response-headers
  {"Content-Type" "text/plain; charset=utf-8"
   "Cache-Control" "no-store"
   "Content-Security-Policy" "sandbox; default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
   "X-Content-Type-Options" "nosniff"
   "X-Frame-Options" "DENY"
   "Referrer-Policy" "no-referrer"})


(defn failure
  "A platform response with the same closed response-header policy."
  [status message]
  {:status status :headers response-headers :body message})


(defn bounded-text?
  "Reject non-realized/streaming bodies and bound UTF-8 allocation itself."
  [body]
  (and (string? body)
       (<= (count body) max-body-bytes)
       (<= (alength (String/.getBytes ^String body "UTF-8")) max-body-bytes)))


(defn- header
  [headers name]
  (some (fn [[k v]] (when (= name (str/lower-case (if (keyword? k) (clojure.core/name k) (str k)))) v)) headers))


(defn- json-text?
  [body]
  (try
    (json/parse-string-strict body)
    true
    (catch Exception _ false)))


(defn response
  "Permit only realized plain text or valid JSON, with platform-owned
   headers. Tenant Set-Cookie, redirects, CORS and content negotiation never
   escape onto the editor origin. An HTML-looking plain-text body stays text."
  [{:keys [status headers body]}]
  (let [content-type (some-> (header headers "content-type")
                             str str/lower-case (str/split #";" 2) first str/trim)
        json? (= "application/json" content-type)
        valid? (and (integer? status) (<= 200 status 599)
                    (not (<= 300 status 399))
                    (bounded-text? body)
                    (contains? #{nil "text/plain" "application/json"} content-type)
                    (or (not json?) (json-text? body)))]
    (if valid?
      {:status status
       :headers (cond-> response-headers
                  json? (assoc "Content-Type" "application/json; charset=utf-8"))
       :body body}
      (failure 502 "The handler must return bounded plain text or JSON."))))


(defn- request-body
  "http-kit supplies a buffered InputStream for a nonempty request body.
   Read at most one byte beyond the limit; never realize an arbitrary stream."
  [body]
  (cond
    (nil? body) ""
    (string? body) (when (bounded-text? body) body)
    (instance? java.io.InputStream body)
    (let [body-bytes (java.io.InputStream/.readNBytes body (inc max-body-bytes))]
      (when (<= (alength body-bytes) max-body-bytes)
        (String. ^bytes body-bytes java.nio.charset.StandardCharsets/UTF_8)))
    :else nil))


(defn request
  "Build the handler's request from public transport fields. The host prefix
   has already been removed from `path`; method, query and body are preserved.
   No cookies, bearer credentials, branch override or async channel survive."
  [incoming path]
  (let [body (request-body (:body incoming))]
    (when (and (bounded-text? body)
               (or (nil? (:query-string incoming))
                   (bounded-text? (:query-string incoming))))
      {:request-method (:request-method incoming)
       :uri path
       :query-string (or (:query-string incoming) "")
       :body body
       :async-channel nil
       :headers (into {}
                      (keep (fn [name]
                              (when-let [value (header (:headers incoming) name)]
                                (when (bounded-text? value) [name value]))))
                      ["accept" "content-type" "user-agent"])})))
