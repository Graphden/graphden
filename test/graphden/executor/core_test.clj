(ns graphden.executor.core-test
  "Core tests for executor.

   Covers:
   - Base function registry
   - Basic execution
   - Literal arg values
   - Partial application (currying)
   - Function references
   - Error handling
   - Mutual references

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
    (let [storage (setup/create-test-storage)
          my-fn (fn [_ _] 42)
          ctx (exec/create-context {:storage storage
                                    :base-fns {:my-custom-fn my-fn}})]
      (is (= my-fn (exec/get-base-fn-from-context ctx :my-custom-fn)))
      (is (nil? (exec/get-base-fn-from-context ctx :nonexistent)))
      (sp/close storage))))


(deftest create-context-test
  (testing "creates context with required storage"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (is (some? ctx))
      (sp/close storage)))

  (testing "throws when storage is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage is required"
          (exec/create-context {})))))


(deftest execute-simple-function-test
  (testing "executes function with literal arg-values"
    (let [storage (setup/create-test-storage)
          {:keys [arg-a arg-b composed-fn]} (setup/setup-add-function! storage)
          ;; Create args for composed fn with values (source-id links to parent's arg)
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 3})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b) :value 5})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id composed-fn) {})]
      (is (= 8 result))
      (sp/close storage))))


(deftest execute-fn-reference-test
  (testing "executes referenced function and uses result"
    (let [storage (setup/create-test-storage)
          ;; Register a constant function
          _ (exec/register-base-fn!
              :const
              (fn [{:keys [value]} _ctx]
                @value))
          ;; Create const base fn
          const-base (setup/create-base-fn! storage "const" :int)
          const-arg (setup/create-arg! storage (:id const-base)
                                       {:name "value" :type :int :required true :is-fn false})
          ;; Create two const composed functions with different values
          const-3 (setup/create-composed-fn! storage "const-3" (:id const-base))
          _ (setup/create-arg! storage (:id const-3)
                               {:name "value" :type :int :required true :is-fn false
                                :source-id (:id const-arg) :value 3})
          const-5 (setup/create-composed-fn! storage "const-5" (:id const-base))
          _ (setup/create-arg! storage (:id const-5)
                               {:name "value" :type :int :required true :is-fn false
                                :source-id (:id const-arg) :value 5})
          ;; Create add function
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Create composed fn that references const-3 and const-5
          add-fn (setup/create-composed-fn! storage "add-consts" (:id base-fn))
          ;; Set arg refs to reference const functions via ref-id
          _ (setup/create-arg! storage (:id add-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :ref-id (:id const-3)})
          _ (setup/create-arg! storage (:id add-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b) :ref-id (:id const-5)})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id add-fn) {})]
      (is (= 8 result))
      (sp/close storage))))


(deftest max-depth-protection-test
  (testing "throws when max depth is exceeded"
    (let [storage (setup/create-test-storage)
          ;; Register identity function
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} _ctx]
                @x))
          ;; Create identity base fn
          id-base (setup/create-base-fn! storage "identity" :int)
          id-arg (setup/create-arg! storage (:id id-base)
                                    {:name "x" :type :int :required true :is-fn false})
          ;; Create chain of functions that reference each other
          fn-a (setup/create-composed-fn! storage "fn-a" (:id id-base))
          fn-b (setup/create-composed-fn! storage "fn-b" (:id id-base))
          fn-c (setup/create-composed-fn! storage "fn-c" (:id id-base))
          ;; fn-a -> fn-b -> fn-c -> literal (via ref-id to trigger execution)
          _ (setup/create-arg! storage (:id fn-a)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :ref-id (:id fn-b)})
          _ (setup/create-arg! storage (:id fn-b)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :ref-id (:id fn-c)})
          _ (setup/create-arg! storage (:id fn-c)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :value 42})
          ;; Execute with max-depth=1 (should fail at fn-c which runs at depth=2)
          ctx (exec/create-context {:storage storage :max-depth 1})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Maximum recursion depth exceeded"
            (exec/execute ctx (:id fn-a) {})))
      (sp/close storage))))


(deftest missing-base-fn-test
  (testing "throws when base function is not registered"
    (let [storage (setup/create-test-storage)
          ;; Create base fn without registering in executor
          base-fn (setup/create-base-fn! storage "unknown-fn" :int)
          composed-fn (setup/create-composed-fn! storage "my-unknown" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Base function 'unknown-fn' not found"
            (exec/execute ctx (:id composed-fn) {})))
      (sp/close storage))))


(deftest missing-required-arg-test
  (testing "throws when required argument is not provided"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Create composed fn with both args: :a bound with value, :b required but no value
          composed-fn (setup/create-composed-fn! storage "partial-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 3})
          ;; Create arg-b on composed-fn: required but no value (will trigger error)
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b)})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required argument 'b' not provided"
            (exec/execute ctx (:id composed-fn) {})))
      (sp/close storage))))


(deftest fn-not-found-test
  (testing "throws when function id doesn't exist"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})
          fake-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
            (exec/execute ctx fake-id {})))
      (sp/close storage))))


(deftest timeout-protection-test
  (testing "throws when execution timeout is exceeded"
    (let [storage (setup/create-test-storage)
          ;; Register a slow function that sleeps and calls another fn
          _ (exec/register-base-fn!
              :slow-fn
              (fn [{:keys [x]} _ctx]
                (Thread/sleep 50) ; Sleep for 50ms
                @x))
          ;; Create slow base fn
          slow-base (setup/create-base-fn! storage "slow-fn" :int)
          slow-arg (setup/create-arg! storage (:id slow-base)
                                      {:name "x" :type :int :required true :is-fn false})
          ;; Create a chain: fn-a -> fn-b -> fn-c -> literal
          ;; Each step sleeps 50ms, so by fn-c the timeout should be exceeded
          fn-a (setup/create-composed-fn! storage "slow-a" (:id slow-base))
          fn-b (setup/create-composed-fn! storage "slow-b" (:id slow-base))
          fn-c (setup/create-composed-fn! storage "slow-c" (:id slow-base))
          _ (setup/create-arg! storage (:id fn-a)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id slow-arg) :ref-id (:id fn-b)})
          _ (setup/create-arg! storage (:id fn-b)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id slow-arg) :ref-id (:id fn-c)})
          _ (setup/create-arg! storage (:id fn-c)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id slow-arg) :value 42})
          ;; Create context with 80ms timeout (fn-a sleeps 50ms, fn-b starts, sleeps 50ms = 100ms > 80ms)
          ctx (exec/create-context {:storage storage :timeout-ms 80})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Execution timeout exceeded"
            (exec/execute ctx (:id fn-a) {})))
      (sp/close storage))))


(deftest lazy-fn-callable-test
  (testing "HOF: fn-type arg returns fn-id, use make-single-arg-callable"
    (let [storage (setup/create-test-storage)
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
          ;; Create apply-fn base
          apply-base (setup/create-base-fn! storage "apply-fn" :int)
          apply-f-arg (setup/create-arg! storage (:id apply-base)
                                         {:name "f" :type :fn :required true :is-fn true})
          apply-value-arg (setup/create-arg! storage (:id apply-base)
                                             {:name "value" :type :int :required true :is-fn false})
          ;; Create double base fn
          double-base (setup/create-base-fn! storage "double" :int)
          _double-arg (setup/create-arg! storage (:id double-base)
                                         {:name "x" :type :int :required true :is-fn false})
          ;; Create double fn instance WITHOUT x value - it's a FREE arg
          ;; HOF will provide the value at call time
          double-fn (setup/create-composed-fn! storage "my-double" (:id double-base))
          ;; NOTE: No arg for double-fn.x - it's a free arg for HOF to provide
          ;; Create apply-fn instance
          apply-fn (setup/create-composed-fn! storage "my-apply" (:id apply-base))
          ;; is-fn=true passes fn-id via ref-id
          _ (setup/create-arg! storage (:id apply-fn)
                               {:name "f" :type :fn :required true :is-fn true
                                :source-id (:id apply-f-arg) :ref-id (:id double-fn)})
          _ (setup/create-arg! storage (:id apply-fn)
                               {:name "value" :type :int :required true :is-fn false
                                :source-id (:id apply-value-arg) :value 5})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id apply-fn) {})]
      ;; The f arg is a callable with free arg x, so calling it with 5 should return 10 (5 * 2)
      (is (= 10 result))
      (sp/close storage)))

  (testing "HOF cannot override DB-defined arg in called fn"
    (let [storage (setup/create-test-storage)
          ;; Register same functions as above
          _ (exec/register-base-fn!
              :apply-fn-override
              (fn [{:keys [f value]} ctx]
                (let [fn-id @f
                      v @value
                      callable (exec/make-single-arg-callable ctx fn-id)]
                  (callable v))))
          _ (exec/register-base-fn!
              :double-fixed
              (fn [{:keys [x]} _ctx]
                (* 2 @x)))
          ;; Create bases
          apply-base (setup/create-base-fn! storage "apply-fn-override" :int)
          apply-f-arg (setup/create-arg! storage (:id apply-base)
                                         {:name "f" :type :fn :required true :is-fn true})
          apply-value-arg (setup/create-arg! storage (:id apply-base)
                                             {:name "value" :type :int :required true :is-fn false})
          double-base (setup/create-base-fn! storage "double-fixed" :int)
          double-arg (setup/create-arg! storage (:id double-base)
                                        {:name "x" :type :int :required true :is-fn false})
          ;; Create double fn WITH x=10 in DB - it's FIXED
          double-fn (setup/create-composed-fn! storage "my-double-fixed" (:id double-base))
          _ (setup/create-arg! storage (:id double-fn)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id double-arg) :value 10})
          ;; Create apply-fn instance with value=5
          apply-fn (setup/create-composed-fn! storage "my-apply-override" (:id apply-base))
          _ (setup/create-arg! storage (:id apply-fn)
                               {:name "f" :type :fn :required true :is-fn true
                                :source-id (:id apply-f-arg) :ref-id (:id double-fn)})
          _ (setup/create-arg! storage (:id apply-fn)
                               {:name "value" :type :int :required true :is-fn false
                                :source-id (:id apply-value-arg) :value 5})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id apply-fn) {})]
      ;; double-fn has x=10 in DB, so HOF's 5 is IGNORED, result is 10*2=20
      (is (= 20 result))
      (sp/close storage))))


(deftest optional-args-test
  (testing "optional arguments that are not provided are not in thunks"
    (let [storage (setup/create-test-storage)
          ;; Register a function that uses optional args
          _ (exec/register-base-fn!
              :greet
              (fn [{:keys [the-name suffix]} _ctx]
                (let [n @the-name
                      s (when suffix @suffix)]
                  (if s
                    (str "Hello, " n s)
                    (str "Hello, " n)))))
          ;; Create greet base fn
          greet-base (setup/create-base-fn! storage "greet" :text)
          name-arg (setup/create-arg! storage (:id greet-base)
                                      {:name "the-name" :type :text :required true :is-fn false})
          ;; Optional arg - just need to define it in schema
          _ (setup/create-arg! storage (:id greet-base)
                               {:name "suffix" :type :text :required false :is-fn false})
          ;; Create composed fn with only required arg
          greet-fn (setup/create-composed-fn! storage "greet-world" (:id greet-base))
          _ (setup/create-arg! storage (:id greet-fn)
                               {:name "the-name" :type :text :required true :is-fn false
                                :source-id (:id name-arg) :value "World"})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id greet-fn) {})]
      (is (= "Hello, World" result))
      (sp/close storage))))


(deftest provided-args-no-override-test
  (testing "provided args cannot override stored arg-values (DB takes precedence)"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Create composed fn with both args provided
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          a-arg (setup/create-arg! storage (:id composed-fn)
                                   {:name "a" :type :int :required true :is-fn false
                                    :source-id (:id arg-a) :value 3})
          b-arg (setup/create-arg! storage (:id composed-fn)
                                   {:name "b" :type :int :required true :is-fn false
                                    :source-id (:id arg-b) :value 5})
          ctx (exec/create-context {:storage storage})
          ;; Execute with provided args - they should be IGNORED (DB values used)
          result (exec/execute ctx (:id composed-fn) {(:id a-arg) 100
                                                      (:id b-arg) 200})]
      ;; Should use DB values (3 + 5) NOT provided values
      (is (= 8 result))
      (sp/close storage)))

  (testing "provided args work for free args (not in DB)"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Create composed fn with both args: :a bound, :b free (no value)
          composed-fn (setup/create-composed-fn! storage "partial-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 3})
          ;; Create free arg :b on composed-fn (required but no value)
          b-composed-arg (setup/create-arg! storage (:id composed-fn)
                                            {:name "b" :type :int :required true :is-fn false
                                             :source-id (:id arg-b)})
          ctx (exec/create-context {:storage storage})
          ;; Execute with provided value for free arg-b (use composed-fn's arg-b id)
          result (exec/execute ctx (:id composed-fn) {(:id b-composed-arg) 7})]
      ;; Should use DB :a (3) and provided :b (7)
      (is (= 10 result))
      (sp/close storage)))

  (testing "provided args ignored for DB args, used for free args"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Create composed fn with both args: :a bound with value, :b free (no value)
          composed-fn (setup/create-composed-fn! storage "partial-add" (:id base-fn))
          a-arg (setup/create-arg! storage (:id composed-fn)
                                   {:name "a" :type :int :required true :is-fn false
                                    :source-id (:id arg-a) :value 3})
          ;; Create free arg :b on composed-fn (required but no value)
          b-composed-arg (setup/create-arg! storage (:id composed-fn)
                                            {:name "b" :type :int :required true :is-fn false
                                             :source-id (:id arg-b)})
          ctx (exec/create-context {:storage storage})
          ;; Try to override arg-a (should be ignored) and provide arg-b (should work)
          result (exec/execute ctx (:id composed-fn) {(:id a-arg) 100
                                                      (:id b-composed-arg) 7})]
      ;; Should use DB :a (3) and provided :b (7)
      (is (= 10 result))
      (sp/close storage))))


(deftest fn-not-found-in-graph-test
  (testing "throws when ref-id references non-existent fn during execution"
    (let [storage (setup/create-test-storage)
          ;; Register identity function that forces its arg
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} _ctx]
                @x))
          ;; Create identity base fn
          id-base (setup/create-base-fn! storage "identity" :int)
          id-arg (setup/create-arg! storage (:id id-base)
                                    {:name "x" :type :int :required true :is-fn false})
          ;; Create identity fn instance
          id-fn (setup/create-composed-fn! storage "my-identity" (:id id-base))
          ;; Create arg pointing to non-existent fn via ref-id
          non-existent-fn-id (random-uuid)
          _ (setup/create-arg! storage (:id id-fn)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :ref-id non-existent-fn-id})
          ctx (exec/create-context {:storage storage})]
      ;; When we execute, it will try to resolve the ref-id which points
      ;; to a non-existent fn - should throw
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found in execution graph"
            (exec/execute ctx (:id id-fn) {})))
      (sp/close storage))))


;; === Context validation edge case tests ===

(deftest create-context-validation-test
  (testing "throws when storage doesn't implement ExecutionGraph protocol"
    (let [invalid-storage {:fake "storage"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"storage must implement ExecutionGraph protocol"
            (exec/create-context {:storage invalid-storage})))))

  (testing "throws with multiple validation errors combined"
    (let [invalid-storage {:fake "storage"}]
      (try
        (exec/create-context {:storage invalid-storage
                              :max-depth -1
                              :timeout-ms 10})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [errors (:validation-errors (ex-data e))]
            (is (> (count errors) 1) "should have multiple errors")))))))


(deftest clear-result-cache-test
  (testing "clear-result-cache! clears the cache and returns count"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (try
        ;; Manually populate the cache
        (reset! (:result-cache ctx) {:a 1 :b 2 :c 3})
        (is (= 3 (count @(:result-cache ctx))))
        ;; Clear and check count returned
        (let [cleared-count (exec/clear-result-cache! ctx)]
          (is (= 3 cleared-count))
          (is (empty? @(:result-cache ctx))))
        (finally
          (sp/close storage)))))

  (testing "clear-result-cache! on empty cache returns 0"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (try
        (is (zero? (exec/clear-result-cache! ctx)))
        (finally
          (sp/close storage))))))


;; === execute-by-name Tests ===

(deftest execute-by-name-test
  (testing "executes function by string name"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Create composed fn with both args
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 10})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b) :value 20})
          ctx (exec/create-context {:storage storage})
          result (exec/execute-by-name ctx (:name composed-fn) nil)]
      (is (= 30 result))
      (sp/close storage)))

  (testing "throws when fn-name is not a string"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a string"
              (exec/execute-by-name ctx :keyword-name nil)))
        (finally
          (sp/close storage)))))

  (testing "throws when function with name doesn't exist"
    (let [storage (setup/create-test-storage)
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function .* not found"
              (exec/execute-by-name ctx "nonexistent-fn" nil)))
        (finally
          (sp/close storage))))))


;; === execute args validation Tests ===

(deftest execute-args-validation-test
  (testing "throws when args is not nil or map"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be nil or a map"
              (exec/execute ctx (:id composed-fn) "not-a-map")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be nil or a map"
              (exec/execute ctx (:id composed-fn) [1 2 3])))
        (finally
          (sp/close storage)))))

  (testing "execute-with-named-args throws when named-args is not nil or map"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"named-args must be nil or a map"
              (exec/execute-with-named-args ctx (:id composed-fn) "not-a-map")))
        (finally
          (sp/close storage))))))


;; === HOF Function Tests ===

(deftest hof-invalid-function-test
  (testing "throws when HOF function has no required args"
    (let [storage (setup/create-test-storage)
          ;; Register a HOF function
          _ (exec/register-base-fn!
              :hof-caller
              (fn [{:keys [f]} ctx]
                (let [fn-id @f
                      callable (exec/make-single-arg-callable ctx fn-id)]
                  (callable 42))))
          ;; Create hof-caller base
          hof-base (setup/create-base-fn! storage "hof-caller" :int)
          hof-arg (setup/create-arg! storage (:id hof-base)
                                     {:name "f" :type :fn :required true :is-fn true})
          ;; Create a function with NO required args (all optional)
          _ (exec/register-base-fn!
              :no-args-fn
              (fn [_ _] 0))
          no-args-base (setup/create-base-fn! storage "no-args-fn" :int)
          ;; Only optional arg
          _ (setup/create-arg! storage (:id no-args-base)
                               {:name "optional" :type :int :required false :is-fn false})
          no-args-fn (setup/create-composed-fn! storage "no-args" (:id no-args-base))
          ;; Create hof-caller instance
          hof-fn (setup/create-composed-fn! storage "my-hof" (:id hof-base))
          _ (setup/create-arg! storage (:id hof-fn)
                               {:name "f" :type :fn :required true :is-fn true
                                :source-id (:id hof-arg) :ref-id (:id no-args-fn)})
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"HOF function requires exactly 1 required argument"
              (exec/execute ctx (:id hof-fn) {})))
        (finally
          (sp/close storage)))))

  (testing "throws when HOF function has multiple required args"
    (let [storage (setup/create-test-storage)
          ;; Register a HOF function
          _ (exec/register-base-fn!
              :hof-caller
              (fn [{:keys [f]} ctx]
                (let [fn-id @f
                      callable (exec/make-single-arg-callable ctx fn-id)]
                  (callable 42))))
          ;; Create hof-caller base
          hof-base (setup/create-base-fn! storage "hof-caller" :int)
          hof-arg (setup/create-arg! storage (:id hof-base)
                                     {:name "f" :type :fn :required true :is-fn true})
          ;; Create add function (has 2 required args)
          {:keys [base-fn]} (setup/setup-add-function! storage)
          add-fn (setup/create-composed-fn! storage "add-for-hof" (:id base-fn))
          ;; Create hof-caller instance
          hof-fn (setup/create-composed-fn! storage "my-hof" (:id hof-base))
          _ (setup/create-arg! storage (:id hof-fn)
                               {:name "f" :type :fn :required true :is-fn true
                                :source-id (:id hof-arg) :ref-id (:id add-fn)})
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"HOF function requires exactly 1 required argument"
              (exec/execute ctx (:id hof-fn) {})))
        (finally
          (sp/close storage))))))


;; === Unknown arg name test ===

(deftest execute-with-named-args-unknown-arg-test
  (testing "throws when unknown arg name is provided"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown argument name"
              (exec/execute-with-named-args ctx (:id composed-fn) {:unknown-arg 42})))
        (finally
          (sp/close storage))))))


;; === execute-with-named-args with nil or empty args test ===

(deftest execute-with-named-args-empty-args-test
  (testing "passes nil named-args to execute"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 5})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b) :value 7})
          ctx (exec/create-context {:storage storage})
          result (exec/execute-with-named-args ctx (:id composed-fn) nil)]
      (is (= 12 result))
      (sp/close storage)))

  (testing "passes empty map named-args to execute"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 3})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b) :value 4})
          ctx (exec/create-context {:storage storage})
          result (exec/execute-with-named-args ctx (:id composed-fn) {})]
      (is (= 7 result))
      (sp/close storage))))


;; === fn-usage cache eviction test ===

(deftest cache-eviction-test
  (testing "evicts oldest entries when cache limit reached"
    (let [storage (setup/create-test-storage)
          ;; Register identity function that takes a ref
          _ (exec/register-base-fn!
              :ref-fn
              (fn [{:keys [x]} _ctx]
                @x))
          ;; Register const function
          _ (exec/register-base-fn!
              :const
              (fn [{:keys [value]} _ctx]
                @value))
          ;; Create ref-fn base
          ref-base (setup/create-base-fn! storage "ref-fn" :int)
          ref-arg (setup/create-arg! storage (:id ref-base)
                                     {:name "x" :type :int :required true :is-fn false})
          ;; Create const base fn
          const-base (setup/create-base-fn! storage "const" :int)
          const-arg (setup/create-arg! storage (:id const-base)
                                       {:name "value" :type :int :required true :is-fn false})
          ;; Create 5 const functions (these will be ref'd)
          const-fns (doall
                      (for [i (range 5)]
                        (let [composed-fn (setup/create-composed-fn! storage (str "const-" i) (:id const-base))]
                          (setup/create-arg! storage (:id composed-fn)
                                             {:name "value" :type :int :required true :is-fn false
                                              :source-id (:id const-arg) :value i})
                          composed-fn)))
          ;; Create ref functions that reference const functions via ref-id
          ;; This triggers execute-ref-fn which uses the cache
          ref-fns (doall
                    (for [[i const-fn] (map-indexed vector const-fns)]
                      (let [ref-fn (setup/create-composed-fn! storage (str "ref-" i) (:id ref-base))]
                        (setup/create-arg! storage (:id ref-fn)
                                           {:name "x" :type :int :required true :is-fn false
                                            :source-id (:id ref-arg) :ref-id (:id const-fn)})
                        ref-fn)))
          ;; Create context with very small cache (2 entries) to trigger eviction
          ctx (exec/create-context {:storage storage
                                    :cache-max-size 2
                                    :cache-warning-threshold 1})]
      (try
        ;; Execute all ref functions - each triggers execute-ref-fn which caches
        (doseq [fn-rec ref-fns]
          (exec/execute ctx (:id fn-rec) nil))
        ;; Cache should not exceed max size due to eviction
        (is (<= (count @(:result-cache ctx)) 2))
        (finally
          (sp/close storage))))))


;; === execute-by-name with named-args test ===

(deftest execute-by-name-with-named-args-test
  (testing "executes function by name with named-args for free args"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Create composed fn with both args: :a bound, :b free
          composed-fn (setup/create-composed-fn! storage "partial-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 10})
          ;; Create free arg :b (no value, will be provided via named-args)
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b)})
          ctx (exec/create-context {:storage storage})
          ;; Provide free arg-b via named args
          result (exec/execute-by-name ctx (:name composed-fn) {:b 20})]
      ;; Should use DB :a (10) and provided :b (20)
      (is (= 30 result))
      (sp/close storage)))

  (testing "named-args cannot override DB-defined args"
    (let [storage (setup/create-test-storage)
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Both args defined in DB
          composed-fn (setup/create-composed-fn! storage "my-add" (:id base-fn))
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 10})
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b) :value 20})
          ctx (exec/create-context {:storage storage})
          ;; Try to override with named args - should be ignored
          result (exec/execute-by-name ctx (:name composed-fn) {:a 100 :b 200})]
      ;; Should use DB values (10 + 20) NOT provided values
      (is (= 30 result))
      (sp/close storage))))


;; === resolve-base-fn error tests ===

(deftest resolve-base-fn-missing-parent-test
  (testing "throws when parent-id points to non-existent fn in graph"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Register base fn
        (exec/register-base-fn!
          :identity
          (fn [{:keys [x]} _ctx] @x))

        ;; Create base fn
        (let [base-fn (setup/create-base-fn! storage "identity" :int)
              _ (setup/create-arg! storage (:id base-fn)
                                   {:name "x" :type :int :required true :is-fn false})
              ;; Create composed fn with invalid parent-id (points to non-existent UUID)
              ;; We'll manually insert this to bypass normal validation
              invalid-parent-id (random-uuid)
              composed-fn {:id (random-uuid)
                           :name "bad-composed"
                           :parent-id invalid-parent-id  ; points to nothing!
                           :return-type :int}
              _ (sp/create-entity storage :fn composed-fn)
              ctx (exec/create-context {:storage storage})]
          ;; When we execute, resolve-base-fn will fail when following parent chain
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Function not found in execution graph"
                (exec/execute ctx (:id composed-fn) {}))))
        (finally
          (sp/close storage))))))


(deftest args-inheritance-depth-exceeded-test
  (testing "throws when parent chain exceeds max depth during arg resolution"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Register base fn
        (exec/register-base-fn!
          :identity
          (fn [{:keys [x]} _ctx] @x))

        ;; Create base fn
        (let [base-fn (setup/create-base-fn! storage "identity" :int)
              base-arg (setup/create-arg! storage (:id base-fn)
                                          {:name "x" :type :int :required true :is-fn false})
              ;; Create deep chain: c4 -> c3 -> c2 -> c1 -> base
              c1 (setup/create-composed-fn! storage "c1" (:id base-fn))
              _ (setup/create-arg! storage (:id c1)
                                   {:name "x" :type :int :required true
                                    :source-id (:id base-arg) :value 42})
              c2 (setup/create-composed-fn! storage "c2" (:id c1))
              c3 (setup/create-composed-fn! storage "c3" (:id c2))
              c4 (setup/create-composed-fn! storage "c4" (:id c3))]

          ;; Execute with very low max-graph-iterations to trigger args depth error
          (binding [sp/*max-graph-iterations* 2]
            (let [ctx (exec/create-context {:storage storage})]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                    #"Parent chain exceeds maximum depth"
                    (exec/execute ctx (:id c4) {}))))))
        (finally
          (sp/close storage))))))


;; === Parent chain depth tests ===

(deftest parent-chain-depth-test
  (testing "throws when parent chain exceeds max depth"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Register simple base fn
        (exec/register-base-fn!
          :identity
          (fn [{:keys [x]} _ctx] @x))

        ;; Create base fn
        (let [base-fn (setup/create-base-fn! storage "identity" :int)
              _ (setup/create-arg! storage (:id base-fn)
                                   {:name "x" :type :int :required true :is-fn false})
              ;; Create chain of composed fns: c1 -> c2 -> c3 -> c4 -> c5 -> base
              c5 (setup/create-composed-fn! storage "c5" (:id base-fn))
              c4 (setup/create-composed-fn! storage "c4" (:id c5))
              c3 (setup/create-composed-fn! storage "c3" (:id c4))
              c2 (setup/create-composed-fn! storage "c2" (:id c3))
              c1 (setup/create-composed-fn! storage "c1" (:id c2))
              ;; Add args down the chain
              arg-c5 (setup/create-arg! storage (:id c5) {:name "x" :type :int :required true :value 42})
              _ (setup/create-arg! storage (:id c4) {:name "x" :type :int :required true :source-id (:id arg-c5)})
              _ (setup/create-arg! storage (:id c3) {:name "x" :type :int :required true})
              _ (setup/create-arg! storage (:id c2) {:name "x" :type :int :required true})
              _ (setup/create-arg! storage (:id c1) {:name "x" :type :int :required true})]

          ;; Execute with very low max-graph-iterations to trigger parent chain depth error
          (binding [sp/*max-graph-iterations* 2]
            (let [ctx (exec/create-context {:storage storage})]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                    #"Parent chain exceeds maximum depth"
                    (exec/execute ctx (:id c1) {}))))))
        (finally
          (sp/close storage))))))


;; === Depth warning test ===

(deftest depth-warning-threshold-test
  (testing "approaching max depth triggers warning log"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Register identity function
        (exec/register-base-fn!
          :identity
          (fn [{:keys [x]} _ctx] @x))

        ;; Create base fn
        (let [base-fn (setup/create-base-fn! storage "identity" :int)
              base-arg (setup/create-arg! storage (:id base-fn)
                                          {:name "x" :type :int :required true :is-fn false})
              ;; Create fn-a -> fn-b -> fn-c chain via ref-id
              fn-c (setup/create-composed-fn! storage "fn-c" (:id base-fn))
              _ (setup/create-arg! storage (:id fn-c)
                                   {:name "x" :type :int :required true :is-fn false
                                    :source-id (:id base-arg) :value 42})
              fn-b (setup/create-composed-fn! storage "fn-b" (:id base-fn))
              _ (setup/create-arg! storage (:id fn-b)
                                   {:name "x" :type :int :required true :is-fn false
                                    :source-id (:id base-arg) :ref-id (:id fn-c)})
              fn-a (setup/create-composed-fn! storage "fn-a" (:id base-fn))
              _ (setup/create-arg! storage (:id fn-a)
                                   {:name "x" :type :int :required true :is-fn false
                                    :source-id (:id base-arg) :ref-id (:id fn-b)})
              ;; max-depth=4, warning at 80% = 3.2 -> depth 3 triggers warning
              ctx (exec/create-context {:storage storage :max-depth 4})]
          ;; Should complete successfully (depth 3 < max 4) but warn
          (is (= 42 (exec/execute ctx (:id fn-a) {}))))
        (finally
          (sp/close storage))))))


;; === Timeout warning threshold test ===

(deftest timeout-warning-threshold-test
  (testing "approaching timeout triggers warning log"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Register a function that sleeps
        (exec/register-base-fn!
          :sleepy
          (fn [{:keys [ms x]} _ctx]
            (Thread/sleep @ms)
            @x))

        ;; Create base fn
        (let [base-fn (setup/create-base-fn! storage "sleepy" :int)
              ms-arg (setup/create-arg! storage (:id base-fn)
                                        {:name "ms" :type :int :required true :is-fn false})
              x-arg (setup/create-arg! storage (:id base-fn)
                                       {:name "x" :type :int :required true :is-fn false})
              ;; Create sleepy fn that sleeps 75ms
              sleepy-fn (setup/create-composed-fn! storage "my-sleepy" (:id base-fn))
              _ (setup/create-arg! storage (:id sleepy-fn)
                                   {:name "ms" :type :int :required true :is-fn false
                                    :source-id (:id ms-arg) :value 75})
              _ (setup/create-arg! storage (:id sleepy-fn)
                                   {:name "x" :type :int :required true :is-fn false
                                    :source-id (:id x-arg) :value 42})
              ;; timeout=100ms, warning at 80% = 80ms, so after 75ms we're approaching
              ctx (exec/create-context {:storage storage :timeout-ms 100})]
          ;; Should complete (75 < 100) but log warning at ~80ms check
          (is (= 42 (exec/execute ctx (:id sleepy-fn) {}))))
        (finally
          (sp/close storage))))))
