(ns graphden.executor.composition.write-records-test
  "Regression tests for the declarative behaviour of
   `composition.core/write-records!` — re-syncing a fn must make its
   body (fn-slots / bindings / list-items) match the new declaration,
   dropping rows a prior definition left behind.

   Guards the `r404`/`r405`/`r500` duplicate-card bug: a package
   refactor moved a binding to a new slot, the additive upsert kept
   the old binding row, and the editor rendered both."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.core :as cc]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture))


(defn- fn-rec
  [id nm parent-ids]
  {:kind :fn :id id :name nm :namespace-id nil
   :parent-ids parent-ids :base-fn-id nil
   :element-fn-id nil :return-type-fn-id nil
   :anonymous-hash nil :constraint nil})


(defn- slot-rec
  [id nm type-fn-id]
  {:kind :slot :id id :name nm :type-fn-id type-fn-id :required true})


(defn- fn-slot-rec
  "Mirror the package parser: the fn-slot id is deterministic in
   `(fn-id, slot-id)`, so a re-sync upserts the same row in place."
  [fn-id slot-id position]
  {:kind :fn-slot :id (ids/fn-slot-id fn-id slot-id)
   :fn-id fn-id :slot-id slot-id :position position})


;; ============================================================================
;; A binding that moves to a new slot must not leave its old row behind
;; ============================================================================

(deftest reconcile-stale-binding-test
  (testing "re-syncing a fn drops a binding the new declaration moved off a slot"
    (let [storage (setup/create-test-storage)]
      (try
        (let [int-id   (get setup/primitive-fn-ids :int)
              host-id  (random-uuid)
              child-id (random-uuid)
              slot-a   (random-uuid)
              slot-b   (random-uuid)
              bind-a   (random-uuid)
              bind-b   (random-uuid)]
          ;; Sync 1 — child binds slot-a.
          (cc/write-records!
            storage
            [(fn-rec host-id "wr-host" [])
             (slot-rec slot-a "a" int-id)
             (fn-slot-rec host-id slot-a 0)
             (fn-rec child-id "wr-child" [host-id])
             {:kind :binding :id bind-a :fn-id child-id :slot-id slot-a :value 1}]
            {})
          (is (= #{bind-a}
                 (set (map :id (sp/query-entities storage :binding {:fn-id child-id})))))

          ;; Sync 2 — the refactored child binds slot-b instead.
          (cc/write-records!
            storage
            [(fn-rec host-id "wr-host" [])
             (slot-rec slot-a "a" int-id)
             (slot-rec slot-b "b" int-id)
             (fn-slot-rec host-id slot-a 0)
             (fn-slot-rec host-id slot-b 1)
             (fn-rec child-id "wr-child" [host-id])
             {:kind :binding :id bind-b :fn-id child-id :slot-id slot-b :value 2}]
            {})
          (is (= #{bind-b}
                 (set (map :id (sp/query-entities storage :binding {:fn-id child-id}))))
              "the stale slot-a binding was reaped; only the new one remains"))
        (finally (sp/close storage))))))


;; ============================================================================
;; Reconciliation must not over-reap rows the declaration still carries
;; ============================================================================

(deftest reconcile-keeps-current-rows-test
  (testing "an unchanged re-sync leaves the fn's body intact (idempotent)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [int-id   (get setup/primitive-fn-ids :int)
              host-id  (random-uuid)
              child-id (random-uuid)
              slot-a   (random-uuid)
              bind-a   (random-uuid)
              records  [(fn-rec host-id "wrk-host" [])
                        (slot-rec slot-a "a" int-id)
                        (fn-slot-rec host-id slot-a 0)
                        (fn-rec child-id "wrk-child" [host-id])
                        {:kind :binding :id bind-a :fn-id child-id
                         :slot-id slot-a :value 7}]]
          (cc/write-records! storage records {})
          (cc/write-records! storage records {})
          (is (= #{bind-a}
                 (set (map :id (sp/query-entities storage :binding {:fn-id child-id})))))
          (is (= 1 (count (sp/query-entities storage :fn-slot {:fn-id host-id})))))
        (finally (sp/close storage))))))


;; ============================================================================
;; Reconciliation cascades: a dropped binding takes its list-items with it,
;; and a dropped fn-slot is reaped too
;; ============================================================================

(deftest reconcile-cascade-test
  (testing "removing a binding reaps its list-items; a dropped fn-slot is reaped"
    (let [storage (setup/create-test-storage)]
      (try
        (let [int-id   (get setup/primitive-fn-ids :int)
              host-id  (random-uuid)
              child-id (random-uuid)
              slot-seq (random-uuid)
              slot-x   (random-uuid)
              bind-seq (random-uuid)
              item-0   (random-uuid)
              item-1   (random-uuid)]
          ;; Sync 1 — child binds a sequence slot with two items, and
          ;; host exposes an extra slot-x.
          (cc/write-records!
            storage
            [(fn-rec host-id "wrc-host" [])
             (slot-rec slot-seq "items" int-id)
             (slot-rec slot-x "x" int-id)
             (fn-slot-rec host-id slot-seq 0)
             (fn-slot-rec host-id slot-x 1)
             (fn-rec child-id "wrc-child" [host-id])
             {:kind :binding :id bind-seq :fn-id child-id :slot-id slot-seq
              :list-append true}
             {:kind :binding-list-item :id item-0 :binding-id bind-seq
              :position 0 :value 10}
             {:kind :binding-list-item :id item-1 :binding-id bind-seq
              :position 1 :value 20}]
            {})
          (is (= 2 (count (sp/query-entities storage :binding-list-item
                                             {:binding-id bind-seq}))))

          ;; Sync 2 — child no longer binds anything; host drops slot-x.
          (cc/write-records!
            storage
            [(fn-rec host-id "wrc-host" [])
             (slot-rec slot-seq "items" int-id)
             (fn-slot-rec host-id slot-seq 0)
             (fn-rec child-id "wrc-child" [host-id])]
            {})
          (is (empty? (sp/query-entities storage :binding {:fn-id child-id}))
              "the dropped binding was reaped")
          (is (empty? (sp/query-entities storage :binding-list-item
                                         {:binding-id bind-seq}))
              "the reaped binding's list-items went with it")
          (is (= 1 (count (sp/query-entities storage :fn-slot {:fn-id host-id})))
              "the dropped fn-slot was reaped"))
        (finally (sp/close storage))))))


;; ============================================================================
;; Shrink → regrow across syncs on VERSIONED storage (2026-07-20 incident)
;; ============================================================================

(deftest reconcile-shrink-then-regrow-versioned-test
  ;; The live-demo route-vanish incident: sync 1 declares a 3-item list,
  ;; sync 2 shrinks it to 1 (reconcile hard-deletes the tail), sync 3
  ;; regrows it to 3 — the tail items re-mint the SAME deterministic
  ;; (binding, position) ids. Pre-fix the tail's identity rows survived
  ;; sync 2 versionless, so sync 3's content-equal upsert diffed to
  ;; nothing, wrote no version, and the items stayed invisible on every
  ;; list read (a fresh DB stayed green — no ghosts to revive).
  (let [storage (setup/create-versioned-test-storage)]
    (try
      (let [int-id   (get setup/primitive-fn-ids :int)
            host-id  (random-uuid)
            child-id (random-uuid)
            slot-seq (random-uuid)
            bind-seq (ids/binding-id child-id slot-seq)
            item-id  #(ids/binding-list-item-id bind-seq %)
            item-rec (fn [pos v]
                       {:kind :binding-list-item :id (item-id pos)
                        :binding-id bind-seq :position pos :value v})
            base-recs [(fn-rec host-id "wrv-host" [])
                       (slot-rec slot-seq "items" int-id)
                       (fn-slot-rec host-id slot-seq 0)
                       (fn-rec child-id "wrv-child" [host-id])
                       {:kind :binding :id bind-seq :fn-id child-id
                        :slot-id slot-seq :list-append true}]
            visible #(sort (map :value (sp/query-entities
                                         storage :binding-list-item
                                         {:binding-id bind-seq})))]
        ;; Sync 1 — three items.
        (cc/write-records!
          storage (into base-recs [(item-rec 0 10) (item-rec 1 20) (item-rec 2 30)]) {})
        (is (= [10 20 30] (visible)))
        ;; Sync 2 — shrink to one; the tail is reconciled away.
        (cc/write-records! storage (into base-recs [(item-rec 0 10)]) {})
        (is (= [10] (visible)))
        ;; Sync 3 — regrow to three; the SAME tail ids come back and
        ;; must be visible again on the resolved read.
        (cc/write-records!
          storage (into base-recs [(item-rec 0 10) (item-rec 1 20) (item-rec 2 30)]) {})
        (is (= [10 20 30] (visible))
            "regrown tail items are visible — pre-fix they revived versionless ghosts"))
      (finally (sp/close (vs/unwrap storage))))))
