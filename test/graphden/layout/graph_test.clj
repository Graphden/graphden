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
          ;; Expanding never drops the root.
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
        (let [var (sp/create-entity storage :fn
                                    {:name "lg-variant" :parent-ids []
                                     :constraint [:variant :ok :int :err :text]})
              result (layout storage (:id var))]
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
                                      :list-append true :override-kind :fixed})
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
              result (layout storage (:id c))]
          (is (seq (:nodes result)))
          ;; The optional arg surfaces on the fn-node's :optionalArgs,
          ;; not as a standalone placeholder node.
          (is (some #(some (fn [n] (= "opt" (name n)))
                           (:optionalArgs (:data %)))
                    (fn-nodes result))))
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
