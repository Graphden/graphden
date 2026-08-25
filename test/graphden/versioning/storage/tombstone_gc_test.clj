(ns ^:integration graphden.versioning.storage.tombstone-gc-test
  "Safety + reclamation tests for `tombstone-gc-sweep!`. The GC hard-purges
   entities that are dead on EVERY branch; the catastrophic failure mode is
   deleting a fn that some branch still resolves to a LIVE version, so the
   bulk of these tests pin the NOT-purged cases (cross-branch-live,
   fork-inheritance, referenced-as-parent, retention)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *container* nil)
(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- base-storage
  []
  (let [schema (-> (mds/create-builder) (gds/extend-builder) (vds/extend-builder)
                   (vts/extend-builder) (ds/build))]
    (-> (pg/create-storage (th/get-container-config *container*))
        (sp/initialize-with-cleanup! schema))))


(defn- tombstone-delete!
  "User-facing delete — writes a TOMBSTONE (what the GC reclaims), like
   `crud.entities/delete-entity` does. Raw `sp/delete-entity` would hard-
   delete (the default), which is a different path."
  [vf id]
  (binding [vs/*tombstone-delete?* true]
    (sp/delete-entity vf :fn id)))


(defn- live?
  "Is fn `id` resolved (visible) on the branch `vf` points at?"
  [vf id]
  (boolean (seq (sp/query-entities vf :fn {:id id}))))


(defn- identity-exists?
  "Does the raw identity row for fn `id` still exist (post-purge check)?"
  [base id]
  (boolean (seq (sp/query-entities base :fn {:id id}))))


(deftest purges-a-fn-created-and-deleted-on-main
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [f (sp/create-entity v :fn {:name "churn" :parent-ids [] :description "h"})]
        (tombstone-delete! v (:id f))                 ; tombstone on main
        (testing "precondition: gone from the resolved view, identity row lingers"
          (is (not (live? v (:id f))))
          (is (identity-exists? base (:id f))))
        (let [purged (vs/tombstone-gc-sweep! base 0)]
          (testing "GC purges the dead entity — identity + versions gone"
            (is (= 1 (:fn purged)))
            (is (not (identity-exists? base (:id f)))))))
      (finally (sp/close base)))))


(deftest does-not-purge-a-fn-still-live-on-another-branch
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [f (sp/create-entity v :fn {:name "shared" :parent-ids [] :description "h"})
            b (vs/create-branch! v "delete-here")
            vb (vs/switch-branch v (:id b))]
        (tombstone-delete! vb (:id f))                ; tombstone on B only; live on main
        (testing "precondition: deleted on B, still live on main"
          (is (not (live? vb (:id f))))
          (is (live? v (:id f))))
        (let [purged (vs/tombstone-gc-sweep! base 0)]
          (testing "GC must NOT purge — it resolves live on main"
            (is (zero? (:fn purged)))
            (is (identity-exists? base (:id f)))
            (is (live? v (:id f))))))
      (finally (sp/close base)))))


(deftest purges-when-a-fork-inherits-the-parents-delete-no-resurrection
  ;; A fork is a REFERENCE, not a snapshot: after main deletes the fn, the
  ;; fork forked BEFORE the delete still resolves main's LATEST version — the
  ;; tombstone — so it too sees the fn gone. The entity is dead everywhere and
  ;; IS purgeable. The key safety assertion is that purging the version rows
  ;; does NOT resurrect it on the fork (which would happen if resolution fell
  ;; back to an inherited live version — it must not, because there is none).
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [f (sp/create-entity v :fn {:name "inherited" :parent-ids [] :description "h"})
            c (vs/create-branch! v "fork-before-delete")
            vc (vs/switch-branch v (:id c))]
        (tombstone-delete! v (:id f))                 ; tombstone on MAIN, after the fork
        (testing "precondition: the fork inherits main's LATEST (the delete)"
          (is (not (live? v (:id f))))
          (is (not (live? vc (:id f)))))
        (let [purged (vs/tombstone-gc-sweep! base 0)]
          (testing "GC purges it, and the fork still sees it absent (no resurrection)"
            (is (= 1 (:fn purged)))
            (is (not (identity-exists? base (:id f))))
            (is (not (live? vc (:id f)))))))
      (finally (sp/close base)))))


(deftest does-not-purge-a-fn-a-branch-holds-its-own-live-version
  ;; The real cross-branch-live case that must survive: a branch makes its own
  ;; LIVE edit to a fn (its own version row), then main deletes it. main
  ;; resolves deleted, but the branch resolves ITS live version — dead-on-
  ;; every-branch? is false, so the GC must leave the entity (and its history)
  ;; intact.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [f (sp/create-entity v :fn {:name "edited-on-branch" :parent-ids [] :description "h"})
            b (vs/create-branch! v "edits-it")
            vb (vs/switch-branch v (:id b))]
        (sp/update-entity vb :fn (:id f) {:description "branch's own live edit"})
        (tombstone-delete! v (:id f))                 ; delete on main
        (testing "precondition: deleted on main, still live (edited) on the branch"
          (is (not (live? v (:id f))))
          (is (live? vb (:id f))))
        (let [purged (vs/tombstone-gc-sweep! base 0)]
          (testing "GC must NOT purge — the branch resolves its own live version"
            (is (zero? (:fn purged)))
            (is (identity-exists? base (:id f)))
            (is (live? vb (:id f))))))
      (finally (sp/close base)))))


(deftest respects-the-retention-window
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [f (sp/create-entity v :fn {:name "recent" :parent-ids [] :description "h"})]
        (tombstone-delete! v (:id f))
        (testing "a tombstone younger than the retention window is left alone"
          (let [purged (vs/tombstone-gc-sweep! base (* 60 60 1000))]  ; 1h retention
            (is (zero? (:fn purged)))
            (is (identity-exists? base (:id f))))))
      (finally (sp/close base)))))


(deftest does-not-purge-a-dead-fn-still-named-as-a-live-parent
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [p (sp/create-entity v :fn {:name "parent" :parent-ids [] :description "h"})
            _ (sp/create-entity v :fn {:name "child" :parent-ids [(:id p)] :description "h"})]
        (tombstone-delete! v (:id p))                 ; tombstone the parent
        (let [purged (vs/tombstone-gc-sweep! base 0)]
          (testing "GC must NOT purge a fn a live child still lists as a parent"
            (is (zero? (:fn purged)))
            (is (identity-exists? base (:id p))))))
      (finally (sp/close base)))))


;; The GC's inbound-ref guard is the FULL ref surface, not just parent-ids
;; (the 2026-08-25 finding: it purged a fn still referenced by a
;; binding/slot/type-FK, dangling the ref forever — an editor random-id can't
;; be re-minted). These three pin the non-parent families the old guard missed.

(deftest does-not-purge-a-dead-fn-still-referenced-by-a-slot-type
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [tf (sp/create-entity v :fn {:name "slot-type" :parent-ids [] :description "h"})
            _  (sp/create-entity v :slot {:name "typed-x" :type-fn-id (:id tf)})]
        (tombstone-delete! v (:id tf))
        (let [purged (vs/tombstone-gc-sweep! base 0)]
          (testing "GC must NOT purge a fn a live slot names as its :type-fn-id"
            (is (zero? (:fn purged)))
            (is (identity-exists? base (:id tf))))))
      (finally (sp/close base)))))


(deftest does-not-purge-a-dead-fn-still-referenced-as-a-return-type
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [rt (sp/create-entity v :fn {:name "ret-type" :parent-ids [] :description "h"})
            _  (sp/create-entity v :fn {:name "uses-ret" :parent-ids []
                                        :return-type-fn-id (:id rt) :description "h"})]
        (tombstone-delete! v (:id rt))
        (let [purged (vs/tombstone-gc-sweep! base 0)]
          (testing "GC must NOT purge a fn a live fn names as its :return-type-fn-id"
            (is (zero? (:fn purged)))
            (is (identity-exists? base (:id rt))))))
      (finally (sp/close base)))))


(deftest does-not-purge-a-dead-fn-referenced-by-a-binding-on-another-branch
  ;; The finding's exact scenario: X is referenced by a binding.ref-fn-id set
  ;; on branch B (a VERSION-plane ref, invisible to a delete evaluated on main),
  ;; X is then tombstoned on main and dead on every branch. Purging X would
  ;; leave B's binding pointing at nothing, unrecoverably.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [x     (sp/create-entity v :fn {:name "x-target" :parent-ids [] :description "h"})
            owner (sp/create-entity v :fn {:name "y-owner" :parent-ids [] :description "h"})
            slot  (sp/create-entity v :slot {:name "y-slot" :type-fn-id (:id owner)})
            bnd   (sp/create-entity v :binding {:fn-id (:id owner) :slot-id (:id slot) :value "v"})
            b     (vs/create-branch! v "refs-x")
            vb    (vs/switch-branch v (:id b))]
        (sp/update-entity vb :binding (:id bnd) {:ref-fn-id (:id x)})  ; version-plane ref on B
        (tombstone-delete! v (:id x))                                  ; delete X on main
        (testing "precondition: X dead on main"
          (is (not (live? v (:id x)))))
        (let [purged (vs/tombstone-gc-sweep! base 0)]
          (testing "GC must NOT purge X — B's binding still references it"
            (is (zero? (:fn purged)))
            (is (identity-exists? base (:id x))))))
      (finally (sp/close base)))))


;; The GC RECLAIMS a dead entity's whole OWNED subgraph — a user delete
;; tombstones only the target row, so without a cascade the owned children leak
;; forever (storage cost on cloud) and dangle the ownership FK at the purged row.

(deftest purges-a-dead-fns-owned-subgraph
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [tf (sp/create-entity v :fn {:name "sub-type" :parent-ids [] :description "h"})
            f  (sp/create-entity v :fn {:name "sub-owner" :parent-ids [] :description "h"})
            s  (sp/create-entity v :slot {:name "sub-slot" :type-fn-id (:id tf)})
            b  (sp/create-entity v :binding {:fn-id (:id f) :slot-id (:id s) :list-append true})
            i  (sp/create-entity v :binding-list-item {:binding-id (:id b) :position 0 :value "x"})]
        (tombstone-delete! v (:id f))                 ; delete only the fn
        (vs/tombstone-gc-sweep! base 0)
        (testing "the fn AND its owned binding + list-item are all reclaimed"
          (is (not (identity-exists? base (:id f))))
          (is (empty? (sp/query-entities base :binding {:id (:id b)}))
              "owned binding is not leaked")
          (is (empty? (sp/query-entities base :binding-list-item {:id (:id i)}))
              "owned list-item is not leaked")))
      (finally (sp/close base)))))


(deftest purges-a-dead-bindings-own-list-items
  ;; Finding: the GC guarded only :fn; a dead :binding was purged bare, dangling
  ;; its live-orphaned binding-list-item.binding-id. Cascade the items with it.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [tf (sp/create-entity v :fn {:name "b-type" :parent-ids [] :description "h"})
            f  (sp/create-entity v :fn {:name "b-owner" :parent-ids [] :description "h"})
            s  (sp/create-entity v :slot {:name "b-slot" :type-fn-id (:id tf)})
            b  (sp/create-entity v :binding {:fn-id (:id f) :slot-id (:id s) :list-append true})
            i  (sp/create-entity v :binding-list-item {:binding-id (:id b) :position 0 :value "x"})]
        (binding [vs/*tombstone-delete?* true]
          (sp/delete-entity v :binding (:id b)))      ; delete only the binding; fn stays live
        (vs/tombstone-gc-sweep! base 0)
        (testing "the binding and its own list-items are reclaimed; the owning fn survives"
          (is (empty? (sp/query-entities base :binding {:id (:id b)})))
          (is (empty? (sp/query-entities base :binding-list-item {:id (:id i)}))
              "no dangling binding-list-item.binding-id at the purged binding")
          (is (identity-exists? base (:id f)) "the live owning fn is untouched")))
      (finally (sp/close base)))))
