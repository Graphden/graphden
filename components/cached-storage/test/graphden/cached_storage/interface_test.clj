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
                                     {:id frv-id :fn-id fn-id :value "result"})]
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
      (sp/create-entity storage :fn-result-value {:id frv-id :fn-id (random-uuid) :value "test"})
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
                                  {:fn-id fn-id :value "result"})]
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
      (sp/create-entity storage :fn-result-value {:id frv-id :fn-id fn-id :value "result"})
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
