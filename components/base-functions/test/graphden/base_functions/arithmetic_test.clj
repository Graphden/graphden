(ns graphden.base-functions.arithmetic-test
  "Tests for arithmetic base functions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.arithmetic :as arithmetic]
    [graphden.base-functions.test-helpers :as h]
    [graphden.executor.interface :as exec]))


(use-fixtures :each exec/with-clean-registry)


(deftest arithmetic-operations-test
  (h/register-arithmetic!)

  (testing "add"
    (is (= 5 (h/call-base-fn :add {:nums [2 3]})))
    (is (zero? (h/call-base-fn :add {:nums [-5 5]})))
    (is (= 3.5 (h/call-base-fn :add {:nums [1.5 2.0]})))
    (is (= 15 (h/call-base-fn :add {:nums [1 2 3 4 5]})))
    (is (zero? (h/call-base-fn :add {:nums []}))))

  (testing "add - overflow to Infinity throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overflow.*infinite"
          (h/call-base-fn :add {:nums [Double/MAX_VALUE Double/MAX_VALUE]}))))

  (testing "sub"
    (is (= 2 (h/call-base-fn :sub {:nums [5 3]})))
    (is (= -8 (h/call-base-fn :sub {:nums [2 10]})))
    (is (= 5 (h/call-base-fn :sub {:nums [10 3 2]})))
    (is (= -5 (h/call-base-fn :sub {:nums [5]}))))

  (testing "mul"
    (is (= 12 (h/call-base-fn :mul {:nums [3 4]})))
    (is (= -15 (h/call-base-fn :mul {:nums [-3 5]})))
    (is (= 120 (h/call-base-fn :mul {:nums [1 2 3 4 5]})))
    (is (= 1 (h/call-base-fn :mul {:nums []}))))

  (testing "div"
    (is (= 2 (h/call-base-fn :div {:nums [6 3]})))
    (is (= 5/2 (h/call-base-fn :div {:nums [5 2]})))
    (is (= 1 (h/call-base-fn :div {:nums [100 5 2 10]})))
    (is (= 1/5 (h/call-base-fn :div {:nums [5]}))))

  (testing "div by zero throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Division by zero"
          (h/call-base-fn :div {:nums [5 0]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Division by zero"
          (h/call-base-fn :div {:nums [10 2 0 5]}))))

  (testing "sub with empty list throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Subtraction requires at least one number"
          (h/call-base-fn :sub {:nums []}))))

  (testing "div with empty list throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Division requires at least one number"
          (h/call-base-fn :div {:nums []}))))

  (testing "mul - overflow to Infinity throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overflow.*infinite"
          (h/call-base-fn :mul {:nums [Double/MAX_VALUE 2.0]}))))

  (testing "sub - overflow to negative Infinity throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overflow.*infinite"
          (h/call-base-fn :sub {:nums [(- Double/MAX_VALUE) Double/MAX_VALUE]}))))

  (testing "overflow exception contains correct data"
    (try
      (h/call-base-fn :mul {:nums [Double/MAX_VALUE Double/MAX_VALUE]})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :execution-error/numeric-overflow (:type (ex-data e))))
        (is (= :mul (:operation (ex-data e))))
        (is (= 2 (:num-count (ex-data e)))))))

  (testing "NaN result throws"
    ;; Test the private function directly to cover the NaN branch
    ;; NaN can occur from 0.0/0.0 in floating point, but our div catches that first
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"NaN"
          (#'arithmetic/check-numeric-result! Double/NaN :test [1 2]))))

  (testing "mod"
    (is (= 1 (h/call-base-fn :mod {:a 7 :b 3})))
    (is (zero? (h/call-base-fn :mod {:a 6 :b 2}))))

  (testing "mod by zero throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Modulo by zero"
          (h/call-base-fn :mod {:a 5 :b 0}))))

  (testing "neg"
    (is (= -5 (h/call-base-fn :neg {:n 5})))
    (is (= 3 (h/call-base-fn :neg {:n -3}))))

  (testing "abs"
    (is (= 5 (h/call-base-fn :abs {:n -5})))
    (is (= 5 (h/call-base-fn :abs {:n 5})))))
