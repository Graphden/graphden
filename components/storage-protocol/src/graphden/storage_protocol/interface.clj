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
    [clojure.set :as set]
    [graphden.data-schema-protocol.interface :as ds]))


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
   Used by initialize to compute diff with target schema.

   Nil return semantics:
   - current-entities: returns empty set if storage is empty, never nil
   - current-fields: returns nil if entity doesn't exist, empty map if no fields
   - current-enums: returns empty set if no enums, never nil
   - current-enum-values: returns nil if enum doesn't exist
   - schema-metadata: returns nil if storage not yet initialized"

  (current-entities
    [this]
    "Returns set of entity names currently in storage.
     Does not include internal tables (like _schema_metadata).
     Returns empty set if storage is empty, never nil.")

  (current-fields
    [this entity-name]
    "Returns map of field definitions for entity.
     Shape: {field-name {:type :text :nullable? false} ...}
     The implicit :id field is not included.
     Returns nil if entity doesn't exist in storage.")

  (current-enums
    [this]
    "Returns set of enum type names in storage.
     Returns empty set if no enums defined, never nil.")

  (current-enum-values
    [this enum-name]
    "Returns set of keyword values for enum.
     Returns nil if enum doesn't exist in storage.")

  (schema-metadata
    [this]
    "Returns stored UUID→name mappings.
     Shape: {:entities {uuid entity-name ...}
             :fields {uuid {:entity entity-name :field field-name
                            :type field-type :nullable? bool} ...}
             :enums {uuid enum-name ...}
             :enum-values {uuid {:enum enum-name :value value-keyword} ...}}
     Returns nil if storage has not been initialized yet."))


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


(defn types-equivalent?
  "Returns true if two types are equivalent (stored the same way).
   For example, :ref is stored as :uuid, and :union is stored as :jsonb.
   This is used to avoid false 'incompatible type' errors during migration."
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


;; === Schema diff utilities ===
;; These functions compute changes between old metadata and new schema.
;; They are shared across all storage implementations.


(defn- collect-fields-meta
  "Collects field metadata for all entities."
  [schema]
  (into {}
        (mapcat (fn [entity-name]
                  (map (fn [[field-name field-spec]]
                         [(:uuid field-spec)
                          {:entity entity-name :field field-name}])
                       (ds/entity-fields schema entity-name)))
                (ds/entities schema))))


(defn- collect-enum-values-meta
  "Collects enum value metadata for all enums."
  [enums-data]
  (into {}
        (mapcat (fn [[enum-name {:keys [values]}]]
                  (map (fn [[value-kw value-uuid]]
                         [value-uuid {:enum enum-name :value value-kw}])
                       values))
                enums-data)))


(defn- collect-created-fields
  "Collects created fields info for changes report."
  [schema]
  (vec (mapcat (fn [e]
                 (map (fn [[f _]] {:entity e :field f})
                      (ds/entity-fields schema e)))
               (ds/entities schema))))


(defn- collect-created-enum-values
  "Collects created enum values info for changes report."
  [schema]
  (vec (mapcat (fn [[enum-name {:keys [values]}]]
                 (map (fn [[v _]] {:enum enum-name :value v})
                      values))
               (ds/enums schema))))


(defn- collect-field-uuids
  "Collects all field UUIDs from schema."
  [schema]
  (set (mapcat (fn [e]
                 (map (fn [[_ spec]] (:uuid spec))
                      (ds/entity-fields schema e)))
               (ds/entities schema))))


(defn- collect-enum-value-uuids
  "Collects all enum value UUIDs from schema."
  [schema]
  (set (mapcat (fn [[_ {:keys [values]}]]
                 (map second values))
               (ds/enums schema))))


(defn build-metadata-from-schema
  "Builds metadata structure from DataSchema for first-time initialization.
   Returns: {:entities {uuid->name}
             :fields {uuid->{:entity name :field name}}
             :enums {uuid->name}
             :enum-values {uuid->{:enum name :value kw}}}"
  [schema]
  (let [entities-meta (into {}
                            (map (fn [entity-name]
                                   [(ds/entity-uuid schema entity-name) entity-name])
                                 (ds/entities schema)))
        fields-meta (collect-fields-meta schema)
        enums-data (ds/enums schema)
        enums-meta (into {}
                         (map (fn [[enum-name {:keys [uuid]}]]
                                [uuid enum-name])
                              enums-data))
        enum-values-meta (collect-enum-values-meta enums-data)]
    {:entities entities-meta
     :fields fields-meta
     :enums enums-meta
     :enum-values enum-values-meta}))


(defn build-first-init-changes
  "Builds the changes map for first-time initialization.
   All entities, fields, enums, and enum-values are marked as created."
  [schema]
  {:entities {:created (vec (ds/entities schema)) :renamed {}}
   :fields {:created (collect-created-fields schema) :renamed []}
   :enums {:created (vec (keys (ds/enums schema))) :renamed {}}
   :enum-values {:created (collect-created-enum-values schema)}})


(defn check-all-removals!
  "Checks for any destructive removals (entities, fields, enums, enum-values).
   Throws on first removal found."
  [old-metadata schema]
  ;; Check entities
  (let [old-entity-uuids (set (keys (:entities old-metadata)))
        new-entity-uuids (set (map #(ds/entity-uuid schema %) (ds/entities schema)))]
    (check-removed! "entities" old-entity-uuids new-entity-uuids
                    #(get (:entities old-metadata) %)))
  ;; Check fields
  (let [old-field-uuids (set (keys (:fields old-metadata)))
        new-field-uuids (collect-field-uuids schema)]
    (check-removed! "fields" old-field-uuids new-field-uuids
                    #(get (:fields old-metadata) %)))
  ;; Check enums
  (let [old-enum-uuids (set (keys (:enums old-metadata)))
        new-enum-uuids (set (map (fn [[_ {:keys [uuid]}]] uuid) (ds/enums schema)))]
    (check-removed! "enums" old-enum-uuids new-enum-uuids
                    #(get (:enums old-metadata) %)))
  ;; Check enum values
  (let [old-value-uuids (set (keys (:enum-values old-metadata)))
        new-value-uuids (collect-enum-value-uuids schema)]
    (check-removed! "enum values" old-value-uuids new-value-uuids
                    #(get (:enum-values old-metadata) %))))


(defn compute-entity-changes
  "Computes created and renamed entities.
   Returns {:created [entity-names...] :renamed {old-name new-name ...}}"
  [old-metadata schema]
  (let [old-uuid->name (:entities old-metadata)
        created (vec (for [entity-name (ds/entities schema)
                           :let [uuid (ds/entity-uuid schema entity-name)]
                           :when (not (contains? old-uuid->name uuid))]
                       entity-name))
        renamed (into {}
                      (for [entity-name (ds/entities schema)
                            :let [uuid (ds/entity-uuid schema entity-name)
                                  old-name (get old-uuid->name uuid)]
                            :when (and old-name (not= old-name entity-name))]
                        [old-name entity-name]))]
    {:created created :renamed renamed}))


(defn compute-field-changes
  "Computes created and renamed fields.
   Returns {:created [{:entity e :field f}...] :renamed [{:entity e :old-field o :new-field n}...]}"
  [old-metadata schema]
  (let [old-uuid->info (:fields old-metadata)
        created (vec (for [entity-name (ds/entities schema)
                           [field-name field-spec] (ds/entity-fields schema entity-name)
                           :let [uuid (:uuid field-spec)]
                           :when (not (contains? old-uuid->info uuid))]
                       {:entity entity-name :field field-name}))
        renamed (vec (for [entity-name (ds/entities schema)
                           [field-name field-spec] (ds/entity-fields schema entity-name)
                           :let [uuid (:uuid field-spec)
                                 old-info (get old-uuid->info uuid)]
                           :when (and old-info (not= (:field old-info) field-name))]
                       {:entity entity-name
                        :old-field (:field old-info)
                        :new-field field-name}))]
    {:created created :renamed renamed}))


(defn compute-enum-changes
  "Computes created and renamed enums.
   Returns {:created [enum-names...] :renamed {old-name new-name ...}}"
  [old-metadata schema]
  (let [old-uuid->name (:enums old-metadata)
        enums-data (ds/enums schema)
        created (vec (for [[enum-name {:keys [uuid]}] enums-data
                           :when (not (contains? old-uuid->name uuid))]
                       enum-name))
        renamed (into {}
                      (for [[enum-name {:keys [uuid]}] enums-data
                            :let [old-name (get old-uuid->name uuid)]
                            :when (and old-name (not= old-name enum-name))]
                        [old-name enum-name]))]
    {:created created :renamed renamed}))


(defn compute-enum-value-changes
  "Computes created enum values.
   Returns {:created [{:enum e :value v}...]}"
  [old-metadata schema]
  (let [old-uuid->info (:enum-values old-metadata)
        enums-data (ds/enums schema)
        created (vec (for [[enum-name {:keys [values]}] enums-data
                           [value-kw value-uuid] values
                           :when (not (contains? old-uuid->info value-uuid))]
                       {:enum enum-name :value value-kw}))]
    {:created created}))
