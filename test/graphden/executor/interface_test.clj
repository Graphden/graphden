(ns graphden.executor.interface-test
  "Tests for the public `exec/` surface — specifically the legacy-deref
   adapter (`adapt-legacy-args` + `wrap-legacy-derefs`) that lets impls
   written with the old `@arg` pattern keep working under the compile
   executor, and the registry identity contract (`get-base-fn` returns
   the user's raw impl despite internal wrapping)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each exec/with-clean-registry)


;; ============================================================================
;; `register-base-fn!` + `get-base-fn` — raw-impl identity is preserved.
;;
;; The user passes an impl; internally we wrap it in a legacy-deref
;; adapter. `get-base-fn` must unwrap via the `:raw-fn` metadata so
;; equality / identity checks against the impl work.
;; ============================================================================

(deftest get-base-fn-returns-raw-impl
  (testing "identity is preserved through register/get roundtrip"
    (let [impl (fn [_ _] :marker)]
      (exec/register-base-fn! :identity-probe impl)
      (is (identical? impl (exec/get-base-fn :identity-probe)))
      (is (= impl (exec/get-base-fn :identity-probe))))))


(deftest register-nil-impl-is-noop
  (testing "a nil impl registers nil so downstream code sees `get` → nil"
    (exec/register-base-fn! :nil-probe nil)
    (is (nil? (exec/get-base-fn :nil-probe)))))


(deftest get-base-fn-unknown-returns-nil
  (is (nil? (exec/get-base-fn :does-not-exist-123))))


;; ============================================================================
;; Legacy `@arg` adapter — `wrap-legacy-derefs` + `adapt-legacy-args`.
;;
;; Impls registered via `exec/register-base-fn!` receive arg values
;; wrapped in Delays, so bodies like `(+ @a @b)` keep working even
;; under the compile executor that otherwise passes thunks/literals.
;; ============================================================================

(deftest legacy-deref-basic-values
  (testing "literal args become delays that yield the literal"
    (let [captured (atom nil)
          impl (fn [{:keys [x]} _]
                 (reset! captured x)
                 @x)]
      (exec/register-base-fn! :legacy-echo impl)
      (let [wrapped (exec/get-default-registry)
            wrapped-fn (:legacy-echo wrapped)
            result (wrapped-fn {:x 42} nil)]
        (is (= 42 result))
        (is (instance? clojure.lang.IDeref @captured)
            "the value reaching the impl is a Delay (IDeref), not the raw int")
        (is (= 42 @@captured) "forces to the underlying value")))))


(deftest legacy-deref-missing-key-yields-nil-delay
  (testing "keys absent from the underlying map resolve to `(delay nil)`"
    (let [impl (fn [{:keys [present missing]} _]
                 [@present @missing])]
      (exec/register-base-fn! :missing-probe impl)
      (let [wrapped-fn (:missing-probe (exec/get-default-registry))]
        (is (= [1 nil] (wrapped-fn {:present 1} nil))
            "missing :missing forces to nil, no NPE")))))


(deftest legacy-deref-contains-and-seq-support
  (testing "adapter supports `contains?`, `seq`, `count` so destructure + iteration work"
    (let [captured (atom nil)
          impl (fn [args _]
                 (reset! captured
                         {:contains-a (contains? args :a)
                          :contains-z (contains? args :z)
                          :count (count args)
                          :keys (sort (map first (seq args)))}))]
      (exec/register-base-fn! :introspect impl)
      (let [wrapped-fn (:introspect (exec/get-default-registry))]
        (wrapped-fn {:a 1 :b 2} nil)
        (is (true? (:contains-a @captured)))
        (is (false? (:contains-z @captured)))
        (is (= 2 (:count @captured)))
        (is (= [:a :b] (:keys @captured)))))))


(deftest legacy-deref-forces-thunks
  (testing "when the underlying arg is a `rt/thunk`, deref forces through it"
    (let [impl (fn [{:keys [x]} _] @x)]
      (exec/register-base-fn! :thunk-probe impl)
      (let [call-count (atom 0)
            thunked #(do (swap! call-count inc) 7)
            thunk (with-meta thunked #:graphden.executor.runtime{:thunk true})
            wrapped-fn (:thunk-probe (exec/get-default-registry))]
        (is (= 7 (wrapped-fn {:x thunk} nil)))
        (is (= 1 @call-count) "thunk fired exactly once on deref")))))


(deftest legacy-deref-passes-ctx-through
  (testing "ctx is delivered to the impl without wrapping"
    (let [captured-ctx (atom nil)
          impl (fn [_ ctx] (reset! captured-ctx ctx) :ok)]
      (exec/register-base-fn! :ctx-probe impl)
      (let [wrapped-fn (:ctx-probe (exec/get-default-registry))]
        (wrapped-fn {} {:marker :ctx-value})
        (is (= {:marker :ctx-value} @captured-ctx))))))


(deftest legacy-deref-assoc-is-read-only
  (testing "adapter throws on assoc — the wrapped map is a view, not a target"
    (let [captured-ex (atom nil)
          impl (fn [args _]
                 (try
                   (assoc args :new 1)
                   (catch UnsupportedOperationException e
                     (reset! captured-ex e)
                     :ok)))]
      (exec/register-base-fn! :assoc-probe impl)
      (let [wrapped-fn (:assoc-probe (exec/get-default-registry))]
        (is (= :ok (wrapped-fn {:x 1} nil)) "catch branch ran")
        (is (some? @captured-ex) "assoc on the adapter throws")))))


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
        (exec/register-base-fn! :double (fn [{:keys [x]} _] (* 2 @x)))
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
