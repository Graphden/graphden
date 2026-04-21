(ns graphden.executor.compile-runtime-test
  "Tests for the public compile-runtime API — the surface `exec/` delegates to.

   Focuses on branches that core-level tests don't always hit directly:
   - `execute` with a callable `fn-id` (legacy HOF pattern)
   - `execute-by-name` with string vs keyword storage name codec
   - `execute-with-arg-ids` (legacy arg-id map style)
   - `make-single-arg-callable` fn-pass-through and free-arg shape dispatch
   - `registry` / `rebuild!` lifecycle"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each exec/with-clean-registry)


;; ============================================================================
;; `registry` / `rebuild!` lifecycle
;; ============================================================================

(deftest registry-auto-builds-on-first-access
  (testing "a fresh context has nil registry; accessing builds it on demand"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :add (setup/fn-impl [a b] (+ a b)))
        (setup/setup-add-function! storage)
        (let [ctx (exec/create-context {:storage storage})]
          (is (nil? @(:compiled-registry ctx)) "fresh — nothing compiled yet")
          (let [reg (cr/registry ctx)]
            (is (map? reg))
            (is (pos? (count reg)) "registry contains compiled fns"))
          (is (some? @(:compiled-registry ctx)) "cached after first access"))
        (finally
          (sp/close storage))))))


(deftest registry-nil-without-atom
  (testing "context without `:compiled-registry` atom returns nil"
    (is (nil? (cr/registry {:storage :mock})))))


(deftest rebuild-replaces-current-registry
  (testing "manual `rebuild!` re-reads storage and replaces atom contents"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :add (setup/fn-impl [a b] (+ a b)))
        (setup/setup-add-function! storage)
        (let [ctx (exec/create-context {:storage storage})
              reg-1 (cr/registry ctx)
              reg-2 (cr/rebuild! ctx)]
          (is (= (set (keys reg-1)) (set (keys reg-2)))
              "same fns in both builds")
          (is (not (identical? reg-1 reg-2))
              "rebuild produces fresh closures"))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; `execute` — fn? branch (callable fn-id)
;; ============================================================================

(deftest execute-with-callable-fn-id
  (testing "callable fn-id with single-entry named-args: unwrap value, invoke"
    (let [called-with (atom nil)
          callable (fn [v] (reset! called-with v) (str "got:" v))]
      (is (= "got:hello" (cr/execute {} callable {:x "hello"})))
      (is (= "hello" @called-with)
          "single value unwrapped from map, passed to callable")))

  (testing "callable fn-id with empty named-args: invoke with the empty map"
    (let [called-with (atom :sentinel)
          callable (fn [v] (reset! called-with v) v)]
      (cr/execute {} callable {})
      (is (= {} @called-with))))

  (testing "callable fn-id with multi-entry named-args: pass the whole map"
    (let [called-with (atom nil)
          callable (fn [v] (reset! called-with v) v)]
      (cr/execute {} callable {:a 1 :b 2})
      (is (= {:a 1 :b 2} @called-with)))))


(deftest execute-throws-when-fn-id-missing
  (testing "non-callable, unknown fn-id throws `:fn-not-found`"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ctx (exec/create-context {:storage storage})
              bogus-id (random-uuid)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Function not found"
                (cr/execute ctx bogus-id {}))))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; `execute-by-name` — storage-name codec tolerance
;; ============================================================================

(deftest execute-by-name-finds-by-string
  (testing "string fn-name matches a storage entry"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :const-val (fn [_ _] 77))
        (setup/create-base-fn! storage "const-val" :int)
        (let [ctx (exec/create-context {:storage storage})]
          (is (= 77 (cr/execute-by-name ctx "const-val" nil))))
        (finally
          (sp/close storage))))))


(deftest execute-by-name-missing-fn-throws
  (testing "unknown fn-name throws `:fn-not-found`"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ctx (exec/create-context {:storage storage})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Function 'nope' not found"
                (cr/execute-by-name ctx "nope" nil))))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; `execute-with-arg-ids` — arg-id keyed map (legacy style)
;; ============================================================================

(deftest execute-with-arg-ids-translates-to-ext-names
  (testing "arg-ids are resolved to external names via `arg-ext-name`"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :add (setup/fn-impl [a b] (+ a b)))
        (let [{:keys [arg-a arg-b composed-fn]} (setup/setup-add-function! storage)
              ctx (exec/create-context {:storage storage})]
          (is (= 11 (cr/execute-with-arg-ids ctx (:id composed-fn)
                                             {(:id arg-a) 4 (:id arg-b) 7}))))
        (finally
          (sp/close storage)))))

  (testing "empty arg-id map passes {} straight to execute"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :const-7 (fn [_ _] 7))
        (let [base-fn (setup/create-base-fn! storage "const-7" :int)
              composed (setup/create-composed-fn! storage "my-c7" (:id base-fn))
              ctx (exec/create-context {:storage storage})]
          (is (= 7 (cr/execute-with-arg-ids ctx (:id composed) {}))))
        (finally
          (sp/close storage))))))


;; ============================================================================
;; `make-single-arg-callable`
;; ============================================================================

(deftest single-arg-callable-passes-fn-through
  (testing "when `fn-id` is already a fn, return it unchanged"
    (let [f (fn [x] (* 2 x))]
      (is (identical? f (cr/make-single-arg-callable {} f))))))


(deftest single-arg-callable-missing-fn-throws
  (testing "UUID that's not in the compiled registry throws"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ctx (exec/create-context {:storage storage})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Function not found"
                (cr/make-single-arg-callable ctx (random-uuid)))))
        (finally
          (sp/close storage))))))


(deftest single-arg-callable-one-free-arg
  (testing "single-free-arg target: callable routes item under that arg name"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :double (setup/fn-impl [x] (* 2 x)))
        (let [base-fn (setup/create-base-fn! storage "double" :int)
              _ (setup/create-arg! storage (:id base-fn)
                                   {:name "x" :type :int :required true :is-fn false})
              composed (setup/create-composed-fn! storage "my-double" (:id base-fn))
              ctx (exec/create-context {:storage storage})
              call (cr/make-single-arg-callable ctx (:id composed))]
          (is (= 10 (call 5))))
        (finally
          (sp/close storage))))))


(deftest single-arg-callable-zero-free-args-variadic
  (testing "0-free-arg target: callable accepts and ignores input"
    (let [storage (setup/create-test-storage)]
      (try
        (exec/register-base-fn! :const-zero (fn [_ _] 0))
        (let [base-fn (setup/create-base-fn! storage "const-zero" :int)
              composed (setup/create-composed-fn! storage "z" (:id base-fn))
              ctx (exec/create-context {:storage storage})
              call (cr/make-single-arg-callable ctx (:id composed))]
          (is (zero? (call :whatever)))
          (is (zero? (call :something-else-entirely))))
        (finally
          (sp/close storage))))))
