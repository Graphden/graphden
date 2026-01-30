(ns graphden.cached-storage.metrics-test
  "Tests for cached-storage metrics tracking."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cached-storage.interface :as cached]
    [graphden.cached-storage.test-mocks :as mocks]
    [graphden.storage-protocol.interface :as sp]))


;; === Cache Metrics Tests ===

(deftest create-metrics-test
  (testing "creates metrics atom with initial values"
    (let [metrics (cached/create-metrics)]
      (is (instance? clojure.lang.Atom metrics))
      (let [m (cached/get-metrics metrics)]
        (is (zero? (:hits m)))
        (is (zero? (:misses m)))
        (is (zero? (:invalidations m)))
        (is (zero? (:total-requests m)))
        (is (= 0.0 (:hit-rate m)))))))


(deftest get-metrics-test
  (testing "returns metrics snapshot with hit-rate calculation"
    (let [metrics (cached/create-metrics)]
      ;; Manually update to test hit-rate calculation
      (swap! metrics assoc :hits 8 :misses 2)
      (let [m (cached/get-metrics metrics)]
        (is (= 8 (:hits m)))
        (is (= 2 (:misses m)))
        (is (= 10 (:total-requests m)))
        (is (= 0.8 (:hit-rate m))))))

  (testing "hit-rate is 0.0 when no requests"
    (let [metrics (cached/create-metrics)
          m (cached/get-metrics metrics)]
      (is (= 0.0 (:hit-rate m))))))


(deftest reset-metrics-test
  (testing "resets all counters and returns previous values"
    (let [metrics (cached/create-metrics)]
      (swap! metrics assoc :hits 10 :misses 5 :invalidations 3)
      (let [prev (cached/reset-metrics! metrics)]
        (is (= 10 (:hits prev)))
        (is (= 5 (:misses prev)))
        (is (= 3 (:invalidations prev)))
        ;; Now should be reset
        (let [m (cached/get-metrics metrics)]
          (is (zero? (:hits m)))
          (is (zero? (:misses m)))
          (is (zero? (:invalidations m))))))))


(deftest wrap-with-cache-and-metrics-test
  (testing "creates storage with metrics tracking"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache-and-metrics storage cache)]
      (is (some? wrapped))
      (is (instance? graphden.cached_storage.interface.CachedStorageWithMetrics wrapped))))

  (testing "accepts custom metrics atom"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          metrics (cached/create-metrics)
          wrapped (cached/wrap-with-cache-and-metrics storage cache metrics)]
      (is (some? wrapped))
      ;; Verify same metrics atom is used
      (is (= metrics (:metrics wrapped))))))


(deftest get-storage-metrics-test
  (testing "returns nil for non-metrics storage"
    (let [storage (mocks/create-mock-storage)]
      (is (nil? (cached/get-storage-metrics storage)))))

  (testing "returns nil for regular cached storage"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))]
      (is (nil? (cached/get-storage-metrics wrapped)))))

  (testing "returns metrics for metrics-enabled storage"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache-and-metrics storage (mocks/create-mock-cache))]
      (is (some? (cached/get-storage-metrics wrapped)))
      (is (map? (cached/get-storage-metrics wrapped))))))


(deftest metrics-tracking-resolve-graph-test
  (testing "tracks cache hits and misses for resolve-execution-graph"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          metrics (cached/create-metrics)
          wrapped (cached/wrap-with-cache-and-metrics storage cache metrics)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :fn {:id fn-id :name "fn" :fn-schema-id schema-id})

      ;; First resolve - cache miss
      (sp/resolve-execution-graph wrapped fn-id)
      (let [m (cached/get-metrics metrics)]
        (is (= 1 (:misses m)))
        (is (zero? (:hits m))))

      ;; Second resolve - cache hit
      (sp/resolve-execution-graph wrapped fn-id)
      (let [m (cached/get-metrics metrics)]
        (is (= 1 (:misses m)))
        (is (= 1 (:hits m)))
        (is (= 0.5 (:hit-rate m)))))))


(deftest metrics-tracking-invalidations-test
  (testing "tracks invalidations for fn creation"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          metrics (cached/create-metrics)
          wrapped (cached/wrap-with-cache-and-metrics storage cache metrics)
          schema-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})

      ;; Create fn - triggers invalidation (rebuild)
      (sp/create-entity wrapped :fn {:name "fn1" :fn-schema-id schema-id})
      (let [m (cached/get-metrics metrics)]
        (is (= 1 (:invalidations m))))

      ;; Create another fn
      (sp/create-entity wrapped :fn {:name "fn2" :fn-schema-id schema-id})
      (let [m (cached/get-metrics metrics)]
        (is (= 2 (:invalidations m)))))))


(deftest metrics-storage-crud-delegation-test
  (testing "CachedStorageWithMetrics delegates all CRUD operations"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache-and-metrics storage cache)
          schema-id (random-uuid)]

      ;; Create
      (let [result (sp/create-entity wrapped :fn-schema {:id schema-id :name "test" :returned-type :int})]
        (is (= schema-id (:id result))))

      ;; Read
      (is (= schema-id (:id (sp/read-entity wrapped :fn-schema schema-id))))

      ;; Update
      (let [result (sp/update-entity wrapped :fn-schema schema-id {:name "updated"})]
        (is (= "updated" (:name result))))

      ;; Query
      (is (= 1 (count (sp/query-entities wrapped :fn-schema nil))))

      ;; Delete
      (is (sp/delete-entity wrapped :fn-schema schema-id)))))


(deftest metrics-storage-batch-crud-test
  (testing "CachedStorageWithMetrics handles batch operations with metrics"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          metrics (cached/create-metrics)
          wrapped (cached/wrap-with-cache-and-metrics storage cache metrics)
          schema-id (random-uuid)
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})

      ;; Batch create fns
      (let [results (sp/create-entities wrapped :fn
                                        [{:id fn-id-1 :name "fn1" :fn-schema-id schema-id}
                                         {:id fn-id-2 :name "fn2" :fn-schema-id schema-id}])]
        (is (= 2 (count results))))

      ;; Check invalidations tracked
      (let [m (cached/get-metrics metrics)]
        (is (= 2 (:invalidations m))))

      ;; Read entities
      (let [results (sp/read-entities wrapped :fn [fn-id-1 fn-id-2])]
        (is (= 2 (count results))))

      ;; Delete entities
      (is (= 2 (sp/delete-entities wrapped :fn [fn-id-1 fn-id-2]))))))


(deftest metrics-storage-introspection-test
  (testing "CachedStorageWithMetrics delegates introspection methods"
    (let [wrapped (cached/wrap-with-cache-and-metrics (mocks/create-mock-storage) (mocks/create-mock-cache))]
      (is (set? (sp/current-entities wrapped)))
      (is (nil? (sp/current-fields wrapped :test)))
      (is (set? (sp/current-enums wrapped)))
      (is (nil? (sp/current-enum-values wrapped :test)))
      (is (nil? (sp/schema-metadata wrapped))))))


(deftest metrics-storage-constraints-test
  (testing "CachedStorageWithMetrics delegates constraint methods"
    (let [wrapped (cached/wrap-with-cache-and-metrics (mocks/create-mock-storage) (mocks/create-mock-cache))
          fn-id (random-uuid)]
      (is (nil? (sp/validate-arg-schema-belongs-to-fn! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-dependency-cycle! wrapped fn-id fn-id))))))


(deftest metrics-storage-lifecycle-test
  (testing "CachedStorageWithMetrics delegates lifecycle methods"
    (let [wrapped (cached/wrap-with-cache-and-metrics (mocks/create-mock-storage) (mocks/create-mock-cache))]
      (is (map? (sp/initialize wrapped {})))
      (is (nil? (sp/close wrapped))))))
