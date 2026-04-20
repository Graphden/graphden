(ns graphden.executor.context-test
  "Tests for `graphden.executor.context/create-context` — validation,
   defaults, and context helpers (clear-result-cache!, current-time-ms,
   resolve-graph-cached)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each exec/with-clean-registry)


;; ============================================================================
;; Validation — missing / invalid options
;; ============================================================================

(deftest validate-storage-required
  (testing "nil storage throws a single-error message"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Storage is required"
          (ctx/create-context {})))))


(deftest validate-storage-must-implement-protocol
  (testing "a non-storage object (missing ExecutionGraph) is rejected"
    ;; A plain map isn't a storage — it doesn't satisfy the protocol.
    ;; The validator collects an error naming the received type.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"storage must implement ExecutionGraph protocol"
          (ctx/create-context {:storage {:fake :storage}})))))


(deftest validate-timeout-ms-minimum
  (testing "timeout-ms below 50ms lower bound is rejected"
    (let [storage (setup/create-test-storage)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"timeout-ms must be at least"
              (ctx/create-context {:storage storage :timeout-ms 10})))
        (finally
          (sp/close storage))))))


(deftest validate-max-depth-must-be-positive-int
  (testing "zero, negative, or non-integer max-depth is rejected"
    (let [storage (setup/create-test-storage)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"max-depth must be a positive integer"
              (ctx/create-context {:storage storage :max-depth 0})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"max-depth must be a positive integer"
              (ctx/create-context {:storage storage :max-depth -5})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"max-depth must be a positive integer"
              (ctx/create-context {:storage storage :max-depth 1.5})))
        (finally
          (sp/close storage))))))


(deftest validate-max-depth-upper-limit
  (testing "max-depth above 100000 is rejected"
    (let [storage (setup/create-test-storage)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"max-depth exceeds maximum allowed value"
              (ctx/create-context {:storage storage :max-depth 1000000})))
        (finally
          (sp/close storage))))))


(deftest validate-collects-multiple-errors
  (testing "bad storage + bad timeout + bad max-depth all reported together"
    (try
      (ctx/create-context {:storage {:fake :storage}
                           :timeout-ms 1
                           :max-depth -1})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [errs (:validation-errors (ex-data e))]
          (is (re-find #"Multiple validation errors" (ex-message e)))
          (is (>= (count errs) 3)
              "storage-type + timeout + max-depth errors all present"))))))


;; ============================================================================
;; Defaults — context fields are populated with expected sentinels
;; ============================================================================

(deftest create-context-defaults
  (let [storage (setup/create-test-storage)]
    (try
      (let [c (ctx/create-context {:storage storage})]
        (is (some? (:storage c)))
        (is (pos? (:max-depth c)) "max-depth defaulted to a positive int")
        (is (pos? (:timeout-ms c)) "timeout-ms defaulted to a positive int")
        (is (zero? (:depth c)) "fresh context starts at depth=0")
        (is (true? (:strict-type-validation? c)) "strict by default")
        (is (instance? clojure.lang.Atom (:result-cache c)))
        (is (instance? clojure.lang.Atom (:compiled-registry c)))
        (is (instance? clojure.lang.Atom (:graph-cache c)))
        (is (instance? clojure.lang.Atom (:unknown-type-counter c)))
        (is (map? @(:result-cache c)) "result-cache starts as empty map")
        (is (empty? @(:result-cache c))))
      (finally
        (sp/close storage)))))


(deftest create-context-permissive-mode
  (testing "strict-type-validation? false is honored"
    (let [storage (setup/create-test-storage)]
      (try
        (let [c (ctx/create-context {:storage storage :strict-type-validation? false})]
          (is (false? (:strict-type-validation? c))))
        (finally
          (sp/close storage))))))


(deftest create-context-custom-clock
  (testing "custom clock fn threads through as :clock and is sampled at creation"
    (let [storage (setup/create-test-storage)
          fake-time (atom 1000)
          clock (fn [] @fake-time)]
      (try
        (let [c (ctx/create-context {:storage storage :clock clock})]
          (is (= clock (:clock c)))
          (is (= 1000 (:start-time c)) "start-time captured from clock at creation"))
        (finally
          (sp/close storage))))))


(deftest create-context-custom-cache-thresholds
  (let [storage (setup/create-test-storage)]
    (try
      (let [c (ctx/create-context {:storage storage
                                   :cache-warning-threshold 42
                                   :cache-max-size 99})]
        (is (= 42 (:cache-warning-threshold c)))
        (is (= 99 (:cache-max-size c))))
      (finally
        (sp/close storage)))))


;; ============================================================================
;; Helpers — current-time-ms, clear-result-cache!, resolve-graph-cached
;; ============================================================================

(deftest current-time-ms-uses-context-clock
  (testing "current-time-ms reads the context's clock (not System/currentTimeMillis)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [t (atom 100)
              c (ctx/create-context {:storage storage :clock (fn [] @t)})]
          (is (= 100 (ctx/current-time-ms c)))
          (reset! t 250)
          (is (= 250 (ctx/current-time-ms c)) "clock is called each time"))
        (finally
          (sp/close storage))))))


(deftest clear-result-cache-resets-and-reports-count
  (let [storage (setup/create-test-storage)]
    (try
      (let [c (ctx/create-context {:storage storage})]
        (swap! (:result-cache c) assoc :a 1 :b 2 :c 3)
        (is (= 3 (count @(:result-cache c))))
        (is (= 3 (ctx/clear-result-cache! c))
            "returns count cleared")
        (is (empty? @(:result-cache c)) "cache emptied"))
      (finally
        (sp/close storage)))))


(deftest resolve-graph-cached-populates-cache-on-first-call
  (testing "the graph cache memoises `resolve-execution-graph` per fn-id"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :const-42 (fn [_ _] 42))
        (let [base-fn (setup/create-base-fn! storage "const-42" :int)
              composed (setup/create-composed-fn! storage "c42" (:id base-fn))
              c (ctx/create-context {:storage storage})]
          (is (empty? @(:graph-cache c)) "starts empty")
          (let [graph-1 (ctx/resolve-graph-cached c (:id composed))
                graph-2 (ctx/resolve-graph-cached c (:id composed))]
            (is (= 1 (count @(:graph-cache c))) "one entry cached")
            (is (identical? graph-1 graph-2)
                "second call returns the cached graph")))
        (finally
          (sp/close storage))))))


(deftest resolve-graph-cached-without-cache-still-works
  (testing "context missing `:graph-cache` atom still resolves (no caching path)"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :const-1 (fn [_ _] 1))
        (let [base-fn (setup/create-base-fn! storage "const-1" :int)
              composed (setup/create-composed-fn! storage "cached-off" (:id base-fn))
              ;; Strip the atom by dissoc'ing it post-creation.
              c (dissoc (ctx/create-context {:storage storage}) :graph-cache)
              graph (ctx/resolve-graph-cached c (:id composed))]
          (is (some? graph) "still returns a resolved graph"))
        (finally
          (sp/close storage))))))
