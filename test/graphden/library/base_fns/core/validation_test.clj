(ns graphden.library.base-fns.core.validation-test
  "Tests for base-functions.validation helper functions."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.library.base-fns.core.validation :as v]))


;; === validate-index-non-negative! tests ===

(deftest validate-index-non-negative!-test
  (testing "passes for zero"
    (is (nil? (v/validate-index-non-negative! 0 :start {:fn :test}))))

  (testing "passes for positive values"
    (is (nil? (v/validate-index-non-negative! 1 :start {:fn :test})))
    (is (nil? (v/validate-index-non-negative! 100 :start {:fn :test}))))

  (testing "throws for negative values"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"start index cannot be negative"
          (v/validate-index-non-negative! -1 :start {:fn :test}))))

  (testing "error contains correct type and context"
    (try
      (v/validate-index-non-negative! -5 :end {:fn :substr :arg "hello"})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :execution-error/invalid-index (:type (ex-data e))))
        (is (= -5 (:end (ex-data e))))
        (is (= :substr (:fn (ex-data e))))
        (is (= "hello" (:arg (ex-data e))))))))


;; === validate-index-in-bounds! tests ===

(deftest validate-index-in-bounds!-test
  (testing "passes when index equals max-value"
    (is (nil? (v/validate-index-in-bounds! 5 5 :start {:fn :test}))))

  (testing "passes when index is less than max-value"
    (is (nil? (v/validate-index-in-bounds! 3 10 :start {:fn :test}))))

  (testing "passes for zero with positive max"
    (is (nil? (v/validate-index-in-bounds! 0 5 :start {:fn :test}))))

  (testing "throws when index exceeds max-value"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"start index out of bounds"
          (v/validate-index-in-bounds! 10 5 :start {:fn :test}))))

  (testing "error contains max-value and context"
    (try
      (v/validate-index-in-bounds! 15 10 :end {:fn :slice})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :execution-error/index-out-of-bounds (:type (ex-data e))))
        (is (= 15 (:end (ex-data e))))
        (is (= 10 (:max-value (ex-data e))))
        (is (= :slice (:fn (ex-data e))))))))


;; === validate-start-end-order! tests ===

(deftest validate-start-end-order!-test
  (testing "passes when end equals start"
    (is (nil? (v/validate-start-end-order! 5 5))))

  (testing "passes when end greater than start"
    (is (nil? (v/validate-start-end-order! 0 10)))
    (is (nil? (v/validate-start-end-order! 5 6))))

  (testing "throws when end less than start"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"end index cannot be less than start"
          (v/validate-start-end-order! 5 3))))

  (testing "error contains start and end values"
    (try
      (v/validate-start-end-order! 10 5)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :execution-error/invalid-index (:type (ex-data e))))
        (is (= 10 (:start (ex-data e))))
        (is (= 5 (:end (ex-data e))))))))


;; === validate-string-index! tests ===

(deftest validate-string-index!-test
  (testing "passes for valid index within string"
    (is (nil? (v/validate-string-index! 0 5 :start)))
    (is (nil? (v/validate-string-index! 4 5 :start)))
    (is (nil? (v/validate-string-index! 5 5 :start))))  ; Equal to length is valid for end position

  (testing "throws for negative index"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"start index cannot be negative"
          (v/validate-string-index! -1 5 :start))))

  (testing "throws for index exceeding string length"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"end index out of bounds"
          (v/validate-string-index! 10 5 :end))))

  (testing "error contains string-length"
    (try
      (v/validate-string-index! 100 10 :pos)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= 10 (:string-length (ex-data e))))))))


;; === validate-collection-size! tests ===

(deftest validate-collection-size!-test
  (testing "passes when size equals max"
    (is (nil? (v/validate-collection-size! 100 100 :too-large {:fn :repeat}))))

  (testing "passes when size less than max"
    (is (nil? (v/validate-collection-size! 50 100 :too-large {:fn :repeat}))))

  (testing "throws when size exceeds max (4-arg version)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"size 150 exceeds max allowed 100"
          (v/validate-collection-size! 150 100 :too-large {:fn :repeat}))))

  (testing "throws with custom message (5-arg version)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Cannot create more than 1000 elements"
          (v/validate-collection-size! 5000 1000 :collection-too-large
                                       {:fn :range}
                                       "Cannot create more than 1000 elements"))))

  (testing "error contains size, max-size, and context"
    (try
      (v/validate-collection-size! 200 50 :execution-error/too-large {:fn :generate})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :execution-error/too-large (:type (ex-data e))))
        (is (= 200 (:size (ex-data e))))
        (is (= 50 (:max-size (ex-data e))))
        (is (= :generate (:fn (ex-data e))))))))


;; === validate-non-negative-count! tests ===

(deftest validate-non-negative-count!-test
  (testing "passes for zero"
    (is (nil? (v/validate-non-negative-count! 0 :n))))

  (testing "passes for positive values"
    (is (nil? (v/validate-non-negative-count! 1 :n)))
    (is (nil? (v/validate-non-negative-count! 1000 :count))))

  (testing "throws for negative (2-arg version with default message)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"n cannot be negative"
          (v/validate-non-negative-count! -1 :n))))

  (testing "throws for negative (3-arg version with custom message)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Repeat count must be >= 0"
          (v/validate-non-negative-count! -5 :count "Repeat count must be >= 0"))))

  (testing "error contains param name and value"
    (try
      (v/validate-non-negative-count! -10 :times)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :execution-error/invalid-count (:type (ex-data e))))
        (is (= -10 (:times (ex-data e))))))))


;; === validate-non-zero! tests ===

(deftest validate-non-zero!-test
  (testing "passes for positive values"
    (is (nil? (v/validate-non-zero! 1 :divisor "Division by zero")))
    (is (nil? (v/validate-non-zero! 100 :value "Cannot be zero"))))

  (testing "passes for negative values"
    (is (nil? (v/validate-non-zero! -1 :divisor "Division by zero")))
    (is (nil? (v/validate-non-zero! -100 :value "Cannot be zero"))))

  (testing "throws for zero"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Division by zero"
          (v/validate-non-zero! 0 :divisor "Division by zero"))))

  (testing "error contains param name and value"
    (try
      (v/validate-non-zero! 0 :denominator "Denominator cannot be zero")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :execution-error/invalid-value (:type (ex-data e))))
        (is (zero? (:denominator (ex-data e))))))))


;; === Edge cases ===

(deftest edge-cases-test
  (testing "validates boundary value Long/MAX_VALUE"
    (is (nil? (v/validate-index-non-negative! Long/MAX_VALUE :idx {}))))

  (testing "validates Long/MIN_VALUE as negative"
    (is (thrown? clojure.lang.ExceptionInfo
          (v/validate-index-non-negative! Long/MIN_VALUE :idx {}))))

  (testing "0 is valid for non-negative but fails non-zero"
    (is (nil? (v/validate-non-negative-count! 0 :n)))
    (is (thrown? clojure.lang.ExceptionInfo
          (v/validate-non-zero! 0 :n "cannot be zero")))))
