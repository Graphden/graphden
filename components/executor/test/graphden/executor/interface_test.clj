(ns graphden.executor.interface-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


;; === Helper Functions ===

(defn create-test-storage
  "Creates a storage with a simple fn-schema and registers the base function."
  []
  (gsm/create-storage))


(defn setup-add-function!
  "Sets up an 'add' function that adds two numbers.
   Returns {:fn-schema fn-schema :arg-a arg-schema-a :arg-b arg-schema-b :fn fn-rec}"
  [storage]
  ;; Register the base function (args are delays, use @ to deref)
  (exec/register-base-fn!
    :add
    (fn [{:keys [a b]} _ctx]
      (+ @a @b)))

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
              (fn [{:keys [value]} _ctx]
                @value))
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
              (fn [{:keys [x]} _ctx]
                (Thread/sleep 50) ; Sleep for 50ms
                @x))
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


(deftest lazy-fn-callable-test
  (testing "HOF: fn-type arg returns fn-id, use make-single-arg-callable"
    (let [storage (create-test-storage)
          ;; Register a higher-order function that receives another fn
          _ (exec/register-base-fn!
              :apply-fn
              (fn [{:keys [f value]} ctx]
                ;; f is now a fn-id (UUID), use make-single-arg-callable
                (let [fn-id @f
                      v @value
                      callable (exec/make-single-arg-callable ctx fn-id)]
                  (callable v))))
          ;; Register a simple function
          _ (exec/register-base-fn!
              :double
              (fn [{:keys [x]} _ctx]
                (* 2 @x)))
          ;; Create apply-fn schema
          apply-schema (sp/create-entity storage :fn-schema
                                         {:name "apply-fn"
                                          :returned-type :int})
          apply-f-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id apply-schema)
                                         :name "f"
                                         :type :fn  ; This is HOF - returns a callable
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
          ;; Create double fn instance (with default value 10, but we'll override)
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
      ;; The f arg is a callable, so calling it with {:x 5} should return 10 (5 * 2)
      (is (= 10 result))
      (sp/close storage))))


(deftest optional-args-test
  (testing "optional arguments that are not provided are not in thunks"
    (let [storage (create-test-storage)
          ;; Register a function that uses optional args
          _ (exec/register-base-fn!
              :greet
              (fn [{:keys [the-name suffix]} _ctx]
                (let [n @the-name
                      s (when suffix @suffix)]
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
              (fn [{:keys [x]} _ctx]
                @x))
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
              (fn [{:keys [f]} _ctx]
                @f))
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
              (fn [{:keys [r]} _ctx]
                @r))
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
              (fn [{:keys [flag]} _ctx]
                (if @flag "yes" "no")))
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
              (fn [{:keys [msg]} _ctx]
                (str "Message: " @msg)))
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
              (fn [{:keys [n]} _ctx]
                @n))
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
          ;; Register base functions that use :fn type args
          ;; :fn type args now return fn-ids (UUIDs), not callables
          _ (exec/register-base-fn!
              :get-partner
              (fn [{:keys [n partner]} _ctx]
                (let [n-val @n
                      ;; partner is now a fn-id (UUID)
                      partner-id @partner]
                  {:n n-val :partner-is-uuid (uuid? partner-id)})))

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
                                         :type :fn  ; :fn type means callable reference
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
        ;; Execute fn-a - partner should be a UUID (fn-id)
        (let [result-a (exec/execute ctx (:id fn-a) {})]
          (is (= 1 (:n result-a)))
          (is (true? (:partner-is-uuid result-a))))

        ;; Execute fn-b - partner should be a UUID (fn-id)
        (let [result-b (exec/execute ctx (:id fn-b) {})]
          (is (= 2 (:n result-b)))
          (is (true? (:partner-is-uuid result-b))))
        (finally
          (sp/close storage))))))


(deftest self-reference-test
  (testing "function with self-reference via :fn type arg"
    ;; A function can reference itself. The self-ref is now a fn-id (UUID)
    ;; so forcing it returns a UUID, not causing infinite execution
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :with-self
              (fn [{:keys [n self-ref]} _ctx]
                (let [n-val @n
                      ;; self-ref is a fn-id (UUID)
                      self-id @self-ref]
                  {:n n-val :self-is-uuid (uuid? self-id)})))

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
          (is (true? (:self-is-uuid result))))
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
              (fn [{:keys [n]} _ctx]
                @n))
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
              (fn [{:keys [n]} _ctx]
                @n))
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
              (fn [{:keys [data]} _ctx]
                @data))
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
              (fn [{:keys [data]} _ctx]
                @data))
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
              (fn [{:keys [data]} _ctx]
                @data))
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
              (fn [{:keys [data]} _ctx]
                @data))
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
              (fn [{:keys [data]} _ctx]
                (vec @data)))
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
              (fn [{:keys [ts]} _ctx]
                @ts))
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
              (fn [{:keys [ts]} _ctx]
                @ts))
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
              (fn [{:keys [ts]} _ctx]
                @ts))
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
              (fn [{:keys [status]} _ctx]
                @status))
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
              (fn [{:keys [status]} _ctx]
                (name @status)))
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
              (fn [{:keys [id]} _ctx]
                @id))
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
              (fn [{:keys [id]} _ctx]
                @id))
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
              (fn [{:keys [data]} _ctx]
                @data))
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
              (fn [{:keys [n]} _ctx]
                @n))
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
              (fn [{:keys [x]} _ctx]
                @x))
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
              (fn [{:keys [ts]} _ctx]
                @ts))
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
  (testing "unknown types throw error in strict mode (default)"
    ;; By default, strict-type-validation? is true to catch schema mismatches early
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-custom
              (fn [{:keys [data]} _ctx]
                @data))
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
      ;; Strict mode: unknown type should throw
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown argument type encountered"
            (exec/execute ctx (:id fn-rec) {(:id data-arg) "test-value"})))
      (sp/close storage)))

  (testing "unknown types are accepted in non-strict mode (forward compatibility)"
    ;; When strict-type-validation? is false, unknown types are accepted
    (let [storage (create-test-storage)
          _ (exec/register-base-fn!
              :use-custom
              (fn [{:keys [data]} _ctx]
                @data))
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
          ctx (exec/create-context {:storage storage
                                    :strict-type-validation? false})]
      ;; Non-strict mode: unknown type should accept any value
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


;; === execute-by-name Tests ===

(deftest execute-by-name-test
  (testing "executes function by name"
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
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
    (let [storage (create-test-storage)
          _ (setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      ;; Note: the fn entity is named "my-add", not "add"
      (is (= 15 (exec/execute-by-name ctx "my-add" {:a 5 :b 10})))
      (sp/close storage)))

  (testing "throws when function name not found"
    (let [storage (create-test-storage)
          _ (setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Function 'nonexistent' not found"
            (exec/execute-by-name ctx "nonexistent" nil)))
      (sp/close storage)))

  (testing "throws when fn-name is not a string"
    (let [storage (create-test-storage)
          _ (setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx :my-add nil)))
      (sp/close storage)))

  (testing "throws when fn-name is nil"
    (let [storage (create-test-storage)
          _ (setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx nil nil)))
      (sp/close storage)))

  (testing "throws when fn-name is integer"
    (let [storage (create-test-storage)
          _ (setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx 123 nil)))
      (sp/close storage)))

  (testing "fn-name error includes type information"
    (let [storage (create-test-storage)
          _ (setup-add-function! storage)
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
    (let [storage (create-test-storage)
          _ (setup-add-function! storage)
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
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
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
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup-add-function! storage)
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
    (let [storage (create-test-storage)
          {:keys [fn-rec arg-a]} (setup-add-function! storage)
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
    (let [storage (create-test-storage)
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
                                      {:fn-id (:id inner-fn)})
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
    (let [storage (create-test-storage)
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
                                  {:fn-id (:id id-fn)})
          frv-b (sp/create-entity storage :fn-result-value
                                  {:fn-id (:id id-fn)})
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
    (let [storage (create-test-storage)
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
    (let [storage (create-test-storage)]
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
    (let [storage (create-test-storage)
          ctx (exec/create-context {:storage storage
                                    :path-args {}})]
      (is (some? ctx))
      (sp/close storage)))

  (testing "accepts nil path-args (defaults to empty)"
    (let [storage (create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (some? ctx))
      (sp/close storage))))


;; === fn-result-value Tests ===

(deftest fn-result-value-basic-test
  (testing "fn-result-value is executed and cached"
    (let [storage (create-test-storage)
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
          ;; Create fn-result-value for counter-fn
          counter-result (sp/create-entity storage :fn-result-value
                                           {:fn-id (:id counter-fn)})
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
          ;; Both args reference the SAME fn-result-value
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id add-fn)
                               :arg-schema-id (:id add-arg-a)
                               :value (:id counter-result)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id add-fn)
                               :arg-schema-id (:id add-arg-b)
                               :value (:id counter-result)})
          ctx (exec/create-context {:storage storage})]
      ;; Execute add-fn
      (exec/execute ctx (:id add-fn) nil)
      ;; counter should be called only ONCE, even though it's used twice
      (is (= 1 @call-count) "fn-result-value should be cached and only executed once")
      (sp/close storage)))

  (testing "different fn-result-values for same fn are computed separately"
    (let [storage (create-test-storage)
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
          ;; Create TWO different fn-result-values for the same fn
          result-1 (sp/create-entity storage :fn-result-value
                                     {:fn-id (:id inc-fn)})
          result-2 (sp/create-entity storage :fn-result-value
                                     {:fn-id (:id inc-fn)})
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
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id add-fn)
                               :arg-schema-id (:id add-arg-a)
                               :value (:id result-1)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id add-fn)
                               :arg-schema-id (:id add-arg-b)
                               :value (:id result-2)})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id add-fn) nil)]
      ;; incrementer should be called TWICE (once for each fn-result-value)
      (is (= 2 @call-count) "Different fn-result-values should each execute separately")
      ;; Result should be 1 + 2 = 3
      (is (= 3 result))
      (sp/close storage)))

  (testing "fn-result-value vs direct fn reference"
    (let [storage (create-test-storage)
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
          ;; Create fn-result-value
          counter-result (sp/create-entity storage :fn-result-value
                                           {:fn-id (:id counter-fn)})
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
          ;; a -> fn-result-value (cached)
          ;; b -> direct fn reference (not cached)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id add-fn)
                               :arg-schema-id (:id add-arg-a)
                               :value (:id counter-result)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id add-fn)
                               :arg-schema-id (:id add-arg-b)
                               :value (:id counter-fn)})
          ctx (exec/create-context {:storage storage})]
      (exec/execute ctx (:id add-fn) nil)
      ;; counter should be called TWICE:
      ;; - once for fn-result-value (cached)
      ;; - once for direct fn reference (not cached)
      (is (= 2 @call-count) "Direct fn ref and fn-result-value should execute separately")
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
    (let [storage (create-test-storage)
          test-fn (fn [_ _] 123)
          _ (exec/register-base-fn! :ctx-test-fn test-fn)
          ctx (exec/create-context {:storage storage})]
      (is (= test-fn (exec/get-base-fn-from-context ctx :ctx-test-fn)))
      (sp/close storage)))

  (testing "returns nil for non-existent function"
    (let [storage (create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (nil? (exec/get-base-fn-from-context ctx :does-not-exist-xyz)))
      (sp/close storage))))


;; === execute-by-name Error Path Tests ===

(deftest execute-by-name-error-test
  (testing "executes function by string name"
    (let [storage (create-test-storage)
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
    (let [storage (create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx :keyword-name nil)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"fn-name must be a string"
            (exec/execute-by-name ctx 123 nil)))
      (sp/close storage)))

  (testing "throws for non-existent function name"
    (let [storage (create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"not found"
            (exec/execute-by-name ctx "non-existent-function" nil)))
      (sp/close storage))))


;; === execute-with-named-args Error Path Tests ===

(deftest execute-with-named-args-error-test
  (testing "executes with named args"
    (let [storage (create-test-storage)
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
    (let [storage (create-test-storage)
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
    (let [storage (create-test-storage)
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
    (let [storage (create-test-storage)
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
    (let [storage (create-test-storage)
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
