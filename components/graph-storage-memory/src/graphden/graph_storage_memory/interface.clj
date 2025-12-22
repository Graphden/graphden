(ns graphden.graph-storage-memory.interface
  "In-memory storage pre-initialized with graph-data-schema.

   Provides a convenient factory function for creating a memory storage
   that is already synced with the graph data schema."
  (:require
    [graphden.graph-data-schema.interface :as graph]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


(defn create-storage
  "Creates an in-memory storage initialized with graph-data-schema.

   Returns a storage instance implementing Storage and StorageIntrospection
   protocols, ready for CRUD operations on graph entities (fn-schema, arg-schema,
   fn, arg-value).

   The storage is already initialized - no need to call sp/initialize.

   Example:
     (require '[graphden.storage-protocol.interface :as sp])
     (let [storage (create-storage)]
       ;; storage is ready to use
       (sp/current-entities storage)  ; => #{:fn-schema :arg-schema :fn :arg-value}
       ;; ... use storage ...
       (sp/close storage))"
  []
  (let [storage (mem/create-storage)
        schema (graph/build-schema (mds/create-builder))]
    (sp/initialize storage schema)
    storage))
