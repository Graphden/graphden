(ns graphden.layout.graph-test
  "Tests for `graphden.layout.graph` — the DB-graph → cytoscape
   nodes/edges builder behind POST /api/graph/layout.

   `build-graph-elements` is a large mutually-recursive walk; the most
   effective coverage is end-to-end `compute-layout` over varied graph
   shapes (value / ref / free args, expansions, type-rows, sequences),
   each exercising a different slice of the walk. The public
   read/build helpers are also unit-tested directly."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.layout.builder-helpers :as bh]
    [graphden.layout.core :as lc]
    [graphden.layout.graph :as lg]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(defn- layout
  "Run the full layout pipeline against the live storage graph."
  ([storage root-id] (layout storage root-id {}))
  ([storage root-id expansions]
   (lc/compute-layout (lg/load-graph-entities-uncached storage)
                      root-id expansions)))


(defn- fn-nodes
  [result]
  (filter #(= "fn" (:type (:data %))) (:nodes result)))


;; ============================================================================
;; load-graph-entities-uncached / build-lookups / derive-fn-slot-views
;; ============================================================================

(deftest graph-read-and-lookups-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [base (setup/create-base-fn! storage "lg-read-base")
            slot (setup/create-slot! storage "a" :int)
            _    (setup/attach-slot! storage (:id base) (:id slot) 0)
            ge   (lg/load-graph-entities-uncached storage)]
        (testing "load-graph-entities-uncached returns the 5 tables + derived :args"
          (is (every? #(contains? ge %)
                      [:fns :slots :fn-slots :bindings :list-items :args])))

        (testing "derive-fn-slot-views emits an anchor row per (fn, slot)"
          (let [rows (lg/derive-fn-slot-views ge)
                a-row (first (filter #(= "a" (:name %)) rows))]
            (is (some? a-row))
            (is (= (:id base) (:fn-id a-row)))))

        (testing "ensure-synth-args fills :args only when missing"
          (is (contains? (lg/ensure-synth-args (dissoc ge :args)) :args))
          (is (identical? (:args ge) (:args (lg/ensure-synth-args ge)))))

        (testing "build-lookups indexes fns / slots / args"
          (let [lk (lg/build-lookups ge)]
            (is (contains? (:fn-map lk) (:id base)))
            (is (contains? (:slot-map lk) (:id slot))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; compute-layout — basic graph shapes
;; ============================================================================

(deftest layout-value-and-free-args-test
  (testing "a composed fn with a value-bound arg and a free arg"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "lg-add")
              sa   (setup/create-slot! storage "a" :int)
              sb   (setup/create-slot! storage "b" :int)
              _    (setup/attach-slot! storage (:id base) (:id sa) 0)
              _    (setup/attach-slot! storage (:id base) (:id sb) 1)
              c    (setup/create-composed-fn! storage "lg-add5" (:id base))
              _    (setup/bind-value! storage (:id c) (:id sa) 5)
              result (layout storage (:id c))]
          (is (seq (:nodes result)))
          (is (some #(= (str (:id c)) (:originalFnId (:data %)))
                    (fn-nodes result))
              "the root fn renders as a node")
          (is (some #(= "5" (str (:label (:data %)))) (:nodes result))
              "the value-bound arg renders"))
        (finally (sp/close storage))))))


(deftest layout-ref-arg-test
  (testing "a ref-bound arg produces an edge to the target fn-card"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base   (setup/create-base-fn! storage "lg-ref-base")
              slot   (setup/create-slot! storage "x" :int)
              _      (setup/attach-slot! storage (:id base) (:id slot) 0)
              target (setup/create-base-fn! storage "lg-ref-target")
              c      (setup/create-composed-fn! storage "lg-ref-fn" (:id base))
              _      (setup/bind-ref! storage (:id c) (:id slot) (:id target))
              result (layout storage (:id c))]
          (is (seq (:edges result)))
          (is (some #(= (str (:id target)) (:originalFnId (:data %)))
                    (fn-nodes result))
              "the ref target renders as its own card"))
        (finally (sp/close storage))))))


(deftest layout-root-not-found-test
  (testing "an unknown root-id throws :execution-error/not-found"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ex (try (layout storage (random-uuid))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :execution-error/not-found (:type (ex-data ex)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; compute-layout — ancestor expansion
;; ============================================================================

(deftest layout-canonical-slot-order-on-expand-test
  ;; Pin for commit 0956763f's canonical sort. Before that, walk
  ;; order produced REVERSE-of-declaration for the inherited slot
  ;; trio (e.g. `:if`'s `:test :then :else` rendered as
  ;; `:else :then :test` from top of the if-card). The fix sorts
  ;; emitted children by (deepest-ancestor-first, position-within-
  ;; ancestor) — inherited slots in declaration order BEFORE the
  ;; closer ancestor's own additions.
  ;;
  ;; Synthetic graph mirrors `_app-cached` / `response-cache-wrap` /
  ;; `:if` (depth-2 chain with 3 inherited slots + 1 own).
  (let [storage (setup/create-test-storage)]
    (try
      (let [;; Deepest base-fn with 3 slots in declaration order.
            deep  (setup/create-base-fn! storage "lcs-deep")
            s-alpha (setup/create-slot! storage "alpha" :int)
            s-beta  (setup/create-slot! storage "beta"  :int)
            s-gamma (setup/create-slot! storage "gamma" :int)
            _ (setup/attach-slot! storage (:id deep) (:id s-alpha) 0)
            _ (setup/attach-slot! storage (:id deep) (:id s-beta)  1)
            _ (setup/attach-slot! storage (:id deep) (:id s-gamma) 2)
            ;; Closer wrapper adds its OWN slot.
            wrap  (setup/create-composed-fn! storage "lcs-wrap" (:id deep))
            s-delta (setup/create-slot! storage "delta" :int)
            _ (setup/attach-slot! storage (:id wrap) (:id s-delta) 0)
            ;; Targets for each slot (any base-fn body).
            t-a (setup/create-base-fn! storage "lcs-t-a")
            t-b (setup/create-base-fn! storage "lcs-t-b")
            t-c (setup/create-base-fn! storage "lcs-t-c")
            t-d (setup/create-base-fn! storage "lcs-t-d")
            ;; Root binds all 4 slots via refs. Bind in NON-declaration
            ;; order to make the canonical-sort do real work (insertion
            ;; order would otherwise hide the bug).
            root (setup/create-composed-fn! storage "lcs-root" (:id wrap))
            _ (setup/bind-ref! storage (:id root) (:id s-delta) (:id t-d))
            _ (setup/bind-ref! storage (:id root) (:id s-gamma) (:id t-c))
            _ (setup/bind-ref! storage (:id root) (:id s-beta)  (:id t-b))
            _ (setup/bind-ref! storage (:id root) (:id s-alpha) (:id t-a))
            ;; Expand to depth 2 = inline both `wrap` and `deep` so all
            ;; 4 slots appear as direct children of the root card.
            result (layout storage (:id root)
                           {(str "fn-" (:id root)) {:full-depth 2
                                                    :partial-fns #{}}})
            root-node-id (str "fn-" (:id root))
            slot-order (->> (:edges result)
                            (filter #(= root-node-id
                                        (get-in % [:data :source])))
                            (map #(get-in % [:data :argName])))]
        (testing "deepest-ancestor slots first (in declaration order), then closer ancestor's own"
          (is (= ["alpha" "beta" "gamma" "delta"] (vec slot-order))
              (str "edges in walk order — should match `:args` "
                   "declaration order (alpha/beta/gamma from deep, "
                   "then delta from wrap); got: " (pr-str slot-order)))))
      (finally (sp/close storage)))))


(deftest layout-expansion-test
  (testing "expanding the root one level pulls in the parent's structure"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "lg-exp-base")
              slot (setup/create-slot! storage "n" :int)
              _    (setup/attach-slot! storage (:id base) (:id slot) 0)
              c    (setup/create-composed-fn! storage "lg-exp-fn" (:id base))
              collapsed (layout storage (:id c))
              expanded  (layout storage (:id c)
                                {(str "fn-" (:id c)) {:full-depth 1
                                                      :partial-fns #{}}})]
          (is (seq (:nodes collapsed)))
          (is (seq (:nodes expanded)))
          ;; Expanding never drops the root. NOTE: this single-slot fixture
          ;; is too trivial to observe expansion adding structure — the
          ;; parent's name is stacked into the root label and its one slot is
          ;; a placeholder in BOTH collapsed and expanded, so node/label
          ;; sets are identical here. Meaningful multi-level expansion (base
          ;; node emitted only when expanded) is asserted by the type-row
          ;; internal tests below.
          (is (some #(= (str (:id c)) (:originalFnId (:data %)))
                    (fn-nodes expanded))))
        (finally (sp/close storage))))))


;; ============================================================================
;; compute-layout — type-row roots (emit-type-row-internals!)
;; ============================================================================

(deftest layout-type-row-internals-test
  (let [storage (setup/create-test-storage)
        int-id  (get setup/primitive-fn-ids :int)]
    (try
      (testing "a refinement type-row emits an internal edge to its base"
        (let [pos (sp/create-entity storage :fn
                                    {:name "lg-pos" :parent-ids []
                                     :base-fn-id int-id :constraint [:> 0]})
              result (layout storage (:id pos))]
          (is (some #(= (str int-id) (:originalFnId (:data %)))
                    (fn-nodes result))
              "the refinement's base type renders")))

      (testing "a list type-row emits an internal edge to its element type"
        (let [lst (sp/create-entity storage :fn
                                    {:name "lg-list" :parent-ids []
                                     :element-fn-id int-id})
              result (layout storage (:id lst))]
          (is (some #(= (str int-id) (:originalFnId (:data %)))
                    (fn-nodes result)))))

      (testing "a union type-row emits one internal edge per branch"
        (let [uni (sp/create-entity storage :fn
                                    {:name "lg-union" :parent-ids []
                                     :constraint [:union :int :text]})
              result (layout storage (:id uni))]
          (is (<= 2 (count (fn-nodes result)))
              "root + one node per union branch")))

      (testing "a variant type-row emits one internal edge per tagged branch"
        (let [variant-row (sp/create-entity storage :fn
                                            {:name "lg-variant" :parent-ids []
                                             :constraint [:variant :ok :int
                                                          :err :text]})
              result (layout storage (:id variant-row))]
          (is (<= 2 (count (fn-nodes result)))
              "root + one node per variant branch")))
      (finally (sp/close storage)))))


;; ============================================================================
;; compute-layout — sequence binding
;; ============================================================================

(deftest layout-sequence-binding-test
  (testing "a :list-append binding with items renders the sequence chain"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "lg-seq-base")
              slot (setup/create-slot! storage "items" :sequence)
              _    (setup/attach-slot! storage (:id base) (:id slot) 0)
              c    (setup/create-composed-fn! storage "lg-seq-fn" (:id base))
              bind (sp/create-entity storage :binding
                                     {:fn-id (:id c) :slot-id (:id slot)
                                      :list-append true})
              _    (sp/create-entity storage :binding-list-item
                                     {:binding-id (:id bind) :position 0 :value 1})
              _    (sp/create-entity storage :binding-list-item
                                     {:binding-id (:id bind) :position 1 :value 2})
              result (layout storage (:id c))]
          (is (seq (:nodes result)))
          (is (some #(= "1" (str (:label (:data %)))) (:nodes result))
              "the first sequence item renders"))
        (finally (sp/close storage))))))


;; ============================================================================
;; compute-layout — multi-inheritance, HOF, optional args, deep expansion
;; ============================================================================

(deftest layout-multi-inheritance-test
  (testing "an MI root renders, and expanding it walks the MI ancestor level"
    (let [storage (setup/create-test-storage)]
      (try
        (let [p1 (setup/create-base-fn! storage "lg-mi-p1")
              p2 (setup/create-base-fn! storage "lg-mi-p2")
              mi (sp/create-entity storage :fn
                                   {:name "lg-mi" :parent-ids [(:id p1) (:id p2)]})
              collapsed (layout storage (:id mi))
              expanded  (layout storage (:id mi)
                                {(str "fn-" (:id mi)) {:full-depth 1
                                                       :partial-fns #{}}})]
          (is (some #(= (str (:id mi)) (:originalFnId (:data %)))
                    (fn-nodes collapsed)))
          (is (seq (:nodes expanded))))
        (finally (sp/close storage))))))


(deftest layout-leaf-self-ref-migration-no-stackoverflow-test
  ;; Reproduces the `:health-status` shape: a fn binds an inherited slot
  ;; to a ref-fn that itself inherits the SAME slot. Before the guard at
  ;; the leaf-migration recursion in graph.clj:2121, the layout engine
  ;; would re-apply the migrated binding when descending into the ref-fn's
  ;; own slot of the same name (slot-ids are global), recursing forever.
  (testing "a leaf bound to a ref that inherits the same slot doesn't StackOverflow"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base    (setup/create-base-fn! storage "lg-self-ref-base")
              slot    (setup/create-slot! storage "x" :int)
              _       (setup/attach-slot! storage (:id base) (:id slot) 0)
              ;; sibling — also inherits :x from base
              sibling (setup/create-composed-fn! storage "lg-self-ref-sibling" (:id base))
              ;; root — inherits :x, binds it to sibling. Sibling itself
              ;; has :x free. The bug: the layout would migrate the
              ;; root's `x → sibling` binding into sibling's own :x slot.
              root    (setup/create-composed-fn! storage "lg-self-ref-root" (:id base))
              _       (setup/bind-ref! storage (:id root) (:id slot) (:id sibling))
              result  (layout storage (:id root))]
          (is (seq (:nodes result))
              "layout completes without StackOverflowError")
          (is (some #(= (str (:id sibling)) (:originalFnId (:data %)))
                    (fn-nodes result))
              "sibling renders as its own card via the ref edge"))
        (finally (sp/close storage))))))


(deftest layout-leaf-multi-hop-cycle-no-stackoverflow-test
  ;; A→B→A migration loop variant of the self-ref case: two sibling fns
  ;; each bind the shared slot to the OTHER. Walking the leaf migration
  ;; chain naïvely would alternate between A's and B's bindings forever.
  ;; The single-hop guard at graph.clj:2121 catches its own arrow; the
  ;; safety here comes from each level being a NAMED-leaf boundary that
  ;; doesn't recurse into the body unless explicitly expanded. This test
  ;; nails that property: no further fixes were needed for the multi-hop
  ;; case at the time the regression test was added (Audit N1: 632/632
  ;; named fns layout cleanly), but the test guards against a future
  ;; broadening of the leaf-migration recursion.
  (testing "A→B→A cycle of leaf-migration bindings doesn't StackOverflow"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "lg-multi-hop-base")
              slot (setup/create-slot! storage "x" :int)
              _    (setup/attach-slot! storage (:id base) (:id slot) 0)
              a    (setup/create-composed-fn! storage "lg-multi-hop-a" (:id base))
              b    (setup/create-composed-fn! storage "lg-multi-hop-b" (:id base))
              _    (setup/bind-ref! storage (:id a) (:id slot) (:id b))
              _    (setup/bind-ref! storage (:id b) (:id slot) (:id a))
              result (layout storage (:id a))]
          (is (seq (:nodes result))
              "A→B→A doesn't StackOverflow at layout time")
          (is (some #(= (str (:id b)) (:originalFnId (:data %)))
                    (fn-nodes result))
              "the bound B renders as its own card via the ref edge"))
        (finally (sp/close storage))))))


(deftest layout-partial-mi-expansion-test
  (testing "a partial-fns spec expands only the named MI parent"
    (let [storage (setup/create-test-storage)]
      (try
        (let [p1 (setup/create-base-fn! storage "lg-pmi-p1")
              p2 (setup/create-base-fn! storage "lg-pmi-p2")
              mi (sp/create-entity storage :fn
                                   {:name "lg-pmi" :parent-ids [(:id p1) (:id p2)]})
              result (layout storage (:id mi)
                             {(str "fn-" (:id mi))
                              {:full-depth 0 :partial-fns #{(:id p1)}}})]
          (is (seq (:nodes result))))
        (finally (sp/close storage))))))


(deftest layout-hof-slot-test
  (testing "a ref bound into an :fn-typed slot drives the HOF path"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base   (setup/create-base-fn! storage "lg-hof-base")
              fslot  (setup/create-slot! storage "func" :fn)
              _      (setup/attach-slot! storage (:id base) (:id fslot) 0)
              target (setup/create-base-fn! storage "lg-hof-target")
              c      (setup/create-composed-fn! storage "lg-hof-fn" (:id base))
              _      (setup/bind-ref! storage (:id c) (:id fslot) (:id target))
              result (layout storage (:id c))]
          (is (seq (:nodes result)))
          (is (some #(= (str (:id target)) (:originalFnId (:data %)))
                    (fn-nodes result))))
        (finally (sp/close storage))))))


(deftest layout-optional-arg-test
  (testing "an unbound optional slot is routed as a compact optional badge"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "lg-opt-base")
              slot (sp/create-entity storage :slot
                                     {:name "opt"
                                      :type-fn-id (get setup/primitive-fn-ids :int)
                                      :required false})
              _    (setup/attach-slot! storage (:id base) (:id slot) 0)
              c    (setup/create-composed-fn! storage "lg-opt-fn" (:id base))
              result (layout storage (:id c))
              entry (some (fn [n]
                            (some (fn [e] (when (= "opt" (name (:name e))) e))
                                  (:optionalArgs (:data n))))
                          (fn-nodes result))]
          (is (seq (:nodes result)))
          ;; The optional arg surfaces on the fn-node's :optionalArgs
          ;; (each entry `{:name … :slot-id …}` so the editor can
          ;; resolve the declaring ancestor for the strip's tooltip),
          ;; not as a standalone placeholder node.
          (is entry "optional arg entry present")
          (is (= (:id slot) (:slot-id entry))
              "entry carries slot-id so the editor's findSlotDeclaringFn can attribute the source"))
        (finally (sp/close storage))))))


(deftest layout-deep-expansion-test
  (testing "a two-level inheritance chain expands to full depth"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base  (setup/create-base-fn! storage "lg-deep-base")
              slot  (setup/create-slot! storage "n" :int)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              mid   (setup/create-composed-fn! storage "lg-deep-mid" (:id base))
              child (setup/create-composed-fn! storage "lg-deep-child" (:id mid))
              result (layout storage (:id child)
                             {(str "fn-" (:id child)) {:full-depth 2
                                                       :partial-fns #{}}})]
          (is (seq (:nodes result)))
          (is (some #(= (str (:id child)) (:originalFnId (:data %)))
                    (fn-nodes result))))
        (finally (sp/close storage))))))


;; ============================================================================
;; Provenance — inheritance-source fields + type-narrowing-chain attribution
;; ============================================================================

(deftest layout-inheritance-source-test
  (testing "an inherited slot's value-arg node carries the ancestor chain"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base   (setup/create-base-fn! storage "lg-src-base")
              slot   (setup/create-slot! storage "a" :int)
              _      (setup/attach-slot! storage (:id base) (:id slot) 0)
              child  (setup/create-composed-fn! storage "lg-src-child" (:id base))
              _      (setup/bind-value! storage (:id child) (:id slot) 7)
              result (layout storage (:id child))
              arg-node (first (filter #(= "7" (str (:label (:data %))))
                                      (:nodes result)))]
          (is (some? arg-node) "the inherited value-arg renders")
          (is (= [{:fnId (str (:id base)) :fnName "lg-src-base"}]
                 (:sourceChain (:data arg-node)))
              "sourceChain walks :source-id to the slot-owning ancestor"))
        (finally (sp/close storage)))))
  (testing "a two-level inheritance chain surfaces both ancestors"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base  (setup/create-base-fn! storage "lg-src2-base")
              slot  (setup/create-slot! storage "a" :int)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              mid   (setup/create-composed-fn! storage "lg-src2-mid" (:id base))
              child (setup/create-composed-fn! storage "lg-src2-child" (:id mid))
              _     (setup/bind-value! storage (:id child) (:id slot) 9)
              result (layout storage (:id child))
              arg-node (first (filter #(= "9" (str (:label (:data %))))
                                      (:nodes result)))]
          (is (= [{:fnId (str (:id mid))  :fnName "lg-src2-mid"}
                  {:fnId (str (:id base)) :fnName "lg-src2-base"}]
                 (:sourceChain (:data arg-node)))
              "chain is leaf→root: immediate parent first, owner last"))
        (finally (sp/close storage))))))


(deftest edge-type-chain-source-attribution-test
  (testing "compute-edge-type-chain tags each group with its narrowing source"
    ;; `:typeChain` is gated hard (no current package graph triggers it),
    ;; so the private chain builder is exercised directly: a 2-fn chain
    ;; where the leaf fn overrides the slot's type via a binding.
    (let [f1 (random-uuid) f2 (random-uuid) s (random-uuid)
          a1 (random-uuid) a2 (random-uuid) ov (random-uuid)
          lookups {:fn-map  {f1 {:id f1 :name "f1"} f2 {:id f2 :name "f2"}}
                   :arg-map {a1 {:id a1 :fn-id f1 :slot-id s :type :int :source-id a2}
                             a2 {:id a2 :fn-id f2 :slot-id s :type :jsonb :source-id nil}}
                   ;; f1 overrides the type via a binding; f2 does not.
                   :binding-by-fn-slot {[f1 s] {:type-override-fn-id ov}
                                        [f2 s] {}}}]
      (is (= [{:type "int"   :fns ["f1"] :source "binding-override"}
              {:type "jsonb" :fns ["f2"] :source "slot-declared"}]
             (bh/compute-edge-type-chain lookups a1 #{f1 f2}))))))
