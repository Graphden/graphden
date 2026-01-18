(ns graphden.executor.named-args-test
  "Named args tests for executor.

   Covers:
   - execute-with-named-args tests
   - execute-by-name tests
   - Path-args tests"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


;; === execute-with-named-args Tests ===

(deftest execute-with-named-args-test
  (testing "executes with named args mapped to schema ids"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Create default arg-values
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 2})
          ctx (exec/create-context {:storage storage})]
      ;; Override args by name
      (is (= 30 (exec/execute-with-named-args ctx (:id fn-rec) {:a 10 :b 20})))
      ;; Override only one arg
      (is (= 102 (exec/execute-with-named-args ctx (:id fn-rec) {:a 100})))
      (sp/close storage)))

  (testing "executes with nil named-args (uses defaults)"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 5})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 7})
          ctx (exec/create-context {:storage storage})]
      (is (= 12 (exec/execute-with-named-args ctx (:id fn-rec) nil)))
      (sp/close storage)))

  (testing "executes with empty named-args map (uses defaults)"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 3})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 4})
          ctx (exec/create-context {:storage storage})]
      (is (= 7 (exec/execute-with-named-args ctx (:id fn-rec) {})))
      (sp/close storage)))

  (testing "throws on invalid named-args type"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 2})
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
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 2})
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
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 10})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 20})
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


;; === Path-Args Tests ===

(deftest path-args-basic-test
  (testing "path-args provides values for free arguments (root function)"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Only provide :a, leave :b as free arg
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 10})
          ;; Use path-args to provide :b by arg-schema-id (root function format)
          ctx (exec/create-context {:storage storage
                                    :path-args {(:id arg-b) 20}})]
      (is (= 30 (exec/execute ctx (:id fn-rec) nil)))
      (sp/close storage)))

  (testing "path-args ignores override of DB-defined args with warning"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Both args defined in DB
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 10})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 20})
          ;; Try to override :a via path-args - should be ignored
          ctx (exec/create-context {:storage storage
                                    :path-args {(:id arg-a) 100}})]
      ;; Should use DB value (10) not path-arg (100)
      (is (= 30 (exec/execute ctx (:id fn-rec) nil)))
      (sp/close storage)))

  (testing "path-args throws error for missing required arg"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a]} (setup/setup-add-function! storage)
          ;; Only provide :a, leave :b as free required arg
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 10})
          ;; Don't provide :b via path-args
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Required argument 'b' not provided"
            (exec/execute ctx (:id fn-rec) nil)))
      (sp/close storage))))


(deftest path-args-nested-test
  (testing "path-args provides values for nested function via fn-result-value"
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
          ;; Create fn-result-value for inner
          inner-frv (sp/create-entity storage :fn-result-value
                                      {:fn-id (:id inner-fn)
                                       :name "inner-frv"})
          ;; outer's x -> fn-result-value (which points to inner)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id outer-fn)
                               :arg-schema-id (:id id-arg)
                               :value (:id inner-frv)})
          ;; inner's x is free - provide via path-args using [frv-id arg-schema-id]
          ctx (exec/create-context {:storage storage
                                    :path-args {[(:id inner-frv) (:id id-arg)] 42}})]
      (is (= 42 (exec/execute ctx (:id outer-fn) nil)))
      (sp/close storage)))

  (testing "path-args with different fn-result-values for same function"
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
          ;; Create TWO fn-result-values for same id-fn (different computations)
          frv-a (sp/create-entity storage :fn-result-value
                                  {:fn-id (:id id-fn)
                                   :name "frv-a"})
          frv-b (sp/create-entity storage :fn-result-value
                                  {:fn-id (:id id-fn)
                                   :name "frv-b"})
          ;; Create add function instance
          add-fn (sp/create-entity storage :fn
                                   {:name "add-fn"
                                    :fn-schema-id (:id add-schema)})
          ;; add-fn's a -> frv-a, b -> frv-b
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id add-fn)
                               :arg-schema-id (:id add-arg-a)
                               :value (:id frv-a)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id add-fn)
                               :arg-schema-id (:id add-arg-b)
                               :value (:id frv-b)})
          ;; Provide different values for id-fn's x via different fn-result-values
          ;; frv-a's x = 10, frv-b's x = 32
          ctx (exec/create-context {:storage storage
                                    :path-args {[(:id frv-a) (:id id-arg)] 10
                                                [(:id frv-b) (:id id-arg)] 32}})]
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
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id map-fn)
                               :arg-schema-id (:id _map-arg-f)
                               :value (:id rec-fn)})
          ;; map-fn's coll -> [1 2 3]
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id map-fn)
                               :arg-schema-id (:id map-arg-coll)
                               :value [1 2 3]})
          ctx (exec/create-context {:storage storage})]
      ;; Execute - should map recorder over [1 2 3]
      (is (= [1 2 3] (exec/execute ctx (:id map-fn) nil)))
      ;; Verify all items were recorded
      (is (= [1 2 3] @call-args))
      (sp/close storage))))


(deftest path-args-context-validation-test
  (testing "throws when path-args is not a map"
    (let [storage (setup/create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"path-args must be a map"
            (exec/create-context {:storage storage
                                  :path-args "not a map"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"path-args must be a map"
            (exec/create-context {:storage storage
                                  :path-args [[:a] 10]})))
      (sp/close storage)))

  (testing "accepts empty path-args map"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage
                                    :path-args {}})]
      (is (some? ctx))
      (sp/close storage)))

  (testing "accepts nil path-args (defaults to empty)"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (some? ctx))
      (sp/close storage))))
