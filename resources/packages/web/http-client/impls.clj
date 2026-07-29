(ns graphden.packages.web.http-client.impls
  "Outgoing HTTP client base functions. The PLATFORM (unrestricted) path uses
   http-kit; a RESTRICTED (tenant) path uses OkHttp pinned to
   `egress/validating-dns`, so the connect-time DNS resolution is validated —
   OkHttp only ever connects to a validated-public address (closing the SSRF
   DNS-rebind window), with TLS / SNI / cert on the hostname."
  (:require
    [graphden.clients.egress :as egress]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [org.httpkit.client :as http])
  (:import
    (java.time
      Duration)
    (okhttp3
      Call
      Headers
      OkHttpClient
      OkHttpClient$Builder
      Request$Builder
      Response
      ResponseBody)))


(defn- stringify-header-keys
  "http-kit returns response headers as keyword-keyed string→string.
   The fn-graph speaks `[:map :text :text]`, so we name the keys to
   strings at the boundary — same one-shot conversion the server side
   does in the opposite direction.

   Also used on the REQUEST side: a request-headers map coming from a
   JSON body (via /api/execute) is keywordized by cheshire, so
   `{:Authorization \"…\"}` would crash http-kit's writer
   (`Keyword cannot be cast to String`). One symmetric stringify call
   fixes both directions."
  [hs]
  (when (map? hs)
    (persistent!
      (reduce-kv
        (fn [acc k v]
          (assoc! acc (if (keyword? k) (name k) (str k)) (str v)))
        (transient {})
        hs))))


;; RESTRICTED tenant egress uses OkHttp (not http-kit) for ONE reason: its
;; `Dns` hook lets us resolve+validate at CONNECT time via
;; `egress/validating-dns`, so a DNS-rebind (public at check, internal at dial)
;; can't reach an internal address — OkHttp only connects to what the resolver
;; returns, and it returns validated-public IPs only. TLS/SNI/cert stay on the
;; hostname. One shared client (shares the connection pool + resolver);
;; per-call timeout via `newBuilder`.
(defonce ^:private restricted-client
  (delay (-> (OkHttpClient$Builder.)
             (OkHttpClient$Builder/.dns egress/validating-dns)
             (OkHttpClient$Builder/.build))))


(defn- egress-blocked-cause
  "Walk `e`'s cause chain for an `:egress/*` ex-info (OkHttp may wrap the
   resolver's throw), so a rebind block surfaces as `:egress/blocked` rather
   than a generic request failure. Security does not depend on this — the
   resolver never returns an internal address, so no internal connection is
   made regardless — it only keeps the error honest."
  [e]
  (loop [t e]
    (cond
      (nil? t) nil
      (and (instance? clojure.lang.ExceptionInfo t)
           (= "egress" (some-> (:type (ex-data t)) namespace))) t
      :else (recur (ex-cause t)))))


(defn- dial-restricted-get*
  "RESTRICTED (tenant) GET via the DNS-pinned OkHttp client. Response body is
   read through the per-org byte-cap (`read-capped-string!`). A non-2xx status
   is returned with its actual code (callers branch on it); a transport failure
   throws `:egress/blocked` (rebind/internal) or `:http-client/request-failed`.
   Library adapter — no fn-graph composition."
  [url headers timeout-ms]
  (let [client (-> (OkHttpClient/.newBuilder @restricted-client)
                   (OkHttpClient$Builder/.callTimeout (Duration/ofMillis (long (or timeout-ms 10000))))
                   (OkHttpClient$Builder/.build))
        req (Request$Builder/.build
              (reduce-kv (fn [b k v] (Request$Builder/.addHeader b (str k) (str v)))
                         (Request$Builder/.url (Request$Builder.) url)
                         (or headers {})))]
    (try
      (with-open [resp (Call/.execute (OkHttpClient/.newCall client req))]
        (let [hs (Response/.headers resp)
              body (Response/.body resp)]
          {:status (Response/.code resp)
           :headers (into {} (map (fn [n] [n (Headers/.get hs n)])) (Headers/.names hs))
           :body (if body (egress/read-capped-string! (ResponseBody/.byteStream body)) "")}))
      (catch Exception e
        (throw (or (egress-blocked-cause e)
                   (ex-info (str "HTTP GET " url " failed: " (ex-message e))
                            {:type :http-client/request-failed :url url :cause (ex-message e)})))))))


(defn- do-http-get*
  "Shared get-impl — `headers` is the FINAL (merged + stringified) map. A
   RESTRICTED (tenant/cloud) execution — `*allowed-effects*` non-nil — is
   SSRF-guarded, rate-capped, and dialed through the DNS-pinned OkHttp client
   with a per-org response byte-cap. The unrestricted PLATFORM ctx dials
   http-kit and slurps in full (its own outbound to internal services is never
   gated)."
  [url headers timeout-ms]
  (if (some? cr/*allowed-effects*)
    (do
      (egress/check-target! url)          ; early clear error + defense-in-depth
      (egress/check-egress-rate!)         ; per-org outbound rate cap
      (dial-restricted-get* url headers timeout-ms))
    (let [resp @(http/get url {:headers (or headers {})
                               :timeout (or timeout-ms 10000)
                               :as :stream})
          resp-body (:body resp)
          resp-error (:error resp)]
      (when resp-error
        (when (instance? java.io.InputStream resp-body)
          (java.io.InputStream/.close ^java.io.InputStream resp-body))
        (throw (ex-info (str "HTTP GET " url " failed: " (Throwable/.getMessage resp-error))
                        {:type :http-client/request-failed
                         :url url
                         :cause (Throwable/.getMessage resp-error)})))
      {:status (or (:status resp) 0)
       :headers (or (stringify-header-keys (:headers resp)) {})
       :body (if (instance? java.io.InputStream resp-body) (slurp resp-body) (or resp-body ""))})))


(defbase http-get
  [url headers timeout-ms]
  (cr/record-effect! :network)
  ;; Destructured names DO NOT shadow defbase arg-syms (the AST
  ;; walker rewrites bare arg-syms anywhere they appear, including
  ;; inside `{:keys [...]}`). So we rename the response's `:headers`
  ;; key locally to `resp-headers` to avoid colliding with the
  ;; `headers` arg-sym. Same precaution for any future status/body
  ;; arg names that might be added.
  (do-http-get* url (stringify-header-keys headers) timeout-ms))


(defbase http-get-with-authorization
  "Generalised auth-aware HTTP GET — `:auth-value` is the FULL
   `Authorization` header value (e.g. `\"Bearer xxx\"`, `\"Token xxx\"`,
   `\"Basic <b64>\"`) and gets injected directly into the request
   headers. The slot is `[:secret :text]`, so a secret-typed value
   accumulated via graph-level `:str` propagation (e.g. from
   `:vault-get`) is accepted structurally — it never crosses a
   generic `[:map :text :text]` slot. The slot-typing IS the
   secret-flow invariant that keeps decomposition safe.

   A scheme-specific variant (e.g. `:http-get-with-bearer`,
   `:http-get-with-token`, `:http-get-with-basic`) would be a thin
   graph fn-def that prepends the scheme keyword to a secret value
   via `:str` (which propagates `[:secret :text]`) and binds the
   resulting full auth-value here — none ship yet."
  [url auth-value extra-headers timeout-ms]
  (cr/record-effect! :network)
  (let [extra (or (stringify-header-keys extra-headers) {})
        headers (assoc extra "Authorization" auth-value)]
    (do-http-get* url headers timeout-ms)))


(def impls
  {:http-get http-get
   :http-get-with-authorization http-get-with-authorization})
