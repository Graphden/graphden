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

   The response post-processing pipeline is fully graph-composed in
   `web/http/fns.edn`: `:process-response` = stringify header keys →
   `Connection: close` policy → content-negotiated encoding, with the
   encode TRIGGERS (compressible pattern, min size, brotli quality)
   as graph-visible bound slots. The base-fns here are single library
   calls / boundary coercions (`:gzip-bytes`, `:brotli-bytes`,
   `:utf8-bytes`, `:byte-count`, `:header-get`) plus one
   self-contained RFC-negotiation parse (`:pick-encoding`)."
  (:require
    [clojure.java.io :as io]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.postgres.graph-epoch :as epoch]
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


;; ---------------------------------------------------------------
;; PUBLIC BASE-FNS — the seams the graph composes against.
;;
;; The immutable-response cache is NO LONGER a bespoke atom + eviction
;; helper here. It is composed in `web/http/fns.edn` from the generic
;; state primitives: a `:cell` (`:response-cache-cell`) holds the map,
;; `:response-cache-get` is `(get (deref cell) key)`, and the store +
;; capacity-eviction is a `:reset` over an in-graph `:deref` / `:assoc`
;; / `:if` / `:count`. Nothing about the cache lives in Clojure any
;; more — a user can build the same cache for their own graph.
;; ---------------------------------------------------------------

(defbase realize-request-body
  [request]
  (realize-body request))


(defbase stringify-response-headers
  "Boundary coercion: keyword header keys → strings, the shape
   http-kit's writer expects. Pass-through when `:headers` isn't a
   map. The rest of the old `process-response` pipeline (Connection:
   close policy, content-negotiated encoding) is composed in
   web/http/fns.edn."
  [response]
  (stringify-headers response))


(defbase header-get
  "Case-insensitive-ish header lookup — tries the lower-cased name
   (Ring convention) first, then the exact spelling. nil when absent."
  [headers header-name]
  (header-ci headers header-name))


(defbase pick-encoding-fn
  "Content negotiation over `Accept-Encoding` (RFC 7231 §5.3) —
   returns \"br\" when brotli is acceptable, else \"gzip\" when gzip
   is, else \"identity\". q=0 forbids a coding; tokens are
   case-insensitive. Single self-contained parse — the DECISION to
   encode (and with what thresholds) lives in the graph."
  [headers]
  (let [h (header-ci headers "Accept-Encoding")]
    (cond
      (encoding-acceptable? h "br")   "br"
      (encoding-acceptable? h "gzip") "gzip"
      :else                           "identity")))


(defbase utf8-bytes-fn
  "Boundary coercion: String → UTF-8 byte[]; byte[] passes through;
   anything else (nil, InputStream, …) → nil. The graph has no bytes
   type — the value flows through `:any` slots into `:gzip-bytes` /
   `:brotli-bytes` / `:byte-count`."
  [value]
  (cond
    (string? value) (String/.getBytes ^String value "UTF-8")
    (bytes? value)  value
    :else           nil))


(defbase byte-count-fn
  "Length of a byte[] (nil-safe)."
  [bytes]
  (when bytes (alength ^bytes bytes)))


(defbase gzip-bytes-fn
  [raw]
  (let [^bytes bs raw
        out (java.io.ByteArrayOutputStream. (alength bs))]
    (with-open [gz (java.util.zip.GZIPOutputStream. out)]
      (java.util.zip.GZIPOutputStream/.write gz bs 0 (alength bs)))
    (java.io.ByteArrayOutputStream/.toByteArray out)))


(defbase brotli-bytes-fn
  [raw quality]
  (let [^bytes bs raw
        params (-> (Encoder$Parameters.)
                   (Encoder$Parameters/.setQuality (int quality)))
        out (java.io.ByteArrayOutputStream. (alength bs))]
    (with-open [br (BrotliOutputStream. out params)]
      (BrotliOutputStream/.write br bs 0 (alength bs)))
    (java.io.ByteArrayOutputStream/.toByteArray out)))


(defbase response-immutable?
  [response]
  (when-let [cc (header-ci (:headers response) "Cache-Control")]
    (and (string? cc) (boolean (re-find #"\bimmutable\b" cc)))))


(defn- http-server-tuning
  "Pod-level http-kit tuning read from env at the adapter boundary (NOT
   graph composition — it is per-deployment, like `DB_POOL_SIZE` /
   `GRAPHDEN_MAX_CONCURRENT_EXECUTIONS`). Two knobs guard against
   crash-under-load:

   - `:thread` — worker threads. http-kit's default is 4; a request
     that derefs a slow execution (`fn-execution` waits up to
     `:timeout-ms`, ~10 s) HOLDS its worker for the wait, so 4 slow
     execs pin the whole pool and starve `/health` → autoheal restarts a
     live pod. A larger pool (default 32) raises that threshold ~8×.
     Reconcile with `DB_POOL_SIZE` so workers can't outrun DB
     connections.
   - `:queue-size` — accept queue past the workers. http-kit's default
     is 20480 (effectively unbounded memory). A bounded queue (default
     512) makes an overloaded pod return 503 at the HTTP-accept layer
     instead of piling connections toward OOM."
  []
  {:thread (or (some-> (System/getenv "GRAPHDEN_HTTP_THREADS") parse-long) 32)
   :queue-size (or (some-> (System/getenv "GRAPHDEN_HTTP_QUEUE_SIZE") parse-long) 512)})


(defbase http-server
  [handler port]
  (cr/record-effect! :network)
  ;; :process — http-kit spawns a listener thread that lives past
  ;; this call. The returned stopper kills it; service registry's
  ;; validate-create requires :process for service-eligibility.
  (cr/record-effect! :process)
  ;; Request-scoped graph-epoch bump log (library-adapter boundary
  ;; plumbing, not composition): every write inside this request logs
  ;; its bump; the eager-invalidation tail drains + notes it. An
  ;; aborted request unwinds the binding, its bumps age un-noted, and
  ;; the router's grace-expiry heal covers them — a reused pool thread
  ;; can never note a dead request's bumps.
  ;; The stopper carries the listener's ENDPOINT as metadata
  ;; (`{:endpoint {:port p}}` — the port actually bound, so `:port 0`
  ;; reports the OS-picked one): the service reconciler reads it off the
  ;; returned handle and records where this service answers, so a
  ;; consumer fn can resolve the service to an address
  ;; (`:service-endpoint`, web/service). Metadata keeps the handle's
  ;; contract — a 0-arg stopper — unchanged for `:http-stop` / `:do`.
  (let [stopper
        (http-kit/run-server
          (fn [req]
            ;; Each request is a NEW logical execution: run the build-captured
            ;; handler under a fresh per-request call-cache. Without this every
            ;; concurrent request reuses the ONE HashMap the handler captured at
            ;; build time — a ConcurrentModificationException under load (eviction
            ;; racing a concurrent put) plus a cross-request memo leak. See
            ;; compile-eager/*request-call-cache*.
            (cr/with-fresh-call-cache
              (fn []
                (binding [epoch/*request-bump-log* (atom [])]
                  (handler req)))))
          (assoc (http-server-tuning) :port port))]
    (vary-meta stopper assoc :endpoint {:port (or (:local-port (meta stopper)) port)})))


(defbase http-stop
  [server]
  (cr/record-effect! :network)
  (when server (server) nil))


(def impls
  {:http-server http-server
   :http-stop http-stop
   :realize-request-body realize-request-body
   :stringify-response-headers stringify-response-headers
   :header-get header-get
   :pick-encoding pick-encoding-fn
   :utf8-bytes utf8-bytes-fn
   :byte-count byte-count-fn
   :gzip-bytes gzip-bytes-fn
   :brotli-bytes brotli-bytes-fn
   :response-immutable? response-immutable?})
