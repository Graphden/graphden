(ns graphden.schema.malli.types
  "Field-type vocabulary for malli-data-schema's BUILD-time
   validation. (The per-entity value-validation pipeline that once
   lived alongside — jsonb-schema / malli-type-mapping /
   make-entity-schema / validate-entity — was excised 2026-08-15: it
   had zero production callers and had rotted against the codec's
   {:_kw …}/{:_set …} jsonb carriers. Runtime write validation is
   storage/protocol/crud_validation.)"
  (:require
    [graphden.schema.fields.types :as ft]))


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
