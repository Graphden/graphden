(ns graphden.graph-storage-postgres.interface
  "PostgreSQL storage pre-initialized with graph-data-schema.

   Provides a convenient factory function for creating a PostgreSQL storage
   that is already synced with the graph data schema."
  (:require
    [graphden.graph-data-schema.interface :as graph]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.interface :as pg]
    [graphden.storage-protocol.interface :as sp]))


(defn create-storage
  "Creates a PostgreSQL storage initialized with graph-data-schema.

   Returns a storage instance implementing Storage and StorageIntrospection
   protocols, ready for CRUD operations on graph entities (fn-schema, arg-schema,
   fn, arg-value).

   The storage is already initialized - no need to call sp/initialize.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username (required)
   - :password - database password (required)
   - :pool-size - connection pool size (default 10)

   Example:
     (require '[graphden.storage-protocol.interface :as sp])
     (let [storage (create-storage {:jdbc-url \"jdbc:postgresql://localhost:5432/mydb\"
                                    :username \"user\"
                                    :password \"pass\"})]
       ;; storage is ready to use
       (sp/current-entities storage)  ; => #{:fn-schema :arg-schema :fn :arg-value}
       ;; ... use storage ...
       (sp/close storage))"
  [opts]
  (let [storage (pg/create-storage opts)
        schema (graph/build-schema (mds/create-builder))]
    (try
      (sp/initialize storage schema)
      storage
      ;; Catch any exception to ensure storage cleanup on initialization failure.
      ;; Re-throws the original exception after closing the storage.
      (catch Exception e
        (sp/close storage)
        (throw e)))))
