(ns graphden.executor.context-test
  "Tests for `graphden.executor.context/create-context` — validation,
   defaults, and context helpers (current-time-ms, resolve-graph-cached)."
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
  (testing "nil storage throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Storage is required"
          (ctx/create-context {})))))


(deftest validate-storage-must-implement-protocol
  (testing "a non-storage object (missing ExecutionGraph) is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"storage must implement ExecutionGraph protocol"
          (ctx/create-context {:storage {:fake :storage}})))))


;; ============================================================================
;; Defaults — context fields are populated with expected sentinels
;; ============================================================================

(deftest create-context-defaults
  (let [storage (setup/create-test-storage)]
    (try
      (let [c (ctx/create-context {:storage storage})]
        (is (some? (:storage c)))
        (is (fn? (:clock c)))
        (is (some? (:base-fns c)) "base-fns defaults to the global registry")
        (is (instance? clojure.lang.Atom (:compiled-registry c)))
        (is (instance? clojure.lang.Atom (:graph-cache c)))
        (is (nil? @(:compiled-registry c)) "compiled-registry starts empty")
        (is (empty? @(:graph-cache c)) "graph-cache starts empty"))
      (finally
        (sp/close storage)))))


(deftest create-context-custom-clock
  (testing "custom clock threads through as :clock and is sampled on demand"
    (let [storage (setup/create-test-storage)
          fake-time (atom 1000)
          clock (fn [] @fake-time)]
      (try
        (let [c (ctx/create-context {:storage storage :clock clock})]
          (is (= clock (:clock c)))
          (is (= 1000 (ctx/current-time-ms c)))
          (reset! fake-time 9999)
          (is (= 9999 (ctx/current-time-ms c))
              "clock is sampled on every call, not snapshotted"))
        (finally
          (sp/close storage))))))


(deftest create-context-explicit-base-fns
  (let [storage (setup/create-test-storage)
        custom {:custom (fn [_ _] :custom)}]
    (try
      (let [c (ctx/create-context {:storage storage :base-fns custom})]
        (is (= custom (:base-fns c))))
      (finally
        (sp/close storage)))))


;; ============================================================================
;; `resolve-graph-cached` — graph-cache memoization
;; ============================================================================

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
              c (dissoc (ctx/create-context {:storage storage}) :graph-cache)
              graph (ctx/resolve-graph-cached c (:id composed))]
          (is (some? graph) "still returns a resolved graph"))
        (finally
          (sp/close storage))))))
