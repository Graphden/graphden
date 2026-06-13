(ns graphden.executor.named-args-test
  "Named args tests for executor.

   Covers:
   - execute-with-named-args tests
   - execute-by-name tests
   - Fn-usage-args tests

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


;; === execute-with-named-args Tests ===

(deftest execute-with-named-args-test
  (testing "executes with named args mapped to schema ids for free args"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn]} (setup/setup-add-function! storage)
          ;; Create composed fn with no args - both args are free
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      ;; Provide both free args by name
      (is (= 30 (exec/execute-with-named-args ctx (:id composed-fn) {:a 10 :b 20})))
      (sp/close storage)))

  (testing "executes with named args - partial free args"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Create composed fn with both args: :a bound, :b free
          composed-fn (setup/create-composed-fn! storage "partial-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true
                                :source-id (:id arg-a) :value 100})
          ;; Create free arg :b (no value, will be provided via named-args)
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true
                                :source-id (:id arg-b)})
          ctx (exec/create-context {:storage storage})]
      ;; Provide free arg-b by name (arg-a from DB)
      (is (= 102 (exec/execute-with-named-args ctx (:id composed-fn) {:b 2})))
      (sp/close storage)))

  (testing "executes with nil named-args (uses defaults)"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true
                                :source-id (:id arg-a) :value 5})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true
                                :source-id (:id arg-b) :value 7})
          ctx (exec/create-context {:storage storage})]
      (is (= 12 (exec/execute-with-named-args ctx (:id composed-fn) nil)))
      (sp/close storage)))

  (testing "executes with empty named-args map (uses defaults)"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true
                                :source-id (:id arg-a) :value 3})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true
                                :source-id (:id arg-b) :value 4})
          ctx (exec/create-context {:storage storage})]
      (is (= 7 (exec/execute-with-named-args ctx (:id composed-fn) {})))
      (sp/close storage)))

  (testing "throws on invalid named-args type"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true
                                :source-id (:id arg-a) :value 1})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true
                                :source-id (:id arg-b) :value 2})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"named-args must be nil or a map"
            (exec/execute-with-named-args ctx (:id composed-fn) "not a map")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"named-args must be nil or a map"
            (exec/execute-with-named-args ctx (:id composed-fn) [:a :b])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"named-args must be nil or a map"
            (exec/execute-with-named-args ctx (:id composed-fn) 123)))
      (sp/close storage)))

  (testing "throws on unknown arg name"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true
                                :source-id (:id arg-a) :value 1})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true
                                :source-id (:id arg-b) :value 2})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown argument name"
            (exec/execute-with-named-args ctx (:id composed-fn) {:unknown-arg 42})))
      (sp/close storage))))


;; === execute-by-name Tests ===

(deftest execute-by-name-test
  (testing "executes function by name"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true
                                :source-id (:id arg-a) :value 10})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true
                                :source-id (:id arg-b) :value 20})
          ctx (exec/create-context {:storage storage})]
      ;; Note: the fn entity is named "my-add"
      (is (= 30 (exec/execute-by-name ctx "my-add" nil)))
      (sp/close storage)))

  (testing "executes function by name with named args"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn]} (setup/setup-add-function! storage)
          ;; Create composed fn with no args (free args)
          _ (setup/create-composed-fn! storage "my-add" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      ;; Note: the fn entity is named "my-add"
      (is (= 15 (exec/execute-by-name ctx "my-add" {:a 5 :b 10})))
      (sp/close storage)))

  (testing "throws when function name not found"
    (let [storage (setup/create-test-storage)
          _ (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Function 'nonexistent' not found"
            (exec/execute-by-name ctx "nonexistent" nil)))
      (sp/close storage)))

  (testing "throws when fn-name is not a string"
    (let [storage (setup/create-test-storage)
          _ (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx :my-add nil)))
      (sp/close storage)))

  (testing "throws when fn-name is nil"
    (let [storage (setup/create-test-storage)
          _ (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx nil nil)))
      (sp/close storage)))

  (testing "throws when fn-name is integer"
    (let [storage (setup/create-test-storage)
          _ (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx 123 nil)))
      (sp/close storage)))

  (testing "fn-name error includes type information"
    (let [storage (setup/create-test-storage)
          _ (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (try
        (exec/execute-by-name ctx :keyword-name nil)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/invalid-fn-name (:type (ex-data e))))
          (is (= :keyword-name (:fn-name (ex-data e))))
          (is (= clojure.lang.Keyword (:fn-name-type (ex-data e))))))
      (sp/close storage)))

  (testing "fn-not-found error includes function name"
    (let [storage (setup/create-test-storage)
          _ (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (try
        (exec/execute-by-name ctx "no-such-fn" nil)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/fn-not-found (:type (ex-data e))))
          (is (= "no-such-fn" (:fn-name (ex-data e))))))
      (sp/close storage))))


;; === HOF Tests ===

(deftest hof-single-arg-model-test
  (testing "HOF functions work with single-arg model"
    ;; This test verifies HOF with the new single-arg model:
    ;; - HOF receives fn-id (not callable)
    ;; - HOF uses make-single-arg-callable to create callable
    ;; - Child function must have exactly 1 required argument
    (let [storage (setup/create-test-storage)
          call-args (atom [])
          ;; A map-like function that receives fn-id and uses make-single-arg-callable
          _ (exec/register-base-fn!
              :my-map
              (setup/fn-impl [f coll]
                             (let [callable (exec/make-single-arg-callable ctx f)]
                               (mapv callable coll))))
          ;; An identity function that records what it receives
          ;; Takes exactly 1 required arg (item) for HOF compatibility
          _ (exec/register-base-fn!
              :recorder
              (setup/fn-impl [item]
                             (swap! call-args conj item)
                             item))
          ;; Create my-map base fn
          map-base (setup/create-base-fn! storage "my-map" :jsonb)
          map-arg-f (setup/create-arg! storage (:id map-base)
                                       {:name "f" :type [:fn {:item :int} :int]
                                        :required true})
          map-arg-coll (setup/create-arg! storage (:id map-base)
                                          {:name "coll" :type :jsonb :required true})
          ;; Create recorder base fn with exactly 1 required arg
          rec-base (setup/create-base-fn! storage "recorder" :int)
          _rec-arg-item (setup/create-arg! storage (:id rec-base)
                                           {:name "item" :type :int :required true})
          ;; Create recorder fn instance (no args - item is free for HOF)
          rec-fn (setup/create-composed-fn! storage "rec-fn" (:id rec-base))
          ;; Create my-map fn instance
          map-fn (setup/create-composed-fn! storage "map-fn" (:id map-base))
          ;; map-fn's f -> rec-fn via ref-id (is-fn=true passes fn-id)
          _ (setup/create-arg! storage (:id map-fn)
                               {:name "f" :type [:fn {:item :int} :int]
                                :required true
                                :source-id (:id map-arg-f) :ref-id (:id rec-fn)})
          ;; map-fn's coll -> [1 2 3]
          _ (setup/create-arg! storage (:id map-fn)
                               {:name "coll" :type :jsonb :required true
                                :source-id (:id map-arg-coll) :value [1 2 3]})
          ctx (exec/create-context {:storage storage})]
      ;; Execute - should map recorder over [1 2 3]
      (is (= [1 2 3] (exec/execute ctx (:id map-fn) nil)))
      ;; Verify all items were recorded
      (is (= [1 2 3] @call-args))
      (sp/close storage))))
