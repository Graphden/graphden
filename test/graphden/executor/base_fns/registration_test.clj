(ns graphden.executor.base-fns.registration-test
  "Tests for base function registration."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.base-fns.test-helpers :as h]
    [graphden.executor.interface :as exec]))


(use-fixtures :each exec/with-clean-registry)


(deftest register-all-test
  (testing "register-all! registers all base functions"
    (h/register-all!)
    ;; Check a sample from each category
    (is (some? (exec/get-base-fn :add)))
    (is (some? (exec/get-base-fn :eq)))
    (is (some? (exec/get-base-fn :and)))
    (is (some? (exec/get-base-fn :if)))
    (is (some? (exec/get-base-fn :str-len)))
    (is (some? (exec/get-base-fn :first)))
    (is (some? (exec/get-base-fn :map)))))


(deftest register-arithmetic-test
  (testing "register-arithmetic! registers arithmetic functions"
    (h/register-arithmetic!)
    (is (some? (exec/get-base-fn :add)))
    (is (some? (exec/get-base-fn :sub)))
    (is (some? (exec/get-base-fn :mul)))
    (is (some? (exec/get-base-fn :div)))
    (is (some? (exec/get-base-fn :mod)))
    (is (some? (exec/get-base-fn :neg)))
    (is (some? (exec/get-base-fn :abs)))))
