(ns graphden.postgres-storage.interface
  "Public interface for PostgreSQL storage implementation."
  (:require
    [graphden.postgres-storage.core :as core]))


(defn create-storage
  "Creates a new PostgreSQL storage instance.

   The storage implements both Storage and StorageIntrospection protocols.
   Use sp/initialize to sync with a DataSchema, then use storage for operations.

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
       (sp/initialize storage my-schema)
       ;; use storage...
       (sp/close storage))"
  [opts]
  (core/create-storage opts))
