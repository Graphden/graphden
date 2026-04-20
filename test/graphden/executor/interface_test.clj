(ns graphden.executor.interface-test
  "Tests for the public `exec/` surface — registry basics, context
   fallback, and `execute-with-named-args` validation."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each exec/with-clean-registry)


;; ============================================================================
;; `register-base-fn!` + `get-base-fn` — registry round-trip
;; ============================================================================

(deftest get-base-fn-returns-registered-impl
  (testing "register/get roundtrip returns the same fn object"
    (let [impl (fn [_ _] :marker)]
      (exec/register-base-fn! :identity-probe impl)
      (is (identical? impl (exec/get-base-fn :identity-probe))))))


(deftest register-nil-impl-is-noop
  (testing "a nil impl registers nil so downstream code sees `get` → nil"
    (exec/register-base-fn! :nil-probe nil)
    (is (nil? (exec/get-base-fn :nil-probe)))))


(deftest get-base-fn-unknown-returns-nil
  (is (nil? (exec/get-base-fn :does-not-exist-123))))


(deftest register-base-fn-raw-is-alias
  (testing "the retired `register-base-fn-raw!` alias still works"
    (let [impl (fn [_ _] :raw)]
      (exec/register-base-fn-raw! :raw-probe impl)
      (is (identical? impl (exec/get-base-fn :raw-probe))))))


;; ============================================================================
;; `get-default-registry` — snapshot for create-context
;; ============================================================================

(deftest get-default-registry-returns-current-state
  (exec/register-base-fn! :r1 (fn [_ _] 1))
  (exec/register-base-fn! :r2 (fn [_ _] 2))
  (let [reg (exec/get-default-registry)]
    (is (contains? reg :r1))
    (is (contains? reg :r2))
    (is (fn? (:r1 reg)))
    (is (fn? (:r2 reg)))))


;; ============================================================================
;; `create-context` — :base-fns falls back to default registry
;; ============================================================================

(deftest create-context-uses-default-registry-by-default
  (let [storage (setup/create-test-storage)]
    (try
      (exec/register-base-fn! :ctx-seeded (fn [_ _] :seeded))
      (let [ctx (exec/create-context {:storage storage})]
        (is (fn? (get (:base-fns ctx) :ctx-seeded))
            "context pulls impls from the global registry when :base-fns is omitted"))
      (finally
        (sp/close storage)))))


(deftest create-context-accepts-explicit-base-fns
  (let [storage (setup/create-test-storage)
        custom {:explicit-fn (fn [_ _] :explicit)}
        ctx (exec/create-context {:storage storage :base-fns custom})]
    (try
      (is (= custom (:base-fns ctx))
          "explicit :base-fns wins over the default registry")
      (finally
        (sp/close storage)))))


;; ============================================================================
;; `execute-with-named-args` — unknown-arg-name validation
;; ============================================================================

(deftest execute-with-named-args-unknown-arg-throws
  (testing "external callers get `Unknown argument name` on typos"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :double (setup/fn-impl [x] (* 2 x)))
        (let [base-fn (setup/create-base-fn! storage "double" :int)
              _ (setup/create-arg! storage (:id base-fn)
                                   {:name "x" :type :int :required true :is-fn false})
              composed (setup/create-composed-fn! storage "my-double" (:id base-fn))
              ctx (exec/create-context {:storage storage})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Unknown argument name"
                (exec/execute-with-named-args ctx (:id composed) {:typo 5})))
          (is (= 10 (exec/execute-with-named-args ctx (:id composed) {:x 5}))
              "correct arg name works"))
        (finally
          (sp/close storage))))))


(deftest execute-with-named-args-skips-validation-for-callable
  (testing "when fn-id is a callable (hof-wrap result), validation is skipped"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ctx (exec/create-context {:storage storage})
              callable (fn [x] [:called-with x])]
          (is (= [:called-with 42]
                 (exec/execute-with-named-args ctx callable {:anything 42}))))
        (finally
          (sp/close storage))))))
