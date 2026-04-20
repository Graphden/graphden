(ns graphden.executor.result-value-test
  "Result value and registry tests for executor.

   Covers:
   - ref-id caching tests (same ref-id computed once)
   - Base function registry tests
   - execute-by-name error path tests
   - execute-with-named-args error path tests
   - register-type-hint! tests
   - get-single-required-arg tests
   - with-clean-registry tests

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


;; === ref-id Caching Tests ===

;; Ref-level caching semantics tests were removed alongside the legacy
;; queue's `:result-cache` — the compile executor uses `*call-cache*`
;; per top-level invocation, but constant folding at compile time runs
;; side-effecting impls (once per fn-entity that wraps them), which
;; makes exact call-count assertions impractical for legacy test-style
;; counter impls. See `compile-test/constant-folding-*` for the
;; equivalent compile-side coverage.


;; === Base Function Registry Tests ===

(deftest get-base-fn-test
  (testing "returns nil for non-existent function"
    (is (nil? (exec/get-base-fn :non-existent-fn-12345)))))


(deftest get-default-registry-test
  (testing "returns current registry state"
    (exec/register-base-fn! :test-registry-fn (fn [_ _] 42))
    (let [registry (exec/get-default-registry)]
      (is (map? registry))
      (is (contains? registry :test-registry-fn))
      (is (fn? (:test-registry-fn registry))))))


(deftest get-base-fn-from-context-test
  (testing "returns function from context registry"
    (let [storage (setup/create-test-storage)
          test-fn (fn [_ _] 123)
          _ (exec/register-base-fn! :ctx-test-fn test-fn)
          ctx (exec/create-context {:storage storage})]
      (is (= test-fn (exec/get-base-fn-from-context ctx :ctx-test-fn)))
      (sp/close storage)))

  (testing "returns nil for non-existent function"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (nil? (exec/get-base-fn-from-context ctx :does-not-exist-xyz)))
      (sp/close storage))))


;; === execute-by-name Error Path Tests ===

(deftest execute-by-name-error-test
  (testing "executes function by string name"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn! :const-42 (fn [_ _] 42))
          ;; Create base fn
          base-fn (setup/create-base-fn! storage "const-42" :int)
          ;; Create composed fn
          _ (setup/create-composed-fn! storage "my-const-fn" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      (is (= 42 (exec/execute-by-name ctx "my-const-fn" nil)))
      (sp/close storage)))

  (testing "throws for non-string fn-name"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx :keyword-name nil)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx 123 nil)))
      (sp/close storage)))

  (testing "throws for non-existent function name"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"not found"
            (exec/execute-by-name ctx "non-existent-function" nil)))
      (sp/close storage))))


;; === execute-with-named-args Error Path Tests ===

(deftest execute-with-named-args-error-test
  (testing "executes with named args"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn! :add-named (setup/fn-impl [a b] (+ a b)))
          ;; Create base fn
          base-fn (setup/create-base-fn! storage "add-named" :int)
          _ (setup/create-arg! storage (:id base-fn)
                               {:name "a" :type :int :required true :is-fn false})
          _ (setup/create-arg! storage (:id base-fn)
                               {:name "b" :type :int :required true :is-fn false})
          ;; Create composed fn (free args - no values set)
          the-fn (setup/create-composed-fn! storage "my-add-named" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      (is (= 7 (exec/execute-with-named-args ctx (:id the-fn) {:a 3 :b 4})))
      (sp/close storage)))

  (testing "throws for unknown arg name"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn! :single-arg (setup/fn-impl [x] x))
          ;; Create base fn
          base-fn (setup/create-base-fn! storage "single-arg" :int)
          _ (setup/create-arg! storage (:id base-fn)
                               {:name "x" :type :int :required true :is-fn false})
          ;; Create composed fn
          the-fn (setup/create-composed-fn! storage "my-single-arg" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown argument name"
            (exec/execute-with-named-args ctx (:id the-fn) {:y 42})))
      (sp/close storage))))


;; === register-type-hint! Tests ===

(deftest register-type-hint-interface-test
  (testing "registers custom type hint through interface"
    (exec/register-type-hint! :custom-email "string in email format")
    ;; The hint is stored internally, we can verify it doesn't throw
    (is true))

  (testing "rejects invalid type keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"type-keyword must be a keyword"
          (exec/register-type-hint! "not-keyword" "hint"))))

  (testing "rejects invalid hint string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"hint-string must be a string"
          (exec/register-type-hint! :valid-keyword :not-a-string)))))


;; === with-clean-registry Tests ===

(deftest with-clean-registry-test
  (testing "clears registry before and after"
    (exec/register-base-fn! :before-clean (fn [_ _] 1))
    (is (some? (exec/get-base-fn :before-clean)))

    (exec/with-clean-registry
      (fn []
        ;; Should be cleared
        (is (nil? (exec/get-base-fn :before-clean)))
        ;; Register during test
        (exec/register-base-fn! :during-clean (fn [_ _] 2))
        (is (some? (exec/get-base-fn :during-clean)))))

    ;; After cleanup, both should be gone
    (is (nil? (exec/get-base-fn :before-clean)))
    (is (nil? (exec/get-base-fn :during-clean)))))
