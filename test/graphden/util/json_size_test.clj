(ns graphden.util.json-size-test
  "Unit tests for the streaming JSON byte counter behind the
   value-capture / result-persistence byte caps
   (`graphden.crud.fn-execution.persist` `capture-value?`,
   `graphden.executor.compile-eager` capped capture).

   The contract under test: `json-bytes-up-to` returns the EXACT UTF-8
   byte count when the encoded value fits the limit, nil when it
   exceeds it (aborting mid-stream — never materialising the full
   JSON), and nil for values that cannot be JSON-encoded at all."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.util.json-size :as json-size]))


(deftest fits-returns-exact-utf8-byte-count-test
  (testing "values under the cap return their exact encoded byte length"
    (is (= 2 (json-size/json-bytes-up-to {} 100)) "{} is 2 bytes")
    (is (= 4 (json-size/json-bytes-up-to true 100)) "true is 4 bytes")
    (is (= 5 (json-size/json-bytes-up-to "abc" 100)) "\"abc\" is 5 bytes")
    (is (= 7 (json-size/json-bytes-up-to [1 2 3] 100)) "[1,2,3] is 7 bytes")))


(deftest exact-limit-boundary-test
  (testing "encoded size == limit fits; one byte less → nil"
    (let [n (json-size/json-bytes-up-to {:k "value"} 1000)]
      (is (pos? n))
      (is (= n (json-size/json-bytes-up-to {:k "value"} n))
          "a limit exactly equal to the encoded size accepts the value")
      (is (nil? (json-size/json-bytes-up-to {:k "value"} (dec n)))
          "one byte under the encoded size rejects it"))))


(deftest multi-byte-utf8-counts-bytes-not-chars-test
  (testing "non-ASCII counts UTF-8 bytes, not chars — the cap guards STORAGE"
    ;; "ёж" is 2 chars but 4 UTF-8 bytes; + 2 quote bytes = 6.
    (is (= 6 (json-size/json-bytes-up-to "ёж" 100)))
    (is (nil? (json-size/json-bytes-up-to "ёж" 5))
        "a 5-byte cap must reject the 6-byte encoding even at 2 chars")))


(deftest oversize-aborts-with-nil-test
  (testing "a value far over the cap → nil, via mid-stream abort"
    (is (nil? (json-size/json-bytes-up-to (vec (range 100000)) 64)))))


(deftest unserializable-returns-nil-test
  (testing "values cheshire cannot encode (fns, atoms) → nil, same as oversize"
    (is (nil? (json-size/json-bytes-up-to (fn no-json []) 1000)))
    (is (nil? (json-size/json-bytes-up-to (atom 1) 1000)))))


(deftest buffered-chunk-writes-count-exactly-test
  (testing "a value larger than the writer's 8 KB internal buffer — so the
            counter sees chunked array writes, not single bytes — still
            returns the exact encoded length"
    (let [s (str/join (repeat 20000 "a"))]
      (is (= 20002 (json-size/json-bytes-up-to s 100000))
          "20000 ASCII chars + 2 quote bytes"))))


(deftest oversize-aborts-in-chunked-write-path-test
  (testing "a buffer-crossing string over a small cap aborts → nil"
    (let [s (str/join (repeat 20000 "a"))]
      (is (nil? (json-size/json-bytes-up-to s 100))))))


(deftest surrogate-pair-counts-four-bytes-test
  (testing "an astral-plane char (surrogate pair in UTF-16) is 4 UTF-8 bytes"
    ;; "😀" is 1 codepoint / 2 Java chars / 4 UTF-8 bytes; + 2 quotes = 6.
    (is (= 6 (json-size/json-bytes-up-to "😀" 100)))
    (is (nil? (json-size/json-bytes-up-to "😀" 5)))))


(deftest nil-encodes-as-null-test
  (testing "nil serializes as the 4-byte JSON literal null"
    (is (= 4 (json-size/json-bytes-up-to nil 100)))))


(deftest zero-limit-rejects-everything-test
  (testing "a zero-byte cap rejects even the smallest encodable value"
    (is (nil? (json-size/json-bytes-up-to {} 0)))
    (is (nil? (json-size/json-bytes-up-to nil 0)))))


(deftest nested-structure-agrees-with-materialised-encoding-test
  (testing "the streaming count equals the byte length of the fully
            materialised encoding for a nested keyword/string/number mix"
    (let [v {:a [1 2 3]
             :b {:c "ёж" :d nil}
             :e ["текст" {:f 3.5}]}
          expected (alength (String/.getBytes (json/generate-string v) "UTF-8"))]
      (is (= expected (json-size/json-bytes-up-to v 10000)))
      (is (nil? (json-size/json-bytes-up-to v (dec expected)))
          "one byte under the materialised size rejects it"))))
