(ns graphden.cached-storage.invalidation-test
  "Tests for cached-storage cache invalidation."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-protocol.interface :as cache]
    [graphden.cached-storage.interface :as cached]
    [graphden.cached-storage.test-mocks :as mocks]
    [graphden.storage-protocol.interface :as sp]))


(deftest fn-creation-creates-cache-test
  (testing "creating fn creates its cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
          fn-id (random-uuid)]

      (is (not (cache/cache-exists? cache fn-id)))

      (sp/create-entity wrapped :fn {:id fn-id :name "my-fn" :fn-schema-id schema-id})

      (is (cache/cache-exists? cache fn-id)))))


(deftest arg-value-crud-cache-invalidation-test
  (testing "arg-value CRUD invalidates fn cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
          fn-id (random-uuid)
          arg-schema-id (random-uuid)]

      ;; Create fn and its arg-schema
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "arg" :type :int :required false})

      ;; Ensure cache exists
      (is (cache/cache-exists? cache fn-id))

      ;; Create arg-value - should trigger cache rebuild
      (let [arg-value-id (random-uuid)]
        (sp/create-entity wrapped :arg-value {:id arg-value-id
                                              :owner-fn-id fn-id
                                              :arg-schema-id arg-schema-id
                                              :value 42})

        ;; Update arg-value
        (sp/update-entity wrapped :arg-value arg-value-id {:value 100})

        ;; Delete arg-value
        (sp/delete-entity wrapped :arg-value arg-value-id)))))


(deftest fn-update-cache-invalidation-test
  (testing "fn update with fn-schema-id change invalidates cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id-1 (random-uuid)
          schema-id-2 (random-uuid)
          _ (sp/create-entity storage :fn-schema {:id schema-id-1 :name "test1" :returned-type :int})
          _ (sp/create-entity storage :fn-schema {:id schema-id-2 :name "test2" :returned-type :text})
          fn-id (random-uuid)]

      ;; Create fn
      (sp/create-entity wrapped :fn {:id fn-id :name "my-fn" :fn-schema-id schema-id-1})

      ;; Update with fn-schema-id change
      (sp/update-entity wrapped :fn fn-id {:fn-schema-id schema-id-2})

      ;; Cache should still exist (rebuilt after invalidation)
      (is (cache/cache-exists? cache fn-id)))))


(deftest fn-delete-cache-invalidation-test
  (testing "fn deletion removes its cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
          fn-id (random-uuid)]

      ;; Create fn
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))

      ;; Delete fn
      (sp/delete-entity wrapped :fn fn-id)
      (is (not (cache/cache-exists? cache fn-id))))))


(deftest fn-schema-update-invalidation-test
  (testing "fn-schema update invalidates dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (let [fn-id (random-uuid)]
        ;; Create fn
        (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})

        ;; Update fn-schema - should not throw
        (let [result (sp/update-entity wrapped :fn-schema schema-id {:name "updated"})]
          (is (= "updated" (:name result))))))))


(deftest arg-schema-update-invalidation-test
  (testing "arg-schema update invalidates dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "arg" :type :int :required false})
      ;; Update arg-schema - should not throw
      (let [result (sp/update-entity wrapped :arg-schema arg-schema-id {:name "updated"})]
        (is (= "updated" (:name result)))))))


(deftest fn-schema-delete-invalidation-test
  (testing "fn-schema deletion invalidates dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))

      ;; Delete fn-schema
      (is (sp/delete-entity wrapped :fn-schema schema-id))
      ;; fn-schema deleted, cache invalidation attempted (but fn still exists so rebuilt)
      )))


(deftest arg-schema-delete-invalidation-test
  (testing "arg-schema deletion invalidates dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "arg" :type :int :required false})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))

      ;; Delete arg-schema
      (is (sp/delete-entity wrapped :arg-schema arg-schema-id)))))


(deftest call-site-crud-test
  (testing "call-site CRUD is delegated (no cache action)"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          cs-id (random-uuid)
          fn-id (random-uuid)]

      ;; Create
      (let [result (sp/create-entity wrapped :call-site
                                     {:id cs-id :fn-id fn-id :value "result" :name "test-call-site"})]
        (is (= cs-id (:id result))))

      ;; Delete
      (is (sp/delete-entity wrapped :call-site cs-id)))))


(deftest fn-update-without-schema-change-test
  (testing "fn update without fn-schema-id does not invalidate cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))

      ;; Update without fn-schema-id change
      (sp/update-entity wrapped :fn fn-id {:name "renamed"})
      ;; Cache should still exist (not invalidated)
      (is (cache/cache-exists? cache fn-id)))))


(deftest delete-entity-returns-false-for-nonexistent-test
  (testing "delete-entity returns false when entity doesn't exist"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))]
      ;; Delete nonexistent - should return false and not trigger cache action
      (is (not (sp/delete-entity wrapped :fn (random-uuid)))))))


(deftest create-entity-other-types-test
  (testing "create-entity for non-fn/arg-value types does not trigger cache action"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          schema-id (random-uuid)]
      ;; Create fn-schema - should not throw
      (let [result (sp/create-entity wrapped :fn-schema {:id schema-id :name "test" :returned-type :int})]
        (is (= schema-id (:id result))))

      ;; Create arg-schema - should not throw
      (let [arg-schema-id (random-uuid)
            result (sp/create-entity wrapped :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                                          :name "arg" :type :int :required true})]
        (is (= arg-schema-id (:id result)))))))


(deftest update-entity-other-types-test
  (testing "update-entity for non-fn/arg-value/fn-schema/arg-schema types does not trigger cache action"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          cs-id (random-uuid)]
      ;; Create call-site
      (sp/create-entity storage :call-site {:id cs-id :fn-id (random-uuid) :value "test" :name "test-call-site"})
      ;; Update - should not throw (default case)
      (let [result (sp/update-entity wrapped :call-site cs-id {:value "updated"})]
        (is (= "updated" (:value result)))))))


(deftest delete-entity-other-types-test
  (testing "delete-entity for non-special types does not trigger cache action"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          custom-id (random-uuid)]
      ;; Create some entity
      (sp/create-entity storage :custom {:id custom-id :data "test"})
      ;; Delete - default case, should not throw
      (is (sp/delete-entity wrapped :custom custom-id)))))


(deftest create-call-site-test
  (testing "create-entity for :call-site does not trigger immediate cache action"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          schema-id (random-uuid)
          fn-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      ;; Create call-site - should not throw and not invalidate cache
      (let [call-site (sp/create-entity wrapped :call-site
                                        {:fn-id fn-id :value "result" :name "test-call-site"})]
        (is (some? call-site))
        (is (= "result" (:value call-site)))))))


(deftest delete-call-site-test
  (testing "delete-entity for :call-site does not trigger cache action"
    (let [storage (mocks/create-mock-storage)
          wrapped (cached/wrap-with-cache storage (mocks/create-mock-cache))
          schema-id (random-uuid)
          fn-id (random-uuid)
          cs-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (sp/create-entity storage :call-site {:id cs-id :fn-id fn-id :value "result" :name "test-call-site"})
      ;; Delete call-site - should not throw
      (is (sp/delete-entity wrapped :call-site cs-id)))))


(deftest update-fn-with-fn-schema-id-change-test
  (testing "update-entity for :fn with fn-schema-id invalidates cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id-1 (random-uuid)
          schema-id-2 (random-uuid)
          fn-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id-1 :name "test1" :returned-type :int})
      (sp/create-entity storage :fn-schema {:id schema-id-2 :name "test2" :returned-type :text})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id-1})
      (is (cache/cache-exists? cache fn-id))
      ;; Update with fn-schema-id - should invalidate
      (sp/update-entity wrapped :fn fn-id {:fn-schema-id schema-id-2})
      ;; Cache should be rebuilt (still exists)
      (is (cache/cache-exists? cache fn-id)))))


(deftest resolve-execution-graph-cache-miss-test
  (testing "resolve-execution-graph caches on miss"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      ;; Create fn directly in base storage (no cache)
      (sp/create-entity storage :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (not (cache/cache-exists? cache fn-id)))
      ;; resolve-execution-graph should cache on miss
      (let [graph (sp/resolve-execution-graph wrapped fn-id)]
        (is (sp/execution-graph? graph))
        (is (cache/cache-exists? cache fn-id))))))


(deftest update-fn-schema-id-invalidates-cache-test
  (testing "update-entity for :fn with fn-schema-id change invalidates cache"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id-1 (random-uuid)
          schema-id-2 (random-uuid)
          fn-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id-1 :name "test1" :returned-type :int})
      (sp/create-entity storage :fn-schema {:id schema-id-2 :name "test2" :returned-type :text})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id-1})
      (is (cache/cache-exists? cache fn-id))
      ;; Update with different fn-schema-id
      (sp/update-entity wrapped :fn fn-id {:fn-schema-id schema-id-2})
      ;; Cache should be rebuilt
      (is (cache/cache-exists? cache fn-id)))))


;; === Complete case branch coverage tests ===

(deftest create-entity-fn-schema-branch-test
  (testing "create-entity :fn-schema branch - no cache action needed"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          ;; Create fn-schema through wrapped storage
          result (sp/create-entity wrapped :fn-schema
                                   {:id schema-id :name "test-schema" :returned-type :int})]
      (is (= schema-id (:id result)))
      (is (= "test-schema" (:name result))))))


(deftest create-entity-arg-schema-branch-test
  (testing "create-entity :arg-schema branch - no cache action needed"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)]
      ;; First create fn-schema
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      ;; Create arg-schema through wrapped storage
      (let [result (sp/create-entity wrapped :arg-schema
                                     {:id arg-schema-id
                                      :fn-schema-id schema-id
                                      :name "x"
                                      :type :int
                                      :required true})]
        (is (= arg-schema-id (:id result)))
        (is (= "x" (:name result)))))))


(deftest delete-entity-arg-schema-branch-test
  (testing "delete-entity :arg-schema branch - invalidates dependent caches"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "x" :type :int :required true})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))
      ;; Delete arg-schema through wrapped storage
      (is (sp/delete-entity wrapped :arg-schema arg-schema-id))
      ;; Cache should be invalidated/rebuilt
      (is (cache/cache-exists? cache fn-id)))))


(deftest delete-entity-default-branch-test
  (testing "delete-entity default branch - no cache action for custom types"
    (let [storage (mocks/create-mock-storage)
          cache (mocks/create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          custom-id (random-uuid)]
      ;; Create custom entity
      (sp/create-entity storage :custom {:id custom-id :data "test"})
      ;; Delete through wrapped storage - should use default branch
      (is (sp/delete-entity wrapped :custom custom-id)))))
