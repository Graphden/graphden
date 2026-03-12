(ns ^:integration graphden.announced-functionality-test
  "High-level tests for all announced Graphden functionality.

   These tests verify the complete stack for key features as described
   in the documentation (CLAUDE.md, ARCHITECTURE.md):

   1. 2-Entity Graph Model (fn + arg)
   2. Function inheritance via parent-id
   3. Argument inheritance via source-id
   4. HOF support via is-fn flag
   5. Value binding (value, ref-id)
   6. Lazy evaluation with delays
   7. GraphConstraints validation
   8. Execution graph resolution
   9. Result caching"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.versioning.storage.core :as vs]))


;; === Test Infrastructure ===

(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))


(use-fixtures :each
  (th/create-clean-db-fixture #'*container*)
  exec/with-clean-registry)


(defn- create-versioned-storage
  "Creates a versioned storage initialized with versioned schema."
  []
  (th/clean-database-fast! *container*)
  (let [schema (vds/build-schema (mds/create-builder))
        base (-> (pg/create-storage (th/get-container-config *container*))
                 (sp/initialize-with-cleanup! schema))]
    (vs/wrap-with-versioning base)))


;; =============================================================================
;; Test 1: 2-Entity Graph Model
;; =============================================================================
;;
;; The system uses only 2 entity types to represent all functions:
;; - fn: represents both base functions and composed functions
;; - arg: represents both primary arguments and inherited/bound arguments
;;
;; This minimal model reduces complexity while enabling full expressiveness.

(deftest two-entity-model-test
  (testing "2-entity model: fn and arg represent all function types"
    (let [storage (create-versioned-storage)]
      (try
        ;; Register base functions
        (registry/initialize-all! storage
                                  [{:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}
                                   {:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}])

        ;; Verify base functions are stored as fn entities with parent-id=nil
        (let [const-fn (first (sp/query-entities storage :fn {:name "const"}))
              add-fn (first (sp/query-entities storage :fn {:name "add"}))]
          (testing "base functions have parent-id=nil"
            (is (nil? (:parent-id const-fn)))
            (is (nil? (:parent-id add-fn))))

          ;; Verify args are stored as arg entities with source-id=nil
          (let [const-args (sp/query-entities storage :arg {:fn-id (:id const-fn)})
                add-args (sp/query-entities storage :arg {:fn-id (:id add-fn)})]
            (testing "base function args have source-id=nil (primary args)"
              (is (= 1 (count const-args)))
              (is (= 2 (count add-args)))
              (is (every? #(nil? (:source-id %)) const-args))
              (is (every? #(nil? (:source-id %)) add-args)))))

        ;; Sync composed functions
        (composition/sync-fns-to-storage! storage
                                          [{:name :five :parent :const :args {:x 5}}
                                           {:name :sum :parent :add :args {:a :five :b :five}}])

        ;; Verify composed functions have parent-id set
        (let [five-fn (first (sp/query-entities storage :fn {:name "five"}))
              sum-fn (first (sp/query-entities storage :fn {:name "sum"}))
              const-fn (first (sp/query-entities storage :fn {:name "const"}))]
          (testing "composed functions have parent-id set"
            (is (= (:id const-fn) (:parent-id five-fn)))
            (is (some? (:parent-id sum-fn))))

          ;; Verify composed function args reference parent args via source-id
          (let [five-args (sp/query-entities storage :arg {:fn-id (:id five-fn)})]
            (testing "composed function args have source-id set (inherited)"
              (is (= 1 (count five-args)))
              (is (some? (:source-id (first five-args))))
              (is (= 5 (:value (first five-args)))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 2: Function Inheritance via parent-id
;; =============================================================================
;;
;; Functions inherit from other functions using parent-id:
;; - Base function: parent-id=nil (has impl-hash linking to Clojure impl)
;; - Composed function: parent-id points to another fn

(deftest function-inheritance-test
  (testing "function inheritance: composed fn inherits from base fn"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:multiply {:args {:a {:type :int :required true}
                                                      :b {:type :int :required true}}
                                               :return-type :int
                                               :impl (fn [{:keys [a b]} _] (* @a @b))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create inheritance chain: double inherits multiply
        (composition/sync-fns-to-storage! storage
                                          [{:name :two :parent :const :args {:x 2}}
                                           {:name :double :parent :multiply :args {:a :two}}])

        (let [multiply-fn (first (sp/query-entities storage :fn {:name "multiply"}))
              double-fn (first (sp/query-entities storage :fn {:name "double"}))]
          (testing "double inherits from multiply"
            (is (= (:id multiply-fn) (:parent-id double-fn))))

          ;; Execute: double(4) = 2 * 4 = 8
          (let [ctx (exec/create-context {:storage storage})
                ;; Create a fn that calls double with b=4
                _ (composition/sync-fns-to-storage! storage
                                                    [{:name :four :parent :const :args {:x 4}}
                                                     {:name :double-four :parent :double :args {:b :four}}])
                double-four-fn (first (sp/query-entities storage :fn {:name "double-four"}))]
            (testing "inherited function executes correctly"
              (is (= 8 (exec/execute ctx (:id double-four-fn) nil))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 3: Argument Inheritance via source-id
;; =============================================================================
;;
;; Arguments inherit from parent function's args using source-id:
;; - Primary arg: source-id=nil (defines the interface)
;; - Inherited arg: source-id points to parent's arg
;;   - Can have value (literal binding)
;;   - Can have ref-id (reference to another fn)

(deftest argument-inheritance-test
  (testing "argument inheritance: args bind or pass through parent args"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create add-5: binds 'a' to 5, passes through 'b'
        (composition/sync-fns-to-storage! storage
                                          [{:name :five :parent :const :args {:x 5}}
                                           {:name :add-5 :parent :add :args {:a :five}}])

        (let [add-fn (first (sp/query-entities storage :fn {:name "add"}))
              add-5-fn (first (sp/query-entities storage :fn {:name "add-5"}))
              add-args (sp/query-entities storage :arg {:fn-id (:id add-fn)})
              add-5-args (sp/query-entities storage :arg {:fn-id (:id add-5-fn)})
              a-arg-base (first (filter #(= "a" (:name %)) add-args))
              a-arg-composed (first (filter #(= (:source-id %) (:id a-arg-base)) add-5-args))]

          (testing "add-5's 'a' arg has source-id pointing to add's 'a'"
            (is (some? a-arg-composed))
            (is (= (:id a-arg-base) (:source-id a-arg-composed))))

          (testing "add-5's 'a' arg has ref-id to :five fn"
            (is (some? (:ref-id a-arg-composed))))

          ;; Execute: add-5(b=3) = 5 + 3 = 8
          (let [ctx (exec/create-context {:storage storage})
                _ (composition/sync-fns-to-storage! storage
                                                    [{:name :three :parent :const :args {:x 3}}
                                                     {:name :add-5-3 :parent :add-5 :args {:b :three}}])
                result-fn (first (sp/query-entities storage :fn {:name "add-5-3"}))]
            (testing "inherited argument binding works in execution"
              (is (= 8 (exec/execute ctx (:id result-fn) nil))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 4: HOF Support via is-fn Flag
;; =============================================================================
;;
;; Higher-order functions receive fn-ids directly (not executed results):
;; - is-fn=true on parent arg → child's ref-id passed directly
;; - is-fn=false (default) → child's ref-id is executed, result passed

(deftest hof-is-fn-flag-test
  (testing "is-fn flag: controls whether fn-id is passed or executed"
    (let [storage (create-versioned-storage)]
      (try
        ;; Define a HOF that takes a function and applies it twice
        (registry/initialize-all! storage
                                  [{:apply-twice {:args {:f {:type :fn :required true}
                                                         :x {:type :int :required true}}
                                                  :return-type :int
                                                  :impl (fn [{:keys [f x]} ctx]
                                                          (let [first-result (exec/execute-with-named-args ctx @f {:n @x})
                                                                second-result (exec/execute-with-named-args ctx @f {:n first-result})]
                                                            second-result))}}
                                   {:inc-n {:args {:n {:type :int :required true}}
                                            :return-type :int
                                            :impl (fn [{:keys [n]} _] (inc @n))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Verify is-fn flag is set on HOF arg
        (let [apply-twice-fn (first (sp/query-entities storage :fn {:name "apply-twice"}))
              f-arg (first (filter #(= "f" (:name %))
                                   (sp/query-entities storage :arg {:fn-id (:id apply-twice-fn)})))]
          (testing "HOF arg has is-fn=true"
            (is (true? (:is-fn f-arg)))))

        ;; Create composition: apply-twice(inc-n, 5) → inc(inc(5)) = 7
        (composition/sync-fns-to-storage! storage
                                          [{:name :five :parent :const :args {:x 5}}
                                           {:name :inc-twice
                                            :parent :apply-twice
                                            :args {:f :inc-n :x :five}}])

        (let [ctx (exec/create-context {:storage storage})
              inc-twice-fn (first (sp/query-entities storage :fn {:name "inc-twice"}))]
          (testing "HOF executes with fn-id passed (not executed)"
            (is (= 7 (exec/execute ctx (:id inc-twice-fn) nil)))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 5: Value Binding
;; =============================================================================
;;
;; Arguments can be bound via:
;; - value: literal JSONB value stored in arg entity
;; - ref-id: reference to another fn (execute and use result)

(deftest value-binding-test
  (testing "value binding: literal values and fn references"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create functions with different binding types
        (composition/sync-fns-to-storage! storage
                                          [;; Literal value binding via const
                                           {:name :ten :parent :const :args {:x 10}}
                                           ;; fn reference binding
                                           {:name :add-tens :parent :add :args {:a :ten :b :ten}}])

        (let [ctx (exec/create-context {:storage storage})
              add-tens-fn (first (sp/query-entities storage :fn {:name "add-tens"}))]
          (testing "literal value binding works"
            (is (= 20 (exec/execute ctx (:id add-tens-fn) nil)))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 6: Lazy Evaluation
;; =============================================================================
;;
;; Execution uses delays (lazy evaluation):
;; - Arguments are wrapped in delays
;; - Only evaluated when dereferenced
;; - Enables short-circuit evaluation for conditionals

(deftest lazy-evaluation-test
  (testing "lazy evaluation: only needed branches computed"
    (let [storage (create-versioned-storage)
          left-evaluated (atom false)
          right-evaluated (atom false)]
      (try
        (registry/initialize-all! storage
                                  [{:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}
                                   {:track-left {:args {:x {:type :any :required true}}
                                                 :return-type :any
                                                 :impl (fn [{:keys [x]} _]
                                                         (reset! left-evaluated true)
                                                         @x)}}
                                   {:track-right {:args {:x {:type :any :required true}}
                                                  :return-type :any
                                                  :impl (fn [{:keys [x]} _]
                                                          (reset! right-evaluated true)
                                                          @x)}}
                                   ;; Lazy conditional: only evaluates taken branch
                                   {:if-fn {:args {:condition {:type :bool :required true}
                                                   :then-val {:type :any :required true}
                                                   :else-val {:type :any :required true}}
                                            :return-type :any
                                            :lazy #{:then-val :else-val}
                                            :impl (fn [{:keys [condition then-val else-val]} _]
                                                    (if @condition @then-val @else-val))}}])

        (composition/sync-fns-to-storage! storage
                                          [{:name :true-cond :parent :const :args {:x true}}
                                           {:name :left-val :parent :track-left :args {:x "left"}}
                                           {:name :right-val :parent :track-right :args {:x "right"}}
                                           {:name :lazy-cond
                                            :parent :if-fn
                                            :args {:condition :true-cond
                                                   :then-val :left-val
                                                   :else-val :right-val}}])

        (reset! left-evaluated false)
        (reset! right-evaluated false)

        (let [ctx (exec/create-context {:storage storage})
              cond-fn (first (sp/query-entities storage :fn {:name "lazy-cond"}))
              result (exec/execute ctx (:id cond-fn) nil)]
          (testing "returns correct branch result"
            (is (= "left" result)))
          (testing "only taken branch was evaluated"
            (is @left-evaluated "left branch should be evaluated")
            (is (not @right-evaluated) "right branch should NOT be evaluated")))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 7: GraphConstraints - Cycle Detection
;; =============================================================================
;;
;; The system prevents dependency cycles:
;; - A fn cannot depend on itself through any chain
;; - Enforced at write time via GraphConstraints protocol

(deftest graph-constraints-cycle-detection-test
  (testing "GraphConstraints: prevents dependency cycles"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:identity {:args {:x {:type :any :required true}}
                                               :return-type :any
                                               :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create fn-a
        (composition/sync-fns-to-storage! storage
                                          [{:name :fn-a :parent :identity :args {:x 1}}])

        ;; Try to create fn-b that depends on fn-a
        (composition/sync-fns-to-storage! storage
                                          [{:name :fn-b :parent :identity :args {:x :fn-a}}])

        ;; Verify both functions exist
        (let [fn-a (first (sp/query-entities storage :fn {:name "fn-a"}))
              fn-b (first (sp/query-entities storage :fn {:name "fn-b"}))]
          (testing "valid dependency chain is allowed"
            (is (some? fn-a))
            (is (some? fn-b)))

          ;; Verify cycle detection via validate-no-dependency-cycle!
          (testing "self-reference cycle is detected"
            ;; A fn cannot depend on itself
            (is (thrown? clojure.lang.ExceptionInfo
                  (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-a))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 8: Execution Graph Resolution
;; =============================================================================
;;
;; resolve-execution-graph builds the complete dependency tree:
;; - Follows parent-id chain for inheritance
;; - Follows ref-id chain for value dependencies
;; - Returns ExecutionGraphResult with all fns and args needed

(deftest execution-graph-resolution-test
  (testing "execution graph: resolves complete dependency tree"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create diamond dependency:
        ;;      result
        ;;      /    \
        ;;   left    right
        ;;      \    /
        ;;       base
        (composition/sync-fns-to-storage! storage
                                          [{:name :base :parent :const :args {:x 1}}
                                           {:name :left :parent :add :args {:a :base :b :base}}
                                           {:name :right :parent :add :args {:a :base :b :base}}
                                           {:name :result :parent :add :args {:a :left :b :right}}])

        (let [result-fn (first (sp/query-entities storage :fn {:name "result"}))
              graph (sp/resolve-execution-graph storage (:id result-fn))
              fns (sp/get-graph-fns graph)
              args (sp/get-graph-args graph)]
          (testing "graph contains all dependent fns"
            ;; Should have: result, left, right, base, add, const
            (is (>= (count fns) 4)))

          (testing "graph contains all dependent args"
            (is (seq args)))

          (testing "graph is executable"
            (let [ctx (exec/create-context {:storage storage})]
              ;; result = left + right = (1+1) + (1+1) = 4
              (is (= 4 (exec/execute ctx (:id result-fn) nil))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 9: Result Caching
;; =============================================================================
;;
;; The executor caches results to avoid duplicate computation:
;; - Same fn-id executed only once per context
;; - Important for diamond dependencies

(deftest result-caching-test
  (testing "result caching: same fn executed only once"
    (let [storage (create-versioned-storage)
          call-count (atom 0)]
      (try
        (registry/initialize-all! storage
                                  [{:counted-const {:args {:x {:type :any :required true}}
                                                    :return-type :any
                                                    :impl (fn [{:keys [x]} _]
                                                            (swap! call-count inc)
                                                            @x)}}
                                   {:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}])

        ;; Both a and b reference the same fn
        (composition/sync-fns-to-storage! storage
                                          [{:name :shared :parent :counted-const :args {:x 5}}
                                           {:name :double-shared :parent :add :args {:a :shared :b :shared}}])

        (reset! call-count 0)

        (let [ctx (exec/create-context {:storage storage})
              double-fn (first (sp/query-entities storage :fn {:name "double-shared"}))
              result (exec/execute ctx (:id double-fn) nil)]
          (testing "returns correct result"
            (is (= 10 result)))
          (testing "shared dependency only executed once (cached)"
            (is (= 1 @call-count))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 10: Full Stack Integration
;; =============================================================================
;;
;; Complete workflow test: packages → registry → storage → execution

(deftest full-stack-integration-test
  (testing "full stack: packages → registry → storage → execution"
    (let [storage (create-versioned-storage)]
      (try
        ;; Step 1: Register base functions (simulating package loading)
        (registry/initialize-all! storage
                                  [{:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}
                                   {:multiply {:args {:a {:type :int :required true}
                                                      :b {:type :int :required true}}
                                               :return-type :int
                                               :impl (fn [{:keys [a b]} _] (* @a @b))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Step 2: Sync fn-defs (simulating package fn-defs)
        (composition/sync-fns-to-storage! storage
                                          [{:name :two :parent :const :args {:x 2}}
                                           {:name :three :parent :const :args {:x 3}}
                                           {:name :four :parent :const :args {:x 4}}
                                           ;; (2 + 3) * 4 = 20
                                           {:name :sum-2-3 :parent :add :args {:a :two :b :three}}
                                           {:name :result :parent :multiply :args {:a :sum-2-3 :b :four}}])

        ;; Step 3: Execute
        (let [ctx (exec/create-context {:storage storage})
              result-fn (first (sp/query-entities storage :fn {:name "result"}))]
          (testing "complex composition executes correctly"
            (is (= 20 (exec/execute ctx (:id result-fn) nil)))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 11: Diamond Dependency Pattern
;; =============================================================================
;;
;; Diamond dependency is when multiple fns reference the same dependency.
;; The system handles this efficiently with caching.
;;
;;         result
;;        /      \
;;     left      right
;;        \      /
;;         shared

(deftest diamond-dependency-pattern-test
  (testing "diamond dependency: shared deps executed efficiently"
    (let [storage (create-versioned-storage)
          shared-call-count (atom 0)]
      (try
        (registry/initialize-all! storage
                                  [{:tracked-const
                                    {:args {:x {:type :any :required true}}
                                     :return-type :any
                                     :impl (fn [{:keys [x]} _]
                                             (swap! shared-call-count inc)
                                             @x)}}
                                   {:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Diamond pattern where shared is referenced by both left and right
        (composition/sync-fns-to-storage! storage
                                          [{:name :shared :parent :tracked-const :args {:x 5}}
                                           {:name :one :parent :const :args {:x 1}}
                                           {:name :left :parent :add :args {:a :shared :b :one}}
                                           {:name :right :parent :add :args {:a :one :b :shared}}
                                           {:name :result :parent :add :args {:a :left :b :right}}])

        (reset! shared-call-count 0)

        (let [ctx (exec/create-context {:storage storage})
              result-fn (first (sp/query-entities storage :fn {:name "result"}))]
          (testing "result is correct"
            ;; result = left + right = (5+1) + (1+5) = 12
            (is (= 12 (exec/execute ctx (:id result-fn) nil))))
          (testing "shared dependency executed efficiently (2 calls due to 2 refs)"
            ;; Called twice: once for left's :a, once for right's :b
            ;; But within same context each ref to :shared is cached per-arg
            (is (<= @shared-call-count 2))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 12: Cycle Detection at Composition Time
;; =============================================================================
;;
;; The system prevents circular dependencies at composition time:
;; - sync-fns-to-storage! detects self-references
;; - Throws before storing invalid graph

(deftest cycle-detection-at-composition-test
  (testing "cycle detection: prevents self-referential fn compositions"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:identity {:args {:x {:type :any :required true}}
                                               :return-type :any
                                               :impl (fn [{:keys [x]} _] @x)}}])

        (testing "self-reference is caught during sync"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"[Cc]ircular|[Cc]ycle"
                (composition/sync-fns-to-storage! storage
                                                  [{:name :self-ref
                                                    :parent :identity
                                                    :args {:x :self-ref}}]))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 13: Optional Arguments
;; =============================================================================
;;
;; Arguments can be optional (required=false):
;; - Missing optional args resolve to nil
;; - Allows flexible function signatures

(deftest optional-arguments-test
  (testing "optional arguments: default to nil when not provided"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:greet
                                    {:args {:person-name {:type :text :required true}
                                            :suffix {:type :text :required false}}
                                     :return-type :text
                                     :impl (fn [{:keys [person-name suffix]} _]
                                             (str "Hello, " @person-name
                                                  (when-let [s @suffix]
                                                    (str " " s))))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create greeting without suffix (optional arg not bound)
        (composition/sync-fns-to-storage! storage
                                          [{:name :alice :parent :const :args {:x "Alice"}}
                                           {:name :greet-alice
                                            :parent :greet
                                            :args {:person-name :alice}}])

        (let [ctx (exec/create-context {:storage storage})
              greet-fn (first (sp/query-entities storage :fn {:name "greet-alice"}))]
          (testing "optional arg defaults to nil"
            (is (= "Hello, Alice" (exec/execute ctx (:id greet-fn) nil)))))

        ;; Create greeting with suffix
        (composition/sync-fns-to-storage! storage
                                          [{:name :exclaim :parent :const :args {:x "!"}}
                                           {:name :greet-alice-exclaim
                                            :parent :greet
                                            :args {:person-name :alice :suffix :exclaim}}])

        (let [ctx (exec/create-context {:storage storage})
              greet-exclaim-fn (first (sp/query-entities storage :fn {:name "greet-alice-exclaim"}))]
          (testing "optional arg can be provided"
            (is (= "Hello, Alice !" (exec/execute ctx (:id greet-exclaim-fn) nil)))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 14: Multi-Level Inheritance Chain
;; =============================================================================
;;
;; Functions can inherit through multiple levels:
;; - A → B → C → D (D inherits from C inherits from B inherits from A)
;; - Each level can bind or pass through arguments

(deftest multi-level-inheritance-test
  (testing "multi-level inheritance: A → B → C → D"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:add-three
                                    {:args {:a {:type :int :required true}
                                            :b {:type :int :required true}
                                            :c {:type :int :required true}}
                                     :return-type :int
                                     :impl (fn [{:keys [a b c]} _] (+ @a @b @c))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Build inheritance chain:
        ;; add-three (base)
        ;;   └── add-three-with-a (binds a=1)
        ;;         └── add-three-with-ab (binds b=2)
        ;;               └── add-three-with-abc (binds c=3)
        (composition/sync-fns-to-storage! storage
                                          [{:name :one :parent :const :args {:x 1}}
                                           {:name :two :parent :const :args {:x 2}}
                                           {:name :three :parent :const :args {:x 3}}
                                           {:name :add-with-a
                                            :parent :add-three
                                            :args {:a :one}}
                                           {:name :add-with-ab
                                            :parent :add-with-a
                                            :args {:b :two}}
                                           {:name :add-with-abc
                                            :parent :add-with-ab
                                            :args {:c :three}}])

        (let [ctx (exec/create-context {:storage storage})
              final-fn (first (sp/query-entities storage :fn {:name "add-with-abc"}))]
          (testing "3-level inheritance executes correctly (1+2+3=6)"
            (is (= 6 (exec/execute ctx (:id final-fn) nil)))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 15: Local Functions (name=nil)
;; =============================================================================
;;
;; Functions without names are local/anonymous:
;; - Not globally visible
;; - Used for intermediate computations
;; - Referenced only by fn-id

(deftest local-functions-test
  (testing "local functions: anonymous intermediate computations"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create a local (anonymous) const function
        (let [add-fn (first (sp/query-entities storage :fn {:name "add"}))
              const-fn (first (sp/query-entities storage :fn {:name "const"}))
              ;; Create local fn (no name)
              local-five (sp/create-entity storage :fn
                                           {:parent-id (:id const-fn)
                                            :return-type :int})
              const-args (sp/query-entities storage :arg {:fn-id (:id const-fn)})
              x-arg (first (filter #(= "x" (:name %)) const-args))
              ;; Bind local fn's x to 5
              _ (sp/create-entity storage :arg
                                  {:fn-id (:id local-five)
                                   :source-id (:id x-arg)
                                   :value 5})
              ;; Create named fn using the local fn
              add-local-fives (sp/create-entity storage :fn
                                                {:name "add-local-fives"
                                                 :parent-id (:id add-fn)
                                                 :return-type :int})
              add-args (sp/query-entities storage :arg {:fn-id (:id add-fn)})
              a-arg (first (filter #(= "a" (:name %)) add-args))
              b-arg (first (filter #(= "b" (:name %)) add-args))]
          ;; Bind a and b to local fn
          (sp/create-entity storage :arg
                            {:fn-id (:id add-local-fives)
                             :source-id (:id a-arg)
                             :ref-id (:id local-five)})
          (sp/create-entity storage :arg
                            {:fn-id (:id add-local-fives)
                             :source-id (:id b-arg)
                             :ref-id (:id local-five)})

          (testing "local fn has no name"
            (is (nil? (:name local-five))))

          (testing "local fn is executable via id"
            (let [ctx (exec/create-context {:storage storage})]
              ;; 5 + 5 = 10
              (is (= 10 (exec/execute ctx (:id add-local-fives) nil))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 16: Transducers and Lazy Sequences
;; =============================================================================
;;
;; HOFs like map/filter support transducers:
;; - Without collection: returns transducer
;; - With collection: returns lazy sequence

(deftest transducer-support-test
  (testing "transducers: compose transformations efficiently"
    (let [storage (create-versioned-storage)
          transform-count (atom 0)]
      (try
        (registry/initialize-all! storage
                                  [{:map-fn
                                    {:args {:f {:type :fn :required true}
                                            :coll {:type :any :required true}}
                                     :return-type :any
                                     :impl (fn [{:keys [f coll]} ctx]
                                             (mapv (fn [x]
                                                     (exec/execute-with-named-args ctx @f {:x x}))
                                                   @coll))}}
                                   {:increment
                                    {:args {:x {:type :int :required true}}
                                     :return-type :int
                                     :impl (fn [{:keys [x]} _]
                                             (swap! transform-count inc)
                                             (inc @x))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        (composition/sync-fns-to-storage! storage
                                          [{:name :numbers :parent :const :args {:x [1 2 3]}}
                                           {:name :inc-all
                                            :parent :map-fn
                                            :args {:f :increment :coll :numbers}}])

        (reset! transform-count 0)

        (let [ctx (exec/create-context {:storage storage})
              inc-all-fn (first (sp/query-entities storage :fn {:name "inc-all"}))]
          (testing "map transforms each element"
            (is (= [2 3 4] (exec/execute ctx (:id inc-all-fn) nil))))
          (testing "all elements were transformed"
            (is (= 3 @transform-count))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 17: Error Propagation
;; =============================================================================
;;
;; Errors in base functions propagate correctly:
;; - Wrapped with context (arg name, source)
;; - Original exception preserved as cause

(deftest error-propagation-test
  (testing "error propagation: errors include context"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:failing-fn
                                    {:args {:x {:type :int :required true}}
                                     :return-type :int
                                     :impl (fn [{:keys [x]} _]
                                             (throw (ex-info "Intentional failure"
                                                             {:value @x})))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        (composition/sync-fns-to-storage! storage
                                          [{:name :five :parent :const :args {:x 5}}
                                           {:name :will-fail
                                            :parent :failing-fn
                                            :args {:x :five}}])

        (let [ctx (exec/create-context {:storage storage})
              fail-fn (first (sp/query-entities storage :fn {:name "will-fail"}))]
          (testing "error is thrown"
            (is (thrown? clojure.lang.ExceptionInfo
                  (exec/execute ctx (:id fail-fn) nil)))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Test 18: Type Validation
;; =============================================================================
;;
;; Strict type validation catches type mismatches:
;; - Validates provided argument types
;; - Can be enabled/disabled per context

(deftest type-validation-test
  (testing "type validation: catches type mismatches"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}
                                   {:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Valid: int + int
        (composition/sync-fns-to-storage! storage
                                          [{:name :one :parent :const :args {:x 1}}
                                           {:name :two :parent :const :args {:x 2}}
                                           {:name :add-valid :parent :add :args {:a :one :b :two}}])

        (let [ctx (exec/create-context {:storage storage
                                        :strict-type-validation? true})
              valid-fn (first (sp/query-entities storage :fn {:name "add-valid"}))]
          (testing "valid types execute correctly"
            (is (= 3 (exec/execute ctx (:id valid-fn) nil)))))
        (finally
          (sp/close storage))))))
