(ns graphden.graph-storage-age.core
  "Core implementation of Apache AGE storage.

   This module implements all Storage protocols using AGE for graph storage.
   Traditional CRUD operations use SQL tables (like postgres-storage), but
   resolve-execution-graph uses a single optimized Cypher query."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.graph-storage-age.age :as age]
    [graphden.graph-storage-age.crud :as crud]
    [graphden.graph-storage-age.graph :as graph]
    [graphden.graph-storage-age.migration :as migration]
    [graphden.graph-storage-age.pool :as pool]
    [graphden.storage-protocol.generic-constraints :as gc]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util.concurrent.locks
      ReentrantReadWriteLock)))


;; === AGEStorage Record ===

(defrecord AGEStorage
  [pool graph-name metadata-cache ^ReentrantReadWriteLock rw-lock]

  sp/Storage

  (initialize
    [_this schema]
    (sp/with-write-lock rw-lock
                        (fn []
                          (reset! metadata-cache nil)
                          (migration/do-initialize pool schema)
                          ;; Initialize AGE graph after tables
                          (age/ensure-graph! pool graph-name))))


  (close
    [_this]
    (sp/with-write-lock rw-lock
                        (fn []
                          (reset! metadata-cache nil)
                          (pool/close-pool pool)))
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (crud/current-entities pool))


  (current-fields
    [_this entity-name]
    (crud/current-fields pool entity-name metadata-cache rw-lock))


  (current-enums
    [_this]
    (crud/current-enums pool))


  (current-enum-values
    [_this enum-name]
    (crud/current-enum-values pool enum-name))


  (schema-metadata
    [_this]
    (crud/get-cached-metadata pool metadata-cache rw-lock))


  sp/StorageCRUD

  (create-entity
    [_this entity-name data]
    (let [result (crud/create-entity pool entity-name data metadata-cache rw-lock)]
      ;; Sync to AGE graph for graph entities
      (when (age/graph-entity? entity-name)
        (age/sync-entity-to-graph! pool graph-name entity-name result))
      result))


  (read-entity
    [_this entity-name id]
    (crud/read-entity pool entity-name id))


  (update-entity
    [_this entity-name id data]
    (let [result (crud/update-entity pool entity-name id data metadata-cache rw-lock)]
      ;; Sync to AGE graph
      (when (and result (age/graph-entity? entity-name))
        (age/sync-entity-to-graph! pool graph-name entity-name result))
      result))


  (delete-entity
    [_this entity-name id]
    ;; Remove from AGE graph first
    (when (age/graph-entity? entity-name)
      (age/delete-entity-from-graph! pool graph-name entity-name id))
    (crud/delete-entity pool entity-name id))


  (query-entities
    [_this entity-name where]
    (crud/query-entities pool entity-name where metadata-cache rw-lock))


  sp/StorageBatchCRUD

  (create-entities
    [_this entity-name data-seq]
    (let [results (crud/create-entities pool entity-name data-seq metadata-cache rw-lock)]
      ;; Sync to AGE graph
      (when (age/graph-entity? entity-name)
        (doseq [entity results]
          (age/sync-entity-to-graph! pool graph-name entity-name entity)))
      results))


  (read-entities
    [_this entity-name ids]
    (crud/read-entities pool entity-name ids))


  (delete-entities
    [_this entity-name ids]
    ;; Remove from AGE graph first
    (when (age/graph-entity? entity-name)
      (doseq [id ids]
        (age/delete-entity-from-graph! pool graph-name entity-name id)))
    (crud/delete-entities pool entity-name ids))


  sp/GraphConstraints

  (validate-arg-schema-belongs-to-fn!
    [this fn-id arg-schema-id]
    (gc/validate-arg-schema-belongs-to-fn! this fn-id arg-schema-id))


  (validate-no-dependency-cycle!
    [this owner-fn-id value-fn-id]
    (gc/validate-no-dependency-cycle! this owner-fn-id value-fn-id))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_this fn-id]
    ;; Use AGE Cypher query for O(1) graph resolution instead of BFS
    (graph/resolve-execution-graph-cypher pool graph-name fn-id))


  sp/StorageErrorClassifier

  (classify-error
    [_this exception]
    (crud/classify-error exception))


  (wrap-error
    [_this exception operation context]
    (crud/wrap-error exception operation context)))


(defn create-storage
  "Creates a new Apache AGE storage instance.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username
   - :password - database password
   - :pool-size - connection pool size (default 10)
   - :graph-name - AGE graph name (default \"graphden\")

   Thread safety:
   - Uses ReentrantReadWriteLock for metadata cache access
   - Multiple concurrent reads allowed, writes are exclusive
   - CRUD operations use PostgreSQL's own transaction isolation"
  [opts]
  (let [graph-name (or (:graph-name opts) "graphden")]
    (->AGEStorage (pool/create-pool opts)
                  graph-name
                  (atom nil)
                  (ReentrantReadWriteLock.))))
