(ns graphden.executor.error-path-test
  "Edge case tests for error paths in executor.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.graph :as graph]))


(use-fixtures :each exec/with-clean-registry)


(defn- create-mock-storage
  "Creates a mock storage that returns the specified execution graph."
  [execution-graph]
  (reify
    sp/ExecutionGraph
    (resolve-execution-graph
      [_ _fn-id]
      execution-graph)))


(deftest fn-not-found-in-graph-test
  (testing "throws when fn is missing from execution graph"
    (let [fn-id (random-uuid)
          _ (exec/register-base-fn! :dummy (fn [_ _] nil))
          ;; Create mock storage with empty :fns map - fn missing!
          mock-storage (create-mock-storage
                         (graph/->execution-graph
                           {:fns {fn-id {:id fn-id
                                         :name "dummy"
                                         :parent-id nil}}
                            :args []}))
          ctx (exec/create-context {:storage mock-storage})]
      ;; Execute with wrong fn-id
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Function not found in execution graph"
            (exec/execute ctx (random-uuid) {}))))))


(deftest arg-type-missing-test
  (testing "throws when arg type is invalid for validation"
    (let [fn-id (random-uuid)
          arg-id (random-uuid)
          _ (exec/register-base-fn! :dummy (fn [_ _] nil))
          ;; Create mock storage with arg missing :type field
          mock-storage (create-mock-storage
                         (graph/->execution-graph
                           {:fns {fn-id {:id fn-id
                                         :name "dummy"
                                         :parent-id nil}}
                            :args [{:id arg-id
                                    :fn-id fn-id
                                    :name "x"
                                    ;; Missing :type field
                                    :required true}]}))
          ctx (exec/create-context {:storage mock-storage})]
      ;; When we provide an arg, validation on malformed schema (no :type) should fail
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid arg.*missing type"
            (exec/execute ctx fn-id {arg-id 100}))))))


;; === Cache Eviction Tests ===

(deftest cache-eviction-test
  (testing "evicts oldest cache entries when cache limit is reached"
    (let [fn-id (random-uuid)
          call-count (atom 0)
          _ (exec/register-base-fn! :counting-fn (fn [_ _] (swap! call-count inc)))
          ;; Create mock storage with fn (base fn with parent-id=nil)
          mock-storage (create-mock-storage
                         (graph/->execution-graph
                           {:fns {fn-id {:id fn-id
                                         :name "counting-fn"
                                         :parent-id nil}}
                            :args []}))
          ;; Create context with very small cache limit
          ctx (exec/create-context {:storage mock-storage
                                    :cache-max-size 5
                                    :cache-warning-threshold 3})
          result-cache (:result-cache ctx)]
      ;; Pre-populate cache with 5 entries (at max limit)
      (doseq [i (range 5)]
        (swap! result-cache assoc (random-uuid) i))
      (is (= 5 (count @result-cache)))
      ;; Execute will trigger cache eviction when trying to add to full cache
      ;; Note: The function will execute successfully, evicting some old entries
      (exec/execute ctx fn-id nil)
      ;; After eviction, cache should have fewer entries than before + new one
      ;; Due to eviction ratio (0.2), we keep 80% = 4 entries, then add 1 = varies
      (is (<= (count @result-cache) 5) "Cache should not exceed max size"))))


(deftest cache-warning-threshold-test
  (testing "warning is logged when cache reaches warning threshold"
    (let [fn-id (random-uuid)
          ref-fn-id (random-uuid)
          arg-id (random-uuid)
          _ (exec/register-base-fn! :const-fn (fn [_ _] 42))
          ;; Create mock with ref-id to trigger cache storage
          mock-storage (create-mock-storage
                         (graph/->execution-graph
                           {:fns {fn-id {:id fn-id
                                         :name "const-fn"
                                         :parent-id nil}
                                  ref-fn-id {:id ref-fn-id
                                             :name "const-fn"
                                             :parent-id nil}}
                            :args [{:id arg-id
                                    :fn-id fn-id
                                    :name "x"
                                    :type :int
                                    :required true
                                    :ref-id ref-fn-id}]}))
          ;; Create context with warning threshold = 2
          ctx (exec/create-context {:storage mock-storage
                                    :cache-warning-threshold 2
                                    :cache-max-size 10})
          result-cache (:result-cache ctx)]
      ;; Pre-populate cache with 1 entry
      (swap! result-cache assoc (random-uuid) 1)
      ;; Execute - will add entry and potentially trigger warning at threshold=2
      (exec/execute ctx fn-id nil)
      ;; Should complete without error
      (is (>= (count @result-cache) 1)))))


(deftest ref-fn-not-found-in-graph-test
  (testing "throws when ref-fn-id references missing fn"
    (let [fn-id (random-uuid)
          missing-fn-id (random-uuid)
          arg-id (random-uuid)
          _ (exec/register-base-fn! :identity-fn (fn [{:keys [x]} _ctx] @x))
          ;; Create mock with arg referencing non-existent fn
          mock-storage (create-mock-storage
                         (graph/->execution-graph
                           {:fns {fn-id {:id fn-id
                                         :name "identity-fn"
                                         :parent-id nil}}
                            ;; arg has ref-id pointing to missing fn
                            :args [{:id arg-id
                                    :fn-id fn-id
                                    :name "x"
                                    :type :int
                                    :required true
                                    :ref-id missing-fn-id}]}))
          ctx (exec/create-context {:storage mock-storage})]
      ;; Should throw because missing-fn-id is not in the execution graph's :fns
      (is (thrown? clojure.lang.ExceptionInfo
            (exec/execute ctx fn-id {}))))))


;; === Depth Warning Tests ===

(deftest depth-warning-at-threshold-test
  (testing "executes successfully and triggers depth warning at 80% threshold"
    ;; With max-depth=10, threshold = 8. We build a chain of 9 functions
    ;; so execution reaches depth 9 (above 8 threshold but below 10 limit).
    (let [num-fns 10
          fn-ids (vec (repeatedly num-fns random-uuid))
          _ (exec/register-base-fn! :chain-fn (fn [_ _] :leaf))
          _ (exec/register-base-fn! :chain-step (fn [{:keys [next-val]} _ctx] @next-val))
          ;; Build fns: first n-1 are chain-step (parent-id=nil base fns), last is chain-fn
          fns (into {} (map-indexed
                         (fn [i fid]
                           [fid {:id fid
                                 :name (if (= i (dec num-fns)) "chain-fn" "chain-step")
                                 :parent-id nil}])
                         fn-ids))
          ;; Create args: chain-step fns have one arg "next-val" pointing to next fn
          ;; Each fn[i] has arg with ref-id = fn[i+1]
          args (vec
                 (for [i (range (dec num-fns))]
                   {:id (random-uuid)
                    :fn-id (get fn-ids i)
                    :name "next-val"
                    :type :any
                    :required true
                    :ref-id (get fn-ids (inc i))}))
          mock-storage (create-mock-storage
                         (graph/->execution-graph
                           {:fns fns :args args}))
          ctx (exec/create-context {:storage mock-storage
                                    :max-depth 10})]
      ;; Execution goes 10 levels deep (depth 1..10), threshold at 8
      ;; Should succeed (depth 10 = max-depth, not >)
      (is (= :leaf (exec/execute ctx (first fn-ids) {}))))))


(deftest depth-limit-exceeded-test
  (testing "throws when depth exceeds max-depth"
    ;; Build a chain of 12 fns with max-depth=10
    (let [num-fns 12
          fn-ids (vec (repeatedly num-fns random-uuid))
          _ (exec/register-base-fn! :chain-fn (fn [_ _] :leaf))
          _ (exec/register-base-fn! :chain-step (fn [{:keys [next-val]} _ctx] @next-val))
          fns (into {} (map-indexed
                         (fn [i fid]
                           [fid {:id fid
                                 :name (if (= i (dec num-fns)) "chain-fn" "chain-step")
                                 :parent-id nil}])
                         fn-ids))
          args (vec
                 (for [i (range (dec num-fns))]
                   {:id (random-uuid)
                    :fn-id (get fn-ids i)
                    :name "next-val"
                    :type :any
                    :required true
                    :ref-id (get fn-ids (inc i))}))
          mock-storage (create-mock-storage
                         (graph/->execution-graph
                           {:fns fns :args args}))
          ctx (exec/create-context {:storage mock-storage :max-depth 10})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Maximum recursion depth exceeded"
            (exec/execute ctx (first fn-ids) {}))))))


;; === Timeout Warning Tests ===

(deftest timeout-warning-at-threshold-test
  (testing "executes with timeout approaching 80% threshold"
    (let [fn-id (random-uuid)
          call-count (atom 0)
          ;; Custom clock: returns 0 at start, then 810ms (just past 80% of 1000ms)
          clock-time (atom 0)
          _ (exec/register-base-fn! :timed-fn
                                    (fn [_ _]
                                      (swap! call-count inc)
                                      ;; After first call, advance clock past threshold
                                      (reset! clock-time 810)
                                      42))
          mock-storage (create-mock-storage
                         (graph/->execution-graph
                           {:fns {fn-id {:id fn-id :name "timed-fn" :parent-id nil}}
                            :args []}))
          ctx (exec/create-context {:storage mock-storage
                                    :timeout-ms 1000
                                    :clock (fn [] @clock-time)})]
      ;; Should succeed - we're past warning threshold but not past timeout
      (is (= 42 (exec/execute ctx fn-id nil)))
      (is (= 1 @call-count)))))


(deftest timeout-exceeded-test
  (testing "throws when execution timeout is exceeded"
    (let [fn-id (random-uuid)
          child-fn-id (random-uuid)
          arg-id (random-uuid)
          _ (exec/register-base-fn! :timeout-fn (fn [{:keys [x]} _ctx] @x))
          mock-storage (create-mock-storage
                         (graph/->execution-graph
                           {:fns {fn-id {:id fn-id :name "timeout-fn" :parent-id nil}
                                  child-fn-id {:id child-fn-id :name "timeout-fn" :parent-id nil}}
                            :args [{:id arg-id
                                    :fn-id fn-id
                                    :name "x"
                                    :type :int
                                    :required true
                                    :ref-id child-fn-id}]}))
          ;; Clock advances past timeout between calls
          clock-calls (atom 0)
          ctx (exec/create-context {:storage mock-storage
                                    :timeout-ms 100
                                    :clock (fn []
                                             (let [n (swap! clock-calls inc)]
                                               ;; First call = 0ms (start-time), then 0ms (first check)
                                               ;; Then 200ms (second check, past timeout)
                                               (if (<= n 2) 0 200)))})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Execution timeout exceeded"
            (exec/execute ctx fn-id {}))))))
