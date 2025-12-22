(ns graphden.graph-storage-datomic.interface
  "Datahike storage pre-initialized with graph-data-schema.

   Provides a convenient factory function for creating a Datahike storage
   that is already synced with the graph data schema."
  (:require
    [graphden.datomic-storage.interface :as dat]
    [graphden.graph-data-schema.interface :as graph]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]))


(defn create-storage
  "Creates a Datahike storage initialized with graph-data-schema.

   Options:
   - :db-name - database name (default: auto-generated unique name)

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
  ([]
   (create-storage {}))
  ([opts]
   (let [db-name (or (:db-name opts)
                     (str "graph-" (System/currentTimeMillis) "-" (rand-int 10000)))
         storage (dat/create-storage {:db-name db-name})
         schema (graph/build-schema (mds/create-builder))]
     (sp/initialize storage schema)
     storage)))
