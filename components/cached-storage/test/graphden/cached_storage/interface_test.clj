(ns graphden.cached-storage.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-protocol.interface :as cache]
    [graphden.cached-storage.interface :as cached]
    [graphden.storage-protocol.interface :as sp]))


;; === Mock implementations for testing ===

(defrecord MockCache
  [state]

  cache/CacheStorage

  (get-cached-graph
    [_ fn-id]
    (get-in @state [:graphs fn-id]))


  (cache-exists?
    [_ fn-id]
    (contains? (:graphs @state) fn-id))


  (save-cache!
    [_ fn-id graph dependencies]
    (swap! state update :graphs assoc fn-id graph)
    (swap! state update :deps assoc fn-id dependencies))


  (delete-cache!
    [_ fn-id]
    (let [existed (contains? (:graphs @state) fn-id)]
      (swap! state update :graphs dissoc fn-id)
      (swap! state update :deps dissoc fn-id)
      existed))


  (find-caches-by-fn-dep
    [_ _dep-fn-id]
    ;; For testing, return empty set - real impl would query deps
    #{})


  (find-caches-by-fn-schema-dep
    [_ _dep-fn-schema-id]
    #{})


  (find-caches-by-arg-schema-dep
    [_ _dep-arg-schema-id]
    #{}))


(defn create-mock-cache
  []
  (->MockCache (atom {:graphs {} :deps {}})))


(defrecord MockStorage
  [state]

  sp/Storage

  (initialize [_ _schema] {:entities {:created [] :renamed {}}})


  (close [_] nil)


  sp/StorageIntrospection

  (current-entities [_] #{})


  (current-fields [_ _] nil)


  (current-enums [_] #{})


  (current-enum-values [_ _] nil)


  (schema-metadata [_] nil)


  sp/StorageCRUD

  (create-entity
    [_ entity-name data]
    (let [id (or (:id data) (random-uuid))
          record (assoc data :id id)]
      (swap! state assoc-in [entity-name id] record)
      record))


  (read-entity
    [_ entity-name id]
    (get-in @state [entity-name id]))


  (update-entity
    [_ entity-name id data]
    (let [existing (get-in @state [entity-name id])]
      (when-not existing
        (throw (ex-info "Not found" {:type :not-found})))
      (let [updated (merge existing data)]
        (swap! state assoc-in [entity-name id] updated)
        updated)))


  (delete-entity
    [_ entity-name id]
    (let [existed (contains? (get @state entity-name) id)]
      (swap! state update entity-name dissoc id)
      existed))


  (query-entities
    [_ entity-name _where]
    (vals (get @state entity-name {})))


  sp/StorageBatchCRUD

  (create-entities
    [this entity-name data-seq]
    (mapv #(sp/create-entity this entity-name %) data-seq))


  (read-entities
    [_ entity-name ids]
    (into {}
          (for [id ids
                :let [record (get-in @state [entity-name id])]
                :when record]
            [id record])))


  (delete-entities
    [_ entity-name ids]
    (let [existing-count (count (filter #(contains? (get @state entity-name) %) ids))]
      (doseq [id ids]
        (swap! state update entity-name dissoc id))
      existing-count))


  sp/GraphConstraints

  (validate-parent-same-schema! [_ _fn-id _parent-fn-id] nil)


  (validate-no-arg-override! [_ _fn-id _arg-schema-id] nil)


  (validate-arg-schema-belongs-to-fn! [_ _fn-id _arg-schema-id] nil)


  (validate-no-inheritance-cycle! [_ _fn-id _parent-fn-id] nil)


  (validate-no-dependency-cycle! [_ _owner-fn-id _value-fn-id] nil)


  sp/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_ fn-id]
    (:fn-schema-id (get-in @state [:fn fn-id])))


  (get-fn-schema-id-for-arg-schema
    [_ arg-schema-id]
    (:fn-schema-id (get-in @state [:arg-schema arg-schema-id])))


  (get-parent-fn-id
    [_ fn-id]
    (:parent-fn-id (get-in @state [:fn fn-id])))


  (collect-parent-chain [_ _fn-id] #{})


  (collect-arg-schema-ids-in-chain [_ _fn-id] #{})


  (collect-dependency-chain [_ fn-id] #{fn-id})


  sp/ExecutionGraph

  (resolve-execution-graph
    [_ fn-id]
    (let [fn-record (get-in @state [:fn fn-id])]
      (when-not fn-record
        (throw (ex-info "Not found" {:type :not-found :fn-id fn-id})))
      (sp/->execution-graph
        {:fns {fn-id fn-record}
         :fn-schemas {(:fn-schema-id fn-record)
                      (get-in @state [:fn-schema (:fn-schema-id fn-record)])}
         :arg-schemas {}
         :resolved-args {fn-id {}}}))))


(defn create-mock-storage
  []
  (->MockStorage (atom {})))


;; === Tests ===

(deftest wrap-with-cache-test
  (testing "wraps storage and cache"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)]
      (is (some? wrapped))
      (is (cached/cached-storage? wrapped))
      (is (= storage (cached/unwrap wrapped)))
      (is (= cache (cached/get-cache wrapped))))))


(deftest cached-storage?-test
  (testing "returns false for non-wrapped storage"
    (is (not (cached/cached-storage? (create-mock-storage))))
    (is (not (cached/cached-storage? nil)))
    (is (not (cached/cached-storage? {})))))


(deftest unwrap-test
  (testing "returns base storage from wrapped"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))]
      (is (= storage (cached/unwrap wrapped)))))

  (testing "returns storage unchanged if not wrapped"
    (let [storage (create-mock-storage)]
      (is (= storage (cached/unwrap storage))))))


(deftest resolve-execution-graph-caching-test
  (testing "caches graph on first access"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
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


(deftest fn-creation-creates-cache-test
  (testing "creating fn creates its cache"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
          fn-id (random-uuid)]

      (is (not (cache/cache-exists? cache fn-id)))

      (sp/create-entity wrapped :fn {:id fn-id :name "my-fn" :fn-schema-id schema-id})

      (is (cache/cache-exists? cache fn-id)))))


(deftest constraint-delegation-test
  (testing "constraint methods are delegated to base storage"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
          fn-id (random-uuid)]

      ;; These should not throw (mock returns nil)
      (is (nil? (sp/validate-parent-same-schema! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-arg-override! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-arg-schema-belongs-to-fn! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-inheritance-cycle! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-dependency-cycle! wrapped fn-id fn-id))))))


(deftest constraint-helpers-delegation-test
  (testing "ConstraintHelpers methods are delegated to base storage"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
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
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))]

      (is (set? (sp/current-entities wrapped)))
      (is (nil? (sp/current-fields wrapped :nonexistent)))
      (is (set? (sp/current-enums wrapped)))
      (is (nil? (sp/current-enum-values wrapped :nonexistent)))
      (is (nil? (sp/schema-metadata wrapped))))))


(deftest batch-crud-delegation-test
  (testing "batch CRUD operations are delegated"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
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


(deftest arg-value-crud-cache-invalidation-test
  (testing "arg-value CRUD invalidates fn cache"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
  (testing "fn update with parent-fn-id change invalidates cache"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
          parent-id (random-uuid)
          fn-id (random-uuid)]

      ;; Create fns
      (sp/create-entity wrapped :fn {:id parent-id :name "parent" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id fn-id :name "child" :fn-schema-id schema-id})

      ;; Update with parent-fn-id change
      (sp/update-entity wrapped :fn fn-id {:parent-fn-id parent-id})

      ;; Cache should still exist (rebuilt after invalidation)
      (is (cache/cache-exists? cache fn-id)))))


(deftest fn-delete-cache-invalidation-test
  (testing "fn deletion removes its cache"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          arg-schema-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "arg" :type :int :required false})
      ;; Update arg-schema - should not throw
      (let [result (sp/update-entity wrapped :arg-schema arg-schema-id {:name "updated"})]
        (is (= "updated" (:name result)))))))


(deftest fn-result-value-crud-test
  (testing "fn-result-value CRUD is delegated (no cache action)"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
          frv-id (random-uuid)
          fn-id (random-uuid)]

      ;; Create
      (let [result (sp/create-entity wrapped :fn-result-value
                                     {:id frv-id :fn-id fn-id :value "result" :name "test-frv"})]
        (is (= frv-id (:id result))))

      ;; Delete
      (is (sp/delete-entity wrapped :fn-result-value frv-id)))))


(deftest batch-arg-value-operations-test
  (testing "batch arg-value operations invalidate caches"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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


(deftest get-cache-returns-nil-for-unwrapped-test
  (testing "get-cache returns nil for non-wrapped storage"
    (let [storage (create-mock-storage)]
      (is (nil? (cached/get-cache storage))))))


(deftest storage-initialize-delegation-test
  (testing "initialize is delegated to base storage"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
          result (sp/initialize wrapped {:some :schema})]
      (is (map? result))
      (is (contains? result :entities)))))


(deftest storage-close-delegation-test
  (testing "close is delegated to base storage"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))]
      ;; Should not throw
      (is (nil? (sp/close wrapped))))))


(deftest fn-schema-delete-invalidation-test
  (testing "fn-schema deletion invalidates dependent caches"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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


(deftest batch-fn-delete-test
  (testing "batch fn deletion removes caches"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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


(deftest create-entity-other-types-test
  (testing "create-entity for non-fn/arg-value types does not trigger cache action"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
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
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
          frv-id (random-uuid)]
      ;; Create fn-result-value
      (sp/create-entity storage :fn-result-value {:id frv-id :fn-id (random-uuid) :value "test" :name "test-frv"})
      ;; Update - should not throw (default case)
      (let [result (sp/update-entity wrapped :fn-result-value frv-id {:value "updated"})]
        (is (= "updated" (:value result)))))))


(deftest delete-entity-other-types-test
  (testing "delete-entity for non-special types does not trigger cache action"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
          custom-id (random-uuid)]
      ;; Create some entity
      (sp/create-entity storage :custom {:id custom-id :data "test"})
      ;; Delete - default case, should not throw
      (is (sp/delete-entity wrapped :custom custom-id)))))


(deftest batch-create-non-fn-entities-test
  (testing "batch create for non-fn/arg-value entities does not trigger cache action"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
          schema-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})

      ;; Batch create arg-schemas - should not throw (default case)
      (let [results (sp/create-entities wrapped :arg-schema
                                        [{:fn-schema-id schema-id :name "arg1" :type :int :required true}
                                         {:fn-schema-id schema-id :name "arg2" :type :text :required false}])]
        (is (= 2 (count results)))))))


(deftest batch-delete-non-fn-entities-test
  (testing "batch delete for non-fn/arg-value entities does not trigger cache action"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
          id-1 (random-uuid)
          id-2 (random-uuid)]
      ;; Create custom entities
      (sp/create-entity storage :custom {:id id-1 :data "a"})
      (sp/create-entity storage :custom {:id id-2 :data "b"})

      ;; Batch delete - default case
      (is (= 2 (sp/delete-entities wrapped :custom [id-1 id-2]))))))


(deftest fn-update-without-parent-change-test
  (testing "fn update without parent-fn-id does not invalidate cache"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))

      ;; Update without parent-fn-id change
      (sp/update-entity wrapped :fn fn-id {:name "renamed"})
      ;; Cache should still exist (not invalidated)
      (is (cache/cache-exists? cache fn-id)))))


(deftest delete-entity-returns-false-for-nonexistent-test
  (testing "delete-entity returns false when entity doesn't exist"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))]
      ;; Delete nonexistent - should return false and not trigger cache action
      (is (not (sp/delete-entity wrapped :fn (random-uuid)))))))


(deftest batch-delete-with-zero-result-test
  (testing "batch delete with no existing entities does not trigger cache action"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))]
      ;; Delete nonexistent fns
      (is (zero? (sp/delete-entities wrapped :fn [(random-uuid) (random-uuid)]))))))


(deftest create-fn-result-value-test
  (testing "create-entity for :fn-result-value does not trigger immediate cache action"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
          schema-id (random-uuid)
          fn-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      ;; Create fn-result-value - should not throw and not invalidate cache
      (let [frv (sp/create-entity wrapped :fn-result-value
                                  {:fn-id fn-id :value "result" :name "test-frv"})]
        (is (some? frv))
        (is (= "result" (:value frv)))))))


(deftest delete-fn-result-value-test
  (testing "delete-entity for :fn-result-value does not trigger cache action"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))
          schema-id (random-uuid)
          fn-id (random-uuid)
          frv-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (sp/create-entity storage :fn-result-value {:id frv-id :fn-id fn-id :value "result" :name "test-frv"})
      ;; Delete fn-result-value - should not throw
      (is (sp/delete-entity wrapped :fn-result-value frv-id)))))


(deftest update-fn-with-parent-fn-id-change-test
  (testing "update-entity for :fn with parent-fn-id invalidates cache"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          parent-fn-id (random-uuid)
          child-fn-id (random-uuid)]
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id parent-fn-id :name "parent" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id child-fn-id :name "child" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache child-fn-id))
      ;; Update with parent-fn-id - should invalidate
      (sp/update-entity wrapped :fn child-fn-id {:parent-fn-id parent-fn-id})
      ;; Cache should be rebuilt (still exists)
      (is (cache/cache-exists? cache child-fn-id)))))


(deftest batch-create-arg-values-test
  (testing "batch create-entities for :arg-value invalidates owner fns"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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


(deftest resolve-execution-graph-cache-miss-test
  (testing "resolve-execution-graph caches on miss"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          ;; Create fn-schema through wrapped storage
          result (sp/create-entity wrapped :fn-schema
                                   {:id schema-id :name "test-schema" :returned-type :int})]
      (is (= schema-id (:id result)))
      (is (= "test-schema" (:name result))))))


(deftest create-entity-arg-schema-branch-test
  (testing "create-entity :arg-schema branch - no cache action needed"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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


(deftest create-entities-default-branch-test
  (testing "create-entities default branch - no cache action for other entity types"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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


(deftest delete-entity-arg-schema-branch-test
  (testing "delete-entity :arg-schema branch - invalidates dependent caches"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          wrapped (cached/wrap-with-cache storage cache)
          custom-id (random-uuid)]
      ;; Create custom entity
      (sp/create-entity storage :custom {:id custom-id :data "test"})
      ;; Delete through wrapped storage - should use default branch
      (is (sp/delete-entity wrapped :custom custom-id)))))


(deftest delete-entities-default-branch-test
  (testing "delete-entities default branch - no cache action for other entity types"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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


;; === Enhanced mock with dependency tracking for better coverage ===

(defrecord MockCacheWithDeps
  [state]

  cache/CacheStorage

  (get-cached-graph
    [_ fn-id]
    (get-in @state [:graphs fn-id]))


  (cache-exists?
    [_ fn-id]
    (contains? (:graphs @state) fn-id))


  (save-cache!
    [_ fn-id graph dependencies]
    (swap! state update :graphs assoc fn-id graph)
    (swap! state update :deps assoc fn-id dependencies)
    ;; Track dependencies for lookup
    (doseq [[dep-fn-id _] (:fn-ids dependencies)]
      (swap! state update-in [:fn-deps dep-fn-id] (fnil conj #{}) fn-id))
    (doseq [[schema-id _] (:fn-schema-ids dependencies)]
      (swap! state update-in [:fn-schema-deps schema-id] (fnil conj #{}) fn-id))
    (doseq [[arg-id _] (:arg-schema-ids dependencies)]
      (swap! state update-in [:arg-schema-deps arg-id] (fnil conj #{}) fn-id)))


  (delete-cache!
    [_ fn-id]
    (let [existed (contains? (:graphs @state) fn-id)]
      (swap! state update :graphs dissoc fn-id)
      (swap! state update :deps dissoc fn-id)
      existed))


  (find-caches-by-fn-dep
    [_ dep-fn-id]
    (get-in @state [:fn-deps dep-fn-id] #{}))


  (find-caches-by-fn-schema-dep
    [_ dep-fn-schema-id]
    (get-in @state [:fn-schema-deps dep-fn-schema-id] #{}))


  (find-caches-by-arg-schema-dep
    [_ dep-arg-schema-id]
    (get-in @state [:arg-schema-deps dep-arg-schema-id] #{})))


(defn create-mock-cache-with-deps
  []
  (->MockCacheWithDeps (atom {:graphs {} :deps {} :fn-deps {} :fn-schema-deps {} :arg-schema-deps {}})))


;; === Tests with dependency-aware cache ===

(deftest fn-schema-update-invalidates-dependents-test
  (testing "fn-schema update finds and invalidates all dependent caches"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
  (testing "fn deletion invalidates all caches that depend on it"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          parent-fn-id (random-uuid)
          child-fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id parent-fn-id :name "parent" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id child-fn-id :name "child" :fn-schema-id schema-id
                                     :parent-fn-id parent-fn-id})
      ;; Manually add fn dependency (child depends on parent)
      (swap! (:state cache) update-in [:fn-deps parent-fn-id] (fnil conj #{}) child-fn-id)
      (is (cache/cache-exists? cache parent-fn-id))
      (is (cache/cache-exists? cache child-fn-id))
      ;; Delete parent - should invalidate child's cache too
      (sp/delete-entity wrapped :fn parent-fn-id)
      (is (not (cache/cache-exists? cache parent-fn-id)))
      ;; Child cache should be rebuilt (child fn still exists)
      (is (cache/cache-exists? cache child-fn-id)))))


(deftest fn-update-invalidates-fn-dependents-test
  (testing "fn update with parent change invalidates all dependent caches"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)
          child-fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "parent" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id child-fn-id :name "child" :fn-schema-id schema-id})
      ;; Manually add fn dependency (child will depend on fn-id after update)
      (swap! (:state cache) update-in [:fn-deps fn-id] (fnil conj #{}) child-fn-id)
      (is (cache/cache-exists? cache fn-id))
      (is (cache/cache-exists? cache child-fn-id))
      ;; Update child with parent - should invalidate dependent caches
      (sp/update-entity wrapped :fn child-fn-id {:parent-fn-id fn-id})
      ;; Both caches should be rebuilt
      (is (cache/cache-exists? cache fn-id))
      (is (cache/cache-exists? cache child-fn-id)))))


(deftest arg-value-delete-with-nil-record-test
  (testing "arg-value delete when record is nil does not trigger invalidation"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          nonexistent-id (random-uuid)]
      ;; Delete nonexistent arg-value - record will be nil
      (is (not (sp/delete-entity wrapped :arg-value nonexistent-id))))))


(deftest invalidate-dependents-with-deleted-fn-test
  (testing "invalidate-dependents skips rebuild for deleted fns"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
  (testing "fn update without fn-schema-id in data does not compare schemas"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (is (cache/cache-exists? cache fn-id))
      ;; Update without parent-fn-id or fn-schema-id - should NOT invalidate
      (sp/update-entity wrapped :fn fn-id {:name "renamed"})
      ;; Cache should still exist (not rebuilt)
      (is (cache/cache-exists? cache fn-id)))))


(deftest fn-update-with-same-fn-schema-id-test
  (testing "fn update with same fn-schema-id does not invalidate cache"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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


(deftest fn-update-with-both-parent-and-schema-change-test
  (testing "fn update with both parent-fn-id and fn-schema-id change"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id-1 (random-uuid)
          schema-id-2 (random-uuid)
          parent-fn-id (random-uuid)
          fn-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id-1 :name "schema1" :returned-type :int})
      (sp/create-entity storage :fn-schema {:id schema-id-2 :name "schema2" :returned-type :text})
      (sp/create-entity wrapped :fn {:id parent-fn-id :name "parent" :fn-schema-id schema-id-1})
      (sp/create-entity wrapped :fn {:id fn-id :name "child" :fn-schema-id schema-id-1})
      (is (cache/cache-exists? cache fn-id))
      ;; Update with BOTH parent-fn-id and fn-schema-id change
      (sp/update-entity wrapped :fn fn-id {:parent-fn-id parent-fn-id :fn-schema-id schema-id-2})
      ;; Cache should be rebuilt
      (is (cache/cache-exists? cache fn-id)))))


(deftest multiple-dependent-caches-invalidation-test
  (testing "invalidating a schema affects all dependent caches correctly"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          schema-id (random-uuid)
          ;; Create chain: grandparent -> parent -> child
          grandparent-id (random-uuid)
          parent-id (random-uuid)
          child-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity wrapped :fn {:id grandparent-id :name "grandparent" :fn-schema-id schema-id})
      (sp/create-entity wrapped :fn {:id parent-id :name "parent" :fn-schema-id schema-id
                                     :parent-fn-id grandparent-id})
      (sp/create-entity wrapped :fn {:id child-id :name "child" :fn-schema-id schema-id
                                     :parent-fn-id parent-id})
      ;; Add fn dependencies
      (swap! (:state cache) update-in [:fn-deps grandparent-id] (fnil conj #{}) parent-id)
      (swap! (:state cache) update-in [:fn-deps parent-id] (fnil conj #{}) child-id)
      ;; All should be cached
      (is (cache/cache-exists? cache grandparent-id))
      (is (cache/cache-exists? cache parent-id))
      (is (cache/cache-exists? cache child-id))
      ;; Delete grandparent - should cascade to parent's cache
      (sp/delete-entity wrapped :fn grandparent-id)
      ;; grandparent cache deleted
      (is (not (cache/cache-exists? cache grandparent-id)))
      ;; parent cache should be rebuilt (parent fn still exists)
      (is (cache/cache-exists? cache parent-id))
      ;; child cache unchanged (wasn't directly dependent on grandparent)
      (is (cache/cache-exists? cache child-id)))))


(deftest arg-value-update-with-owner-fn-id-test
  (testing "arg-value update correctly uses result's owner-fn-id"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
          wrapped (cached/wrap-with-cache storage cache)
          nonexistent-id (random-uuid)]
      ;; Try to delete nonexistent arg-value - should return false
      (is (not (sp/delete-entity wrapped :arg-value nonexistent-id))))))


(deftest batch-delete-mixed-existing-nonexisting-test
  (testing "batch delete with mix of existing and non-existing entities"
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache-with-deps)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          wrapped (cached/wrap-with-cache-and-metrics storage cache)]
      (is (some? wrapped))
      (is (instance? graphden.cached_storage.interface.CachedStorageWithMetrics wrapped))))

  (testing "accepts custom metrics atom"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
          metrics (cached/create-metrics)
          wrapped (cached/wrap-with-cache-and-metrics storage cache metrics)]
      (is (some? wrapped))
      ;; Verify same metrics atom is used
      (is (= metrics (:metrics wrapped))))))


(deftest get-storage-metrics-test
  (testing "returns nil for non-metrics storage"
    (let [storage (create-mock-storage)]
      (is (nil? (cached/get-storage-metrics storage)))))

  (testing "returns nil for regular cached storage"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache storage (create-mock-cache))]
      (is (nil? (cached/get-storage-metrics wrapped)))))

  (testing "returns metrics for metrics-enabled storage"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache-and-metrics storage (create-mock-cache))]
      (is (some? (cached/get-storage-metrics wrapped)))
      (is (map? (cached/get-storage-metrics wrapped))))))


(deftest metrics-tracking-resolve-graph-test
  (testing "tracks cache hits and misses for resolve-execution-graph"
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [storage (create-mock-storage)
          cache (create-mock-cache)
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
    (let [wrapped (cached/wrap-with-cache-and-metrics (create-mock-storage) (create-mock-cache))]
      (is (set? (sp/current-entities wrapped)))
      (is (nil? (sp/current-fields wrapped :test)))
      (is (set? (sp/current-enums wrapped)))
      (is (nil? (sp/current-enum-values wrapped :test)))
      (is (nil? (sp/schema-metadata wrapped))))))


(deftest metrics-storage-constraints-test
  (testing "CachedStorageWithMetrics delegates constraint methods"
    (let [wrapped (cached/wrap-with-cache-and-metrics (create-mock-storage) (create-mock-cache))
          fn-id (random-uuid)]
      (is (nil? (sp/validate-parent-same-schema! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-arg-override! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-arg-schema-belongs-to-fn! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-inheritance-cycle! wrapped fn-id fn-id)))
      (is (nil? (sp/validate-no-dependency-cycle! wrapped fn-id fn-id))))))


(deftest metrics-storage-constraint-helpers-test
  (testing "CachedStorageWithMetrics delegates constraint helper methods"
    (let [storage (create-mock-storage)
          wrapped (cached/wrap-with-cache-and-metrics storage (create-mock-cache))
          schema-id (random-uuid)
          fn-id (random-uuid)
          arg-schema-id (random-uuid)]
      ;; Setup
      (sp/create-entity storage :fn-schema {:id schema-id :name "test" :returned-type :int})
      (sp/create-entity storage :fn {:id fn-id :name "fn" :fn-schema-id schema-id})
      (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema-id
                                             :name "x" :type :int :required true})
      ;; Test helpers
      (is (= schema-id (sp/get-fn-schema-id-for-fn wrapped fn-id)))
      (is (= schema-id (sp/get-fn-schema-id-for-arg-schema wrapped arg-schema-id)))
      (is (nil? (sp/get-parent-fn-id wrapped fn-id)))
      (is (set? (sp/collect-parent-chain wrapped fn-id)))
      (is (set? (sp/collect-arg-schema-ids-in-chain wrapped fn-id)))
      (is (set? (sp/collect-dependency-chain wrapped fn-id))))))


(deftest metrics-storage-lifecycle-test
  (testing "CachedStorageWithMetrics delegates lifecycle methods"
    (let [wrapped (cached/wrap-with-cache-and-metrics (create-mock-storage) (create-mock-cache))]
      (is (map? (sp/initialize wrapped {})))
      (is (nil? (sp/close wrapped))))))
