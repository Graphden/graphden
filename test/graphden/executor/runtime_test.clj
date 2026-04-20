(ns graphden.executor.runtime-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.runtime :as rt]))


(deftest resolve-arg-literal
  (testing "returns literal value as-is"
    (is (= 42 (rt/resolve-arg {:x 42} :x)))
    (is (= "hello" (rt/resolve-arg {:x "hello"} :x)))
    (is (nil? (rt/resolve-arg {:x nil} :x)))
    (is (nil? (rt/resolve-arg {} :missing)))))


(deftest resolve-arg-thunk
  (testing "calls thunk wrapped via rt/thunk"
    (let [t (rt/thunk (fn [] 42))]
      (is (= 42 (rt/resolve-arg {:x t} :x)))))
  (testing "thunk called every time resolve-arg is invoked"
    (let [call-count (atom 0)
          t (rt/thunk (fn [] (swap! call-count inc)))]
      (rt/resolve-arg {:x t} :x)
      (rt/resolve-arg {:x t} :x)
      (is (= 2 @call-count)))))


(deftest raw-fn-not-resolved
  (testing "raw fn (without thunk marker) returned as-is — HOF callables"
    (let [raw-fn (fn [item] (* 2 item))]
      (is (identical? raw-fn (rt/resolve-arg {:x raw-fn} :x))))))


(deftest thunk-predicate
  (is (rt/thunk? (rt/thunk (fn [] 1))))
  (is (not (rt/thunk? (fn [] 1))))
  (is (not (rt/thunk? 42)))
  (is (not (rt/thunk? nil))))
