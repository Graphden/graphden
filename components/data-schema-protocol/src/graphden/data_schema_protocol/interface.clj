(ns graphden.data-schema-protocol.interface
  "Protocol for data schema definition.

   Data schema describes entities that can be stored in various storage backends
   (PostgreSQL, Datomic, in-memory, etc.). Each entity has:
   - A unique name
   - A set of fields with types
   - An implicit :id field of type :uuid

   Supported field types:
   - :uuid, :text, :int, :bool, :numeric, :timestamptz, :jsonb, :bytes
   - :ref - reference to another entity (foreign key), always points to :id field
   - :enum - enumeration type with a set of allowed values
   - :union - value can be one of several types, specified via :variants")


(defprotocol DataSchema
  "Protocol for working with data schema definitions."

  (entities
    [this]
    "Returns a sequence of entity names defined in this schema.")

  (entity-fields
    [this entity-name]
    "Returns a map of field definitions for the given entity.
     Each field is a map with :type and optional attributes like:
     - :nullable? - whether the field can be null (default: false)
     - :unique? - whether the field must be unique (default: false)
     - :ref-entity - for :ref type, the name of referenced entity
     - :variants - for :union type, a vector of variant type specs
     Example: {:name {:type :text :nullable? false :unique? true}
               :fn-schema-id {:type :ref :ref-entity :fn-schema}
               :value {:type :union
                       :variants [{:type :ref :ref-entity :fn}
                                  {:type :int}
                                  {:type :text}]}}")

  (enums
    [this]
    "Returns a map of enum definitions.
     Each enum is a map with :values containing allowed values.
     Example: {:value-kind {:values #{:null :bool :int :text}}}")

  (validate-entity
    [this entity-name data]
    "Validates data against the entity schema.
     Returns nil if valid, or a map with :errors if invalid."))


(defprotocol DataSchemaBuilder
  "Protocol for building data schemas programmatically."

  (add-enum
    [this enum-name values]
    "Adds an enum type definition. Returns updated schema builder.")

  (add-entity
    [this entity-name fields]
    "Adds an entity definition. Returns updated schema builder.
     Fields should be a map of field-name to field-spec.
     The :id field is automatically added.")

  (build
    [this]
    "Builds and returns the final DataSchema."))
