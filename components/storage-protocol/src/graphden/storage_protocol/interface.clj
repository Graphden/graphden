(ns graphden.storage-protocol.interface
  "Protocol for storage implementations.

   Storage brings itself into sync with a DataSchema through the
   initialize function. It uses UUIDs to detect renames vs add/delete.

   Key concepts:
   - Storage reads the current state via StorageIntrospection methods
   - UUID mappings stored in _schema_metadata table enable rename detection
   - Destructive changes (removing entities/fields/enums) throw errors
   - Safe type widening (int→numeric, text→jsonb) is allowed

   The typical flow:
   1. Create storage instance (implementation-specific)
   2. Call (initialize storage schema) to sync with DataSchema
   3. Use storage for CRUD operations (future protocol methods)
   4. Call (close storage) when done"
  (:require
    [clojure.set :as set]))


(defprotocol Storage
  "Protocol for data storage backends."

  (initialize
    [this schema]
    "Initializes/migrates storage to match the given DataSchema.
     Uses UUID-based tracking to detect renames.

     Process:
     1. Read current storage state via StorageIntrospection methods
     2. Read stored metadata (_schema_metadata)
     3. Compute diff between schema and current state using UUIDs
     4. Apply changes (create tables, add columns, rename, create indexes)
     5. Update _schema_metadata

     Returns a map describing changes made:
     {:entities {:created [:name ...] :renamed {old-name new-name ...}}
      :fields {:created [{:entity e :field f} ...] :renamed [...]}
      :enums {:created [:name ...] :renamed {...}}
      :enum-values {:created [{:enum e :value v} ...]}
      :constraints {:created [{:entity e :constraint c} ...]}}

     Throws ExceptionInfo on destructive changes:
     - Removed entity (UUID in metadata but not in schema)
     - Removed field
     - Removed enum
     - Removed enum value
     - Incompatible type change (e.g., text→int)")

  (close
    [this]
    "Releases storage resources (connections, handles).
     Should be called when storage is no longer needed.
     Implementations should be idempotent (safe to call multiple times)."))


(defprotocol StorageIntrospection
  "Protocol for reading storage state.
   Used by initialize to compute diff with target schema."

  (current-entities
    [this]
    "Returns set of entity names currently in storage.
     Does not include internal tables (like _schema_metadata).")

  (current-fields
    [this entity-name]
    "Returns map of field definitions for entity, or nil if entity not found.
     Shape: {field-name {:type :text :nullable? false} ...}
     The implicit :id field is not included.")

  (current-enums
    [this]
    "Returns set of enum type names in storage.")

  (current-enum-values
    [this enum-name]
    "Returns set of keyword values for enum, or nil if enum not found.")

  (schema-metadata
    [this]
    "Returns stored UUID→name mappings, or nil if not initialized.
     Shape: {:entities {uuid entity-name ...}
             :fields {uuid {:entity entity-name :field field-name} ...}
             :enums {uuid enum-name ...}
             :enum-values {uuid {:enum enum-name :value value-keyword} ...}}"))


;; === Type compatibility ===

(def type-widening
  "Map of type→set of types it can safely widen to.
   Widening means no data loss is possible.
   Types not in this map cannot be widened (only same-type allowed)."
  {:int #{:numeric :text :jsonb}
   :bool #{:text :jsonb}
   :numeric #{:text :jsonb}
   :text #{:jsonb}
   :uuid #{:text}
   :timestamptz #{:text}})


(def type-equivalents
  "Types that are equivalent (stored the same way in storage).
   Used for comparison to avoid false 'incompatible type' errors."
  #{#{:uuid :ref}    ; :ref is stored as UUID
    #{:jsonb :union}})  ; :union is stored as JSONB


(defn- types-equivalent?
  "Returns true if two types are equivalent (stored the same way)."
  [t1 t2]
  (some #(and (contains? % t1) (contains? % t2)) type-equivalents))


(defn safe-type-change?
  "Returns true if changing from old-type to new-type is safe.
   Safe changes are: same type, equivalent types, or widening to a more general type."
  [old-type new-type]
  (or (= old-type new-type)
      (types-equivalent? old-type new-type)
      (contains? (get type-widening old-type #{}) new-type)))


(defn safe-nullable-change?
  "Returns true if changing nullable is safe.
   Safe changes:
   - Same value (no change)
   - false→true (allowing nulls is safe)
   Unsafe changes:
   - true→false (existing nulls would become invalid)"
  [old-nullable? new-nullable?]
  (or (= old-nullable? new-nullable?)
      (and (false? old-nullable?) (true? new-nullable?))))


;; === Destructive change detection utilities ===

(defn check-removed!
  "Checks for items removed from schema and throws if any found.

   Arguments:
   - item-type: string describing the type (e.g., \"entities\", \"fields\")
   - old-uuids: set of UUIDs from existing metadata
   - new-uuids: set of UUIDs from new schema
   - get-name-fn: function that takes UUID and returns human-readable name/info

   Throws ExceptionInfo with :type :destructive-change if items were removed."
  [item-type old-uuids new-uuids get-name-fn]
  (let [removed (set/difference old-uuids new-uuids)]
    (when (seq removed)
      (throw (ex-info (str "Destructive change: " item-type " removed")
                      {:type :destructive-change
                       :removed (vec (map get-name-fn removed))})))))


(defn check-type-change!
  "Checks that a field type change is safe and throws if not.

   Arguments:
   - entity-name: keyword name of the entity
   - field-name: keyword name of the field
   - old-type: the current type in storage
   - new-type: the new type in schema

   Throws ExceptionInfo with :type :destructive-change if type change is unsafe."
  [entity-name field-name old-type new-type]
  (when (and old-type
             (not (safe-type-change? old-type new-type)))
    (throw (ex-info "Destructive change: incompatible type change"
                    {:type :destructive-change
                     :entity entity-name
                     :field field-name
                     :old-type old-type
                     :new-type new-type}))))


(defn check-nullable-change!
  "Checks that a nullable change is safe and throws if not.

   Arguments:
   - entity-name: keyword name of the entity
   - field-name: keyword name of the field
   - old-nullable?: the current nullable value in storage
   - new-nullable?: the new nullable value in schema

   Throws ExceptionInfo with :type :destructive-change if nullable change is unsafe
   (i.e., changing from nullable to non-nullable)."
  [entity-name field-name old-nullable? new-nullable?]
  (when (and (some? old-nullable?)
             (not (safe-nullable-change? old-nullable? new-nullable?)))
    (throw (ex-info "Destructive change: field changed from nullable to non-nullable"
                    {:type :destructive-change
                     :entity entity-name
                     :field field-name
                     :old-nullable? old-nullable?
                     :new-nullable? new-nullable?}))))
