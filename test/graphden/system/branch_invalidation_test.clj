(ns graphden.system.branch-invalidation-test
  "Cross-branch compiled-registry invalidation.

   A branch ctx caches its own `{fn-id → closure}` map. A branch with
   no own version rows gets that map copied from `main` at build time
   (`compile-runtime/instantiate-from-templates!`). Its STORAGE resolves
   main's newest rows, but nothing recompiles its registry — so an edit
   on main left the branch executing stale closures forever.

   `branch-router/invalidate-affected-ctxs!` closes that. These tests pin
   both directions: the sweep must reach branches that inherit from the
   written one, and must NOT touch branches that don't."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ectx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.schemas :as schemas]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- fixture!
  "A compiled one-fn graph on `main`: `echo-1` returns its bound `:x`.
   Returns everything a test needs to edit that binding and re-execute."
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        raw (pg/create-storage (pth/get-container-config container))]
    (sp/initialize raw (schemas/full-schema))
    (sp/upsert-entities raw :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (exec/register-base-fn! :echo-x (fn [args _ctx] (get args :x)))
    (let [storage (vs/wrap-with-versioning raw "main")
          base (setup/create-base-fn! storage "echo-x" :any)
          slot (setup/create-slot! storage "x" :any)
          _ (setup/attach-slot! storage (:id base) (:id slot) 0)
          composed (setup/create-composed-fn! storage "echo-1" (:id base))
          _ (setup/bind-value! storage (:id composed) (:id slot) 1)
          base-ctx (ectx/create-context {:storage storage
                                         :base-fns (exec/get-default-registry)})]
      (cr/rebuild! base-ctx)
      {:raw raw :storage storage :base-ctx base-ctx :fn-id (:id composed)
       :router (br/create-router base-ctx "echo-1")})))


(defn- set-value!
  "Rewrite `echo-1`'s bound value on `storage`'s branch, then invalidate
   exactly the way `crud.entities/invalidate!` does."
  [{:keys [storage base-ctx router fn-id]} write-storage write-ctx v]
  (let [binding-id (:id (first (sp/query-entities write-storage :binding {:fn-id fn-id})))]
    (sp/update-entity write-storage :binding binding-id {:value v :value-present true})
    (ectx/invalidate-graph-cache! write-ctx #{fn-id})
    (br/invalidate-affected-ctxs! router (vs/current-branch-id write-storage) #{fn-id})
    (when (= write-storage storage) base-ctx)))


(deftest main-edit-reaches-cached-inheriting-branch-test
  (let [{:keys [raw storage base-ctx router fn-id] :as f} (fixture!)
        feature (vs/create-branch! storage "inherits-main")]
    (try
      (let [branch-ctx (br/ctx-for router (:id feature))]
        (testing "precondition: the branch inherits main's value"
          (is (= 1 (cr/execute branch-ctx fn-id {})))
          (is (= 1 (cr/execute base-ctx fn-id {}))))

        (set-value! f storage base-ctx 2)

        (testing "main sees its own edit"
          (is (= 2 (cr/execute base-ctx fn-id {}))))
        (testing "the cached branch recompiles — it has no rows of its own to override with"
          (is (= 2 (cr/execute branch-ctx fn-id {})))))
      (finally (sp/close raw)))))


(deftest branch-edit-does-not-invalidate-main-test
  (let [{:keys [raw storage base-ctx router fn-id] :as f} (fixture!)
        feature (vs/create-branch! storage "writes-its-own")
        branch-storage (vs/->VersionedStorage (vs/unwrap storage) (:id feature))]
    (try
      (let [branch-ctx (br/ctx-for router (:id feature))]
        (set-value! f branch-storage branch-ctx 9)

        (testing "the branch sees its own write"
          (is (= 9 (cr/execute branch-ctx fn-id {}))))
        (testing "main is untouched — version rows are branch-scoped, main does not inherit from a child"
          (is (= 1 (cr/execute base-ctx fn-id {})))))
      (finally (sp/close raw)))))


(deftest sweep-never-builds-an-uncached-branch-test
  (let [{:keys [raw storage base-ctx router fn-id] :as f} (fixture!)
        feature (vs/create-branch! storage "never-served")]
    (try
      (testing "precondition: the pod has never served this branch"
        (is (nil? (get @(:handlers router) (:id feature)))))

      (set-value! f storage base-ctx 3)

      (testing "a write on main must not compile a branch nobody asked this pod about"
        (is (nil? (get @(:handlers router) (:id feature)))))
      (testing "and the branch still builds correctly on first demand"
        (is (= 3 (cr/execute (br/ctx-for router (:id feature)) fn-id {}))))
      (finally (sp/close raw)))))


(deftest branch-forked-off-nonroot-inherits-middle-edit-test
  ;; Regression: the lazy per-branch build decided fast-path vs delta from
  ;; `merge-affected-fn-ids` on the branch's OWN rows only. A branch C forked
  ;; off a NON-root branch B (which has edits) has no own rows, so it took the
  ;; graph-identical fast path and executed MAIN's pre-B closures verbatim —
  ;; even though C's storage resolves B's edits along C→B→main. The divergence
  ;; set is now unioned across the whole ancestor chain except the default.
  (let [{:keys [raw storage base-ctx router fn-id] :as f} (fixture!)
        b (vs/create-branch! storage "middle-b")
        b-storage (vs/->VersionedStorage (vs/unwrap storage) (:id b))
        b-ctx (br/ctx-for router (:id b))]
    (try
      ;; Edit F → 2 on the MIDDLE branch B — B now owns a binding-version row.
      (set-value! f b-storage b-ctx 2)
      (testing "precondition: B sees its own edit, main is untouched"
        (is (= 2 (cr/execute b-ctx fn-id {})))
        (is (= 1 (cr/execute base-ctx fn-id {}))))

      ;; C forks off B (base = B), with NO own edits.
      (let [c (vs/create-branch! storage "child-c" {:base-branch-id (:id b)})
            ;; Sibling forked off ROOT (main), also no edits — the preserved
            ;; fast path: it must still see main's original value.
            sib (vs/create-branch! storage "root-sib")
            c-ctx (br/ctx-for router (:id c))
            sib-ctx (br/ctx-for router (:id sib))]
        (testing "C inherits B's edit through the chain (C→B→main), not main's stale closure"
          (is (= 2 (cr/execute c-ctx fn-id {}))))
        (testing "a root-forked sibling with no edits stays on the fast path — sees main's value"
          (is (= 1 (cr/execute sib-ctx fn-id {})))))
      (finally (sp/close raw)))))


(deftest branch-forked-off-merge-target-inherits-merged-edit-test
  ;; A1.1 — the tail of the W1 chain-divergence fix. Fns merged INTO an
  ;; ancestor own their version rows on the merge SOURCE branch, which is
  ;; NOT in a fork's ancestor chain. So a fork C of a merge-target B saw an
  ;; EMPTY divergence set for those fns (merge-affected over [C B main] is
  ;; empty — B has no own rows, main is excluded) and ran main's pre-merge
  ;; closure verbatim, even though C's storage resolves the merged edit via
  ;; B's merge record. Fixed by unioning merge-affected over the merge-SOURCE
  ;; branches of every merge whose target is on the chain.
  (let [{:keys [raw storage base-ctx router fn-id] :as f} (fixture!)
        s (vs/create-branch! storage "merge-source-s")
        s-storage (vs/->VersionedStorage (vs/unwrap storage) (:id s))
        s-ctx (br/ctx-for router (:id s))
        b (vs/create-branch! storage "merge-target-b")
        b-storage (vs/->VersionedStorage (vs/unwrap storage) (:id b))]
    (try
      ;; S edits F -> 7 (owns a binding-version row on S).
      (set-value! f s-storage s-ctx 7)
      ;; Merge S -> B. B resolves F=7 via the merge record; the version row
      ;; still lives on S, NOT on B.
      (vs/merge-branch! b-storage (:id s))
      (let [b-ctx (br/ctx-for router (:id b))]
        (testing "precondition: B (a merge-target) sees the merged edit; main untouched"
          (is (= 7 (cr/execute b-ctx fn-id {})))
          (is (= 1 (cr/execute base-ctx fn-id {})))))
      ;; C forks off the merge-target B, with NO own edits and NO merge of its own.
      (let [c (vs/create-branch! storage "fork-of-merge-target"
                                 {:base-branch-id (:id b)})
            c-ctx (br/ctx-for router (:id c))]
        (testing "C inherits B's MERGED edit (via S), not main's stale closure"
          (is (= 7 (cr/execute c-ctx fn-id {})))))
      (finally (sp/close raw)))))
