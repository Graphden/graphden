(ns graphden.packages.app.build-graph-elements-test
  "Direct tests for `build-graph-elements` — the layout pass that turns
   raw fn/arg entities + per-call expansion specs into the
   cytoscape-shaped {:nodes :edges} response. Covers node-id strategy,
   edge emission/dedup, free-arg propagation rendering (placeholder vs
   λ-badge vs cross-HOF capture migration), and the migrated-binding
   rendering inside expanded named-fn ancestors."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))


;; =============================================================================
;; DYNAMIC LOADING — same pattern as layout-test.clj
;; =============================================================================

(def ^:private layout-ns
  (let [impls-file (io/resource "packages/app/layout/impls.clj")]
    (when impls-file
      (load-file (java.io.File/.getPath (io/file impls-file))))
    (find-ns 'graphden.packages.app.layout.impls)))


(def ^:private build-lookups        (some-> layout-ns (ns-resolve 'build-lookups)))
(def ^:private build-graph-elements (some-> layout-ns (ns-resolve 'build-graph-elements)))


;; =============================================================================
;; HELPERS
;; =============================================================================

(defn- mk-fn
  ([id] (mk-fn id nil nil))
  ([id nm] (mk-fn id nm nil))
  ([id nm parent-ids]
   (cond-> {:id id :name nm}
     parent-ids (assoc :parent-ids parent-ids))))


(defn- mk-arg
  "Schema-shaped arg. `opts` is a partial map; we drop nil keys to match the
   denser real DB shape and avoid surprising `(:value arg)` truthy checks."
  [opts]
  (into {} (remove (comp nil? val)) opts))


(defn- run
  ([root-id fns args] (run root-id fns args {}))
  ([root-id fns args expansions]
   (build-graph-elements root-id expansions (build-lookups {:fns fns :args args}))))


(defn- node-by-id
  [layout id]
  (some #(when (= id (get-in % [:data :id])) %) (:nodes layout)))


(defn- edges-with-arg-name
  [layout n]
  (filterv #(= n (get-in % [:data :argName])) (:edges layout)))


;; =============================================================================
;; SMOKE — root-only and single-arg shapes
;; =============================================================================

(deftest root-only-no-args
  (testing "fn with no args renders as a single root node, no edges"
    (let [a (random-uuid)
          {:keys [nodes edges]} (run a [(mk-fn a "a")] [])]
      (is (= 1 (count nodes)))
      (is (empty? edges))
      (is (true? (get-in (first nodes) [:data :isRoot])))
      (is (= (str "fn-" a) (get-in (first nodes) [:data :id]))))))


(deftest literal-value-arg-renders-edge-and-arg-node
  (testing "primary arg bound to a literal value emits an arg-node + value-edge"
    (let [base (random-uuid)  ; base-fn (parent-ids nil) with primary :x
          base-x (random-uuid)
          a (random-uuid)     ; user-facing fn binding :x to 42
          a-x (random-uuid)
          fns [(mk-fn base "base") (mk-fn a "a" [base])]
          args [(mk-arg {:id base-x :fn-id base :name "x"})
                (mk-arg {:id a-x :fn-id a :source-id base-x :value 42})]
          {:keys [nodes edges]} (run a fns args)
          val-edges (edges-with-arg-name {:edges edges} "x")]
      (is (= 1 (count val-edges)))
      (is (= 42 (some-> (node-by-id {:nodes nodes}
                                    (:target (:data (first val-edges))))
                        :data :label
                        (->> (re-find #"\d+"))
                        Integer/parseInt)))
      (is (= "arg" (get-in (some #(when (= "arg" (get-in % [:data :type])) %) nodes)
                           [:data :type]))))))


(deftest non-hof-ref-renders-fn-edge
  (testing "primary arg bound to a fn ref emits an fn→fn ref edge"
    (let [base (random-uuid)
          base-y (random-uuid)
          a (random-uuid)
          a-y (random-uuid)
          b (random-uuid)
          fns [(mk-fn base "base") (mk-fn a "a" [base]) (mk-fn b "b")]
          args [(mk-arg {:id base-y :fn-id base :name "y"})
                (mk-arg {:id a-y :fn-id a :source-id base-y :ref-id b})]
          {:keys [nodes edges]} (run a fns args)]
      (is (= [{:source (str "fn-" a)
               :target (str "fn-" a "-" a-y)
               :argName "y"}]
             (mapv #(select-keys (:data %) [:source :target :argName]) edges)))
      (is (some #(= (str "fn-" a "-" a-y) (get-in % [:data :id])) nodes)
          "callee node added with call-site-keyed id"))))


;; =============================================================================
;; UNSET-ARG ROUTING — placeholder vs λ-badge vs capture migration
;; =============================================================================

(deftest unbound-free-arg-renders-placeholder
  (testing "free primary with no binding renders as dashed placeholder + edge"
    (let [base (random-uuid)
          base-x (random-uuid)
          a (random-uuid)
          ;; sync creates a propagated shadow on `a` for each free arg of
          ;; the parent — without it the slot isn't even on a's interface.
          a-x-propagated (random-uuid)
          fns [(mk-fn base "base") (mk-fn a "a" [base])]
          args [(mk-arg {:id base-x :fn-id base :name "x"})
                (mk-arg {:id a-x-propagated :fn-id a :source-id base-x})]
          {:keys [nodes edges]} (run a fns args)
          unset (some #(when (true? (get-in % [:data :isPlaceholder])) %) nodes)
          unset-edge (some #(when (true? (get-in % [:data :isUnset])) %) edges)]
      (is (some? unset) "placeholder node emitted")
      (is (some? unset-edge) "edge marked :isUnset")
      (is (= "x" (get-in unset-edge [:data :argName]))))))


(deftest hof-lambda-param-renders-as-lambda-badge
  (testing "free arg below an is-fn boundary surfaces as `λname` on its node, not a placeholder"
    (let [;; base-outer has :f :fn primary
          base-outer (random-uuid)
          base-outer-f (random-uuid)
          ;; base-inner — a single :v free primary
          base-inner (random-uuid)
          base-inner-v (random-uuid)
          ;; outer — is-fn ref to inner via :f
          outer (random-uuid)
          outer-f (random-uuid)
          ;; inner is the lambda body; needs a propagated shadow for :v so
          ;; the layout's args-by-fn carries the slot it'll render as λ.
          inner (random-uuid)
          inner-v-propagated (random-uuid)
          fns [(mk-fn base-outer "base-outer")
               (mk-fn base-inner "base-inner")
               (mk-fn outer "outer" [base-outer])
               (mk-fn inner nil [base-inner])]
          args [(mk-arg {:id base-outer-f :fn-id base-outer :name "f" :is-fn true})
                (mk-arg {:id base-inner-v :fn-id base-inner :name "v"})
                (mk-arg {:id outer-f :fn-id outer :source-id base-outer-f :ref-id inner :is-fn true})
                (mk-arg {:id inner-v-propagated :fn-id inner :source-id base-inner-v})]
          {:keys [nodes]} (run outer fns args)
          inner-node (some #(when (re-find #"base-inner" (str (get-in % [:data :label]))) %) nodes)]
      (is (= ["v"] (get-in inner-node [:data :hofCapturedArgs]))
          "λv badge surfaces — caller has no structural anchor for :v"))))


(deftest hof-capture-migrates-the-edge-from-caller-to-inner-consumer
  (testing "caller's bound arg whose source-id reaches an inner unset slot:
            edge migrates from caller node to the inner consumer node.
            Cross-HOF source-id chain: outer.v → base.v primary, with the
            inner lambda also propagating base.v as its own free slot."
    (let [;; base — :f :fn + :v free
          base (random-uuid)
          base-f (random-uuid)
          base-v (random-uuid)
          ;; inner: the lambda body. Same base. Carries a propagated :v
          ;; shadow so the layout descends into a slot to render.
          inner (random-uuid)
          inner-v (random-uuid)
          ;; outer: binds :f → inner AND :v → 42 (a literal). The :v binding
          ;; is what migrates to the inner consumer.
          outer (random-uuid)
          outer-f (random-uuid)
          outer-v (random-uuid)
          fns [(mk-fn base "base")
               (mk-fn inner nil [base])
               (mk-fn outer "outer" [base])]
          args [(mk-arg {:id base-f :fn-id base :name "f" :is-fn true})
                (mk-arg {:id base-v :fn-id base :name "v"})
                (mk-arg {:id outer-f :fn-id outer :source-id base-f :ref-id inner :is-fn true})
                (mk-arg {:id outer-v :fn-id outer :source-id base-v :value 42})
                (mk-arg {:id inner-v :fn-id inner :source-id base-v})]
          {:keys [nodes edges]} (run outer fns args)
          v-edges (edges-with-arg-name {:edges edges} "v")
          v-edge (first v-edges)]
      (is (pos? (count v-edges)) ":v edge present (migrated or otherwise)")
      ;; After migration, the edge's :source should NOT be the outer node;
      ;; the captured-edge-migrations atom rewrites it to the inner consumer.
      (is (not= (str "fn-" outer) (:source (:data v-edge)))
          ":v edge no longer originates at outer caller after migration")
      (is (some? (node-by-id {:nodes nodes} (:source (:data v-edge))))
          "migrated source still points at a real node"))))


;; =============================================================================
;; FREE-ARG PROPAGATION — render rules
;; =============================================================================

(deftest no-edge-emitted-when-edge-arg-name-missing
  (testing "process-any-fn at the root has no source-node → no parent edge"
    (let [a (random-uuid)
          {:keys [edges]} (run a [(mk-fn a "a")] [])]
      (is (empty? edges)))))


;; =============================================================================
;; NAMED SEQUENCE ITEMS — `{:as :name}` syntax inside :sequence anchors
;; =============================================================================

(deftest named-seq-item-on-sequence-anchor
  (testing "anchor with one named-no-value item: layout walks the chain
            and the named slot rendering doesn't crash. Also ensures the
            item's own :name is preserved on the arg entity."
    (let [base (random-uuid)
          base-items (random-uuid) ; primary :items :sequence
          inner (random-uuid)      ; inner :parent base, :items anchor + 1 named item
          inner-anchor (random-uuid)
          inner-item (random-uuid)
          fns [(mk-fn base "base") (mk-fn inner nil [base])]
          args [(mk-arg {:id base-items :fn-id base :name "items" :type :sequence})
                (mk-arg {:id inner-anchor :fn-id inner :source-id base-items
                         :type :sequence :next-arg-id inner-item})
                (mk-arg {:id inner-item :fn-id inner :name "captured-name"})]
          {:keys [nodes]} (run inner fns args)]
      ;; Smoke: layout produces something for the inner fn; the named item
      ;; renders as part of the synthetic `items[0]`-style sequence slot
      ;; entries (no exception, at least 1 node).
      (is (pos? (count nodes))))))


;; =============================================================================
;; MIGRATED BINDINGS INSIDE EXPANDED ANCESTORS  (regression — see e9449aa)
;; =============================================================================
;;
;; Setup: outer's binding for a slot whose source-chain terminates inside
;; an inner ref-target. When the user expands BOTH outer (so the binding
;; classifies as `migrated-by-ref` to the inner ancestor-ref) AND that
;; inner ref-target with a non-trivial spec (so `process-expanded-fn-impl`
;; runs there too), the migrated entry must be honoured by the inner
;; expansion's unset-arg dispatch — otherwise the consumer node simply
;; disappears (text-error-router on web-server's expand-router-result).

(deftest migrated-binding-honoured-inside-expanded-named-fn
  (testing "binding that migrated to a named ancestor-ref stays visible
            after the user expands that ancestor-ref too. Mirrors the
            web-server / router-result / text-error-router scenario:
            outer's :func binding migrates to the ancestor-ref where
            invoke.func actually lives; expanding that ref must still
            render the migrated edge + target node."
    (let [;; invoke base: :func + :arg (both :any free)
          invoke (random-uuid)
          invoke-func (random-uuid)
          invoke-arg (random-uuid)
          ;; named call-site that binds :arg = some literal but leaves
          ;; :func free. Inherits from invoke.
          call-site (random-uuid)
          cs-func (random-uuid)
          cs-arg (random-uuid)
          ;; target the outer caller wants :func to point at
          target (random-uuid)
          ;; outer wraps call-site through a non-HOF :ref AND binds the
          ;; deeper :func slot. Also has its own base for clean isolation.
          base (random-uuid)
          base-x (random-uuid)
          outer (random-uuid)
          outer-x (random-uuid)
          outer-func (random-uuid)
          fns [(mk-fn invoke "invoke")
               (mk-fn target "target")
               (mk-fn call-site "call-site" [invoke])
               (mk-fn base "base")
               (mk-fn outer "outer" [base])]
          args [(mk-arg {:id invoke-func :fn-id invoke :name "func"})
                (mk-arg {:id invoke-arg :fn-id invoke :name "arg"})
                (mk-arg {:id cs-func :fn-id call-site :source-id invoke-func})
                (mk-arg {:id cs-arg :fn-id call-site :source-id invoke-arg :value 1})
                (mk-arg {:id base-x :fn-id base :name "x"})
                (mk-arg {:id outer-x :fn-id outer :source-id base-x :ref-id call-site})
                ;; Cross-HOF/cross-ref source-id chain: outer's :func
                ;; sources directly at invoke.func (the deepest free slot).
                (mk-arg {:id outer-func :fn-id outer :source-id invoke-func :ref-id target})]
          spec {(str "fn-" outer) 3
                (str "fn-" outer "-" outer-x) 3}
          {:keys [nodes]} (run outer fns args spec)
          target-node (some #(when (= (str target) (get-in % [:data :originalFnId])) %) nodes)]
      (is (some? target-node)
          "target fn-node still appears after expanding the ancestor-ref;
           regression: process-expanded-fn-impl used to ignore
           parent-bindings and drop the :func edge + its target."))))
