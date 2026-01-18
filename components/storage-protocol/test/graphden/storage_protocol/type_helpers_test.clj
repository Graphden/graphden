(ns graphden.storage-protocol.type-helpers-test
  "Tests for type helper functions and query validations."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.interface :as storage]))


;; === Additional Helper Function Tests ===

(deftest canonical-type?-test
  (testing "returns true for canonical types"
    (is (true? (storage/canonical-type? :uuid)))
    (is (true? (storage/canonical-type? :text)))
    (is (true? (storage/canonical-type? :int)))
    (is (true? (storage/canonical-type? :bool)))
    (is (true? (storage/canonical-type? :numeric)))
    (is (true? (storage/canonical-type? :timestamptz)))
    (is (true? (storage/canonical-type? :bytes)))
    (is (true? (storage/canonical-type? :jsonb)))
    (is (true? (storage/canonical-type? :ref)))
    (is (true? (storage/canonical-type? :enum)))
    (is (true? (storage/canonical-type? :union))))

  (testing "returns false for non-canonical types"
    (is (false? (storage/canonical-type? :unknown)))
    (is (false? (storage/canonical-type? :string)))
    (is (false? (storage/canonical-type? :integer)))
    (is (false? (storage/canonical-type? nil)))))


(deftest reference-type?-test
  (testing "returns true for reference types"
    (is (true? (storage/reference-type? :ref))))

  (testing "returns false for non-reference types"
    (is (false? (storage/reference-type? :uuid)))
    (is (false? (storage/reference-type? :text)))
    (is (false? (storage/reference-type? :jsonb)))))


(deftest complex-type?-test
  (testing "returns true for complex types"
    (is (true? (storage/complex-type? :jsonb)))
    (is (true? (storage/complex-type? :union))))

  (testing "returns false for non-complex types"
    (is (false? (storage/complex-type? :text)))
    (is (false? (storage/complex-type? :int)))
    (is (false? (storage/complex-type? :ref)))))


(deftest standard-query-validations!-test
  (testing "passes for valid query"
    (let [fields {:name {:type :text} :age {:type :int}}]
      (is (nil? (storage/standard-query-validations! :user fields {:name "Alice"})))))

  (testing "passes with nil where clause"
    (is (nil? (storage/standard-query-validations! :user nil nil))))

  (testing "throws for invalid where type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"where clause must be nil or a map"
          (storage/standard-query-validations! :user nil "invalid"))))

  (testing "throws for unknown field in where clause"
    (let [fields {:name {:type :text}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown field"
            (storage/standard-query-validations! :user fields {:unknown "value"})))))

  (testing "throws for type mismatch in where clause"
    (let [fields {:age {:type :int}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch"
            (storage/standard-query-validations! :user fields {:age "not-an-int"}))))))


;; === validate-where-clause-fields! tests ===

(deftest validate-where-clause-fields!-test
  (testing "passes for known fields"
    (let [fields {:name {:type :text} :age {:type :int}}]
      (is (nil? (storage/validate-where-clause-fields! :user fields {:name "test"})))))

  (testing "passes for empty where clause"
    (is (nil? (storage/validate-where-clause-fields! :user {:name {:type :text}} {}))))

  (testing "throws for unknown field"
    (let [fields {:name {:type :text}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown field"
            (storage/validate-where-clause-fields! :user fields {:unknown "value"})))))

  (testing "exception contains correct data"
    (let [fields {:name {:type :text}}]
      (try
        (storage/validate-where-clause-fields! :user fields {:bad-field 123})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/unknown-field (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= :bad-field (:field (ex-data e)))))))))
