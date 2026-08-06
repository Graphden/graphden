(ns graphden.util.json-size
  "Streaming UTF-8 JSON byte measurement with an abort-at-limit guard.

   Lives in `util` so the executor's value-capture seam can enforce
   its per-entry byte cap through the SAME machinery the `:result`
   persistence caps use (`graphden.crud.fn-execution.persist`),
   without an executor→crud dependency. The point of streaming: a
   500 MB result string must be REFUSED by a 5 KB cap without ever
   materialising the JSON in memory — `json/generate-string` + `count`
   would realise it fully first."
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]))


(defn json-bytes-up-to
  "Serialize `value` to UTF-8 JSON through a streaming writer that
   counts bytes and ABORTS the moment the count exceeds `limit`.
   Returns the byte count (a long ≤ `limit`) when the value fits;
   nil when it exceeds `limit` OR cannot be JSON-encoded at all
   (unserializable values — Clojure fns, atoms, … — are treated the
   same as oversize: the storage layer would fail on them downstream
   anyway; a warn is logged so the two nil causes stay
   distinguishable in logs)."
  [value ^long limit]
  (let [counter (java.util.concurrent.atomic.AtomicLong.)
        os (proxy [java.io.OutputStream] []
             (write
               ([b]
                (when (> (java.util.concurrent.atomic.AtomicLong/.incrementAndGet counter) limit)
                  (throw (ex-info "oversize" {::oversize true}))))
               ([_b _off len]
                (when (> (java.util.concurrent.atomic.AtomicLong/.addAndGet counter (long len)) limit)
                  (throw (ex-info "oversize" {::oversize true}))))))
        w (java.io.OutputStreamWriter. os java.nio.charset.StandardCharsets/UTF_8)]
    (try
      (json/generate-stream value w)
      (java.io.Writer/.flush w)
      (java.util.concurrent.atomic.AtomicLong/.get counter)
      (catch clojure.lang.ExceptionInfo e
        (when-not (::oversize (ex-data e))
          (log/warn e "JSON-encode failed while byte-counting — treating as oversize"))
        nil)
      (catch Exception e
        (log/warn e "JSON-encode failed while byte-counting — treating as oversize")
        nil))))
