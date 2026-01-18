(ns graphden.base-functions.conditionals-test
  "Tests for conditional base functions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.test-helpers :as h]
    [graphden.executor.interface :as exec]))


(use-fixtures :each exec/with-clean-registry)


(deftest conditionals-test
  (h/register-conditionals!)

  (testing "if - true branch"
    (is (= "yes" (h/call-base-fn :if {:condition true :then "yes" :else "no"}))))

  (testing "if - false branch"
    (is (= "no" (h/call-base-fn :if {:condition false :then "yes" :else "no"}))))

  (testing "if - truthy values"
    (is (= "yes" (h/call-base-fn :if {:condition 1 :then "yes" :else "no"})))
    (is (= "yes" (h/call-base-fn :if {:condition "non-empty" :then "yes" :else "no"}))))

  (testing "if - falsy values"
    (is (= "no" (h/call-base-fn :if {:condition nil :then "yes" :else "no"}))))

  (testing "cond - first match"
    (is (= "one" (h/call-base-fn :cond {:pairs [{:pred true :result "one"}
                                                {:pred true :result "two"}]
                                        :default "none"}))))

  (testing "cond - second match"
    (is (= "two" (h/call-base-fn :cond {:pairs [{:pred false :result "one"}
                                                {:pred true :result "two"}]
                                        :default "none"}))))

  (testing "cond - no match, uses default"
    (is (= "none" (h/call-base-fn :cond {:pairs [{:pred false :result "one"}
                                                 {:pred false :result "two"}]
                                         :default "none"}))))

  (testing "cond - no match, no default"
    (is (nil? (h/call-base-fn :cond {:pairs [{:pred false :result "one"}]})))))
