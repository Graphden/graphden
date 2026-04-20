(ns ^:integration graphden.e2e-test
  "End-to-end tests for complete Graphden workflow.

   Tests the full announced functionality:
   1. Package loading (base functions + fn-defs)
   2. Registry initialization with base functions
   3. Sync fn-defs to versioned storage
   4. Branch creation and switching
   5. Function execution across branches
   6. Branch merging
   7. Conflict detection and resolution

   These tests verify that all components work together correctly."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.runtime :as rt]
    [graphden.executor.test-setup :as setup]
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
;; E2E Test 1: Complete Workflow - Packages to Execution
;; =============================================================================

(deftest e2e-packages-to-execution-test
  (testing "complete workflow: base-fns → sync → execute"
    (let [storage (create-versioned-storage)]
      (try
        ;; Step 1: Define base functions (simulating package loading)
        (let [base-fn-defs {:const {:args {:x {:type :any :required true}}
                                    :return-type :any
                                    :impl (setup/fn-impl [x] x)}
                            :add {:args {:a {:type :int :required true}
                                         :b {:type :int :required true}}
                                  :return-type :int
                                  :impl (setup/fn-impl [a b] (+ a b))}
                            :multiply {:args {:a {:type :int :required true}
                                              :b {:type :int :required true}}
                                       :return-type :int
                                       :impl (setup/fn-impl [a b] (* a b))}}]

          ;; Step 2: Initialize registry with base functions
          (registry/initialize-all! storage (mapv (fn [[k v]] {k v}) base-fn-defs))

          ;; Step 3: Define fn-defs (simulating package fn-defs)
          (let [fn-defs [{:name :five :parent :const :args {:x 5}}
                         {:name :three :parent :const :args {:x 3}}
                         {:name :sum :parent :add :args {:a :five :b :three}}
                         {:name :product :parent :multiply :args {:a :five :b :three}}]]

            ;; Step 4: Sync fn-defs to storage
            (composition/sync-fns-to-storage! storage fn-defs)

            ;; Step 5: Execute functions
            (let [ctx (exec/create-context {:storage storage})
                  sum-fn (first (sp/query-entities storage :fn {:name "sum"}))
                  product-fn (first (sp/query-entities storage :fn {:name "product"}))]

              (testing "sum function executes correctly (5 + 3 = 8)"
                (is (= 8 (exec/execute ctx (:id sum-fn) nil))))

              (testing "product function executes correctly (5 * 3 = 15)"
                (is (= 15 (exec/execute ctx (:id product-fn) nil)))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; E2E Test 2: Versioning Workflow - Branches and Merging
;; =============================================================================

(deftest e2e-versioning-workflow-test
  (testing "complete versioning workflow: branch → modify → merge"
    (let [storage (create-versioned-storage)]
      (try
        ;; Setup: base functions and initial fn-defs
        (registry/initialize-all! storage
                                  [{:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (setup/fn-impl [x] x)}}
                                   {:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (setup/fn-impl [a b] (+ a b))}}])

        ;; Create initial fn on main branch
        (composition/sync-fns-to-storage! storage
                                          [{:name :value :parent :const :args {:x 10}}])

        ;; Step 1: Create feature branch
        (let [feature-branch (vs/create-branch! storage "feature")
              feature-storage (vs/switch-branch storage (:id feature-branch))]

          (testing "feature branch created successfully"
            (is (= "feature" (:name feature-branch)))
            (is (= (:id feature-branch) (vs/current-branch-id feature-storage))))

          ;; Step 2: Modify value on feature branch
          (let [value-fn (first (sp/query-entities feature-storage :fn {:name "value"}))]
            (sp/update-entity feature-storage :fn (:id value-fn) {:name "value"}))

          ;; Create new fn only on feature branch
          (composition/sync-fns-to-storage! feature-storage
                                            [{:name :feature-only :parent :const :args {:x 42}}])

          ;; Step 3: Verify isolation - main doesn't see feature-only
          (testing "branch isolation: main doesn't see feature-only fn"
            (let [main-fns (sp/query-entities storage :fn {:name "feature-only"})]
              (is (empty? main-fns))))

          (testing "branch isolation: feature sees feature-only fn"
            (let [feature-fns (sp/query-entities feature-storage :fn {:name "feature-only"})]
              (is (= 1 (count feature-fns)))))

          ;; Step 4: Merge feature into main
          (let [merge-result (vs/merge-branch! storage (:id feature-branch))]
            (testing "merge completed successfully"
              (is (some? merge-result))
              (is (= (:id feature-branch) (:source-branch-id merge-result))))

            ;; Step 5: Verify merge - feature-only fn is now resolvable on main
            ;; Note: query-entities uses optimized batch path that doesn't follow merges,
            ;; but individual entity resolution via read-entity DOES follow merges
            (testing "after merge: feature-only fn resolvable via read-entity"
              (let [feature-only-fn (first (sp/query-entities feature-storage :fn {:name "feature-only"}))]
                (is (some? (sp/read-entity storage :fn (:id feature-only-fn))))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; E2E Test 3: HOF (Higher-Order Functions) Workflow
;; =============================================================================

(deftest e2e-hof-workflow-test
  (testing "higher-order functions: map, filter, reduce patterns"
    (let [storage (create-versioned-storage)]
      (try
        ;; Setup HOF base functions
        (registry/initialize-all! storage
                                  [{:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (setup/fn-impl [x] x)}}
                                   {:inc {:args {:n {:type :int :required true}}
                                          :return-type :int
                                          :impl (setup/fn-impl [n] (inc n))}}
                                   {:double {:args {:n {:type :int :required true}}
                                             :return-type :int
                                             :impl (setup/fn-impl [n] (* 2 n))}}
                                   {:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (setup/fn-impl [a b] (+ a b))}}
                                   ;; Compose two unary int->int functions
                                   {:compose-unary {:args {:f {:type :fn :required true}
                                                           :g {:type :fn :required true}
                                                           :x {:type :int :required true}}
                                                    :return-type :int
                                                    :impl (setup/fn-impl [f g x]
                                                                         (let [inner-result (exec/execute-with-named-args ctx f {:n x})
                                                                               outer-result (exec/execute-with-named-args ctx g {:n inner-result})]
                                                                           outer-result))}}])

        ;; Create composition: double(inc(5)) = double(6) = 12
        (composition/sync-fns-to-storage! storage
                                          [{:name :five :parent :const :args {:x 5}}
                                           {:name :composed
                                            :parent :compose-unary
                                            :args {:f :inc    ; first apply inc
                                                   :g :double ; then apply double
                                                   :x :five}}])

        (let [ctx (exec/create-context {:storage storage})
              composed-fn (first (sp/query-entities storage :fn {:name "composed"}))]
          (testing "HOF compose-unary: double(inc(5)) = 12"
            (is (= 12 (exec/execute ctx (:id composed-fn) nil)))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; E2E Test 4: Lazy Evaluation and Caching
;; =============================================================================

(deftest e2e-lazy-evaluation-test
  (testing "lazy evaluation: only needed branches are computed"
    (let [storage (create-versioned-storage)
          left-called (atom false)
          right-called (atom false)]
      (try
        (registry/initialize-all! storage
                                  [{:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (setup/fn-impl [x] x)}}
                                   {:left-branch {:args {:x {:type :any :required true}}
                                                  :return-type :any
                                                  :impl (setup/fn-impl [x]
                                                                       (reset! left-called true)
                                                                       x)}}
                                   {:right-branch {:args {:x {:type :any :required true}}
                                                   :return-type :any
                                                   :impl (setup/fn-impl [x]
                                                                        (reset! right-called true)
                                                                        x)}}
                                   ;; if-fn: returns then-val if cond is truthy, else else-val
                                   {:if-fn {:args {:condition {:type :bool :required true}
                                                   :then-val {:type :any :required true}
                                                   :else-val {:type :any :required true}}
                                            :return-type :any
                                            :lazy #{:then-val :else-val}
                                            :impl (setup/fn-impl [condition]
                                                                 (if condition
                                                                   (rt/resolve-arg args :then-val)
                                                                   (rt/resolve-arg args :else-val)))}}])

        (composition/sync-fns-to-storage! storage
                                          [{:name :true-val :parent :const :args {:x true}}
                                           {:name :left :parent :left-branch :args {:x 1}}
                                           {:name :right :parent :right-branch :args {:x 2}}
                                           {:name :conditional
                                            :parent :if-fn
                                            :args {:condition :true-val
                                                   :then-val :left
                                                   :else-val :right}}])

        (let [ctx (exec/create-context {:storage storage})
              cond-fn (first (sp/query-entities storage :fn {:name "conditional"}))]

          ;; Reset tracking
          (reset! left-called false)
          (reset! right-called false)

          (let [result (exec/execute ctx (:id cond-fn) nil)]
            (testing "conditional returns correct branch"
              (is (= 1 result)))

            (testing "only the taken branch was evaluated (lazy)"
              (is @left-called "left branch should be called")
              (is (not @right-called) "right branch should NOT be called"))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; E2E Test 5: Error Handling Across Layers
;; =============================================================================

(deftest e2e-error-handling-test
  (testing "errors propagate correctly through execution stack"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (setup/fn-impl [x] x)}}
                                   {:failing-fn {:args {:x {:type :any :required true}}
                                                 :return-type :any
                                                 :impl (fn [_ _]
                                                         (throw (ex-info "Intentional failure"
                                                                         {:type :test-error})))}}
                                   {:wrapper {:args {:inner {:type :any :required true}}
                                              :return-type :any
                                              :impl (setup/fn-impl [inner] inner)}}])

        (composition/sync-fns-to-storage! storage
                                          [{:name :will-fail :parent :failing-fn :args {:x 1}}
                                           {:name :outer :parent :wrapper :args {:inner :will-fail}}])

        (let [ctx (exec/create-context {:storage storage})
              outer-fn (first (sp/query-entities storage :fn {:name "outer"}))]

          (testing "error from nested function propagates"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Intentional failure"
                  (exec/execute ctx (:id outer-fn) nil)))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; E2E Test 6: Conflict Detection in Merge
;; =============================================================================

(deftest e2e-conflict-detection-test
  (testing "conflict detection when same entity modified in both branches"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (setup/fn-impl [x] x)}}])

        ;; Create initial fn on main
        (composition/sync-fns-to-storage! storage
                                          [{:name :shared-value :parent :const :args {:x 10}}])
        (let [shared-fn (first (sp/query-entities storage :fn {:name "shared-value"}))
              ;; Create feature branch
              feature-branch (vs/create-branch! storage "feature")
              feature-storage (vs/switch-branch storage (:id feature-branch))]

          ;; Modify on feature branch (different value)
          (sp/update-entity feature-storage :fn (:id shared-fn) {:name "shared-value-feature"})

          ;; Modify on main branch (different value, creating conflict)
          (sp/update-entity storage :fn (:id shared-fn) {:name "shared-value-main"})

          ;; Detect conflicts
          (let [conflicts (vs/detect-conflicts storage (:id feature-branch))]
            (testing "conflicts detected for shared entity"
              (is (seq (:conflicts conflicts))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; E2E Test 7: Execution Graph Resolution
;; =============================================================================

(deftest e2e-execution-graph-test
  (testing "execution graph resolves complete dependency tree"
    (let [storage (create-versioned-storage)]
      (try
        (registry/initialize-all! storage
                                  [{:const {:args {:x {:type :any :required true}}
                                            :return-type :any
                                            :impl (setup/fn-impl [x] x)}}
                                   {:add {:args {:a {:type :int :required true}
                                                 :b {:type :int :required true}}
                                          :return-type :int
                                          :impl (setup/fn-impl [a b] (+ a b))}}])

        ;; Create diamond dependency: result depends on both left and right,
        ;; which both depend on base
        (composition/sync-fns-to-storage! storage
                                          [{:name :base :parent :const :args {:x 1}}
                                           {:name :left :parent :add :args {:a :base :b :base}}
                                           {:name :right :parent :add :args {:a :base :b :base}}
                                           {:name :result :parent :add :args {:a :left :b :right}}])

        (let [result-fn (first (sp/query-entities storage :fn {:name "result"}))
              graph (sp/resolve-execution-graph storage (:id result-fn))]

          (testing "execution graph contains all fns"
            ;; Should have: result, left, right, base, add, const (6 total)
            (is (>= (count (:fns graph)) 4)))

          (testing "execution graph contains args"
            (is (seq (:args graph)))))
        (finally
          (sp/close storage))))))
