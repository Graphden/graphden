(ns graphden.field-types.interface
  "Centralized definition of supported field types.")


(def types
  "Supported field types with metadata.
   Each implementation (malli-data-schema, storage, etc.) must support these types."
  {:uuid        {:description "UUID identifier"}
   :text        {:description "Text/string value"}
   :int         {:description "Integer number"}
   :bool        {:description "Boolean true/false"}
   :numeric     {:description "Numeric value (int or double)"}
   :timestamptz {:description "Timestamp with timezone"}
   :jsonb       {:description "JSON data"}
   :bytes       {:description "Binary data"}})


(def supported-types
  "Set of supported type keywords."
  (set (keys types)))
