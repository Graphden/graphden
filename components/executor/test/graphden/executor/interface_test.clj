(ns graphden.executor.interface-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp]))


;; === Test Fixtures ===

(defn with-clean-registry
  [f]
  (exec/clear-base-fns!)
  (try
    (f)
    (finally
      (exec/clear-base-fns!))))


(use-fixtures :each with-clean-registry)


;; === Helper Functions ===

(defn create-test-storage
  "Creates a storage with a simple fn-schema and registers the base function."
  []
  (gsm/create-storage))


(defn setup-add-function!
  "Sets up an 'add' function that adds two numbers.
   Returns {:fn-schema fn-schema :arg-a arg-schema-a :arg-b arg-schema-b :fn fn-rec}"
  [storage]
  ;; Register the base function
  (exec/register-base-fn!
    :add
    (fn [{:keys [a b]} ctx]
      (+ (exec/force-value a ctx)
         (exec/force-value b ctx))))

  ;; Create fn-schema
  (let [fn-schema (sp/create-entity storage :fn-schema
                                    {:name "add"
                                     :returned-type :int})
        ;; Create arg-schemas
        arg-a (sp/create-entity storage :arg-schema
                                {:fn-schema-id (:id fn-schema)
                                 :name "a"
                                 :type :int
                                 :required true})
        arg-b (sp/create-entity storage :arg-schema
                                {:fn-schema-id (:id fn-schema)
                                 :name "b"
                                 :type :int
                                 :required true})
        ;; Create fn instance
        fn-rec (sp/create-entity storage :fn
                                 {:name "my-add"
                                  :fn-schema-id (:id fn-schema)})]
    {:fn-schema fn-schema
     :arg-a arg-a
     :arg-b arg-b
     :fn-rec fn-rec}))


;; === Tests ===

(deftest base-fn-registry-test
  (testing "register and get base function"
    (let [add-fn (fn [args _ctx] (+ (:a args) (:b args)))]
      (exec/register-base-fn! :test-add add-fn)
      (is (= add-fn (exec/get-base-fn :test-add)))))

  (testing "get-base-fn returns nil for unknown function"
    (is (nil? (exec/get-base-fn :unknown-fn))))

  (testing "clear-base-fns! removes all functions"
    (exec/register-base-fn! :fn1 identity)
    (exec/register-base-fn! :fn2 identity)
    (exec/clear-base-fns!)
    (is (nil? (exec/get-base-fn :fn1)))
    (is (nil? (exec/get-base-fn :fn2))))

  (testing "get-default-registry returns registered functions"
    (exec/register-base-fn! :reg-test-1 identity)
    (exec/register-base-fn! :reg-test-2 str)
    (let [registry (exec/get-default-registry)]
      (is (map? registry))
      (is (= identity (get registry :reg-test-1)))
      (is (= str (get registry :reg-test-2)))))

  (testing "get-base-fn-from-context returns function from context"
    (let [storage (create-test-storage)
          my-fn (fn [_ _] 42)
          ctx (exec/create-context {:storage storage
                                    :base-fns {:my-custom-fn my-fn}})]
      (is (= my-fn (exec/get-base-fn-from-context ctx :my-custom-fn)))
      (is (nil? (exec/get-base-fn-from-context ctx :nonexistent)))
      (sp/close storage))))


(deftest create-context-test
  (testing "creates context with required storage"
    (let [storage (create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (some? ctx))
      (sp/close storage)))

  (testing "throws when storage is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage is required"
          (exec/create-context {})))))


(deftest execute-simple-function-test
  (testing "executes function with literal arg-values"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
          ;; Create arg-values with literals
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 3})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 5})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id fn-rec) {})]
      (is (= 8 result))
      (sp/close storage))))


(deftest execute-with-parent-chain-test
  (testing "inherits arg-values from parent"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b fn-schema]} (setup-add-function! storage)
          ;; Parent has :a = 10
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 10})
          ;; Create child fn with :b = 5
          child-fn (sp/create-entity storage :fn
                                     {:name "child-add"
                                      :fn-schema-id (:id fn-schema)
                                      :parent-fn-id (:id fn-rec)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id child-fn)
                               :arg-schema-id (:id arg-b)
                               :value 5})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id child-fn) {})]
      ;; Should use a=10 from parent, b=5 from child
      (is (= 15 result))
      (sp/close storage))))


(deftest execute-fn-reference-test
  (testing "executes referenced function and uses result"
    (let [storage (create-test-storage)
          ;; Register a constant function
          _ (exec/register-base-fn!
              :const
              (fn [{:keys [value]} ctx]
                (exec/force-value value ctx)))
          ;; Create const fn-schema
          const-schema (sp/create-entity storage :fn-schema
                                         {:name "const"
                                          :returned-type :int})
          const-arg (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id const-schema)
                                       :name "value"
                                       :type :int
                                       :required true})
          ;; Create two const functions
          const-3 (sp/create-entity storage :fn
                                    {:name "const-3"
                                     :fn-schema-id (:id const-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id const-3)
                               :arg-schema-id (:id const-arg)
                               :value 3})
          const-5 (sp/create-entity storage :fn
                                    {:name "const-5"
                                     :fn-schema-id (:id const-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id const-5)
                               :arg-schema-id (:id const-arg)
                               :value 5})
          ;; Create add fn-schema
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
          ;; Set arg-values to reference const functions
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value (:id const-3)})  ; Reference to const-3
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value (:id const-5)})  ; Reference to const-5
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id fn-rec) {})]
      (is (= 8 result))
      (sp/close storage))))


(deftest max-depth-protection-test
  (testing "throws when max depth is exceeded"
    (let [storage (create-test-storage)
          ;; Register identity function
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} ctx]
                (exec/force-value x ctx)))
          ;; Create identity fn-schema
          id-schema (sp/create-entity storage :fn-schema
                                      {:name "identity"
                                       :returned-type :int})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id id-schema)
                                    :name "x"
                                    :type :int
                                    :required true})
          ;; Create chain of functions that reference each other
          fn-a (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id (:id id-schema)})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id (:id id-schema)})
          fn-c (sp/create-entity storage :fn {:name "fn-c" :fn-schema-id (:id id-schema)})
          ;; fn-a -> fn-b -> fn-c -> literal
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-a)
                               :arg-schema-id (:id id-arg)
                               :value (:id fn-b)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id id-arg)
                               :value (:id fn-c)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-c)
                               :arg-schema-id (:id id-arg)
                               :value 42})
          ;; Execute with max-depth=1 (should fail at fn-c which runs at depth=2)
          ctx (exec/create-context {:storage storage :max-depth 1})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Maximum recursion depth exceeded"
            (exec/execute ctx (:id fn-a) {})))
      (sp/close storage))))


(deftest missing-base-fn-test
  (testing "throws when base function is not registered"
    (let [storage (create-test-storage)
          ;; Create fn-schema without registering base function
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "unknown-fn"
                                       :returned-type :int})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-unknown"
                                    :fn-schema-id (:id fn-schema)})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Base function 'unknown-fn' not found"
            (exec/execute ctx (:id fn-rec) {})))
      (sp/close storage))))


(deftest missing-required-arg-test
  (testing "throws when required argument is not provided"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a]} (setup-add-function! storage)
          ;; Only provide :a, not :b
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 3})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required argument 'b' not provided"
            (exec/execute ctx (:id fn-rec) {})))
      (sp/close storage))))


(deftest fn-not-found-test
  (testing "throws when function id doesn't exist"
    (let [storage (create-test-storage)
          ctx (exec/create-context {:storage storage})
          fake-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
            (exec/execute ctx fake-id {})))
      (sp/close storage))))


(deftest timeout-protection-test
  (testing "throws when execution timeout is exceeded"
    (let [storage (create-test-storage)
          ;; Register a slow function that sleeps and calls another fn
          _ (exec/register-base-fn!
              :slow-fn
              (fn [{:keys [x]} ctx]
                (Thread/sleep 50) ; Sleep for 50ms
                (exec/force-value x ctx)))
          ;; Create slow fn-schema
          slow-schema (sp/create-entity storage :fn-schema
                                        {:name "slow-fn"
                                         :returned-type :int})
          slow-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id slow-schema)
                                      :name "x"
                                      :type :int
                                      :required true})
          ;; Create a chain: fn-a -> fn-b -> fn-c -> literal
          ;; Each step sleeps 50ms, so by fn-c the timeout should be exceeded
          fn-a (sp/create-entity storage :fn
                                 {:name "slow-a"
                                  :fn-schema-id (:id slow-schema)})
          fn-b (sp/create-entity storage :fn
                                 {:name "slow-b"
                                  :fn-schema-id (:id slow-schema)})
          fn-c (sp/create-entity storage :fn
                                 {:name "slow-c"
                                  :fn-schema-id (:id slow-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-a)
                               :arg-schema-id (:id slow-arg)
                               :value (:id fn-b)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id slow-arg)
                               :value (:id fn-c)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-c)
                               :arg-schema-id (:id slow-arg)
                               :value 42})
          ;; Create context with 80ms timeout (fn-a sleeps 50ms, fn-b starts, sleeps 50ms = 100ms > 80ms)
          ctx (exec/create-context {:storage storage :timeout-ms 80})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Execution timeout exceeded"
            (exec/execute ctx (:id fn-a) {})))
      (sp/close storage))))


(deftest lazy-fn-thunk-test
  (testing "HOF: fn-type arg returns fn-id instead of executing"
    (let [storage (create-test-storage)
          ;; Register a higher-order function that receives another fn
          _ (exec/register-base-fn!
              :apply-fn
              (fn [{:keys [f value]} ctx]
                ;; f should be a fn-id (not executed), value is literal
                (let [fn-id (exec/force-value f ctx)
                      v (exec/force-value value ctx)]
                  ;; For this test, just return the fn-id to verify it wasn't executed
                  {:fn-id fn-id :value v})))
          ;; Register a simple function
          _ (exec/register-base-fn!
              :double
              (fn [{:keys [x]} ctx]
                (* 2 (exec/force-value x ctx))))
          ;; Create apply-fn schema
          apply-schema (sp/create-entity storage :fn-schema
                                         {:name "apply-fn"
                                          :returned-type :int})
          apply-f-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id apply-schema)
                                         :name "f"
                                         :type :fn  ; This is HOF - should return fn-id, not execute
                                         :required true})
          apply-value-arg (sp/create-entity storage :arg-schema
                                            {:fn-schema-id (:id apply-schema)
                                             :name "value"
                                             :type :int
                                             :required true})
          ;; Create double fn-schema
          double-schema (sp/create-entity storage :fn-schema
                                          {:name "double"
                                           :returned-type :int})
          double-arg (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id double-schema)
                                        :name "x"
                                        :type :int
                                        :required true})
          ;; Create double fn instance
          double-fn (sp/create-entity storage :fn
                                      {:name "my-double"
                                       :fn-schema-id (:id double-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id double-fn)
                               :arg-schema-id (:id double-arg)
                               :value 10})
          ;; Create apply-fn instance
          apply-fn (sp/create-entity storage :fn
                                     {:name "my-apply"
                                      :fn-schema-id (:id apply-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id apply-fn)
                               :arg-schema-id (:id apply-f-arg)
                               :value (:id double-fn)})  ; Reference to double-fn
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id apply-fn)
                               :arg-schema-id (:id apply-value-arg)
                               :value 5})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id apply-fn) {})]
      ;; The f arg should return fn-id (not execute double-fn)
      (is (= (:id double-fn) (:fn-id result)))
      (is (= 5 (:value result)))
      (sp/close storage))))


(deftest optional-args-test
  (testing "optional arguments that are not provided are not in thunks"
    (let [storage (create-test-storage)
          ;; Register a function that uses optional args
          _ (exec/register-base-fn!
              :greet
              (fn [{:keys [the-name suffix]} ctx]
                (let [n (exec/force-value the-name ctx)
                      s (when suffix (exec/force-value suffix ctx))]
                  (if s
                    (str "Hello, " n s)
                    (str "Hello, " n)))))
          ;; Create greet fn-schema
          greet-schema (sp/create-entity storage :fn-schema
                                         {:name "greet"
                                          :returned-type :text})
          name-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id greet-schema)
                                      :name "the-name"
                                      :type :text
                                      :required true})
          ;; Optional arg - just need to define it in schema
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id greet-schema)
                               :name "suffix"
                               :type :text
                               :required false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "greet-world"
                                    :fn-schema-id (:id greet-schema)})
          ;; Only provide required arg, not optional
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id name-arg)
                               :value "World"})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id fn-rec) {})]
      (is (= "Hello, World" result))
      (sp/close storage))))


(deftest provided-args-override-test
  (testing "provided args override stored arg-values"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
          ;; Create arg-values with literals
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 3})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 5})
          ctx (exec/create-context {:storage storage})
          ;; Execute with provided args that override the stored values
          result (exec/execute ctx (:id fn-rec) {(:id arg-a) 100
                                                 (:id arg-b) 200})]
      ;; Should use provided values (100 + 200) not stored values (3 + 5)
      (is (= 300 result))
      (sp/close storage)))

  (testing "provided args partially override stored arg-values"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
          ;; Create arg-values with literals
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 3})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 5})
          ctx (exec/create-context {:storage storage})
          ;; Execute with only :a overridden
          result (exec/execute ctx (:id fn-rec) {(:id arg-a) 100})]
      ;; Should use provided :a (100) and stored :b (5)
      (is (= 105 result))
      (sp/close storage))))


(deftest fn-not-found-in-graph-test
  (testing "throws when fn reference points to non-existent fn during execution"
    (let [storage (create-test-storage)
          ;; Register identity function that forces its arg
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} ctx]
                (exec/force-value x ctx)))
          ;; Create identity fn-schema
          id-schema (sp/create-entity storage :fn-schema
                                      {:name "identity"
                                       :returned-type :int})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id id-schema)
                                    :name "x"
                                    :type :int  ; NOT :fn, so will try to execute the ref
                                    :required true})
          ;; Create identity fn instance
          id-fn (sp/create-entity storage :fn
                                  {:name "my-identity"
                                   :fn-schema-id (:id id-schema)})
          ;; Create arg-value with UUID that doesn't exist as a fn
          non-existent-fn-id (random-uuid)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id id-fn)
                               :arg-schema-id (:id id-arg)
                               :value non-existent-fn-id})
          ctx (exec/create-context {:storage storage})]
      ;; When we execute, it will try to force the arg, which creates a FnRefThunk
      ;; The FnRefThunk will try to execute the non-existent fn, which should throw
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found in execution graph"
            (exec/execute ctx (:id id-fn) {})))
      (sp/close storage))))


;; === Type Validation Tests ===

(deftest provided-arg-type-validation-test
  (testing "throws when :fn type arg is provided with non-UUID value"
    (let [storage (create-test-storage)
          ;; Register HOF that takes a function
          _ (exec/register-base-fn!
              :apply-fn
              (fn [{:keys [f]} ctx]
                (exec/force-value f ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "apply-fn"
                                       :returned-type :int})
          f-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "f"
                                   :type :fn
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-apply"
                                    :fn-schema-id (:id fn-schema)})
          ;; Create dummy arg-value (will be overridden)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id f-arg)
                               :value (random-uuid)})
          ctx (exec/create-context {:storage storage})]
      ;; Provide a string instead of UUID for :fn type
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'f': expected fn"
            (exec/execute ctx (:id fn-rec) {(:id f-arg) "not-a-uuid"})))
      (sp/close storage)))

  (testing "throws when :ref type arg is provided with non-UUID value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-ref
              (fn [{:keys [r]} ctx]
                (exec/force-value r ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-ref"
                                       :returned-type :int})
          r-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "r"
                                   :type :ref
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-ref"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id r-arg)
                               :value (random-uuid)})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'r': expected ref"
            (exec/execute ctx (:id fn-rec) {(:id r-arg) 12345})))
      (sp/close storage)))

  (testing "throws when :int type arg is provided with non-integer value"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 2})
          ctx (exec/create-context {:storage storage})]
      ;; Provide a string instead of int
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'a': expected int"
            (exec/execute ctx (:id fn-rec) {(:id arg-a) "not-an-int"})))
      (sp/close storage)))

  (testing "throws when :bool type arg is provided with non-boolean value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-bool
              (fn [{:keys [flag]} ctx]
                (if (exec/force-value flag ctx) "yes" "no")))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-bool"
                                       :returned-type :text})
          flag-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "flag"
                                      :type :bool
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-bool"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id flag-arg)
                               :value true})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'flag': expected bool"
            (exec/execute ctx (:id fn-rec) {(:id flag-arg) "true"})))
      (sp/close storage)))

  (testing "throws when :text type arg is provided with non-string value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-text
              (fn [{:keys [msg]} ctx]
                (str "Message: " (exec/force-value msg ctx))))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-text"
                                       :returned-type :text})
          msg-arg (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "msg"
                                     :type :text
                                     :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-text"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id msg-arg)
                               :value "hello"})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'msg': expected text"
            (exec/execute ctx (:id fn-rec) {(:id msg-arg) 12345})))
      (sp/close storage)))

  (testing "valid types pass without throwing"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 2})
          ctx (exec/create-context {:storage storage})
          ;; Provide valid int values
          result (exec/execute ctx (:id fn-rec) {(:id arg-a) 10
                                                 (:id arg-b) 20})]
      (is (= 30 result))
      (sp/close storage)))

  (testing "other types (like :numeric) pass without strict validation"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-numeric
              (fn [{:keys [n]} ctx]
                (exec/force-value n ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-numeric"
                                       :returned-type :numeric})
          n-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :numeric
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-numeric"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id n-arg)
                               :value 3.14})
          ctx (exec/create-context {:storage storage})
          ;; Numeric allows various number types - should not throw
          result (exec/execute ctx (:id fn-rec) {(:id n-arg) 2.718})]
      (is (= 2.718 result))
      (sp/close storage))))


;; === Mutual Reference Tests ===

(deftest mutual-reference-graph-test
  (testing "mutual references are correctly resolved in execution graph"
    ;; This tests that functions referencing each other (A -> B, B -> A)
    ;; are correctly resolved without infinite loops in graph resolution
    (let [storage (create-test-storage)
          ;; Register base functions that return their :fn type args
          ;; (demonstrating that LazyFnThunk works for mutual refs)
          _ (exec/register-base-fn!
              :get-partner
              (fn [{:keys [n partner]} ctx]
                (let [n-val (exec/force-value n ctx)
                      ;; partner is a LazyFnThunk - forcing returns fn-id, not result
                      partner-id (exec/force-value partner ctx)]
                  {:n n-val :partner-id partner-id})))

          ;; Create fn-schemas
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "get-partner"
                                       :returned-type :jsonb})
          arg-n (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :int
                                   :required true})
          arg-partner (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id fn-schema)
                                         :name "partner"
                                         :type :fn  ; :fn type means lazy reference
                                         :required true})

          ;; Create two fn instances that reference each other
          fn-a (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id (:id fn-schema)})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id (:id fn-schema)})

          ;; fn-a's n = 1, partner = fn-b
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-a)
                               :arg-schema-id (:id arg-n)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-a)
                               :arg-schema-id (:id arg-partner)
                               :value (:id fn-b)})

          ;; fn-b's n = 2, partner = fn-a
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id arg-n)
                               :value 2})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id arg-partner)
                               :value (:id fn-a)})

          ctx (exec/create-context {:storage storage})]

      (try
        ;; Execute fn-a - should get its own n and fn-b's id as partner
        (let [result-a (exec/execute ctx (:id fn-a) {})]
          (is (= 1 (:n result-a)))
          (is (= (:id fn-b) (:partner-id result-a))))

        ;; Execute fn-b - should get its own n and fn-a's id as partner
        (let [result-b (exec/execute ctx (:id fn-b) {})]
          (is (= 2 (:n result-b)))
          (is (= (:id fn-a) (:partner-id result-b))))
        (finally
          (sp/close storage))))))


(deftest self-reference-test
  (testing "function with self-reference via :fn type arg"
    ;; A function can reference itself. The self-ref is a LazyFnThunk
    ;; so forcing it returns the fn-id, not causing infinite execution
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :with-self
              (fn [{:keys [n self-ref]} ctx]
                (let [n-val (exec/force-value n ctx)
                      ;; self-ref is LazyFnThunk - forcing returns fn-id
                      self-id (exec/force-value self-ref ctx)]
                  {:n n-val :self-id self-id})))

          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "with-self"
                                       :returned-type :jsonb})
          arg-n (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :int
                                   :required true})
          arg-self (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "self-ref"
                                      :type :fn
                                      :required true})

          fn-rec (sp/create-entity storage :fn
                                   {:name "my-self-fn"
                                    :fn-schema-id (:id fn-schema)})

          ;; n = 42
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-n)
                               :value 42})
          ;; Self-reference
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-self)
                               :value (:id fn-rec)})

          ctx (exec/create-context {:storage storage})]

      (try
        (let [result (exec/execute ctx (:id fn-rec) {})]
          (is (= 42 (:n result)))
          (is (= (:id fn-rec) (:self-id result))))
        (finally
          (sp/close storage))))))


;; === Edge Case Tests for Error Paths ===

(defn- create-mock-storage
  "Creates a mock storage that returns the specified execution graph."
  [execution-graph]
  (reify
    sp/ExecutionGraph
    (resolve-execution-graph
      [_ _fn-id]
      execution-graph)))


(deftest fn-schema-not-found-in-graph-test
  (testing "throws when fn-schema is missing from execution graph"
    ;; This tests the error path at lines 203-207 of executor/core.clj
    ;; where fn-schema is not found in the execution graph
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          _ (exec/register-base-fn! :dummy (fn [_ _] nil))
          ;; Create mock storage that returns a graph with fn but missing fn-schema
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "my-dummy"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {}  ; Empty - fn-schema is missing!
                          :arg-schemas {}
                          :resolved-args {}})
          ctx (exec/create-context {:storage mock-storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Function schema not found in execution graph"
            (exec/execute ctx fn-id {}))))))


(deftest arg-schema-missing-type-test
  (testing "throws when arg-schema is missing :type field"
    ;; This tests the error path at lines 106-109 of executor/core.clj
    ;; where arg-schema is nil or missing :type
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          bad-arg-schema-id (random-uuid)
          _ (exec/register-base-fn! :dummy (fn [_ _] nil))
          ;; Create mock storage with arg-schema missing :type
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "my-dummy"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {fn-schema-id {:id fn-schema-id
                                                     :name "dummy"
                                                     :returned-type :int}}
                          ;; arg-schema without :type field
                          :arg-schemas {bad-arg-schema-id {:id bad-arg-schema-id
                                                           :fn-schema-id fn-schema-id
                                                           :name "x"
                                                           :required true}}
                          :resolved-args {fn-id
                                          {bad-arg-schema-id {:owner-fn-id fn-id
                                                              :arg-schema-id bad-arg-schema-id
                                                              :value 42}}}})
          ctx (exec/create-context {:storage mock-storage})]
      ;; When we try to provide an arg that will trigger validation on malformed schema
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid arg-schema: missing type"
            (exec/execute ctx fn-id {bad-arg-schema-id 100}))))))


;; === Additional Type Validation Tests ===

(deftest numeric-type-validation-test
  (testing "throws when :numeric type arg is provided with non-number value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-numeric
              (fn [{:keys [n]} ctx]
                (exec/force-value n ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-numeric"
                                       :returned-type :numeric})
          n-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :numeric
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-numeric"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id n-arg)
                               :value 3.14})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'n': expected numeric"
            (exec/execute ctx (:id fn-rec) {(:id n-arg) "not-a-number"})))
      (sp/close storage)))

  (testing "accepts valid numeric values"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-numeric
              (fn [{:keys [n]} ctx]
                (exec/force-value n ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-numeric"
                                       :returned-type :numeric})
          n-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :numeric
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-numeric"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id n-arg)
                               :value 3.14})
          ctx (exec/create-context {:storage storage})]
      (is (= 2.718M (exec/execute ctx (:id fn-rec) {(:id n-arg) 2.718M})))
      (sp/close storage))))


(deftest jsonb-type-validation-test
  (testing "throws when :jsonb type arg is provided with non-map/vector value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-jsonb
              (fn [{:keys [data]} ctx]
                (exec/force-value data ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-jsonb"
                                       :returned-type :jsonb})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :jsonb
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-jsonb"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value {:a 1}})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'data': expected jsonb"
            (exec/execute ctx (:id fn-rec) {(:id data-arg) "not-jsonb"})))
      (sp/close storage)))

  (testing "accepts valid jsonb values (map)"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-jsonb
              (fn [{:keys [data]} ctx]
                (exec/force-value data ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-jsonb"
                                       :returned-type :jsonb})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :jsonb
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-jsonb"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value {:a 1}})
          ctx (exec/create-context {:storage storage})]
      (is (= {:x 1 :y 2} (exec/execute ctx (:id fn-rec) {(:id data-arg) {:x 1 :y 2}})))
      (sp/close storage)))

  (testing "accepts valid jsonb values (vector)"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-jsonb
              (fn [{:keys [data]} ctx]
                (exec/force-value data ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-jsonb"
                                       :returned-type :jsonb})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :jsonb
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-jsonb"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value [1 2 3]})
          ctx (exec/create-context {:storage storage})]
      (is (= [4 5 6] (exec/execute ctx (:id fn-rec) {(:id data-arg) [4 5 6]})))
      (sp/close storage))))


(deftest bytes-type-validation-test
  (testing "throws when :bytes type arg is provided with non-byte-array value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-bytes
              (fn [{:keys [data]} ctx]
                (exec/force-value data ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-bytes"
                                       :returned-type :bytes})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :bytes
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-bytes"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value (byte-array [1 2 3])})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'data': expected bytes"
            (exec/execute ctx (:id fn-rec) {(:id data-arg) "not-bytes"})))
      (sp/close storage)))

  (testing "accepts valid bytes values"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-bytes
              (fn [{:keys [data]} ctx]
                (vec (exec/force-value data ctx))))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-bytes"
                                       :returned-type :jsonb})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :bytes
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-bytes"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value (byte-array [1 2 3])})
          ctx (exec/create-context {:storage storage})]
      (is (= [4 5 6] (exec/execute ctx (:id fn-rec) {(:id data-arg) (byte-array [4 5 6])})))
      (sp/close storage))))


(deftest timestamptz-type-validation-test
  (testing "throws when :timestamptz type arg is provided with invalid value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} ctx]
                (exec/force-value ts ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-timestamp"
                                       :returned-type :timestamptz})
          ts-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id ts-arg)
                               :value (java.time.Instant/now)})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'ts': expected timestamptz"
            (exec/execute ctx (:id fn-rec) {(:id ts-arg) "not-a-timestamp"})))
      (sp/close storage)))

  (testing "accepts valid Instant value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} ctx]
                (exec/force-value ts ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-timestamp"
                                       :returned-type :timestamptz})
          ts-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id ts-arg)
                               :value (java.time.Instant/now)})
          ctx (exec/create-context {:storage storage})
          test-instant (java.time.Instant/parse "2024-01-01T00:00:00Z")]
      (is (= test-instant (exec/execute ctx (:id fn-rec) {(:id ts-arg) test-instant})))
      (sp/close storage)))

  (testing "accepts valid Date value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} ctx]
                (exec/force-value ts ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-timestamp"
                                       :returned-type :timestamptz})
          ts-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id ts-arg)
                               :value (java.util.Date.)})
          ctx (exec/create-context {:storage storage})
          test-date (java.util.Date. 0)]
      (is (= test-date (exec/execute ctx (:id fn-rec) {(:id ts-arg) test-date})))
      (sp/close storage))))


(deftest enum-type-validation-test
  (testing "throws when :enum type arg is provided with non-keyword value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-enum
              (fn [{:keys [status]} ctx]
                (exec/force-value status ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-enum"
                                       :returned-type :text})
          status-arg (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "status"
                                        :type :enum
                                        :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-enum"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id status-arg)
                               :value :active})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'status': expected enum"
            (exec/execute ctx (:id fn-rec) {(:id status-arg) "not-a-keyword"})))
      (sp/close storage)))

  (testing "accepts valid keyword value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-enum
              (fn [{:keys [status]} ctx]
                (name (exec/force-value status ctx))))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-enum"
                                       :returned-type :text})
          status-arg (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "status"
                                        :type :enum
                                        :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-enum"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id status-arg)
                               :value :active})
          ctx (exec/create-context {:storage storage})]
      (is (= "pending" (exec/execute ctx (:id fn-rec) {(:id status-arg) :pending})))
      (sp/close storage))))


(deftest uuid-type-validation-test
  (testing "throws when :uuid type arg is provided with non-UUID value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-uuid
              (fn [{:keys [id]} ctx]
                (exec/force-value id ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-uuid"
                                       :returned-type :uuid})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "id"
                                    :type :uuid
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-uuid"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id id-arg)
                               :value (random-uuid)})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch for argument 'id': expected uuid"
            (exec/execute ctx (:id fn-rec) {(:id id-arg) "not-a-uuid"})))
      (sp/close storage)))

  (testing "accepts valid UUID value"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-uuid
              (fn [{:keys [id]} ctx]
                (exec/force-value id ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-uuid"
                                       :returned-type :uuid})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "id"
                                    :type :uuid
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-uuid"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id id-arg)
                               :value (random-uuid)})
          ctx (exec/create-context {:storage storage})
          test-uuid #uuid "12345678-1234-1234-1234-123456789abc"]
      (is (= test-uuid (exec/execute ctx (:id fn-rec) {(:id id-arg) test-uuid})))
      (sp/close storage))))


;; === Union Type Tests ===

(deftest union-type-validation-test
  (testing ":union type accepts any value without strict validation"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-union
              (fn [{:keys [data]} ctx]
                (exec/force-value data ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-union"
                                       :returned-type :union})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :union
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-union"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value {:default "value"}})
          ctx (exec/create-context {:storage storage})]
      ;; Union type should accept any value
      (is (= "a string" (exec/execute ctx (:id fn-rec) {(:id data-arg) "a string"})))
      (is (= 12345 (exec/execute ctx (:id fn-rec) {(:id data-arg) 12345})))
      (is (= {:key "value"} (exec/execute ctx (:id fn-rec) {(:id data-arg) {:key "value"}})))
      (is (= [1 2 3] (exec/execute ctx (:id fn-rec) {(:id data-arg) [1 2 3]})))
      (sp/close storage))))


;; Note: Nil guards in build-thunk are for defensive programming.
;; They cannot be triggered through normal execution flow because:
;; - nil arg-value: build-thunks checks for nil before calling build-thunk
;; - nil arg-schema: build-thunks iterates over arg-schemas map, not arg-values
;; The guards protect against future code changes that might bypass these checks.


;; === Large Value Truncation Tests ===

(deftest large-value-truncation-in-error-test
  (testing "large values are truncated in type mismatch errors"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-int
              (fn [{:keys [n]} ctx]
                (exec/force-value n ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-int"
                                       :returned-type :int})
          n-arg (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id fn-schema)
                                   :name "n"
                                   :type :int
                                   :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-int"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id n-arg)
                               :value 42})
          ctx (exec/create-context {:storage storage})
          ;; Create a very large string (> 100 chars) that will be truncated
          large-string (str/join (repeat 200 "x"))]
      (try
        (exec/execute ctx (:id fn-rec) {(:id n-arg) large-string})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)
                truncated-value (:provided-value data)]
            ;; The truncated value should end with "..."
            (is (clojure.string/ends-with? truncated-value "..."))
            ;; And should be around 103 chars (100 + "...")
            (is (<= (count truncated-value) 105)))))
      (sp/close storage))))


;; === Deep Nesting Tests ===

(deftest deep-nesting-near-limit-test
  (testing "executes successfully at exactly max-depth"
    (let [storage (create-test-storage)
          ;; Register identity function that forces its arg
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} ctx]
                (exec/force-value x ctx)))
          ;; Create identity fn-schema
          id-schema (sp/create-entity storage :fn-schema
                                      {:name "identity"
                                       :returned-type :int})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id id-schema)
                                    :name "x"
                                    :type :int
                                    :required true})
          ;; Create a chain of 3 functions
          fn-a (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id (:id id-schema)})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id (:id id-schema)})
          fn-c (sp/create-entity storage :fn {:name "fn-c" :fn-schema-id (:id id-schema)})
          ;; fn-a -> fn-b -> fn-c -> literal
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-a)
                               :arg-schema-id (:id id-arg)
                               :value (:id fn-b)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id id-arg)
                               :value (:id fn-c)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-c)
                               :arg-schema-id (:id id-arg)
                               :value 42})
          ;; max-depth=3 means: fn-a(0) -> fn-b(1) -> fn-c(2) -> literal
          ;; This should work as 2 < 3
          ctx (exec/create-context {:storage storage :max-depth 3})]
      (is (= 42 (exec/execute ctx (:id fn-a) {})))
      (sp/close storage)))

  (testing "fails when depth exceeds max-depth"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} ctx]
                (exec/force-value x ctx)))
          id-schema (sp/create-entity storage :fn-schema
                                      {:name "identity"
                                       :returned-type :int})
          id-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id id-schema)
                                    :name "x"
                                    :type :int
                                    :required true})
          fn-a (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id (:id id-schema)})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id (:id id-schema)})
          fn-c (sp/create-entity storage :fn {:name "fn-c" :fn-schema-id (:id id-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-a)
                               :arg-schema-id (:id id-arg)
                               :value (:id fn-b)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id id-arg)
                               :value (:id fn-c)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-c)
                               :arg-schema-id (:id id-arg)
                               :value 42})
          ;; max-depth=1: fn-a(0) ok, fn-b(1) ok, fn-c(2) fails because depth=2 > max-depth=1
          ctx (exec/create-context {:storage storage :max-depth 1})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Maximum recursion depth exceeded"
            (exec/execute ctx (:id fn-a) {})))
      (sp/close storage))))


;; === Context Validation Tests ===

(deftest context-validation-test
  (testing "throws when max-depth exceeds upper limit"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max-depth exceeds maximum allowed value"
            (exec/create-context {:storage storage :max-depth 100001})))
      (sp/close storage)))

  (testing "accepts max-depth at upper limit"
    (let [storage (create-test-storage)
          ctx (exec/create-context {:storage storage :max-depth 100000})]
      (is (some? ctx))
      (sp/close storage)))

  (testing "throws when max-depth is not a positive integer"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max-depth must be a positive integer"
            (exec/create-context {:storage storage :max-depth 0})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max-depth must be a positive integer"
            (exec/create-context {:storage storage :max-depth -1})))
      (sp/close storage))))


;; === Additional Timestamp Type Tests ===

(deftest local-date-time-type-validation-test
  (testing "accepts valid LocalDateTime value for timestamptz"
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-timestamp
              (fn [{:keys [ts]} ctx]
                (exec/force-value ts ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-timestamp"
                                       :returned-type :timestamptz})
          ts-arg (sp/create-entity storage :arg-schema
                                   {:fn-schema-id (:id fn-schema)
                                    :name "ts"
                                    :type :timestamptz
                                    :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-timestamp"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id ts-arg)
                               :value (java.time.Instant/now)})
          ctx (exec/create-context {:storage storage})
          test-ldt (java.time.LocalDateTime/of 2024 1 1 12 0 0)]
      (is (= test-ldt (exec/execute ctx (:id fn-rec) {(:id ts-arg) test-ldt})))
      (sp/close storage))))


;; === Timeout Validation Tests ===

(deftest timeout-ms-validation-test
  (testing "throws when timeout-ms is below minimum (50ms)"
    (let [storage (create-test-storage)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"timeout-ms must be at least 50ms"
            (exec/create-context {:storage storage :timeout-ms 10})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"timeout-ms must be at least 50ms"
            (exec/create-context {:storage storage :timeout-ms 49})))
      (sp/close storage)))

  (testing "accepts timeout-ms at minimum (50ms)"
    (let [storage (create-test-storage)
          ctx (exec/create-context {:storage storage :timeout-ms 50})]
      (is (some? ctx))
      (sp/close storage))))


;; === Execute Args Validation Tests ===

(deftest execute-args-validation-test
  (testing "throws when args is not nil or a map"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
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
                            #"args must be nil or a map"
            (exec/execute ctx (:id fn-rec) "not a map")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"args must be nil or a map"
            (exec/execute ctx (:id fn-rec) [:a :vector])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"args must be nil or a map"
            (exec/execute ctx (:id fn-rec) 123)))
      (sp/close storage)))

  (testing "accepts nil args"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 1})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 2})
          ctx (exec/create-context {:storage storage})]
      ;; nil should work fine
      (is (= 3 (exec/execute ctx (:id fn-rec) nil)))
      (sp/close storage))))


;; === Unknown Type Validation Tests ===

(deftest unknown-type-validation-test
  (testing "unknown types are accepted without error (forward compatibility)"
    ;; When a new type is added to the schema but not yet to known-types,
    ;; the system should accept any value and log a warning
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-custom
              (fn [{:keys [data]} ctx]
                (exec/force-value data ctx)))
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "use-custom"
                                       :returned-type :text})
          data-arg (sp/create-entity storage :arg-schema
                                     {:fn-schema-id (:id fn-schema)
                                      :name "data"
                                      :type :custom-future-type  ; Unknown type
                                      :required true})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-use-custom"
                                    :fn-schema-id (:id fn-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id data-arg)
                               :value "default"})
          ctx (exec/create-context {:storage storage})]
      ;; Unknown type should accept any value without throwing
      (is (= "test-value" (exec/execute ctx (:id fn-rec) {(:id data-arg) "test-value"})))
      (is (= 12345 (exec/execute ctx (:id fn-rec) {(:id data-arg) 12345})))
      (sp/close storage))))


;; === execute-with-named-args Tests ===

(deftest execute-with-named-args-test
  (testing "executes with named args mapped to schema ids"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
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
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
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
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
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
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
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
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
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
