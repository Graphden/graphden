(ns graphden.versioned-graph-storage-memory.interface
  "In-memory storage pre-initialized with versioned-data-schema.

   Provides a convenient factory function for creating a memory storage
   that is already synced with the versioned data schema and wrapped
   with versioning support."
  (:require
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]
    [graphden.versioned-data-schema.interface :as vds]
    [graphden.versioned-storage.interface :as vs]))


(defn create-storage
  "Creates an in-memory storage initialized with versioned-data-schema
   and wrapped with versioning support.

   Options:
   - :branch-name — name of the initial branch (default: \"main\")

   Returns a VersionedStorage instance on the specified branch,
   ready for branch-aware CRUD operations.

   Example:
     (require '[graphden.storage-protocol.interface :as sp])
     (require '[graphden.versioned-storage.interface :as vs])
     (let [storage (create-storage)]
       ;; storage is on 'main' branch
       (sp/create-entity storage :fn {:name \"foo\" :fn-schema-id id})
       ;; create a feature branch
       (let [branch (vs/create-branch! storage \"feature\")]
         (vs/switch-branch storage (:id branch)))
       (sp/close storage))"
  ([]
   (create-storage {}))
  ([{:keys [branch-name] :or {branch-name "main"}}]
   (let [schema (vds/build-schema (mds/create-builder))
         base (-> (mem/create-storage)
                  (sp/initialize-with-cleanup! schema))
         main-storage (vs/wrap-with-versioning base)]
     (if (= branch-name "main")
       main-storage
       (let [branch (vs/create-branch! main-storage branch-name)]
         (vs/switch-branch main-storage (:id branch)))))))
