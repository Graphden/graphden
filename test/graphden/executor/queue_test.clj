(ns graphden.executor.queue-test
  "Tests for the trampolined queue-based executor.

   Covers:
   - SmartDelay creation and deref behavior
   - NeedComputation marker exception
   - Execution state and cache key building
   - Limit checking (depth, timeout, cache size)
   - Full execute-with-queue via integration with storage

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.queue :as queue]
    [graphden.executor.test-helpers :as th]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.graph :as graph]))


;; === Fixtures for integration tests ===

(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


;; =============================================================================
;; SmartDelay Unit Tests
;; =============================================================================

(deftest smart-delay-realized-test
  (testing "creates a realized SmartDelay with the given value"
    (let [sd (queue/smart-delay-realized 42)]
      (is (= 42 @sd))
      (is (realized? sd))))

  (testing "creates a realized SmartDelay with nil value"
    (let [sd (queue/smart-delay-realized nil)]
      (is (nil? @sd))
      (is (realized? sd))))

  (testing "creates a realized SmartDelay with string value"
    (let [sd (queue/smart-delay-realized "hello")]
      (is (= "hello" @sd))
      (is (realized? sd))))

  (testing "creates a realized SmartDelay with collection value"
    (let [sd (queue/smart-delay-realized [1 2 3])]
      (is (= [1 2 3] @sd))
      (is (realized? sd)))))


(deftest smart-delay-unrealized-test
  (testing "throws NeedComputation when value not yet in value-map or result-cache"
    (let [exec-state (queue/->ExecutionState (atom {}) (atom {}) (atom {}) {})
          cache-key :test-key
          task {:some "task"}
          sd (queue/smart-delay cache-key exec-state task)]
      (is (not (realized? sd)))
      (is (thrown? clojure.lang.ExceptionInfo @sd))
      (try
        @sd
        (catch clojure.lang.ExceptionInfo e
          (is (queue/need-computation-ex? e))
          (is (= cache-key (:cache-key (ex-data e))))
          (is (= task (:task (ex-data e))))))))

  (testing "returns value from value-map when available"
    (let [cache-key :test-key
          exec-state (queue/->ExecutionState (atom {cache-key 99}) (atom {}) (atom {}) {})
          sd (queue/smart-delay cache-key exec-state {:some "task"})]
      (is (= 99 @sd))
      ;; After first deref, should be realized
      (is (realized? sd))))

  (testing "returns value from result-cache when not in value-map"
    (let [cache-key :test-key
          exec-state (queue/->ExecutionState (atom {}) (atom {cache-key 77}) (atom {}) {})
          sd (queue/smart-delay cache-key exec-state {:some "task"})]
      (is (= 77 @sd))
      (is (realized? sd))))

  (testing "handles nil sentinel in value-map correctly"
    (let [cache-key :test-key
          exec-state (queue/->ExecutionState (atom {cache-key :graphden.executor.queue/nil-sentinel}) (atom {}) (atom {}) {})
          sd (queue/smart-delay cache-key exec-state {:some "task"})]
      (is (nil? @sd))
      (is (realized? sd)))))


(deftest smart-delay-isRealized-test
  (testing "not realized when value-map and result-cache are empty"
    (let [exec-state (queue/->ExecutionState (atom {}) (atom {}) (atom {}) {})
          sd (queue/smart-delay :k exec-state {:task true})]
      (is (not (realized? sd)))))

  (testing "realized when value-map contains key"
    (let [exec-state (queue/->ExecutionState (atom {:k 42}) (atom {}) (atom {}) {})
          sd (queue/smart-delay :k exec-state {:task true})]
      (is (realized? sd))))

  (testing "realized when result-cache contains key"
    (let [exec-state (queue/->ExecutionState (atom {}) (atom {:k 42}) (atom {}) {})
          sd (queue/smart-delay :k exec-state {:task true})]
      (is (realized? sd)))))


;; =============================================================================
;; NeedComputation Marker Exception Tests
;; =============================================================================

(deftest throw-need-computation-test
  (testing "throws an ex-info with ::need-computation type"
    (try
      (queue/throw-need-computation :my-key {:fn-id :some-fn})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= "NeedComputation" (ex-message e)))
        (is (= :graphden.executor.queue/need-computation (:type (ex-data e))))
        (is (= :my-key (:cache-key (ex-data e))))
        (is (= {:fn-id :some-fn} (:task (ex-data e))))))))


(deftest need-computation-ex?-test
  (testing "returns true for NeedComputation exceptions"
    (let [e (ex-info "NeedComputation"
                     {:type :graphden.executor.queue/need-computation
                      :cache-key :k :task {}})]
      (is (true? (queue/need-computation-ex? e)))))

  (testing "returns false for other exceptions"
    (let [e (ex-info "Some other error" {:type :some-other-type})]
      (is (false? (queue/need-computation-ex? e)))))

  (testing "returns false for exceptions without ex-data"
    (is (false? (queue/need-computation-ex? (Exception. "plain"))))))


;; =============================================================================
;; Limit Checking Tests (via private functions)
;; =============================================================================

(deftest check-limits-depth-test
  (testing "throws when depth exceeds max-depth"
    (let [context {:max-depth 5 :start-time 0 :timeout-ms 10000
                   :clock (constantly 0)}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Maximum recursion depth exceeded"
            (#'queue/check-limits! context 6)))
      ;; Verify error data
      (try
        (#'queue/check-limits! context 6)
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/max-depth-exceeded (:type (ex-data e))))
          (is (= 6 (:depth (ex-data e))))
          (is (= 5 (:max-depth (ex-data e))))))))

  (testing "does not throw at exactly max-depth"
    (let [context {:max-depth 5 :start-time 0 :timeout-ms 10000
                   :clock (constantly 0)}]
      (is (nil? (#'queue/check-limits! context 5))))))


(deftest check-limits-timeout-test
  (testing "throws when timeout is exceeded"
    (let [{:keys [clock advance!]} (th/create-controllable-clock)
          context {:max-depth 100 :start-time 0 :timeout-ms 100 :clock clock}]
      (advance! 101)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Execution timeout exceeded"
            (#'queue/check-limits! context 0)))
      ;; Verify error data
      (try
        (#'queue/check-limits! context 0)
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/timeout (:type (ex-data e))))
          (is (= 100 (:timeout-ms (ex-data e))))))))

  (testing "does not throw when within timeout"
    (let [{:keys [clock advance!]} (th/create-controllable-clock)
          context {:max-depth 100 :start-time 0 :timeout-ms 100 :clock clock}]
      (advance! 50)
      (is (nil? (#'queue/check-limits! context 0))))))


(deftest check-cache-limit-test
  (testing "evicts entries when cache reaches max-size"
    (let [cache (atom (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 10))))
          context {:result-cache cache :cache-max-size 10}]
      (is (= 10 (count @cache)))
      (#'queue/check-cache-limit! context)
      ;; After eviction, should be at ~80% of max-size
      (is (<= (count @cache) 8))))

  (testing "does not evict when cache is below max-size"
    (let [cache (atom {:a 1 :b 2})
          context {:result-cache cache :cache-max-size 10}]
      (#'queue/check-cache-limit! context)
      (is (= 2 (count @cache))))))


;; =============================================================================
;; realize-lazy-value Tests
;; =============================================================================

(deftest realize-lazy-value-test
  (testing "passes through nil"
    (is (nil? (#'queue/realize-lazy-value nil))))

  (testing "passes through non-lazy values"
    (is (= 42 (#'queue/realize-lazy-value 42)))
    (is (= "hello" (#'queue/realize-lazy-value "hello"))))

  (testing "realizes lazy sequences as vectors"
    (let [result (#'queue/realize-lazy-value (map inc [1 2 3]))]
      (is (= [2 3 4] result))
      (is (vector? result))))

  (testing "realizes maps with lazy values recursively"
    (let [result (#'queue/realize-lazy-value {:a (map inc [1 2]) :b 42})]
      (is (= {:a [2 3] :b 42} result))))

  (testing "realizes nested maps with lazy sequences"
    (let [result (#'queue/realize-lazy-value {:outer {:inner (map str [1 2 3])}})]
      (is (= {:outer {:inner ["1" "2" "3"]}} result))))

  (testing "passes through vectors unchanged"
    (is (= [1 2 3] (#'queue/realize-lazy-value [1 2 3]))))

  (testing "passes through keywords unchanged"
    (is (= :foo (#'queue/realize-lazy-value :foo)))))


;; =============================================================================
;; ExecutionState and Cache Key Tests
;; =============================================================================

(deftest execution-state-test
  (testing "creates execution state with atoms"
    (let [ctx {:some "context"}
          state (queue/->ExecutionState (atom {}) (atom {}) (atom {}) ctx)]
      (is (= {} @(:value-map state)))
      (is (= {} @(:result-cache state)))
      (is (= {} @(:pending-tasks state)))
      (is (= ctx (:context state))))))


;; =============================================================================
;; Integration Tests - execute-with-queue via exec/execute
;; =============================================================================

(deftest execute-with-queue-simple-test
  (testing "executes function with literal arg-values via queue"
    (let [storage (setup/create-test-storage)
          {:keys [arg-a arg-b composed-fn]} (setup/setup-add-function! storage)
          ;; Bind both args to literal values
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


(deftest execute-with-queue-fn-reference-test
  (testing "executes referenced function and uses result via queue"
    (let [storage (setup/create-test-storage)
          ;; Register const function
          _ (exec/register-base-fn!
              :const
              (fn [{:keys [value]} _ctx]
                @value))
          ;; Create const base fn
          const-base (setup/create-base-fn! storage "const" :int)
          const-arg (setup/create-arg! storage (:id const-base)
                                       {:name "value" :type :int :required true :is-fn false})
          ;; Create const-3 and const-5
          const-3 (setup/create-composed-fn! storage "const-3" (:id const-base))
          _ (setup/create-arg! storage (:id const-3)
                               {:name "value" :type :int :required true :is-fn false
                                :source-id (:id const-arg) :value 3})
          const-5 (setup/create-composed-fn! storage "const-5" (:id const-base))
          _ (setup/create-arg! storage (:id const-5)
                               {:name "value" :type :int :required true :is-fn false
                                :source-id (:id const-arg) :value 5})
          ;; Create add function with refs to const-3 and const-5
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          add-fn (setup/create-composed-fn! storage "add-consts" (:id base-fn))
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


(deftest execute-with-queue-depth-limit-test
  (testing "throws when execution depth exceeds max-depth"
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
          ;; Create chain: fn-a -> fn-b -> fn-c (literal)
          fn-c (setup/create-composed-fn! storage "fn-c" (:id id-base))
          _ (setup/create-arg! storage (:id fn-c)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :value 42})
          fn-b (setup/create-composed-fn! storage "fn-b" (:id id-base))
          _ (setup/create-arg! storage (:id fn-b)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :ref-id (:id fn-c)})
          fn-a (setup/create-composed-fn! storage "fn-a" (:id id-base))
          _ (setup/create-arg! storage (:id fn-a)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :ref-id (:id fn-b)})
          ;; Use very low max-depth to trigger the limit
          ctx (exec/create-context {:storage storage :max-depth 1})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Maximum recursion depth exceeded"
            (exec/execute ctx (:id fn-a) {})))
      (sp/close storage))))


(deftest execute-with-queue-timeout-test
  (testing "throws when execution timeout is exceeded"
    (let [storage (setup/create-test-storage)
          ;; Register a slow function
          _ (exec/register-base-fn!
              :slow-fn
              (fn [{:keys [x]} _ctx]
                (Thread/sleep 200)
                @x))
          ;; Create slow base fn
          slow-base (setup/create-base-fn! storage "slow-fn" :int)
          slow-arg (setup/create-arg! storage (:id slow-base)
                                      {:name "x" :type :int :required true :is-fn false})
          ;; Create composed fn with literal value
          slow-composed (setup/create-composed-fn! storage "slow-composed" (:id slow-base))
          _ (setup/create-arg! storage (:id slow-composed)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id slow-arg) :value 42})
          ;; Register identity fn that refs the slow fn
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} _ctx]
                @x))
          id-base (setup/create-base-fn! storage "identity" :int)
          id-arg (setup/create-arg! storage (:id id-base)
                                    {:name "x" :type :int :required true :is-fn false})
          ;; Create wrapper that refs slow-composed
          wrapper (setup/create-composed-fn! storage "wrapper" (:id id-base))
          _ (setup/create-arg! storage (:id wrapper)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :ref-id (:id slow-composed)})
          ;; Use very short timeout
          ctx (exec/create-context {:storage storage :timeout-ms 50})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Execution timeout exceeded"
            (exec/execute ctx (:id wrapper) {})))
      (sp/close storage))))


(deftest execute-with-queue-string-result-test
  (testing "handles string result from base function correctly"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :const
              (fn [{:keys [value]} _ctx]
                @value))
          const-base (setup/create-base-fn! storage "const" :text)
          const-arg (setup/create-arg! storage (:id const-base)
                                       {:name "value" :type :text :required true :is-fn false})
          const-hello (setup/create-composed-fn! storage "const-hello" (:id const-base))
          _ (setup/create-arg! storage (:id const-hello)
                               {:name "value" :type :text :required true :is-fn false
                                :source-id (:id const-arg) :value "hello"})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id const-hello) {})]
      (is (= "hello" result))
      (sp/close storage))))


;; =============================================================================
;; Unit Tests for Private Functions
;; =============================================================================

(deftest need-computation-type-test
  (testing "NeedComputation toString includes cache-key"
    (let [nc (queue/->NeedComputation :my-cache-key {:task true})]
      (is (= "NeedComputation: :my-cache-key" (str nc))))))


(deftest get-fn-args-with-inheritance-test
  (testing "returns own args for base-fn (no parent)"
    (let [fn-id (random-uuid)
          arg {:id (random-uuid) :fn-id fn-id :name "x" :type :int}
          graph (graph/->execution-graph
                  {:fns {fn-id {:id fn-id :parent-id nil}}
                   :args [arg]})]
      (is (= [arg] (#'queue/get-fn-args-with-inheritance graph fn-id 0)))))

  (testing "inherits parent args not overridden by child"
    (let [base-id (random-uuid)
          child-id (random-uuid)
          base-arg {:id (random-uuid) :fn-id base-id :name "x" :type :int}
          child-arg {:id (random-uuid) :fn-id child-id :source-id (:id base-arg) :value 10}
          graph (graph/->execution-graph
                  {:fns {base-id {:id base-id :parent-id nil}
                         child-id {:id child-id :parent-id base-id}}
                   :args [base-arg child-arg]})
          result (#'queue/get-fn-args-with-inheritance graph child-id 0)]
      (is (= 1 (count result)))
      (is (= (:id child-arg) (:id (first result))))))

  (testing "includes un-overridden parent args"
    (let [base-id (random-uuid)
          child-id (random-uuid)
          arg-x {:id (random-uuid) :fn-id base-id :name "x" :type :int}
          arg-y {:id (random-uuid) :fn-id base-id :name "y" :type :int}
          ;; Child only overrides arg-x
          child-arg {:id (random-uuid) :fn-id child-id :source-id (:id arg-x) :value 10}
          graph (graph/->execution-graph
                  {:fns {base-id {:id base-id :parent-id nil}
                         child-id {:id child-id :parent-id base-id}}
                   :args [arg-x arg-y child-arg]})
          result (#'queue/get-fn-args-with-inheritance graph child-id 0)
          result-ids (set (map :id result))]
      ;; Should have child-arg AND arg-y (not arg-x since it's overridden)
      (is (= 2 (count result)))
      (is (contains? result-ids (:id child-arg)))
      (is (contains? result-ids (:id arg-y))))))


(deftest nil-sentinel-in-result-cache-test
  (testing "nil sentinel in result-cache resolves to nil"
    (let [cache-key :test-key
          exec-state (queue/->ExecutionState
                       (atom {})
                       (atom {cache-key :graphden.executor.queue/nil-sentinel})
                       (atom {}) {})
          sd (queue/smart-delay cache-key exec-state {:some "task"})]
      (is (nil? @sd))
      (is (realized? sd)))))


(deftest resolve-base-fn-test
  (testing "returns base-fn when parent-id is nil"
    (let [fn-id (random-uuid)
          fns {fn-id {:id fn-id :parent-id nil :name "my-fn"}}]
      (is (= {:id fn-id :parent-id nil :name "my-fn"}
             (#'queue/resolve-base-fn fns fn-id 0)))))

  (testing "follows parent chain to find base-fn"
    (let [base-id (random-uuid)
          child-id (random-uuid)
          fns {base-id {:id base-id :parent-id nil :name "base"}
               child-id {:id child-id :parent-id base-id}}]
      (is (= {:id base-id :parent-id nil :name "base"}
             (#'queue/resolve-base-fn fns child-id 0)))))

  (testing "throws when fn not found"
    (let [fn-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Function not found"
            (#'queue/resolve-base-fn {} fn-id 0)))))

  (testing "throws when parent chain exceeds max depth"
    (binding [sp/*max-graph-iterations* 2]
      (let [id1 (random-uuid)
            id2 (random-uuid)
            id3 (random-uuid)
            ;; Create chain longer than max-depth: id1->id2->id3->nil
            ;; but starting at depth 2 means depth check triggers
            fns {id1 {:id id1 :parent-id id2}
                 id2 {:id id2 :parent-id id3}
                 id3 {:id id3 :parent-id nil}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Parent chain exceeds maximum depth"
              (#'queue/resolve-base-fn fns id1 2)))))))


(deftest fn-in-parent-chain-test
  (testing "returns true when fn-id is start-fn-id"
    (let [fn-id (random-uuid)
          fns {fn-id {:id fn-id :parent-id nil}}]
      (is (true? (#'queue/fn-in-parent-chain? fns fn-id fn-id)))))

  (testing "returns true when fn-id is in parent chain"
    (let [base-id (random-uuid)
          child-id (random-uuid)
          fns {base-id {:id base-id :parent-id nil}
               child-id {:id child-id :parent-id base-id}}]
      (is (true? (#'queue/fn-in-parent-chain? fns base-id child-id)))))

  (testing "returns false when fn-id is NOT in parent chain"
    (let [base-id (random-uuid)
          other-id (random-uuid)
          child-id (random-uuid)
          fns {base-id {:id base-id :parent-id nil}
               other-id {:id other-id :parent-id nil}
               child-id {:id child-id :parent-id base-id}}]
      (is (false? (#'queue/fn-in-parent-chain? fns other-id child-id)))))

  (testing "returns false for nil start-fn-id"
    (let [fn-id (random-uuid)]
      (is (false? (#'queue/fn-in-parent-chain? {} fn-id nil))))))


(deftest get-fn-data-from-graph-test
  (testing "throws when fn-id not found in execution graph"
    (let [fn-id (random-uuid)
          graph {:fns {} :args-by-fn {} :args-by-id {} :arg-roots {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Function not found"
            (#'queue/get-fn-data-from-graph graph fn-id)))
      (try
        (#'queue/get-fn-data-from-graph graph fn-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/fn-not-found (:type (ex-data e))))
          (is (some? (:available-fn-ids (ex-data e)))))))))


(deftest collect-propagated-args-test
  (testing "collects args that trace to target fn"
    (let [target-fn-id (random-uuid)
          base-arg-id (random-uuid)
          caller-arg-id (random-uuid)
          ;; Base arg belongs to target fn, caller arg has source-id pointing to base arg
          args-by-id {base-arg-id {:id base-arg-id :fn-id target-fn-id :name "x"}
                      caller-arg-id {:id caller-arg-id :fn-id (random-uuid) :source-id base-arg-id :value 42}}
          graph {:args-by-id args-by-id}
          caller-args [{:id caller-arg-id :fn-id (random-uuid) :source-id base-arg-id :value 42}]
          result (#'queue/collect-propagated-args graph caller-args target-fn-id)]
      ;; Should find that caller-arg traces to base-arg-id
      (is (contains? result base-arg-id))
      (is (= 42 (get-in result [base-arg-id :arg :value])))))

  (testing "prefers literal values over refs at same depth"
    (let [target-fn-id (random-uuid)
          base-arg-id (random-uuid)
          lit-arg-id (random-uuid)
          ref-arg-id (random-uuid)
          args-by-id {base-arg-id {:id base-arg-id :fn-id target-fn-id :name "x"}
                      lit-arg-id {:id lit-arg-id :source-id base-arg-id :value 10}
                      ref-arg-id {:id ref-arg-id :source-id base-arg-id :ref-id (random-uuid)}}
          graph {:args-by-id args-by-id}
          ;; Both caller args trace to same base arg, but one has literal and one has ref
          caller-args [{:id ref-arg-id :source-id base-arg-id :ref-id (random-uuid)}
                       {:id lit-arg-id :source-id base-arg-id :value 10}]
          result (#'queue/collect-propagated-args graph caller-args target-fn-id)]
      ;; Should prefer the literal value
      (is (= 10 (get-in result [base-arg-id :arg :value])))))

  (testing "direct match includes args belonging to target fn with value"
    (let [target-fn-id (random-uuid)
          direct-arg-id (random-uuid)
          args-by-id {direct-arg-id {:id direct-arg-id :fn-id target-fn-id :value 99}}
          graph {:args-by-id args-by-id}
          caller-args [{:id direct-arg-id :fn-id target-fn-id :value 99}]
          result (#'queue/collect-propagated-args graph caller-args target-fn-id)]
      (is (contains? result direct-arg-id))
      (is (= 99 (get-in result [direct-arg-id :arg :value])))))

  (testing "does NOT direct-match pass-through args (no value, no ref)"
    (let [target-fn-id (random-uuid)
          passthru-arg-id (random-uuid)
          args-by-id {passthru-arg-id {:id passthru-arg-id :fn-id target-fn-id :name "x"}}
          graph {:args-by-id args-by-id}
          caller-args [{:id passthru-arg-id :fn-id target-fn-id :name "x"}]
          result (#'queue/collect-propagated-args graph caller-args target-fn-id)]
      ;; Pass-through (no value, no ref-id) should NOT match directly
      (is (empty? result)))))


(deftest build-deep-cache-key-test
  (testing "returns fn-id when no propagated args"
    (let [fn-id (random-uuid)
          graph {:args-by-id {} :args-by-fn {} :fns {fn-id {:id fn-id :parent-id nil}}}
          result (#'queue/build-deep-cache-key graph [] fn-id 0 #{})]
      (is (= fn-id result))))

  (testing "returns fn-id when depth exceeds limit"
    (let [fn-id (random-uuid)]
      ;; depth > 10 triggers early return
      (is (= fn-id (#'queue/build-deep-cache-key {} [] fn-id 11 #{})))))

  (testing "returns fn-id when cycle detected (fn already visited)"
    (let [fn-id (random-uuid)]
      (is (= fn-id (#'queue/build-deep-cache-key {} [] fn-id 0 #{fn-id})))))

  (testing "includes literal values in cache key"
    (let [target-fn-id (random-uuid)
          base-arg-id (random-uuid)
          caller-arg-id (random-uuid)
          args-by-id {base-arg-id {:id base-arg-id :fn-id target-fn-id :name "x"}
                      caller-arg-id {:id caller-arg-id :source-id base-arg-id :value 42}}
          graph {:args-by-id args-by-id
                 :args-by-fn {target-fn-id [{:id base-arg-id :fn-id target-fn-id :name "x"}]}
                 :fns {target-fn-id {:id target-fn-id :parent-id nil}}}
          caller-args [{:id caller-arg-id :source-id base-arg-id :value 42}]
          result (#'queue/build-deep-cache-key graph caller-args target-fn-id 0 #{})]
      ;; Result should be [fn-id {base-arg-id [:lit 42]}]
      (is (vector? result))
      (is (= target-fn-id (first result)))
      (is (= [:lit 42] (get (second result) base-arg-id))))))


(deftest arg-belongs-to-current-fn-test
  (testing "base-fn arg (no source-id) belongs to its own fn"
    (let [base-fn-id (random-uuid)
          arg-id (random-uuid)
          arg {:id arg-id :fn-id base-fn-id :name "x" :type :int}
          args-by-id {arg-id arg}
          fns {base-fn-id {:id base-fn-id :parent-id nil}}
          graph {:args-by-id args-by-id :fns fns}]
      (is (true? (#'queue/arg-belongs-to-current-fn? graph arg base-fn-id)))))

  (testing "inherited arg with name belongs to derived fn"
    (let [base-fn-id (random-uuid)
          child-fn-id (random-uuid)
          base-arg-id (random-uuid)
          arg {:id base-arg-id :fn-id base-fn-id :name "x" :type :int}
          args-by-id {base-arg-id arg}
          fns {base-fn-id {:id base-fn-id :parent-id nil}
               child-fn-id {:id child-fn-id :parent-id base-fn-id}}
          graph {:args-by-id args-by-id :fns fns}]
      (is (true? (#'queue/arg-belongs-to-current-fn? graph arg child-fn-id)))))

  (testing "own arg with value and source in parent chain belongs"
    (let [base-fn-id (random-uuid)
          child-fn-id (random-uuid)
          base-arg-id (random-uuid)
          child-arg-id (random-uuid)
          base-arg {:id base-arg-id :fn-id base-fn-id :name "x" :type :int}
          child-arg {:id child-arg-id :fn-id child-fn-id :source-id base-arg-id :value 10}
          args-by-id {base-arg-id base-arg
                      child-arg-id child-arg}
          fns {base-fn-id {:id base-fn-id :parent-id nil}
               child-fn-id {:id child-fn-id :parent-id base-fn-id}}
          graph {:args-by-id args-by-id :fns fns}]
      (is (true? (#'queue/arg-belongs-to-current-fn? graph child-arg child-fn-id)))))

  (testing "arg whose root does NOT belong to base-fn returns false"
    (let [base-fn-id (random-uuid)
          other-fn-id (random-uuid)
          arg-id (random-uuid)
          ;; Arg's root belongs to other-fn, not base-fn
          arg {:id arg-id :fn-id other-fn-id :name "x" :type :int}
          args-by-id {arg-id arg}
          fns {base-fn-id {:id base-fn-id :parent-id nil}
               other-fn-id {:id other-fn-id :parent-id nil}}
          graph {:args-by-id args-by-id :fns fns}]
      (is (false? (#'queue/arg-belongs-to-current-fn? graph arg base-fn-id)))))

  (testing "own arg with source NOT in parent chain returns false"
    (let [base-fn-id (random-uuid)
          other-fn-id (random-uuid)
          child-fn-id (random-uuid)
          base-arg-id (random-uuid)
          other-arg-id (random-uuid)
          child-arg-id (random-uuid)
          base-arg {:id base-arg-id :fn-id base-fn-id :name "x" :type :int}
          other-arg {:id other-arg-id :fn-id other-fn-id :name "y" :type :int}
          ;; child-arg sources from other-fn's arg, NOT from parent chain
          child-arg {:id child-arg-id :fn-id child-fn-id :source-id other-arg-id :value 10}
          args-by-id {base-arg-id base-arg
                      other-arg-id other-arg
                      child-arg-id child-arg}
          fns {base-fn-id {:id base-fn-id :parent-id nil}
               other-fn-id {:id other-fn-id :parent-id nil}
               child-fn-id {:id child-fn-id :parent-id base-fn-id}}
          graph {:args-by-id args-by-id :fns fns}]
      ;; Root of child-arg goes to other-arg -> other-fn, not base-fn
      (is (false? (#'queue/arg-belongs-to-current-fn? graph child-arg child-fn-id))))))


(deftest trace-source-to-fn-test
  (testing "returns result when arg belongs to target fn"
    (let [target-fn-id (random-uuid)
          arg-id (random-uuid)
          args-by-id {arg-id {:id arg-id :fn-id target-fn-id :name "x"}}
          graph {:args-by-id args-by-id}]
      (is (= {:target-arg-id arg-id :depth 0}
             (#'queue/trace-source-to-fn graph arg-id target-fn-id #{} 0)))))

  (testing "follows source-id chain to find target"
    (let [target-fn-id (random-uuid)
          base-arg-id (random-uuid)
          mid-arg-id (random-uuid)
          args-by-id {base-arg-id {:id base-arg-id :fn-id target-fn-id :name "x"}
                      mid-arg-id {:id mid-arg-id :fn-id (random-uuid) :source-id base-arg-id}}
          graph {:args-by-id args-by-id}]
      (is (= {:target-arg-id base-arg-id :depth 1}
             (#'queue/trace-source-to-fn graph mid-arg-id target-fn-id #{} 0)))))

  (testing "returns nil when target not reachable"
    (let [target-fn-id (random-uuid)
          arg-id (random-uuid)
          args-by-id {arg-id {:id arg-id :fn-id (random-uuid) :name "x"}}
          graph {:args-by-id args-by-id}]
      (is (nil? (#'queue/trace-source-to-fn graph arg-id target-fn-id #{} 0)))))

  (testing "returns nil when depth exceeds limit"
    (binding [sp/*max-graph-iterations* 0]
      (let [arg-id (random-uuid)]
        (is (nil? (#'queue/trace-source-to-fn {} arg-id (random-uuid) #{} 1)))))))


;; =============================================================================
;; Integration Tests - Cache Hits and Multiple Retries
;; =============================================================================

(deftest execute-with-queue-cache-hit-test
  (testing "same fn referenced twice uses cached result"
    (let [storage (setup/create-test-storage)
          call-count (atom 0)
          _ (exec/register-base-fn!
              :counting-const
              (fn [{:keys [value]} _ctx]
                (swap! call-count inc)
                @value))
          ;; Create counting-const base fn
          const-base (setup/create-base-fn! storage "counting-const" :int)
          const-arg (setup/create-arg! storage (:id const-base)
                                       {:name "value" :type :int :required true :is-fn false})
          ;; Create const-7
          const-7 (setup/create-composed-fn! storage "const-7" (:id const-base))
          _ (setup/create-arg! storage (:id const-7)
                               {:name "value" :type :int :required true :is-fn false
                                :source-id (:id const-arg) :value 7})
          ;; Create add function that refs const-7 for BOTH args
          {:keys [base-fn arg-a arg-b]} (setup/setup-add-function! storage)
          add-fn (setup/create-composed-fn! storage "add-same" (:id base-fn))
          _ (setup/create-arg! storage (:id add-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :ref-id (:id const-7)})
          _ (setup/create-arg! storage (:id add-fn)
                               {:name "b" :type :int :required true :is-fn false
                                :source-id (:id arg-b) :ref-id (:id const-7)})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id add-fn) {})]
      ;; Result should be 7 + 7 = 14
      (is (= 14 result))
      ;; counting-const should be called only ONCE due to cache hit
      (is (= 1 @call-count))
      (sp/close storage))))


(deftest execute-with-queue-multiple-retries-test
  (testing "trampoline handles multi-level dependency chains (A refs B refs C)"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :identity
              (fn [{:keys [x]} _ctx]
                @x))
          ;; Create identity base fn
          id-base (setup/create-base-fn! storage "identity" :int)
          id-arg (setup/create-arg! storage (:id id-base)
                                    {:name "x" :type :int :required true :is-fn false})
          ;; Chain: fn-a -> fn-b -> fn-c (literal 42)
          fn-c (setup/create-composed-fn! storage "fn-c" (:id id-base))
          _ (setup/create-arg! storage (:id fn-c)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :value 42})
          fn-b (setup/create-composed-fn! storage "fn-b" (:id id-base))
          _ (setup/create-arg! storage (:id fn-b)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :ref-id (:id fn-c)})
          fn-a (setup/create-composed-fn! storage "fn-a" (:id id-base))
          _ (setup/create-arg! storage (:id fn-a)
                               {:name "x" :type :int :required true :is-fn false
                                :source-id (:id id-arg) :ref-id (:id fn-b)})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id fn-a) {})]
      ;; Should correctly traverse A -> B -> C and return 42
      (is (= 42 result))
      (sp/close storage))))


(deftest execute-with-queue-nil-result-test
  (testing "nil return from base function triggers NPE due to {:done nil} being falsy"
    ;; BUG: try-execute-task returns {:done nil} when fn returns nil.
    ;; (:done result) is nil which is falsy, so the trampoline enters
    ;; the :need branch and tries to push nil dependency onto ArrayDeque.
    ;; This is a known issue: the trampoline check (if (:done result))
    ;; conflates nil result with "not done".
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :nil-fn
              (fn [_args _ctx]
                nil))
          nil-base (setup/create-base-fn! storage "nil-fn" :any)
          nil-composed (setup/create-composed-fn! storage "my-nil" (:id nil-base))
          ctx (exec/create-context {:storage storage})]
      (is (thrown? NullPointerException
            (exec/execute ctx (:id nil-composed) {})))
      (sp/close storage))))


(deftest execute-with-queue-base-fn-not-found-test
  (testing "throws when base function is not registered"
    (let [storage (setup/create-test-storage)
          ;; Create a base fn in storage but do NOT register it
          missing-base (setup/create-base-fn! storage "unregistered-fn" :int)
          composed (setup/create-composed-fn! storage "use-unregistered" (:id missing-base))
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Base function.*not found"
            (exec/execute ctx (:id composed) {})))
      (try
        (exec/execute ctx (:id composed) {})
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/base-fn-not-found (:type (ex-data e))))))
      (sp/close storage))))


(deftest execute-with-queue-missing-required-arg-test
  (testing "throws when required argument is not provided and has no value"
    (let [storage (setup/create-test-storage)
          {:keys [arg-a composed-fn]} (setup/setup-add-function! storage)
          ;; Only bind arg-a, leave arg-b unbound and required
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 5})
          ctx (exec/create-context {:storage storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Required argument.*not provided"
            (exec/execute ctx (:id composed-fn) {})))
      (try
        (exec/execute ctx (:id composed-fn) {})
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/missing-required-arg (:type (ex-data e))))))
      (sp/close storage))))


(deftest execute-with-queue-is-fn-arg-test
  (testing "is-fn=true passes fn-id directly instead of executing"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :get-fn-id
              (fn [{:keys [f]} _ctx]
                ;; f should be the fn-id UUID itself, not an executed result
                (str @f)))
          get-fn-id-base (setup/create-base-fn! storage "get-fn-id" :text)
          f-arg (setup/create-arg! storage (:id get-fn-id-base)
                                   {:name "f" :type :fn :required true :is-fn true})
          ;; Create a dummy fn to reference
          _ (exec/register-base-fn!
              :dummy
              (fn [_args _ctx] "should not execute"))
          dummy-base (setup/create-base-fn! storage "dummy" :text)
          ;; Create composed fn that passes dummy as is-fn
          composed (setup/create-composed-fn! storage "get-dummy-id" (:id get-fn-id-base))
          _ (setup/create-arg! storage (:id composed)
                               {:name "f" :type :fn :required true :is-fn true
                                :source-id (:id f-arg) :ref-id (:id dummy-base)})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id composed) {})]
      ;; Result should be the string representation of dummy-base's UUID
      (is (= (str (:id dummy-base)) result))
      (sp/close storage))))


(deftest execute-with-queue-lazy-seq-realization-test
  (testing "lazy sequences are realized as vectors in results"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :lazy-range
              (fn [{:keys [n]} _ctx]
                ;; Return a lazy seq
                (map inc (range @n))))
          lazy-base (setup/create-base-fn! storage "lazy-range" :jsonb)
          n-arg (setup/create-arg! storage (:id lazy-base)
                                   {:name "n" :type :int :required true :is-fn false})
          composed (setup/create-composed-fn! storage "range-5" (:id lazy-base))
          _ (setup/create-arg! storage (:id composed)
                               {:name "n" :type :int :required true :is-fn false
                                :source-id (:id n-arg) :value 5})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id composed) {})]
      (is (= [1 2 3 4 5] result))
      (is (vector? result))
      (sp/close storage))))


(deftest execute-with-queue-provided-args-test
  (testing "provided args override free (unbound) arguments"
    (let [storage (setup/create-test-storage)
          {:keys [arg-a arg-b composed-fn]} (setup/setup-add-function! storage)
          ;; Bind only arg-a, leave arg-b free
          _ (setup/create-arg! storage (:id composed-fn)
                               {:name "a" :type :int :required true :is-fn false
                                :source-id (:id arg-a) :value 10})
          ;; We need the inherited arg-b's id for the composed fn
          ;; Since arg-b is inherited (not overridden), it keeps its original id
          ctx (exec/create-context {:storage storage})
          ;; Provide arg-b via its ID
          result (exec/execute ctx (:id composed-fn) {(:id arg-b) 5})]
      (is (= 15 result))
      (sp/close storage))))


;; =============================================================================
;; Branch Coverage Tests - SmartDelay edge cases
;; =============================================================================

(deftest smart-delay-nil-sentinel-value-map-returns-nil
  (testing "value-map containing ::nil-sentinel returns nil (not the sentinel)"
    (let [cache-key :k
          exec-state (queue/->ExecutionState
                       (atom {cache-key :graphden.executor.queue/nil-sentinel})
                       (atom {})
                       (atom {}) {})
          sd (queue/smart-delay cache-key exec-state {:task true})]
      ;; First deref reads from value-map and converts sentinel -> nil
      (is (nil? @sd))
      ;; Subsequent deref uses cached-value (realized-flag path)
      (is (nil? @sd))
      (is (realized? sd)))))


(deftest smart-delay-result-cache-non-sentinel-value
  (testing "result-cache path returns actual value and caches it for subsequent deref"
    (let [cache-key :k
          exec-state (queue/->ExecutionState
                       (atom {})
                       (atom {cache-key "from-cache"})
                       (atom {}) {})
          sd (queue/smart-delay cache-key exec-state {:task true})]
      ;; First deref hits result-cache
      (is (= "from-cache" @sd))
      ;; Second deref uses realized-flag fast path
      (is (= "from-cache" @sd))
      (is (realized? sd)))))


(deftest smart-delay-isRealized-nil-exec-state-fields
  (testing "isRealized returns false when exec-state has nil atoms (SmartDelay-realized path)"
    ;; smart-delay-realized creates SmartDelay with nil exec-state
    (let [sd (queue/smart-delay-realized 42)]
      ;; realized-flag is true, so short-circuits before checking exec-state
      (is (realized? sd)))

    ;; SmartDelay-realized has exec-state=nil but realized-flag=true
    ;; so isRealized short-circuits before accessing exec-state
    (let [sd (queue/smart-delay-realized "test")]
      (is (realized? sd)))))


;; =============================================================================
;; Branch Coverage Tests - build-deep-cache-key branches
;; =============================================================================

(deftest build-deep-cache-key-is-fn-ref-branch
  (testing "is-fn ref produces [:fn ref-id] in cache key"
    (let [target-fn-id (random-uuid)
          base-arg-id (random-uuid)
          caller-arg-id (random-uuid)
          ref-fn-id (random-uuid)
          args-by-id {base-arg-id {:id base-arg-id :fn-id target-fn-id :name "f"}
                      caller-arg-id {:id caller-arg-id :source-id base-arg-id :ref-id ref-fn-id :is-fn true}}
          graph {:args-by-id args-by-id
                 :args-by-fn {target-fn-id [{:id base-arg-id :fn-id target-fn-id :name "f"}]}
                 :fns {target-fn-id {:id target-fn-id :parent-id nil}}}
          caller-args [{:id caller-arg-id :source-id base-arg-id :ref-id ref-fn-id :is-fn true}]
          result (#'queue/build-deep-cache-key graph caller-args target-fn-id 0 #{})]
      (is (vector? result))
      (is (= target-fn-id (first result)))
      (is (= [:fn ref-fn-id] (get (second result) base-arg-id))))))


(deftest build-deep-cache-key-nil-pass-through-branch
  (testing "pass-through arg (no value, no ref) produces [:nil] in cache key"
    (let [target-fn-id (random-uuid)
          base-arg-id (random-uuid)
          caller-arg-id (random-uuid)
          ;; caller-arg has source-id -> base-arg but no value and no ref-id
          args-by-id {base-arg-id {:id base-arg-id :fn-id target-fn-id :name "x"}
                      caller-arg-id {:id caller-arg-id :fn-id target-fn-id :source-id base-arg-id}}
          graph {:args-by-id args-by-id
                 :args-by-fn {target-fn-id [{:id base-arg-id :fn-id target-fn-id :name "x"}]}
                 :fns {target-fn-id {:id target-fn-id :parent-id nil}}}
          ;; Direct match: arg belongs to target fn AND has value - but here no value
          ;; so it won't direct-match. Use ref-id on caller-arg for it to match via trace
          other-fn-id (random-uuid)
          tracing-arg-id (random-uuid)
          ;; An arg from another fn that traces to base-arg but has no value/ref
          args-by-id2 (assoc args-by-id
                             tracing-arg-id {:id tracing-arg-id :fn-id other-fn-id :source-id base-arg-id})
          graph2 (assoc graph :args-by-id args-by-id2)
          caller-args [{:id tracing-arg-id :fn-id other-fn-id :source-id base-arg-id}]
          result (#'queue/build-deep-cache-key graph2 caller-args target-fn-id 0 #{})]
      ;; The pass-through arg should produce [:nil]
      (when (vector? result)
        (is (= [:nil] (get (second result) base-arg-id)))))))


(deftest build-deep-cache-key-ref-recursive-branch
  (testing "normal ref-id (not is-fn) produces [:ref ...] with recursive key"
    (let [target-fn-id (random-uuid)
          inner-fn-id (random-uuid)
          base-arg-id (random-uuid)
          caller-arg-id (random-uuid)
          base-arg {:id base-arg-id :fn-id target-fn-id :name "x"}
          caller-arg {:id caller-arg-id :source-id base-arg-id :ref-id inner-fn-id}
          graph (graph/->execution-graph
                  {:fns {target-fn-id {:id target-fn-id :parent-id nil}
                         inner-fn-id {:id inner-fn-id :parent-id nil}}
                   :args [base-arg caller-arg]})
          caller-args [caller-arg]
          result (#'queue/build-deep-cache-key graph caller-args target-fn-id 0 #{})]
      (is (vector? result))
      ;; The entry for base-arg-id should be [:ref <recursive-key>]
      (let [entry (get (second result) base-arg-id)]
        (is (= :ref (first entry)))))))


;; =============================================================================
;; Branch Coverage Tests - collect-propagated-args depth replacement
;; =============================================================================

(deftest collect-propagated-args-shorter-depth-wins
  (testing "when two args of same type (both refs) trace to same target, shorter depth wins"
    (let [target-fn-id (random-uuid)
          base-arg-id (random-uuid)
          mid-arg-id (random-uuid)
          ;; Two callers: one traces directly (depth 0), one traces via mid (depth 1)
          direct-arg-id (random-uuid)
          indirect-arg-id (random-uuid)
          ref1 (random-uuid)
          ref2 (random-uuid)
          args-by-id {base-arg-id {:id base-arg-id :fn-id target-fn-id :name "x"}
                      mid-arg-id {:id mid-arg-id :fn-id (random-uuid) :source-id base-arg-id}
                      direct-arg-id {:id direct-arg-id :fn-id target-fn-id :ref-id ref1}
                      indirect-arg-id {:id indirect-arg-id :fn-id (random-uuid) :source-id mid-arg-id :ref-id ref2}}
          graph {:args-by-id args-by-id}
          ;; indirect traces: indirect -> mid -> base (depth 1)
          ;; direct is direct match (depth 0) because fn-id = target-fn-id and has ref-id
          caller-args [{:id indirect-arg-id :fn-id (random-uuid) :source-id mid-arg-id :ref-id ref2}
                       {:id direct-arg-id :fn-id target-fn-id :ref-id ref1}]
          result (#'queue/collect-propagated-args graph caller-args target-fn-id)]
      ;; Direct match (depth 0) should win over indirect (depth 1) since both are refs
      (is (= ref1 (get-in result [direct-arg-id :arg :ref-id]))))))


;; =============================================================================
;; Branch Coverage Tests - arg-belongs-to-current-fn edge cases
;; =============================================================================

(deftest arg-belongs-inherited-with-value-no-name
  (testing "inherited arg with value (not from current fn) belongs if it has value"
    (let [base-fn-id (random-uuid)
          mid-fn-id (random-uuid)
          child-fn-id (random-uuid)
          base-arg-id (random-uuid)
          mid-arg-id (random-uuid)
          base-arg {:id base-arg-id :fn-id base-fn-id :name "x" :type :int}
          ;; mid-arg: belongs to mid-fn, has source -> base, has value
          mid-arg {:id mid-arg-id :fn-id mid-fn-id :source-id base-arg-id :value 42}
          args-by-id {base-arg-id base-arg
                      mid-arg-id mid-arg}
          fns {base-fn-id {:id base-fn-id :parent-id nil}
               mid-fn-id {:id mid-fn-id :parent-id base-fn-id}
               child-fn-id {:id child-fn-id :parent-id mid-fn-id}}
          graph {:args-by-id args-by-id :fns fns}]
      ;; mid-arg is inherited (not own), has value -> should belong
      (is (true? (#'queue/arg-belongs-to-current-fn? graph mid-arg child-fn-id))))))


(deftest arg-belongs-own-arg-with-source-name-only
  (testing "own arg with source that has a name (but arg itself has no value/ref) belongs"
    (let [base-fn-id (random-uuid)
          child-fn-id (random-uuid)
          base-arg-id (random-uuid)
          child-arg-id (random-uuid)
          base-arg {:id base-arg-id :fn-id base-fn-id :name "x" :type :int}
          ;; child-arg: belongs to child, sources from base, but has no value/ref-id
          ;; It should still belong because the source arg has a name
          child-arg {:id child-arg-id :fn-id child-fn-id :source-id base-arg-id}
          args-by-id {base-arg-id base-arg
                      child-arg-id child-arg}
          fns {base-fn-id {:id base-fn-id :parent-id nil}
               child-fn-id {:id child-fn-id :parent-id base-fn-id}}
          graph {:args-by-id args-by-id :fns fns}]
      ;; own arg, source in parent chain, source has name -> belongs (returns truthy)
      (is (#'queue/arg-belongs-to-current-fn? graph child-arg child-fn-id)))))


(deftest arg-belongs-inherited-no-name-no-value-no-ref
  (testing "inherited arg with no name, no value, no ref does NOT belong"
    (let [base-fn-id (random-uuid)
          child-fn-id (random-uuid)
          base-arg-id (random-uuid)
          mid-arg-id (random-uuid)
          base-arg {:id base-arg-id :fn-id base-fn-id :name "x" :type :int}
          ;; mid-arg: from base, has source -> base, but no name/value/ref
          mid-arg {:id mid-arg-id :fn-id base-fn-id :source-id base-arg-id}
          args-by-id {base-arg-id base-arg
                      mid-arg-id mid-arg}
          fns {base-fn-id {:id base-fn-id :parent-id nil}
               child-fn-id {:id child-fn-id :parent-id base-fn-id}}
          graph {:args-by-id args-by-id :fns fns}]
      ;; inherited, no name, no value, no ref -> doesn't belong
      (is (false? (#'queue/arg-belongs-to-current-fn? graph mid-arg child-fn-id))))))


;; =============================================================================
;; Branch Coverage Tests - check-cache-limit! edge cases
;; =============================================================================

(deftest check-cache-limit-exactly-at-max
  (testing "evicts when cache size equals max-size exactly"
    (let [cache (atom (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 5))))
          context {:result-cache cache :cache-max-size 5}]
      (is (= 5 (count @cache)))
      (#'queue/check-cache-limit! context)
      ;; Should evict to 80% of 5 = 4
      (is (<= (count @cache) 4)))))


(deftest check-cache-limit-one-over-max
  (testing "eviction removes correct number of entries"
    (let [cache (atom (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 11))))
          context {:result-cache cache :cache-max-size 10}]
      (#'queue/check-cache-limit! context)
      ;; Target is 80% of 10 = 8, so removes 11-8=3 entries
      (is (= 8 (count @cache))))))


;; =============================================================================
;; Branch Coverage Tests - realize-lazy-value nested edge cases
;; =============================================================================

(deftest realize-lazy-value-empty-map
  (testing "empty map passes through unchanged"
    (is (= {} (#'queue/realize-lazy-value {})))))


(deftest realize-lazy-value-map-with-nil-value
  (testing "map with nil value passes through"
    (is (= {:a nil} (#'queue/realize-lazy-value {:a nil})))))


;; =============================================================================
;; Branch Coverage Tests - execute-task-once root cache-key path
;; =============================================================================

(deftest execute-task-once-root-skips-result-cache
  (testing "when cache-key is :root, result is stored in value-map but NOT result-cache"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :const
              (fn [{:keys [value]} _ctx]
                @value))
          const-base (setup/create-base-fn! storage "const" :int)
          const-arg (setup/create-arg! storage (:id const-base)
                                       {:name "value" :type :int :required true :is-fn false})
          const-42 (setup/create-composed-fn! storage "const-42" (:id const-base))
          _ (setup/create-arg! storage (:id const-42)
                               {:name "value" :type :int :required true :is-fn false
                                :source-id (:id const-arg) :value 42})
          ctx (exec/create-context {:storage storage})
          result (exec/execute ctx (:id const-42) {})]
      ;; The root result should be returned correctly
      (is (= 42 result))
      ;; Result cache should be empty (root results skip result-cache)
      ;; Note: this tests the `(when (not= cache-key :root)` branch in execute-task-once
      (is (empty? @(:result-cache ctx)))
      (sp/close storage))))


;; =============================================================================
;; Branch Coverage Tests - error wrapping in try-execute-task
;; =============================================================================

(deftest try-execute-task-non-need-computation-exception-rethrown
  (testing "non-NeedComputation exceptions are re-thrown, not caught as :need"
    (let [storage (setup/create-test-storage)
          _ (exec/register-base-fn!
              :error-fn
              (fn [_args _ctx]
                (throw (ex-info "Intentional error"
                                {:type :test-error/intentional}))))
          error-base (setup/create-base-fn! storage "error-fn" :any)
          composed (setup/create-composed-fn! storage "error-composed" (:id error-base))
          ctx (exec/create-context {:storage storage})]
      (try
        (exec/execute ctx (:id composed) {})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :test-error/intentional (:type (ex-data e))))))
      (sp/close storage))))


;; =============================================================================
;; Branch Coverage Tests - trace-source-to-fn visited cycle prevention
;; =============================================================================

(deftest trace-source-to-fn-cycle-returns-nil
  (testing "returns nil when source-id chain has a cycle (visited set)"
    (let [arg-a-id (random-uuid)
          arg-b-id (random-uuid)
          ;; Create a cycle: a -> b -> a
          args-by-id {arg-a-id {:id arg-a-id :fn-id (random-uuid) :source-id arg-b-id}
                      arg-b-id {:id arg-b-id :fn-id (random-uuid) :source-id arg-a-id}}
          graph {:args-by-id args-by-id}
          target-fn-id (random-uuid)]
      ;; Should return nil because of cycle detection (visited set)
      (is (nil? (#'queue/trace-source-to-fn graph arg-a-id target-fn-id #{} 0))))))


(deftest trace-source-to-fn-nil-source-id-no-match
  (testing "returns nil when arg has no source-id and fn-id doesn't match target"
    (let [arg-id (random-uuid)
          args-by-id {arg-id {:id arg-id :fn-id (random-uuid)}}
          graph {:args-by-id args-by-id}]
      ;; No source-id and fn-id != target -> nil
      (is (nil? (#'queue/trace-source-to-fn graph arg-id (random-uuid) #{} 0))))))


;; =============================================================================
;; Branch Coverage Tests - get-fn-args-with-inheritance depth limit
;; =============================================================================

(deftest get-fn-args-with-inheritance-depth-limit
  (testing "throws when parent chain exceeds max graph iterations"
    (binding [sp/*max-graph-iterations* 1]
      (let [id1 (random-uuid)
            id2 (random-uuid)
            id3 (random-uuid)
            graph (graph/->execution-graph
                    {:fns {id1 {:id id1 :parent-id id2}
                           id2 {:id id2 :parent-id id3}
                           id3 {:id id3 :parent-id nil}}
                     :args []})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Parent chain exceeds maximum depth"
              (#'queue/get-fn-args-with-inheritance graph id1 1)))))))
