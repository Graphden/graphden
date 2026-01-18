(ns graphden.storage-protocol.arg-values-test
  "Tests for arg-value merging and UUID extraction helpers."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.interface :as storage]))


;; === merge-arg-values-for-chain tests ===

(deftest merge-arg-values-for-chain-test
  (testing "returns nil for empty chain"
    (is (nil? (storage/merge-arg-values-for-chain [] []))))

  (testing "returns nil for nil chain via (seq nil)"
    (is (nil? (storage/merge-arg-values-for-chain [] nil))))

  (testing "child arg-value overrides parent"
    (let [parent-fn-id (random-uuid)
          child-fn-id (random-uuid)
          arg-schema-id (random-uuid)
          arg-values [{:id (random-uuid)
                       :owner-fn-id parent-fn-id
                       :arg-schema-id arg-schema-id
                       :value "parent-value"}
                      {:id (random-uuid)
                       :owner-fn-id child-fn-id
                       :arg-schema-id arg-schema-id
                       :value "child-value"}]
          ;; Chain: child -> parent (child first = lower position = wins)
          chain [child-fn-id parent-fn-id]
          result (storage/merge-arg-values-for-chain arg-values chain)]
      (is (= "child-value" (:value (get result arg-schema-id))))))

  (testing "uses Long/MAX_VALUE fallback for unknown owner"
    ;; This tests the edge case where an arg-value has an owner not in the chain
    (let [known-fn-id (random-uuid)
          unknown-fn-id (random-uuid)
          arg-schema-id (random-uuid)
          arg-values [{:id (random-uuid)
                       :owner-fn-id known-fn-id
                       :arg-schema-id arg-schema-id
                       :value "known"}
                      {:id (random-uuid)
                       :owner-fn-id unknown-fn-id
                       :arg-schema-id arg-schema-id
                       :value "unknown"}]
          chain [known-fn-id]
          result (storage/merge-arg-values-for-chain arg-values chain)]
      ;; Known owner should win (has lower position than MAX_VALUE)
      (is (= "known" (:value (get result arg-schema-id))))))

  (testing "handles multiple arg-schemas correctly"
    (let [fn-id (random-uuid)
          arg-schema-1 (random-uuid)
          arg-schema-2 (random-uuid)
          arg-values [{:owner-fn-id fn-id :arg-schema-id arg-schema-1 :value 1}
                      {:owner-fn-id fn-id :arg-schema-id arg-schema-2 :value 2}]
          chain [fn-id]
          result (storage/merge-arg-values-for-chain arg-values chain)]
      (is (= 1 (:value (get result arg-schema-1))))
      (is (= 2 (:value (get result arg-schema-2)))))))


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
