(ns graphden.executor.registry.uuid-test
  "Tests for fn-registry UUID generation."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as core]
    [graphden.executor.registry.interface :as registry])
  (:import
    (java.util
      UUID)))


(use-fixtures :each exec/with-clean-registry)


;; === UUID Generation Tests ===

(deftest uuid-generation-test
  (testing "fn-schema-uuid is deterministic"
    (let [uuid1 (registry/fn-schema-uuid :test-fn)
          uuid2 (registry/fn-schema-uuid :test-fn)
          uuid3 (registry/fn-schema-uuid :other-fn)]
      (is (= uuid1 uuid2) "Same name should produce same UUID")
      (is (not= uuid1 uuid3) "Different names should produce different UUIDs")))

  (testing "arg-schema-uuid is deterministic"
    (let [uuid1 (registry/arg-schema-uuid :test-fn :arg-a)
          uuid2 (registry/arg-schema-uuid :test-fn :arg-a)
          uuid3 (registry/arg-schema-uuid :test-fn :arg-b)
          uuid4 (registry/arg-schema-uuid :other-fn :arg-a)]
      (is (= uuid1 uuid2) "Same fn+arg should produce same UUID")
      (is (not= uuid1 uuid3) "Different arg should produce different UUID")
      (is (not= uuid1 uuid4) "Different fn should produce different UUID"))))


;; === UUID-v5 Tests ===

(deftest uuid-v5-test
  (testing "uuid-v5 is deterministic"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid1 (#'core/uuid-v5 ns-uuid "test-name")
          uuid2 (#'core/uuid-v5 ns-uuid "test-name")]
      (is (= uuid1 uuid2))))

  (testing "uuid-v5 produces different UUIDs for different names"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid1 (#'core/uuid-v5 ns-uuid "name1")
          uuid2 (#'core/uuid-v5 ns-uuid "name2")]
      (is (not= uuid1 uuid2))))

  (testing "uuid-v5 produces different UUIDs for different namespaces"
    (let [ns1 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          ns2 #uuid "11111111-2222-3333-4444-555555555555"
          uuid1 (#'core/uuid-v5 ns1 "same-name")
          uuid2 (#'core/uuid-v5 ns2 "same-name")]
      (is (not= uuid1 uuid2))))

  (testing "uuid-v5 throws on empty string"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"name-str must not be blank"
            (#'core/uuid-v5 ns-uuid "")))))

  (testing "uuid-v5 handles Unicode strings"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid-cyrillic (#'core/uuid-v5 ns-uuid "тест")
          uuid-emoji (#'core/uuid-v5 ns-uuid "test🎉")]
      (is (uuid? uuid-cyrillic))
      (is (uuid? uuid-emoji))
      (is (not= uuid-cyrillic uuid-emoji))))

  (testing "uuid-v5 handles special characters"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid (#'core/uuid-v5 ns-uuid "test:with/special-chars!@#$%")]
      (is (uuid? uuid))))

  (testing "uuid-v5 throws on non-UUID namespace"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 "not-a-uuid" "name"))))

  (testing "uuid-v5 throws on non-string name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" :keyword))))

  (testing "uuid-v5 throws on nil name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d" nil))))

  (testing "uuid-v5 produces version 5 UUID"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          uuid (#'core/uuid-v5 ns-uuid "test")]
      ;; Version is in bits 12-15 of time_hi_and_version (byte 6)
      ;; For version 5, the version nibble should be 5
      (is (= 5 (UUID/.version uuid))))))


;; === uuid-v5 Error Case Tests ===

(deftest uuid-v5-validation-test
  (testing "throws when namespace-uuid is not a UUID"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 "not-a-uuid" "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 123 "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace-uuid must be a UUID"
          (#'core/uuid-v5 nil "test"))))

  (testing "throws when name-str is not a string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 (random-uuid) 123)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 (random-uuid) :keyword)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name-str must be a string"
          (#'core/uuid-v5 (random-uuid) nil))))

  (testing "error data contains correct info"
    (try
      (#'core/uuid-v5 "bad-uuid" "test")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-argument (:type (ex-data e))))
        (is (= "bad-uuid" (:namespace-uuid (ex-data e))))))
    (try
      (#'core/uuid-v5 (random-uuid) :bad-name)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-argument (:type (ex-data e))))
        (is (= :bad-name (:name-str (ex-data e)))))))

  (testing "generates deterministic UUIDs"
    (let [ns-uuid (random-uuid)
          result1 (#'core/uuid-v5 ns-uuid "test-name")
          result2 (#'core/uuid-v5 ns-uuid "test-name")]
      (is (uuid? result1))
      (is (= result1 result2))))

  (testing "different names generate different UUIDs"
    (let [ns-uuid (random-uuid)
          result1 (#'core/uuid-v5 ns-uuid "name1")
          result2 (#'core/uuid-v5 ns-uuid "name2")]
      (is (not= result1 result2))))

  (testing "different namespaces generate different UUIDs"
    (let [ns-uuid1 (random-uuid)
          ns-uuid2 (random-uuid)
          result1 (#'core/uuid-v5 ns-uuid1 "same-name")
          result2 (#'core/uuid-v5 ns-uuid2 "same-name")]
      (is (not= result1 result2)))))


;; === memoized-uuid-v5 Tests ===

(deftest memoized-uuid-v5-test
  (testing "returns same UUID for same input"
    (let [uuid1 (core/fn-schema-uuid :test-fn)
          uuid2 (core/fn-schema-uuid :test-fn)]
      (is (= uuid1 uuid2))))

  (testing "returns different UUIDs for different inputs"
    (let [uuid1 (core/fn-schema-uuid :fn-a)
          uuid2 (core/fn-schema-uuid :fn-b)]
      (is (not= uuid1 uuid2))))

  (testing "arg-schema-uuid is different from fn-schema-uuid"
    (let [fn-uuid (core/fn-schema-uuid :my-fn)
          arg-uuid (core/arg-schema-uuid :my-fn :x)]
      (is (not= fn-uuid arg-uuid))))

  (testing "same arg-name on different functions produces different UUIDs"
    (let [uuid1 (core/arg-schema-uuid :fn-a :x)
          uuid2 (core/arg-schema-uuid :fn-b :x)]
      (is (not= uuid1 uuid2)))))


;; === uuid-v5 Name Length and Null Byte Tests ===

(deftest uuid-v5-name-length-test
  (testing "throws when name-str exceeds max length (256 bytes)"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          long-name (str/join (repeat 257 "a"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"name-str exceeds maximum length"
            (#'core/uuid-v5 ns-uuid long-name)))))

  (testing "error data contains length info"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          long-name (str/join (repeat 300 "x"))]
      (try
        (#'core/uuid-v5 ns-uuid long-name)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-argument (:type (ex-data e))))
          (is (= 256 (:max-length (ex-data e))))
          (is (= 300 (:actual-length (ex-data e))))))))

  (testing "accepts name-str at exactly max length (256 bytes)"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          max-name (str/join (repeat 256 "b"))]
      (is (uuid? (#'core/uuid-v5 ns-uuid max-name))))))


(deftest uuid-v5-null-byte-test
  (testing "throws when name-str contains null bytes"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
          null-name "test\u0000name"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"name-str contains null bytes"
            (#'core/uuid-v5 ns-uuid null-name)))))

  (testing "error data for null byte"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"]
      (try
        (#'core/uuid-v5 ns-uuid "foo\u0000bar")
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-argument (:type (ex-data e)))))))))


(deftest uuid-v5-whitespace-test
  (testing "throws when name-str is only whitespace"
    (let [ns-uuid #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"name-str must not be blank"
            (#'core/uuid-v5 ns-uuid "   ")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"name-str must not be blank"
            (#'core/uuid-v5 ns-uuid "\t\n"))))))
