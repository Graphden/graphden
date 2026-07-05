(ns graphden.packages.web.http.impls
  "Library primitives for the web/http package.

   `http-server` is the minimal http-kit wrapper — it just runs the
   supplied handler. Everything else (body realisation, response
   encoding, cache lookup + store) is exposed as small base-fns and
   composed in `web/http/fns.edn` via fn-defs, mirroring the
   `:branch-routing-wrap` pattern in `web.branch-router`.

   Private Clojure helpers (`realize-body`, `stringify-headers`,
   `maybe-encode-response`) stay inline as the library-adapter
   boilerplate behind the public base-fns — they're called once
   per request and don't warrant graph dispatch overhead, but the
   COMPOSITION (when to encode, when to cache) IS graph-visible."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [org.httpkit.server :as http-kit])
  (:import
    (com.aayushatharva.brotli4j
      Brotli4jLoader)
    (com.aayushatharva.brotli4j.encoder
      BrotliOutputStream
      Encoder$Parameters)))


;; brotli4j ships native binaries (`native-linux-x86_64` jar) that
;; must be loaded into the JVM once before any encode call. Doing it
;; at namespace load eliminates a per-request init check. If the
;; native isn't on the classpath for this JVM's arch, this throws
;; loudly at startup — much better than the first brotli request
;; mysteriously failing.
(Brotli4jLoader/ensureAvailability)


;; ---------------------------------------------------------------
;; PRIVATE HELPERS — called once per request from the public base-fns
;; below. Inline rather than per-step graph dispatch because graph
;; execution per byte-encoding is too coarse a unit; the relevant
;; composition (cache wrap, encode trigger) lives in fn-defs.
;; ---------------------------------------------------------------

(defn- realize-body
  [req]
  (let [b (:body req)]
    (if (instance? java.io.InputStream b)
      (assoc req :body (slurp (java.io.InputStreamReader. b "UTF-8")))
      req)))


(defn- stringify-headers
  [resp]
  (if (map? (:headers resp))
    (update resp :headers
            (fn [hs]
              (persistent!
                (reduce-kv
                  (fn [acc k v]
                    (assoc! acc (if (keyword? k) (name k) (str k)) v))
                  (transient {})
                  hs))))
    resp))


(defn- force-close-connection
  "Inject `Connection: close` into response headers. http-kit honours
   this — flushes the body, then closes the channel and releases its
   write-queue byte[] buffers. WITHOUT this, default HTTP/1.1 keep-
   alive leaves the channel + buffers in the server's tracking
   HashMap until idle-timeout. Heap dump 2026-06-21 showed 27,955
   byte[] (1.7 GB) accumulated across ~969 long-lived channels —
   90.83% of total heap. Forcing close removes the leak vector.

   Tradeoff: per-request TCP handshake cost. For the e2e suite this
   is invisible (fast loopback). For prod we'd want a smarter
   policy — close only after the heavy endpoints (e.g.
   /api/graph/entities returning 4.5MB) — but until that policy is
   in place, blanket close is the safe default given the leak risk."
  [resp]
  (if (map? (:headers resp))
    (assoc-in resp [:headers "Connection"] "close")
    resp))


(def ^:private gzip-min-size 1024)


(defn- gzip-bytes
  ^bytes [^bytes raw]
  (let [out (java.io.ByteArrayOutputStream. (alength raw))]
    (with-open [gz (java.util.zip.GZIPOutputStream. out)]
      (java.util.zip.GZIPOutputStream/.write gz raw 0 (alength raw)))
    (java.io.ByteArrayOutputStream/.toByteArray out)))


(def ^:private brotli-params
  (-> (Encoder$Parameters.) (Encoder$Parameters/.setQuality 6)))


(defn- brotli-bytes
  ^bytes [^bytes raw]
  (let [out (java.io.ByteArrayOutputStream. (alength raw))]
    (with-open [br (BrotliOutputStream. out brotli-params)]
      (BrotliOutputStream/.write br raw 0 (alength raw)))
    (java.io.ByteArrayOutputStream/.toByteArray out)))


(defn- header-ci
  ^String [headers ^String header-name]
  (or (get headers (String/.toLowerCase header-name))
      (get headers header-name)))


(defn- pick-encoding
  [headers]
  (let [h (header-ci headers "Accept-Encoding")]
    (cond
      ;; `(?i)` — Accept-Encoding tokens are case-insensitive per
      ;; RFC 7231 §5.3.4; a client sending `BR` / `GZIP` must still get
      ;; compression rather than silently falling back to identity.
      (and h (re-find #"(?i)\bbr\b" h))   :br
      (and h (re-find #"(?i)\bgzip\b" h)) :gzip
      :else                               :identity)))


(def ^:private compressible-pattern
  #"(?i)\b(?:json|text|javascript|xml|svg|html)\b")


(defn- maybe-encode
  "Compress `:body` with the encoding the request advertises (`br`
   wins over `gzip` wins over identity), when the response is
   compressible, not already encoded, and at least `gzip-min-size`
   bytes."
  [req resp]
  (let [resp-headers (:headers resp)
        ce (header-ci resp-headers "Content-Encoding")
        ct (or (header-ci resp-headers "Content-Type") "")
        encoding (pick-encoding (:headers req))
        body (:body resp)
        ^bytes raw (when (and (not= :identity encoding)
                              (not ce)
                              (re-find compressible-pattern ct))
                     (cond
                       (string? body) (String/.getBytes ^String body "UTF-8")
                       (bytes?  body) body))]
    (if (and raw (>= (alength raw) gzip-min-size))
      (let [compressed (case encoding
                         :br   (brotli-bytes raw)
                         :gzip (gzip-bytes raw))]
        (-> resp
            (assoc :body compressed)
            (update :headers
                    (fn [h]
                      (-> h
                          (assoc "Content-Encoding" (name encoding)
                                 "Content-Length" (str (alength compressed)))
                          (update "Vary" #(if % (str % ", Accept-Encoding")
                                              "Accept-Encoding")))))))
      resp)))


;; ---------------------------------------------------------------
;; Cache state — accessed by the public `:response-cache-get` /
;; `:response-cache-put-if!` base-fns. The composition that decides
;; WHEN to look up and WHEN to store lives in graph fn-defs (see
;; `:response-cache-wrap` in `web/http/fns.edn`).
;; ---------------------------------------------------------------

(def ^:private response-cache-capacity 64)
(def ^:private response-cache (atom {}))


(defn- cache-put
  [m k v]
  (let [m' (if (>= (count m) response-cache-capacity) {} m)]
    (assoc m' k v)))


;; ---------------------------------------------------------------
;; PUBLIC BASE-FNS — the seams the graph composes against.
;; ---------------------------------------------------------------

(defbase realize-request-body
  [request]
  (realize-body request))


(defbase process-response
  [request response]
  (maybe-encode request (force-close-connection (stringify-headers response))))


(defbase response-cache-get
  [key]
  (get @response-cache key))


(defbase response-cache-put-if!
  [key value when?]
  (cr/record-effect! :state)
  (when when?
    (swap! response-cache cache-put key value))
  value)


(defbase response-immutable?
  [response]
  (when-let [cc (header-ci (:headers response) "Cache-Control")]
    (and (string? cc) (boolean (re-find #"\bimmutable\b" cc)))))


(defbase http-server
  [handler port]
  (cr/record-effect! :network)
  ;; :process — http-kit spawns a listener thread that lives past
  ;; this call. The returned stopper kills it; service registry's
  ;; validate-create requires :process for service-eligibility.
  (cr/record-effect! :process)
  (http-kit/run-server handler {:port port}))


(defbase http-stop
  [server]
  (cr/record-effect! :network)
  (when server (server) nil))


(def impls
  {:http-server http-server
   :http-stop http-stop
   :realize-request-body realize-request-body
   :process-response process-response
   :response-cache-get response-cache-get
   :response-cache-put-if! response-cache-put-if!
   :response-immutable? response-immutable?})
