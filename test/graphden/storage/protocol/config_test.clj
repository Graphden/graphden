(ns graphden.storage.protocol.config-test
  "Tests for storage-protocol.config - declarative configuration validation."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.config :as cfg]))


;; === Schema definition tests ===

(deftest positive-int-schema-test
  (testing "schema is defined"
    (is (some? cfg/positive-int))))


(deftest non-negative-int-schema-test
  (testing "schema is defined"
    (is (some? cfg/non-negative-int))))


;; === Query timeout tests ===

(deftest query-timeout-dynamic-var-test
  (testing "default value is set"
    (is (pos-int? cfg/*query-timeout-ms*))))


(deftest min-query-timeout-ms-test
  (testing "minimum is 1000ms"
    (is (= 1000 cfg/min-query-timeout-ms))))


(deftest validate-query-timeout!-test
  (testing "passes for valid timeout"
    (is (nil? (cfg/validate-query-timeout! 5000))))

  (testing "passes for minimum timeout"
    (is (nil? (cfg/validate-query-timeout! 1000))))

  (testing "throws for non-positive integer"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be a positive integer"
          (cfg/validate-query-timeout! 0)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be a positive integer"
          (cfg/validate-query-timeout! -100))))

  (testing "throws for below minimum"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be at least"
          (cfg/validate-query-timeout! 500)))))


(deftest with-query-timeout-test
  (testing "executes function with custom timeout"
    (let [captured (atom nil)]
      (cfg/with-query-timeout 5000
                              #(reset! captured cfg/*query-timeout-ms*))
      (is (= 5000 @captured))))

  (testing "returns function result"
    (is (= 42 (cfg/with-query-timeout 5000 #(+ 40 2)))))

  (testing "validates timeout"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be at least"
          (cfg/with-query-timeout 100 #(identity 1))))))


(deftest get-query-timeout-seconds-test
  (testing "converts milliseconds to seconds"
    (cfg/with-query-timeout 5000
                            #(is (= 5 (cfg/get-query-timeout-seconds)))))

  (testing "throws for timeout below minimum"
    ;; Use binding directly to simulate improper usage
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be at least"
          (binding [cfg/*query-timeout-ms* 100]
            (cfg/get-query-timeout-seconds))))))


;; === Regex limits tests ===

(deftest regex-limits-dynamic-vars-test
  (testing "default values are set"
    (is (pos-int? cfg/*max-regex-length*))
    (is (pos-int? cfg/*max-regex-input-length*))
    (is (pos-int? cfg/*regex-compile-timeout-ms*))))


(deftest with-regex-limits-test
  (testing "binds custom limits"
    (cfg/with-regex-limits {:max-pattern-length 50
                            :max-input-length 5000
                            :compile-timeout-ms 200}
                           #(do
                              (is (= 50 cfg/*max-regex-length*))
                              (is (= 5000 cfg/*max-regex-input-length*))
                              (is (= 200 cfg/*regex-compile-timeout-ms*)))))

  (testing "returns function result"
    (is (= :result (cfg/with-regex-limits {} #(identity :result)))))

  (testing "uses defaults for unspecified options"
    (let [original-pattern cfg/*max-regex-length*]
      (cfg/with-regex-limits {:max-input-length 999}
                             #(do
                                (is (= original-pattern cfg/*max-regex-length*))
                                (is (= 999 cfg/*max-regex-input-length*)))))))


;; === Lazy sequence limits tests ===

(deftest lazy-seq-limits-dynamic-vars-test
  (testing "default values are set"
    (is (pos-int? cfg/*max-lazy-seq-size*))
    (is (pos-int? cfg/*max-nested-collection-depth*))))
