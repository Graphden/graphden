(ns graphden.graph-with-base-fns-memory.interface
  "In-memory storage with graph schema and base functions.

   Creates a complete graphden environment:
   - Memory storage initialized with graph-data-schema
   - Base functions registered in executor
   - Base function schemas synced to storage

   This is the recommended way to create a fully-functional
   graphden instance for development and testing."
  (:require
    [graphden.base-functions.interface :as bf]
    [graphden.fn-registry.interface :as registry]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp]))


(defn create-storage
  "Creates an in-memory storage with graph schema and base functions.

   This function:
   1. Creates memory storage with graph-data-schema
   2. Registers all base functions in the executor
   3. Syncs base function schemas to storage

   Returns a storage instance ready for graph operations with
   all base functions available.

   Example:
     (require '[graphden.storage-protocol.interface :as sp])
     (let [storage (create-storage)]
       ;; Base functions are registered and synced
       (sp/query-entities storage :fn-schema {})
       ;; ... use storage ...
       (sp/close storage))"
  []
  (let [storage (gsm/create-storage)]
    (try
      ;; Register base functions in executor
      (registry/register-base-fns! (bf/get-all-defs))
      ;; Sync base function schemas to storage
      (registry/sync-defs-to-storage! storage (bf/get-all-defs))
      storage
      (catch Exception e
        (sp/close storage)
        (throw e)))))
