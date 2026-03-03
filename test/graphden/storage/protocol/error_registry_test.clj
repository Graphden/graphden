(ns graphden.storage.protocol.error-registry-test
  "Tests for error registry extensibility."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


;; === Error Registry Extensibility Tests ===

(deftest error-registry-extensibility-test
  (testing "can register custom error types"
    (storage/register-error-type! :test-app/custom-error
                                  {:category :validation
                                   :retryable? true
                                   :severity :warning
                                   :description "Test custom error"})
    (is (contains? (storage/registered-error-types) :test-app/custom-error)))

  (testing "get-error-metadata returns registered metadata"
    (let [metadata (storage/get-error-metadata :test-app/custom-error)]
      (is (= :validation (:category metadata)))
      (is (true? (:retryable? metadata)))
      (is (= :warning (:severity metadata)))))

  (testing "error-retryable? returns correct value"
    (is (true? (storage/error-retryable? :test-app/custom-error)))
    (is (false? (storage/error-retryable? :nonexistent-error))))

  (testing "error-category returns correct value"
    (is (= :validation (storage/error-category :test-app/custom-error)))
    (is (= :unknown (storage/error-category :nonexistent-error))))

  (testing "pre-registered types exist"
    (is (contains? (storage/registered-error-types) :constraint-violation/unique))
    (is (contains? (storage/registered-error-types) :not-found))
    (is (contains? (storage/registered-error-types) :connection-error)))

  (testing "throws on invalid category"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid error category"
          (storage/register-error-type! :test-app/bad-category
                                        {:category :invalid-category}))))

  (testing "throws on non-keyword error-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be a keyword"
          (storage/register-error-type! "not-a-keyword"
                                        {:category :validation})))))


(deftest error-categories-test
  (testing "contains expected categories"
    (is (contains? storage/error-categories :constraint))
    (is (contains? storage/error-categories :validation))
    (is (contains? storage/error-categories :config))
    (is (contains? storage/error-categories :connection))
    (is (contains? storage/error-categories :execution))
    (is (contains? storage/error-categories :batch))
    (is (contains? storage/error-categories :unknown))))


(deftest error-severities-test
  (testing "contains expected severities"
    (is (contains? storage/error-severities :error))
    (is (contains? storage/error-severities :warning))
    (is (contains? storage/error-severities :info))))
