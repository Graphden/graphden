(ns graphden.cached-storage.batch-test
  "Tests for cached-storage batch operations."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-protocol.interface :as cache]
    [graphden.cached-storage.interface :as cached]
    [graphden.cached-storage.test-mocks :as mocks]
    [graphden.storage-protocol.interface :as sp]))


(deftest batch-crud-delegation-test
  (testing "batch CRUD operations are delegated"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)]

      ;; create-entities for :fn
      (let [results (sp/create-entities wrapped :fn
                                        [{:id fn-id-1 :name "fn1" :fn-schema-id schema-id}
                                         {:id fn-id-2 :name "fn2" :fn-schema-id schema-id}])]
        (is (= 2 (count results)))
        (is (= fn-id-1 (:id (first results)))))

      ;; read-entities
      (let [results (sp/read-entities wrapped :fn [fn-id-1 fn-id-2])]
        (is (= 2 (count results)))
        (is (contains? results fn-id-1))
        (is (contains? results fn-id-2)))

      ;; delete-entities
      (is (= 2 (sp/delete-entities wrapped :fn [fn-id-1 fn-id-2]))))))


(deftest batch-arg-value-operations-test
  (testing "batch arg-value operations invalidate caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)
          arg-schema-id (random-uuid)]

      ;; Create fns
      (sp/create-entity wrapped :fn {:id fn-id-1 :name "fn1" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id fn-id-2 :name "fn2" :fn-schema-id schema-id})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "arg" :type :int :required false})

      ;; Batch create arg-values
      (let [av-id-1 (random-uuid)
            av-id-2 (random-uuid)
            results (sp/create-entities wrapped :arg-value
                                        [{:id av-id-1 :owner-fn-id fn-id-1 :arg-schema-id arg-schema-id :value 1}
                                         {:id av-id-2 :owner-fn-id fn-id-2 :arg-schema-id arg-schema-id :value 2}])]
        (is (= 2 (count results)))

        ;; Batch delete arg-values
        (is (= 2 (sp/delete-entities wrapped :arg-value [av-id-1 av-id-2])))))))


(deftest batch-fn-delete-test
  (testing "batch fn deletion removes caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id-1 :name "fn1" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id fn-id-2 :name "fn2" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id-1))
      (is (cache/cache-exists? cache fn-id-2))

      ;; Batch delete
      (is (= 2 (sp/delete-entities wrapped :fn [fn-id-1 fn-id-2])))
      (is (not (cache/cache-exists? cache fn-id-1)))
      (is (not (cache/cache-exists? cache fn-id-2))))))


(deftest batch-create-non-fn-entities-test
  (testing "batch create for non-fn/arg-value entities does not trigger cache action"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          schema-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})

      ;; Batch create arg-schemas - should not throw (default case)
      (let [results (sp/create-entities wrapped :arg-schema
                                        [{:fn-schema-id schema-id :name "arg1" :type :int :required true}
                                         {:fn-schema-id schema-id :name "arg2" :type :text :required false}])]
        (is (= 2 (count results)))))))


(deftest batch-delete-non-fn-entities-test
  (testing "batch delete for non-fn/arg-value entities does not trigger cache action"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          id-1 (random-uuid)
          id-2 (random-uuid)]
      ;; Create custom entities
      (sp/create-entity storage :custom {:id id-1 :data "a"})
      (sp/create-entity storage :custom {:id id-2 :data "b"})

      ;; Batch delete - default case
      (is (= 2 (sp/delete-entities wrapped :custom [id-1 id-2]))))))


(deftest batch-delete-with-zero-result-test
  (testing "batch delete with no existing entities does not trigger cache action"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))]
      ;; Delete nonexistent fns
      (is (zero? (sp/delete-entities wrapped :fn [(random-uuid) (random-uuid)]))))))


(deftest batch-create-arg-values-test
  (testing "batch create-entities for :arg-value invalidates owner fns"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)
          fn-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "x" :type :int :required true})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))
      ;; Batch create arg-values
      (let [results (sp/create-entities wrapped :arg-value
                                        [{:owner-fn-id fn-id :arg-schema-id arg-schema-id :value 1}
                                         {:owner-fn-id fn-id :arg-schema-id arg-schema-id :value 2}])]
        (is (= 2 (count results))))
      ;; Cache should be rebuilt
      (is (cache/cache-exists? cache fn-id)))))


(deftest batch-delete-arg-values-test
  (testing "batch delete-entities for :arg-value invalidates owner fns"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)
          fn-id (random-uuid)
          av-id-1 (random-uuid)
          av-id-2 (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "x" :type :int :required true})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (sp/create-entity storage :arg-value {:id av-id-1 :owner-fn-id fn-id :arg-schema-id arg-schema-id :value 1})
      (sp/create-entity storage :arg-value {:id av-id-2 :owner-fn-id fn-id :arg-schema-id arg-schema-id :value 2})
      (is (cache/cache-exists? cache fn-id))
      ;; Batch delete arg-values
      (is (= 2 (sp/delete-entities wrapped :arg-value [av-id-1 av-id-2])))
      ;; Cache should be rebuilt
      (is (cache/cache-exists? cache fn-id)))))


(deftest create-entities-default-branch-test
  (testing "create-entities default branch - no cache action for other entity types"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)]
      ;; Create fn-schema first
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      ;; Batch create arg-schemas (not :fn or :arg-value, so default branch)
      (let [results (sp/create-entities wrapped :arg-schema
                                        [{:fn-schema-id schema-id :name "a" :type :int :required true}
                                         {:fn-schema-id schema-id :name "b" :type :text :required false}])]
        (is (= 2 (count results)))
        (is (= #{"a" "b"} (set (map :name results))))))))


(deftest delete-entities-default-branch-test
  (testing "delete-entities default branch - no cache action for other entity types"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id-1 (random-uuid)
          arg-schema-id-2 (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id-1 :fn-schema-id schema-id
                                             :name "a" :type :int :required true})
      (sp/create-entity storage :arg-schema {:id arg-schema-id-2 :fn-schema-id schema-id
                                             :name "b" :type :text :required false})
      ;; Batch delete arg-schemas (not :fn or :arg-value, so default branch)
      (is (= 2 (sp/delete-entities wrapped :arg-schema [arg-schema-id-1 arg-schema-id-2]))))))
