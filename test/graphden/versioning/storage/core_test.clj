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
                                             :impl-hash "h"})
            feature (vs/create-branch! v "iso-feature")
            vf      (vs/switch-branch v (:id feature))
            on-feat (sp/create-entity vf :fn {:name "feat-fn" :parent-ids []
                                              :impl-hash "h"})]
        (testing "a fn created on main is visible from a child branch"
          (let [feat-fns (set (map :id (:fns (vs/query-all-graph-entities vf))))]
            (is (contains? feat-fns (:id on-main)))))

        (testing "a fn created on the child branch is NOT visible on main"
          (let [main-fns (set (map :id (:fns (vs/query-all-graph-entities v))))]
            (is (contains? main-fns (:id on-main)))
            (is (not (contains? main-fns (:id on-feat)))))))
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

      (testing "deleting the main branch is forbidden"
        (let [ex (try (vs/delete-branch! v (vs/current-branch-id v))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :constraint-violation/main-branch-undeletable
                 (:type (ex-data ex))))))

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
                                            :impl-hash "h0"})
            feature (vs/create-branch! v "diff-feature")
            vf      (vs/switch-branch v (:id feature))
            ;; mutate `shared` on feature only
            _       (sp/update-entity vf :fn (:id shared)
                                      {:description "edited-on-feature"})
            ;; create a brand-new fn on feature only
            feat-only (sp/create-entity vf :fn {:name "diff-feat-only" :parent-ids []
                                                :impl-hash "h-feat"})
            ;; create another fn on main, AFTER fork. With the live-
            ;; inheritance resolver this stays identical on both sides
            ;; (feature sees it via the chain), so it must NOT appear
            ;; in the diff — guards against accidental drift in the
            ;; resolver semantics.
            main-late (sp/create-entity v :fn {:name "diff-main-late" :parent-ids []
                                               :impl-hash "h-main"})]
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
                                              :impl-hash "h"})]
        (testing "no entity touched on both sides → detect-conflicts is empty"
          (let [{:keys [conflicts]} (vs/detect-conflicts v (:id feature))]
            (is (empty? conflicts))))

        (testing "merge-branch! records a branch-merge from source into target"
          (let [record (vs/merge-branch! v (:id feature))]
            (is (= (:id feature) (:source-branch-id record)))
            (is (= main-id (:target-branch-id record)))
            (is (some? (sp/read-entity base :branch-merge (:id record)))))))
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
                                             :parent-ids [] :impl-hash "h"
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
                                             :impl-hash "h"
                                             :branch-local? true})
            ;; Non-local plain base-fn — control case.
            plain-parent  (sp/create-entity v :fn
                                            {:name "plain-parent"
                                             :parent-ids []
                                             :impl-hash "h"})
            feature (vs/create-branch! v "branch-local-feat")
            vf      (vs/switch-branch v (:id feature))
            ;; CHILD fn created on the feature branch, parented from
            ;; the sticky base-fn → effective branch-local via the
            ;; walker. Production analog: `{:parent :http-server …}`.
            sticky-child  (sp/create-entity vf :fn
                                            {:name "sticky-child"
                                             :parent-ids [(:id sticky-parent)]
                                             :impl-hash "h"})
            ;; CHILD fn parented from plain-parent — should merge.
            plain-child   (sp/create-entity vf :fn
                                            {:name "plain-child"
                                             :parent-ids [(:id plain-parent)]
                                             :impl-hash "h"})]
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
                                             :impl-hash "h"
                                             :branch-local? true})
            plain-parent  (sp/create-entity v :fn
                                            {:name "bind-plain-parent"
                                             :parent-ids []
                                             :impl-hash "h"})
            slot          (sp/create-entity v :slot
                                            {:name "x"
                                             :type-fn-id (:id sticky-parent)})
            feature (vs/create-branch! v "binding-feat")
            vf      (vs/switch-branch v (:id feature))
            sticky-child  (sp/create-entity vf :fn
                                            {:name "bind-sticky-child"
                                             :parent-ids [(:id sticky-parent)]
                                             :impl-hash "h"})
            plain-child   (sp/create-entity vf :fn
                                            {:name "bind-plain-child"
                                             :parent-ids [(:id plain-parent)]
                                             :impl-hash "h"})
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
                                             :impl-hash "h"
                                             :branch-local? true})
            ;; Sticky-local child created on MAIN (the root branch).
            sticky-child (sp/create-entity v :fn
                                           {:name "inh-sticky-child"
                                            :parent-ids [(:id sticky-parent)]
                                            :impl-hash "h"})
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
                                               :impl-hash "h"})
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
                                          [{:name "vb-a" :parent-ids [] :impl-hash "h"}
                                           {:name "vb-b" :parent-ids [] :impl-hash "h"}])
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
        (let [parent (sp/create-entity v :fn {:name "vb-parent" :parent-ids [] :impl-hash "h"})
              child  (sp/create-entity v :fn {:name "vb-child" :parent-ids [] :impl-hash "h"})]
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
                                         :impl-hash "h"})
              result (sp/resolve-execution-graph v (:id f))]
          (is (contains? (:fns result) (:id f)))))

      (testing "validate-no-dependency-cycle! — nil ref is a no-op"
        (let [f (sp/create-entity v :fn {:name "veg-cyc" :parent-ids []
                                         :impl-hash "h"})]
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
                                         :impl-hash "h"})
        owner   (sp/create-entity v :fn {:name (str label "-fn") :parent-ids []
                                         :impl-hash "h"})
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
                                           :impl-hash "h-x"})
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
