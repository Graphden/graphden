(ns graphden.packages.app.layout-substitution-test
  "Regression tests for substitution-context binding migration in
   `derive-fn-slot-views` + `migration-target-for`.

   The shape under test mirrors the ring-adapter chain:

       INVOKE       (base-fn, slots [arg func])
       ROUTER-RES   (parent INVOKE, binding {arg → INTERNAL-REQ})
       MERGE-IN     (base-fn, slot [m])
       ROUTER-RING  (parent MERGE-IN, binding {m → ROUTER-RES})
       APP-RING     (parent ROUTER-RING, binding {func → TARGET})
       INTERNAL-REQ, TARGET (leaf fns)

   `APP-RING :args {:func TARGET}` binds INVOKE's `:func` slot — a slot
   whose owner sits OUTSIDE APP-RING's parent chain. Reachable only via
   `:m → ROUTER-RES → :invoke`. The expected layout for
   `(root APP-RING, expand root:1)` migrates `:func` down so the edge
   sources from ROUTER-RES, NOT from APP-RING."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.layout.graph :refer [derive-fn-slot-views]]))


(defn- uuid
  [n]
  (java.util.UUID/fromString (format "00000000-0000-0000-0000-%012d" n)))


(def F-ANY        (uuid 1))
(def F-FN         (uuid 2))
(def F-INVOKE     (uuid 10))
(def F-MERGE-IN   (uuid 11))
(def F-ROUTER-RES (uuid 12))
(def F-ROUTER-RNG (uuid 13))
(def F-APP-RING   (uuid 14))
(def F-TARGET     (uuid 15))
(def F-INT-REQ    (uuid 16))

(def S-ARG  (uuid 100))
(def S-FUNC (uuid 101))
(def S-M    (uuid 102))

(def B-ROUTER-RES-ARG (uuid 200))
(def B-ROUTER-RNG-M   (uuid 201))
(def B-APP-RING-FUNC  (uuid 202))


(def fixture
  {:fns
   [{:id F-ANY        :name "any"}
    {:id F-FN         :name "fn"}
    {:id F-INVOKE     :name "invoke"     :return-type-fn-id F-ANY :parent-ids []}
    {:id F-MERGE-IN   :name "merge-in"   :return-type-fn-id F-ANY :parent-ids []}
    {:id F-ROUTER-RES :name "router-result"        :parent-ids [F-INVOKE]}
    {:id F-ROUTER-RNG :name "router-ring-response" :parent-ids [F-MERGE-IN]}
    {:id F-APP-RING   :name "_app-ring-response"   :parent-ids [F-ROUTER-RNG]}
    {:id F-TARGET     :name "_router"              :parent-ids []}
    {:id F-INT-REQ    :name "internal-request"     :parent-ids []}]
   :slots
   [{:id S-ARG  :name "arg"  :type-fn-id F-ANY}
    {:id S-FUNC :name "func" :type-fn-id F-FN}
    {:id S-M    :name "m"    :type-fn-id F-ANY}]
   :fn-slots
   [{:fn-id F-INVOKE   :slot-id S-ARG  :position 0}
    {:fn-id F-INVOKE   :slot-id S-FUNC :position 1}
    {:fn-id F-MERGE-IN :slot-id S-M    :position 0}]
   :bindings
   [{:id B-ROUTER-RES-ARG :fn-id F-ROUTER-RES :slot-id S-ARG  :ref-fn-id F-INT-REQ}
    {:id B-ROUTER-RNG-M   :fn-id F-ROUTER-RNG :slot-id S-M    :ref-fn-id F-ROUTER-RES}
    {:id B-APP-RING-FUNC  :fn-id F-APP-RING   :slot-id S-FUNC :ref-fn-id F-TARGET}]
   :list-items []})


(deftest derive-fn-slot-views-emits-ref-chain-binding-anchor
  (testing "Pass 2 — anchor row for binding on slot whose owner is outside parent chain"
    (let [args (derive-fn-slot-views fixture)
          ;; Anchor row for APP-RING + S-FUNC.
          row (first (filter #(and (= (:fn-id %) F-APP-RING)
                                   (= (:slot-id %) S-FUNC))
                             args))]
      (is row "Anchor row for ref-chain-propagated binding must be emitted")
      (is (= F-TARGET (:ref-id row))
          "Anchor must carry the binding's ref target")
      (is (nil? (:source-id row))
          ":source-id must be nil — the slot is not on APP-RING's parent chain")
      (is (= "func" (:name row))
          "Slot name surfaces unchanged"))))


(deftest derive-fn-slot-views-skips-non-substitution-bindings
  (testing "Pass 2 must NOT emit duplicate rows when slot's owner IS in chain"
    (let [args (derive-fn-slot-views fixture)
          ;; ROUTER-RES binds :arg (S-ARG). Owner of S-ARG is INVOKE,
          ;; which IS in ROUTER-RES's parent chain — Pass 1 covers it,
          ;; Pass 2 must not re-emit.
          rows (filter #(and (= (:fn-id %) F-ROUTER-RES)
                             (= (:slot-id %) S-ARG))
                       args)]
      (is (= 1 (count rows)) "Exactly one anchor for parent-chain slot"))))


(deftest derive-fn-slot-views-emits-only-substitution-context-bindings
  (testing "Pass 2 must NOT fire for non-ref-chain bindings (no spurious anchors)"
    (let [args (derive-fn-slot-views fixture)
          ;; ROUTER-RNG binds :m. Owner of S-M is MERGE-IN — IS in
          ;; ROUTER-RNG's parent chain, so Pass 2 must skip it.
          ;; Only Pass 1 contributes one row.
          rows-for-router-rng-m (filter #(and (= (:fn-id %) F-ROUTER-RNG)
                                              (= (:slot-id %) S-M))
                                        args)
          ;; APP-RING binds :func. Owner of S-FUNC is INVOKE — NOT
          ;; in APP-RING's parent chain (chain is APP-RING →
          ;; ROUTER-RNG → MERGE-IN). Pass 2 emits this row; Pass 1
          ;; finds no fn-slot for S-FUNC along the chain.
          rows-for-app-ring-func (filter #(and (= (:fn-id %) F-APP-RING)
                                               (= (:slot-id %) S-FUNC))
                                         args)]
      (is (= 1 (count rows-for-router-rng-m))
          "MERGE-IN's slot reached via parent chain produces exactly one row")
      (is (= 1 (count rows-for-app-ring-func))
          "INVOKE's slot reached via ref-chain also produces exactly one row"))))
