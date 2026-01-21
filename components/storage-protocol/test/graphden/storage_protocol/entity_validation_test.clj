(ns graphden.storage-protocol.entity-validation-test
  "Tests for entity name validation and chain depth limits."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.interface :as storage]))


;; === Entity name validation tests ===

(deftest validate-entity-name-test
  (testing "valid entity names pass validation"
    (is (nil? (storage/validate-entity-name! :user "test")))
    (is (nil? (storage/validate-entity-name! :fn-schema "test")))
    (is (nil? (storage/validate-entity-name! :arg-value "test")))
    (is (nil? (storage/validate-entity-name! :my-entity-123 "test")))
    (is (nil? (storage/validate-entity-name! :a "test"))))

  (testing "rejects non-keyword entity names"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
          (storage/validate-entity-name! "user" "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
          (storage/validate-entity-name! nil "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
          (storage/validate-entity-name! 123 "test"))))

  (testing "rejects entity names with invalid characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (storage/validate-entity-name! :User "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (storage/validate-entity-name! :user! "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (storage/validate-entity-name! :user.name "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (storage/validate-entity-name! :123user "test"))))

  (testing "rejects entity names exceeding max length"
    (let [long-name (keyword (str/join (repeat 65 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds maximum length"
            (storage/validate-entity-name! long-name "test")))))

  (testing "error includes operation context"
    (try
      (storage/validate-entity-name! "bad" "create-entity")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-entity-name (:type (ex-data e))))
        (is (= "create-entity" (:operation (ex-data e))))))))


;; === Chain depth limits tests ===

(deftest chain-depth-limits-constants-test
  (testing "default-max-dependency-chain-depth is defined"
    (is (pos-int? storage/default-max-dependency-chain-depth))
    (is (= 1000 storage/default-max-dependency-chain-depth))))
