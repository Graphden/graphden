(ns graphden.executor.core-test
  "Core tests for executor.

   Covers:
   - Base function registry
   - Basic execution
   - Literal arg values
   - Partial application (currying)
   - Function references
   - Error handling
   - Mutual references"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


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
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
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


(deftest execute-fn-reference-test
  (testing "executes referenced function and uses result"
    (let [storage (setup/create-test-storage)
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
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Set arg-values to reference const functions via fn-result-value
          ;; (fn-result-value means: execute the fn and use its result)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value (setup/create-fn-result-value! storage (:id const-3))})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value (setup/create-fn-result-value! storage (:id const-5))})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id fn-rec) {})]
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
          ;; fn-a -> fn-b -> fn-c -> literal (via fn-result-value to trigger execution)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-a)
                               :arg-schema-id (:id id-arg)
                               :value (setup/create-fn-result-value! storage (:id fn-b))})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id id-arg)
                               :value (setup/create-fn-result-value! storage (:id fn-c))})
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
    (let [storage (setup/create-test-storage)
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
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a]} (setup/setup-add-function! storage)
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
                               :value (setup/create-fn-result-value! storage (:id fn-b))})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-b)
                               :arg-schema-id (:id slow-arg)
                               :value (setup/create-fn-result-value! storage (:id fn-c))})
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
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
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
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
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
  (testing "throws when fn-result-value references non-existent fn during execution"
    (let [storage (setup/create-test-storage)
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
          ;; Create identity fn instance
          id-fn (sp/create-entity storage :fn
                                  {:name "my-identity"
                                   :fn-schema-id (:id id-schema)})
          ;; Create fn-result-value pointing to non-existent fn
          non-existent-fn-id (random-uuid)
          bad-frv (sp/create-entity storage :fn-result-value
                                    {:fn-id non-existent-fn-id
                                     :name "bad-frv"})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id id-fn)
                               :arg-schema-id (:id id-arg)
                               :value (:id bad-frv)})
          ctx (exec/create-context {:storage storage})]
      ;; When we execute, it will try to resolve the fn-result-value which points
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

  (testing "throws when path-args exceed max count"
    (let [storage (setup/create-test-storage)
          ;; Create 1001 path-args (max is 1000)
          large-path-args (into {}
                                (for [i (range 1001)]
                                  [(random-uuid) i]))]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"path-args count exceeds maximum"
              (exec/create-context {:storage storage :path-args large-path-args})))
        (finally
          (sp/close storage)))))

  (testing "throws when path-args keys have invalid format"
    (let [storage (setup/create-test-storage)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"path-args keys must be UUID or"
              (exec/create-context {:storage storage
                                    :path-args {"string-key" 42}})))
        (finally
          (sp/close storage)))))

  (testing "throws with multiple validation errors combined"
    (let [invalid-storage {:fake "storage"}]
      ;; Missing storage + path-args not a map
      (try
        (exec/create-context {:storage invalid-storage
                              :path-args "not-a-map"
                              :max-depth -1
                              :timeout-ms 10})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [errors (:validation-errors (ex-data e))]
            (is (> (count errors) 1) "should have multiple errors"))))))

  (testing "accepts path-args with valid UUID keys"
    (let [storage (setup/create-test-storage)
          path-args {(random-uuid) 42}]
      (try
        (let [ctx (exec/create-context {:storage storage :path-args path-args})]
          (is (some? ctx)))
        (finally
          (sp/close storage)))))

  (testing "accepts path-args with valid [UUID UUID] vector keys"
    (let [storage (setup/create-test-storage)
          path-args {[(random-uuid) (random-uuid)] 42}]
      (try
        (let [ctx (exec/create-context {:storage storage :path-args path-args})]
          (is (some? ctx)))
        (finally
          (sp/close storage))))))


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
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 10})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 20})
          ctx (exec/create-context {:storage storage})
          result (exec/execute-by-name ctx (:name fn-rec) nil)]
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
          {:keys [fn-rec]} (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be nil or a map"
              (exec/execute ctx (:id fn-rec) "not-a-map")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"args must be nil or a map"
              (exec/execute ctx (:id fn-rec) [1 2 3])))
        (finally
          (sp/close storage)))))

  (testing "execute-with-named-args throws when named-args is not nil or map"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec]} (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"named-args must be nil or a map"
              (exec/execute-with-named-args ctx (:id fn-rec) "not-a-map")))
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
          ;; Create hof-caller schema
          hof-schema (sp/create-entity storage :fn-schema
                                       {:name "hof-caller"
                                        :returned-type :int})
          hof-arg (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id hof-schema)
                                     :name "f"
                                     :type :fn
                                     :required true})
          ;; Create a function with NO required args (all optional)
          _ (exec/register-base-fn!
              :no-args-fn
              (fn [_ _] 0))
          no-args-schema (sp/create-entity storage :fn-schema
                                           {:name "no-args-fn"
                                            :returned-type :int})
          ;; Only optional arg
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id no-args-schema)
                               :name "optional"
                               :type :int
                               :required false})
          no-args-fn (sp/create-entity storage :fn
                                       {:name "no-args"
                                        :fn-schema-id (:id no-args-schema)})
          ;; Create hof-caller instance
          hof-fn (sp/create-entity storage :fn
                                   {:name "my-hof"
                                    :fn-schema-id (:id hof-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id hof-fn)
                               :arg-schema-id (:id hof-arg)
                               :value (:id no-args-fn)})
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
          ;; Create hof-caller schema
          hof-schema (sp/create-entity storage :fn-schema
                                       {:name "hof-caller"
                                        :returned-type :int})
          hof-arg (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id hof-schema)
                                     :name "f"
                                     :type :fn
                                     :required true})
          ;; Create a function with multiple required args (like add)
          {:keys [fn-rec]} (setup/setup-add-function! storage)
          ;; Create hof-caller instance
          hof-fn (sp/create-entity storage :fn
                                   {:name "my-hof"
                                    :fn-schema-id (:id hof-schema)})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id hof-fn)
                               :arg-schema-id (:id hof-arg)
                               :value (:id fn-rec)})
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
          {:keys [fn-rec]} (setup/setup-add-function! storage)
          ctx (exec/create-context {:storage storage})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown argument name"
              (exec/execute-with-named-args ctx (:id fn-rec) {:unknown-arg 42})))
        (finally
          (sp/close storage))))))


;; === execute-with-named-args with nil or empty args test ===

(deftest execute-with-named-args-empty-args-test
  (testing "passes nil named-args to execute"
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
          ctx (exec/create-context {:storage storage})
          result (exec/execute-with-named-args ctx (:id fn-rec) nil)]
      (is (= 12 result))
      (sp/close storage)))

  (testing "passes empty map named-args to execute"
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
          ctx (exec/create-context {:storage storage})
          result (exec/execute-with-named-args ctx (:id fn-rec) {})]
      (is (= 7 result))
      (sp/close storage))))


;; === fn-result-value cache eviction test ===

(deftest cache-eviction-test
  (testing "evicts oldest entries when cache limit reached"
    (let [storage (setup/create-test-storage)
          ;; Register const function
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
          ;; Create 10 const functions
          fns (doall
                (for [i (range 10)]
                  (let [fn-rec (sp/create-entity storage :fn
                                                 {:name (str "const-" i)
                                                  :fn-schema-id (:id const-schema)})]
                    (sp/create-entity storage :arg-value
                                      {:owner-fn-id (:id fn-rec)
                                       :arg-schema-id (:id const-arg)
                                       :value i})
                    fn-rec)))
          ;; Create context with very small cache (2 entries)
          ctx (exec/create-context {:storage storage
                                    :cache-max-size 3
                                    :cache-warning-threshold 2})]
      (try
        ;; Execute all 10 functions
        (doseq [fn-rec fns]
          (exec/execute ctx (:id fn-rec) nil))
        ;; Cache should not exceed max size due to eviction
        (is (<= (count @(:result-cache ctx)) 3))
        (finally
          (sp/close storage))))))


;; === execute-by-name with named-args test ===

(deftest execute-by-name-with-named-args-test
  (testing "executes function by name with named-args"
    (let [storage (setup/create-test-storage)
          {:keys [fn-rec arg-a arg-b]} (setup/setup-add-function! storage)
          ;; Set default values
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-a)
                               :value 10})
          _ (sp/create-entity storage :arg-value
                              {:owner-fn-id (:id fn-rec)
                               :arg-schema-id (:id arg-b)
                               :value 20})
          ctx (exec/create-context {:storage storage})
          ;; Override with named args
          result (exec/execute-by-name ctx (:name fn-rec) {:a 100 :b 200})]
      (is (= 300 result))
      (sp/close storage))))
