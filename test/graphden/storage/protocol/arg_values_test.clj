(ns graphden.storage.protocol.arg-values-test
  "Tests for arg-value UUID extraction helpers."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.interface :as storage]))


;; === extract-uuid-refs-from-arg-values tests ===

(deftest extract-uuid-refs-from-arg-values-test
  (testing "extracts UUID values"
    (let [uuid1 (random-uuid)
          uuid2 (random-uuid)
          k1 (random-uuid)
          k2 (random-uuid)
          arg-values-map {k1 {:value uuid1}
                          k2 {:value uuid2}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{uuid1 uuid2} result))))

  (testing "parses UUID strings"
    (let [uuid1 (random-uuid)
          k1 (random-uuid)
          arg-values-map {k1 {:value (str uuid1)}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{uuid1} result))))

  (testing "ignores non-UUID values"
    (let [k1 (random-uuid)
          k2 (random-uuid)
          k3 (random-uuid)
          arg-values-map {k1 {:value "not-a-uuid"}
                          k2 {:value 123}
                          k3 {:value nil}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{} result))))

  (testing "handles empty map"
    (is (= #{} (storage/extract-uuid-refs-from-arg-values {}))))

  (testing "handles mixed values"
    (let [uuid1 (random-uuid)
          k1 (random-uuid)
          k2 (random-uuid)
          k3 (random-uuid)
          arg-values-map {k1 {:value uuid1}
                          k2 {:value "not-a-uuid"}
                          k3 {:value 42}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{uuid1} result)))))
