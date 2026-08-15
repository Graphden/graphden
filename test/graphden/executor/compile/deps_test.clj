(ns graphden.executor.compile.deps-test
  "Pin the reverse-dep edge sources + dedup behaviour `build-reverse-deps`
   produces. Hot path: `prime-compile-deps!` calls this on every
   CRUD write through `delta-recompile!`, so a regression that
   silently drops an edge source ships stale closures to handlers.

   The performance contract (O(fns + bindings + list-items) after
   index-graph pre-build, NOT O(fns × bindings)) is the reason
   `index-graph` exists at all — without it `forward-deps-of` did
   a full filter scan per fn-id and `build-reverse-deps` became a
   billion-operation GC-pressure source on production-scale graphs
   (3000+ fns). The unit-test below is too small to demonstrate the
   complexity difference; the contract is held by the shape of the
   implementation, not by a per-test wall-clock check."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile.deps :as deps]))


;; Helper: tiny graph builders so the assertions read like prose.
(defn- ->graph
  [fns bindings list-items]
  {:fns fns :bindings bindings :list-items list-items})


(deftest forward-deps-edge-sources-test
  (testing "every documented edge source contributes to the dep set"
    (let [PARENT  #uuid "00000000-0000-0000-0000-000000000001"
          BASE    #uuid "00000000-0000-0000-0000-000000000002"
          ELEMENT #uuid "00000000-0000-0000-0000-000000000003"
          RETURN  #uuid "00000000-0000-0000-0000-000000000004"
          REF     #uuid "00000000-0000-0000-0000-000000000005"
          OVER    #uuid "00000000-0000-0000-0000-000000000006"
          ITEM    #uuid "00000000-0000-0000-0000-000000000007"
          RESOLVE #uuid "00000000-0000-0000-0000-000000000008"
          ME      #uuid "00000000-0000-0000-0000-00000000000a"
          B1      #uuid "00000000-0000-0000-0000-00000000000b"
          B2      #uuid "00000000-0000-0000-0000-00000000000c"
          graph (->graph
                  [{:id ME :parent-ids [PARENT]
                    :base-fn-id BASE :element-fn-id ELEMENT
                    :return-type-fn-id RETURN}]
                  [{:id B1 :fn-id ME :ref-fn-id REF :type-override-fn-id OVER}
                   ;; resolver-backed value binding (vault secret) —
                   ;; the resolver runs at arg-resolution time, so a
                   ;; fleet cell must carry its closure (load-cell!'s
                   ;; forward-closure walks exactly these edges).
                   {:id B2 :fn-id ME :value-present true :resolver-fn-id RESOLVE}]
                  [{:binding-id B1 :ref-fn-id ITEM}])
          edges (deps/forward-deps-of ME (deps/index-graph graph))]
      (is (= #{PARENT BASE ELEMENT RETURN REF OVER ITEM RESOLVE} edges)
          "edge contributions: parent-ids + base/element/return + ref-fn-id (binding) + type-override + resolver-fn-id + ref-fn-id (item)")))

  (testing "nil edge sources are dropped"
    (let [ME #uuid "00000000-0000-0000-0000-00000000000a"
          graph (->graph
                  [{:id ME :parent-ids [] :base-fn-id nil
                    :element-fn-id nil :return-type-fn-id nil}]
                  []
                  [])
          edges (deps/forward-deps-of ME (deps/index-graph graph))]
      (is (= #{} edges) "no edges from a fn with no parents, base, refs, items"))))


(deftest build-reverse-deps-inversion-test
  (testing "edges are inverted — querying by TARGET returns owners"
    (let [PARENT #uuid "00000000-0000-0000-0000-000000000001"
          C1 #uuid "00000000-0000-0000-0000-00000000000a"
          C2 #uuid "00000000-0000-0000-0000-00000000000b"
          graph (->graph
                  [{:id PARENT :parent-ids []}
                   {:id C1 :parent-ids [PARENT]}
                   {:id C2 :parent-ids [PARENT]}]
                  [] [])
          rev (deps/build-reverse-deps graph)]
      (is (= #{C1 C2} (get rev PARENT))
          "two children name PARENT — both appear in the reverse-dep set")
      (is (nil? (get rev C1)) "leaf fn has no downstream dependents")
      (is (nil? (get rev C2)))))

  (testing "raw `:fns` vector input gets indexed before reduction"
    ;; Mirrors the `read-graph` output shape — `:fns` is a seq, not
    ;; a {id → fn} map. The internal `index-graph` step normalises.
    (let [PARENT #uuid "00000000-0000-0000-0000-000000000001"
          CHILD  #uuid "00000000-0000-0000-0000-00000000000a"
          graph (->graph
                  [{:id PARENT :parent-ids []}
                   {:id CHILD :parent-ids [PARENT]}]
                  [] [])
          rev (deps/build-reverse-deps graph)]
      (is (= #{CHILD} (get rev PARENT)))))

  (testing "binding and item edges also invert"
    (let [REF  #uuid "00000000-0000-0000-0000-000000000005"
          ITEM #uuid "00000000-0000-0000-0000-000000000007"
          OWNER1 #uuid "00000000-0000-0000-0000-00000000000a"
          OWNER2 #uuid "00000000-0000-0000-0000-00000000000b"
          B1 #uuid "00000000-0000-0000-0000-00000000000c"
          B2 #uuid "00000000-0000-0000-0000-00000000000d"
          graph (->graph
                  [{:id OWNER1 :parent-ids []}
                   {:id OWNER2 :parent-ids []}]
                  [{:id B1 :fn-id OWNER1 :ref-fn-id REF}
                   {:id B2 :fn-id OWNER2}]
                  [{:binding-id B2 :ref-fn-id ITEM}])
          rev (deps/build-reverse-deps graph)]
      (is (= #{OWNER1} (get rev REF))
          "OWNER1's binding points at REF → OWNER1 in rev[REF]")
      (is (= #{OWNER2} (get rev ITEM))
          "OWNER2's binding-list-item points at ITEM → OWNER2 in rev[ITEM]"))))


(deftest transitive-blast-walks-reverse-edges-test
  (testing "blast includes seed itself + every transitive downstream"
    (let [A #uuid "00000000-0000-0000-0000-00000000000a"
          B #uuid "00000000-0000-0000-0000-00000000000b"
          C #uuid "00000000-0000-0000-0000-00000000000c"
          D #uuid "00000000-0000-0000-0000-00000000000d"
          ;; A → B → C → D in the reverse-dep direction (i.e. mutate
          ;; A blasts B; mutate B blasts C; mutate C blasts D)
          rev {A #{B}, B #{C}, C #{D}}]
      (is (= #{A B C D} (deps/transitive-blast rev [A]))
          "seed A walks the full chain through B,C,D")
      (is (= #{B C D} (deps/transitive-blast rev [B]))
          "seed B walks only its sub-tree")
      (is (= #{C D} (deps/transitive-blast rev [C])))
      (is (= #{D} (deps/transitive-blast rev [D])))))

  (testing "absent seed has no downstream — returns the seed itself"
    (let [X #uuid "00000000-0000-0000-0000-00000000000a"]
      (is (= #{X} (deps/transitive-blast {} [X]))))))


(deftest forward-closure-walks-forward-edges-test
  ;; The cell walk (docs/FLEET_RFC.md §3): what a root DEPENDS ON, transitively.
  (testing "closure includes the root + every transitive dependency"
    (let [A #uuid "00000000-0000-0000-0000-00000000000a"
          B #uuid "00000000-0000-0000-0000-00000000000b"
          C #uuid "00000000-0000-0000-0000-00000000000c"
          D #uuid "00000000-0000-0000-0000-00000000000d"
          ;; forward: A depends on B, B on C, C on D
          fwd {A #{B}, B #{C}, C #{D}}]
      (is (= #{A B C D} (deps/forward-closure fwd [A]))
          "root A's cell = A + everything it transitively needs")
      (is (= #{C D} (deps/forward-closure fwd [C])))
      (is (= #{D} (deps/forward-closure fwd [D])) "leaf dependency = just itself")))

  (testing "a root with no dependencies is a one-fn cell"
    (let [X #uuid "00000000-0000-0000-0000-00000000000a"]
      (is (= #{X} (deps/forward-closure {} [X])))))

  (testing "over a real build-deps-state — the cell is the ref-closure of the root"
    (let [BASE  #uuid "00000000-0000-0000-0000-000000000002"
          UTIL  #uuid "00000000-0000-0000-0000-000000000003"
          ROOT  #uuid "00000000-0000-0000-0000-00000000000a"
          OTHER #uuid "00000000-0000-0000-0000-00000000000b"
          graph (->graph
                  [{:id BASE :parent-ids []}
                   {:id UTIL :parent-ids [BASE]}
                   {:id ROOT :parent-ids [UTIL] :base-fn-id BASE}
                   {:id OTHER :parent-ids []}]
                  [] [])
          {:keys [forward-deps]} (deps/build-deps-state graph)]
      (is (= #{ROOT UTIL BASE} (deps/forward-closure forward-deps [ROOT]))
          "ROOT's cell pulls in UTIL + BASE, but NOT the unrelated OTHER")
      (is (not (contains? (deps/forward-closure forward-deps [ROOT]) OTHER))
          "a fn outside the root's ref-closure is not in the cell"))))


(deftest incremental-update-matches-full-rebuild-test
  ;; The invariant: after any sequence of CRUDs on a graph,
  ;; incrementally updating the deps-state must produce the SAME
  ;; reverse-deps + forward-deps maps that a from-scratch rebuild
  ;; would produce. Without this guarantee, delta-recompile! ships
  ;; stale closures.
  (let [A #uuid "00000000-0000-0000-0000-00000000000a"
        B #uuid "00000000-0000-0000-0000-00000000000b"
        C #uuid "00000000-0000-0000-0000-00000000000c"
        D #uuid "00000000-0000-0000-0000-00000000000d"
        BIND-B #uuid "00000000-0000-0000-0000-00000000010b"
        BIND-C #uuid "00000000-0000-0000-0000-00000000010c"
        BIND-D #uuid "00000000-0000-0000-0000-00000000010d"]
    (testing "CREATE — new fn pulls in its forward-deps"
      (let [g0 {:fns [{:id A}] :bindings [] :list-items []}
            g1 {:fns [{:id A} {:id B :parent-ids [A]}]
                :bindings [] :list-items []}
            s0 (deps/build-deps-state g0)
            s1-incr (deps/incremental-update s0 g1 [B])
            s1-full (deps/build-deps-state g1)]
        (is (= s1-full s1-incr)
            "creating B that depends on A → incremental == full")))
    (testing "UPDATE — fn loses an edge, gains another"
      (let [g0 {:fns [{:id A} {:id B} {:id C :parent-ids [A]}]
                :bindings [] :list-items []}
            g1 {:fns [{:id A} {:id B} {:id C :parent-ids [B]}]
                :bindings [] :list-items []}
            s0 (deps/build-deps-state g0)
            s1-incr (deps/incremental-update s0 g1 [C])
            s1-full (deps/build-deps-state g1)]
        (is (= s1-full s1-incr)
            "C reparented A→B → incremental == full")))
    (testing "DELETE — fn's reverse-deps entry + outgoing edges drop"
      (let [g0 {:fns [{:id A} {:id B :parent-ids [A]}]
                :bindings [{:id BIND-B :fn-id B :ref-fn-id A}]
                :list-items []}
            g1 {:fns [{:id A}]
                :bindings [] :list-items []}
            s0 (deps/build-deps-state g0)
            s1-incr (deps/incremental-update s0 g1 [B])
            s1-full (deps/build-deps-state g1)]
        (is (= s1-full s1-incr)
            "deleting B → A's reverse-deps loses B; B's entries vanish")))
    (testing "MIXED CRUD — three fns mutate in one batch"
      (let [g0 {:fns [{:id A} {:id B :parent-ids [A]}
                      {:id C :parent-ids [B]}]
                :bindings [{:id BIND-B :fn-id B :ref-fn-id A}]
                :list-items []}
            g1 {:fns [{:id A} {:id C :parent-ids [A]}
                      {:id D :parent-ids [C]}]
                :bindings [{:id BIND-C :fn-id C :ref-fn-id A}
                           {:id BIND-D :fn-id D :ref-fn-id A}]
                :list-items []}
            s0 (deps/build-deps-state g0)
            s1-incr (deps/incremental-update s0 g1 [B C D])
            s1-full (deps/build-deps-state g1)]
        (is (= s1-full s1-incr)
            "batch CRUD (delete B, update C, create D) — incremental == full")))
    (testing "cold start — empty state → incremental == full rebuild"
      (let [g {:fns [{:id A} {:id B :parent-ids [A]}]
               :bindings [] :list-items []}
            empty-state {:forward-deps {} :reverse-deps {}}
            s-incr (deps/incremental-update empty-state g [A B])
            s-full (deps/build-deps-state g)]
        (is (= s-full s-incr)
            "from empty state incrementally adding all fns == full build")))))
