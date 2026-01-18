(ns graphden.base-functions.logic-test
  "Tests for logic base functions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.test-helpers :as h]
    [graphden.executor.interface :as exec]))


(use-fixtures :each exec/with-clean-registry)


(deftest logic-operations-test
  (h/register-logic!)

  (testing "and"
    (is (true? (h/call-base-fn :and {:a true :b true})))
    (is (false? (h/call-base-fn :and {:a true :b false})))
    (is (false? (h/call-base-fn :and {:a false :b true})))
    (is (false? (h/call-base-fn :and {:a false :b false}))))

  (testing "or"
    (is (true? (h/call-base-fn :or {:a true :b true})))
    (is (true? (h/call-base-fn :or {:a true :b false})))
    (is (true? (h/call-base-fn :or {:a false :b true})))
    (is (false? (h/call-base-fn :or {:a false :b false}))))

  (testing "not"
    (is (false? (h/call-base-fn :not {:x true})))
    (is (true? (h/call-base-fn :not {:x false})))
    (is (true? (h/call-base-fn :not {:x nil})))))


(deftest logic-laziness-test
  ;; Tests that and/or exhibit short-circuit evaluation behavior.
  (h/register-logic!)

  (testing "and short-circuits on false - second arg not evaluated"
    ;; Clojure's 'and' macro short-circuits, so when a is false, b is never evaluated
    (let [call-count (atom 0)
          tracking-delay (delay (do (swap! call-count inc) true))
          false-delay (delay false)
          and-fn (exec/get-base-fn :and)]
      (and-fn {:a false-delay :b tracking-delay} nil)
      ;; Short-circuit: b is never evaluated when a is false
      (is (zero? @call-count) "and short-circuits - second arg not evaluated")))

  (testing "or short-circuits on true - second arg not evaluated"
    ;; Clojure's 'or' macro short-circuits, so when a is true, b is never evaluated
    (let [call-count (atom 0)
          tracking-delay (delay (do (swap! call-count inc) false))
          true-delay (delay true)
          or-fn (exec/get-base-fn :or)]
      (or-fn {:a true-delay :b tracking-delay} nil)
      ;; Short-circuit: b is never evaluated when a is true
      (is (zero? @call-count) "or short-circuits - second arg not evaluated"))))
