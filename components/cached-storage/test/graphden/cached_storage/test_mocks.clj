(ns graphden.cached-storage.test-mocks
  "Mock implementations for cached-storage testing."
  (:require
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


;; === Basic Mock Cache ===

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
    #{})


  (find-caches-by-call-site-dep
    [_ _dep-call-site-id]
    #{}))


(defn create-mock-cache
  []
  (->MockCache (atom {:graphs {} :deps {}})))


;; === Enhanced Mock Cache with Dependency Tracking ===

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
      (swap! state update-in [:arg-schema-deps arg-id] (fnil conj #{}) fn-id))
    (doseq [[cs-id _] (:call-site-ids dependencies)]
      (swap! state update-in [:call-site-deps cs-id] (fnil conj #{}) fn-id)))


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
    (get-in @state [:arg-schema-deps dep-arg-schema-id] #{}))


  (find-caches-by-call-site-dep
    [_ dep-call-site-id]
    (get-in @state [:call-site-deps dep-call-site-id] #{})))


(defn create-mock-cache-with-deps
  []
  (->MockCacheWithDeps (atom {:graphs {} :deps {} :fn-deps {} :fn-schema-deps {} :arg-schema-deps {} :call-site-deps {}})))


;; === Mock Storage ===

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

  (validate-arg-schema-belongs-to-fn! [_ _fn-id _arg-schema-id] nil)


  (validate-no-dependency-cycle! [_ _owner-fn-id _value-fn-id] nil)


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
