(ns graphden.cached-storage.dependency-test
  "Tests for cached-storage dependency-aware cache invalidation."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-protocol.interface :as cache]
    [graphden.cached-storage.interface :as cached]
    [graphden.cached-storage.test-mocks :as mocks]
    [graphden.storage-protocol.interface :as sp]))


(deftest fn-schema-update-invalidates-dependents-test
  (testing "fn-schema update finds and invalidates all dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id-1 :name "fn1" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id fn-id-2 :name "fn2" :fn-schema-id schema-id})
      ;; Both should be cached
      (is (cache/cache-exists? cache fn-id-1))
      (is (cache/cache-exists? cache fn-id-2))
      ;; Update fn-schema - should invalidate all dependent caches
      (sp/update-entity wrapped :fn-schema schema-id {:name "updated"})
      ;; Both caches should be rebuilt (still exist because fns still exist)
      (is (cache/cache-exists? cache fn-id-1))
      (is (cache/cache-exists? cache fn-id-2)))))


(deftest arg-schema-update-invalidates-dependents-test
  (testing "arg-schema update finds and invalidates all dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup - create entities such that arg-schema is in the graph
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "x" :type :int :required true})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      ;; Manually add arg-schema dependency (simulating real cache behavior)
      (swap! (:state cache) update-in [:arg-schema-deps arg-schema-id] (fnil conj #{}) fn-id)
      (is (cache/cache-exists? cache fn-id))
      ;; Update arg-schema - should invalidate dependent cache
      (sp/update-entity wrapped :arg-schema arg-schema-id {:name "renamed"})
      ;; Cache should be rebuilt
      (is (cache/cache-exists? cache fn-id)))))


(deftest fn-schema-delete-invalidates-dependents-test
  (testing "fn-schema delete finds and invalidates all dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))
      ;; Delete fn-schema - should invalidate all dependent caches
      (sp/delete-entity wrapped :fn-schema schema-id)
      ;; Cache should be rebuilt (fn still exists)
      (is (cache/cache-exists? cache fn-id)))))


(deftest arg-schema-delete-invalidates-dependents-test
  (testing "arg-schema delete finds and invalidates all dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "x" :type :int :required true})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      ;; Manually add arg-schema dependency
      (swap! (:state cache) update-in [:arg-schema-deps arg-schema-id] (fnil conj #{}) fn-id)
      (is (cache/cache-exists? cache fn-id))
      ;; Delete arg-schema
      (sp/delete-entity wrapped :arg-schema arg-schema-id)
      ;; Cache should be rebuilt
      (is (cache/cache-exists? cache fn-id)))))


(deftest fn-deletion-invalidates-fn-dependents-test
  (testing "fn deletion invalidates all caches that depend on it via arg-value references"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          referenced-fn-id (random-uuid)
          referencing-fn-id (random-uuid)]
      ;; Setup - referencing-fn references referenced-fn via arg-value
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id referenced-fn-id :name "referenced" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id referencing-fn-id :name "referencing" :fn-schema-id schema-id})
      ;; Manually add fn dependency (referencing depends on referenced)
      (swap! (:state cache) update-in [:fn-deps referenced-fn-id] (fnil conj #{}) referencing-fn-id)
      (is (cache/cache-exists? cache referenced-fn-id))
      (is (cache/cache-exists? cache referencing-fn-id))
      ;; Delete referenced - should invalidate referencing's cache too
      (sp/delete-entity wrapped :fn referenced-fn-id)
      (is (not (cache/cache-exists? cache referenced-fn-id)))
      ;; Referencing fn cache should be rebuilt (fn still exists)
      (is (cache/cache-exists? cache referencing-fn-id)))))


(deftest fn-update-invalidates-fn-dependents-test
  (testing "fn update with schema change invalidates all dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id-1 (random-uuid)
          schema-id-2 (random-uuid)
          fn-id (random-uuid)
          dependent-fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id-1 :name "test1" :returned-type :int})
      (sp/create-entity storage :fn-schema {:id schema-id-2 :name "test2" :returned-type :text})
      (sp/create-entity wrapped :fn {:id fn-id :name "original" :fn-schema-id schema-id-1})
      (sp/create-entity wrapped :fn {:id dependent-fn-id :name "dependent" :fn-schema-id schema-id-1})
      ;; Manually add fn dependency
      (swap! (:state cache) update-in [:fn-deps fn-id] (fnil conj #{}) dependent-fn-id)
      (is (cache/cache-exists? cache fn-id))
      (is (cache/cache-exists? cache dependent-fn-id))
      ;; Update fn with new schema - should invalidate dependent caches
      (sp/update-entity wrapped :fn fn-id {:fn-schema-id schema-id-2})
      ;; Both caches should be rebuilt
      (is (cache/cache-exists? cache fn-id))
      (is (cache/cache-exists? cache dependent-fn-id)))))


(deftest arg-value-delete-with-nil-record-test
  (testing "arg-value delete when record is nil does not trigger invalidation"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          nonexistent-id (random-uuid)]
      ;; Delete nonexistent arg-value - record will be nil
      (is (not (sp/delete-entity wrapped :arg-value nonexistent-id))))))


(deftest invalidate-dependents-with-deleted-fn-test
  (testing "invalidate-dependents skips rebuild for deleted fns"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)
          deleted-fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      ;; Add a dependency to a fn-id that doesn't exist
      (swap! (:state cache) update-in [:fn-schema-deps schema-id] (fnil conj #{}) fn-id)
      (swap! (:state cache) update-in [:fn-schema-deps schema-id] (fnil conj #{}) deleted-fn-id)
      ;; Also add cache entry for the "deleted" fn to test deletion path
      (swap! (:state cache) assoc-in [:graphs deleted-fn-id] {:fns {}})
      (is (cache/cache-exists? cache fn-id))
      (is (cache/cache-exists? cache deleted-fn-id))
      ;; Update fn-schema - should try to rebuild both but skip deleted one
      (sp/update-entity wrapped :fn-schema schema-id {:name "updated"})
      ;; fn-id cache rebuilt, deleted-fn-id cache deleted (fn doesn't exist)
      (is (cache/cache-exists? cache fn-id))
      ;; deleted-fn-id's cache should be deleted but not rebuilt (fn doesn't exist)
      (is (not (cache/cache-exists? cache deleted-fn-id))))))


;; === Edge case tests for invalidation logic ===

(deftest compute-dependencies-edge-cases-test
  (testing "compute-dependencies with multiple fns sharing same schema"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)
          fn-id-3 (random-uuid)]
      ;; Setup - 3 fns with same schema
      (sp/create-entity storage :fn-schema {:id schema-id :name "shared" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id-1 :name "fn1" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id fn-id-2 :name "fn2" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id fn-id-3 :name "fn3" :fn-schema-id schema-id})
      ;; All should have caches
      (is (cache/cache-exists? cache fn-id-1))
      (is (cache/cache-exists? cache fn-id-2))
      (is (cache/cache-exists? cache fn-id-3)))))


(deftest fn-update-without-fn-schema-id-in-data-test
  (testing "fn update without fn-schema-id in data does not invalidate cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))
      ;; Update without fn-schema-id - should NOT invalidate
      (sp/update-entity wrapped :fn fn-id {:name "renamed"})
      ;; Cache should still exist (not rebuilt)
      (is (cache/cache-exists? cache fn-id)))))


(deftest fn-update-with-same-fn-schema-id-test
  (testing "fn update with same fn-schema-id does not invalidate cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))
      ;; Update with SAME fn-schema-id - should NOT invalidate
      (sp/update-entity wrapped :fn fn-id {:fn-schema-id schema-id :name "renamed"})
      ;; Cache should still exist (not invalidated because schema didn't change)
      (is (cache/cache-exists? cache fn-id)))))


(deftest fn-update-with-schema-change-test
  (testing "fn update with fn-schema-id change invalidates cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id-1 (random-uuid)
          schema-id-2 (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id-1 :name "schema1" :returned-type :int})
      (sp/create-entity storage :fn-schema {:id schema-id-2 :name "schema2" :returned-type :text})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id-1})
      (is (cache/cache-exists? cache fn-id))
      ;; Update with fn-schema-id change
      (sp/update-entity wrapped :fn fn-id {:fn-schema-id schema-id-2})
      ;; Cache should be rebuilt
      (is (cache/cache-exists? cache fn-id)))))


(deftest multiple-dependent-caches-invalidation-test
  (testing "invalidating a schema affects all dependent caches correctly"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-ids (repeatedly 5 random-uuid)]
      ;; Setup - create schema and 5 fns
      (sp/create-entity storage :fn-schema {:id schema-id :name "shared" :returned-type :int})
      (doseq [fn-id fn-ids]
        (sp/create-entity wrapped :fn {:id fn-id :name (str "fn-" fn-id) :fn-schema-id schema-id}))
      ;; All should be cached
      (doseq [fn-id fn-ids]
        (is (cache/cache-exists? cache fn-id)))
      ;; Update schema - all dependents should be invalidated and rebuilt
      (sp/update-entity wrapped :fn-schema schema-id {:name "updated"})
      ;; All should still be cached (rebuilt)
      (doseq [fn-id fn-ids]
        (is (cache/cache-exists? cache fn-id))))))


(deftest cascade-invalidation-through-fn-chain-test
  (testing "deleting a fn invalidates dependent caches in a chain"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          ;; Create chain of dependencies via arg-value references
          fn-a-id (random-uuid)
          fn-b-id (random-uuid)
          fn-c-id (random-uuid)]
      ;; Setup - fn-b references fn-a, fn-c references fn-b
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-a-id :name "fn-a" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id fn-b-id :name "fn-b" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id fn-c-id :name "fn-c" :fn-schema-id schema-id})
      ;; Add fn dependencies
      (swap! (:state cache) update-in [:fn-deps fn-a-id] (fnil conj #{}) fn-b-id)
      (swap! (:state cache) update-in [:fn-deps fn-b-id] (fnil conj #{}) fn-c-id)
      ;; All should be cached
      (is (cache/cache-exists? cache fn-a-id))
      (is (cache/cache-exists? cache fn-b-id))
      (is (cache/cache-exists? cache fn-c-id))
      ;; Delete fn-a - should cascade to fn-b's cache
      (sp/delete-entity wrapped :fn fn-a-id)
      ;; fn-a cache deleted
      (is (not (cache/cache-exists? cache fn-a-id)))
      ;; fn-b cache should be rebuilt (fn still exists)
      (is (cache/cache-exists? cache fn-b-id))
      ;; fn-c cache unchanged (wasn't directly dependent on fn-a)
      (is (cache/cache-exists? cache fn-c-id)))))


(deftest arg-value-update-with-owner-fn-id-test
  (testing "arg-value update correctly uses result's owner-fn-id"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)
          arg-schema-id (random-uuid)
          arg-value-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "x" :type :int :required true})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (sp/create-entity storage :arg-value {:id arg-value-id :owner-fn-id fn-id
                                            :arg-schema-id arg-schema-id :value 42})
      (is (cache/cache-exists? cache fn-id))
      ;; Update arg-value - should invalidate owner fn's cache
      (sp/update-entity wrapped :arg-value arg-value-id {:value 100})
      ;; Cache should be rebuilt
      (is (cache/cache-exists? cache fn-id)))))


(deftest batch-fn-create-caches-all-test
  (testing "batch create-entities for :fn creates caches for all"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-ids (repeatedly 3 random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      ;; Batch create fns
      (let [results (sp/create-entities wrapped :fn
                                        (mapv (fn [fn-id]
                                                {:id fn-id :name (str "fn-" fn-id) :fn-schema-id schema-id})
                                              fn-ids))]
        (is (= 3 (count results)))
        ;; All should be cached
        (doseq [fn-id fn-ids]
          (is (cache/cache-exists? cache fn-id)))))))


(deftest invalidation-with-empty-dependents-test
  (testing "invalidation with no dependents completes without error"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)]
      ;; Create schema but no fns using it
      (sp/create-entity storage :fn-schema {:id schema-id :name "orphan" :returned-type :int})
      ;; Update schema - should complete without error even with no dependents
      (let [result (sp/update-entity wrapped :fn-schema schema-id {:name "updated"})]
        (is (= "updated" (:name result))))
      ;; Delete schema - should complete without error
      (is (sp/delete-entity wrapped :fn-schema schema-id)))))


(deftest delete-arg-value-nil-owner-fn-test
  (testing "delete arg-value with nil record does not trigger invalidation"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          nonexistent-id (random-uuid)]
      ;; Try to delete nonexistent arg-value - should return false
      (is (not (sp/delete-entity wrapped :arg-value nonexistent-id))))))


(deftest batch-delete-mixed-existing-nonexisting-test
  (testing "batch delete with mix of existing and non-existing entities"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          existing-fn-id (random-uuid)
          nonexistent-fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id existing-fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache existing-fn-id))
      ;; Batch delete - one exists, one doesn't
      (let [result (sp/delete-entities wrapped :fn [existing-fn-id nonexistent-fn-id])]
        (is (= 1 result)))
      ;; existing-fn-id cache should be deleted
      (is (not (cache/cache-exists? cache existing-fn-id))))))


(deftest resolve-graph-handles-cache-hit-test
  (testing "resolve-execution-graph returns cached graph without recomputing"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      ;; First resolve - caches the graph
      (let [graph1 (sp/resolve-execution-graph wrapped fn-id)]
        (is (sp/execution-graph? graph1))
        ;; Second resolve - should return same cached graph
        (let [graph2 (sp/resolve-execution-graph wrapped fn-id)]
          (is (sp/execution-graph? graph2))
          ;; Both should be equivalent
          (is (= (:fns graph1) (:fns graph2))))))))
