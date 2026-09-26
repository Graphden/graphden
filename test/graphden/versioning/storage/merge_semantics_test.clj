(ns ^:integration graphden.versioning.storage.merge-semantics-test
  "The merge rules a branch user can observe — pinned as scenarios over
   `VersionedStorage` (PostgreSQL testcontainer, the same stack as
   `core-test`):

   - a change that reached the target THROUGH A MERGE counts as the
     target's change: a second sibling's merge must conflict with it
     instead of silently replacing it (the sibling lost update);
   - a sync merge (base → feature) does not freeze the feature's view of
     its base: for everything the feature has not touched it keeps
     following the base, and it never reverts the feature's own edits;
   - identical edits on both sides are not a conflict; a conflict carries
     the per-field diff of what actually differs."
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
    [graphden.versioning.merge.core :as mp]
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


(defn- on
  "`v` switched to `branch` (a branch row or id)."
  [v branch]
  (vs/switch-branch v (if (map? branch) (:id branch) branch)))


(defn- desc
  [v branch id]
  (:description (sp/read-entity (on v branch) :fn id)))


(defn- conflicts
  "`detect-conflicts` for a merge of `source` into `target`."
  [v source target]
  (:conflicts (vs/detect-conflicts (on v target) (:id source))))


(defn- tick
  "Version rows order by `created-at`; keep successive writes on distinct
   clock ticks so a scenario's ordering is the one it states."
  []
  (Thread/sleep 5))


(deftest sibling-merge-after-a-sibling-landed-is-a-conflict-test
  ;; A and B fork main and both edit X and Y. A lands first (no conflict —
  ;; main never touched them). B's merge used to report NO conflict either:
  ;; the target scan looked only at main's OWN version rows, and A's rows
  ;; live on A, surfaced onto main by the merge record — so B's merge won
  ;; and A's edits vanished from main without a prompt.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            y    (:id (sp/create-entity v :fn {:name "y" :parent-ids [] :description "m0"}))
            a    (vs/create-branch! v "sib-a")
            b    (vs/create-branch! v "sib-b")]
        (tick)
        (sp/update-entity (on v a) :fn x {:description "x-from-A"})
        (sp/update-entity (on v a) :fn y {:description "y-from-A"})
        (tick)
        (sp/update-entity (on v b) :fn x {:description "x-from-B"})
        (sp/update-entity (on v b) :fn y {:description "y-from-B"})
        (tick)
        (is (empty? (conflicts v a main)) "A → main: main touched nothing")
        (vs/merge-branch! (on v main) (:id a))
        (is (= "x-from-A" (desc v main x)))
        (testing "B → main conflicts on both, with A's landed version as the target side"
          (let [cs (conflicts v b main)]
            (is (= #{x y} (set (map :entity-id cs))))
            (is (= #{"x-from-A" "y-from-A"}
                   (set (map (comp :description :target-version) cs))))
            (is (= #{"x-from-B" "y-from-B"}
                   (set (map (comp :description :source-version) cs))))))
        (testing "an unresolved merge is refused"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved merge conflicts"
                (vs/merge-branch! (on v main) (:id b)))))
        (testing "resolving picks the chosen content per entity"
          (vs/merge-branch! (on v main) (:id b)
                            {:conflict-resolutions {[:fn x] :source [:fn y] :target}})
          (is (= "x-from-B" (desc v main x)) ":source → B's edit")
          (is (= "y-from-A" (desc v main y)) ":target → A's landed edit survives")))
      (finally (sp/close base)))))


(deftest child-of-a-landed-branch-conflicts-with-what-its-parent-landed-test
  ;; Chained: A forks main, B forks A. Both edit X on their own row. A lands
  ;; on main; B → main must see A's landed X as the target's version. (B
  ;; inherits A live, so B's edit may well have been made on top of A's —
  ;; the rule stays conservative: ask, never silently drop.)
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            a    (vs/create-branch! v "chain-a")
            b    (vs/create-branch! (on v a) "chain-b")]      ; B.base = A
        (tick)
        (sp/update-entity (on v a) :fn x {:description "from-A"})
        (tick)
        (sp/update-entity (on v b) :fn x {:description "from-B"})
        (tick)
        (vs/merge-branch! (on v main) (:id a))
        (is (= "from-A" (desc v main x)))
        (let [cs (conflicts v b main)]
          (is (= [x] (mapv :entity-id cs)))
          (is (= "from-A" (:description (:target-version (first cs)))))
          (is (= "from-B" (:description (:source-version (first cs))))))
        (vs/merge-branch! (on v main) (:id b) {:conflict-resolutions {[:fn x] :target}})
        (is (= "from-A" (desc v main x)) "the chosen target side is what main keeps"))
      (finally (sp/close base)))))


(deftest sync-merge-reports-a-landed-siblings-edit-against-the-features-own-test
  ;; The same rule in the sync direction. Sibling A landed Y on main; the
  ;; feature edited Y itself. Syncing main into the feature must ask —
  ;; A's Y reached main through a merge, not as main's own row. X, which
  ;; the feature never touched, is not a conflict: both sides show A's row.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            y    (:id (sp/create-entity v :fn {:name "y" :parent-ids [] :description "m0"}))
            a    (vs/create-branch! v "landed-a")
            feat (vs/create-branch! v "feat")]
        (tick)
        (sp/update-entity (on v a) :fn x {:description "x-from-A"})
        (sp/update-entity (on v a) :fn y {:description "y-from-A"})
        (sp/update-entity (on v feat) :fn y {:description "y-from-feat"})
        (tick)
        (vs/merge-branch! (on v main) (:id a))
        (is (= "x-from-A" (desc v feat x)) "the feature follows main's merged-in X live")
        (let [cs (conflicts v {:id main} feat)]
          (is (= [y] (mapv :entity-id cs)) "only the entity both sides changed")
          (is (= "y-from-A" (:description (:source-version (first cs)))))
          (is (= "y-from-feat" (:description (:target-version (first cs)))))))
      (finally (sp/close base)))))


(deftest sync-merge-keeps-following-the-base-test
  ;; A branch is a live view of its base for everything it has not touched.
  ;; A sync merge (main → feat) must not change that: an entity main edited
  ;; BEFORE the sync used to freeze on feat at its sync-time value (the merge
  ;; record elevated main's row over live inheritance), while an entity main
  ;; had not touched stayed live — two semantics on one branch.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            feat (vs/create-branch! v "feat")]
        (tick)
        (sp/update-entity (on v main) :fn x {:description "m1"})
        (is (= "m1" (desc v feat x)) "live inheritance before the sync")
        (tick)
        (is (empty? (conflicts v {:id main} feat)))
        (vs/merge-branch! (on v feat) main)
        (is (= "m1" (desc v feat x)))
        (tick)
        (sp/update-entity (on v main) :fn x {:description "m2"})
        (testing "after the sync the feature still follows main"
          (is (= "m2" (desc v feat x))))
        (testing "an entity created on main after the sync is visible too"
          (let [z (sp/create-entity (on v main) :fn {:name "z" :parent-ids [] :description "z0"})]
            (is (= "z0" (desc v feat (:id z))))))
        (testing "and a deletion on main is followed as well"
          (binding [vs/*tombstone-delete?* true]
            (sp/delete-entity (on v main) :fn x))
          (is (nil? (sp/read-entity (on v feat) :fn x)))))
      (finally (sp/close base)))))


(deftest sync-merge-never-reverts-the-features-own-edit-test
  ;; main's X predates the fork; the feature edits X after forking; main
  ;; does not touch X again. Syncing main into the feature has nothing to
  ;; say about X (no conflict) — and must NOT quietly replace the feature's
  ;; edit with main's older row. That is exactly what the merge record used
  ;; to do: main's row, stamped with the merge time, outranked the
  ;; feature's own older-stamped edit.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            feat (vs/create-branch! v "feat")]
        (tick)
        (sp/update-entity (on v feat) :fn x {:description "feat-1"})
        (tick)
        (is (empty? (conflicts v {:id main} feat)) "main did not touch X after the fork")
        (vs/merge-branch! (on v feat) main)
        (is (= "feat-1" (desc v feat x)) "the feature's own edit survives the sync")
        (is (= "m0" (desc v main x)) "and main is untouched by a sync into the feature"))
      (finally (sp/close base)))))


(deftest sync-merge-resolution-pins-the-chosen-side-on-the-feature-test
  ;; Both sides edited X after the fork → the sync asks. Choosing `:source`
  ;; (take main's) writes main's value as the FEATURE's own row: from then
  ;; on the feature has touched X, so it keeps that value even when main
  ;; moves again — the next sync asks again. Choosing `:target` keeps the
  ;; feature's edit.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            y    (:id (sp/create-entity v :fn {:name "y" :parent-ids [] :description "m0"}))
            feat (vs/create-branch! v "feat")]
        (tick)
        (sp/update-entity (on v main) :fn x {:description "x-main-1"})
        (sp/update-entity (on v main) :fn y {:description "y-main-1"})
        (sp/update-entity (on v feat) :fn x {:description "x-feat-1"})
        (sp/update-entity (on v feat) :fn y {:description "y-feat-1"})
        (tick)
        (is (= #{x y} (set (map :entity-id (conflicts v {:id main} feat)))))
        (vs/merge-branch! (on v feat) main
                          {:conflict-resolutions {[:fn x] :source [:fn y] :target}})
        (is (= "x-main-1" (desc v feat x)) ":source → main's value")
        (is (= "y-feat-1" (desc v feat y)) ":target → the feature's edit")
        (tick)
        (sp/update-entity (on v main) :fn x {:description "x-main-2"})
        (testing "the resolved value is the feature's own row now — it does not move with main"
          (is (= "x-main-1" (desc v feat x))))
        (testing "so the next sync asks about X again, and only about X"
          (is (= [x] (mapv :entity-id (conflicts v {:id main} feat))))))
      (finally (sp/close base)))))


(deftest identical-edits-on-both-sides-are-not-a-conflict-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            feat (vs/create-branch! v "feat")]
        (tick)
        (sp/update-entity (on v feat) :fn x {:description "same"})
        (sp/update-entity (on v main) :fn x {:description "same"})
        (is (empty? (conflicts v feat main)) "the same content on both sides needs no decision")
        (vs/merge-branch! (on v main) (:id feat))
        (is (= "same" (desc v main x))))
      (finally (sp/close base)))))


(deftest both-sides-deleting-is-not-a-conflict-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            feat (vs/create-branch! v "feat")]
        (tick)
        (binding [vs/*tombstone-delete?* true]
          (sp/delete-entity (on v feat) :fn x)
          (sp/delete-entity (on v main) :fn x))
        (is (empty? (conflicts v feat main)))
        (vs/merge-branch! (on v main) (:id feat))
        (is (nil? (sp/read-entity (on v main) :fn x))))
      (finally (sp/close base)))))


(deftest conflict-carries-the-per-field-diff-test
  ;; Different FIELDS of one fn still conflict (the row is one unit), but
  ;; the payload says which fields differ and what each side holds, so a
  ;; reader can decide without diffing two version maps by eye.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            z    (:id (sp/create-entity v :fn {:name "z" :parent-ids [] :description "z0"}))
            feat (vs/create-branch! v "feat")]
        (tick)
        (sp/update-entity (on v feat) :fn z {:description "d-desc"})
        (sp/update-entity (on v main) :fn z {:constraint {:kind "x"}})
        (let [[c :as cs] (conflicts v feat main)]
          (is (= 1 (count cs)))
          (is (= [{:field "constraint" :source "∅" :target "{:kind \"x\"}"}
                  {:field "description" :source "d-desc" :target "z0"}]
                 (:fields c)))))
      (finally (sp/close base)))))


(deftest binding-conflict-names-the-slot-and-the-ref-targets-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            f    (sp/create-entity v :fn {:name "owner" :parent-ids []})
            p    (sp/create-entity v :fn {:name "p" :parent-ids []})
            q    (sp/create-entity v :fn {:name "q" :parent-ids []})
            s    (sp/create-entity v :slot {:name "arg" :type-fn-id (:id f)})
            b    (sp/create-entity v :binding {:fn-id (:id f) :slot-id (:id s) :value "v0"})
            feat (vs/create-branch! v "feat")]
        (tick)
        (sp/update-entity (on v feat) :binding (:id b) {:ref-fn-id (:id p) :value nil})
        (sp/update-entity (on v main) :binding (:id b) {:ref-fn-id (:id q) :value nil})
        (let [[c :as cs] (conflicts v feat main)]
          (is (= 1 (count cs)))
          (is (= :binding (:entity-name c)))
          (is (= "arg" (:slot-name c)))
          (is (= [{:field "ref-fn-id" :source ":p" :target ":q"}] (:fields c)))))
      (finally (sp/close base)))))


(deftest delete-versus-edit-conflict-has-no-field-diff-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main (vs/current-branch-id v)
            x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            feat (vs/create-branch! v "feat")]
        (tick)
        (sp/update-entity (on v feat) :fn x {:description "edited"})
        (binding [vs/*tombstone-delete?* true]
          (sp/delete-entity (on v main) :fn x))
        (let [[c :as cs] (conflicts v feat main)]
          (is (= 1 (count cs)))
          (is (nil? (:target-version c)) "the deleted side is nil, as before")
          (is (= [] (:fields c)) "nothing to pair field by field")))
      (finally (sp/close base)))))


(deftest branch-content-stamp-advances-when-a-merge-lands-on-the-branch-test
  ;; An approval is recorded against the proposal's content stamp. Content
  ;; can reach the proposal through a merge as well as an edit — the stamp
  ;; must move for both, or an approval given before the merge keeps
  ;; counting for content the reviewer never saw.
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [x    (:id (sp/create-entity v :fn {:name "x" :parent-ids [] :description "m0"}))
            prop (vs/create-branch! v "proposal")
            sib  (vs/create-branch! v "sibling")]
        (tick)
        (sp/update-entity (on v sib) :fn x {:description "from-sibling"})
        (let [before (mp/branch-content-stamp base (:id prop))]
          (tick)
          (vs/merge-branch! (on v prop) (:id sib))
          (is (not= before (mp/branch-content-stamp base (:id prop)))
              "the merge brought content the approval never covered")))
      (finally (sp/close base)))))
