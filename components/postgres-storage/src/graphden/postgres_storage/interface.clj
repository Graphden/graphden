(ns graphden.postgres-storage.interface
  "Public interface for PostgreSQL storage implementation."
  (:require
    [graphden.postgres-storage.core :as core]
    [graphden.postgres-storage.util :as util]))


;; Re-export timeout configuration from util (canonical location).
;; Using the var reference directly since Clojure def doesn't create aliases.
(def ^:dynamic *query-timeout-ms*
  "Timeout for SQL queries in milliseconds. Can be rebound per-thread.
   Default is 30000 ms (30 seconds).
   Note: For bindings, use graphden.postgres-storage.util/*query-timeout-ms*."
  util/*query-timeout-ms*)


(def with-query-timeout
  "Executes f with a custom query timeout (in milliseconds).

   Example:
   (with-query-timeout 60000
     #(sp/query-entities storage :user {}))"
  util/with-query-timeout)


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
