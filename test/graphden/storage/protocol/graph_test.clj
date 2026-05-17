(ns graphden.storage.protocol.graph-test
  "Tests for `graphden.storage.protocol.graph` — the ExecutionGraph
   utilities: iteration-limit guards, generic BFS, UUID parsing, the
   ExecutionGraphResult record + accessors, and the loader-driven
   graph-resolution BFS.

   The whole namespace is pure (graph resolution takes loader fns as
   arguments), so no storage fixture is needed — in-memory maps stand
   in as loaders."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.graph :as g]))


;; ============================================================================
;; try-parse-uuid
;; ============================================================================

(deftest try-parse-uuid-test
  (testing "a UUID passes through; a UUID string parses; anything else → nil"
    (let [u (random-uuid)]
      (is (= u (g/try-parse-uuid u)))
      (is (= u (g/try-parse-uuid (str u)))))
    (is (nil? (g/try-parse-uuid "not-a-uuid")))
    (is (nil? (g/try-parse-uuid 42)))
    (is (nil? (g/try-parse-uuid nil)))))


;; ============================================================================
;; iteration-limit guards
;; ============================================================================

(deftest check-graph-iteration-limit-test
  (testing "a count under the limit passes; near the limit warns but passes"
    (is (nil? (g/check-graph-iteration-limit! 100 (random-uuid))))
    ;; 8500 is in [80% .. 100%) of the 10000 default → WARN, no throw
    (is (nil? (g/check-graph-iteration-limit! 8500 (random-uuid)))))

  (testing "a count over the limit throws :execution-error/graph-too-large"
    (let [ex (try (g/check-graph-iteration-limit! 20000 (random-uuid))
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :execution-error/graph-too-large (:type (ex-data ex))))))

  (testing "with-max-graph-iterations rebinds the limit for the body"
    (is (= 5 (g/with-max-graph-iterations 5 (fn [] g/*max-graph-iterations*))))
    (g/with-max-graph-iterations 5
      (fn []
        (is (thrown? clojure.lang.ExceptionInfo
                     (g/check-graph-iteration-limit! 6 (random-uuid))))))))


;; ============================================================================
;; traverse-bfs
;; ============================================================================

(deftest traverse-bfs-test
  (testing "every reachable node is visited"
    (let [graph {:a [:b :c] :b [:d] :c [:d] :d []}]
      (is (= #{:a :b :c :d}
             (g/traverse-bfs :a #(get graph % []))))))

  (testing "a cycle terminates rather than looping forever"
    (let [graph {:a [:b] :b [:a]}]
      (is (= #{:a :b} (g/traverse-bfs :a #(get graph % []))))))

  (testing "exceeding :max-iterations throws :execution-error/traversal-too-large"
    (let [ex (try (g/traverse-bfs :a (constantly [:b :c :d :e])
                                  {:max-iterations 1})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :execution-error/traversal-too-large (:type (ex-data ex)))))))


;; ============================================================================
;; ->execution-graph / execution-graph? / accessors
;; ============================================================================

(deftest execution-graph-construction-test
  (let [fa (random-uuid)
        slot-id (random-uuid)
        bind-id (random-uuid)
        graph (g/->execution-graph
                {:fns        {fa {:id fa :name "f"}}
                 :slots      [{:id slot-id :name "s"}]
                 :fn-slots   [{:fn-id fa :slot-id slot-id :position 0}]
                 :bindings   [{:id bind-id :fn-id fa :value 1}]
                 :list-items [{:binding-id bind-id :position 0 :value 9}]})]

    (testing "the result is an ExecutionGraphResult"
      (is (g/execution-graph? graph))
      (is (not (g/execution-graph? {:fns {}}))))

    (testing "the table accessors return their collections"
      (is (= {fa {:id fa :name "f"}} (g/get-graph-fns graph)))
      (is (= 1 (count (g/get-graph-slots graph))))
      (is (= 1 (count (g/get-graph-fn-slots graph))))
      (is (= 1 (count (g/get-graph-bindings graph))))
      (is (= 1 (count (g/get-graph-list-items graph)))))

    (testing "the per-fn / per-binding indexes are O(1) and keyed correctly"
      (is (= 1 (count (g/get-fn-slots-for-fn graph fa))))
      (is (= 1 (count (g/get-bindings-for-fn graph fa))))
      (is (= 9 (:value (first (g/get-items-for-binding graph bind-id)))))
      (is (= [] (g/get-bindings-for-fn graph (random-uuid)))))))


(deftest execution-graph-rejects-bad-fns-test
  (testing ":fns must be a non-empty map"
    (let [ex1 (try (g/->execution-graph {:fns [:not :a :map]})
                   (catch clojure.lang.ExceptionInfo e e))
          ex2 (try (g/->execution-graph {:fns {}})
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-data (:type (ex-data ex1))))
      (is (= :invalid-data (:type (ex-data ex2)))))))


;; ============================================================================
;; process-fn-node
;; ============================================================================

(deftest process-fn-node-test
  (let [fa (random-uuid)
        fb (random-uuid)
        bind-id (random-uuid)
        loaders {:load-fn-record         {fa {:id fa :parent-ids []}}
                 :load-fn-slots-for-fn   (constantly [])
                 :load-bindings-for-fn   {fa [{:id bind-id :fn-id fa :ref-fn-id fb}]}
                 :load-items-for-binding (constantly [])}
        empty-state {:fns {} :fn-slots [] :bindings [] :list-items []}]

    (testing "a loaded fn contributes its rows and surfaces its ref edges"
      (let [r (g/process-fn-node loaders fa empty-state)]
        (is (contains? (:fns r) fa))
        (is (= 1 (count (:bindings r))))
        (is (contains? (:new-fn-refs r) fb))))

    (testing "a fn that fails to load leaves state untouched, no new refs"
      (let [r (g/process-fn-node (assoc loaders :load-fn-record (constantly nil))
                                 fa empty-state)]
        (is (= #{} (:new-fn-refs r)))
        (is (empty? (:fns r)))))))


;; ============================================================================
;; resolve-execution-graph-bfs
;; ============================================================================

(deftest resolve-execution-graph-bfs-test
  (testing "BFS walks ref edges and parent-ids to pull in the whole closure"
    (let [fa (random-uuid)
          fb (random-uuid)
          fc (random-uuid)
          fns {fa {:id fa :parent-ids []}
               fb {:id fb :parent-ids [fc]}
               fc {:id fc :parent-ids []}}
          bindings {fa [{:id (random-uuid) :fn-id fa :ref-fn-id fb}]}
          loaders {:load-fn-record         fns
                   :load-fn-slots-for-fn   (constantly [])
                   :load-bindings-for-fn   #(get bindings % [])
                   :load-items-for-binding (constantly [])
                   :load-all-slots         (constantly [])}
          result (g/resolve-execution-graph-bfs loaders fa)]
      (is (g/execution-graph? result))
      ;; fa → (ref) fb → (parent) fc — all three resolved.
      (is (= #{fa fb fc} (set (keys (g/get-graph-fns result))))))))
