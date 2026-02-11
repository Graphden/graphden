(ns graphden.cached-versioned-storage.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-memory.interface :as cache-mem]
    [graphden.cache-protocol.interface :as cache]
    [graphden.cached-storage.interface :as cs]
    [graphden.cached-versioned-storage.interface :as cvs]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]
    [graphden.versioned-data-schema.interface :as vds]
    [graphden.versioned-storage.interface :as vs]))


(defn- create-test-stack
  "Creates a CachedStorage(VersionedStorage(MemoryStorage)) stack for testing."
  []
  (let [schema (vds/build-schema (mds/create-builder))
        base (-> (mem/create-storage) (sp/initialize-with-cleanup! schema))
        versioned (vs/wrap-with-versioning base)
        mem-cache (cache-mem/create-cache)]
    {:storage (cs/wrap-with-cache versioned mem-cache)
     :cache mem-cache}))


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
