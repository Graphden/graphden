(ns graphden.storage.protocol.encoding-test
  "Tests for storage-protocol constants and the error-classifier protocol."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


;; === default-query-timeout-ms tests ===

(deftest default-query-timeout-ms-test
  (testing "default timeout is 30 seconds"
    (is (= 30000 storage/default-query-timeout-ms)))

  (testing "timeout is a positive number"
    (is (pos? storage/default-query-timeout-ms))))


;; === StorageErrorClassifier ===
(deftest storage-error-classifier-protocol-test
  (testing "StorageErrorClassifier protocol is defined"
    (is (some? storage/StorageErrorClassifier))
    (is (contains? (:sigs storage/StorageErrorClassifier) :classify-error))
    (is (contains? (:sigs storage/StorageErrorClassifier) :wrap-error))))
