(ns graphden.executor.named-args-test
  "Named args tests for executor.

   Covers:
   - execute-with-named-args tests
   - execute-by-name tests
   - Call-site-args tests"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]))


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


;; === Call-Site-Args Tests ===

(deftest call-site-args-basic-test
  (testing "call-site-args provides values for free arguments (root function)"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Only provide :a, leave :b as free arg
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 10)
          ;; Use call-site-args to provide :b by arg-schema-id (root function format)
          ctx (exec/create-context {:storage storage
                                    :call-site-args {(:id arg-b) 20}})]
      (is (= 30 (exec/execute ctx (:id fn-rec) nil)))
      (sp/close storage)))

  (testing "call-site-args ignores override of DB-defined args with warning"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Both args defined in DB
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 10)
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-b) 20)
          ;; Try to override :a via call-site-args - should be ignored
          ctx (exec/create-context {:storage storage
                                    :call-site-args {(:id arg-a) 100}})]
      ;; Should use DB value (10) not call-site-arg (100)
      (is (= 30 (exec/execute ctx (:id fn-rec) nil)))
      (sp/close storage)))

  (testing "call-site-args throws error for missing required arg"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a]} (setup/setup-add-function! storage)
          ;; Only provide :a, leave :b as free required arg
          _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-a) 10)
          ;; Don't provide :b via call-site-args
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Required argument 'b' not provided"
            (exec/execute ctx (:id fn-rec) nil)))
      (sp/close storage))))


(deftest call-site-args-nested-test
  (testing "call-site-args provides values for nested function via call-site"
    (let [storage (setup/create-test-storage)
          ;; Register identity function
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} _ctx]
                @x))
          ;; Create identity fn-schema
          id-schema (sp/create-entity storage :fn-schema
                                      {:name "identity"
                                       :returned-type :int})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id id-schema)
                                    :name "x"
                                    :type :int
                                    :required true})
          ;; Create outer fn that wraps identity
          outer-fn (sp/create-entity storage :fn
                                     {:name "outer"
                                      :fn-schema-id (:id id-schema)})
          ;; Create inner fn with free arg
          inner-fn (sp/create-entity storage :fn
                                     {:name "inner"
                                      :fn-schema-id (:id id-schema)})
          ;; Create call-site for inner (call site)
          inner-call-site (sp/create-entity storage :call-site
                                            {:fn-id (:id inner-fn)
                                             :name "inner-call-site"})
          ;; outer's x -> call-site (which points to inner)
          _ (setup/create-arg-value-with-binding! storage (:id outer-fn) (:id id-arg) (:id inner-call-site))
          ;; inner's x is free - provide via call-site-args using [call-site-id arg-schema-id]
          ctx (exec/create-context {:storage storage
                                    :call-site-args {[(:id inner-call-site) (:id id-arg)] 42}})]
      (is (= 42 (exec/execute ctx (:id outer-fn) nil)))
      (sp/close storage)))

  (testing "call-site-args with different call-sites for same function"
    (let [storage (setup/create-test-storage)
          ;; A function that takes two args and adds them
          _ (exec/register-base-fn!
              :add
              (fn [{:keys [a b]} _ctx]
                (+ @a @b)))
          ;; identity fn for passthrough
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} _ctx]
                @x))
          ;; Create add fn-schema
          add-schema (sp/create-entity storage :fn-schema
                                       {:name "add"
                                        :returned-type :int})
          add-arg-a (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id add-schema)
                                       :name "a"
                                       :type :int
                                       :required true})
          add-arg-b (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id add-schema)
                                       :name "b"
                                       :type :int
                                       :required true})
          ;; Create identity fn-schema
          id-schema (sp/create-entity storage :fn-schema
                                      {:name "identity"
                                       :returned-type :int})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id id-schema)
                                    :name "x"
                                    :type :int
                                    :required true})
          ;; Create the identity function instance
          id-fn (sp/create-entity storage :fn
                                  {:name "id-fn"
                                   :fn-schema-id (:id id-schema)})
          ;; Create TWO call-sites for same id-fn (different call sites)
          call-site-a (sp/create-entity storage :call-site
                                        {:fn-id (:id id-fn)
                                         :name "call-site-a"})
          call-site-b (sp/create-entity storage :call-site
                                        {:fn-id (:id id-fn)
                                         :name "call-site-b"})
          ;; Create add function instance
          add-fn (sp/create-entity storage :fn
                                   {:name "add-fn"
                                    :fn-schema-id (:id add-schema)})
          ;; add-fn's a -> call-site-a, b -> call-site-b
          _ (setup/create-arg-value-with-binding! storage (:id add-fn) (:id add-arg-a) (:id call-site-a))
          _ (setup/create-arg-value-with-binding! storage (:id add-fn) (:id add-arg-b) (:id call-site-b))
          ;; Provide different values for id-fn's x via different call sites
          ;; call-site-a's x = 10, call-site-b's x = 32
          ctx (exec/create-context {:storage storage
                                    :call-site-args {[(:id call-site-a) (:id id-arg)] 10
                                                     [(:id call-site-b) (:id id-arg)] 32}})]
      (is (= 42 (exec/execute ctx (:id add-fn) nil)))
      (sp/close storage)))

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
                                        :required true})
          map-arg-coll (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id map-schema)
                                          :name "coll"
                                          :type :jsonb
                                          :required true})
          ;; Create recorder fn-schema with exactly 1 required arg
          rec-schema (sp/create-entity storage :fn-schema
                                       {:name "recorder"
                                        :returned-type :int})
          _rec-arg-item (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id rec-schema)
                                           :name "item"
                                           :type :int
                                           :required true})
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


(deftest call-site-args-context-validation-test
  (testing "throws when call-site-args is not a map"
    (let [storage (setup/create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"call-site-args must be a map"
            (exec/create-context {:storage storage
                                  :call-site-args "not a map"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"call-site-args must be a map"
            (exec/create-context {:storage storage
                                  :call-site-args [[:a] 10]})))
      (sp/close storage)))

  (testing "accepts empty call-site-args map"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage
                                    :call-site-args {}})]
      (is (some? ctx))
      (sp/close storage)))

  (testing "accepts nil call-site-args (defaults to empty)"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (some? ctx))
      (sp/close storage))))
