(ns graphden.graph-with-base-fns-datomic.interface
  "Datahike storage with graph schema and base functions.

   Creates a complete graphden environment:
   - Datahike storage initialized with graph-data-schema
   - Base functions registered in executor
   - Base function schemas synced to storage

   This is the recommended way to create a fully-functional
   graphden instance with Datahike backend."
  (:require
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-datomic.interface :as gsd]))


(defn create-storage
  "Creates a Datahike storage with graph schema and base functions.

   This function:
   1. Creates Datahike storage with graph-data-schema
   2. Registers all base functions in the executor
   3. Syncs base function schemas to storage

   Options:
   - :db-name - database name (default: auto-generated unique name)

   Returns a storage instance ready for graph operations with
   all base functions available.

   Example:
     (require '[graphden.storage-protocol.interface :as sp])
     (let [storage (create-storage)]
       ;; Base functions are registered and synced
       (sp/query-entities storage :fn-schema {})
       ;; ... use storage ...
       (sp/close storage))"
  ([]
   (create-storage {}))
  ([opts]
   (-> (gsd/create-storage opts)
       (registry/initialize-with-base-fns!))))
