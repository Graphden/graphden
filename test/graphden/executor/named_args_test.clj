(ns graphden.executor.named-args-test
  "Named args tests for executor.

   Covers:
   - execute-with-named-args tests
   - execute-by-name tests
   - Fn-usage-args tests"
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
          {:keys [fn-rec]} (setup/setup-add-function! storage)
          ;; No arg-values in DB - both args are free
          ctx (exec/create-context {:storage storage})]
      ;; Provide both free args by name
      (is (= 30 (exec/execute-with-named-args ctx (:id fn-rec) {:a 10 :b 20})))
      (sp/close storage)))

  (testing "executes with named args - partial free args"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a]} (setup/setup-add-function! storage)
          ;; Only arg-a in DB, arg-b is free
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 100)
          ctx (exec/create-context {:storage storage})]
      ;; Provide free arg-b by name (arg-a from DB)
      (is (= 102 (exec/execute-with-named-args ctx (:id fn-rec) {:b 2})))
      (sp/close storage)))

  (testing "executes with nil named-args (uses defaults)"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 5)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-b) 7)
          ctx (exec/create-context {:storage storage})]
      (is (= 12 (exec/execute-with-named-args ctx (:id fn-rec) nil)))
      (sp/close storage)))

  (testing "executes with empty named-args map (uses defaults)"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 3)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-b) 4)
          ctx (exec/create-context {:storage storage})]
      (is (= 7 (exec/execute-with-named-args ctx (:id fn-rec) {})))
      (sp/close storage)))

  (testing "throws on invalid named-args type"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 1)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-b) 2)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"named-args must be nil or a map"
            (exec/execute-with-named-args ctx (:id fn-rec) "not a map")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"named-args must be nil or a map"
            (exec/execute-with-named-args ctx (:id fn-rec) [:a :b])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"named-args must be nil or a map"
            (exec/execute-with-named-args ctx (:id fn-rec) 123)))
      (sp/close storage)))

  (testing "throws on unknown arg name"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 1)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-b) 2)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown argument name"
            (exec/execute-with-named-args ctx (:id fn-rec) {:unknown-arg 42})))
      (sp/close storage))))


;; === execute-by-name Tests ===

(deftest execute-by-name-test
  (testing "executes function by name"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 10)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-b) 20)
          ctx (exec/create-context {:storage storage})]
      ;; Note: the fn entity is named "my-add", not "add"
      (is (= 30 (exec/execute-by-name ctx "my-add" nil)))
      (sp/close storage)))

  (testing "executes function by name with named args"
    (let [storage (setup/create-test-storage)
          _ (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      ;; Note: the fn entity is named "my-add", not "add"
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
              (fn [{:keys [f coll]} ctx]
                (let [callable (exec/make-single-arg-callable ctx @f)]
                  (mapv callable @coll))))
          ;; An identity function that records what it receives
          ;; Takes exactly 1 required arg (item) for HOF compatibility
          _ (exec/register-base-fn!
              :recorder
              (fn [{:keys [item]} _ctx]
                (let [v @item]
                  (swap! call-args conj v)
                  v)))
          ;; Create my-map fn-schema
          map-schema (sp/create-entity storage :fn-schema
                                       {:name "my-map"
                                        :returned-type :jsonb})
          _map-arg-f (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id map-schema)
                                        :name "f"
                                        :type :fn  ; HOF!
                                        :required true :first-class false})
          map-arg-coll (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id map-schema)
                                          :name "coll"
                                          :type :jsonb
                                          :required true :first-class false})
          ;; Create recorder fn-schema with exactly 1 required arg
          rec-schema (sp/create-entity storage :fn-schema
                                       {:name "recorder"
                                        :returned-type :int})
          _rec-arg-item (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id rec-schema)
                                           :name "item"
                                           :type :int
                                           :required true :first-class false})
          ;; Create recorder fn instance
          rec-fn (sp/create-entity storage :fn
                                   {:name "rec-fn"
                                    :fn-schema-id (:id rec-schema)})
          ;; Create my-map fn instance
          map-fn (sp/create-entity storage :fn
                                   {:name "map-fn"
                                    :fn-schema-id (:id map-schema)})
          ;; map-fn's f -> rec-fn (direct ref, HOF)
          _ (setup/create-arg-value-with-binding! storage (:id map-fn) (:id _map-arg-f) (:id rec-fn))
          ;; map-fn's coll -> [1 2 3]
          _ (setup/create-arg-value-with-binding! storage (:id map-fn) (:id map-arg-coll) [1 2 3])
          ctx (exec/create-context {:storage storage})]
      ;; Execute - should map recorder over [1 2 3]
      (is (= [1 2 3] (exec/execute ctx (:id map-fn) nil)))
      ;; Verify all items were recorded
      (is (= [1 2 3] @call-args))
      (sp/close storage))))
