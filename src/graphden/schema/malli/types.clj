(ns graphden.schema.malli.types
  "Type definitions and mappings for malli-data-schema."
  (:require
    [graphden.schema.fields.types :as ft]))


(def ^:private jsonb-schema
  "Schema for JSON-compatible values (recursive)."
  [:schema {:registry {::json [:or
                               :nil
                               :boolean
                               :int
                               :double
                               :string
                               [:vector [:ref ::json]]
                               [:map-of :string [:ref ::json]]]}}
   [:ref ::json]])


(def malli-type-mapping
  "Mapping of field-types to malli schemas."
  {:uuid        :uuid
   :text        :string
   :int         :int
   :bool        :boolean
   :numeric     [:or :int :double]
   :timestamptz inst?
   :jsonb       jsonb-schema
   :bytes       bytes?
   :sequence    sequential?
   :keyword     :keyword})


(def known-field-types
  "All valid field types.
   Includes storage types from field-types plus semantic types:
   - :ref, :ref-many - reference types (single / many-to-many)
   - :enum, :union - structural types
   - :any - polymorphic type (accepts any value)
   - :fn - function reference type (stored as UUID)"
  (into #{:ref :ref-many :enum :union :any :fn} ft/supported-types))


(def known-constraint-types
  "All valid constraint types."
  #{:unique})
