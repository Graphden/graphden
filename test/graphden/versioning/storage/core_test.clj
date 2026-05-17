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
    [graphden.versioning.storage.core :as vs]))


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
