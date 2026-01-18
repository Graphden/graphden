(ns graphden.cached-storage.wrapper-test
  "Tests for cached-storage wrapper basic functionality."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-protocol.interface :as cache]
    [graphden.cached-storage.interface :as cached]
    [graphden.cached-storage.test-mocks :as mocks]
    [graphden.storage-protocol.interface :as sp]))


(deftest wrap-with-cache-test
  (testing "wraps storage and cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)]
      (is (some? wrapped))
      (is (cached/cached-storage? wrapped))
      (is (= storage (cached/unwrap wrapped)))
      (is (= cache (cached/get-cache wrapped))))))


(deftest cached-storage?-test
  (testing "returns false for non-wrapped storage"
    (is (not (cached/cached-storage? (mocks/create-mock-storage))))
    (is (not (cached/cached-storage? nil)))
    (is (not (cached/cached-storage? {})))))


(deftest unwrap-test
  (testing "returns base storage from wrapped"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))]
      (is (= storage (cached/unwrap wrapped)))))

  (testing "returns storage unchanged if not wrapped"
    (let [storage (mocks/create-mock-storage)]
      (is (= storage (cached/unwrap storage))))))


(deftest resolve-execution-graph-caching-test
  (testing "caches graph on first access"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          ;; Create test data
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
          fn-id (random-uuid)
          _ (sp/create-entity storage :fn {:id fn-id :name "my-fn" :fn-schema-id schema-id})]

      ;; First call - should cache
      (is (not (cache/cache-exists? cache fn-id)))
      (let [graph (sp/resolve-execution-graph wrapped fn-id)]
        (is (some? graph))
        (is (cache/cache-exists? cache fn-id)))

      ;; Second call - should return from cache
      (let [cached-graph (sp/resolve-execution-graph wrapped fn-id)]
        (is (some? cached-graph))))))


(deftest crud-delegation-test
  (testing "CRUD operations are delegated to base storage"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          schema-id (random-uuid)]

      ;; Create
      (let [created (sp/create-entity wrapped :fn-schema
                                      {:id schema-id :name "test" :returned-type :int})]
        (is (= schema-id (:id created)))
        (is (= "test" (:name created))))

      ;; Read
      (let [read-result (sp/read-entity wrapped :fn-schema schema-id)]
        (is (= schema-id (:id read-result))))

      ;; Update
      (let [updated (sp/update-entity wrapped :fn-schema schema-id {:name "updated"})]
        (is (= "updated" (:name updated))))

      ;; Query
      (let [results (sp/query-entities wrapped :fn-schema nil)]
        (is (= 1 (count results))))

      ;; Delete
      (is (sp/delete-entity wrapped :fn-schema schema-id))
      (is (nil? (sp/read-entity wrapped :fn-schema schema-id))))))


(deftest constraint-delegation-test
  (testing "constraint methods are delegated to base storage"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          fn-id (random-uuid)]

      ;; These should not throw (mock returns nil)
      (is (nil? (sp/validate-parent-same-schema! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-arg-override! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-arg-schema-belongs-to-fn! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-inheritance-cycle! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-dependency-cycle! wrapped fn-id fn-id))))))


(deftest constraint-helpers-delegation-test
  (testing "ConstraintHelpers methods are delegated to base storage"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          schema-id (random-uuid)
          fn-id (random-uuid)
          arg-schema-id (random-uuid)]

      ;; Setup test data in base storage
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "arg" :type :int :required true})

      ;; Test helper methods
      (is (= schema-id (sp/get-fn-schema-id-for-fn wrapped fn-id)))
      (is (= schema-id (sp/get-fn-schema-id-for-arg-schema wrapped arg-schema-id)))
      (is (nil? (sp/get-parent-fn-id wrapped fn-id)))
      (is (set? (sp/collect-parent-chain wrapped fn-id)))
      (is (set? (sp/collect-arg-schema-ids-in-chain wrapped fn-id)))
      (is (set? (sp/collect-dependency-chain wrapped fn-id))))))


(deftest introspection-delegation-test
  (testing "StorageIntrospection methods are delegated"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))]

      (is (set? (sp/current-entities wrapped)))
      (is (nil? (sp/current-fields wrapped :nonexistent)))
      (is (set? (sp/current-enums wrapped)))
      (is (nil? (sp/current-enum-values wrapped :nonexistent)))
      (is (nil? (sp/schema-metadata wrapped))))))


(deftest get-cache-returns-nil-for-unwrapped-test
  (testing "get-cache returns nil for non-wrapped storage"
    (let [storage (mocks/create-mock-storage)]
      (is (nil? (cached/get-cache storage))))))


(deftest storage-initialize-delegation-test
  (testing "initialize is delegated to base storage"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          result (sp/initialize wrapped {:some :schema})]
      (is (map? result))
      (is (contains? result :entities)))))


(deftest storage-close-delegation-test
  (testing "close is delegated to base storage"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))]
      ;; Should not throw
      (is (nil? (sp/close wrapped))))))
