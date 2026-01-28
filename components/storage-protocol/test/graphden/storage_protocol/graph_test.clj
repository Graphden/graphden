(ns graphden.storage-protocol.graph-test
  "Tests for storage-protocol.graph - ExecutionGraph utilities and BFS algorithm."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.graph :as graph]))


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
        fn-schema-id (random-uuid)]
    (testing "creates valid ExecutionGraphResult"
      (let [result (graph/->execution-graph
                     {:fns {fn-id {:id fn-id}}
                      :fn-schemas {fn-schema-id {:id fn-schema-id}}
                      :arg-schemas {}
                      :resolved-args {}})]
        (is (graph/execution-graph? result))
        (is (= {fn-id {:id fn-id}} (:fns result)))
        (is (= {} (:call-sites result)))))  ; defaults to empty

    (testing "includes call-sites when provided"
      (let [cs-id (random-uuid)
            result (graph/->execution-graph
                     {:fns {fn-id {:id fn-id}}
                      :fn-schemas {fn-schema-id {:id fn-schema-id}}
                      :arg-schemas {}
                      :resolved-args {}
                      :call-sites {cs-id {:id cs-id :value 42}}})]
        (is (= {cs-id {:id cs-id :value 42}} (:call-sites result)))))

    (testing "throws when :fns is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fns map"
            (graph/->execution-graph
              {:fns []
               :fn-schemas {fn-schema-id {}}
               :arg-schemas {}
               :resolved-args {}}))))

    (testing "throws when :fns is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fns must contain"
            (graph/->execution-graph
              {:fns {}
               :fn-schemas {fn-schema-id {}}
               :arg-schemas {}
               :resolved-args {}}))))

    (testing "throws when :fn-schemas is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fn-schemas map"
            (graph/->execution-graph
              {:fns {fn-id {}}
               :fn-schemas "invalid"
               :arg-schemas {}
               :resolved-args {}}))))

    (testing "throws when :fn-schemas is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fn-schemas must contain"
            (graph/->execution-graph
              {:fns {fn-id {}}
               :fn-schemas {}
               :arg-schemas {}
               :resolved-args {}}))))

    (testing "throws when :arg-schemas is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :arg-schemas map"
            (graph/->execution-graph
              {:fns {fn-id {}}
               :fn-schemas {fn-schema-id {}}
               :arg-schemas nil
               :resolved-args {}}))))

    (testing "throws when :resolved-args is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :resolved-args map"
            (graph/->execution-graph
              {:fns {fn-id {}}
               :fn-schemas {fn-schema-id {}}
               :arg-schemas {}
               :resolved-args []}))))))


(deftest execution-graph?-test
  (testing "returns true for ExecutionGraphResult"
    (let [result (graph/->execution-graph
                   {:fns {(random-uuid) {}}
                    :fn-schemas {(random-uuid) {}}
                    :arg-schemas {}
                    :resolved-args {}})]
      (is (true? (graph/execution-graph? result)))))

  (testing "returns false for other types"
    (is (false? (graph/execution-graph? {})))
    (is (false? (graph/execution-graph? nil)))
    (is (false? (graph/execution-graph? [])))))


;; === extract-uuid-refs-from-arg-values tests ===

(deftest extract-uuid-refs-from-arg-values-test
  (testing "extracts UUID values"
    (let [uuid1 (random-uuid)
          uuid2 (random-uuid)
          k1 (random-uuid)
          k2 (random-uuid)
          arg-values {k1 {:value uuid1}
                      k2 {:value uuid2}}]
      (is (= #{uuid1 uuid2}
             (graph/extract-uuid-refs-from-arg-values arg-values)))))

  (testing "parses UUID strings"
    (let [uuid (random-uuid)
          k1 (random-uuid)
          arg-values {k1 {:value (str uuid)}}]
      (is (= #{uuid}
             (graph/extract-uuid-refs-from-arg-values arg-values)))))

  (testing "ignores non-UUID values"
    (let [k1 (random-uuid)
          k2 (random-uuid)
          k3 (random-uuid)
          arg-values {k1 {:value "not-uuid"}
                      k2 {:value 123}
                      k3 {:value nil}}]
      (is (= #{}
             (graph/extract-uuid-refs-from-arg-values arg-values)))))

  (testing "handles empty map"
    (is (= #{} (graph/extract-uuid-refs-from-arg-values {}))))

  (testing "handles mixed values"
    (let [uuid (random-uuid)
          k1 (random-uuid)
          k2 (random-uuid)
          k3 (random-uuid)
          arg-values {k1 {:value uuid}
                      k2 {:value "text"}
                      k3 {:value 42}}]
      (is (= #{uuid}
             (graph/extract-uuid-refs-from-arg-values arg-values))))))


;; === process-fn-node tests ===

(deftest process-fn-node-test
  (let [fn-id (random-uuid)
        fn-schema-id (random-uuid)
        arg-schema-id (random-uuid)]

    (testing "returns empty when fn not found"
      (let [load-fn (constantly nil)
            result (graph/process-fn-node
                     load-fn nil nil nil nil fn-id
                     {:fns {} :fn-schemas {} :arg-schemas {}
                      :resolved-args {} :call-sites {}})]
        (is (= {} (:fns (:graph result))))
        (is (= #{} (:new-fn-refs result)))))

    (testing "adds fn to graph when found"
      (let [fn-rec {:id fn-id :fn-schema-id fn-schema-id}
            fn-schema {:id fn-schema-id :name "test"}
            load-fn (fn [id] (when (= id fn-id) fn-rec))
            load-fn-schema (fn [id] (when (= id fn-schema-id) fn-schema))
            load-arg-schemas (constantly {arg-schema-id {:id arg-schema-id}})
            load-arg-values (constantly [])
            classify-refs (constantly {:fn-refs #{} :frvs {}})
            init-graph {:fns {} :fn-schemas {} :arg-schemas {}
                        :resolved-args {} :call-sites {}}
            result (graph/process-fn-node
                     load-fn load-fn-schema load-arg-schemas
                     load-arg-values classify-refs
                     fn-id init-graph)]
        (is (= fn-rec (get-in result [:graph :fns fn-id])))
        (is (= fn-schema (get-in result [:graph :fn-schemas fn-schema-id])))
        (is (some? (get-in result [:graph :arg-schemas arg-schema-id])))))))


;; === resolve-execution-graph-bfs tests ===

(deftest resolve-execution-graph-bfs-test
  (let [fn-id (random-uuid)
        fn-schema-id (random-uuid)]

    (testing "resolves single function"
      (let [fn-rec {:id fn-id :fn-schema-id fn-schema-id}
            fn-schema {:id fn-schema-id :name "test"}
            load-fn (fn [id] (when (= id fn-id) fn-rec))
            load-fn-schema (fn [id] (when (= id fn-schema-id) fn-schema))
            load-arg-schemas (constantly {})
            load-arg-values (constantly [])
            classify-refs (constantly {:fn-refs #{} :frvs {}})
            result (graph/resolve-execution-graph-bfs
                     load-fn load-fn-schema load-arg-schemas
                     load-arg-values classify-refs
                     fn-id)]
        (is (graph/execution-graph? result))
        (is (= fn-rec (get (:fns result) fn-id)))
        (is (= fn-schema (get (:fn-schemas result) fn-schema-id)))))

    (testing "follows fn references"
      (let [fn-id-a (random-uuid)
            fn-id-b (random-uuid)
            fn-schema-id (random-uuid)
            fns {fn-id-a {:id fn-id-a :fn-schema-id fn-schema-id}
                 fn-id-b {:id fn-id-b :fn-schema-id fn-schema-id}}
            load-fn #(get fns %)
            load-fn-schema (fn [_] {:id fn-schema-id :name "test"})
            load-arg-schemas (constantly {})
            load-arg-values (constantly [])
            ;; First call returns reference to fn-id-b, subsequent calls return empty
            calls (atom 0)
            classify-refs (fn [_]
                            (swap! calls inc)
                            (if (= 1 @calls)
                              {:fn-refs #{fn-id-b} :frvs {}}
                              {:fn-refs #{} :frvs {}}))
            result (graph/resolve-execution-graph-bfs
                     load-fn load-fn-schema load-arg-schemas
                     load-arg-values classify-refs
                     fn-id-a)]
        (is (contains? (:fns result) fn-id-a))
        (is (contains? (:fns result) fn-id-b))))))
