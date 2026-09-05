(ns graphden.packages.web.http-client.impls
  "Outgoing HTTP client base function. ONE universal primitive —
   `http-request` — carries the method as DATA (the graph narrows and
   pins it through the fn-def ladder in fns.edn: `:standard-http-request`
   → `:http-get` / `:http-post` / …). The PLATFORM (unrestricted) path
   uses http-kit; the RESTRICTED (tenant) path uses OkHttp pinned to
   `egress/validating-dns`, so the connect-time DNS resolution is
   validated — OkHttp only ever connects to a validated-public address
   (closing the SSRF DNS-rebind window), with TLS / SNI / cert on the
   hostname. Every guard lives HERE, on the single impl, so no graph
   descendant can specialize past it."
  (:require
    [clojure.string :as str]
    [graphden.clients.egress :as egress]
    [graphden.crud.fn-execution.trace :as trace]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [org.httpkit.client :as http])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.time
      Duration)
    (okhttp3
      Call
      Headers
      OkHttpClient
      OkHttpClient$Builder
      Request$Builder
      RequestBody
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


(defn- wire-method
  "Boundary validation + normalization of the `:method` slot value.
   The type ladder (`:http-method-token` refinement) is the authoring-
   time guarantee; this is the runtime backstop for values arriving
   through /api/execute. Returns the UPPER-CASE wire form."
  [method]
  (let [m (str/upper-case (str method))]
    (when-not (re-matches #"[!#$%&'*+.^_`|~0-9A-Za-z-]+" m)
      (throw (ex-info (str "Invalid HTTP method token: " (pr-str method))
                      {:type :http-client/invalid-method :method method})))
    m))


(defn- final-headers
  "Merge the generic `:headers` map with the secret-typed `:auth-value`
   (the full Authorization header value). Auth is injected HERE, inside
   the impl, so a secret value never crosses a generic `[:map :text
   :text]` slot in the graph — the slot-typing IS the secret-flow
   boundary. Auth wins on key collision."
  [headers auth-value]
  (cond-> (or (stringify-header-keys headers) {})
    (some? auth-value) (assoc "Authorization" (str auth-value))))


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


(def ^:private body-required-methods
  "Wire methods OkHttp refuses to build without a request body. A nil
   `:body` for these sends an empty body (curl -X POST parity)."
  #{"POST" "PUT" "PATCH"})


(defn- okhttp-request-body
  "RequestBody for the wire `method`/`body` pair: text body as UTF-8
   (content-type stays a header concern — no media-type here), empty
   body when the method demands one and none was given, nil otherwise
   (OkHttp rejects a body on GET/HEAD)."
  [method body]
  (cond
    (some? body) (RequestBody/create ^bytes (String/.getBytes (str body) StandardCharsets/UTF_8))
    (body-required-methods method) (RequestBody/create ^bytes (byte-array 0))
    :else nil))


(defn- dial-restricted*
  "RESTRICTED (tenant) request via the DNS-pinned OkHttp client. Response
   body is read through the per-org byte-cap (`read-capped-string!`). A
   non-2xx status is returned with its actual code (callers branch on it);
   a transport failure throws `:egress/blocked` (rebind/internal) or
   `:http-client/request-failed`. Library adapter — no fn-graph
   composition."
  [method url headers body timeout-ms]
  (let [client (-> (OkHttpClient/.newBuilder @restricted-client)
                   (OkHttpClient$Builder/.callTimeout (Duration/ofMillis (long (or timeout-ms 10000))))
                   (OkHttpClient$Builder/.build))
        req (Request$Builder/.build
              (Request$Builder/.method
                (reduce-kv (fn [b k v] (Request$Builder/.addHeader b (str k) (str v)))
                           (Request$Builder/.url (Request$Builder.) ^String url)
                           (or headers {}))
                method
                (okhttp-request-body method body)))]
    (try
      (with-open [resp (Call/.execute (OkHttpClient/.newCall client req))]
        (let [hs (Response/.headers resp)
              resp-body (Response/.body resp)]
          {:status (Response/.code resp)
           :headers (into {} (map (fn [n] [n (Headers/.get hs n)])) (Headers/.names hs))
           :body (if resp-body (egress/read-capped-string! (ResponseBody/.byteStream resp-body)) "")}))
      (catch Exception e
        (throw (or (egress-blocked-cause e)
                   (ex-info (str "HTTP " method " " url " failed: " (ex-message e))
                            {:type :http-client/request-failed
                             :method method :url url :cause (ex-message e)})))))))


(defn- dial-platform*
  "UNRESTRICTED (platform) request via http-kit — its own outbound to
   internal services is never gated, body slurped in full."
  [method url headers body timeout-ms]
  (let [resp @(http/request (cond-> {:url url
                                     :method (keyword (str/lower-case method))
                                     :headers (or headers {})
                                     :timeout (or timeout-ms 10000)
                                     :as :stream}
                              (some? body) (assoc :body (str body))))
        resp-body (:body resp)
        resp-error (:error resp)]
    (when resp-error
      (when (instance? java.io.InputStream resp-body)
        (java.io.InputStream/.close ^java.io.InputStream resp-body))
      (throw (ex-info (str "HTTP " method " " url " failed: " (Throwable/.getMessage resp-error))
                      {:type :http-client/request-failed
                       :method method :url url
                       :cause (Throwable/.getMessage resp-error)})))
    {:status (or (:status resp) 0)
     :headers (or (stringify-header-keys (:headers resp)) {})
     :body (if (instance? java.io.InputStream resp-body) (slurp resp-body) (or resp-body ""))}))


(defbase http-request
  "The ONE outbound-HTTP primitive — method / url / headers / body /
   auth-value / timeout all data. A RESTRICTED (tenant/cloud) execution
   (`*allowed-effects*` non-nil) is SSRF-guarded, rate-capped, and dialed
   through the DNS-pinned OkHttp client with a per-org response byte-cap;
   the unrestricted PLATFORM ctx dials http-kit. Specialization (standard
   method set, per-method presets, per-resource fns) happens in the GRAPH
   — see fns.edn."
  [method url headers body auth-value timeout-ms]
  (cr/record-effect! :network)
  (let [m (wire-method method)
        hs (final-headers headers auth-value)]
    (if (some? cr/*allowed-effects*)
      (do
        (egress/check-target! url)          ; early clear error + defense-in-depth
        (egress/check-egress-rate!)         ; per-org outbound rate cap
        (dial-restricted* m url hs body timeout-ms))
      (dial-platform* m url hs body timeout-ms))))


(defbase trace-headers
  "The cross-service trace header for an outbound call — `{\"X-Graphden-
   Trace\" \"<trace>;<execution>\"}` under a persisted run, `{}` otherwise.
   Merged into `:service-get` / `:service-post`'s headers so the callee
   links the request it handles to this execution."
  []
  (trace/trace-headers))


(def impls
  {:http-request http-request
   :trace-headers trace-headers})
