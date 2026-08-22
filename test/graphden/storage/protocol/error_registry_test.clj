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


;; The category / severity VOCABULARIES belong to `protocol.errors` and are
;; pinned by `errors-test`. What is unique here is the REGISTRY — that a
;; downstream app can register its own error type and have the accessors
;; honour it — so that is all this file keeps.
