(ns ^:integration graphden.versioning.storage.core-test
  "Tests for `graphden.versioning.storage.core` — the VersionedStorage
   decorator and the branch lifecycle (create / switch / list / get /
   delete), branch isolation, conflict detection, merge, and the
   batched graph-entity loader.

   Storage stack: PostgreSQL testcontainer + graph + versioning +
   traits schema, mirroring `versioning.merge.core-test`."
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
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.merge :as mrg]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- base-storage
  "A PostgreSQL storage initialized with the graph + versioning +
   traits schema — the substrate `wrap-with-versioning` expects."
  []
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (vds/extend-builder)
                   (vts/extend-builder)
                   (ds/build))]
    (-> (pg/create-storage (th/get-container-config *container*))
        (sp/initialize-with-cleanup! schema))))


;; ============================================================================
;; wrap-with-versioning / versioned-storage? / unwrap / current-branch-id
;; ============================================================================

(deftest wrap-with-versioning-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (testing "wrapping yields a VersionedStorage on the main branch"
        (is (true? (vs/versioned-storage? v)))
        (is (false? (vs/versioned-storage? base)))
        (let [main (vs/get-branch v (vs/current-branch-id v))]
          (is (= "main" (:name main)))))

      (testing "unwrap returns the base; a non-versioned storage is unchanged"
        (is (identical? base (vs/unwrap v)))
        (is (identical? base (vs/unwrap base))))

      (testing "wrapping onto an unknown named branch throws :not-found"
        (let [ex (try (vs/wrap-with-versioning base "no-such-branch")
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :not-found (:type (ex-data ex))))))
      (finally (sp/close base)))))


;; ============================================================================
;; Branch lifecycle — create / list / get / switch
;; ============================================================================

(deftest branch-lifecycle-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main-id (vs/current-branch-id v)
            feature (vs/create-branch! v "feature")]
        (testing "create-branch! forks from the current branch"
          (is (= "feature" (:name feature)))
          (is (= main-id (:base-branch-id feature))))

        (testing "list-branches sees both main and the new branch"
          (let [names (set (map :name (vs/list-branches v)))]
            (is (contains? names "main"))
            (is (contains? names "feature"))))

        (testing "get-branch resolves by id; an unknown id → nil"
          (is (= "feature" (:name (vs/get-branch v (:id feature)))))
          (is (nil? (vs/get-branch v (random-uuid)))))

        (testing "switch-branch returns a storage pointed at the target branch"
          (let [vf (vs/switch-branch v (:id feature))]
            (is (= (:id feature) (vs/current-branch-id vf)))
            (is (= main-id (vs/current-branch-id v)))))

        (testing "switch-branch to an unknown branch throws :not-found"
          (let [ex (try (vs/switch-branch v (random-uuid))
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= :not-found (:type (ex-data ex)))))))
      (finally (sp/close base)))))


;; ============================================================================
;; Branch isolation — version rows resolve along the branch chain
;; ============================================================================

(deftest branch-isolation-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [on-main (sp/create-entity v :fn {:name "main-fn" :parent-ids []
                                             :description "h"})
            feature (vs/create-branch! v "iso-feature")
            vf      (vs/switch-branch v (:id feature))
            on-feat (sp/create-entity vf :fn {:name "feat-fn" :parent-ids []
                                              :description "h"})]
        (testing "a fn created on main is visible from a child branch"
          (let [feat-fns (set (map :id (:fns (vs/query-all-graph-entities vf))))]
            (is (contains? feat-fns (:id on-main)))))

        (testing "a fn created on the child branch is NOT visible on main"
          (let [main-fns (set (map :id (:fns (vs/query-all-graph-entities v))))]
            (is (contains? main-fns (:id on-main)))
            (is (not (contains? main-fns (:id on-feat)))))))
      (finally (sp/close base)))))


(deftest binding-required-is-branch-isolated-test
  ;; Regression: `binding.required` (per-binding optional→required
  ;; narrowing, honoured by the executor's `effective-required?`) was
  ;; absent from the binding `version-data-fields`, so a branch-only
  ;; change wrote through to the SHARED identity row and leaked the
  ;; narrowing onto every other branch. Now versioned like its sibling
  ;; binding flags (`:terminal`, `:list-append`, …).
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [owner (sp/create-entity v :fn {:name "req-iso-fn" :parent-ids []
                                           :description "h"})
            slot  (sp/create-entity v :slot {:name "x" :type-fn-id (:id owner)})
            bnd   (sp/create-entity v :binding {:fn-id (:id owner)
                                                :slot-id (:id slot)
                                                :value "v"})
            id    (:id bnd)
            feature (vs/create-branch! v "req-iso-feature")
            vf      (vs/switch-branch v (:id feature))
            _       (sp/update-entity vf :binding id {:required true})]
        (testing "a branch-only :required change does NOT leak to main"
          (is (not (true? (:required (sp/read-entity v :binding id))))
              "main keeps the binding non-required")
          (is (true? (:required (sp/read-entity vf :binding id)))
              "feature branch sees the narrowed :required true")))
      (finally (sp/close base)))))


;; ============================================================================
;; query-all-graph-entities
;; ============================================================================

(deftest query-all-graph-entities-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (testing "returns the five slot/binding-model tables"
        (let [g (vs/query-all-graph-entities v)]
          (is (every? #(contains? g %)
                      [:fns :slots :fn-slots :bindings :list-items]))
          (is (every? vector? (vals g)))))
      (finally (sp/close base)))))


;; ============================================================================
;; delete-branch!
;; ============================================================================

(deftest delete-branch-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (testing "a leaf branch is deleted and drops out of list-branches"
        (let [leaf (vs/create-branch! v "to-delete")]
          (is (true? (vs/delete-branch! v (:id leaf))))
          (is (not (contains? (set (map :name (vs/list-branches v)))
                              "to-delete")))))

      (testing "deleting the root branch is forbidden"
        (let [ex (try (vs/delete-branch! v (vs/current-branch-id v))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :constraint-violation/root-branch-undeletable
                 (:type (ex-data ex))))))

      (testing "root protection is STRUCTURAL (base-branch-id nil), not the name 'main'"
        ;; Regression pin for the name-vs-id bug: a root branch named
        ;; anything but 'main' must still be undeletable. Create one
        ;; directly on the base storage (bypassing the name-based
        ;; bootstrap) so its identity is purely structural.
        (let [base* (:base-storage v)
              trunk-id (random-uuid)
              _     (sp/create-entity base* :branch
                                      {:id trunk-id
                                       :name "trunk"
                                       :base-branch-id nil
                                       :created-at (java.time.Instant/now)})
              ex    (try (vs/delete-branch! v trunk-id)
                         (catch clojure.lang.ExceptionInfo e e))]
          (is (= :constraint-violation/root-branch-undeletable
                 (:type (ex-data ex)))
              "a root branch NOT named 'main' is still protected by root-ness")))

      (testing "deleting a branch that has children is forbidden"
        (let [parent (vs/create-branch! v "parent-branch")
              vp     (vs/switch-branch v (:id parent))
              _      (vs/create-branch! vp "child-branch")
              ex     (try (vs/delete-branch! v (:id parent))
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :constraint-violation/branch-has-children
                 (:type (ex-data ex))))))

      (testing "deleting an unknown branch throws :not-found"
        (let [ex (try (vs/delete-branch! v (random-uuid))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :not-found (:type (ex-data ex))))))
      (finally (sp/close base)))))


;; ============================================================================
;; detect-conflicts / merge-branch!
;; ============================================================================

;; ============================================================================
;; diff-branches — resolved-view diff across the slot/binding model
;; ============================================================================

(deftest diff-branches-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main-id (vs/current-branch-id v)
            ;; seed: one fn on main, before any branch exists
            shared (sp/create-entity v :fn {:name "diff-shared" :parent-ids []
                                            :description "h0"})
            feature (vs/create-branch! v "diff-feature")
            vf      (vs/switch-branch v (:id feature))
            ;; mutate `shared` on feature only
            _       (sp/update-entity vf :fn (:id shared)
                                      {:description "edited-on-feature"})
            ;; create a brand-new fn on feature only
            feat-only (sp/create-entity vf :fn {:name "diff-feat-only" :parent-ids []
                                                :description "h-feat"})
            ;; create another fn on main, AFTER fork. With the live-
            ;; inheritance resolver this stays identical on both sides
            ;; (feature sees it via the chain), so it must NOT appear
            ;; in the diff — guards against accidental drift in the
            ;; resolver semantics.
            main-late (sp/create-entity v :fn {:name "diff-main-late" :parent-ids []
                                               :description "h-main"})]
        (testing "source=feature, target=main"
          (let [{:keys [diffs]} (mrg/diff-branches base (:id feature) main-id)
                by-id (group-by :entity-id diffs)
                shared-entry (first (get by-id (:id shared)))
                feat-entry   (first (get by-id (:id feat-only)))]
            (is (some? shared-entry))
            (is (= :modified (:change shared-entry)))
            (is (= "edited-on-feature"
                   (:description (:source-version shared-entry))))

            (is (some? feat-entry))
            (is (= :added-in-source (:change feat-entry))
                "feat-only doesn't resolve on main → :added-in-source")

            (is (empty? (get by-id (:id main-late)))
                "main-late inherits to feature (live chain) — not a diff")))

        (testing "source=main, target=feature flips the roles"
          (let [{:keys [diffs]} (mrg/diff-branches base main-id (:id feature))
                feat-entry (first (filter #(= (:id feat-only) (:entity-id %)) diffs))]
            (is (= :added-in-target (:change feat-entry)))))

        (testing "diffing a branch against itself returns nothing"
          (let [{:keys [diffs]} (mrg/diff-branches base (:id feature) (:id feature))]
            (is (empty? diffs)))))
      (finally (sp/close base)))))


(deftest merge-no-conflict-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main-id (vs/current-branch-id v)
            feature (vs/create-branch! v "merge-feature")
            vf      (vs/switch-branch v (:id feature))
            _       (sp/create-entity vf :fn {:name "merged-fn" :parent-ids []
                                              :description "h"})]
        (testing "no entity touched on both sides → detect-conflicts is empty"
          (let [{:keys [conflicts]} (vs/detect-conflicts v (:id feature))]
            (is (empty? conflicts))))

        (testing "merge-branch! records a branch-merge from source into target"
          (let [record (vs/merge-branch! v (:id feature))]
            (is (= (:id feature) (:source-branch-id record)))
            (is (= main-id (:target-branch-id record)))
            (is (some? (sp/read-entity base :branch-merge (:id record)))))))
      (finally (sp/close base)))))


(deftest sibling-merge-detects-conflict-regardless-of-creation-order-test
  ;; Regression: `fork-point`'s old `:else` fallback used the SOURCE
  ;; branch's created-at whenever neither branch was a direct child of the
  ;; other (two siblings off main). If the TARGET sibling was created — and
  ;; edited — BEFORE the source sibling existed, the target's edit predated
  ;; that fork-point, so `detect-conflicts` silently MISSED the overlap and
  ;; a merge would clobber the target's value with no prompt (a false
  ;; NEGATIVE — the dangerous kind). The LCA-based fork-point forks at the
  ;; EARLIER sibling's divergence, catching it.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      ;; Seed the contested entity on main so both siblings inherit it.
      (let [seeded (sp/create-entity v :fn {:name "contested" :parent-ids []
                                            :description "from-main"})
            id     (:id seeded)
            ;; TARGET sibling created + edited FIRST (v stays on main, so
            ;; both create-branch! calls fork from main → siblings).
            b-tgt  (vs/create-branch! v "sib-target")
            vb     (vs/switch-branch v (:id b-tgt))
            _      (sp/update-entity vb :fn id {:description "from-target"})
            _      (Thread/sleep 5)   ; guarantee a-src.created > b-tgt's edit
            ;; SOURCE sibling created AFTER — also off main.
            a-src  (vs/create-branch! v "sib-source")
            va     (vs/switch-branch v (:id a-src))
            _      (sp/update-entity va :fn id {:description "from-source"})]
        (testing "both siblings edited the same entity → a conflict, even
                  though the target's edit predates the source branch"
          (let [{:keys [conflicts]} (vs/detect-conflicts vb (:id a-src))]
            (is (= 1 (count conflicts))
                "the pre-fork target edit must not be silently dropped")
            (is (= id (:entity-id (first conflicts))))
            (is (= :fn (:entity-name (first conflicts)))))))
      (finally (sp/close base)))))


(deftest merge-applies-source-overlay-on-batch-read-path-test
  ;; Regression for #52. The batch read path (`resolve-all-entities`,
  ;; `resolve-entities-batch`, the executor's compiled-graph load via
  ;; `load-all-resolved`) used to call a "simplified" cache resolver
  ;; that walked the branch chain only — no `branch-merge` support.
  ;; A `POST /api/branches/:target/merge` would create the merge row
  ;; but reads on the target STILL saw the target's own version. The
  ;; user-visible symptom we hit in the browser smoke pass: smoke-branch
  ;; sets a list-item value to 100, main holds 42, merge endpoint
  ;; returns ok:true, executor on main still answers 42. The
  ;; per-entity `resolve-version` did walk merges; only the batch
  ;; path lagged.
  ;;
  ;; Acceptance: a `:fn` row whose only version overlay lives on the
  ;; merged-in source branch must show that overlay on the target
  ;; after `merge-branch!`. Same shape as the failing scenario, just
  ;; using `:description` instead of a list-item value because the
  ;; versioning machinery is the same for any version-data field.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [;; Seed an entity on main with the original value.
            seeded  (sp/create-entity v :fn {:name "merge-overlay-fn"
                                             :parent-ids []
                                             :description "from-main"})
            id      (:id seeded)
            ;; Fork a feature branch and rewrite the description there.
            feature (vs/create-branch! v "merge-overlay-feature")
            vf      (vs/switch-branch v (:id feature))
            _       (sp/update-entity vf :fn id {:description "from-feature"})]
        (testing "before merge: each branch sees its own version"
          (is (= "from-main" (:description (sp/read-entity v :fn id))))
          (is (= "from-feature" (:description (sp/read-entity vf :fn id)))))

        (testing "after merge: main's batch read picks up feature's overlay"
          (vs/merge-branch! v (:id feature))
          (is (= "from-feature"
                 (:description (sp/read-entity v :fn id)))
              "per-entity read (already merge-aware) sees the overlay")
          ;; `query-entities` exercises the batch path — the one
          ;; that #52 documented as silently dropping merges.
          (let [batch (sp/query-entities v :fn {:id id})]
            (is (= 1 (count batch)))
            (is (= "from-feature" (:description (first batch)))
                "batch read path must honor the branch-merge row")))

        (testing "an unrelated branch still sees main's version"
          (let [other  (vs/create-branch! v "merge-overlay-other")
                vother (vs/switch-branch v (:id other))]
            ;; "other" forked from main AFTER the merge already
            ;; promoted feature's version to main — so other inherits
            ;; the merged value too. Mirrors what the user expects
            ;; when starting work after a merge has landed.
            (is (= "from-feature"
                   (:description (sp/read-entity vother :fn id)))))))
      (finally (sp/close base)))))


(deftest branch-local-fn-does-not-propagate-on-merge-test
  ;; A `:fn` row whose effective `:branch-local?` is true (via own
  ;; flag OR via parent-ids closure) must NOT have its version row
  ;; surface on a sibling branch after merge. The branch-merge
  ;; pointer still lands (and OTHER non-branch-local entities DO
  ;; merge normally — see #52), but resolution filters foreign-branch
  ;; candidates for sticky fn-ids.
  ;;
  ;; This is the unit-level counterpart of the smoke-pass [7] test
  ;; in graphden.integration.smoke-pass-test, exercising the same
  ;; behaviour without the HTTP / packages stack on top.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [;; Local-marker base-fn (mirrors `:http-server` etc.).
            sticky-parent (sp/create-entity v :fn
                                            {:name "sticky-parent"
                                             :parent-ids []
                                             :description "h"
                                             :branch-local? true})
            ;; Non-local plain base-fn — control case.
            plain-parent  (sp/create-entity v :fn
                                            {:name "plain-parent"
                                             :parent-ids []
                                             :description "h"})
            feature (vs/create-branch! v "branch-local-feat")
            vf      (vs/switch-branch v (:id feature))
            ;; CHILD fn created on the feature branch, parented from
            ;; the sticky base-fn → effective branch-local via the
            ;; walker. Production analog: `{:parent :http-server …}`.
            sticky-child  (sp/create-entity vf :fn
                                            {:name "sticky-child"
                                             :parent-ids [(:id sticky-parent)]
                                             :description "h"})
            ;; CHILD fn parented from plain-parent — should merge.
            plain-child   (sp/create-entity vf :fn
                                            {:name "plain-child"
                                             :parent-ids [(:id plain-parent)]
                                             :description "h"})]
        ;; Sanity: feat sees both children.
        (is (= "sticky-child" (:name (sp/read-entity vf :fn (:id sticky-child)))))
        (is (= "plain-child"  (:name (sp/read-entity vf :fn (:id plain-child)))))

        (testing "before merge: main sees NEITHER child (no version on main)"
          (is (nil? (sp/read-entity v :fn (:id sticky-child))))
          (is (nil? (sp/read-entity v :fn (:id plain-child)))))

        (vs/merge-branch! v (:id feature))

        (testing "after merge: plain-child propagates, sticky-child does NOT"
          (is (= "plain-child"
                 (:name (sp/read-entity v :fn (:id plain-child))))
              "non-branch-local children merge normally")
          (is (nil? (sp/read-entity v :fn (:id sticky-child)))
              "branch-local fn is filtered out — identity exists but no version resolves"))

        (testing "feature branch still sees the sticky child after merge"
          ;; Feat's own-latest is still authoritative on its own
          ;; branch — the filter only drops FOREIGN-branch candidates.
          (is (= "sticky-child"
                 (:name (sp/read-entity vf :fn (:id sticky-child))))))

        (testing "batch path agrees: sticky-child absent from main's :fn list"
          (let [main-fns (sp/query-entities v :fn {})
                names (set (map :name main-fns))]
            (is (contains? names "plain-child"))
            (is (not (contains? names "sticky-child"))
                "resolve-all-entities on main filters the branch-local fn"))))
      (finally (sp/close base)))))


(deftest branch-local-binding-does-not-propagate-on-merge-test
  ;; Child-rows extension of the previous test. A `:binding` row's
  ;; visibility on a sibling branch after merge ALSO depends on the
  ;; owning fn's effective `:branch-local?`. Without this, main
  ;; would see orphan bindings whose `:fn-id` doesn't resolve there
  ;; — cosmetic noise + the `/api/graph/entities` payload bloated
  ;; with sticky-local subtree leaves.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [sticky-parent (sp/create-entity v :fn
                                            {:name "bind-sticky-parent"
                                             :parent-ids []
                                             :description "h"
                                             :branch-local? true})
            plain-parent  (sp/create-entity v :fn
                                            {:name "bind-plain-parent"
                                             :parent-ids []
                                             :description "h"})
            slot          (sp/create-entity v :slot
                                            {:name "x"
                                             :type-fn-id (:id sticky-parent)})
            feature (vs/create-branch! v "binding-feat")
            vf      (vs/switch-branch v (:id feature))
            sticky-child  (sp/create-entity vf :fn
                                            {:name "bind-sticky-child"
                                             :parent-ids [(:id sticky-parent)]
                                             :description "h"})
            plain-child   (sp/create-entity vf :fn
                                            {:name "bind-plain-child"
                                             :parent-ids [(:id plain-parent)]
                                             :description "h"})
            sticky-binding (sp/create-entity vf :binding
                                             {:fn-id (:id sticky-child)
                                              :slot-id (:id slot)
                                              :value "sticky-val"})
            plain-binding  (sp/create-entity vf :binding
                                             {:fn-id (:id plain-child)
                                              :slot-id (:id slot)
                                              :value "plain-val"})]
        (testing "before merge: main sees neither binding"
          (is (nil? (sp/read-entity v :binding (:id sticky-binding))))
          (is (nil? (sp/read-entity v :binding (:id plain-binding)))))

        (vs/merge-branch! v (:id feature))

        (testing "after merge: plain-binding propagates, sticky-binding does NOT"
          (is (= "plain-val"
                 (:value (sp/read-entity v :binding (:id plain-binding))))
              "binding under a non-branch-local fn surfaces normally")
          (is (nil? (sp/read-entity v :binding (:id sticky-binding)))
              "binding under a sticky-local fn is filtered alongside the fn")))
      (finally (sp/close base)))))


(deftest branch-local-list-item-does-not-propagate-on-merge-test
  ;; Finding-2 regression. The binding EXISTS on main (so it resolves
  ;; via inheritance and is NEVER filtered as a source-only row), but an
  ;; ITEM-ONLY edit on a branch-local fn's list arg (`:schedule` cron /
  ;; `:env` multi-value) was leaking across the merge: a
  ;; binding-list-item version row carries only `:binding-id`, and the
  ;; resolver's owner lookup used to return nil for it, so the
  ;; branch-local filter never fired.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [type-fn (sp/create-entity v :fn {:name "li-type" :parent-ids []
                                             :description "h"})
            sticky  (sp/create-entity v :fn {:name "li-sticky" :parent-ids []
                                             :description "h" :branch-local? true})
            plain   (sp/create-entity v :fn {:name "li-plain" :parent-ids []
                                             :description "h"})
            s-slot  (sp/create-entity v :slot {:name "s-xs" :type-fn-id (:id type-fn)})
            p-slot  (sp/create-entity v :slot {:name "p-xs" :type-fn-id (:id type-fn)})
            _       (sp/create-entity v :fn-slot {:fn-id (:id sticky) :slot-id (:id s-slot) :position 0})
            _       (sp/create-entity v :fn-slot {:fn-id (:id plain) :slot-id (:id p-slot) :position 0})
            ;; Bindings live on MAIN — they inherit onto the feature
            ;; branch, so they are not source-only rows.
            s-bind  (sp/create-entity v :binding {:fn-id (:id sticky) :slot-id (:id s-slot) :list-append true})
            p-bind  (sp/create-entity v :binding {:fn-id (:id plain) :slot-id (:id p-slot) :list-append true})
            feature (vs/create-branch! v "li-feat")
            vf      (vs/switch-branch v (:id feature))]
        ;; ITEM-ONLY edit on the feature branch: touch neither binding
        ;; nor fn row, just add one item to each list.
        (sp/create-entity vf :binding-list-item
                          {:binding-id (:id s-bind) :position 0 :value "sticky-item"})
        (sp/create-entity vf :binding-list-item
                          {:binding-id (:id p-bind) :position 0 :value "plain-item"})
        (vs/merge-branch! v (:id feature))
        (testing "after merge: plain fn's item propagates, sticky fn's item does NOT"
          (is (= 1 (count (sp/query-entities v :binding-list-item {:binding-id (:id p-bind)})))
              "item under a non-branch-local fn surfaces on main")
          (is (empty? (sp/query-entities v :binding-list-item {:binding-id (:id s-bind)}))
              "item under a sticky-local fn is filtered — no cross-branch leak")))
      (finally (sp/close base)))))


(deftest tombstone-delete-hides-inherited-entity-test
  ;; A user-facing delete on a feature branch of an entity that lives on the
  ;; PARENT branch must HIDE it on the feature branch (+ descendants) while
  ;; leaving the parent untouched. The old hard-delete-current-branch-only
  ;; path was a silent no-op for inherited entities.
  (let [base (base-storage)
        v (vs/wrap-with-versioning base)]
    (try
      (let [f (sp/create-entity v :fn {:name "td-fn" :parent-ids [] :description "orig"})
            feature (vs/create-branch! v "td-feat")
            vf (vs/switch-branch v (:id feature))]
        (testing "before delete: the feature branch inherits the fn"
          (is (some? (sp/read-entity vf :fn (:id f)))))
        ;; User-facing delete on the feature branch → tombstone.
        (binding [vs/*tombstone-delete?* true]
          (sp/delete-entity vf :fn (:id f)))
        (testing "after delete: feature hides it, main still has it"
          (is (nil? (sp/read-entity vf :fn (:id f)))
              "the tombstone hides the inherited fn on the feature branch")
          (is (not-any? #(= (:id f) (:id %)) (sp/query-entities vf :fn {}))
              "and it's gone from the feature-branch listing")
          (is (some? (sp/read-entity v :fn (:id f)))
              "main still sees it — the delete did not touch the parent branch")))
      (finally (sp/close base)))))


(deftest tombstone-delete-own-branch-entity-test
  ;; Deleting an entity that has its OWN version on this branch hides it too
  ;; (the tombstone is the new latest), and a hard-delete (default binding)
  ;; still removes rows outright — so sync / rollback are unaffected.
  (let [base (base-storage)
        v (vs/wrap-with-versioning base)]
    (try
      (let [f (sp/create-entity v :fn {:name "td-own" :parent-ids [] :description "d"})]
        (testing "tombstone delete on the creating branch hides it"
          (binding [vs/*tombstone-delete?* true]
            (sp/delete-entity v :fn (:id f)))
          (is (nil? (sp/read-entity v :fn (:id f)))))
        (let [g (sp/create-entity v :fn {:name "td-hard" :parent-ids [] :description "d"})]
          (testing "default (hard) delete removes the row outright"
            (sp/delete-entity v :fn (:id g))
            (is (nil? (sp/read-entity v :fn (:id g)))))))
      (finally (sp/close base)))))


(deftest tombstone-delete-propagates-on-merge-test
  ;; Deleting an entity on a source branch then merging that branch into a
  ;; target must delete it on the target too — the tombstone version travels
  ;; as a merge candidate and wins the latest-by-effective-ts race.
  (let [base (base-storage)
        v (vs/wrap-with-versioning base)]
    (try
      (let [f (sp/create-entity v :fn {:name "tdm-fn" :parent-ids [] :description "d"})
            feature (vs/create-branch! v "tdm-feat")
            vf (vs/switch-branch v (:id feature))]
        ;; Delete on the feature branch (tombstone), main unaffected yet.
        (binding [vs/*tombstone-delete?* true]
          (sp/delete-entity vf :fn (:id f)))
        (is (nil? (sp/read-entity vf :fn (:id f))) "gone on feature")
        (is (some? (sp/read-entity v :fn (:id f))) "still on main before merge")
        ;; Merge feature → main; the delete propagates.
        (vs/merge-branch! v (:id feature))
        (testing "after merge the delete is visible on main"
          (is (nil? (sp/read-entity v :fn (:id f)))
              "main sees the fn as deleted after merging the feature branch")))
      (finally (sp/close base)))))


(deftest branch-local-fn-inherits-via-parent-branch-recursion-test
  ;; Intentional asymmetry between merge (filtered) and inheritance
  ;; (NOT filtered). A branch B forked from A picks up A's sticky-
  ;; local fn version through `resolve-version`'s parent-branch
  ;; recursion — that's normal inheritance. Only MERGE (sibling →
  ;; sibling, child → parent fold-in) is what `:branch-local?`
  ;; blocks.
  ;;
  ;; This test pins the asymmetry so a future refactor that
  ;; "uniformly" applies the filter doesn't silently break the
  ;; inheritance direction.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [sticky-parent (sp/create-entity v :fn
                                            {:name "inh-sticky-parent"
                                             :parent-ids []
                                             :description "h"
                                             :branch-local? true})
            ;; Sticky-local child created on MAIN (the root branch).
            sticky-child (sp/create-entity v :fn
                                           {:name "inh-sticky-child"
                                            :parent-ids [(:id sticky-parent)]
                                            :description "h"})
            ;; Fork a child branch FROM main. No edits on the child
            ;; branch — it inherits main's state.
            feature (vs/create-branch! v "inh-feat")
            vf      (vs/switch-branch v (:id feature))]
        (testing "feat inherits sticky-child via parent-branch recursion"
          ;; Resolve on feat: own-latest nil, no merges, recurse to
          ;; main → finds sticky-child's version. Asymmetric with
          ;; merge: a fn-version moving FROM dev TO main via merge
          ;; would be filtered.
          (is (= "inh-sticky-child"
                 (:name (sp/read-entity vf :fn (:id sticky-child))))
              "branch-local fn IS inherited downward via parent-branch")))
      (finally (sp/close base)))))


;; ============================================================================
;; VersionedStorage — the StorageCRUD protocol over a versioned entity (:fn)
;; ============================================================================

(deftest versioned-crud-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (testing "create → read → update → delete round-trip on a versioned :fn"
        (let [created (sp/create-entity v :fn {:name "vc-fn" :parent-ids []
                                               :description "h"})
              id      (:id created)]
          (is (= "vc-fn" (:name (sp/read-entity v :fn id))))
          (sp/update-entity v :fn id {:description "updated"})
          (is (= "updated" (:description (sp/read-entity v :fn id))))
          (is (true? (sp/delete-entity v :fn id)))
          (is (nil? (sp/read-entity v :fn id)))))

      (testing "update-entity on a missing entity → :not-found"
        (let [ex (try (sp/update-entity v :fn (random-uuid) {:description "x"})
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :not-found (:type (ex-data ex))))))

      (testing "a non-versioned entity (:ns) passes straight through"
        (let [ns-row (sp/create-entity v :ns {:name "vc-ns"})]
          (is (= "vc-ns" (:name (sp/read-entity v :ns (:id ns-row)))))
          (sp/delete-entity v :ns (:id ns-row))
          (is (nil? (sp/read-entity v :ns (:id ns-row))))))
      (finally (sp/close base)))))


(deftest versioned-batch-crud-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (testing "create-entities → read-entities → update-entities → delete-entities"
        (let [created (sp/create-entities v :fn
                                          [{:name "vb-a" :parent-ids [] :description "h"}
                                           {:name "vb-b" :parent-ids [] :description "h"}])
              ids     (mapv :id created)]
          (is (= 2 (count (sp/read-entities v :fn ids))))
          (sp/update-entities v :fn (mapv #(assoc % :description "batch") created))
          (is (every? #(= "batch" (:description %))
                      (vals (sp/read-entities v :fn ids))))
          (is (= 2 (sp/delete-entities v :fn ids)))))

      (testing "batch update-entities reconciles the :parent-ids ref-many junction"
        ;; Regression (fix 12253c8): VersionedStorage's batch
        ;; update-entities only wrote version records, so non-versioned
        ;; junctions like fn :parent-ids were dropped — a base-fn →
        ;; composed-fn change (same row) silently lost its parent link.
        (let [parent (sp/create-entity v :fn {:name "vb-parent" :parent-ids [] :description "h"})
              child  (sp/create-entity v :fn {:name "vb-child" :parent-ids [] :description "h"})]
          (sp/update-entities v :fn [(assoc child :parent-ids [(:id parent)])])
          (is (= [(:id parent)]
                 (:parent-ids (sp/read-entity v :fn (:id child))))
              "the :parent-ids junction must survive the batch update")))

      (testing "update-entities with a missing id → :not-found"
        (let [ex (try (sp/update-entities v :fn [{:id (random-uuid)
                                                  :description "x"}])
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :not-found (:type (ex-data ex))))))
      (finally (sp/close base)))))


(deftest versioned-introspection-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (testing "introspection delegates to the base storage"
        (is (seq (sp/current-entities v)))
        (is (seq (sp/current-fields v :fn)))
        (is (some? (sp/schema-metadata v)))
        ;; current-enums / current-enum-values just need to not blow up.
        (is (coll? (sp/current-enums v))))
      (finally (sp/close base)))))


(deftest versioned-execution-graph-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (testing "resolve-execution-graph on an unknown fn → :not-found"
        (let [ex (try (sp/resolve-execution-graph v (random-uuid))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :not-found (:type (ex-data ex))))))

      (testing "resolve-execution-graph on a real fn returns its graph"
        (let [f (sp/create-entity v :fn {:name "veg-fn" :parent-ids []
                                         :description "h"})
              result (sp/resolve-execution-graph v (:id f))]
          (is (contains? (:fns result) (:id f)))))

      (testing "validate-no-dependency-cycle! — nil ref is a no-op"
        (let [f (sp/create-entity v :fn {:name "veg-cyc" :parent-ids []
                                         :description "h"})]
          (is (nil? (sp/validate-no-dependency-cycle! v (:id f) nil)))))
      (finally (sp/close base)))))


;; ============================================================================
;; binding-list-item position-collision check — replaces the retired
;; base-table `UNIQUE (binding_id, position)` index. The constraint is
;; now per-branch + per-resolved-view, enforced in `VersionedStorage`.
;; ============================================================================

(defn- make-list-binding!
  "Seed an empty `:list-append` binding on `v` and return the binding
   row. Sufficient parent rows (fn, type-fn, slot, fn-slot, binding)
   are created so the binding-list-item tests have a real owner. The
   :fn rows are versioned (live on the wrapper's current branch)."
  [v label]
  (let [type-fn (sp/create-entity v :fn {:name (str label "-list-type") :parent-ids []
                                         :description "h"})
        owner   (sp/create-entity v :fn {:name (str label "-fn") :parent-ids []
                                         :description "h"})
        slot    (sp/create-entity v :slot {:name (str label "-xs")
                                           :type-fn-id (:id type-fn)})
        _       (sp/create-entity v :fn-slot {:fn-id (:id owner)
                                              :slot-id (:id slot)
                                              :position 0})
        bind (sp/create-entity v :binding {:fn-id (:id owner)
                                           :slot-id (:id slot)
                                           :list-append true})]
    bind))


(deftest diff-branches-respects-branch-merge-test
  ;; Regression for the "diff ignores branch_merge edges" gap.
  ;; Setup: sibling branches A and B off main. A creates fn X. Merge
  ;; A into B (no conflicts — main has nothing at X). Now `diff-
  ;; branches(B, main)` should see X as :added-in-source on the B
  ;; side, because B inherits X via the merge — pre-fix the diff
  ;; only walked the ancestor chain and missed it.
  (let [base (base-storage)
        v (vs/wrap-with-versioning base)]
    (try
      (let [main-id (vs/current-branch-id v)
            branch-a (vs/create-branch! v "diff-merge-a")
            va (vs/switch-branch v (:id branch-a))
            ;; X exists only on A initially.
            x-fn (sp/create-entity va :fn {:name "diff-merge-x" :parent-ids []
                                           :description "h-x"})
            branch-b (vs/create-branch! v "diff-merge-b")
            vb (vs/switch-branch v (:id branch-b))
            _ (vs/merge-branch! vb (:id branch-a))]
        (testing "before merge: diff(B, main) shows nothing for X (B is empty)"
                 ;; Pre-merge sanity check is implicit — we merged immediately.
                 )

        (testing "after merge: diff(B-as-source, main-as-target) sees X added-in-source"
          (let [{:keys [diffs]} (mrg/diff-branches base (:id branch-b) main-id)
                x-entry (first (filter #(= (:id x-fn) (:entity-id %)) diffs))]
            (is (some? x-entry)
                "X should appear in the diff — pre-fix this returned nothing
                 because the merge-source branch wasn't walked")
            (is (= :added-in-source (:change x-entry))
                "X is present on B (via merge from A) but not on main")))

        (testing "diff(main, B) flips: X is added-in-target now"
          (let [{:keys [diffs]} (mrg/diff-branches base main-id (:id branch-b))
                x-entry (first (filter #(= (:id x-fn) (:entity-id %)) diffs))]
            (is (= :added-in-target (:change x-entry))))))
      (finally (sp/close base)))))


(deftest list-item-cross-branch-position-test
  ;; Legitimate cross-branch case: TWO SIBLING branches off main, both
  ;; adding an item at the same position to a binding that was empty
  ;; at fork. Pre-fix the legacy `UNIQUE (binding_id, position)` on
  ;; the IDENTITY table rejected the second insert (regardless of
  ;; which branch wrote it). Post-fix the constraint is gone; each
  ;; sibling gets its own identity row, the per-branch resolved-view
  ;; check passes because neither sibling inherits from the other.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "x")
            branch-a (vs/create-branch! v "li-sibling-a")
            branch-b (vs/create-branch! v "li-sibling-b")
            va (vs/switch-branch v (:id branch-a))
            vb (vs/switch-branch v (:id branch-b))
            item-a (sp/create-entity va :binding-list-item
                                     {:binding-id (:id b)
                                      :position 0
                                      :value "a-val"})
            item-b (sp/create-entity vb :binding-list-item
                                     {:binding-id (:id b)
                                      :position 0
                                      :value "b-val"})]
        (testing "each sibling branch sees its own item at position 0"
          (let [a-rows (sp/query-entities va :binding-list-item
                                          {:binding-id (:id b)})
                b-rows (sp/query-entities vb :binding-list-item
                                          {:binding-id (:id b)})]
            (is (= 1 (count a-rows)))
            (is (= 1 (count b-rows)))
            (is (= "a-val" (:value (first a-rows))))
            (is (= "b-val" (:value (first b-rows))))
            (is (not= (:id item-a) (:id item-b))
                "the two siblings got distinct identity rows")))

        (testing "main sees neither sibling's item — both are branch-local"
          (let [main-rows (sp/query-entities v :binding-list-item
                                             {:binding-id (:id b)})]
            (is (empty? main-rows)))))
      (finally (sp/close base)))))


(deftest list-item-position-collision-via-ancestor-test
  ;; The OTHER kind of cross-branch case: child branch wants the same
  ;; position as an item that's already visible via inheritance from
  ;; main. The per-branch resolved view would have two items at the
  ;; same position — which is what the check protects against. So
  ;; this MUST be rejected.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "anc")
            _ (sp/create-entity v :binding-list-item
                                {:binding-id (:id b)
                                 :position 0
                                 :value "from-main"})
            child (vs/create-branch! v "li-child")
            vc (vs/switch-branch v (:id child))
            ex (try (sp/create-entity vc :binding-list-item
                                      {:binding-id (:id b)
                                       :position 0
                                       :value "from-child"})
                    (catch clojure.lang.ExceptionInfo e e))]
        (testing "child branch rejects pos=0 because main's item already lives there"
          (is (= :constraint-violation/position-collision
                 (:type (ex-data ex)))))
        (testing "child branch still sees main's item as the only resolved row"
          (let [rows (sp/query-entities vc :binding-list-item
                                        {:binding-id (:id b)})]
            (is (= 1 (count rows)))
            (is (= "from-main" (:value (first rows)))))))
      (finally (sp/close base)))))


(deftest list-item-same-branch-position-collision-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "y")
            _first (sp/create-entity v :binding-list-item
                                     {:binding-id (:id b)
                                      :position 0
                                      :value 1})
            ex (try (sp/create-entity v :binding-list-item
                                      {:binding-id (:id b)
                                       :position 0
                                       :value 2})
                    (catch clojure.lang.ExceptionInfo e e))]
        (testing "second insert at the same branch + position is rejected"
          (is (some? ex))
          (is (= :constraint-violation/position-collision
                 (:type (ex-data ex)))))
        (testing "the failed insert did NOT land — branch still has 1 item"
          (is (= 1 (count (sp/query-entities v :binding-list-item
                                             {:binding-id (:id b)}))))))
      (finally (sp/close base)))))


;; ============================================================================
;; fn (namespace-id, name) collision check — replaces the retired base-table
;; `UNIQUE (namespace_id, name)` index. Same doctrine as list-item positions:
;; uniqueness is a per-branch resolved-view property. The raw index kept
;; soft-deleted identities in the key forever (delete a fn in a namespace →
;; that (ns, name) pair bounced every future create/move), and never covered
;; NULL namespace-id (root fns) at all.
;; ============================================================================

(deftest fn-name-ghost-frees-the-name-test
  ;; THE regression that motivated the retirement: create a fn inside a
  ;; namespace, tombstone-delete it, then create a same-named fn at root and
  ;; move it into that namespace. Pre-fix the dead identity's base row still
  ;; occupied the unique key and the move bounced with a unique-violation.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [ns-row (sp/create-entity v :ns {:name "ghost-ns"})
            dead (sp/create-entity v :fn {:name "ghost-fn" :parent-ids []
                                          :namespace-id (:id ns-row)
                                          :description "h"})
            _ (binding [vs/*tombstone-delete?* true]
                (sp/delete-entity v :fn (:id dead)))
            fresh (sp/create-entity v :fn {:name "ghost-fn" :parent-ids []
                                           :description "h2"})]
        (testing "re-creating the name at root works while the ghost persists"
          (is (some? (:id fresh))))
        (testing "moving the fresh fn into the ghost's namespace works"
          (let [moved (sp/update-entity v :fn (:id fresh)
                                        {:namespace-id (:id ns-row)})]
            (is (= (:id ns-row) (:namespace-id moved))))
          (is (= (:id ns-row)
                 (:namespace-id (sp/read-entity v :fn (:id fresh)))))))
      (finally (sp/close base)))))


(deftest fn-name-same-branch-collision-test
  ;; Two LIVE fns with the same (ns, name) on one branch must be rejected —
  ;; including at root (nil namespace-id), which the old btree never covered
  ;; because NULLs don't collide.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [_ (sp/create-entity v :fn {:name "dup-fn" :parent-ids []
                                       :description "h"})
            ex (try (sp/create-entity v :fn {:name "dup-fn" :parent-ids []
                                             :description "h2"})
                    (catch clojure.lang.ExceptionInfo e e))]
        (testing "second live create of the same root name is rejected"
          (is (= :constraint-violation/fn-name-collision
                 (:type (ex-data ex))))))
      (finally (sp/close base)))))


(deftest fn-name-rename-collision-test
  ;; A rename landing on an occupied live name is the update-side of the
  ;; same rule.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [_ (sp/create-entity v :fn {:name "rn-a" :parent-ids []
                                       :description "h"})
            b (sp/create-entity v :fn {:name "rn-b" :parent-ids []
                                       :description "h"})
            ex (try (sp/update-entity v :fn (:id b) {:name "rn-a"})
                    (catch clojure.lang.ExceptionInfo e e))]
        (testing "rename onto a live name is rejected"
          (is (= :constraint-violation/fn-name-collision
                 (:type (ex-data ex)))))
        (testing "a rename to a FREE name still works"
          (is (= "rn-c" (:name (sp/update-entity v :fn (:id b)
                                                 {:name "rn-c"}))))))
      (finally (sp/close base)))))


(deftest fn-name-cross-branch-divergence-legal-test
  ;; Sibling branches may each hold their own live fn with the same
  ;; (ns, name) — per-branch views never see both. The old base-table
  ;; index wrongly blocked exactly this divergence for non-null
  ;; namespaces.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [ns-row (sp/create-entity v :ns {:name "sib-ns"})
            branch-a (vs/create-branch! v "fn-name-sib-a")
            branch-b (vs/create-branch! v "fn-name-sib-b")
            va (vs/switch-branch v (:id branch-a))
            vb (vs/switch-branch v (:id branch-b))
            fa (sp/create-entity va :fn {:name "sib-fn" :parent-ids []
                                         :namespace-id (:id ns-row)
                                         :description "a"})
            fb (sp/create-entity vb :fn {:name "sib-fn" :parent-ids []
                                         :namespace-id (:id ns-row)
                                         :description "b"})]
        (testing "both siblings created their own identity"
          (is (not= (:id fa) (:id fb))))
        (testing "each branch resolves exactly its own fn"
          (is (= "a" (:description (sp/read-entity va :fn (:id fa)))))
          (is (= "b" (:description (sp/read-entity vb :fn (:id fb)))))
          (is (nil? (sp/read-entity va :fn (:id fb))))
          (is (nil? (sp/read-entity vb :fn (:id fa))))))
      (finally (sp/close base)))))


(deftest fn-name-ns-move-collision-test
  ;; Moving a fn into a namespace already holding a LIVE same-named fn on
  ;; this branch must be rejected — the identity-level namespace-id write
  ;; goes through the same live-view check as create/rename.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [ns-row (sp/create-entity v :ns {:name "mv-ns"})
            _ (sp/create-entity v :fn {:name "mv-fn" :parent-ids []
                                       :namespace-id (:id ns-row)
                                       :description "resident"})
            newcomer (sp/create-entity v :fn {:name "mv-fn" :parent-ids []
                                              :description "newcomer"})
            ex (try (sp/update-entity v :fn (:id newcomer)
                                      {:namespace-id (:id ns-row)})
                    (catch clojure.lang.ExceptionInfo e e))]
        (testing "root twin can coexist, but the move into the occupied ns is rejected"
          (is (= :constraint-violation/fn-name-collision
                 (:type (ex-data ex))))
          (is (nil? (:namespace-id (sp/read-entity v :fn (:id newcomer)))))))
      (finally (sp/close base)))))


(deftest fn-name-concurrent-create-serialized-test
  ;; The (branch, ns, name) advisory lock serializes racing creates of the
  ;; same name — exactly one lands, every loser hits the collision check.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [n 8
            results (->> (repeatedly n
                                     (fn []
                                       (future
                                         (try
                                           (sp/create-entity v :fn
                                                             {:name "race-fn"
                                                              :parent-ids []
                                                              :description "r"})
                                           :ok
                                           (catch clojure.lang.ExceptionInfo e
                                             (:type (ex-data e)))))))
                         doall
                         (mapv deref))
            live (sp/query-entities v :fn {:name "race-fn"})]
        (testing "exactly one concurrent create landed"
          (is (= 1 (count (filter #{:ok} results))))
          (is (= 1 (count live))))
        (testing "every loser hit the fn-name collision check"
          (is (every? #{:constraint-violation/fn-name-collision}
                      (remove #{:ok} results)))))
      (finally (sp/close base)))))


(deftest list-item-concurrent-same-position-serialized-test
  ;; The per-binding advisory lock serializes concurrent appends to the SAME
  ;; binding, so racing inserts that computed the same position can't both land
  ;; (which would corrupt the sequence order). Without the lock this races and
  ;; 2+ can land; with it, exactly one lands and the rest hit the (now
  ;; committed-visible) collision check.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "cc")
            n 8
            results (->> (repeatedly n
                                     (fn []
                                       (future
                                         (try
                                           (sp/create-entity v :binding-list-item
                                                             {:binding-id (:id b)
                                                              :position 0
                                                              :value 1})
                                           :ok
                                           (catch clojure.lang.ExceptionInfo e
                                             (:type (ex-data e)))))))
                         doall
                         (mapv deref))
            landed (sp/query-entities v :binding-list-item {:binding-id (:id b)})]
        (testing "exactly one concurrent insert at position 0 lands"
          (is (= 1 (count landed)))
          (is (= 1 (count (filter #{:ok} results)))))
        (testing "every loser hit the position-collision check (no silent double-insert)"
          (is (every? #{:constraint-violation/position-collision}
                      (remove #{:ok} results)))))
      (finally (sp/close base)))))


(deftest list-item-batch-duplicate-position-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "z")
            ex (try (sp/create-entities v :binding-list-item
                                        [{:binding-id (:id b) :position 0 :value "a"}
                                         {:binding-id (:id b) :position 0 :value "b"}])
                    (catch clojure.lang.ExceptionInfo e e))]
        (testing "intra-batch duplicate (binding-id, position) is rejected"
          (is (= :constraint-violation/position-collision
                 (:type (ex-data ex))))
          (is (zero? (count (sp/query-entities v :binding-list-item
                                               {:binding-id (:id b)}))))))
      (finally (sp/close base)))))


(deftest list-item-batch-multi-binding-collision-test
  ;; The batch collision check queries EVERY touched binding's versions in
  ;; one round-trip, then scopes each item back to its OWN binding. An
  ;; existing item at position 0 in binding A must NOT falsely collide with
  ;; a batch item at position 0 in binding B (a naive all-versions-together
  ;; check would); a batch item at the taken position in binding A must.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [bind-a (make-list-binding! v "mba")
            bind-b (make-list-binding! v "mbb")
            _existing (sp/create-entity v :binding-list-item
                                        {:binding-id (:id bind-a) :position 0 :value 1})]
        (testing "batch item at position 0 in a DIFFERENT binding does not collide"
          (sp/create-entities v :binding-list-item
                              [{:binding-id (:id bind-b) :position 0 :value 2}])
          (is (= 1 (count (sp/query-entities v :binding-list-item {:binding-id (:id bind-b)})))))
        (testing "multi-binding batch: the item at the taken (A,0) collides, and nothing lands"
          (let [ex (try (sp/create-entities v :binding-list-item
                                            [{:binding-id (:id bind-b) :position 9 :value 3}
                                             {:binding-id (:id bind-a) :position 0 :value 4}])
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= :constraint-violation/position-collision (:type (ex-data ex))))
            (is (= (:id bind-a) (:binding-id (ex-data ex)))
                "collision is attributed to binding A, not the free B item")
            (is (= 1 (count (sp/query-entities v :binding-list-item {:binding-id (:id bind-b)})))
                "the free B item did not land — batch is all-or-nothing")
            (is (= 1 (count (sp/query-entities v :binding-list-item {:binding-id (:id bind-a)})))))))
      (finally (sp/close base)))))


(deftest list-item-delete-then-recreate-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "w")
            orig (sp/create-entity v :binding-list-item
                                   {:binding-id (:id b) :position 0 :value 1})
            _ (sp/delete-entity v :binding-list-item (:id orig))
            ;; Regression for the original workaround in
            ;; `process-sequence-append`: with the constraint dropped
            ;; and the per-branch resolved-view check, recreating at
            ;; the same `(binding-id, position)` after a soft-delete
            ;; should succeed (the orphan identity row no longer
            ;; resolves on this branch).
            replacement (sp/create-entity v :binding-list-item
                                          {:binding-id (:id b)
                                           :position 0
                                           :value 99})]
        (testing "recreate after delete succeeds; resolved view shows new value"
          (is (some? replacement))
          (let [rows (sp/query-entities v :binding-list-item
                                        {:binding-id (:id b)})]
            (is (= 1 (count rows)))
            (is (= 99 (:value (first rows)))))))
      (finally (sp/close base)))))


(deftest fork-point-parent-to-child-merge-no-spurious-conflict-test
  ;; H: a main→feature (pull) merge computed the fork from the SOURCE (main)
  ;; root, so a fn ONLY the feature branch changed (after it forked) was
  ;; flagged as modified-on-both → a spurious conflict. The fork must come
  ;; from the CHILD (feature) branch, so only genuinely-concurrent edits count.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main-id  (vs/current-branch-id v)
            ;; created on main BEFORE feature forks
            e        (sp/create-entity v :fn {:name "pull-shared-fn" :parent-ids []
                                              :description "orig"})
            feature  (vs/create-branch! v "pull-feature")
            vf       (vs/switch-branch v (:id feature))
            ;; feature edits the fn AFTER forking; main never touches it again
            _        (sp/update-entity vf :fn (:id e) {:description "feature-edit"})]
        (testing "main→feature merge: only feature changed the fn → NO conflict"
          (let [{:keys [conflicts]} (vs/detect-conflicts vf main-id)]
            (is (empty? conflicts)
                "feature's own post-fork edit must not conflict with unchanged main"))))
      (finally (sp/close base)))))


;; ============================================================================
;; query-ref-many-owners — the reverse index must not resurrect the dead
;; ============================================================================

(deftest ref-many-owners-excludes-deleted-owners-test
  ;; A deletion here is a TOMBSTONE, not a row removal, and the junction table
  ;; that answers "who points at me" is not versioned. `query-ref-many-owners`
  ;; used to pass the base storage's answer straight through, so a child this
  ;; branch had already deleted still counted as an owner.
  ;;
  ;; Everything built on that reverse index inherited the lie. The delete guard
  ;; ("Graph is a parent of N other graph(s) — remove the dependents first")
  ;; refused forever:
  ;;
  ;;     DELETE child  -> 200
  ;;     DELETE parent -> 409  "is a parent of 1 other graph"
  ;;
  ;; A fn that ever had a child could not be deleted again — not by a user in
  ;; the editor, and not by an e2e test cleaning up after itself. The leaked
  ;; parents piled up in the graph and the NEXT test file tripped over them.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [parent (sp/create-entity v :fn {:name "rmo-parent" :parent-ids []
                                            :description "h"})
            child  (sp/create-entity v :fn {:name "rmo-child"
                                            :parent-ids [(:id parent)]
                                            :description "h"})]

        (testing "a LIVE child is reported as an owner — the guard must still bite"
          (is (= [(:id child)]
                 (vec (sp/query-ref-many-owners v :fn :parent-ids (:id parent))))
              "the reverse index finds the living child"))

        ;; Bind the var the user-facing CRUD delete binds. Default is FALSE —
        ;; a HARD delete of this branch's version rows, the declarative-sync
        ;; path. The bug lived on the tombstone path, which is what a user in
        ;; the editor and an e2e test cleaning up after itself both take.
        (binding [vs/*tombstone-delete?* true]
          (sp/delete-entity v :fn (:id child)))

        (testing "a DELETED child is no longer an owner"
          (is (empty? (sp/query-ref-many-owners v :fn :parent-ids (:id parent)))
              "the tombstoned child must not count as a dependent — this is what
               made a once-parented fn undeletable forever"))

        (testing "the parent itself is now deletable"
          (binding [vs/*tombstone-delete?* true]
            (sp/delete-entity v :fn (:id parent)))
          (is (empty? (sp/query-ref-many-owners v :fn :parent-ids (:id parent)))
              "with no live dependents left, the parent deletes cleanly")))
      (finally (sp/close base)))))


(deftest ref-many-owners-is-branch-scoped-test
  ;; The living/dead question is answered per BRANCH: a child deleted on a
  ;; feature branch is still alive on main, and main's guard must still see it.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [parent  (sp/create-entity v :fn {:name "rmo-br-parent" :parent-ids []
                                             :description "h"})
            child   (sp/create-entity v :fn {:name "rmo-br-child"
                                             :parent-ids [(:id parent)]
                                             :description "h"})
            feature (vs/create-branch! v "rmo-feature")
            vf      (vs/switch-branch v (:id feature))]

        (binding [vs/*tombstone-delete?* true]
          (sp/delete-entity vf :fn (:id child)))

        (testing "on the branch that deleted it, the child is gone"
          (is (empty? (sp/query-ref-many-owners vf :fn :parent-ids (:id parent)))))

        (testing "on main it is still alive, and still an owner"
          (is (= [(:id child)]
                 (vec (sp/query-ref-many-owners v :fn :parent-ids (:id parent))))
              "a delete on a feature branch must not make main's guard blind")))
      (finally (sp/close base)))))


(deftest upsert-onto-versionless-identity-writes-a-version-test
  ;; A package re-sync grows a list: `upsert-entities` classifies an item
  ;; whose IDENTITY row already exists as an update. If that identity has
  ;; NO version row on the chain (a remnant of an older list — item-ids are
  ;; deterministic per (binding, position), so they come back), the diff ran
  ;; against the identity itself, came out empty, and no version row was
  ;; written — while the read path needs one, so the item stayed INVISIBLE.
  ;; Live consequence (2026-07-20): appending a route mid-list dropped
  ;; `:api-routes` from the demo's route list and every /api/* 404'd.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "w")
            ;; A versionless identity row: written straight to BASE storage,
            ;; exactly the shape an old sync left behind.
            orphan-id (random-uuid)
            _ (sp/create-entity base :binding-list-item
                                {:id orphan-id :binding-id (:id b)
                                 :position 0 :value 1})]
        (testing "precondition: invisible through the versioned view"
          (is (empty? (sp/query-entities v :binding-list-item
                                         {:binding-id (:id b)}))))
        (testing "upsert of the SAME content makes it visible (version row written)"
          (sp/upsert-entities v :binding-list-item
                              [{:id orphan-id :binding-id (:id b)
                                :position 0 :value 1}])
          (let [rows (sp/query-entities v :binding-list-item
                                        {:binding-id (:id b)})]
            (is (= 1 (count rows)))
            (is (= 1 (:value (first rows))))))
        (testing "and a subsequent no-op upsert stays a no-op (no version churn)"
          (let [before (count (sp/query-entities base :binding-list-item-version
                                                 {:item-id orphan-id}))]
            (sp/upsert-entities v :binding-list-item
                                [{:id orphan-id :binding-id (:id b)
                                  :position 0 :value 1}])
            (is (= before (count (sp/query-entities base :binding-list-item-version
                                                    {:item-id orphan-id})))))))
      (finally (sp/close base)))))


(deftest singular-update-onto-versionless-identity-is-not-found-test
  ;; The asymmetry with the batch path above is deliberate and pinned here:
  ;; singular `update-entity` resolves through the VERSION-gated
  ;; `resolve-entity`, so a versionless identity is simply absent on this
  ;; branch → :not-found. Only the batch path (what the package sync drives)
  ;; resolves identity-as-is, which is why the versionless guard lives there.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "w")
            orphan-id (random-uuid)
            _ (sp/create-entity base :binding-list-item
                                {:id orphan-id :binding-id (:id b)
                                 :position 0 :value 7})]
        (is (empty? (sp/query-entities v :binding-list-item {:binding-id (:id b)}))
            "precondition: invisible")
        (is (thrown? clojure.lang.ExceptionInfo
              (sp/update-entity v :binding-list-item orphan-id {:value 7}))))
      (finally (sp/close base)))))


;; ============================================================================
;; Hard delete purges sole-branch identities (ghost-identity prevention)
;; ============================================================================

(deftest hard-delete-purges-sole-branch-identity-test
  ;; The 2026-07-20 shrink-regrow class at its root: a sync hard delete
  ;; used to drop only this branch's version rows, leaving a versionless
  ;; identity that a later regrow of the same deterministic id revived
  ;; through the update path — content-equal, no version written,
  ;; invisible on every list read. Now the identity goes too (no other
  ;; branch retains a version), so the regrow flows through
  ;; create-entities and is visible immediately.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "hd")
            item (sp/create-entity v :binding-list-item
                                   {:binding-id (:id b) :position 0 :value 1})]
        (sp/delete-entity v :binding-list-item (:id item))
        (testing "identity row is gone at the base plane"
          (is (empty? (sp/read-entities base :binding-list-item [(:id item)]))))
        (testing "regrow of the SAME id goes through create and is visible"
          (sp/upsert-entities v :binding-list-item
                              [{:id (:id item) :binding-id (:id b)
                                :position 0 :value 1}])
          (let [rows (sp/query-entities v :binding-list-item
                                        {:binding-id (:id b)})]
            (is (= 1 (count rows)))
            (is (= 1 (:value (first rows)))))))
      (finally (sp/close base)))))


(deftest hard-delete-retains-identity-pinned-by-another-branch-test
  ;; Per-branch isolation: a feature branch that diverged an item pins
  ;; the identity row — main's hard delete removes main's versions only,
  ;; and the feature branch keeps resolving its own version.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "pin")
            item (sp/create-entity v :binding-list-item
                                   {:binding-id (:id b) :position 0 :value 1})
            feature (vs/create-branch! v "hd-pin-feature")
            vf (vs/switch-branch v (:id feature))]
        (sp/update-entity vf :binding-list-item (:id item) {:value 42})
        (sp/delete-entity v :binding-list-item (:id item))
        (testing "identity survives — the feature branch pins it"
          (is (= 1 (count (sp/read-entities base :binding-list-item [(:id item)])))))
        (testing "main no longer resolves the item"
          (is (empty? (sp/query-entities v :binding-list-item
                                         {:binding-id (:id b)}))))
        (testing "feature still resolves its own version"
          (let [rows (sp/query-entities vf :binding-list-item
                                        {:binding-id (:id b)})]
            (is (= 1 (count rows)))
            (is (= 42 (:value (first rows)))))))
      (finally (sp/close base)))))


(deftest hard-delete-retains-parent-identity-while-child-survives-test
  ;; A binding whose item identity is pinned by another branch must keep
  ;; its own identity row too — nothing may dangle. The versionless
  ;; backstop in update-entities still heals such a binding on the next
  ;; content-equal write.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "dang")
            item (sp/create-entity v :binding-list-item
                                   {:binding-id (:id b) :position 0 :value 1})
            feature (vs/create-branch! v "hd-dang-feature")
            vf (vs/switch-branch v (:id feature))]
        (sp/update-entity vf :binding-list-item (:id item) {:value 2})
        ;; Leaves first, like reconcile-fn-bodies!: the item's identity
        ;; is pinned by the feature branch, so it stays…
        (sp/delete-entity v :binding-list-item (:id item))
        (is (= 1 (count (sp/read-entities base :binding-list-item [(:id item)]))))
        ;; …and therefore the parent binding's identity must stay too.
        (sp/delete-entity v :binding (:id b))
        (testing "binding identity survives while a child references it"
          (is (= 1 (count (sp/read-entities base :binding [(:id b)])))))
        (testing "but main's resolved view no longer shows the binding"
          (is (empty? (sp/query-entities v :binding {:fn-id (:fn-id b)})))))
      (finally (sp/close base)))))


(deftest versionless-probe-is-chain-scoped-test
  ;; The versionless backstop must not be satisfied by a version living
  ;; only on an UNRELATED branch: visibility is per-chain. Pre-fix the
  ;; probe queried all branches, so an item created on a feature branch
  ;; stayed invisible on main even after a content-equal main write.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "chain")
            feature (vs/create-branch! v "probe-feature")
            vf (vs/switch-branch v (:id feature))
            item (sp/create-entity vf :binding-list-item
                                   {:binding-id (:id b) :position 0 :value 5})]
        (testing "precondition: invisible on main (version only on feature)"
          (is (empty? (sp/query-entities v :binding-list-item
                                         {:binding-id (:id b)}))))
        (testing "content-equal upsert on main forces a main version"
          (sp/upsert-entities v :binding-list-item
                              [{:id (:id item) :binding-id (:id b)
                                :position 0 :value 5}])
          (let [rows (sp/query-entities v :binding-list-item
                                        {:binding-id (:id b)})]
            (is (= 1 (count rows)))
            (is (= 5 (:value (first rows)))))))
      (finally (sp/close base)))))


;; ============================================================================
;; Audit-5: identity-INSERT framework strip + create identity-field diff +
;; inbound-ref purge guard
;; ============================================================================

(deftest create-strips-all-version-framework-cols-test
  ;; The identity INSERT must survive a caller echoing a RAW version row
  ;; (which carries :branch-id / :created-at / :deleted-at / the
  ;; version-id-field) — the identity table has none of those columns.
  ;; Symmetric with prepare-version-record's whitelist on the version side.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [b (make-list-binding! v "fw")
            item-id (random-uuid)
            echoed {:id item-id :binding-id (:id b) :position 0 :value 3
                    :branch-id (random-uuid) :created-at (java.time.Instant/now)
                    :deleted-at nil :item-id item-id}]
        (sp/create-entity v :binding-list-item echoed)
        (let [rows (sp/query-entities v :binding-list-item
                                      {:binding-id (:id b)})]
          (is (= 1 (count rows)))
          (is (= 3 (:value (first rows))))))
      (finally (sp/close base)))))


(deftest create-with-existing-id-applies-identity-field-diff-test
  ;; A create hitting an EXISTING identity row used to silently drop a
  ;; changed identity-plane field; now the diff flows through update.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [f (sp/create-entity v :fn {:name "idf-fn" :parent-ids []
                                       :description "h" :branch-local? false})]
        (sp/create-entity v :fn {:id (:id f) :name "idf-fn" :parent-ids []
                                 :description "h" :branch-local? true})
        (is (true? (:branch-local? (sp/read-entity base :fn (:id f))))
            "identity-plane field change applied instead of silently dropped"))
      (finally (sp/close base)))))


(deftest hard-delete-retains-fn-referenced-by-inbound-refs-test
  ;; A hard delete of a fn some OTHER row still references must keep the
  ;; identity (conservative purge) — otherwise the purge silently
  ;; manufactures the dangling-refs class integrity.clj hunts.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [target (sp/create-entity v :fn {:name "inb-target" :parent-ids []
                                            :description "h"})
            b (make-list-binding! v "inb")
            _ (sp/update-entity v :binding (:id b) {:ref-fn-id (:id target)})]
        (sp/delete-entity v :fn (:id target))
        (testing "identity survives — a binding's ref-fn-id points at it"
          (is (= 1 (count (sp/read-entities base :fn [(:id target)]))))))
      (finally (sp/close base)))))


(deftest hard-delete-retains-fn-referenced-as-parent-test
  ;; parent-ids is a ref-many junction — the purge guard probes it via
  ;; query-ref-many-owners.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [parent (sp/create-entity v :fn {:name "par-target" :parent-ids []
                                            :description "h"})
            _child (sp/create-entity v :fn {:name "par-child"
                                            :parent-ids [(:id parent)]
                                            :description "h"})]
        (sp/delete-entity v :fn (:id parent))
        (testing "identity survives — referenced in a child's parent-ids"
          (is (= 1 (count (sp/read-entities base :fn [(:id parent)]))))))
      (finally (sp/close base)))))
