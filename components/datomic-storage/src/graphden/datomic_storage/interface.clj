(ns graphden.datomic-storage.interface
  "Public interface for Datomic storage implementation."
  (:require
    [graphden.datomic-storage.core :as core]
    [graphden.datomic-storage.util :as util]))


;; Re-export timeout configuration for API compatibility
(def ^:dynamic *query-timeout-ms*
  "Query timeout in milliseconds for API compatibility with postgres-storage.
   NOTE: Datomic Client API does not support native query timeout.
   Default is 30000 ms (30 seconds)."
  core/*query-timeout-ms*)


(defn with-query-timeout
  "Executes f with a custom query timeout binding.
   NOTE: Datomic Client API does not enforce this timeout on queries.

   Example:
   (with-query-timeout 60000
     #(sp/query-entities storage :user {}))"
  [timeout-ms f]
  (core/with-query-timeout timeout-ms f))


(def default-local-config
  "Default configuration for Datomic Local with in-memory storage.
   Use this as a base for customization."
  util/default-local-config)


(defn create-storage
  "Creates a new Datomic storage instance.

   The storage implements both Storage and StorageIntrospection protocols.

   Options:
   - :db-name - database name (default \"graphden\")
   - :client-config - Datomic client configuration map
                      (default: local in-memory)

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
  [opts]
  (core/create-storage opts))
