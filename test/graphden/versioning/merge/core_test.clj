(ns ^:integration graphden.versioning.merge.core-test
  "Tests for `graphden.versioning.merge.core` — merge protection traits.

   Storage stack: PostgreSQL via testcontainer + graph + versioning +
   traits schema. The trait-based protection requires a real binding
   row to attach to, so every test seeds a base-fn + slot + binding
   before exercising the protection API."
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
