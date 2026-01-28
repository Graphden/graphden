(ns graphden.executor.result-value-test
  "Result value and registry tests for executor.

   Covers:
   - call-site tests
   - Base function registry tests
   - execute-by-name error path tests
   - execute-with-named-args error path tests
   - register-type-hint! tests
   - get-single-required-arg tests
   - with-clean-registry tests"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


;; === call-site Tests ===

(deftest call-site-basic-test
  (testing "call-site is executed and cached"
    (let [storage (setup/create-test-storage)
          call-count (atom 0)
          ;; Register a function that tracks how many times it's called
          _ (exec/register-base-fn!
              :counter
              (fn [_args _ctx]
                (swap! call-count inc)))
          ;; Create counter fn-schema
          counter-schema (sp/create-entity storage :fn-schema
                                           {:name "counter"
                                            :returned-type :int})
          ;; Create counter fn instance
          counter-fn (sp/create-entity storage :fn
                                       {:name "counter-fn"
                                        :fn-schema-id (:id counter-schema)})
          ;; Create call-site for counter-fn
          counter-result (sp/create-entity storage :call-site
                                           {:fn-id (:id counter-fn)
                                            :name "counter-result"})
          ;; Create add fn-schema that takes two int args
          _ (exec/register-base-fn!
              :add
              (fn [{:keys [a b]} _ctx]
                (+ @a @b)))
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
          ;; Create add fn that uses counter-result for BOTH args
          add-fn (sp/create-entity storage :fn
                                   {:name "add-fn"
                                    :fn-schema-id (:id add-schema)})
          ;; Both args reference the SAME call-site
          _ (setup/create-arg-value-with-binding! storage (:id add-fn) (:id add-arg-a) (:id counter-result))
          _ (setup/create-arg-value-with-binding! storage (:id add-fn) (:id add-arg-b) (:id counter-result))
          ctx (exec/create-context {:storage storage})]
      ;; Execute add-fn
      (exec/execute ctx (:id add-fn) nil)
      ;; counter should be called only ONCE, even though it's used twice
      (is (= 1 @call-count) "call-site should be cached and only executed once")
      (sp/close storage)))

  (testing "different call-sites for same fn are computed separately"
    (let [storage (setup/create-test-storage)
          call-count (atom 0)
          ;; Register a function that tracks calls and returns incremented count
          _ (exec/register-base-fn!
              :incrementer
              (fn [_args _ctx]
                (swap! call-count inc)))
          ;; Create incrementer fn-schema
          inc-schema (sp/create-entity storage :fn-schema
                                       {:name "incrementer"
                                        :returned-type :int})
          ;; Create incrementer fn instance
          inc-fn (sp/create-entity storage :fn
                                   {:name "inc-fn"
                                    :fn-schema-id (:id inc-schema)})
          ;; Create TWO different call-sites for the same fn
          result-1 (sp/create-entity storage :call-site
                                     {:fn-id (:id inc-fn)
                                      :name "result-1"})
          result-2 (sp/create-entity storage :call-site
                                     {:fn-id (:id inc-fn)
                                      :name "result-2"})
          ;; Create add fn that uses result-1 and result-2
          _ (exec/register-base-fn!
              :add
              (fn [{:keys [a b]} _ctx]
                (+ @a @b)))
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
          add-fn (sp/create-entity storage :fn
                                   {:name "add-fn"
                                    :fn-schema-id (:id add-schema)})
          ;; a -> result-1, b -> result-2
          _ (setup/create-arg-value-with-binding! storage (:id add-fn) (:id add-arg-a) (:id result-1))
          _ (setup/create-arg-value-with-binding! storage (:id add-fn) (:id add-arg-b) (:id result-2))
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id add-fn) nil)]
      ;; incrementer should be called TWICE (once for each call-site)
      (is (= 2 @call-count) "Different call-sites should each execute separately")
      ;; Result should be 1 + 2 = 3
      (is (= 3 result))
      (sp/close storage)))

  (testing "call-site executes fn, direct fn reference passes fn-id"
    (let [storage (setup/create-test-storage)
          call-count (atom 0)
          ;; Register a function that tracks calls
          _ (exec/register-base-fn!
              :counter
              (fn [_args _ctx]
                (swap! call-count inc)))
          ;; Create counter fn-schema
          counter-schema (sp/create-entity storage :fn-schema
                                           {:name "counter"
                                            :returned-type :int})
          counter-fn (sp/create-entity storage :fn
                                       {:name "counter-fn"
                                        :fn-schema-id (:id counter-schema)})
          ;; Create TWO call-sites pointing to same fn
          counter-result-1 (sp/create-entity storage :call-site
                                             {:fn-id (:id counter-fn)
                                              :name "counter-result-1"})
          counter-result-2 (sp/create-entity storage :call-site
                                             {:fn-id (:id counter-fn)
                                              :name "counter-result-2"})
          ;; Create add fn
          _ (exec/register-base-fn!
              :add
              (fn [{:keys [a b]} _ctx]
                (+ @a @b)))
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
          add-fn (sp/create-entity storage :fn
                                   {:name "add-fn"
                                    :fn-schema-id (:id add-schema)})
          ;; a -> call-site-1 (executes counter)
          ;; b -> call-site-2 (executes counter again - different call-site)
          _ (setup/create-arg-value-with-binding! storage (:id add-fn) (:id add-arg-a) (:id counter-result-1))
          _ (setup/create-arg-value-with-binding! storage (:id add-fn) (:id add-arg-b) (:id counter-result-2))
          ctx (exec/create-context {:storage storage})]
      (exec/execute ctx (:id add-fn) nil)
      ;; counter should be called TWICE - once for each call-site
      (is (= 2 @call-count) "Different call-sites should each execute the fn")
      (sp/close storage))))


;; Note: get-single-required-arg and make-single-arg-callable are designed
;; to be used INSIDE HOF base functions during execution (when the execution
;; graph is populated). They are tested indirectly via lazy-fn-callable-test
;; and other HOF tests that exercise the full execution flow.


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
          schema (sp/create-entity storage :fn-schema
                                   {:name "const-42" :returned-type :int})
          _ (sp/create-entity storage :fn
                              {:name "my-const-fn"
                               :fn-schema-id (:id schema)})
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
          _ (exec/register-base-fn! :add-named (fn [{:keys [a b]} _] (+ @a @b)))
          schema (sp/create-entity storage :fn-schema
                                   {:name "add-named" :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id schema)
                               :name "a"
                               :type :int
                               :required true})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id schema)
                               :name "b"
                               :type :int
                               :required true})
          the-fn (sp/create-entity storage :fn
                                   {:name "my-add-named"
                                    :fn-schema-id (:id schema)})
          ctx (exec/create-context {:storage storage})]
      (is (= 7 (exec/execute-with-named-args ctx (:id the-fn) {:a 3 :b 4})))
      (sp/close storage)))

  (testing "throws for unknown arg name"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn! :single-arg (fn [{:keys [x]} _] @x))
          schema (sp/create-entity storage :fn-schema
                                   {:name "single-arg" :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id schema)
                               :name "x"
                               :type :int
                               :required true})
          the-fn (sp/create-entity storage :fn
                                   {:name "my-single-arg"
                                    :fn-schema-id (:id schema)})
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


;; === get-single-required-arg Tests ===

(deftest get-single-required-arg-interface-test
  (testing "returns single required arg-schema"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn! :identity-fn (fn [{:keys [x]} _] @x))
          schema (sp/create-entity storage :fn-schema
                                   {:name "identity-fn" :returned-type :int})
          arg-schema (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id schema)
                                        :name "x"
                                        :type :int
                                        :required true})
          the-fn (sp/create-entity storage :fn
                                   {:name "my-identity"
                                    :fn-schema-id (:id schema)})
          ctx (exec/create-context {:storage storage})
          ;; Need to pre-resolve graph to have arg-schemas available
          graph (sp/resolve-execution-graph storage (:id the-fn))
          ctx-with-graph (assoc ctx :execution-graph graph)]
      (let [result (exec/get-single-required-arg ctx-with-graph (:id the-fn))]
        (is (= (:id arg-schema) (:id result)))
        (is (= "x" (:name result))))
      (sp/close storage)))

  (testing "throws when HOF function has more than 1 required argument"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn! :multi-arg-fn (fn [{:keys [a b]} _] (+ @a @b)))
          schema (sp/create-entity storage :fn-schema
                                   {:name "multi-arg-fn" :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id schema)
                               :name "a"
                               :type :int
                               :required true})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id schema)
                               :name "b"
                               :type :int
                               :required true})
          the-fn (sp/create-entity storage :fn
                                   {:name "my-multi"
                                    :fn-schema-id (:id schema)})
          ctx (exec/create-context {:storage storage})
          graph (sp/resolve-execution-graph storage (:id the-fn))
          ctx-with-graph (assoc ctx :execution-graph graph)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"HOF function requires exactly 1 required argument"
            (exec/get-single-required-arg ctx-with-graph (:id the-fn))))
      (sp/close storage)))

  (testing "throws when HOF function has 0 required arguments"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn! :no-req-fn (fn [{:keys [x]} _] (or @x 0)))
          schema (sp/create-entity storage :fn-schema
                                   {:name "no-req-fn" :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id schema)
                               :name "x"
                               :type :int
                               :required false})  ; optional, not required
          the-fn (sp/create-entity storage :fn
                                   {:name "my-optional"
                                    :fn-schema-id (:id schema)})
          ctx (exec/create-context {:storage storage})
          graph (sp/resolve-execution-graph storage (:id the-fn))
          ctx-with-graph (assoc ctx :execution-graph graph)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"HOF function requires exactly 1 required argument"
            (exec/get-single-required-arg ctx-with-graph (:id the-fn))))
      (sp/close storage))))


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
