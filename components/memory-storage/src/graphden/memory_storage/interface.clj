(ns graphden.memory-storage.interface
  "Public interface for in-memory storage implementation."
  (:require
    [graphden.memory-storage.core :as core]))


(defn create-storage
  "Creates a new in-memory storage instance.

   The storage implements both Storage and StorageIntrospection protocols.
   Use sp/initialize to sync with a DataSchema, then use storage for operations.

   Example:
     (require '[graphden.storage-protocol.interface :as sp])
     (let [storage (create-storage)]
       (sp/initialize storage my-schema)
       ;; use storage...
       (sp/close storage))"
  []
  (core/create-storage))
