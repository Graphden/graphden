(ns graphden.data-schema-protocol.interface
  "Protocol for data schema definition.

   Data schema describes entities that can be stored in various storage backends
   (PostgreSQL, Datomic, in-memory, etc.). Each entity has:
   - A unique name (keyword)
   - A stable UUID (for tracking identity across renames)
   - A set of fields with types (each field has its own UUID)
   - An implicit :id field of type :uuid (primary key)
   - Optional constraints (e.g., unique indexes)

   UUIDs are used by storage implementations to track identity during migrations.
   When an entity/field/enum is renamed, the UUID stays the same, allowing
   storage to detect renames vs add/delete operations.

   Supported field types:
   - :uuid, :text, :int, :bool, :numeric, :timestamptz, :jsonb, :bytes
   - :ref - reference to another entity, always points to :id field
   - :enum - enumeration type with a set of allowed keyword values
   - :union - value can be one of several types, specified via :variants

   Constraints are metadata for storage implementations. They are not enforced
   by validate-entity (which validates a single entity in isolation), but should
   be enforced by storage backends (e.g., as database indexes).")


(defprotocol DataSchema
  "Protocol for working with data schema definitions."

  (entities
    [this]
    "Returns a sequence of entity names defined in this schema.")

  (entity-uuid
    [this entity-name]
    "Returns the UUID of the given entity, or nil if entity not found.")

  (entity-fields
    [this entity-name]
    "Returns a map of field definitions for the given entity.
     Returns nil if entity is not found.

     Each field is a map with :uuid (required), :type (required), and
     type-specific attributes. Allowed attributes per field type:

     Base types (:uuid, :text, :int, :bool, :numeric, :timestamptz, :jsonb, :bytes):
       - :uuid (required, stable identifier)
       - :type (required)
       - :nullable? (optional, default false)

     :ref (reference to another entity, always points to :id):
       - :uuid (required, stable identifier)
       - :type (required, must be :ref)
       - :ref-entity (required, keyword naming the referenced entity)
       - :nullable? (optional, default false)

     :enum (enumeration with predefined keyword values):
       - :uuid (required, stable identifier)
       - :type (required, must be :enum)
       - :enum-name (required, keyword naming the enum)
       - :nullable? (optional, default false)

     :union (value can be one of several types):
       - :uuid (required, stable identifier)
       - :type (required, must be :union)
       - :variants (required, vector of field specs without :nullable? or :uuid)
       - :nullable? (optional, default false)

     Example: {:name {:uuid #uuid \"...\" :type :text}
               :bio {:uuid #uuid \"...\" :type :text :nullable? true}
               :role {:uuid #uuid \"...\" :type :enum :enum-name :user-role}
               :manager-id {:uuid #uuid \"...\" :type :ref :ref-entity :user}
               :value {:uuid #uuid \"...\"
                       :type :union
                       :variants [{:type :ref :ref-entity :fn}
                                  {:type :int}
                                  {:type :text}]}}")

  (enums
    [this]
    "Returns a map of enum definitions.
     Each enum is a map with:
       - :uuid - stable identifier for the enum
       - :values - map of value keyword to value UUID

     Example: {:user-role {:uuid #uuid \"...\"
                           :values {:admin #uuid \"...\"
                                    :user #uuid \"...\"
                                    :guest #uuid \"...\"}}}")

  (enum-uuid
    [this enum-name]
    "Returns the UUID of the given enum, or nil if enum not found.")

  (validate-entity
    [this entity-name data]
    "Validates data against the entity schema.
     Returns nil if valid, or a map with :errors key if invalid.
     Returns {:errors {:entity [\"Unknown entity: ...\"]}} for unknown entities.

     Note: This validates structure and types only. Constraints (like uniqueness)
     are not validated here - they require access to all stored data.")

  (entity-constraints
    [this entity-name]
    "Returns a vector of constraints for the given entity.
     Returns empty vector [] if entity has no constraints or is unknown.

     Each constraint is a map with :type and :fields.
     Currently supported constraint types: :unique

     Example: [{:type :unique :fields [:email]}
               {:type :unique :fields [:tenant-id :name]}]"))


(defprotocol DataSchemaBuilder
  "Protocol for building data schemas programmatically."

  (add-enum
    [this enum-name enum-uuid values]
    "Adds an enum type definition. Returns updated schema builder.
     - enum-name: keyword naming the enum
     - enum-uuid: UUID for the enum (stable across renames)
     - values: vector of {:uuid ... :value ...} maps

     Example: (add-enum builder :status #uuid \"...\"
                [{:uuid #uuid \"...\" :value :active}
                 {:uuid #uuid \"...\" :value :inactive}])")

  (add-entity
    [this entity-name entity-uuid fields]
    "Adds an entity definition. Returns updated schema builder.
     - entity-name: keyword (must not conflict with existing)
     - entity-uuid: UUID for the entity (stable across renames)
     - fields: map of field-name to field-spec (each spec must include :uuid)

     Field name :id is reserved (implicit primary key).

     Example: (add-entity builder :user #uuid \"...\"
                {:name {:uuid #uuid \"...\" :type :text}
                 :email {:uuid #uuid \"...\" :type :text :nullable? true}})")

  (add-constraint
    [this entity-name constraint]
    "Adds a constraint to an entity. Returns updated schema builder.
     Constraint must have :type (e.g., :unique) and :fields (vector of field names).
     Example: {:type :unique :fields [:tenant-id :name]}")

  (build
    [this]
    "Builds and returns the final DataSchema.
     Validates all references, constraints, and UUID uniqueness before building.
     Throws ExceptionInfo if schema is invalid."))
