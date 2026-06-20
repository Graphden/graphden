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
          ME      #uuid "00000000-0000-0000-0000-00000000000a"
          B1      #uuid "00000000-0000-0000-0000-00000000000b"
          graph (->graph
                  [{:id ME :parent-ids [PARENT]
                    :base-fn-id BASE :element-fn-id ELEMENT
                    :return-type-fn-id RETURN}]
                  [{:id B1 :fn-id ME :ref-fn-id REF :type-override-fn-id OVER}]
                  [{:binding-id B1 :ref-fn-id ITEM}])
          edges (deps/forward-deps-of ME (deps/index-graph graph))]
      (is (= #{PARENT BASE ELEMENT RETURN REF OVER ITEM} edges)
          "edge contributions: parent-ids + base/element/return + ref-fn-id (binding) + type-override + ref-fn-id (item)")))

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
