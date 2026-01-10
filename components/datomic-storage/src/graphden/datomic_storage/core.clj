(ns graphden.datomic-storage.core
  "Datomic Local implementation of Storage protocol.

   ## Module Structure

   This module is split into focused namespaces:
   - core.clj (this file) - Storage record, configuration, factory
   - introspection.clj - Schema introspection helpers
   - metadata.clj - Metadata transaction building and persistence
   - migration.clj - Schema migration logic
   - crud.clj - CRUD and batch operation implementations
   - graph.clj - ExecutionGraph resolution
   - schema.clj - Datomic schema building
   - constraints.clj - Graph constraint validation
   - util.clj - Common utilities"
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [datomic.client.api :as d]
    [graphden.datomic-storage.constraints :as constraints]
    [graphden.datomic-storage.crud :as crud]
    [graphden.datomic-storage.graph :as graph]
    [graphden.datomic-storage.introspection :as introspection]
    [graphden.datomic-storage.migration :as migration]
    [graphden.datomic-storage.schema :as schema]
    [graphden.datomic-storage.util :as util]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util.concurrent.locks
      ReentrantReadWriteLock)))


;; === Configuration ===
;; Query timeout is managed by util.clj for this component.
;; Re-export from util for backward compatibility.
;; NOTE: Use util/*query-timeout-ms* as the canonical var.

(def with-query-timeout
  "Executes f with a custom query timeout binding.
   See util/with-query-timeout for details."
  util/with-query-timeout)


;; === Connection validation ===

(defn- ensure-connection!
  "Ensures connection is available for CRUD operations.
   Throws :storage-not-initialized if conn-atom is nil.
   Returns the connection if valid."
  [conn-atom operation-name]
  (if-let [conn @conn-atom]
    conn
    (do
      (log/error "CRUD operation failed: storage not initialized" {:operation operation-name})
      (throw (ex-info "Cannot perform operation: storage not initialized"
                      {:type :storage-not-initialized
                       :operation operation-name})))))


;; === Storage record ===

(defrecord DatomicStorage
  [client-config db-name client-atom conn-atom schema-atom metadata-cache ^ReentrantReadWriteLock rw-lock]

  sp/Storage

  (initialize
    [_this schema]
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [client (d/client client-config)]
                            (reset! client-atom client)
                            ;; Store schema for multi-field constraint validation
                            (reset! schema-atom schema)
                            ;; Create database if it doesn't exist
                            ;; Uses optimistic approach: try to create and ignore "already exists" error.
                            ;; This is race-condition safe: if another thread creates the DB between
                            ;; our check and create, we simply catch the exception and continue.
                            (try
                              (d/create-database client {:db-name db-name})
                              (catch clojure.lang.ExceptionInfo e
                                ;; Datomic throws ExceptionInfo with :db/error when DB already exists
                                (when-not (= (:db/error (ex-data e)) :db.error/db-already-exists)
                                  (throw e))))
                            (let [conn (d/connect client {:db-name db-name})]
                              (reset! conn-atom conn)
                              ;; Perform initialization, then invalidate metadata cache on success.
                              ;; Cache invalidation is intentionally AFTER do-initialize completes:
                              ;; - If do-initialize throws, cache stays nil (safe: fresh start on retry)
                              ;; - If do-initialize succeeds, cache is reset to nil (forces refresh)
                              ;; Note: try-finally is NOT needed here because cache starts as nil,
                              ;; so failed initialization leaves system in consistent state.
                              (let [result (migration/do-initialize conn schema)]
                                (reset! metadata-cache nil)
                                result))))))


  (close
    [_this]
    (sp/with-write-lock rw-lock
                        (fn []
                          (when-let [client @client-atom]
                            (d/delete-database client {:db-name db-name}))
                          (reset! conn-atom nil)
                          (reset! client-atom nil)))
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (sp/with-read-lock rw-lock
                       (fn []
                         (if-let [conn @conn-atom]
                           (let [db (d/db conn)
                                 attrs (introspection/current-attrs db)]
                             (->> (keys attrs)
                                  (map namespace)
                                  (filter some?)
                                  (set)
                                  (map keyword)
                                  (set)))
                           #{}))))


  (current-fields
    [_this entity-name]
    (sp/with-read-lock rw-lock
                       (fn []
                         (when-let [conn @conn-atom]
                           (let [db (d/db conn)
                                 metadata (introspection/read-metadata db)
                                 ;; Check if entity exists in metadata
                                 entity-exists? (some #(= % entity-name) (vals (:entities metadata)))]
                             (when entity-exists?
                               (let [entity-fields (->> (:fields metadata)
                                                        (vals)
                                                        (filter #(= (:entity %) entity-name)))]
                                 (into {}
                                       (map (fn [{:keys [field nullable?] field-type :type}]
                                              [field {:type field-type :nullable? nullable?}])
                                            entity-fields)))))))))


  (current-enums
    [_this]
    (sp/with-read-lock rw-lock
                       (fn []
                         (if-let [conn @conn-atom]
                           (let [db (d/db conn)
                                 enum-values (introspection/current-enum-values-db db)]
                             (->> enum-values
                                  (map #(-> (namespace %) (str/replace ".value" "") keyword))
                                  (set)))
                           #{}))))


  (current-enum-values
    [_this enum-name]
    (sp/with-read-lock rw-lock
                       (fn []
                         (when-let [conn @conn-atom]
                           (let [db (d/db conn)
                                 enum-values (introspection/current-enum-values-db db)
                                 enum-ns (str (name enum-name) ".value")
                                 values (->> enum-values
                                             (filter #(= (namespace %) enum-ns))
                                             (map #(keyword (name %)))
                                             (set))]
                             (when (seq values) values))))))


  (schema-metadata
    [_this]
    (sp/with-read-lock rw-lock
                       (fn []
                         (when-let [conn @conn-atom]
                           (introspection/read-metadata (d/db conn))))))


  sp/StorageCRUD

  (create-entity
    [_this entity-name data]
    ;; Validate data type before acquiring lock
    (sp/validate-data-is-map! entity-name data)
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [conn (ensure-connection! conn-atom :create-entity)
                                db (d/db conn)
                                field-specs (crud/get-fields-with-specs db entity-name)]
                            ;; Validate multi-field unique constraints before creating
                            (when-let [schema @schema-atom]
                              (schema/validate-multi-field-constraints! db schema entity-name data field-specs nil))
                            (crud/create-entity-impl conn entity-name data)))))


  (read-entity
    [_this entity-name id]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [conn (ensure-connection! conn-atom :read-entity)]
                           (crud/read-entity-impl conn entity-name id)))))


  (update-entity
    [_this entity-name id data]
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [conn (ensure-connection! conn-atom :update-entity)
                                db (d/db conn)
                                field-specs (crud/get-fields-with-specs db entity-name)]
                            ;; Validate multi-field unique constraints before updating
                            (when-let [schema @schema-atom]
                              (schema/validate-multi-field-constraints! db schema entity-name data field-specs id))
                            (crud/update-entity-impl conn entity-name id data)))))


  (delete-entity
    [_this entity-name id]
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [conn (ensure-connection! conn-atom :delete-entity)]
                            (crud/delete-entity-impl conn entity-name id)))))


  (query-entities
    [_this entity-name where]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [conn (ensure-connection! conn-atom :query-entities)]
                           (util/execute-with-timeout! :query-entities
                                                       #(crud/query-entities-impl conn entity-name where))))))


  sp/StorageBatchCRUD

  (create-entities
    [_this entity-name data-seq]
    (sp/with-write-lock rw-lock
                        (fn []
                          (when (seq data-seq)
                            (sp/validate-batch-size! (count data-seq) :create-entities
                                                     {:entity-name entity-name}))
                          (let [conn (ensure-connection! conn-atom :create-entities)
                                db (d/db conn)
                                field-specs (crud/get-fields-with-specs db entity-name)]
                            ;; Validate multi-field unique constraints for each entity
                            (when-let [schema @schema-atom]
                              (doseq [data data-seq]
                                (schema/validate-multi-field-constraints! db schema entity-name data field-specs nil)))
                            (crud/create-entities-impl conn entity-name data-seq)))))


  (read-entities
    [_this entity-name ids]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [conn (ensure-connection! conn-atom :read-entities)]
                           (util/execute-with-timeout! :read-entities
                                                       #(crud/read-entities-impl conn entity-name ids))))))


  (delete-entities
    [_this entity-name ids]
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [conn (ensure-connection! conn-atom :delete-entities)]
                            (crud/delete-entities-impl conn entity-name ids)))))


  sp/GraphConstraints

  (validate-parent-same-schema!
    [_this fn-id parent-fn-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-parent-same-schema! conn-atom fn-id parent-fn-id)))


  (validate-no-arg-override!
    [_this fn-id arg-schema-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-no-arg-override! conn-atom fn-id arg-schema-id)))


  (validate-arg-schema-belongs-to-fn!
    [_this fn-id arg-schema-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-arg-schema-belongs-to-fn! conn-atom fn-id arg-schema-id)))


  (validate-no-inheritance-cycle!
    [_this fn-id parent-fn-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-no-inheritance-cycle! conn-atom fn-id parent-fn-id)))


  (validate-no-dependency-cycle!
    [_this owner-fn-id value-fn-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-no-dependency-cycle! conn-atom owner-fn-id value-fn-id)))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_this fn-id]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [conn (ensure-connection! conn-atom :resolve-execution-graph)]
                           (graph/resolve-execution-graph-impl conn fn-id)))))


  sp/StorageErrorClassifier

  (classify-error
    [_this exception]
    ;; Delegate to util/classify-datomic-error for consistent classification
    (util/classify-datomic-error exception))


  (wrap-error
    [_this exception operation context]
    ;; Delegate to util/wrap-datomic-error for consistent error wrapping
    (util/wrap-datomic-error exception "Datomic error" operation context)))


(defn create-storage
  "Creates a new Datomic storage instance.

   Options:
   - :db-name - database name (default \"graphden\")
   - :client-config - Datomic client configuration map
                      (default: local in-memory, see util/default-local-config)

   Validates:
   - db-name must be alphanumeric with hyphens, starting with letter
   - client-config must have valid :server-type
   - Required keys for each server-type are present

   Examples:

   ;; Local in-memory (default):
   (create-storage {:db-name \"my-db\"})

   ;; Local with file storage:
   (create-storage {:db-name \"my-db\"
                    :client-config {:server-type :datomic-local
                                    :storage-dir \"/path/to/data\"
                                    :system \"my-system\"}})

   ;; Pro with peer-server:
   (create-storage {:db-name \"my-db\"
                    :client-config {:server-type :peer-server
                                    :endpoint \"localhost:8998\"
                                    :secret \"your-secret\"
                                    :access-key \"your-key\"}})"
  [{:keys [db-name client-config]
    :or {db-name "graphden"
         client-config util/default-local-config}}]
  (util/validate-db-name! db-name)
  (util/validate-client-config! client-config)
  (log/info "Creating Datomic storage" {:db-name db-name :server-type (:server-type client-config)})
  (->DatomicStorage client-config db-name (atom nil) (atom nil) (atom nil) (atom nil) (ReentrantReadWriteLock.)))
