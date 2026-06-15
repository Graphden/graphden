(ns graphden.packages.web.http.impls
  "Implementations for web/http base functions.

   Thin wrappers around http-kit. Request adaptation, header merging,
   and auth are composed from fn-defs elsewhere."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [org.httpkit.server :as http-kit]))


(defn- stringify-response-headers
  "http-kit's `HeaderMap.camelCase` does `(String) key` on every entry
   of the response `:headers` map — keyword keys throw
   `ClassCastException` mid-write. Stringify at this adapter so the
   fn-graph can keep keyword-keyed records end-to-end (storage round-
   trip keywordises map keys; record-types in graphden are keyword-
   keyed). One conversion point, instead of every response-building
   fn-def having to wedge a `:stringify-map-keys` step into the graph."
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


(defn- realize-body
  "Eagerly slurp the request `:body` InputStream into a UTF-8 String
   so downstream graph fn-defs that read the body more than once (the
   per-call `*call-cache*` keys by the caller's full env — and a
   parse-stage referenced from both a `:cond` validation chain AND a
   sibling `:apply` ref can land in two cache slots) don't hit an
   exhausted stream on the second consumer.

   Without this, anything that read the body more than once produced
   a silently-wrong second result: nil keys, empty vectors, and
   downstream apply stages writing rows with `:name nil` and zero
   slots. See C19 regression hunt 2026-06-03.

   `crud.request/read-json-body` already accepts strings, maps, and
   InputStreams — once we hand it a String the rest of the chain
   works without changes."
  [req]
  (let [b (:body req)]
    (if (instance? java.io.InputStream b)
      (assoc req :body (slurp (java.io.InputStreamReader. b "UTF-8")))
      req)))


(def ^:private gzip-min-size
  "Skip the gzip overhead for bodies under this many bytes — the
   Content-Encoding header plus GZIP framing eats the savings on
   tiny responses. 1 KB is the conventional crossover (CloudFront /
   nginx defaults sit at 256-1024); pick the conservative end."
  1024)


(defn- accepts-gzip?
  [req]
  (when-let [h (or (get-in req [:headers "accept-encoding"])
                   (get-in req [:headers "Accept-Encoding"]))]
    (re-find #"\bgzip\b" h)))


(defn- gzip-bytes
  ^bytes [^bytes raw]
  (let [out (java.io.ByteArrayOutputStream. (count raw))]
    (with-open [gz (java.util.zip.GZIPOutputStream. out)]
      (java.util.zip.GZIPOutputStream/.write gz raw 0 (count raw)))
    (java.io.ByteArrayOutputStream/.toByteArray out)))


(defn- maybe-gzip-response
  "Compress `:body` with gzip when:
   - the request advertises `Accept-Encoding: gzip`, AND
   - the response is a String or byte[] body ≥ `gzip-min-size` bytes,
     AND
   - the response doesn't already carry `Content-Encoding`.

   `:content-type` text/json/javascript/svg/html are the typical
   JSON-on-wire payloads the editor downloads (`/api/graph/entities`
   sits at ~3 MB raw, ~150 KB gzipped). Binary content-types (images,
   pre-compressed archives) are passed through untouched.

   The response shape stays Ring-compatible — `:body` becomes a
   `byte[]`, `Content-Encoding: gzip` is set, `Content-Length` is
   updated, and `Vary: Accept-Encoding` is appended so caches don't
   serve a compressed payload to a client that didn't ask for it."
  [req resp]
  (let [headers (:headers resp)
        ce (or (get headers "content-encoding") (get headers "Content-Encoding"))
        ct (or (get headers "content-type") (get headers "Content-Type") "")
        compressible? (and (string? ct)
                           (some #(re-find % ct)
                                 [#"(?i)\bjson\b"
                                  #"(?i)\btext\b"
                                  #"(?i)\bjavascript\b"
                                  #"(?i)\bxml\b"
                                  #"(?i)\bsvg\b"
                                  #"(?i)\bhtml\b"]))
        body (:body resp)
        ^bytes raw (cond
                     (and (string? body) compressible?)
                     (String/.getBytes ^String body "UTF-8")

                     (and (bytes? body) compressible?)
                     body)
        big-enough? (and raw (>= (alength raw) gzip-min-size))]
    (if (and (accepts-gzip? req) (not ce) big-enough?)
      (let [compressed (gzip-bytes raw)
            new-headers (-> headers
                            (assoc "Content-Encoding" "gzip"
                                   "Content-Length" (str (alength compressed)))
                            (update "Vary" #(if % (str % ", Accept-Encoding")
                                                "Accept-Encoding")))]
        (assoc resp :body compressed :headers new-headers))
      resp)))


(defbase http-server
  [handler port]
  (cr/record-effect! :network)
  ;; :process — http-kit spawns a listener thread that lives past
  ;; this call. The returned stopper kills it; service registry's
  ;; validate-create requires :process for service-eligibility.
  (cr/record-effect! :process)
  (http-kit/run-server
    (fn [req]
      (let [realized (realize-body req)
            resp (handler realized)]
        (->> resp
             stringify-response-headers
             (maybe-gzip-response realized))))
    {:port port}))


(defbase http-stop
  [server]
  (cr/record-effect! :network)
  (when server (server) nil))


(def impls
  {:http-server http-server
   :http-stop http-stop})
