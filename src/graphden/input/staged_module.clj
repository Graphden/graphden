(ns graphden.input.staged-module
  "Read exact, checksum-pinned bytes emitted by the offline staging command."
  (:require
    [clojure.edn :as edn]
    [graphden.util.hex :as hex])
  (:import
    (java.io
      PushbackReader
      StringReader)
    (java.nio
      ByteBuffer)
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)))


(defn- reject!
  [code]
  (throw (ex-info "Cannot read staged input module"
                  {:type :input/staged-module :code code})))


(defn read-pinned
  "Verify an externally supplied SHA-256 before reading exactly one UTF-8
   EDN form. Never retain bytes or reader exceptions in diagnostics."
  [bytes expected-sha256]
  (when-not (bytes? bytes)
    (reject! :staged-module-bytes-required))
  (let [bytes (aclone ^bytes bytes)
        checksum (hex/encode (MessageDigest/.digest (MessageDigest/getInstance "SHA-256") bytes))]
    (when-not (= checksum expected-sha256)
      (reject! :staged-module-checksum-mismatch))
    (try
      (let [text (str (.decode (.newDecoder StandardCharsets/UTF_8) (ByteBuffer/wrap bytes)))]
        (with-open [reader (PushbackReader. (StringReader. text))]
          (let [eof (Object.)
                options {:readers {} :eof eof}
                form (edn/read options reader)]
            (when (or (identical? eof form) (not (identical? eof (edn/read options reader))))
              (reject! :invalid-staged-module-edn))
            form)))
      (catch Exception _
        (reject! :invalid-staged-module-edn)))))
