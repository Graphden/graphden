(ns graphden.layout.bindings-test
  "Directed tests for the sequence-anchor walk — which items a list slot
   shows on a card whose fn did not bind the list itself.

   The `:source-id` of an anchor row points at the slot's DECLARING fn
   (the base-fn), so before `parent-anchor-of` a child appending to
   `add-10 [10]` prepended `add`'s empty chain and drew its own items
   alone, and a child without a binding drew nothing at any depth. The
   walk now resolves the parent by inheritance, and a pass-through anchor
   (no items / value / ref of its own) inherits its parent's list only
   as far as the reader has unfolded."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.layout.bindings :as bnd]))


(def ^:private base (random-uuid))
(def ^:private p (random-uuid))
(def ^:private c (random-uuid))
(def ^:private slot (random-uuid))


;; base declares the list; p seeds [10]; c binds nothing.
(def ^:private a-base {:id "a-base" :fn-id base :slot-id slot :type :sequence})
(def ^:private i1 {:id "i1" :fn-id p :slot-id slot :type :sequence :item-id 1 :value 10})
(def ^:private a-p {:id "a-p" :fn-id p :slot-id slot :type :sequence :next-arg-id "i1" :append? true})
(def ^:private a-c {:id "a-c" :fn-id c :slot-id slot :type :sequence :source-id "a-base"})
(def ^:private arg-map {"a-base" a-base "i1" i1 "a-p" a-p "a-c" a-c})


(def ^:private lookups
  {:fn-map {base {:parent-ids []} p {:parent-ids [base]} c {:parent-ids [p]}}
   :args-by-fn {base [a-base] p [a-p i1] c [a-c]}
   :slot-map {}})


(deftest a-pass-through-anchor-inherits-only-what-is-unfolded
  (testing "level 0 — the card shows what its fn binds: nothing here"
    (is (= [] (bnd/walk-anchor-chain a-c arg-map lookups nil))))
  (testing "unfolded to the parent — the parent's items, then the child's tail"
    (is (= ["i1"] (mapv :id (bnd/walk-anchor-chain a-c arg-map lookups #{c p}))))
    (let [entries (bnd/expand-sequence-anchor a-c "nums" arg-map lookups #{c p})]
      (is (= 2 (count entries)))
      (is (= :value (:kind (first entries))))
      (is (true? (:seq-tail? (last entries))) "the tail follows the inherited items")
      (is (= c (:fn-id (last entries))) "and it is the CHILD's — its append lands on c")))
  (testing "unfolded only to itself — the parent's row is folded, its list stays off"
    (is (= [] (bnd/walk-anchor-chain a-c arg-map lookups #{c}))))
  (testing "`true` — every ancestor, however far"
    (is (= ["i1"] (mapv :id (bnd/walk-anchor-chain a-c arg-map lookups true))))))


(deftest an-appending-anchor-prepends-the-parent-resolved-by-inheritance
  (let [i2 {:id "i2" :fn-id c :slot-id slot :type :sequence :item-id 2 :value 20}
        a-c* (assoc a-c :next-arg-id "i2" :append? true)
        arg-map* (assoc arg-map "i2" i2 "a-c" a-c*)
        lookups* (assoc-in lookups [:args-by-fn c] [a-c* i2])]
    (testing "with lookups the parent is found through parent-ids, not :source-id"
      (is (= ["i1" "i2"] (mapv :id (bnd/walk-anchor-chain a-c* arg-map* lookups* nil)))))
    (testing "the legacy 2-arity follows :source-id — the base's empty chain"
      (is (= ["i2"] (mapv :id (bnd/walk-anchor-chain a-c* arg-map*)))))))


(deftest the-tail-is-emitted-for-a-closed-list-too
  ;; The client draws a lock there (`listClosedBy` on the node) — a missing
  ;; tail said nothing about why.
  (let [entries (bnd/expand-sequence-anchor a-p "nums" arg-map lookups nil)]
    (is (= 2 (count entries)))
    (is (true? (:sequence-anchor? (last entries))))))


(deftest a-binding-on-a-rename-family-root-marks-every-view-of-it-bound
  ;; The write path canonicalises a binding to its rename family's ROOT
  ;; (`body` over assoc's :value lands on :value), while the card's arg row
  ;; names the VIEW. Keyed by the root alone, the view's `+` outlived the
  ;; bind (lesson 38, 2026-09-22): the placeholder for :body stayed after
  ;; `hello` was written on it.
  (let [root (random-uuid) view (random-uuid) leaf (random-uuid)
        owner (random-uuid) child (random-uuid)
        lk (lookups/build-lookups
             {:fns [{:id owner :name "ring-response" :parent-ids []}
                    {:id child :name "tutorial-hello" :parent-ids [owner]}]
              :slots [{:id root :name "value"}
                      {:id view :name "body" :source-slot-id root}
                      {:id leaf :name "greeting" :source-slot-id view}]
              :fn-slots [{:fn-id owner :slot-id view :position 0}
                         {:fn-id child :slot-id leaf :position 0}]
              :bindings [{:id "b1" :fn-id child :slot-id root :value "hello" :value-present true}]
              :list-items []})
        b (bnd/add-bindings-from-fn child {} lk)]
    (testing "the family is one slot to the card: root, view and the view of the view"
      (is (contains? b root))
      (is (contains? b view))
      (is (contains? b leaf))
      (is (= "hello" (:value (get b view))))
      (is (= "b1" (:binding-id (get b leaf)))))))
