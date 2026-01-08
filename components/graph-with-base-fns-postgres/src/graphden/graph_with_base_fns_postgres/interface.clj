(ns graphden.graph-with-base-fns-postgres.interface
  "PostgreSQL storage with graph schema and base functions.

   Creates a complete graphden environment:
   - PostgreSQL storage initialized with graph-data-schema
   - Base functions registered in executor
   - Base function schemas synced to storage

   This is the recommended way to create a fully-functional
   graphden instance for production use."
  (:require
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-postgres.interface :as gsp]))


(defn create-storage
  "Creates a PostgreSQL storage with graph schema and base functions.

   This function:
   1. Creates PostgreSQL storage with graph-data-schema
   2. Registers all base functions in the executor
   3. Syncs base function schemas to storage

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username (required)
   - :password - database password (required)
   - :pool-size - connection pool size (default 10)

   Returns a storage instance ready for graph operations with
   all base functions available.

   Example:
     (require '[graphden.storage-protocol.interface :as sp])
     (let [storage (create-storage {:jdbc-url \"jdbc:postgresql://localhost:5432/mydb\"
                                    :username \"user\"
                                    :password \"pass\"})]
       ;; Base functions are registered and synced
       (sp/query-entities storage :fn-schema {})
       ;; ... use storage ...
       (sp/close storage))"
  [opts]
  (registry/create-storage-with-base-fns gsp/create-storage opts))
