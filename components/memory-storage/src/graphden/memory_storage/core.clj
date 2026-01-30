(ns graphden.memory-storage.core
  "In-memory implementation of Storage protocol."
  (:require
    [clojure.tools.logging :as log]
    [graphden.memory-storage.crud :as crud]
    [graphden.memory-storage.graph :as graph]
    [graphden.memory-storage.migration :as migration]
    [graphden.storage-protocol.generic-constraints :as gc]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util.concurrent.locks
      ReentrantReadWriteLock)))


;; === Internal helpers ===
;; Note: sensitive-field? and redact-sensitive-map are used from sp/
;; See storage-protocol.interface for shared security utilities.


(defn- do-initialize
  "Performs initialization, returns [new-state changes]."
  [state schema]
  (let [old-state @state
        old-metadata (:metadata old-state)]
    (if (nil? old-metadata)
      ;; First-time initialization
      (let [new-metadata (sp/build-metadata-from-schema schema)
            new-entities (migration/build-entities-structure schema)
            new-enums (migration/build-enums-structure schema)
            new-state {:entities new-entities
                       :enums new-enums
                       :metadata new-metadata
                       :data {}}
            changes (sp/build-first-init-changes schema)]
        [new-state changes])
      ;; Migration
      (do
        ;; Check for destructive changes
        (sp/check-all-removals! old-metadata schema)
        (migration/validate-type-changes! old-state old-metadata schema)
        ;; Compute changes
        (let [entity-changes (sp/compute-entity-changes old-metadata schema)
              field-changes (sp/compute-field-changes old-metadata schema)
              enum-changes (sp/compute-enum-changes old-metadata schema)
              enum-value-changes (sp/compute-enum-value-changes old-metadata schema)
              ;; Build new state
              new-metadata (sp/build-metadata-from-schema schema)
              new-entities (migration/build-entities-structure schema)
              new-enums (migration/build-enums-structure schema)
              new-data (migration/migrate-data (:data old-state) old-metadata schema)
              new-state {:entities new-entities
                         :enums new-enums
                         :metadata new-metadata
                         :data new-data}
              changes {:entities entity-changes
                       :fields field-changes
                       :enums enum-changes
                       :enum-values enum-value-changes}]
          [new-state changes])))))


;; === Storage record ===

(defrecord MemoryStorage
  [state ^ReentrantReadWriteLock rw-lock]

  sp/Storage

  (initialize
    [_this schema]
    (sp/with-write-lock rw-lock
                        (fn []
                          (log/info "Initializing memory storage")
                          (let [[new-state changes] (do-initialize state schema)]
                            (reset! state new-state)
                            (log/info "Memory storage initialized" {:entities (count (:entities new-state))
                                                                    :enums (count (:enums new-state))})
                            changes))))


  (close
    [_this]
    (sp/with-write-lock rw-lock
                        (fn []
                          (log/info "Closing memory storage")
                          (reset! state {:entities {}
                                         :enums {}
                                         :metadata nil
                                         :data {}})
                          nil)))


  sp/StorageIntrospection

  (current-entities
    [_this]
    (sp/with-read-lock rw-lock
                       #(set (keys (:entities @state)))))


  (current-fields
    [_this entity-name]
    (sp/with-read-lock rw-lock
                       #(get-in @state [:entities entity-name :fields])))


  (current-enums
    [_this]
    (sp/with-read-lock rw-lock
                       #(set (keys (:enums @state)))))


  (current-enum-values
    [_this enum-name]
    (sp/with-read-lock rw-lock
                       #(get-in @state [:enums enum-name :values])))


  (schema-metadata
    [_this]
    (sp/with-read-lock rw-lock
                       #(:metadata @state)))


  sp/StorageCRUD

  (create-entity
    [_this entity-name data]
    ;; Validate data type before acquiring lock
    (sp/validate-data-is-map! entity-name data)
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [id (or (:id data) (random-uuid))
                                record (assoc data :id id)]
                            (crud/create-record-atomic! state entity-name record)))))


  (read-entity
    [_this entity-name id]
    (sp/with-read-lock rw-lock
                       #(crud/get-record @state entity-name id)))


  (update-entity
    [_this entity-name id data]
    (sp/with-write-lock rw-lock
                        #(crud/update-record-atomic! state entity-name id data)))


  (delete-entity
    [_this entity-name id]
    (sp/with-write-lock rw-lock
                        #(crud/remove-record! state entity-name id)))


  (query-entities
    [_this entity-name where]
    ;; Validate where clause type before acquiring lock
    (sp/validate-where-clause! where)
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [s @state]
                           (crud/validate-entity-exists! s entity-name)
                           (let [fields (crud/get-entity-fields s entity-name)
                                 all-records (vals (crud/get-entity-data s entity-name))]
                             ;; Validate where clause fields after we have schema info
                             (sp/validate-where-clause-fields! entity-name fields where)
                             (if (empty? where)
                               all-records
                               (filter (fn [record]
                                         (every? (fn [[k v]] (= (get record k) v)) where))
                                       all-records)))))))


  sp/StorageBatchCRUD

  (create-entities
    [_this entity-name data-seq]
    (sp/with-write-lock rw-lock
                        (fn []
                          (if (empty? data-seq)
                            []
                            (do
                              (sp/validate-batch-size! (count data-seq) :create-entities
                                                       {:entity-name entity-name})
                              (sp/validate-no-duplicate-ids! entity-name data-seq)
                              (let [records (map (fn [data]
                                                   (let [id (or (:id data) (random-uuid))]
                                                     (assoc data :id id)))
                                                 data-seq)]
                                (crud/create-records-atomic! state entity-name records)))))))


  (read-entities
    [_this entity-name ids]
    (sp/with-read-lock rw-lock
                       #(crud/read-records @state entity-name ids)))


  (delete-entities
    [_this entity-name ids]
    (sp/with-write-lock rw-lock
                        #(crud/remove-records! state entity-name ids)))


  sp/GraphConstraints

  (validate-arg-schema-belongs-to-fn!
    [this fn-id arg-schema-id]
    (sp/with-read-lock rw-lock
                       #(gc/validate-arg-schema-belongs-to-fn! this fn-id arg-schema-id)))


  (validate-no-dependency-cycle!
    [this owner-fn-id value-fn-id]
    (sp/with-read-lock rw-lock
                       #(gc/validate-no-dependency-cycle! this owner-fn-id value-fn-id)))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_this fn-id]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [s @state]
                           (when-not (crud/get-record s :fn fn-id)
                             (throw (ex-info "Function not found"
                                             {:type :not-found
                                              :fn-id fn-id})))
                           (graph/resolve-execution-graph-impl s fn-id)))))


  sp/StorageErrorClassifier

  (classify-error
    [_this exception]
    (if (instance? clojure.lang.ExceptionInfo exception)
      (let [error-type (:type (ex-data exception))]
        (or error-type :unknown-memory-error))
      :unknown-memory-error))


  (wrap-error
    [this exception operation context]
    (let [error-type (sp/classify-error this exception)
          error-data (merge {:type error-type
                             :operation operation
                             :message (ex-message exception)}
                            context)]
      (ex-info (str "Memory storage error during " (name operation) ": " (ex-message exception))
               error-data
               exception))))


(defn create-storage
  "Creates a new in-memory storage instance.
   Thread-safe with ReentrantReadWriteLock for concurrent access."
  []
  (let [state (atom {:entities {}
                     :enums {}
                     :metadata nil
                     :data {}})]
    (->MemoryStorage state
                     (ReentrantReadWriteLock.))))
