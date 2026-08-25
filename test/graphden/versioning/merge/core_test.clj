(ns ^:integration graphden.versioning.merge.core-test
  "Tests for `graphden.versioning.merge.core` — merge protection traits.

   Storage stack: PostgreSQL via testcontainer + graph + versioning +
   traits schema. The trait-based protection requires a real binding
   row to attach to, so every test seeds a base-fn + slot + binding
   before exercising the protection API.

   Parallel-safe: the resolution-write-failure test injects its write
   failure by `binding` the THREAD-LOCAL
   `pg-crud/*create-entities-override*` seam (house pattern —
   `advisory-lock/*impl-override*`), not by `with-redefs`-ing the root
   var. The root rebind was process-wide and leaked the injected
   `boom` into whatever sibling NS happened to sync fn-defs during
   that window (observed: state-cell-test's `sync-fns-to-storage!`
   dying with `{:injected true}` in a landing gate), which forced a
   `^:serial` pin on this NS."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.postgres.crud :as pg-crud]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.branch-local :as bl]
    [graphden.versioning.merge.core :as mp]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.merge :as mrg]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- create-test-storage
  "Returns `{:storage versioned-storage :base base-storage :fn-id … :slot-id …}`
   with graph + versioning + traits schema and a single fn / slot
   pair already seeded. Tests create binding rows themselves on the
   branch that needs them (a binding's branch-membership is what
   merge-protection cares about)."
  []
  (th/clean-database-fast! *container*)
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (vds/extend-builder)
                   (vts/extend-builder)
                   (ds/build))
        base (-> (pg/create-storage (th/get-container-config *container*))
                 (sp/initialize-with-cleanup! schema))
        versioned (vs/wrap-with-versioning base)
        f (sp/create-entity versioned :fn
                            {:name "test-fn"
                             :parent-ids []})
        s (sp/create-entity versioned :slot
                            {:name "x"
                             :type-fn-id (:id f)})]
    {:storage versioned :base base :fn-id (:id f) :slot-id (:id s)}))


(defn- create-binding-on-current!
  "Helper: write a binding via the versioned wrapper so a version
   row lands on the storage's current branch."
  [versioned fn-id slot-id value]
  (sp/create-entity versioned :binding
                    {:fn-id fn-id :slot-id slot-id :value value}))


;; === Trait CRUD ===

(deftest add-merge-protection-test
  (testing "add-merge-protection! attaches the trait, has-merge-protected-trait? sees it"
    (let [{:keys [storage fn-id slot-id]} (create-test-storage)
          b (create-binding-on-current! storage fn-id slot-id "secret")]
      (is (false? (mp/has-merge-protected-trait? storage (:id b))))
      (mp/add-merge-protection! storage (:id b))
      (is (true? (mp/has-merge-protected-trait? storage (:id b))))
      (sp/close storage))))


(deftest add-merge-protection-idempotent-test
  (testing "calling add-merge-protection! twice doesn't double-create binding-trait rows"
    (let [{:keys [storage base fn-id slot-id]} (create-test-storage)
          b (create-binding-on-current! storage fn-id slot-id "secret")]
      (mp/add-merge-protection! storage (:id b))
      (mp/add-merge-protection! storage (:id b))
      (let [rows (sp/query-entities base :binding-trait
                                    {:binding-id (:id b)
                                     :trait-id vts/merge-protected-trait-uuid})]
        (is (= 1 (count rows))
            "second add-merge-protection! must be a no-op"))
      (sp/close storage))))


(deftest remove-merge-protection-test
  (testing "remove-merge-protection! drops the trait and returns true"
    (let [{:keys [storage fn-id slot-id]} (create-test-storage)
          b (create-binding-on-current! storage fn-id slot-id "secret")]
      (mp/add-merge-protection! storage (:id b))
      (is (true? (mp/has-merge-protected-trait? storage (:id b))))
      (is (true? (mp/remove-merge-protection! storage (:id b))))
      (is (false? (mp/has-merge-protected-trait? storage (:id b))))
      (sp/close storage))))


(deftest remove-merge-protection-when-absent-test
  (testing "remove-merge-protection! on an unprotected binding returns false (no-op)"
    (let [{:keys [storage fn-id slot-id]} (create-test-storage)
          b (create-binding-on-current! storage fn-id slot-id "secret")]
      (is (false? (mp/remove-merge-protection! storage (:id b))))
      (sp/close storage))))


;; === Detection ===
;;
;; `detect-protected-transfers` flags bindings that would BECOME
;; visible on the target after merge — i.e. exist on source but not
;; on target. The test inverts the typical write order: feature
;; branch creates the binding row, main has none → transfer.

(deftest detect-protected-transfers-empty-test
  (testing "no traits in DB → empty result, not blocked"
    (let [{:keys [storage]} (create-test-storage)
          source (vs/create-branch! storage "feature")
          {:keys [protected-transfers blocked?]}
          (mp/detect-protected-transfers storage (:id source))]
      (is (empty? protected-transfers))
      (is (false? blocked?))
      (sp/close storage))))


(deftest detect-protected-transfers-blocked-test
  (testing "protected binding exists on source only → blocked"
    (let [{:keys [storage fn-id slot-id]} (create-test-storage)
          source (vs/create-branch! storage "feature")
          feature-storage (vs/switch-branch storage (:id source))
          ;; Create the binding ON FEATURE so the binding-version row
          ;; lives only on the source branch.
          b (create-binding-on-current! feature-storage fn-id slot-id "feature-secret")]
      (mp/add-merge-protection! feature-storage (:id b))
      (let [{:keys [protected-transfers blocked?]}
            (mp/detect-protected-transfers storage (:id source))]
        (is (true? blocked?))
        (is (= 1 (count protected-transfers)))
        (is (= (:id b) (:binding-id (first protected-transfers))))
        (is (= :binding (:entity-type (first protected-transfers)))))
      (sp/close storage))))


(deftest detect-protected-transfers-also-on-target-test
  (testing "protected binding present on BOTH branches → not transferred → not blocked"
    (let [{:keys [storage fn-id slot-id]} (create-test-storage)
          ;; First create on main (target) so a target version exists.
          b (create-binding-on-current! storage fn-id slot-id "main-secret")]
      (mp/add-merge-protection! storage (:id b))
      (let [source (vs/create-branch! storage "feature")
            feature-storage (vs/switch-branch storage (:id source))]
        ;; Write a new version on feature — binding-id IS on target,
        ;; so it's an overwrite-not-transfer.
        (sp/update-entity feature-storage :binding (:id b) {:value "feature-secret"})
        (let [{:keys [blocked?]}
              (mp/detect-protected-transfers storage (:id source))]
          (is (false? blocked?)
              "shared binding-id present on target is overwrite-not-transfer")))
      (sp/close storage))))


;; === safe-merge-branch! integration ===

(deftest safe-merge-branch-blocks-on-protection-test
  (testing "safe-merge-branch! throws :merge-protection-violation when transfer would happen"
    (let [{:keys [storage fn-id slot-id]} (create-test-storage)
          source (vs/create-branch! storage "feature")
          feature-storage (vs/switch-branch storage (:id source))
          b (create-binding-on-current! feature-storage fn-id slot-id "leaked-secret")]
      (mp/add-merge-protection! feature-storage (:id b))
      (try
        (mp/safe-merge-branch! storage (:id source))
        (is false "safe-merge-branch! should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :merge-protection-violation (:type (ex-data e))))
          (is (seq (:protected-transfers (ex-data e))))))
      (sp/close storage))))


(deftest safe-merge-branch-skip-flag-test
  (testing ":skip-protection-check skips the gate even with violations present"
    (let [{:keys [storage fn-id slot-id]} (create-test-storage)
          source (vs/create-branch! storage "feature")
          feature-storage (vs/switch-branch storage (:id source))
          b (create-binding-on-current! feature-storage fn-id slot-id "leaked")]
      (mp/add-merge-protection! feature-storage (:id b))
      ;; Should NOT throw — the flag bypasses validate-merge!.
      (mp/safe-merge-branch! storage (:id source) {:skip-protection-check true})
      (is true "merge proceeded under skip-protection-check")
      (sp/close storage))))


(deftest safe-merge-branch-passes-without-violations-test
  (testing "safe-merge-branch! delegates to vs/merge-branch! when nothing is protected"
    (let [{:keys [storage]} (create-test-storage)
          source (vs/create-branch! storage "feature")]
      ;; No traits in DB — should be a clean pass-through.
      (mp/safe-merge-branch! storage (:id source))
      (is true "merge succeeded with no protected bindings")
      (sp/close storage))))


;; === Branch policy: :forbid-invalid? (error-tolerance Phase 5) ===
;;
;; The gate judges the RECORDED per-branch diagnostics store, so the
;; tests seed it directly via `diag/record!` (the same rows the CRUD
;; post-mutation check writes) under the parallel-safe override.

(deftest forbid-invalid-off-merge-unchanged-test
  (testing "diagnostics recorded but target has no :forbid-invalid? → merge proceeds"
    (binding [diag/*diagnostics-override* (atom {})]
      (let [{:keys [storage fn-id]} (create-test-storage)
            source (vs/create-branch! storage "feature")]
        (diag/record! (:id source) fn-id [{:message "broken on source"}])
        (mp/safe-merge-branch! storage (:id source))
        (is true "merge proceeded — policy off by default")
        (sp/close storage)))))


(deftest forbid-invalid-blocks-broken-source-test
  (testing "policy on + recorded diagnostics on source → blocked naming the fn; cleared → merges"
    (binding [diag/*diagnostics-override* (atom {})]
      (let [{:keys [storage fn-id]} (create-test-storage)
            ;; Create the protected target WITH the flag (exercises the
            ;; vs/create-branch! opt-passing) and fork the source off it.
            protected (vs/create-branch! storage "protected"
                                         {:forbid-invalid? true})
            target (vs/switch-branch storage (:id protected))
            source (vs/create-branch! target "feature")]
        (is (true? (:forbid-invalid? protected))
            "create-branch! persists the flag")
        (diag/record! (:id source) fn-id [{:message "Type mismatch on arg :x"}])
        (try
          (mp/validate-branch-policy! target (:id source))
          (is false "validate-branch-policy! should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :merge-protection-violation (:type (ex-data e))))
            (is (= :forbid-invalid (:reason (ex-data e))))
            (is (= ["test-fn"] (:invalid-fn-names (ex-data e))))
            (is (re-find #"test-fn" (ex-message e))
                "message names the broken fn")))
        (is (thrown? clojure.lang.ExceptionInfo
              (mp/safe-merge-branch! target (:id source)))
            "the full safe-merge path is gated too")
        ;; Fix (clear the recorded entry) → merge proceeds.
        (diag/clear-fn! (:id source) fn-id)
        (mp/safe-merge-branch! target (:id source))
        (is true "merge proceeded after the fn was fixed")
        (sp/close storage)))))


(deftest forbid-invalid-blocks-broken-target-test
  (testing "policy on + recorded diagnostics on the TARGET itself → blocked"
    (binding [diag/*diagnostics-override* (atom {})]
      (let [{:keys [storage base fn-id]} (create-test-storage)
            main-id (vs/current-branch-id storage)
            source (vs/create-branch! storage "feature")]
        ;; :branch is non-versioned — flip the flag with a plain update.
        (sp/update-entity base :branch main-id {:forbid-invalid? true})
        (diag/record! main-id fn-id [{:message "broken on target"}])
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"unresolved type errors"
              (mp/validate-branch-policy! storage (:id source))))
        (sp/close storage)))))


(deftest forbid-invalid-ignores-invisible-fns-test
  ;; The diagnostics store has no org dimension — on a multi-tenant pod
  ;; a shared branch's bucket also holds fn-ids the requester's
  ;; org-scoped read can't see (and, single-tenant, ids whose fn is
  ;; gone). Those must neither block the merge nor leak into the
  ;; violation message. Simulated here by recording under an id the
  ;; storage has no row for — exactly what the scoped read returns for
  ;; a foreign org's fn.
  (testing "recorded diagnostics whose fn the storage can't see don't gate the merge"
    (binding [diag/*diagnostics-override* (atom {})]
      (let [{:keys [storage base fn-id]} (create-test-storage)
            main-id (vs/current-branch-id storage)
            source (vs/create-branch! storage "feature")
            invisible-id (java.util.UUID/randomUUID)]
        (sp/update-entity base :branch main-id {:forbid-invalid? true})
        (diag/record! main-id invisible-id [{:message "foreign org's broken fn"}])
        (mp/validate-branch-policy! storage (:id source))
        (is true "invisible-only diagnostics → merge proceeds")
        (testing "mixed: a visible broken fn still blocks, naming ONLY itself"
          (diag/record! main-id fn-id [{:message "own broken fn"}])
          (try
            (mp/validate-branch-policy! storage (:id source))
            (is false "validate-branch-policy! should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= ["test-fn"] (:invalid-fn-names (ex-data e)))
                  "the invisible id appears neither as a name nor a UUID")
              (is (not (re-find #"foreign" (ex-message e)))))))
        (sp/close storage)))))


;; === Conflict resolution apply path ===
;;
;; Exercises `versioning.storage.merge/apply-resolutions!` — the
;; batched create-entities call I switched to in the perf pass.
;; e2e-conflict-detection-test in e2e_test.clj only covers the
;; detection side; this test verifies the WRITE side too.

(deftest merge-with-source-resolution-applies-version-test
  (testing "vs/merge-branch! with :source resolution writes a new version on target"
    (let [{:keys [storage fn-id slot-id]} (create-test-storage)
          ;; main: binding value=10
          b (create-binding-on-current! storage fn-id slot-id 10)
          source (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id source))]
      ;; feature: binding value=20
      (sp/update-entity feature :binding (:id b) {:value 20})
      ;; main: binding value=11 (creates conflict — both branches modified
      ;; after fork point).
      (sp/update-entity storage :binding (:id b) {:value 11})
      (let [{:keys [conflicts]} (vs/detect-conflicts storage (:id source))]
        (is (seq conflicts) "expected a conflict on the binding"))
      ;; Merge with explicit :source resolution — apply-resolutions!
      ;; should batch-write a new binding-version row carrying
      ;; feature's value onto main.
      (vs/merge-branch! storage (:id source)
                        {:conflict-resolutions {[:binding (:id b)] :source}})
      ;; Main should now resolve to feature's value (20).
      (let [resolved (sp/read-entity storage :binding (:id b))]
        (is (= 20 (:value resolved))
            "main now sees feature's value after :source resolution"))
      (sp/close storage))))


(deftest merge-resolution-write-failure-rolls-back-merge-record-test
  (testing "if apply-resolutions! fails, the branch-merge record is rolled back atomically"
    ;; Without the transaction, create-merge-record! commits first (surfacing
    ;; every source version on target) and a failed resolution write leaves
    ;; the user's :target choices missing → source values silently win. The
    ;; transaction must undo the merge record too.
    (let [{:keys [storage base fn-id slot-id]} (create-test-storage)
          b (create-binding-on-current! storage fn-id slot-id 10)
          source (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id source))]
      (sp/update-entity feature :binding (:id b) {:value 20})
      (sp/update-entity storage :binding (:id b) {:value 11})
      (is (seq (:conflicts (vs/detect-conflicts storage (:id source))))
          "expected a conflict on the binding")
      ;; Inject a failure into the resolution write only. `create-merge-record!`
      ;; uses `create-entity` (singular) and runs FIRST inside the tx;
      ;; `apply-resolutions!` uses `create-entities` (plural, batched) — bind
      ;; the thread-local `*create-entities-override*` seam in the concrete
      ;; postgres impl (which the protocol method delegates to) to throw, so
      ;; the merge record is written then rolled back. A protocol-level
      ;; wrapper storage can't intercept here without breaking the very
      ;; atomicity under test: `merge-branch!` reaches into the concrete
      ;; record (`(:pool base-storage)` + `(assoc base-storage :pool tx)`)
      ;; for its transaction plumbing.
      (let [threw? (atom false)]
        (binding [pg-crud/*create-entities-override*
                  (fn [& _] (throw (ex-info "boom" {:injected true})))]
          (try (vs/merge-branch! storage (:id source)
                                 {:conflict-resolutions {[:binding (:id b)] :source}})
               (catch clojure.lang.ExceptionInfo _ (reset! threw? true))))
        (is @threw? "merge propagates the injected resolution-write failure"))
      (is (empty? (sp/query-entities base :branch-merge {:source-branch-id (:id source)}))
          "branch-merge record rolled back with the failed resolution write")
      (is (= 11 (:value (sp/read-entity storage :binding (:id b))))
          "main unchanged — the merge did not partially apply")
      (sp/close storage))))


(deftest detect-conflicts-skips-branch-local-fn-test
  (testing "a binding on a branch-local fn modified on BOTH branches is not a conflict"
    ;; Canonical branch-local case: per-branch runtime config (dev port
    ;; vs prod port). The same binding is edited after fork on both
    ;; sides — pre-fix that surfaced as a phantom `:merge-conflict` that
    ;; blocked the merge (and, if resolved `:source`, leaked the value).
    (let [{:keys [storage base]} (create-test-storage)
          local-fn (sp/create-entity storage :fn
                                     {:name "runtime-cfg"
                                      :parent-ids []
                                      :branch-local? true})
          slot (sp/create-entity storage :slot
                                 {:name "port" :type-fn-id (:id local-fn)})
          b (create-binding-on-current! storage (:id local-fn) (:id slot) 3000)
          source (vs/create-branch! storage "prod")
          feature (vs/switch-branch storage (:id source))]
      (bl/invalidate! base)
      (sp/update-entity feature :binding (:id b) {:value 8080})
      (sp/update-entity storage :binding (:id b) {:value 5000})
      (let [{:keys [conflicts]} (vs/detect-conflicts storage (:id source))]
        (is (empty? (filter #(= (:id b) (:entity-id %)) conflicts))
            "branch-local fn's binding must be skipped, not a conflict"))
      ;; And the whole merge goes through without a thrown :merge-conflict.
      (is (vs/merge-branch! storage (:id source)))
      (sp/close storage))))


(deftest merge-without-resolutions-throws-on-conflict-test
  (testing "vs/merge-branch! throws :merge-conflict when conflicts exist and no resolutions provided"
    (let [{:keys [storage fn-id slot-id]} (create-test-storage)
          b (create-binding-on-current! storage fn-id slot-id 10)
          source (vs/create-branch! storage "feature")
          feature (vs/switch-branch storage (:id source))]
      (sp/update-entity feature :binding (:id b) {:value 20})
      (sp/update-entity storage :binding (:id b) {:value 11})
      (try
        (vs/merge-branch! storage (:id source))
        (is false "merge-branch! should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :merge-conflict (:type (ex-data e))))
          (is (seq (:unresolved (ex-data e))))))
      (sp/close storage))))


(deftest merge-into-self-is-rejected-test
  (testing "vs/merge-branch! refuses source == target (self-merge)"
    ;; A self-merge degenerates `fork-point` (neither side diverges from
    ;; the other → every own edit reads as modified-on-both) into phantom
    ;; self-conflicts, and would plant a self-referential branch-merge the
    ;; resolver walks on every read. It must be rejected up front.
    (let [{:keys [storage fn-id slot-id base]} (create-test-storage)
          _ (create-binding-on-current! storage fn-id slot-id 10)
          target-id (vs/current-branch-id storage)]
      (try
        (vs/merge-branch! storage target-id)
        (is false "self-merge should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/self-merge (:type (ex-data e))))))
      (is (empty? (sp/query-entities base :branch-merge {:source-branch-id target-id}))
          "no branch-merge record planted for the rejected self-merge")
      (sp/close storage))))


;; === merge-affected-fn-ids (delta-invalidation seed) ===

(deftest merge-affected-fn-ids-test
  ;; The seed set a merge uses to DELTA-invalidate the target ctx —
  ;; every fn owning a version row on the source branch. A regression
  ;; that drops a version table from the query (→ stale post-merge
  ;; closures) trips here.
  (let [{:keys [storage base fn-id slot-id]} (create-test-storage)
        b (create-binding-on-current! storage fn-id slot-id 10)
        source (vs/create-branch! storage "feature")
        feature (vs/switch-branch storage (:id source))]
    (testing "a source-branch binding-version surfaces its owner fn-id"
      ;; Edit the binding on the feature branch → binding-version{feature}
      ;; whose denormalised :fn-id is the owner.
      (sp/update-entity feature :binding (:id b) {:value 20})
      (is (= #{fn-id} (mrg/merge-affected-fn-ids base (:id source)))))
    (testing "empty for a source branch with no own version rows"
      (let [empty-source (vs/create-branch! storage "empty-feature")]
        (is (empty? (mrg/merge-affected-fn-ids base (:id empty-source))))))
    (sp/close storage)))


;; === Review policy — approval counting + stale-dismissal ===

(deftest count-valid-approvals-test
  (testing "distinct, stale-filtered, author-excluded, target-bound counting"
    (let [{:keys [storage base fn-id slot-id]} (create-test-storage)
          src (vs/create-branch! storage "rev-src")
          src-id (:id src)
          target-id (:base-branch-id src)
          src-storage (vs/switch-branch storage src-id)
          b (create-binding-on-current! src-storage fn-id slot-id "v1")
          stamp1 (mp/branch-content-stamp base src-id)
          approve! (fn [uid stamp & {:keys [target] :or {target target-id}}]
                     (sp/create-entity base :branch-approval
                                       {:source-branch-id src-id
                                        :target-branch-id target
                                        :approver-id uid
                                        :content-stamp stamp
                                        :created-at (java.time.Instant/now)}))
          count* (fn [author allow-self?]
                   (mp/count-valid-approvals base src-id target-id #{} author allow-self?))]
      (testing "no approvals → 0"
        (is (zero? (count* nil false))))
      (testing "two distinct reviewers at the current stamp → 2"
        (approve! "alice" stamp1)
        (approve! "bob" stamp1)
        (is (= 2 (count* nil false))))
      (testing "a duplicate approval by the same reviewer stays distinct → 2"
        (approve! "alice" stamp1)
        (is (= 2 (count* nil false))))
      (testing "author's own approval excluded unless self-approval allowed"
        (is (= 1 (count* "alice" false)))
        (is (= 2 (count* "alice" true))))
      (testing "an approval stamped for a DIFFERENT target does not count"
        (approve! "dave" stamp1 :target (:id (vs/create-branch! storage "other-target")))
        (is (= 2 (count* nil false))
            "dave's cross-target approval is ignored — the gate-bypass fix"))
      (testing "a restrictive approver-ids allow-list counts only listed reviewers"
        (is (= 1 (mp/count-valid-approvals base src-id target-id #{"alice"} nil false))
            "only alice is in the allow-list")
        (is (zero? (mp/count-valid-approvals base src-id target-id #{"nobody"} nil false))
            "no listed reviewer approved"))
      (testing "editing the source advances the stamp → prior approvals go stale → 0"
        (sp/update-entity src-storage :binding (:id b) {:value "v2"})
        (is (not= stamp1 (mp/branch-content-stamp base src-id))
            "content stamp advances after an edit")
        (is (zero? (count* nil false))
            "all stamp1 approvals are now stale"))
      (testing "a fresh approval at the new stamp counts again"
        (approve! "carol" (mp/branch-content-stamp base src-id))
        (is (= 1 (count* nil false))))
      (sp/close storage))))


(deftest count-valid-approvals*-pure-core-test
  ;; The pure arity the status projection reuses (audit-2 double-compute
  ;; fix) must agree with the I/O arity's filtering — same stamp/target/
  ;; allow-list/author/self rules, no DB.
  (let [stamp "3|2026"
        tgt "target-A"
        rows [{:approver-id "alice" :content-stamp stamp :target-branch-id tgt}
              {:approver-id "alice" :content-stamp stamp :target-branch-id tgt}    ; dup → distinct
              {:approver-id "bob"   :content-stamp stamp :target-branch-id tgt}
              {:approver-id "carol" :content-stamp "1|old" :target-branch-id tgt}  ; stale → dropped
              {:approver-id "dave"  :content-stamp stamp :target-branch-id "target-B"}]] ; wrong target → dropped
    (is (= 2 (mp/count-valid-approvals* stamp tgt #{} rows nil false)) "distinct + stale + target filtered")
    (is (= 1 (mp/count-valid-approvals* stamp tgt #{} rows "alice" false)) "author excluded")
    (is (= 2 (mp/count-valid-approvals* stamp tgt #{} rows "alice" true)) "author counted when allowed")
    (is (zero? (mp/count-valid-approvals* "9|newer" tgt #{} rows nil false)) "all stale at a newer stamp")
    (is (= 1 (mp/count-valid-approvals* stamp "target-B" #{} rows nil false))
        "switching the target to B counts dave (the only B row), not alice/bob (A)")
    (is (= 1 (mp/count-valid-approvals* stamp tgt #{"alice"} rows nil false)) "restrictive allow-list keeps only alice")
    (is (zero? (mp/count-valid-approvals* stamp tgt #{"zoe"} rows nil false)) "no listed reviewer present")))


(deftest self-approval-allowed?-defaults-on
  (testing "nil :allow-self-approval? ≡ ON (author's own approval counts) —
            solo/small teams aren't locked out; explicit false opts into strict"
    (is (true? (mp/self-approval-allowed? {})) "nil default → true")
    (is (true? (mp/self-approval-allowed? {:allow-self-approval? nil})) "explicit nil → true")
    (is (true? (mp/self-approval-allowed? {:allow-self-approval? true})))
    (is (false? (mp/self-approval-allowed? {:allow-self-approval? false})) "explicit false → strict")))


(deftest delete-branch-cascades-approvals-test
  (testing "deleting a branch removes its :branch-approval rows (no orphans)"
    (let [{:keys [storage base]} (create-test-storage)
          src (vs/create-branch! storage "cascade-src")
          src-id (:id src)]
      (sp/create-entity base :branch-approval
                        {:source-branch-id src-id :approver-id "alice"
                         :content-stamp "x" :created-at (java.time.Instant/now)})
      (sp/create-entity base :branch-approval
                        {:source-branch-id src-id :approver-id "bob"
                         :content-stamp "x" :created-at (java.time.Instant/now)})
      (is (= 2 (count (sp/query-entities base :branch-approval {:source-branch-id src-id}))))
      (vs/delete-branch! storage src-id)
      (is (empty? (sp/query-entities base :branch-approval {:source-branch-id src-id}))
          "approvals cascaded with the branch")
      (sp/close storage))))


(deftest delete-branch-cascades-comments-test
  (testing "deleting a branch removes its :branch-comment rows"
    (let [{:keys [storage base]} (create-test-storage)
          src (vs/create-branch! storage "cmt-cascade-src")
          src-id (:id src)]
      (sp/create-entity base :branch-comment
                        {:source-branch-id src-id :author-id "alice"
                         :body "hi" :created-at (java.time.Instant/now)})
      (is (= 1 (count (sp/query-entities base :branch-comment {:source-branch-id src-id}))))
      (vs/delete-branch! storage src-id)
      (is (empty? (sp/query-entities base :branch-comment {:source-branch-id src-id}))
          "comments cascaded with the branch")
      (sp/close storage))))
