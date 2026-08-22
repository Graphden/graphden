(ns graphden.storage.protocol.encoding-test
  "Tests for encoding helpers and constants."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


;; === needs-special-encoding? tests ===

(deftest needs-special-encoding?-test
  (testing "returns true for JSONB type"
    (is (true? (storage/needs-special-encoding? :jsonb))))

  (testing "returns true for union type"
    (is (true? (storage/needs-special-encoding? :union))))

  (testing "returns true for enum type"
    (is (true? (storage/needs-special-encoding? :enum))))

  (testing "returns false for basic types"
    (is (false? (storage/needs-special-encoding? :text)))
    (is (false? (storage/needs-special-encoding? :int)))
    (is (false? (storage/needs-special-encoding? :bool)))
    (is (false? (storage/needs-special-encoding? :uuid)))
    (is (false? (storage/needs-special-encoding? :ref)))
    (is (false? (storage/needs-special-encoding? :numeric)))
    (is (false? (storage/needs-special-encoding? :timestamptz)))
    (is (false? (storage/needs-special-encoding? :bytes)))))


;; === default-query-timeout-ms tests ===

(deftest default-query-timeout-ms-test
  (testing "default timeout is 30 seconds"
    (is (= 30000 storage/default-query-timeout-ms)))

  (testing "timeout is a positive number"
    (is (pos? storage/default-query-timeout-ms))))


;; === storage-error-types tests ===
(deftest storage-error-classifier-protocol-test
  (testing "StorageErrorClassifier protocol is defined"
    (is (some? storage/StorageErrorClassifier))
    (is (contains? (:sigs storage/StorageErrorClassifier) :classify-error))
    (is (contains? (:sigs storage/StorageErrorClassifier) :wrap-error))))


;; `storage-error-types` belongs to `protocol.errors` and is pinned by
;; `errors-test`. The copy that used to live here tested the same set
;; through the facade under the same deftest name, so a failure pointed at
;; the wrong file. Its two extra members (:parse-error, :unknown-sql-error)
;; and the `set?` check moved there.
