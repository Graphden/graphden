(ns graphden.data-schema-protocol.interface
  "Protocol for data schema definition.

   Data schema describes entities that can be stored in various storage backends
   (PostgreSQL, Datomic, in-memory, etc.). Each entity has:
   - A unique name (keyword)
   - A set of fields with types
   - An implicit :id field of type :uuid (primary key)
   - Optional constraints (e.g., unique indexes)

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

  (entity-fields
    [this entity-name]
    "Returns a map of field definitions for the given entity.
     Returns nil if entity is not found.

     Each field is a map with :type (required) and type-specific attributes.
     Allowed attributes per field type:

     Base types (:uuid, :text, :int, :bool, :numeric, :timestamptz, :jsonb, :bytes):
       - :type (required)
       - :nullable? (optional, default false)

     :ref (reference to another entity, always points to :id):
       - :type (required, must be :ref)
       - :ref-entity (required, keyword naming the referenced entity)
       - :nullable? (optional, default false)

     :enum (enumeration with predefined keyword values):
       - :type (required, must be :enum)
       - :enum-name (required, keyword naming the enum)
       - :nullable? (optional, default false)

     :union (value can be one of several types):
       - :type (required, must be :union)
       - :variants (required, vector of field specs without :nullable?)
       - :nullable? (optional, default false)

     Example: {:name {:type :text}
               :bio {:type :text :nullable? true}
               :role {:type :enum :enum-name :user-role}
               :manager-id {:type :ref :ref-entity :user}
               :value {:type :union
                       :variants [{:type :ref :ref-entity :fn}
                                  {:type :int}
                                  {:type :text}]}}")

  (enums
    [this]
    "Returns a map of enum definitions.
     Each enum is a map with :values containing a set of allowed keyword values.
     Example: {:user-role {:values #{:admin :user :guest}}}")

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
    [this enum-name values]
    "Adds an enum type definition. Returns updated schema builder.
     Values must be a non-empty collection of unique keyword values.")

  (add-entity
    [this entity-name fields]
    "Adds an entity definition. Returns updated schema builder.
     Entity name must be a keyword.
     Fields should be a map of field-name (keyword) to field-spec.
     Field name :id is reserved (implicit primary key).")

  (add-constraint
    [this entity-name constraint]
    "Adds a constraint to an entity. Returns updated schema builder.
     Constraint must have :type (e.g., :unique) and :fields (vector of field names).
     Example: {:type :unique :fields [:tenant-id :name]}")

  (build
    [this]
    "Builds and returns the final DataSchema.
     Validates all references and constraints before building.
     Throws ExceptionInfo if schema is invalid."))
