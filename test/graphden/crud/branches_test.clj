(ns ^:integration graphden.crud.branches-test
  "Endpoint-level tests for `graphden.crud.branches` — the read +
   write surface behind /api/branches/* and /api/fns/:fn-id/versions.

   Each test stands up a versioned PG storage on the shared
   container fixture, exercises the public fn, and asserts the
   JSON-shaped return value (stringified UUIDs, ISO timestamps,
   `:ok` flag)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.branches :as branches]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture))


;; =============================================================================
;; Helpers — fresh schema + versioned storage + branch / fn factories
;; =============================================================================

(defn- full-schema
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (es/extend-builder)
      (svcs/extend-builder)
      (ds/build)))


(defn- new-storage
  "Fresh PG → schema initialised → primitive base-fns seeded →
   wrapped with VersionedStorage on the 'main' branch (created on
   first wrap)."
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        storage (pg/create-storage (pth/get-container-config container))]
    (sp/initialize storage (full-schema))
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (vs/wrap-with-versioning storage "main")))


(defn- ctx-of
  [storage]
  {:storage storage})


(defn- mk-branch!
  "Create a branch via the public API + return its row. Asserts
   success so the surrounding test can rely on the row."
  [storage branch-name & [base-ref]]
  (let [{:keys [ok branch]} (branches/create-branch
                              (ctx-of storage)
                              (cond-> {:name branch-name}
                                base-ref (assoc :base-branch-id base-ref)))]
    (is ok (str "create-branch failed for " branch-name))
    branch))


(defn- mk-fn!
  "Insert a versioned :fn row on the current branch + return its id."
  [storage fn-name]
  (let [row (sp/create-entity storage :fn
                              {:id (random-uuid)
                               :name fn-name
                               :parent-ids []})]
    (:id row)))


;; =============================================================================
;; list-branches
;; =============================================================================

(deftest list-branches-empty-returns-just-main
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok branches] cnt :count} (branches/list-branches (ctx-of storage))]
        (is ok)
        (is (= 1 cnt))
        (is (= ["main"] (mapv :name branches)))
        (testing "as-json-branch shape"
          (let [b (first branches)]
            (is (string? (:id b)))
            (is (nil? (:base-branch-id b)) "main has no parent")
            (is (string? (:created-at b))))))
      (finally (sp/close storage)))))


(deftest list-branches-multiple-sorted-by-created-at
  (let [storage (new-storage)]
    (try
      (mk-branch! storage "feature-a")
      (Thread/sleep 5)
      (mk-branch! storage "feature-b")
      (let [{:keys [branches] cnt :count} (branches/list-branches (ctx-of storage))]
        (is (= 3 cnt))
        (is (= ["main" "feature-a" "feature-b"] (mapv :name branches))
            "ascending created-at ordering — main first, then by fork time"))
      (finally (sp/close storage)))))


;; =============================================================================
;; get-branch
;; =============================================================================

(deftest get-branch-by-name
  (let [storage (new-storage)
        b (mk-branch! storage "feat")]
    (try
      (let [{:keys [ok branch]} (branches/get-branch (ctx-of storage) "feat")]
        (is ok)
        (is (= "feat" (:name branch)))
        (is (= (:id b) (:id branch))))
      (finally (sp/close storage)))))


(deftest get-branch-by-uuid
  (let [storage (new-storage)
        b (mk-branch! storage "feat")]
    (try
      (let [{:keys [ok branch]} (branches/get-branch (ctx-of storage) (:id b))]
        (is ok)
        (is (= "feat" (:name branch))))
      (finally (sp/close storage)))))


(deftest get-branch-not-found
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/get-branch (ctx-of storage) "no-such")]
        (is (not ok))
        (is (re-find #"not found" error)))
      (finally (sp/close storage)))))


(deftest get-branch-blank-returns-not-found
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok]} (branches/get-branch (ctx-of storage) "  ")]
        (is (not ok) "blank ref → not-found (resolve-branch-ref guards str/blank?)"))
      (finally (sp/close storage)))))


;; =============================================================================
;; list-fn-versions
;; =============================================================================

(deftest list-fn-versions-nil-fn-id-returns-empty
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok versions] cnt :count}
            (branches/list-fn-versions (ctx-of storage) nil)]
        (is ok)
        (is (zero? cnt))
        (is (= [] versions)))
      (finally (sp/close storage)))))


(deftest list-fn-versions-single-version
  (let [storage (new-storage)
        fn-id (mk-fn! storage "my-fn")]
    (try
      (let [{:keys [ok versions] cnt :count}
            (branches/list-fn-versions (ctx-of storage) fn-id)]
        (is ok)
        (is (= 1 cnt))
        (let [v (first versions)]
          (is (= "my-fn" (:name v)))
          (is (= "main" (:branch-name v)) "joined branch-name")
          (is (zero? (:execution-count v))
              "no executions yet → count 0")
          (is (string? (:id v)) "uuids stringified")
          (is (string? (:branch-id v)))
          (is (string? (:created-at v)))))
      (finally (sp/close storage)))))


(deftest list-fn-versions-cross-branch
  ;; Same fn touched on two branches → two version rows, latest
  ;; created-at first.
  (let [storage (new-storage)
        fn-id (mk-fn! storage "my-fn")
        feat (mk-branch! storage "feat")
        on-feat (vs/switch-branch storage (java.util.UUID/fromString (:id feat)))]
    (try
      (Thread/sleep 5)
      (sp/update-entity on-feat :fn fn-id
                        {:name "my-fn" :description "edited-on-feat"})
      (let [{:keys [versions] cnt :count}
            (branches/list-fn-versions (ctx-of storage) fn-id)]
        (is (= 2 cnt))
        (is (= "feat" (-> versions first :branch-name))
            "latest version (feat) wins the first slot")
        (is (= "main" (-> versions second :branch-name))))
      (finally (sp/close storage)))))


;; =============================================================================
;; diff-branches
;; =============================================================================

(deftest diff-branches-target-not-found
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/diff-branches (ctx-of storage) "no" "main")]
        (is (not ok))
        (is (re-find #"Target branch not found" error)))
      (finally (sp/close storage)))))


(deftest diff-branches-source-not-found
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/diff-branches (ctx-of storage) "main" "no")]
        (is (not ok))
        (is (re-find #"Source branch not found" error)))
      (finally (sp/close storage)))))


(deftest diff-branches-against-missing
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/diff-branches (ctx-of storage) "main" nil)]
        (is (not ok))
        (is (re-find #"against" error)))
      (finally (sp/close storage)))))


(deftest diff-branches-identical-returns-empty
  (let [storage (new-storage)
        feat (mk-branch! storage "feat")]
    (try
      (let [{:keys [ok target source diffs] cnt :count}
            (branches/diff-branches (ctx-of storage) (:name feat) "main")]
        (is ok)
        (is (= "feat" (:name target)))
        (is (= "main" (:name source)))
        (is (zero? cnt))
        (is (= [] diffs)))
      (finally (sp/close storage)))))


(deftest diff-branches-detects-modifications
  (let [storage (new-storage)
        fn-id (mk-fn! storage "shared-fn")
        feat (mk-branch! storage "feat")
        on-feat (vs/switch-branch storage (java.util.UUID/fromString (:id feat)))]
    (try
      (sp/update-entity on-feat :fn fn-id
                        {:name "shared-fn" :description "modified"})
      (let [{:keys [ok diffs] cnt :count}
            (branches/diff-branches (ctx-of storage) (:name feat) "main")]
        (is ok)
        (is (pos? cnt) "feat diverged from main by one fn description edit")
        (let [d (first diffs)]
          (is (= :fn (:entity-name d)))
          (is (string? (:entity-id d)))
          (is (#{:modified :added-in-source :added-in-target} (:change d)))))
      (finally (sp/close storage)))))


;; =============================================================================
;; create-branch
;; =============================================================================

(deftest create-branch-missing-name
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/create-branch (ctx-of storage) {})]
        (is (not ok))
        (is (re-find #":name" error)))
      (finally (sp/close storage)))))


(deftest create-branch-blank-name
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok]} (branches/create-branch (ctx-of storage) {:name "   "})]
        (is (not ok) "blank :name treated as missing"))
      (finally (sp/close storage)))))


(deftest create-branch-duplicate-rejected
  (let [storage (new-storage)]
    (try
      (mk-branch! storage "feat")
      (let [{:keys [ok error]} (branches/create-branch (ctx-of storage)
                                                       {:name "feat"})]
        (is (not ok))
        (is (re-find #"already exists" error)))
      (finally (sp/close storage)))))


(deftest create-branch-default-forks-main
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok branch]} (branches/create-branch (ctx-of storage)
                                                        {:name "feat"})]
        (is ok)
        (is (string? (:base-branch-id branch))
            "default fork picks the wrapper's current branch (main)"))
      (finally (sp/close storage)))))


(deftest create-branch-explicit-base
  (let [storage (new-storage)
        feat (mk-branch! storage "feat")]
    (try
      (let [{:keys [ok branch]} (branches/create-branch (ctx-of storage)
                                                        {:name "feat-2"
                                                         :base-branch-id (:name feat)})]
        (is ok)
        (is (= (:id feat) (:base-branch-id branch))))
      (finally (sp/close storage)))))


(deftest create-branch-unknown-base
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/create-branch
                                 (ctx-of storage)
                                 {:name "feat" :base-branch-id "no-such"})]
        (is (not ok))
        (is (re-find #"Base branch not found" error)))
      (finally (sp/close storage)))))


;; =============================================================================
;; delete-branch
;; =============================================================================

(deftest delete-branch-not-found
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/delete-branch (ctx-of storage) "no-such")]
        (is (not ok))
        (is (re-find #"not found" error)))
      (finally (sp/close storage)))))


(deftest delete-branch-rejects-main
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok reason]} (branches/delete-branch (ctx-of storage) "main")]
        (is (not ok))
        (is (= :main-branch-undeletable reason)))
      (finally (sp/close storage)))))


(deftest delete-branch-rejects-with-children
  (let [storage (new-storage)
        parent (mk-branch! storage "parent")
        _child (mk-branch! storage "child" (:name parent))]
    (try
      (let [{:keys [ok reason child-branch-ids]}
            (branches/delete-branch (ctx-of storage) (:name parent))]
        (is (not ok))
        (is (= :branch-has-children reason))
        (is (seq child-branch-ids) "carries the offending child ids"))
      (finally (sp/close storage)))))


(deftest delete-branch-happy
  (let [storage (new-storage)
        b (mk-branch! storage "ephemeral")]
    (try
      (let [{:keys [ok id] nm :name} (branches/delete-branch (ctx-of storage)
                                                             (:name b))]
        (is ok)
        (is (= (:id b) id))
        (is (= "ephemeral" nm)))
      (testing "branch is gone from list-branches"
        (let [{:keys [branches]} (branches/list-branches (ctx-of storage))]
          (is (not-any? #(= "ephemeral" (:name %)) branches))))
      (finally (sp/close storage)))))


;; =============================================================================
;; preview-conflicts
;; =============================================================================

(deftest preview-conflicts-target-missing
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/preview-conflicts
                                 (ctx-of storage) "no" "main")]
        (is (not ok))
        (is (re-find #"Target branch not found" error)))
      (finally (sp/close storage)))))


(deftest preview-conflicts-source-ref-missing
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/preview-conflicts
                                 (ctx-of storage) "main" nil)]
        (is (not ok))
        (is (re-find #"source" error)))
      (finally (sp/close storage)))))


(deftest preview-conflicts-source-not-found
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/preview-conflicts
                                 (ctx-of storage) "main" "no-such")]
        (is (not ok))
        (is (re-find #"Source branch not found" error)))
      (finally (sp/close storage)))))


(deftest preview-conflicts-no-conflicts
  (let [storage (new-storage)
        feat (mk-branch! storage "feat")]
    (try
      (let [{:keys [ok conflicts source target] cnt :count}
            (branches/preview-conflicts (ctx-of storage) "main" (:name feat))]
        (is ok)
        (is (zero? cnt))
        (is (= [] conflicts))
        (is (= "main" (:name target)))
        (is (= "feat" (:name source))))
      (finally (sp/close storage)))))


;; =============================================================================
;; merge-branch
;; =============================================================================

(deftest merge-branch-target-missing
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/merge-branch
                                 (ctx-of storage) "no" {:source "main"})]
        (is (not ok))
        (is (re-find #"Target branch not found" error)))
      (finally (sp/close storage)))))


(deftest merge-branch-source-field-missing
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/merge-branch
                                 (ctx-of storage) "main" {})]
        (is (not ok))
        (is (re-find #":source" error)))
      (finally (sp/close storage)))))


(deftest merge-branch-source-not-found
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/merge-branch
                                 (ctx-of storage) "main" {:source "no-such"})]
        (is (not ok))
        (is (re-find #"Source branch not found" error)))
      (finally (sp/close storage)))))


(deftest merge-branch-same-source-target-rejected
  (let [storage (new-storage)]
    (try
      (let [{:keys [ok error]} (branches/merge-branch
                                 (ctx-of storage) "main" {:source "main"})]
        (is (not ok))
        (is (re-find #"must differ" error)))
      (finally (sp/close storage)))))


(deftest merge-branch-happy
  (let [storage (new-storage)
        fn-id (mk-fn! storage "shared")
        feat (mk-branch! storage "feat")
        on-feat (vs/switch-branch storage (java.util.UUID/fromString (:id feat)))]
    (try
      ;; Diverge feat from main by editing the fn.
      (sp/update-entity on-feat :fn fn-id
                        {:name "shared" :description "new on feat"})
      (let [{:keys [ok] m :merge} (branches/merge-branch
                                    (ctx-of storage)
                                    "main"
                                    {:source (:name feat)})]
        (is ok)
        (is (string? (:id m)))
        (is (= (:id feat) (:source-branch-id m)))
        (is (string? (:target-branch-id m)))
        (is (string? (:created-at m))))
      (finally (sp/close storage)))))


;; =============================================================================
;; coerce-resolutions (private — covered through public merge path)
;; =============================================================================

(deftest merge-branch-bad-resolutions-silently-dropped
  ;; Unknown choice / non-UUID id / unknown entity-name are silently
  ;; skipped (mirrors merge.clj's `case` matcher). The merge still
  ;; happens because the resolutions don't apply.
  (let [storage (new-storage)
        fn-id (mk-fn! storage "shared")
        feat (mk-branch! storage "feat")
        on-feat (vs/switch-branch storage (java.util.UUID/fromString (:id feat)))]
    (try
      (sp/update-entity on-feat :fn fn-id
                        {:name "shared" :description "edited"})
      (let [{:keys [ok]}
            (branches/merge-branch
              (ctx-of storage)
              "main"
              {:source (:name feat)
               :conflict-resolutions
               [{:entity-name "fn" :entity-id "not-a-uuid" :choice "source"}
                {:entity-name "fn" :entity-id (str fn-id) :choice "elsewhere"}]})]
        (is ok "bad resolutions don't break the merge"))
      (finally (sp/close storage)))))
