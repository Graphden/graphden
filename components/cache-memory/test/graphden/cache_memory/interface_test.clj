(ns graphden.cache-memory.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-memory.interface :as cache-memory]
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


;; === Unit tests ===

(deftest create-cache-test
  (testing "creates MemoryCache instance"
    (let [c (cache-memory/create-cache)]
      (is (some? c))
      (is (cache/cached-storage? c)))))


(deftest cache-exists-test
  (testing "returns false for non-existent cache"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)]
      (is (false? (cache/cache-exists? c fn-id))))))


(deftest save-and-get-cache-test
  (testing "saves and retrieves execution graph"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          graph {:fns {fn-id {:id fn-id
                              :name "test-fn"
                              :fn-schema-id fn-schema-id}}
                 :fn-schemas {fn-schema-id {:id fn-schema-id
                                            :name "test-schema"
                                            :base-fn-name "base-fn"
                                            :returned-type :text}}
                 :arg-schemas {arg-schema-id {:id arg-schema-id
                                              :fn-schema-id fn-schema-id
                                              :name "arg1"
                                              :type :text
                                              :required true}}
                 :resolved-args {fn-id {arg-schema-id "test-value"}}
                 :call-sites {}}
          dependencies {:fn-ids {fn-id 1}
                        :fn-schema-ids {fn-schema-id 1}
                        :arg-schema-ids {arg-schema-id 1}}]
      ;; Save cache
      (cache/save-cache! c fn-id graph dependencies)
      ;; Verify it exists
      (is (true? (cache/cache-exists? c fn-id)))
      ;; Get cached graph
      (let [cached (cache/get-cached-graph c fn-id)]
        (is (some? cached))
        (is (sp/execution-graph? cached))
        ;; Verify fns
        (is (= 1 (count (:fns cached))))
        (is (= "test-fn" (get-in cached [:fns fn-id :name])))
        ;; Verify fn-schemas
        (is (= 1 (count (:fn-schemas cached))))
        (is (= "test-schema" (get-in cached [:fn-schemas fn-schema-id :name])))
        (is (= :text (get-in cached [:fn-schemas fn-schema-id :returned-type])))
        ;; Verify arg-schemas
        (is (= 1 (count (:arg-schemas cached))))
        (is (= "arg1" (get-in cached [:arg-schemas arg-schema-id :name])))
        (is (= :text (get-in cached [:arg-schemas arg-schema-id :type])))
        ;; Verify resolved-args
        (is (= "test-value" (get-in cached [:resolved-args fn-id arg-schema-id])))))))


(deftest delete-cache-test
  (testing "deletes cache data"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)
          fn-schema-id (random-uuid)
          graph {:fns {fn-id {:id fn-id
                              :name "test-fn"
                              :fn-schema-id fn-schema-id}}
                 :fn-schemas {fn-schema-id {:id fn-schema-id
                                            :name "test-schema"
                                            :base-fn-name "base-fn"
                                            :returned-type :text}}
                 :arg-schemas {}
                 :resolved-args {}
                 :call-sites {}}
          dependencies {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
      ;; Save and verify
      (cache/save-cache! c fn-id graph dependencies)
      (is (true? (cache/cache-exists? c fn-id)))
      ;; Delete
      (is (true? (cache/delete-cache! c fn-id)))
      ;; Verify deleted
      (is (false? (cache/cache-exists? c fn-id)))
      (is (nil? (cache/get-cached-graph c fn-id)))
      ;; Delete again returns false
      (is (false? (cache/delete-cache! c fn-id))))))


(deftest find-caches-by-deps-test
  (testing "finds caches by dependency"
    (let [c (cache-memory/create-cache)
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)
          shared-dep-fn-id (random-uuid)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          ;; First cache depends on shared-dep-fn-id
          graph-1 {:fns {fn-id-1 {:id fn-id-1
                                  :name "fn-1"
                                  :fn-schema-id fn-schema-id}}
                   :fn-schemas {fn-schema-id {:id fn-schema-id
                                              :name "schema"
                                              :base-fn-name "base"
                                              :returned-type :text}}
                   :arg-schemas {arg-schema-id {:id arg-schema-id
                                                :fn-schema-id fn-schema-id
                                                :name "arg"
                                                :type :text
                                                :required false}}
                   :resolved-args {}
                   :call-sites {}}
          deps-1 {:fn-ids {shared-dep-fn-id 1}
                  :fn-schema-ids {fn-schema-id 1}
                  :arg-schema-ids {arg-schema-id 1}}
          ;; Second cache also depends on shared-dep-fn-id
          graph-2 {:fns {fn-id-2 {:id fn-id-2
                                  :name "fn-2"
                                  :fn-schema-id fn-schema-id}}
                   :fn-schemas {fn-schema-id {:id fn-schema-id
                                              :name "schema"
                                              :base-fn-name "base"
                                              :returned-type :text}}
                   :arg-schemas {arg-schema-id {:id arg-schema-id
                                                :fn-schema-id fn-schema-id
                                                :name "arg"
                                                :type :text
                                                :required false}}
                   :resolved-args {}
                   :call-sites {}}
          deps-2 {:fn-ids {shared-dep-fn-id 2}
                  :fn-schema-ids {fn-schema-id 1}
                  :arg-schema-ids {arg-schema-id 1}}]
      ;; Save both caches
      (cache/save-cache! c fn-id-1 graph-1 deps-1)
      (cache/save-cache! c fn-id-2 graph-2 deps-2)
      ;; Find caches by fn dependency
      (let [affected (cache/find-caches-by-fn-dep c shared-dep-fn-id)]
        (is (set? affected))
        (is (= 2 (count affected)))
        (is (contains? affected fn-id-1))
        (is (contains? affected fn-id-2)))
      ;; Find caches by fn-schema dependency
      (let [affected (cache/find-caches-by-fn-schema-dep c fn-schema-id)]
        (is (= 2 (count affected))))
      ;; Find caches by arg-schema dependency
      (let [affected (cache/find-caches-by-arg-schema-dep c arg-schema-id)]
        (is (= 2 (count affected)))))))


(deftest cache-overwrites-existing-test
  (testing "save-cache! overwrites existing cache"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)
          fn-schema-id (random-uuid)
          graph-v1 {:fns {fn-id {:id fn-id
                                 :name "v1"
                                 :fn-schema-id fn-schema-id}}
                    :fn-schemas {fn-schema-id {:id fn-schema-id
                                               :name "schema"
                                               :base-fn-name "base"
                                               :returned-type :text}}
                    :arg-schemas {}
                    :resolved-args {}
                    :call-sites {}}
          graph-v2 {:fns {fn-id {:id fn-id
                                 :name "v2"
                                 :fn-schema-id fn-schema-id}}
                    :fn-schemas {fn-schema-id {:id fn-schema-id
                                               :name "schema"
                                               :base-fn-name "base"
                                               :returned-type :text}}
                    :arg-schemas {}
                    :resolved-args {}
                    :call-sites {}}
          deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
      ;; Save v1
      (cache/save-cache! c fn-id graph-v1 deps)
      (is (= "v1" (get-in (cache/get-cached-graph c fn-id) [:fns fn-id :name])))
      ;; Save v2 (should overwrite)
      (cache/save-cache! c fn-id graph-v2 deps)
      (is (= "v2" (get-in (cache/get-cached-graph c fn-id) [:fns fn-id :name]))))))


(deftest dependency-cleanup-on-overwrite-test
  (testing "old dependencies are cleaned up when cache is overwritten"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)
          fn-schema-id (random-uuid)
          old-dep-fn-id (random-uuid)
          new-dep-fn-id (random-uuid)
          graph {:fns {fn-id {:id fn-id
                              :name "test"
                              :fn-schema-id fn-schema-id}}
                 :fn-schemas {fn-schema-id {:id fn-schema-id
                                            :name "schema"
                                            :base-fn-name "base"
                                            :returned-type :text}}
                 :arg-schemas {}
                 :resolved-args {}
                 :call-sites {}}
          old-deps {:fn-ids {old-dep-fn-id 1}
                    :fn-schema-ids {}
                    :arg-schema-ids {}}
          new-deps {:fn-ids {new-dep-fn-id 1}
                    :fn-schema-ids {}
                    :arg-schema-ids {}}]
      ;; Save with old dependency
      (cache/save-cache! c fn-id graph old-deps)
      (is (contains? (cache/find-caches-by-fn-dep c old-dep-fn-id) fn-id))
      (is (empty? (cache/find-caches-by-fn-dep c new-dep-fn-id)))
      ;; Save with new dependency
      (cache/save-cache! c fn-id graph new-deps)
      ;; Old dep should be cleaned up
      (is (empty? (cache/find-caches-by-fn-dep c old-dep-fn-id)))
      ;; New dep should be active
      (is (contains? (cache/find-caches-by-fn-dep c new-dep-fn-id) fn-id)))))


(deftest dependency-cleanup-on-delete-test
  (testing "dependencies are cleaned up when cache is deleted"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)
          fn-schema-id (random-uuid)
          dep-fn-id (random-uuid)
          graph {:fns {fn-id {:id fn-id
                              :name "test"
                              :fn-schema-id fn-schema-id}}
                 :fn-schemas {fn-schema-id {:id fn-schema-id
                                            :name "schema"
                                            :base-fn-name "base"
                                            :returned-type :text}}
                 :arg-schemas {}
                 :resolved-args {}
                 :call-sites {}}
          deps {:fn-ids {dep-fn-id 1}
                :fn-schema-ids {fn-schema-id 1}
                :arg-schema-ids {}}]
      ;; Save
      (cache/save-cache! c fn-id graph deps)
      (is (contains? (cache/find-caches-by-fn-dep c dep-fn-id) fn-id))
      (is (contains? (cache/find-caches-by-fn-schema-dep c fn-schema-id) fn-id))
      ;; Delete
      (cache/delete-cache! c fn-id)
      ;; Dependencies should be cleaned up
      (is (empty? (cache/find-caches-by-fn-dep c dep-fn-id)))
      (is (empty? (cache/find-caches-by-fn-schema-dep c fn-schema-id))))))


(deftest delete-nonexistent-cache-test
  (testing "deleting non-existent cache returns false"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)]
      (is (false? (cache/delete-cache! c fn-id))))))


(deftest get-cached-graph-nonexistent-test
  (testing "get-cached-graph returns nil for non-existent cache"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)]
      (is (nil? (cache/get-cached-graph c fn-id))))))


(deftest find-deps-for-nonexistent-returns-empty-test
  (testing "find-caches-by-*-dep returns empty set for non-existent dep"
    (let [c (cache-memory/create-cache)
          non-existent-id (random-uuid)]
      (is (= #{} (cache/find-caches-by-fn-dep c non-existent-id)))
      (is (= #{} (cache/find-caches-by-fn-schema-dep c non-existent-id)))
      (is (= #{} (cache/find-caches-by-arg-schema-dep c non-existent-id))))))


(deftest multiple-caches-sharing-deps-test
  (testing "multiple caches can share the same dependency"
    (let [c (cache-memory/create-cache)
          shared-schema-id (random-uuid)
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)
          fn-id-3 (random-uuid)
          make-graph (fn [fid]
                       {:fns {fid {:id fid :name "fn" :fn-schema-id shared-schema-id}}
                        :fn-schemas {shared-schema-id {:id shared-schema-id :name "s" :base-fn-name "b" :returned-type :int}}
                        :arg-schemas {}
                        :resolved-args {}
                        :call-sites {}})
          deps {:fn-ids {} :fn-schema-ids {shared-schema-id 1} :arg-schema-ids {}}]

      ;; Create 3 caches all depending on the same schema
      (cache/save-cache! c fn-id-1 (make-graph fn-id-1) deps)
      (cache/save-cache! c fn-id-2 (make-graph fn-id-2) deps)
      (cache/save-cache! c fn-id-3 (make-graph fn-id-3) deps)

      ;; All 3 should be found
      (let [affected (cache/find-caches-by-fn-schema-dep c shared-schema-id)]
        (is (= 3 (count affected)))
        (is (= #{fn-id-1 fn-id-2 fn-id-3} affected)))

      ;; Delete one - others should still be there
      (cache/delete-cache! c fn-id-2)
      (let [affected (cache/find-caches-by-fn-schema-dep c shared-schema-id)]
        (is (= 2 (count affected)))
        (is (= #{fn-id-1 fn-id-3} affected))))))


(deftest execution-graph-record-preservation-test
  (testing "ExecutionGraphResult is preserved through cache"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)
          schema-id (random-uuid)
          ;; Pass a proper ExecutionGraphResult
          graph (sp/->execution-graph
                  {:fns {fn-id {:id fn-id :name "fn" :fn-schema-id schema-id}}
                   :fn-schemas {schema-id {:id schema-id :name "s" :base-fn-name "b" :returned-type :int}}
                   :arg-schemas {}
                   :resolved-args {}
                   :call-sites {}})
          deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]

      (cache/save-cache! c fn-id graph deps)

      (let [cached (cache/get-cached-graph c fn-id)]
        (is (sp/execution-graph? cached))))))


(deftest concurrent-read-write-safety-test
  (testing "concurrent reads and writes are safe"
    (let [c (cache-memory/create-cache)
          schema-id (random-uuid)
          make-graph (fn [fid]
                       {:fns {fid {:id fid :name "fn" :fn-schema-id schema-id}}
                        :fn-schemas {schema-id {:id schema-id :name "s" :base-fn-name "b" :returned-type :int}}
                        :arg-schemas {}
                        :resolved-args {}
                        :call-sites {}})
          deps {:fn-ids {} :fn-schema-ids {schema-id 1} :arg-schema-ids {}}
          fn-ids (repeatedly 100 random-uuid)]

      ;; Parallel writes
      (doall
        (pmap (fn [fid]
                (cache/save-cache! c fid (make-graph fid) deps))
              fn-ids))

      ;; Verify all were saved
      (is (= 100 (count (cache/find-caches-by-fn-schema-dep c schema-id))))

      ;; Parallel reads and deletes
      (let [results (doall
                      (pmap (fn [fid]
                              (let [g (cache/get-cached-graph c fid)]
                                (cache/delete-cache! c fid)
                                (some? g)))
                            fn-ids))]
        (is (every? true? results)))

      ;; All should be deleted
      (is (zero? (count (cache/find-caches-by-fn-schema-dep c schema-id)))))))


(deftest dependency-partial-cleanup-keeps-other-caches-test
  (testing "removing one cache keeps deps for other caches"
    (let [c (cache-memory/create-cache)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)
          dep-fn-id (random-uuid)
          graph-1 {:fns {fn-id-1 {:id fn-id-1 :name "fn1" :fn-schema-id fn-schema-id}}
                   :fn-schemas {fn-schema-id {:id fn-schema-id :name "s" :base-fn-name "b" :returned-type :int}}
                   :arg-schemas {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id
                                                :name "a" :type :int :required true}}
                   :resolved-args {}
                   :call-sites {}}
          graph-2 {:fns {fn-id-2 {:id fn-id-2 :name "fn2" :fn-schema-id fn-schema-id}}
                   :fn-schemas {fn-schema-id {:id fn-schema-id :name "s" :base-fn-name "b" :returned-type :int}}
                   :arg-schemas {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id
                                                :name "a" :type :int :required true}}
                   :resolved-args {}
                   :call-sites {}}
          deps {:fn-ids {dep-fn-id 1}
                :fn-schema-ids {fn-schema-id 1}
                :arg-schema-ids {arg-schema-id 1}}]
      ;; Save both caches
      (cache/save-cache! c fn-id-1 graph-1 deps)
      (cache/save-cache! c fn-id-2 graph-2 deps)
      ;; Both should be in deps
      (is (= #{fn-id-1 fn-id-2} (cache/find-caches-by-fn-dep c dep-fn-id)))
      (is (= #{fn-id-1 fn-id-2} (cache/find-caches-by-fn-schema-dep c fn-schema-id)))
      (is (= #{fn-id-1 fn-id-2} (cache/find-caches-by-arg-schema-dep c arg-schema-id)))
      ;; Delete first cache
      (cache/delete-cache! c fn-id-1)
      ;; Second should still be in deps (non-empty after removal)
      (is (= #{fn-id-2} (cache/find-caches-by-fn-dep c dep-fn-id)))
      (is (= #{fn-id-2} (cache/find-caches-by-fn-schema-dep c fn-schema-id)))
      (is (= #{fn-id-2} (cache/find-caches-by-arg-schema-dep c arg-schema-id)))
      ;; Delete second - now deps should be empty
      (cache/delete-cache! c fn-id-2)
      (is (= #{} (cache/find-caches-by-fn-dep c dep-fn-id)))
      (is (= #{} (cache/find-caches-by-fn-schema-dep c fn-schema-id)))
      (is (= #{} (cache/find-caches-by-arg-schema-dep c arg-schema-id))))))


(deftest overwrite-with-shared-deps-test
  (testing "overwriting cache with shared deps keeps other caches' deps intact"
    (let [c (cache-memory/create-cache)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)
          old-dep-fn-id (random-uuid)
          shared-dep-fn-id (random-uuid)
          new-dep-fn-id (random-uuid)
          make-graph (fn [fid]
                       {:fns {fid {:id fid :name "fn" :fn-schema-id fn-schema-id}}
                        :fn-schemas {fn-schema-id {:id fn-schema-id :name "s" :base-fn-name "b" :returned-type :int}}
                        :arg-schemas {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id
                                                     :name "a" :type :int :required true}}
                        :resolved-args {}
                        :call-sites {}})
          old-deps {:fn-ids {old-dep-fn-id 1 shared-dep-fn-id 1}
                    :fn-schema-ids {fn-schema-id 1}
                    :arg-schema-ids {arg-schema-id 1}}
          shared-deps {:fn-ids {shared-dep-fn-id 1}
                       :fn-schema-ids {fn-schema-id 1}
                       :arg-schema-ids {arg-schema-id 1}}
          new-deps {:fn-ids {new-dep-fn-id 1 shared-dep-fn-id 1}
                    :fn-schema-ids {fn-schema-id 1}
                    :arg-schema-ids {arg-schema-id 1}}]
      ;; Save first cache with old-dep and shared-dep
      (cache/save-cache! c fn-id-1 (make-graph fn-id-1) old-deps)
      ;; Save second cache with only shared-dep
      (cache/save-cache! c fn-id-2 (make-graph fn-id-2) shared-deps)
      ;; Verify initial state
      (is (= #{fn-id-1} (cache/find-caches-by-fn-dep c old-dep-fn-id)))
      (is (= #{fn-id-1 fn-id-2} (cache/find-caches-by-fn-dep c shared-dep-fn-id)))
      ;; Overwrite first cache with new-dep (removes old-dep, keeps shared-dep)
      (cache/save-cache! c fn-id-1 (make-graph fn-id-1) new-deps)
      ;; old-dep should be completely gone
      (is (= #{} (cache/find-caches-by-fn-dep c old-dep-fn-id)))
      ;; shared-dep should still have both caches
      (is (= #{fn-id-1 fn-id-2} (cache/find-caches-by-fn-dep c shared-dep-fn-id)))
      ;; new-dep should have first cache
      (is (= #{fn-id-1} (cache/find-caches-by-fn-dep c new-dep-fn-id))))))


(deftest save-cache-with-all-dep-types-test
  (testing "save-cache! handles all dependency types correctly"
    (let [c (cache-memory/create-cache)
          fn-id (random-uuid)
          fn-schema-id (random-uuid)
          arg-schema-id-1 (random-uuid)
          arg-schema-id-2 (random-uuid)
          dep-fn-id-1 (random-uuid)
          dep-fn-id-2 (random-uuid)
          graph {:fns {fn-id {:id fn-id :name "fn" :fn-schema-id fn-schema-id}}
                 :fn-schemas {fn-schema-id {:id fn-schema-id :name "s" :base-fn-name "b" :returned-type :int}}
                 :arg-schemas {arg-schema-id-1 {:id arg-schema-id-1 :fn-schema-id fn-schema-id
                                                :name "a1" :type :int :required true}
                               arg-schema-id-2 {:id arg-schema-id-2 :fn-schema-id fn-schema-id
                                                :name "a2" :type :text :required false}}
                 :resolved-args {}
                 :call-sites {}}
          deps {:fn-ids {dep-fn-id-1 1 dep-fn-id-2 1}
                :fn-schema-ids {fn-schema-id 1}
                :arg-schema-ids {arg-schema-id-1 1 arg-schema-id-2 1}}]
      (cache/save-cache! c fn-id graph deps)
      ;; All deps should be tracked
      (is (= #{fn-id} (cache/find-caches-by-fn-dep c dep-fn-id-1)))
      (is (= #{fn-id} (cache/find-caches-by-fn-dep c dep-fn-id-2)))
      (is (= #{fn-id} (cache/find-caches-by-fn-schema-dep c fn-schema-id)))
      (is (= #{fn-id} (cache/find-caches-by-arg-schema-dep c arg-schema-id-1)))
      (is (= #{fn-id} (cache/find-caches-by-arg-schema-dep c arg-schema-id-2))))))
