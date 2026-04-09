(ns graphden.storage.protocol.graph-test
  "Tests for storage-protocol.graph - ExecutionGraph utilities and BFS algorithm.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.graph :as graph]))


;; === Constants tests ===

(deftest constants-test
  (testing "default-query-timeout-ms is 30 seconds"
    (is (= 30000 graph/default-query-timeout-ms)))

  (testing "default-max-depth is 1000"
    (is (= 1000 graph/default-max-depth)))

  (testing "default-max-unknown-types is 10"
    (is (= 10 graph/default-max-unknown-types)))

  (testing "*max-graph-iterations* default is 10000"
    (is (= 10000 graph/*max-graph-iterations*))))


;; === with-max-graph-iterations tests ===

(deftest with-max-graph-iterations-test
  (testing "binds limit for duration of function"
    (is (= 500
           (graph/with-max-graph-iterations 500
                                            #(identity graph/*max-graph-iterations*)))))

  (testing "restores original limit after function"
    (let [original graph/*max-graph-iterations*]
      (graph/with-max-graph-iterations 999 #(identity nil))
      (is (= original graph/*max-graph-iterations*))))

  (testing "restores limit after exception"
    (let [original graph/*max-graph-iterations*]
      (try
        (graph/with-max-graph-iterations 999
                                         #(throw (ex-info "test" {})))
        (catch Exception _))
      (is (= original graph/*max-graph-iterations*))))

  (testing "returns value from body"
    (is (= 42
           (graph/with-max-graph-iterations 100 #(+ 40 2))))))


;; === check-graph-iteration-limit! tests ===

(deftest check-graph-iteration-limit!-test
  (testing "under limit doesn't throw"
    (is (nil? (graph/check-graph-iteration-limit! 0 (random-uuid))))
    (is (nil? (graph/check-graph-iteration-limit! 100 (random-uuid))))
    (is (nil? (graph/check-graph-iteration-limit! 9999 (random-uuid)))))

  (testing "at limit doesn't throw"
    (is (nil? (graph/check-graph-iteration-limit! 10000 (random-uuid)))))

  (testing "over limit throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"exceeded maximum iterations"
          (graph/check-graph-iteration-limit! 10001 (random-uuid)))))

  (testing "exception contains correct data"
    (let [fn-id (random-uuid)]
      (try
        (graph/check-graph-iteration-limit! 15000 fn-id)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/graph-too-large (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= 10000 (:max-iterations (ex-data e))))
          (is (= 15000 (:iteration-count (ex-data e))))))))

  (testing "respects dynamic binding"
    (let [fn-id (random-uuid)]
      (graph/with-max-graph-iterations 50
                                       #(do
                                          (is (nil? (graph/check-graph-iteration-limit! 50 fn-id)))
                                          (is (thrown? clojure.lang.ExceptionInfo
                                                (graph/check-graph-iteration-limit! 51 fn-id))))))))


;; === traverse-bfs tests ===

(deftest traverse-bfs-test
  (testing "returns start node with no neighbors"
    (is (= #{:a}
           (graph/traverse-bfs :a (constantly [])))))

  (testing "traverses single-level neighbors"
    (let [neighbors {:a [:b :c]
                     :b []
                     :c []}]
      (is (= #{:a :b :c}
             (graph/traverse-bfs :a #(get neighbors % []))))))

  (testing "traverses multi-level neighbors"
    (let [neighbors {:a [:b]
                     :b [:c]
                     :c [:d]
                     :d []}]
      (is (= #{:a :b :c :d}
             (graph/traverse-bfs :a #(get neighbors % []))))))

  (testing "handles cycles"
    (let [neighbors {:a [:b]
                     :b [:c]
                     :c [:a]}]  ; cycle back to :a
      (is (= #{:a :b :c}
             (graph/traverse-bfs :a #(get neighbors % []))))))

  (testing "handles diamond pattern"
    (let [neighbors {:a [:b :c]
                     :b [:d]
                     :c [:d]
                     :d []}]
      (is (= #{:a :b :c :d}
             (graph/traverse-bfs :a #(get neighbors % []))))))

  (testing "respects max-iterations option"
    (let [neighbors {:a [:b]
                     :b [:c]
                     :c [:d]
                     :d [:e]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"BFS traversal exceeded maximum iterations"
            (graph/traverse-bfs :a
                                #(get neighbors % [])
                                {:max-iterations 2})))))

  (testing "uses context-id in errors"
    ;; We need to create a graph that will exceed max-iterations
    ;; Each iteration discovers a new node, so with max-iterations 2
    ;; we need at least 3 new nodes to trigger the error
    (let [counter (atom 0)
          get-neighbors (fn [_]
                          (swap! counter inc)
                          [(keyword (str "node-" @counter))])]
      (try
        (graph/traverse-bfs :start get-neighbors {:max-iterations 2 :context-id :my-context})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :my-context (:context-id (ex-data e))))))))

  (testing "with UUIDs"
    (let [id-a (random-uuid)
          id-b (random-uuid)
          id-c (random-uuid)
          neighbors {id-a [id-b id-c]
                     id-b []
                     id-c []}]
      (is (= #{id-a id-b id-c}
             (graph/traverse-bfs id-a #(get neighbors % [])))))))


;; === try-parse-uuid tests ===

(deftest try-parse-uuid-test
  (testing "returns UUID for UUID input"
    (let [u (random-uuid)]
      (is (= u (graph/try-parse-uuid u)))))

  (testing "parses valid UUID string"
    (let [u (random-uuid)
          s (str u)]
      (is (= u (graph/try-parse-uuid s)))))

  (testing "returns nil for invalid UUID string"
    (is (nil? (graph/try-parse-uuid "not-a-uuid")))
    (is (nil? (graph/try-parse-uuid "")))
    (is (nil? (graph/try-parse-uuid "12345"))))

  (testing "returns nil for non-string non-UUID"
    (is (nil? (graph/try-parse-uuid nil)))
    (is (nil? (graph/try-parse-uuid 123)))
    (is (nil? (graph/try-parse-uuid :keyword)))
    (is (nil? (graph/try-parse-uuid [])))))


;; === ExecutionGraphResult tests ===

(deftest ->execution-graph-test
  (let [fn-id (random-uuid)
        parent-id (random-uuid)
        arg-id (random-uuid)]
    (testing "creates valid ExecutionGraphResult"
      (let [result (graph/->execution-graph
                     {:fns {fn-id {:id fn-id :name "test" :parent-ids [parent-id]}
                            parent-id {:id parent-id :name "base" :parent-ids nil}}
                      :args [{:id arg-id :fn-id fn-id :name "x" :type :int}]})]
        (is (graph/execution-graph? result))
        (is (= 2 (count (:fns result))))
        (is (= 1 (count (:args result))))))

    (testing "allows empty :args sequence"
      (let [result (graph/->execution-graph
                     {:fns {fn-id {:id fn-id}}
                      :args []})]
        (is (graph/execution-graph? result))
        (is (= [] (:args result)))))

    (testing "defaults :args to empty sequence"
      (let [result (graph/->execution-graph
                     {:fns {fn-id {:id fn-id}}})]
        (is (graph/execution-graph? result))
        (is (= [] (:args result)))))

    (testing "builds args-by-fn index"
      (let [fn-1-id (random-uuid)
            fn-2-id (random-uuid)
            arg-1-id (random-uuid)
            arg-2-id (random-uuid)
            arg-3-id (random-uuid)
            result (graph/->execution-graph
                     {:fns {fn-1-id {:id fn-1-id}
                            fn-2-id {:id fn-2-id}}
                      :args [{:id arg-1-id :fn-id fn-1-id :name "a"}
                             {:id arg-2-id :fn-id fn-1-id :name "b"}
                             {:id arg-3-id :fn-id fn-2-id :name "c"}]})]
        (is (= 2 (count (get-in result [:args-by-fn fn-1-id]))))
        (is (= 1 (count (get-in result [:args-by-fn fn-2-id]))))))

    (testing "throws when :fns is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fns map"
            (graph/->execution-graph
              {:fns []
               :args []}))))

    (testing "throws when :fns is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fns must contain"
            (graph/->execution-graph
              {:fns {}
               :args []}))))

    (testing "throws when :args is not a sequence"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :args sequence"
            (graph/->execution-graph
              {:fns {fn-id {:id fn-id}}
               :args "invalid"}))))))


(deftest execution-graph?-test
  (testing "returns true for ExecutionGraphResult"
    (let [result (graph/->execution-graph
                   {:fns {(random-uuid) {:id (random-uuid)}}
                    :args []})]
      (is (true? (graph/execution-graph? result)))))

  (testing "returns false for other types"
    (is (false? (graph/execution-graph? {})))
    (is (false? (graph/execution-graph? nil)))
    (is (false? (graph/execution-graph? [])))))


;; === extract-fn-refs-from-args tests ===

(deftest extract-fn-refs-from-args-test
  (testing "extracts ref-id references"
    (let [ref-fn-1 (random-uuid)
          ref-fn-2 (random-uuid)
          args [{:id (random-uuid) :fn-id (random-uuid) :name "a" :ref-id ref-fn-1}
                {:id (random-uuid) :fn-id (random-uuid) :name "b" :ref-id ref-fn-2}]]
      (is (= #{ref-fn-1 ref-fn-2}
             (graph/extract-fn-refs-from-args args)))))

  (testing "extracts UUID values (HOF pattern)"
    (let [ref-fn (random-uuid)
          args [{:id (random-uuid) :fn-id (random-uuid) :name "f" :value ref-fn :is-fn true}]]
      (is (= #{ref-fn}
             (graph/extract-fn-refs-from-args args)))))

  (testing "ignores non-UUID values"
    (let [args [{:id (random-uuid) :fn-id (random-uuid) :name "a" :value "string"}
                {:id (random-uuid) :fn-id (random-uuid) :name "b" :value 123}
                {:id (random-uuid) :fn-id (random-uuid) :name "c" :value nil}]]
      (is (= #{}
             (graph/extract-fn-refs-from-args args)))))

  (testing "handles empty sequence"
    (is (= #{} (graph/extract-fn-refs-from-args []))))

  (testing "handles mixed references"
    (let [ref-fn-1 (random-uuid)
          ref-fn-2 (random-uuid)
          args [{:id (random-uuid) :fn-id (random-uuid) :name "a" :ref-id ref-fn-1}
                {:id (random-uuid) :fn-id (random-uuid) :name "b" :value ref-fn-2}
                {:id (random-uuid) :fn-id (random-uuid) :name "c" :value 42}]]
      (is (= #{ref-fn-1 ref-fn-2}
             (graph/extract-fn-refs-from-args args)))))

  (testing "deduplicates shared references"
    (let [shared-ref (random-uuid)
          args [{:id (random-uuid) :fn-id (random-uuid) :name "a" :ref-id shared-ref}
                {:id (random-uuid) :fn-id (random-uuid) :name "b" :ref-id shared-ref}]]
      (is (= #{shared-ref}
             (graph/extract-fn-refs-from-args args))))))


;; === process-fn-node tests ===

(deftest process-fn-node-test
  (let [fn-id (random-uuid)
        parent-id (random-uuid)]

    (testing "returns empty when fn not found"
      (let [load-fn (constantly nil)
            load-args (constantly [])
            result (graph/process-fn-node load-fn load-args fn-id {} [])]
        (is (= {} (:fns result)))
        (is (= [] (:args result)))
        (is (= #{} (:new-fn-refs result)))))

    (testing "adds fn to graph when found"
      (let [fn-rec {:id fn-id :name "test" :parent-ids [parent-id]}
            load-fn (fn [id] (when (= id fn-id) fn-rec))
            load-args (constantly [])
            result (graph/process-fn-node load-fn load-args fn-id {} [])]
        (is (= fn-rec (get (:fns result) fn-id)))
        (is (contains? (:new-fn-refs result) parent-id))))

    (testing "includes args in result"
      (let [fn-rec {:id fn-id :name "test" :parent-ids nil}
            arg-1 {:id (random-uuid) :fn-id fn-id :name "a"}
            arg-2 {:id (random-uuid) :fn-id fn-id :name "b"}
            load-fn (fn [id] (when (= id fn-id) fn-rec))
            load-args (fn [id] (when (= id fn-id) [arg-1 arg-2]))
            result (graph/process-fn-node load-fn load-args fn-id {} [])]
        (is (= 2 (count (:args result))))
        (is (= [arg-1 arg-2] (:args result)))))

    (testing "extracts ref-id references"
      (let [ref-fn-id (random-uuid)
            fn-rec {:id fn-id :name "test" :parent-ids nil}
            args [{:id (random-uuid) :fn-id fn-id :name "x" :ref-id ref-fn-id}]
            load-fn (fn [id] (when (= id fn-id) fn-rec))
            load-args (fn [id] (when (= id fn-id) args))
            result (graph/process-fn-node load-fn load-args fn-id {} [])]
        (is (contains? (:new-fn-refs result) ref-fn-id))))

    (testing "accumulates with existing state"
      (let [existing-fn {:id parent-id :name "existing"}
            existing-arg {:id (random-uuid) :fn-id parent-id :name "y"}
            fn-rec {:id fn-id :name "test" :parent-ids nil}
            new-arg {:id (random-uuid) :fn-id fn-id :name "x"}
            load-fn (fn [id] (when (= id fn-id) fn-rec))
            load-args (fn [id] (when (= id fn-id) [new-arg]))
            result (graph/process-fn-node
                     load-fn load-args fn-id
                     {parent-id existing-fn}
                     [existing-arg])]
        (is (= 2 (count (:fns result))))
        (is (= 2 (count (:args result))))))))


;; === resolve-execution-graph-bfs tests ===

(deftest resolve-execution-graph-bfs-test
  (let [fn-id (random-uuid)
        parent-id (random-uuid)]

    (testing "resolves single function"
      (let [fn-rec {:id fn-id :name "test" :parent-ids nil}
            load-fn (fn [id] (when (= id fn-id) fn-rec))
            load-args (constantly [])
            result (graph/resolve-execution-graph-bfs load-fn load-args fn-id)]
        (is (graph/execution-graph? result))
        (is (= fn-rec (get (:fns result) fn-id)))))

    (testing "follows parent-id references"
      (let [fn-rec {:id fn-id :name "composed" :parent-ids [parent-id]}
            parent-rec {:id parent-id :name "base" :parent-ids nil}
            fns {fn-id fn-rec parent-id parent-rec}
            load-fn #(get fns %)
            load-args (constantly [])
            result (graph/resolve-execution-graph-bfs load-fn load-args fn-id)]
        (is (contains? (:fns result) fn-id))
        (is (contains? (:fns result) parent-id))))

    (testing "follows ref-id references"
      (let [ref-fn-id (random-uuid)
            fn-rec {:id fn-id :name "test" :parent-ids nil}
            ref-fn-rec {:id ref-fn-id :name "referenced" :parent-ids nil}
            fns {fn-id fn-rec ref-fn-id ref-fn-rec}
            args {fn-id [{:id (random-uuid) :fn-id fn-id :name "x" :ref-id ref-fn-id}]}
            load-fn #(get fns %)
            load-args #(get args % [])
            result (graph/resolve-execution-graph-bfs load-fn load-args fn-id)]
        (is (contains? (:fns result) fn-id))
        (is (contains? (:fns result) ref-fn-id))))

    (testing "collects all args"
      (let [fn-rec {:id fn-id :name "test" :parent-ids [parent-id]}
            parent-rec {:id parent-id :name "base" :parent-ids nil}
            fns {fn-id fn-rec parent-id parent-rec}
            fn-args [{:id (random-uuid) :fn-id fn-id :name "a"}
                     {:id (random-uuid) :fn-id fn-id :name "b"}]
            parent-args [{:id (random-uuid) :fn-id parent-id :name "x"}]
            args-map {fn-id fn-args parent-id parent-args}
            load-fn #(get fns %)
            load-args #(get args-map % [])
            result (graph/resolve-execution-graph-bfs load-fn load-args fn-id)]
        (is (= 3 (count (:args result))))))

    (testing "handles diamond pattern (shared dependency)"
      (let [;; fn-a -> fn-b, fn-a -> fn-c, fn-b -> fn-d, fn-c -> fn-d
            fn-a (random-uuid)
            fn-b (random-uuid)
            fn-c (random-uuid)
            fn-d (random-uuid)
            fns {fn-a {:id fn-a :name "a" :parent-ids nil}
                 fn-b {:id fn-b :name "b" :parent-ids nil}
                 fn-c {:id fn-c :name "c" :parent-ids nil}
                 fn-d {:id fn-d :name "d" :parent-ids nil}}
            args-map {fn-a [{:id (random-uuid) :fn-id fn-a :name "x" :ref-id fn-b}
                            {:id (random-uuid) :fn-id fn-a :name "y" :ref-id fn-c}]
                      fn-b [{:id (random-uuid) :fn-id fn-b :name "z" :ref-id fn-d}]
                      fn-c [{:id (random-uuid) :fn-id fn-c :name "w" :ref-id fn-d}]}
            load-fn #(get fns %)
            load-args #(get args-map % [])
            result (graph/resolve-execution-graph-bfs load-fn load-args fn-a)]
        ;; Should have all 4 fns, fn-d only once
        (is (= 4 (count (:fns result))))
        (is (contains? (:fns result) fn-d))))))
