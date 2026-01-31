(ns graphden.executor.error-path-test
  "Edge case tests for error paths in executor."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


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
                          ;; No resolved-args - arg is free so provided-arg triggers validation
                          :resolved-args {fn-id {}}})
          ctx (exec/create-context {:storage mock-storage})]
      ;; When we provide an arg, validation on malformed schema (no :type) should fail
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid arg-schema: missing type"
            (exec/execute ctx fn-id {bad-arg-schema-id 100}))))))


;; === Cache Eviction Tests ===

(deftest cache-eviction-test
  (testing "evicts oldest cache entries when cache limit is reached"
    ;; This tests the evict-cache-entries! function at lines 133-147 of core.clj
    ;; and check-cache-limit! at lines 150-167
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          call-count (atom 0)
          _ (exec/register-base-fn! :counting-fn (fn [_ _] (swap! call-count inc)))
          ;; Create mock storage with fn and schema
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "counting-fn"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {fn-schema-id {:id fn-schema-id
                                                     :name "counting-fn"
                                                     :returned-type :int}}
                          :arg-schemas {}
                          :resolved-args {}
                          :call-sites {}})
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
    ;; This tests the warning path at lines 199-204 of core.clj
    ;; We can't easily verify logging, but we ensure no crash at threshold
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          cs-id (random-uuid)
          _ (exec/register-base-fn! :const-fn (fn [_ _] 42))
          ;; Create mock with call-site to trigger cache storage
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "const-fn"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {fn-schema-id {:id fn-schema-id
                                                     :name "const-fn"
                                                     :returned-type :int}}
                          :arg-schemas {}
                          :resolved-args {}
                          :call-sites {cs-id {:id cs-id :fn-id fn-id :name "result"}}})
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


(deftest call-site-not-found-in-graph-test
  (testing "throws when call-site-id is missing from execution graph"
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          missing-cs-id (random-uuid)
          _ (exec/register-base-fn! :identity-fn (fn [{:keys [x]} _ctx] @x))
          ;; Create mock with call-site UUID in resolved-args AND in call-sites map
          ;; to pass build-uuid-ref-delay check, but the call-site record has
          ;; a fn-id that references a non-existent fn (will trigger error in execute)
          mock-storage (create-mock-storage
                         (let [child-fn-id (random-uuid)]
                           {:fns {fn-id {:id fn-id
                                         :name "identity-fn"
                                         :fn-schema-id fn-schema-id}}
                            :fn-schemas {fn-schema-id {:id fn-schema-id
                                                       :name "identity-fn"
                                                       :returned-type :int}}
                            :arg-schemas {arg-schema-id {:id arg-schema-id
                                                         :fn-schema-id fn-schema-id
                                                         :name "x"
                                                         :type :int
                                                         :required true}}
                            ;; resolved-arg value is a call-site UUID (wrapped in arg-value record)
                            :resolved-args {fn-id {arg-schema-id {:value missing-cs-id}}}
                            ;; call-site exists but references a fn not in :fns
                            :call-sites {missing-cs-id {:id missing-cs-id
                                                        :fn-id child-fn-id
                                                        :name "result"}}}))
          ctx (exec/create-context {:storage mock-storage})]
      ;; Should throw because child-fn-id is not in the execution graph's :fns
      (is (thrown? clojure.lang.ExceptionInfo
            (exec/execute ctx fn-id {}))))))


;; === Depth Warning Tests ===

(deftest depth-warning-at-threshold-test
  (testing "executes successfully and triggers depth warning at 80% threshold"
    ;; With max-depth=10, threshold = 8. We build a chain of 9 call-sites
    ;; so execution reaches depth 9 (above 8 threshold but below 10 limit).
    (let [;; Create a chain: fn-0 -> cs-0 -> fn-1 -> cs-1 -> ... -> fn-9 (leaf)
          num-fns 10
          fn-ids (vec (repeatedly num-fns random-uuid))
          schema-id (random-uuid)
          _ (exec/register-base-fn! :chain-fn (fn [_ _] :leaf))
          _ (exec/register-base-fn! :chain-step (fn [{:keys [next-val]} _ctx] @next-val))
          ;; Build graph: fns, schemas, call-sites
          fns (into {} (map-indexed
                         (fn [i fid]
                           [fid {:id fid
                                 :name (if (= i (dec num-fns)) "chain-fn" "chain-step")
                                 :fn-schema-id schema-id}])
                         fn-ids))
          ;; Leaf fn has no args; step fns have one arg "next-val"
          leaf-schema-id (random-uuid)
          step-schema-id schema-id
          arg-schema-id (random-uuid)
          fn-schemas {step-schema-id {:id step-schema-id
                                      :name "chain-step"
                                      :returned-type :any}
                      leaf-schema-id {:id leaf-schema-id
                                      :name "chain-fn"
                                      :returned-type :any}}
          ;; Update leaf fn to use leaf-schema
          fns (assoc-in fns [(last fn-ids) :fn-schema-id] leaf-schema-id)
          ;; Create call-sites: cs-i points to fn-(i+1)
          cs-ids (vec (repeatedly (dec num-fns) random-uuid))
          call-sites (into {} (map-indexed
                                (fn [i csid]
                                  [csid {:id csid
                                         :fn-id (get fn-ids (inc i))
                                         :name (str "cs-" i)}])
                                cs-ids))
          ;; resolved-args: each step fn's next-val = call-site UUID
          resolved-args (into {}
                              (map-indexed
                                (fn [i fid]
                                  (if (< i (dec num-fns))
                                    [fid {arg-schema-id {:value (get cs-ids i)}}]
                                    [fid {}]))
                                fn-ids))
          arg-schemas {arg-schema-id {:id arg-schema-id
                                      :fn-schema-id step-schema-id
                                      :name "next-val"
                                      :type :any
                                      :required true}}
          mock-storage (create-mock-storage
                         {:fns fns
                          :fn-schemas fn-schemas
                          :arg-schemas arg-schemas
                          :resolved-args resolved-args
                          :call-sites call-sites})
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
          schema-id (random-uuid)
          leaf-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          _ (exec/register-base-fn! :chain-fn (fn [_ _] :leaf))
          _ (exec/register-base-fn! :chain-step (fn [{:keys [next-val]} _ctx] @next-val))
          fns (into {} (map-indexed
                         (fn [i fid]
                           [fid {:id fid
                                 :name (if (= i (dec num-fns)) "chain-fn" "chain-step")
                                 :fn-schema-id (if (= i (dec num-fns)) leaf-schema-id schema-id)}])
                         fn-ids))
          fn-schemas {schema-id {:id schema-id :name "chain-step" :returned-type :any}
                      leaf-schema-id {:id leaf-schema-id :name "chain-fn" :returned-type :any}}
          cs-ids (vec (repeatedly (dec num-fns) random-uuid))
          call-sites (into {} (map-indexed
                                (fn [i csid]
                                  [csid {:id csid :fn-id (get fn-ids (inc i)) :name (str "cs-" i)}])
                                cs-ids))
          resolved-args (into {}
                              (map-indexed
                                (fn [i fid]
                                  (if (< i (dec num-fns))
                                    [fid {arg-schema-id {:value (get cs-ids i)}}]
                                    [fid {}]))
                                fn-ids))
          arg-schemas {arg-schema-id {:id arg-schema-id
                                      :fn-schema-id schema-id
                                      :name "next-val" :type :any :required true}}
          mock-storage (create-mock-storage
                         {:fns fns :fn-schemas fn-schemas :arg-schemas arg-schemas
                          :resolved-args resolved-args :call-sites call-sites})
          ctx (exec/create-context {:storage mock-storage :max-depth 10})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Maximum recursion depth exceeded"
            (exec/execute ctx (first fn-ids) {}))))))


;; === Timeout Warning Tests ===

(deftest timeout-warning-at-threshold-test
  (testing "executes with timeout approaching 80% threshold"
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
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
                         {:fns {fn-id {:id fn-id :name "timed-fn" :fn-schema-id fn-schema-id}}
                          :fn-schemas {fn-schema-id {:id fn-schema-id
                                                     :name "timed-fn"
                                                     :returned-type :int}}
                          :arg-schemas {}
                          :resolved-args {}
                          :call-sites {}})
          ctx (exec/create-context {:storage mock-storage
                                    :timeout-ms 1000
                                    :clock (fn [] @clock-time)})]
      ;; Should succeed - we're past warning threshold but not past timeout
      (is (= 42 (exec/execute ctx fn-id nil)))
      (is (= 1 @call-count)))))


(deftest timeout-exceeded-test
  (testing "throws when execution timeout is exceeded"
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          cs-id (random-uuid)
          child-fn-id (random-uuid)
          _ (exec/register-base-fn! :timeout-fn (fn [{:keys [x]} _ctx] @x))
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id :name "timeout-fn" :fn-schema-id fn-schema-id}
                                child-fn-id {:id child-fn-id :name "timeout-fn" :fn-schema-id fn-schema-id}}
                          :fn-schemas {fn-schema-id {:id fn-schema-id
                                                     :name "timeout-fn"
                                                     :returned-type :int}}
                          :arg-schemas {arg-schema-id {:id arg-schema-id
                                                       :fn-schema-id fn-schema-id
                                                       :name "x" :type :int :required true}}
                          :resolved-args {fn-id {arg-schema-id {:value cs-id}}
                                          child-fn-id {}}
                          :call-sites {cs-id {:id cs-id :fn-id child-fn-id :name "r"}}})
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
