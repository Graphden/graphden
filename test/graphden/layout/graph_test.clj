(ns graphden.layout.graph-test
  "Tests for `graphden.layout.graph` — the DB-graph → cytoscape
   nodes/edges builder behind POST /api/graph/layout.

   `build-graph-elements` is a large mutually-recursive walk; the most
   effective coverage is end-to-end `compute-layout` over varied graph
   shapes (value / ref / free args, expansions, type-rows, sequences),
   each exercising a different slice of the walk. The public
   read/build helpers are also unit-tested directly."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.layout.builder-helpers :as bh]
    [graphden.layout.core :as lc]
    [graphden.layout.graph :as lg]
    [graphden.packages.records.ids :as ids]
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


(defn- no-badge-arrays?
  "True when no node carries the RETIRED :optionalArgs /
   :hofCapturedArgs badge arrays — the unified-arg-edges guard."
  [result]
  (not-any? (fn [n] (or (:optionalArgs (:data n)) (:hofCapturedArgs (:data n))))
            (:nodes result)))


(deftest layout-optional-arg-test
  (testing "an unbound optional slot renders as a uniform placeholder
            edge flagged :optionalArg (unified-arg-edges 2026-08-26 —
            the compact badge strip is gone; provenance is a style
            gradation on the SAME shape every argument gets)"
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
              edge (some (fn [e]
                           (let [d (:data e)]
                             (when (and (:isUnset d) (= "opt" (:argName d))) d)))
                         (:edges result))
              node (when edge
                     (some (fn [n]
                             (when (= (:target edge) (:id (:data n)))
                               (:data n)))
                           (:nodes result)))]
          (is (seq (:nodes result)))
          (is edge "the optional arg is an unset placeholder edge")
          (is (:optionalArg edge) "the edge carries the :optionalArg flag")
          (is (:isPlaceholder node) "its target is a placeholder node")
          (is (:optionalArg node) "the node carries the flag too (styles the + binder)")
          (is (no-badge-arrays? result)
              "no node resurrects the retired :optionalArgs/:hofCapturedArgs arrays"))
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


(deftest layout-anonymous-ref-cycle-depth-bounded-test
  ;; F3 regression: ANONYMOUS fns (name=nil) auto-expand, and process-fn's
  ;; call-site-scoped cycle key grows each hop so it never repeats — an
  ;; anonymous A↔B ref cycle recursed to a StackOverflow (a 500 on the
  ;; editor read path). The depth cap in process-any-fn truncates the
  ;; pathological subtree instead of crashing.
  (testing "an anonymous ref cycle does not StackOverflow — layout returns"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "lg-anon-cycle-base")
              slot (setup/create-slot! storage "x" :int)
              _    (setup/attach-slot! storage (:id base) (:id slot) 0)
              ;; Two ANONYMOUS composed fns (name nil) parented on base,
              ;; each binding the shared slot to the other.
              a    (sp/create-entity storage :fn {:name nil :parent-ids [(:id base)]})
              b    (sp/create-entity storage :fn {:name nil :parent-ids [(:id base)]})
              _    (setup/bind-ref! storage (:id a) (:id slot) (:id b))
              _    (setup/bind-ref! storage (:id b) (:id slot) (:id a))
              result (layout storage (:id a))]
          (is (seq (:nodes result))
              "the anonymous ref cycle layouts (truncated) rather than crashing"))
        (finally (sp/close storage))))))


;; ============================================================================
;; Pure build-graph-elements tests — literal entity maps, no storage.
;;
;; `build-lookups` accepts the same `{:fns :slots :fn-slots :bindings
;; :list-items}` tables the storage read produces, so the walker's
;; contracts can be pinned without a database. Each fixture is the
;; minimal graph that exercises one documented behaviour.
;; ============================================================================

(defn- pure-lookups
  "Build the lookups bundle from a literal entity map — no storage."
  [ge]
  (lg/build-lookups
    (lg/ensure-synth-args
      (merge {:fns [] :slots [] :fn-slots [] :bindings [] :list-items []}
             ge))))


(deftest build-elements-call-site-tree-test
  ;; LAYOUT.md § 2.2 — node ids encode the CALL SITE: the root keys by
  ;; `fn-<id>`; every nested fn keys by (caller-tag, source-arg-id), so
  ;; the same fn referenced from two bindings becomes two distinct
  ;; nodes and the graph handed to placement is a TREE.
  (let [int-id (random-uuid)
        leaf   (random-uuid)
        base   (random-uuid)
        root   (random-uuid)
        sa     (random-uuid)
        sb     (random-uuid)
        lookups (pure-lookups
                  {:fns [{:id int-id :name "int" :parent-ids []}
                         {:id leaf :name "pcs-leaf" :parent-ids []
                          :return-type-fn-id int-id}
                         {:id base :name "pcs-base" :parent-ids []
                          :return-type-fn-id int-id}
                         {:id root :name "pcs-root" :parent-ids [base]}]
                   :slots [{:id sa :name "a" :type-fn-id int-id :required true}
                           {:id sb :name "b" :type-fn-id int-id :required true}]
                   :fn-slots [{:id (random-uuid) :fn-id base :slot-id sa :position 0}
                              {:id (random-uuid) :fn-id base :slot-id sb :position 1}]
                   :bindings [{:id (random-uuid) :fn-id root :slot-id sa :ref-fn-id leaf}
                              {:id (random-uuid) :fn-id root :slot-id sb :ref-fn-id leaf}]})
        result (lg/build-graph-elements root {} lookups)
        root-node (first (filter #(= (str "fn-" root) (get-in % [:data :id]))
                                 (:nodes result)))
        leaf-nodes (filter #(= (str leaf) (get-in % [:data :originalFnId]))
                           (:nodes result))]
    (testing "the root keys by fn-<id>, is flagged, and stacks its ancestor's name"
      (is (some? root-node))
      (is (true? (get-in root-node [:data :isRoot])))
      (is (= "pcs-root" (first (str/split-lines
                                 (get-in root-node [:data :label]))))))
    (testing "one node PER CALL SITE — the shared leaf renders twice, scoped to the caller"
      (is (= 2 (count leaf-nodes)))
      (is (= 2 (count (distinct (map #(get-in % [:data :id]) leaf-nodes)))))
      (is (every? #(str/starts-with? (get-in % [:data :id])
                                     (str "fn-" root "-"))
                  leaf-nodes)
          "nested ids fold in the caller tag"))
    (testing "each call site gets its own edge from the root, named by its slot"
      (let [ref-edges (filter #(= (str "fn-" root) (get-in % [:data :source]))
                              (:edges result))]
        (is (= #{"a" "b"} (set (map #(get-in % [:data :argName]) ref-edges))))
        (is (= 2 (count (distinct (map #(get-in % [:data :target]) ref-edges)))))))))


(deftest type-row-synth-slot-hidden-by-id-test
  ;; The loader synthesizes a `value` slot on every refinement and an
  ;; `items` slot on every list type-row (deterministic id —
  ;; `ids/slot-id owner "value"|"items"`). At the type-row's OWN page
  ;; the synth slot is hidden (its structure is already carried by the
  ;; base/element internal edges); the match is on the deterministic
  ;; ID, not the resolved name.
  (testing "a refinement root hides its synth `value` slot but still emits the base edge"
    (let [int-id (random-uuid)
          pos (random-uuid)
          vslot (ids/slot-id pos "value")
          lookups (pure-lookups
                    {:fns [{:id int-id :name "int" :parent-ids []}
                           {:id pos :name "pcs-pos" :parent-ids []
                            :base-fn-id int-id :constraint [:> 0]}]
                     :slots [{:id vslot :name "value" :type-fn-id int-id
                              :required true}]
                     :fn-slots [{:id (random-uuid) :fn-id pos :slot-id vslot
                                 :position 0}]})
          result (lg/build-graph-elements pos {} lookups)]
      (is (not-any? #(= "value" (get-in % [:data :argName])) (:edges result))
          "no placeholder edge for the synthetic value slot")
      (is (some #(= (str int-id) (get-in % [:data :originalFnId]))
                (:nodes result))
          "the base type still surfaces via the type-row internal edge")))
  (testing "a slot NAMED items but with a non-deterministic id is NOT hidden"
    (let [int-id (random-uuid)
          lst (random-uuid)
          real-slot (random-uuid)
          lookups (pure-lookups
                    {:fns [{:id int-id :name "int" :parent-ids []}
                           {:id lst :name "pcs-nums" :parent-ids []
                            :element-fn-id int-id}]
                     :slots [{:id real-slot :name "items" :type-fn-id int-id
                              :required true}]
                     :fn-slots [{:id (random-uuid) :fn-id lst :slot-id real-slot
                                 :position 0}]})
          result (lg/build-graph-elements lst {} lookups)]
      (is (some #(= "items" (get-in % [:data :argName])) (:edges result))
          "id mismatch ⇒ the slot renders — hiding matches the id, not the name")))
  (testing "the same slot under its DETERMINISTIC id is hidden"
    (let [int-id (random-uuid)
          lst (random-uuid)
          islot (ids/slot-id lst "items")
          lookups (pure-lookups
                    {:fns [{:id int-id :name "int" :parent-ids []}
                           {:id lst :name "pcs-nums2" :parent-ids []
                            :element-fn-id int-id}]
                     :slots [{:id islot :name "items" :type-fn-id int-id
                              :required true}]
                     :fn-slots [{:id (random-uuid) :fn-id lst :slot-id islot
                                 :position 0}]})
          result (lg/build-graph-elements lst {} lookups)]
      (is (not-any? #(= "items" (get-in % [:data :argName])) (:edges result))))))


(deftest expansion-migrates-binding-into-ancestor-ref-consumer-test
  ;; β-substitution rendering (process-expanded-fn-impl stages 2+5):
  ;; the root binds a slot whose OWNER lives inside an ancestor-ref's
  ;; inheritance chain, so on expansion the binding must "migrate" —
  ;; its target renders as a child of the CONSUMER card (where the
  ;; use-site lives), not as a direct child of the root card.
  (let [int-id (random-uuid)
        b1 (random-uuid)      ; base-fn owning :handler
        hs (random-uuid)
        consumer (random-uuid) ; composed of b1, :handler free
        b2 (random-uuid)      ; base-fn owning :route
        rs (random-uuid)
        wrap (random-uuid)    ; composed of b2, binds :route → consumer
        root (random-uuid)    ; composed of wrap, binds :handler → target
        target (random-uuid)
        lookups (pure-lookups
                  {:fns [{:id int-id :name "int" :parent-ids []}
                         {:id b1 :name "pcs-mb1" :parent-ids []
                          :return-type-fn-id int-id}
                         {:id consumer :name "pcs-consumer" :parent-ids [b1]}
                         {:id b2 :name "pcs-mb2" :parent-ids []
                          :return-type-fn-id int-id}
                         {:id wrap :name "pcs-wrap" :parent-ids [b2]}
                         {:id root :name "pcs-mroot" :parent-ids [wrap]}
                         {:id target :name "pcs-target" :parent-ids []
                          :return-type-fn-id int-id}]
                   :slots [{:id hs :name "handler" :type-fn-id int-id :required true}
                           {:id rs :name "route" :type-fn-id int-id :required true}]
                   :fn-slots [{:id (random-uuid) :fn-id b1 :slot-id hs :position 0}
                              {:id (random-uuid) :fn-id b2 :slot-id rs :position 0}]
                   :bindings [{:id (random-uuid) :fn-id wrap :slot-id rs
                               :ref-fn-id consumer}
                              {:id (random-uuid) :fn-id root :slot-id hs
                               :ref-fn-id target}]})
        result (lg/build-graph-elements
                 root
                 {(str "fn-" root) {:full-depth 1 :partial-fns #{}}}
                 lookups)
        root-node-id (str "fn-" root)
        node-id-of (fn [fid]
                     (some #(when (= (str fid) (get-in % [:data :originalFnId]))
                              (get-in % [:data :id]))
                           (:nodes result)))
        consumer-node-id (node-id-of consumer)
        target-node-id (node-id-of target)]
    (testing "the ancestor-ref consumer renders as a child of the root"
      (is (some? consumer-node-id))
      (is (some #(and (= root-node-id (get-in % [:data :source]))
                      (= consumer-node-id (get-in % [:data :target]))
                      (= "route" (get-in % [:data :argName])))
                (:edges result))))
    (testing "the migrated binding's target hangs off the CONSUMER card"
      (is (some? target-node-id))
      (is (some #(and (= consumer-node-id (get-in % [:data :source]))
                      (= target-node-id (get-in % [:data :target]))
                      (= "handler" (get-in % [:data :argName])))
                (:edges result))))
    (testing "the migrated binding does NOT also render from the root card"
      (is (not-any? #(and (= root-node-id (get-in % [:data :source]))
                          (= "handler" (get-in % [:data :argName])))
                    (:edges result))))))


;; ============================================================================
;; Post-processing transforms — pure, literal inputs
;; ============================================================================

(deftest annotate-optionals-deep-free-strip-test
  (testing "nodes listed in :deep-free-by-node get a SORTED :deepFreeArgs
            vector; every other node passes through untouched"
    (let [state (atom {:deep-free-by-node {"fn-a" #{"zeta" "alpha"}}})
          nodes [{:data {:id "fn-a" :type "fn"}}
                 {:data {:id "fn-b" :type "fn"}}]
          [a b] (#'lg/annotate-optionals nodes state)]
      (is (= ["alpha" "zeta"] (get-in a [:data :deepFreeArgs])))
      (is (= {:data {:id "fn-b" :type "fn"}} b)))))


(deftest migrate-captured-edges-test
  (testing "an edge whose :sourceArgId resolves in the migrations map is
            re-rooted at the inside consumer; :target/:argName survive"
    (let [aid (random-uuid)
          other (random-uuid)
          edges [{:data {:id "e-1" :source "fn-root" :target "unset-x"
                         :sourceArgId aid :argName "handler"}}
                 {:data {:id "e-2" :source "fn-root" :target "fn-leaf"
                         :sourceArgId other :argName "coll"}}]
          [m u] (#'lg/migrate-captured-edges edges {aid "fn-root-consumer"})]
      (is (= "fn-root-consumer" (get-in m [:data :source])))
      (is (= "e-cap-fn-root-consumer-unset-x" (get-in m [:data :id])))
      (is (= "unset-x" (get-in m [:data :target])))
      (is (= "handler" (get-in m [:data :argName])))
      (is (= (second edges) u) "an unmigrated edge passes through unchanged"))))


(deftest dedup-overlays-test
  (let [slot (random-uuid)
        a1 (random-uuid)
        a2 (random-uuid)
        arg-map {a1 {:id a1 :slot-id slot}
                 a2 {:id a2 :slot-id slot}}]
    (testing "duplicate value-overlays on one terminal keep only the deepest consumer"
      (let [fn-node {:data {:id "fn-r" :type "fn"}}
            shallow {:data {:id "arg-1" :type "arg" :argId (str a1) :label "5"}}
            deep    {:data {:id "arg-2" :type "arg" :argId (str a2) :label "5"}}
            edges [{:data {:id "e1" :source "fn-r" :target "arg-1"}}
                   {:data {:id "e2" :source "fn-r-x1-y2" :target "arg-2"}}]
            {:keys [nodes edges]} (#'lg/dedup-overlays
                                   [fn-node shallow deep] edges arg-map)]
        (is (= ["fn-r" "arg-2"] (mapv #(get-in % [:data :id]) nodes))
            "the shallow copy is dropped, the deepest kept")
        (is (= ["e2"] (mapv #(get-in % [:data :id]) edges))
            "edges pointing at dropped overlays are dropped with them")))
    (testing "overlays with no terminal (no argId) are never deduped"
      (let [n1 {:data {:id "arg-1" :type "arg" :label "5"}}
            n2 {:data {:id "arg-2" :type "arg" :label "5"}}
            {:keys [nodes]} (#'lg/dedup-overlays [n1 n2] [] arg-map)]
        (is (= 2 (count nodes)))))))


(deftest layout-root-deep-free-placeholders-test
  (testing "a template's deeper holes surface as placeholders on the ROOT card:
            an extension of T (which binds x → F, F leaving svc free) shows
            svc as a deep placeholder that binds on the extension itself"
    (let [storage (setup/create-test-storage)]
      (try
        (let [b1   (setup/create-base-fn! storage "lg-deep-b1")
              x    (setup/create-slot! storage "x" :any)
              _    (setup/attach-slot! storage (:id b1) (:id x) 0)
              b2   (setup/create-base-fn! storage "lg-deep-b2")
              svc  (setup/create-slot! storage "svc" :text)
              _    (setup/attach-slot! storage (:id b2) (:id svc) 0)
              f    (setup/create-composed-fn! storage "lg-deep-f" (:id b2))
              t    (setup/create-composed-fn! storage "lg-deep-t" (:id b1))
              _    (setup/bind-ref! storage (:id t) (:id x) (:id f))
              r    (setup/create-composed-fn! storage "lg-deep-r" (:id t))
              result (layout storage (:id r))
              root-id (str "fn-" (:id r))
              edge (some (fn [e]
                           (let [d (:data e)]
                             (when (and (:isUnset d) (= "svc" (:argName d))) d)))
                         (:edges result))
              node (when edge
                     (some (fn [n] (when (= (:target edge) (:id (:data n))) (:data n)))
                           (:nodes result)))]
          (is edge "svc — F's free arg reached through T's binding — is an unset edge of the root")
          (is (= root-id (:source edge)) "…from the root card")
          (is (:deepArg edge) "flagged deep: the hole lives below the visible slot surface")
          (is (:isPlaceholder node))
          (is (:deepArg node))
          (is (= (str (:id r)) (:fnId node))
              "the + binder writes the binding on the ROOT fn (closure capture)…")
          (is (= (str (:id svc)) (:slotId node)) "…keyed by the inner slot")
          (testing "once bound on the root, the placeholder is gone"
            (setup/bind-value! storage (:id r) (:id svc) "orders")
            (let [again (layout storage (:id r))]
              (is (not-any? (fn [e] (and (:isUnset (:data e)) (= "svc" (:argName (:data e)))))
                            (:edges again))))))
        (finally (sp/close storage)))))

  (testing "a positional rename inside the template (`:parts [{:as :path}]`) is a deep hole too"
    ;; `service-get`'s `path` lives on `:_service-url-join`'s `:parts` list
    ;; item — the walker resolves the rename slot through
    ;; `[fn-id slot-name]`, which the layout lookups must carry.
    (let [storage (setup/create-test-storage)]
      (try
        (let [b1   (setup/create-base-fn! storage "lg-ren-b1")
              x    (setup/create-slot! storage "x" :any)
              _    (setup/attach-slot! storage (:id b1) (:id x) 0)
              join (setup/create-base-fn! storage "lg-ren-join")
              parts (setup/create-slot! storage "parts" :sequence)
              _    (setup/attach-slot! storage (:id join) (:id parts) 0)
              f    (setup/create-composed-fn! storage "lg-ren-f" (:id join))
              ;; f: parts [{:as :path}] — as the parser stores it: an own
              ;; slot `path` on f (no source) plus the list item naming it.
              path (sp/create-entity storage :slot {:name "path" :type-fn-id (get setup/primitive-fn-ids :text)})
              _    (setup/attach-slot! storage (:id f) (:id path) 0)
              lb   (sp/create-entity storage :binding {:fn-id (:id f) :slot-id (:id parts) :list-append true})
              _    (sp/create-entity storage :binding-list-item
                                     {:binding-id (:id lb) :position 0 :value {:as :path}})
              t    (setup/create-composed-fn! storage "lg-ren-t" (:id b1))
              _    (setup/bind-ref! storage (:id t) (:id x) (:id f))
              r    (setup/create-composed-fn! storage "lg-ren-r" (:id t))
              result (layout storage (:id r))
              edge (some (fn [e]
                           (let [d (:data e)]
                             (when (and (:isUnset d) (= "path" (:argName d))) d)))
                         (:edges result))
              node (when edge
                     (some (fn [n] (when (= (:target edge) (:id (:data n))) (:data n)))
                           (:nodes result)))]
          (is edge "path — a positional rename two refs down — is a deep placeholder of the root")
          (is (:deepArg edge))
          (is (= (str (:id r)) (:fnId node)))
          (is (= (str (:id path)) (:slotId node)) "keyed by the rename-view slot"))
        (finally (sp/close storage))))))
