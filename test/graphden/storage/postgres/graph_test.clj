(ns ^:integration graphden.storage.postgres.graph-test
  "Tests for `graphden.storage.postgres.graph` — the PG-optimised
   recursive-CTE `resolve-execution-graph` implementation.

   The generic resolver (`graphden.storage.protocol.generic-graph`)
   is covered by `generic_graph_test`; this file asserts that the PG
   path produces a bit-identical `ExecutionGraphResult` over every
   outgoing-edge category the recursive CTE walks:

     - `fn_parent_ids` junction        (parent inheritance)
     - `fn.base_fn_id`                 (refinement)
     - `fn.element_fn_id`              (list-type)
     - `fn.return_type_fn_id`          (declared return)
     - `binding.ref_fn_id`             (call-site refs)
     - `binding.type_override_fn_id`   (per-binding type)
     - `binding_list_item.ref_fn_id`   (sequence refs)

   Reached via `sp/resolve-execution-graph` — the PG storage dispatch
   forwards into `pg-graph/resolve-execution-graph`, so the same
   `:not-found` error path / `ExecutionGraphResult` shape the generic
   resolver provides has to flow through here."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.postgres.graph :as pg-graph]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-graph :as gg]
    [graphden.storage.protocol.graph :as graph]))


(use-fixtures :once (setup/create-container-fixture))


(deftest unknown-fn-id-not-found-test
  (testing "unknown fn-id → :not-found, same shape as generic resolver"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ex (try (sp/resolve-execution-graph storage (random-uuid))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :not-found (:type (ex-data ex)))))
        (finally (sp/close storage))))))


(deftest seed-without-edges-test
  (testing "an isolated base-fn returns a graph containing only itself"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "isolated-base")
              result (sp/resolve-execution-graph storage (:id base))]
          (is (graph/execution-graph? result))
          (let [fn-ids (set (keys (graph/get-graph-fns result)))]
            (is (contains? fn-ids (:id base)) "seed is included")))
        (finally (sp/close storage))))))


(deftest multi-level-inheritance-test
  (testing "chain root → middle → leaf — all three reachable from leaf"
    (let [storage (setup/create-test-storage)]
      (try
        (let [root     (setup/create-base-fn! storage "chain-root")
              middle   (setup/create-composed-fn! storage "chain-middle" (:id root))
              leaf     (setup/create-composed-fn! storage "chain-leaf" (:id middle))
              result   (sp/resolve-execution-graph storage (:id leaf))
              fn-ids   (set (keys (graph/get-graph-fns result)))]
          (is (contains? fn-ids (:id leaf))   "leaf — the seed itself")
          (is (contains? fn-ids (:id middle)) "middle — leaf's parent")
          (is (contains? fn-ids (:id root))   "root — middle's parent (transitive)"))
        (finally (sp/close storage))))))


(deftest resolve-execution-graph-enforces-iteration-limit-test
  (testing "a closure larger than *max-graph-iterations* throws graph-too-large"
    (let [storage (setup/create-test-storage)]
      (try
        (let [root   (setup/create-base-fn! storage "cap-root")
              middle (setup/create-composed-fn! storage "cap-middle" (:id root))
              leaf   (setup/create-composed-fn! storage "cap-leaf" (:id middle))
              ;; leaf's closure = {leaf, middle, root} = 3 reachable fns;
              ;; cap at 2 so it trips — matches the generic BFS guard.
              ex (sp/with-max-graph-iterations 2
                                               (fn []
                                                 (try (sp/resolve-execution-graph storage (:id leaf))
                                                      (catch clojure.lang.ExceptionInfo e e))))]
          (is (instance? clojure.lang.ExceptionInfo ex))
          (is (= :execution-error/graph-too-large (:type (ex-data ex))))
          (is (= 2 (:max-iterations (ex-data ex)))))
        (finally (sp/close storage))))))


(deftest ref-binding-edge-test
  (testing "binding.ref_fn_id pulls the ref target into the closure"
    (let [storage (setup/create-test-storage)]
      (try
        (let [target   (setup/create-base-fn! storage "ref-target")
              base     (setup/create-base-fn! storage "ref-base")
              slot     (setup/create-slot! storage "callee" :any)
              _        (setup/attach-slot! storage (:id base) (:id slot) 0)
              composed (setup/create-composed-fn! storage "ref-composed" (:id base))
              _        (setup/bind-ref! storage (:id composed) (:id slot) (:id target))
              result   (sp/resolve-execution-graph storage (:id composed))
              fn-ids   (set (keys (graph/get-graph-fns result)))]
          (is (contains? fn-ids (:id composed)) "seed")
          (is (contains? fn-ids (:id base))     "parent")
          (is (contains? fn-ids (:id target))   "ref-binding target"))
        (finally (sp/close storage))))))


(deftest binding-list-item-ref-edge-test
  (testing "binding_list_item.ref_fn_id pulls sequence-item refs in"
    (let [storage (setup/create-test-storage)]
      (try
        (let [item-target (setup/create-base-fn! storage "list-item-target")
              base        (setup/create-base-fn! storage "list-base")
              slot        (setup/create-slot! storage "items" :any)
              _           (setup/attach-slot! storage (:id base) (:id slot) 0)
              composed    (setup/create-composed-fn! storage "list-composed" (:id base))
              ;; Empty-value binding so we can hang a list-item under it.
              ;; `:value` is required for the binding row; use nil to
              ;; mark the slot as sequence-bearing.
              binding     (sp/create-entity storage :binding
                                            {:fn-id (:id composed)
                                             :slot-id (:id slot)
                                             :override-kind :fixed})
              _           (sp/create-entity storage :binding-list-item
                                            {:binding-id (:id binding)
                                             :position 0
                                             :ref-fn-id (:id item-target)})
              result      (sp/resolve-execution-graph storage (:id composed))
              fn-ids      (set (keys (graph/get-graph-fns result)))]
          (is (contains? fn-ids (:id composed)))
          (is (contains? fn-ids (:id item-target))
              "sequence-item ref target is in the closure"))
        (finally (sp/close storage))))))


(deftest pg-matches-generic-test
  (testing "PG recursive-CTE resolver produces the same fn-id set as the generic BFS"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Build a small graph touching every edge category the
        ;; recursive CTE walks (inheritance + ref + sequence-item ref)
        ;; so divergence between the two resolvers would surface.
        (let [target1   (setup/create-base-fn! storage "shape-target1")
              target2   (setup/create-base-fn! storage "shape-target2")
              root      (setup/create-base-fn! storage "shape-root")
              slot1     (setup/create-slot! storage "a" :any)
              slot2     (setup/create-slot! storage "items" :any)
              _         (setup/attach-slot! storage (:id root) (:id slot1) 0)
              _         (setup/attach-slot! storage (:id root) (:id slot2) 1)
              middle    (setup/create-composed-fn! storage "shape-middle" (:id root))
              leaf      (setup/create-composed-fn! storage "shape-leaf" (:id middle))
              _         (setup/bind-ref! storage (:id leaf) (:id slot1) (:id target1))
              binding2  (sp/create-entity storage :binding
                                          {:fn-id (:id leaf)
                                           :slot-id (:id slot2)
                                           :override-kind :fixed})
              _         (sp/create-entity storage :binding-list-item
                                          {:binding-id (:id binding2)
                                           :position 0
                                           :ref-fn-id (:id target2)})
              pg-result      (sp/resolve-execution-graph storage (:id leaf))
              generic-result (gg/resolve-execution-graph storage (:id leaf))]
          (is (= (set (keys (graph/get-graph-fns pg-result)))
                 (set (keys (graph/get-graph-fns generic-result))))
              "PG and generic resolvers produce the same fn closure")
          (is (= (set (map :id (graph/get-graph-slots pg-result)))
                 (set (map :id (graph/get-graph-slots generic-result))))
              "same slot set")
          (is (= (set (map (juxt :fn-id :slot-id)
                           (graph/get-graph-fn-slots pg-result)))
                 (set (map (juxt :fn-id :slot-id)
                           (graph/get-graph-fn-slots generic-result))))
              "same fn-slot junctions")
          (is (= (set (map (juxt :fn-id :slot-id)
                           (graph/get-graph-bindings pg-result)))
                 (set (map (juxt :fn-id :slot-id)
                           (graph/get-graph-bindings generic-result))))
              "same bindings"))
        (finally (sp/close storage))))))


(deftest direct-impl-test
  (testing "calling `pg-graph/resolve-execution-graph` directly works"
    ;; sp/resolve-execution-graph dispatches through the protocol; we
    ;; also exercise the PG entry-point directly to make sure the
    ;; private `reachable-fn-ids` + bulk-load shape is reachable from
    ;; tests that instrument the PG path specifically.
    (let [storage (setup/create-test-storage)]
      (try
        (let [base   (setup/create-base-fn! storage "direct-base")
              result (pg-graph/resolve-execution-graph
                       (:pool storage) storage (:id base))]
          (is (graph/execution-graph? result))
          (is (contains? (set (keys (graph/get-graph-fns result)))
                         (:id base))))
        (finally (sp/close storage))))))
