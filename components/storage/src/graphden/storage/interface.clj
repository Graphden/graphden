(ns graphden.storage.interface
  "Storage protocol - abstraction over different storage backends
   (in-memory, PostgreSQL, Datomic, etc.)")

;; Capabilities that a storage can have
(def capabilities
  {:crud "Basic CRUD operations"
   :batch "Batch operations"
   :transactions "ACID transactions"
   :indexes "Index support"
   :recursive-cte "Recursive CTE queries (SQL)"
   :graph-traverse "Native graph traversal (Datomic rules)"
   :refs "Referential integrity"
   :watch "Watch for changes"})

(defprotocol StorageInfo
  "Protocol for storage metadata and capabilities"

  (storage-type [this]
    "Returns storage type: :memory, :postgres, :datomic, etc.")

  (storage-capabilities [this]
    "Returns set of capabilities: #{:crud :indexes ...}"))

(defprotocol Storage
  "Protocol for data storage operations"

  (put [this entity-type id data]
    "Store entity, returns updated storage")

  (get-by-id [this entity-type id]
    "Get entity by id, returns entity or nil")

  (delete [this entity-type id]
    "Delete entity, returns updated storage")

  (update-entity [this entity-type id update-fn]
    "Update entity by applying update-fn, returns updated storage")

  (find-by [this entity-type field value]
    "Find entities where field equals value, returns seq of entities")

  (get-all [this entity-type]
    "Get all entities of type, returns seq of entities")

  (exists? [this entity-type id]
    "Check if entity exists, returns boolean"))

;; Wrapper functions
(defn put*
  "Store entity"
  [storage entity-type id data]
  (put storage entity-type id data))

(defn get-by-id*
  "Get entity by id"
  [storage entity-type id]
  (get-by-id storage entity-type id))

(defn delete*
  "Delete entity"
  [storage entity-type id]
  (delete storage entity-type id))

(defn update-entity*
  "Update entity"
  [storage entity-type id update-fn]
  (update-entity storage entity-type id update-fn))

(defn find-by*
  "Find entities by field value"
  [storage entity-type field value]
  (find-by storage entity-type field value))

(defn get-all*
  "Get all entities"
  [storage entity-type]
  (get-all storage entity-type))

(defn exists?*
  "Check if entity exists"
  [storage entity-type id]
  (exists? storage entity-type id))

(defn has-capability?
  "Check if storage has a capability"
  [storage capability]
  (contains? (storage-capabilities storage) capability))
