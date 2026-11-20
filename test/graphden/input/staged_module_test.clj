(ns graphden.input.staged-module-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.staged-module :as staged]
    [graphden.packages.records.ids :as ids]
    [graphden.util.hex :as hex])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)))


(defn- read-error
  [bytes checksum]
  (try (staged/read-pinned bytes checksum)
       (catch clojure.lang.ExceptionInfo failure (ex-data failure))))


(deftest staging-receipt-binds-the-original-utf8-bytes
  (let [text "{:input-format 2 :namespace \"café\" :fns []}\r\n; comment\r\n"
        original (String/.getBytes text StandardCharsets/UTF_8)
        changed (String/.getBytes (str text "\n") StandardCharsets/UTF_8)
        checksum (ids/digest-hex "SHA-256" text)]
    (is (= {:input-format 2 :namespace "café" :fns []}
           (staged/read-pinned original checksum)))
    (is (= :staged-module-checksum-mismatch (:code (read-error changed checksum))))))


(deftest staged-reader-rejects-extra-forms-and-malformed-input-without-payloads
  (doseq [text ["{} {}" "" "#secret/payload \"private\"" "#=(+ 1 2)"]]
    (is (= {:type :input/staged-module :code :invalid-staged-module-edn}
           (read-error (String/.getBytes text StandardCharsets/UTF_8)
                       (ids/digest-hex "SHA-256" text)))))
  (let [invalid-utf8 (byte-array [-61 40])
        checksum (hex/encode (MessageDigest/.digest (MessageDigest/getInstance "SHA-256") invalid-utf8))]
    (is (= :invalid-staged-module-edn (:code (read-error invalid-utf8 checksum))))))
