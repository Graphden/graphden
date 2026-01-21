(ns graphden.cache-postgres.cache-deps-test
  "Integration tests for cache dependency management."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.cache-postgres.interface :as cache-pg]
    [graphden.cache-postgres.test-setup :as setup]
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


(deftest find-caches-by-deps-integration-test
  (testing "finds caches by dependency"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :text
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "arg"
                                            :type :text
                                            :required false})
              arg-schema-id (:id arg-schema)
              ;; Create shared dependency fn
              shared-dep-fn (sp/create-entity storage :fn
                                              {:name "shared-dep"
                                               :fn-schema-id fn-schema-id})
              shared-dep-fn-id (:id shared-dep-fn)
              ;; First cache fn
              fn-record-1 (sp/create-entity storage :fn
                                            {:name "fn-1"
                                             :fn-schema-id fn-schema-id})
              fn-id-1 (:id fn-record-1)
              ;; Second cache fn
              fn-record-2 (sp/create-entity storage :fn
                                            {:name "fn-2"
                                             :fn-schema-id fn-schema-id})
              fn-id-2 (:id fn-record-2)
              ;; Build graphs
              make-graph (fn [fid fname]
                           {:fns {fid {:id fid
                                       :name fname
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
                            :fn-result-values {}})
              deps {:fn-ids {shared-dep-fn-id 1}
                    :fn-schema-ids {fn-schema-id 1}
                    :arg-schema-ids {arg-schema-id 1}}]
          ;; Save both caches
          (cache/save-cache! cache fn-id-1 (make-graph fn-id-1 "fn-1") deps)
          (cache/save-cache! cache fn-id-2 (make-graph fn-id-2 "fn-2") deps)
          ;; Find caches by fn dependency
          (let [affected (cache/find-caches-by-fn-dep cache shared-dep-fn-id)]
            (is (set? affected))
            (is (= 2 (count affected)))
            (is (contains? affected fn-id-1))
            (is (contains? affected fn-id-2)))
          ;; Find caches by fn-schema dependency
          (let [affected (cache/find-caches-by-fn-schema-dep cache fn-schema-id)]
            (is (= 2 (count affected))))
          ;; Find caches by arg-schema dependency
          (let [affected (cache/find-caches-by-arg-schema-dep cache arg-schema-id)]
            (is (= 2 (count affected)))))
        (finally
          (sp/close storage))))))


(deftest dependency-cleanup-on-overwrite-integration-test
  (testing "old dependencies are cleaned up when cache is overwritten"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :text
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              ;; Create fns for dependencies
              old-dep-fn (sp/create-entity storage :fn
                                           {:name "old-dep"
                                            :fn-schema-id fn-schema-id})
              old-dep-fn-id (:id old-dep-fn)
              new-dep-fn (sp/create-entity storage :fn
                                           {:name "new-dep"
                                            :fn-schema-id fn-schema-id})
              new-dep-fn-id (:id new-dep-fn)
              cache-fn (sp/create-entity storage :fn
                                         {:name "cache-fn"
                                          :fn-schema-id fn-schema-id})
              fn-id (:id cache-fn)
              graph {:fns {fn-id {:id fn-id
                                  :name "cache-fn"
                                  :fn-schema-id fn-schema-id}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :text}}
                     :arg-schemas {}
                     :resolved-args {}
                     :fn-result-values {}}
              old-deps {:fn-ids {old-dep-fn-id 1}
                        :fn-schema-ids {}
                        :arg-schema-ids {}}
              new-deps {:fn-ids {new-dep-fn-id 1}
                        :fn-schema-ids {}
                        :arg-schema-ids {}}]
          ;; Save with old dependency
          (cache/save-cache! cache fn-id graph old-deps)
          (is (contains? (cache/find-caches-by-fn-dep cache old-dep-fn-id) fn-id))
          (is (empty? (cache/find-caches-by-fn-dep cache new-dep-fn-id)))
          ;; Save with new dependency
          (cache/save-cache! cache fn-id graph new-deps)
          ;; Old dep should be cleaned up
          (is (empty? (cache/find-caches-by-fn-dep cache old-dep-fn-id)))
          ;; New dep should be active
          (is (contains? (cache/find-caches-by-fn-dep cache new-dep-fn-id) fn-id)))
        (finally
          (sp/close storage))))))


(deftest find-deps-for-nonexistent-returns-empty-integration-test
  (testing "find-caches-by-*-dep returns empty set for non-existent dep"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              non-existent-id (random-uuid)]
          (is (= #{} (cache/find-caches-by-fn-dep cache non-existent-id)))
          (is (= #{} (cache/find-caches-by-fn-schema-dep cache non-existent-id)))
          (is (= #{} (cache/find-caches-by-arg-schema-dep cache non-existent-id))))
        (finally
          (sp/close storage))))))
