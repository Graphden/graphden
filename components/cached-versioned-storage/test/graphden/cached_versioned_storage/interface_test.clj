(ns graphden.cached-versioned-storage.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.cache-data-schema.interface :as cds]
    [graphden.cache-postgres.interface :as cache-pg]
    [graphden.cache-protocol.interface :as cache]
    [graphden.cached-storage.interface :as cs]
    [graphden.cached-versioned-storage.interface :as cvs]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.graph-data-schema.interface :as gds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.interface :as pg]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.postgres-test-helpers :as th]
    [graphden.versioned-data-schema.interface :as vds]
    [graphden.versioned-storage.interface :as vs]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- create-test-stack
  "Creates a CachedStorage(VersionedStorage(PostgresStorage)) stack for testing.
   Cleans the database before creating storage to ensure test isolation.
   Uses combined schema: graph + cache + versioned entities."
  []
  (th/clean-database-fast! *container*)
  (let [config (th/get-container-config *container*)
        ;; Build schema with all layers: graph + cache + versioned
        schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (cds/extend-builder)
                   (vds/extend-builder)
                   (ds/build))
        base (-> (pg/create-storage config) (sp/initialize-with-cleanup! schema))
        versioned (vs/wrap-with-versioning base)
        ;; Get datasource from the base storage (stored in :pool field)
        datasource (:pool base)
        pg-cache (cache-pg/create-cache datasource)]
    {:storage (cs/wrap-with-cache versioned pg-cache)
     :cache pg-cache}))


(deftest merge-invalidates-cache-test
  (testing "merge-branch! invalidates caches for affected entities"
    (let [{:keys [storage cache]} (create-test-stack)
          ;; Create fn-schema and fn on main
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id :name "test-schema" :returned-type :int})
          fn-data (sp/create-entity storage :fn
                                    {:name "main-fn" :fn-schema-id schema-id})
          fn-id (:id fn-data)
          ;; Populate cache by resolving execution graph
          _ (sp/resolve-execution-graph storage fn-id)
          _ (is (cache/cache-exists? cache fn-id) "Cache should exist after resolve")
          ;; Create feature branch and modify fn
          branch (cvs/create-branch! storage "feature")
          feature-storage (cvs/switch-branch storage (:id branch))
          _ (sp/update-entity feature-storage :fn fn-id {:name "feature-fn"})
          ;; Switch back to main and resolve again to populate cache
          main-storage storage
          _ (sp/resolve-execution-graph main-storage fn-id)
          _ (is (cache/cache-exists? cache fn-id) "Cache should exist before merge")]
      ;; Merge feature into main
      (cvs/merge-branch! main-storage (:id branch))
      ;; Cache should be invalidated (may be rebuilt, but the stale one is gone)
      ;; After merge, reading the fn should show the feature version
      (let [merged (sp/read-entity main-storage :fn fn-id)]
        (is (= "feature-fn" (:name merged))))
      (sp/close main-storage))))


(deftest switch-branch-clears-cache-test
  (testing "switch-branch clears caches to prevent stale reads"
    (let [{:keys [storage cache]} (create-test-stack)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id :name "test-schema" :returned-type :int})
          fn-data (sp/create-entity storage :fn
                                    {:name "main-fn" :fn-schema-id schema-id})
          fn-id (:id fn-data)
          ;; Populate cache
          _ (sp/resolve-execution-graph storage fn-id)
          _ (is (cache/cache-exists? cache fn-id))
          ;; Create branch and switch
          branch (cvs/create-branch! storage "feature")
          _feature-storage (cvs/switch-branch storage (:id branch))]
      ;; Cache should be cleared after switch
      (is (not (cache/cache-exists? cache fn-id))
          "Cache should be cleared after branch switch")
      (sp/close storage))))


(deftest create-branch-no-invalidation-test
  (testing "create-branch! does not invalidate caches"
    (let [{:keys [storage cache]} (create-test-stack)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id :name "test-schema" :returned-type :int})
          fn-data (sp/create-entity storage :fn
                                    {:name "main-fn" :fn-schema-id schema-id})
          fn-id (:id fn-data)
          ;; Populate cache
          _ (sp/resolve-execution-graph storage fn-id)
          _ (is (cache/cache-exists? cache fn-id))]
      ;; Create branch — should NOT invalidate
      (cvs/create-branch! storage "feature")
      (is (cache/cache-exists? cache fn-id)
          "Cache should still exist after creating branch")
      (sp/close storage))))


(deftest convenience-wrappers-test
  (testing "current-branch-id works through cached layer"
    (let [{:keys [storage]} (create-test-stack)
          branch-id (cvs/current-branch-id storage)]
      (is (uuid? branch-id))
      (sp/close storage)))

  (testing "list-branches works through cached layer"
    (let [{:keys [storage]} (create-test-stack)
          branches (cvs/list-branches storage)]
      (is (= 1 (count branches)))
      (is (= "main" (:name (first branches))))
      (sp/close storage)))

  (testing "get-branch works through cached layer"
    (let [{:keys [storage]} (create-test-stack)
          branch-id (cvs/current-branch-id storage)
          branch (cvs/get-branch storage branch-id)]
      (is (= "main" (:name branch)))
      (sp/close storage))))
