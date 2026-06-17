(ns graphden.executor.result-value-test
  "Executor scenario tests against a real storage instance.

   Covers the executor's public entry points end-to-end —
   `execute-by-name`, `execute-with-named-args`, and the
   `with-clean-registry` thread-local override — against a
   PostgreSQL-backed graph. Pure-registry behaviour (register / get /
   clear / context lookup) lives in `graphden.executor.registry-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


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
                               {:name "a" :type :int :required true})
          _ (setup/create-arg! storage (:id base-fn)
                               {:name "b" :type :int :required true})
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
                               {:name "x" :type :int :required true})
          ;; Create composed fn
          the-fn (setup/create-composed-fn! storage "my-single-arg" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown argument name"
            (exec/execute-with-named-args ctx (:id the-fn) {:y 42})))
      (sp/close storage))))


;; === with-clean-registry Tests ===

(deftest with-clean-registry-test
  ;; This NS already wires `with-clean-registry` as a `:each` fixture
  ;; (line 39), so each test body runs INSIDE a thread-local override.
  ;; The assertions below verify the new dynamic-binding semantics:
  ;; a nested `with-clean-registry` opens a fresh inner scope that
  ;; doesn't leak writes to the surrounding scope.
  (testing "nested with-clean-registry opens a fresh inner override"
    ;; Register in the OUTER (:each-fixture) scope.
    (exec/register-base-fn! :outer-stub (fn [_ _] :outer))
    (is (some? (exec/get-base-fn :outer-stub)))

    ;; Inner scope — outer-stub is invisible (different atom), and
    ;; inner stubs don't leak back out.
    (exec/with-clean-registry
      (fn []
        (is (nil? (exec/get-base-fn :outer-stub))
            "inner override doesn't inherit the outer override's writes")
        (exec/register-base-fn! :inner-stub (fn [_ _] :inner))
        (is (some? (exec/get-base-fn :inner-stub)))))

    ;; Back in the outer scope: outer-stub still there, inner-stub
    ;; cleaned up automatically.
    (is (some? (exec/get-base-fn :outer-stub)))
    (is (nil? (exec/get-base-fn :inner-stub)))))
