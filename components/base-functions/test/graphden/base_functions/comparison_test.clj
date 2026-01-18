(ns graphden.base-functions.comparison-test
  "Tests for comparison base functions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.test-helpers :as h]
    [graphden.executor.interface :as exec]))


(use-fixtures :each exec/with-clean-registry)


(deftest comparison-operations-test
  (h/register-comparison!)

  (testing "eq"
    (is (true? (h/call-base-fn :eq {:values [5 5]})))
    (is (false? (h/call-base-fn :eq {:values [5 6]})))
    (is (true? (h/call-base-fn :eq {:values ["hello" "hello"]})))
    (is (true? (h/call-base-fn :eq {:values [1 1 1 1]})))
    (is (false? (h/call-base-fn :eq {:values [1 1 2 1]}))))

  (testing "neq"
    (is (false? (h/call-base-fn :neq {:values [5 5]})))
    (is (true? (h/call-base-fn :neq {:values [5 6]}))))

  (testing "lt"
    (is (true? (h/call-base-fn :lt {:nums [3 5]})))
    (is (false? (h/call-base-fn :lt {:nums [5 3]})))
    (is (false? (h/call-base-fn :lt {:nums [5 5]})))
    (is (true? (h/call-base-fn :lt {:nums [1 2 3 4 5]})))
    (is (false? (h/call-base-fn :lt {:nums [1 2 3 3 5]}))))

  (testing "lte"
    (is (true? (h/call-base-fn :lte {:nums [3 5]})))
    (is (true? (h/call-base-fn :lte {:nums [5 5]})))
    (is (false? (h/call-base-fn :lte {:nums [6 5]})))
    (is (true? (h/call-base-fn :lte {:nums [1 2 2 3 3]}))))

  (testing "gt"
    (is (true? (h/call-base-fn :gt {:nums [5 3]})))
    (is (false? (h/call-base-fn :gt {:nums [3 5]})))
    (is (false? (h/call-base-fn :gt {:nums [5 5]})))
    (is (true? (h/call-base-fn :gt {:nums [5 4 3 2 1]}))))

  (testing "gte"
    (is (true? (h/call-base-fn :gte {:nums [5 3]})))
    (is (true? (h/call-base-fn :gte {:nums [5 5]})))
    (is (false? (h/call-base-fn :gte {:nums [4 5]})))
    (is (true? (h/call-base-fn :gte {:nums [5 4 4 3 3]})))))
