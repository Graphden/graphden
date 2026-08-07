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
