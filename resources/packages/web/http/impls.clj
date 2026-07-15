(ns graphden.packages.web.http.impls
  "Library primitives for the web/http package.

   `http-server` is the minimal http-kit wrapper — it just runs the
   supplied handler. Everything else (body realisation, response
   encoding, the immutable-response predicate) is exposed as small
   base-fns and composed in `web/http/fns.edn` via fn-defs, mirroring
   the `:branch-routing-wrap` pattern in `web.branch-router`. The
   response cache itself is composed entirely in the graph from the
   generic `:cell` / `:swap` / `:deref` state primitives — no bespoke
   atom or eviction helper lives here.

   Private Clojure helpers (`realize-body`, `stringify-headers`,
   `maybe-encode-response`) stay inline as the library-adapter
   boilerplate behind the public base-fns — they're called once
   per request and don't warrant graph dispatch overhead, but the
   COMPOSITION (when to encode, when to cache) IS graph-visible."
  (:require
    [clojure.java.io :as io]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [org.httpkit.server :as http-kit])
  (:import
    (com.aayushatharva.brotli4j
      Brotli4jLoader)
    (com.aayushatharva.brotli4j.encoder
      BrotliOutputStream
      Encoder$Parameters)
    (java.io
      File)))


;; brotli4j ships native binaries (`native-linux-x86_64` jar) that must be
;; loaded into the JVM once before any encode call. We do it at namespace load
;; (fail loudly at startup if the arch's native is missing, rather than on the
;; first brotli request) — BUT NOT via brotli4j's default extraction, which
;; drops the `.so` into a RANDOM `${tmpdir}/com_aayushatharva_brotli4j_<nanoTime>/`
;; dir marked `deleteOnExit`. That random, self-deleting path breaks CRaC restore
;; (docs/FLEET_RFC.md §5.1): CRIU records the mmap of the native lib and re-opens
;; it at restore, so the file must sit at a STABLE, image-resident path.
;;
;; So extract the `.so` ONCE to a deterministic path and point brotli4j at it via
;; its supported `brotli4j.library.path` property — brotli4j then `System.load`s
;; that path directly, with no temp dir and no `deleteOnExit`. The dir is
;; `-Dgraphden.native-lib.dir` (default `${tmpdir}/graphden-native`); a CRaC
;; image sets it to a persistent baked-in path so the checkpoint mmap survives.
(defn- ensure-stable-brotli-native!
  "Extract libbrotli.so from the classpath to a deterministic, persistent path
   and set `brotli4j.library.path`. Idempotent; returns the `.so` path."
  []
  (let [dir (io/file (or (System/getProperty "graphden.native-lib.dir")
                         (str (System/getProperty "java.io.tmpdir") "/graphden-native")))
        so  (io/file dir "libbrotli.so")]
    (File/.mkdirs dir)
    (when-not (File/.exists so)
      (with-open [in (io/input-stream (io/resource "lib/linux-x86_64/libbrotli.so"))]
        (io/copy in so)))
    (System/setProperty "brotli4j.library.path" (File/.getAbsolutePath so))
    so))


(ensure-stable-brotli-native!)
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


(defn- encoding-acceptable?
  "True iff `coding` (\"br\" / \"gzip\") is offered in the Accept-Encoding
   header with a NON-zero q-value. `br;q=0` explicitly forbids brotli
   (RFC 7231 §5.3.1) — a bare substring match would wrongly send an
   encoding the client declared unacceptable and cannot decode. Tokens
   are case-insensitive (§5.3.4)."
  [^String h ^String coding]
  (boolean
    (when h
      (some (fn [^String part]
              (let [segs (String/.split (String/.trim part) ";")
                    tok (String/.toLowerCase (String/.trim ^String (aget segs 0)))
                    q0? (some (fn [^String s]
                                (re-find #"(?i)^\s*q=0(?:\.0+)?\s*$" (String/.trim s)))
                              (rest (seq segs)))]
                (and (= tok coding) (not q0?))))
            (seq (String/.split h ","))))))


(defn- pick-encoding
  [headers]
  (let [h (header-ci headers "Accept-Encoding")]
    (cond
      (encoding-acceptable? h "br")   :br
      (encoding-acceptable? h "gzip") :gzip
      :else                           :identity)))


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
;; PUBLIC BASE-FNS — the seams the graph composes against.
;;
;; The immutable-response cache is NO LONGER a bespoke atom + eviction
;; helper here. It is composed in `web/http/fns.edn` from the generic
;; state primitives: a `:cell` (`:response-cache-cell`) holds the map,
;; `:response-cache-get` is `(get (deref cell) key)`, and the store +
;; capacity-eviction is a `:swap` over an in-graph `:assoc` / `:if` /
;; `:count`. Nothing about the cache lives in Clojure any more — a user
;; can build the same cache for their own graph.
;; ---------------------------------------------------------------

(defbase realize-request-body
  [request]
  (realize-body request))


(defbase process-response
  [request response]
  (maybe-encode request (force-close-connection (stringify-headers response))))


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
   :response-immutable? response-immutable?})
