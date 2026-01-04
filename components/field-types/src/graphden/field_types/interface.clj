(ns graphden.field-types.interface
  "Centralized definition of supported field types.")


(def types
  "Supported field types with metadata.
   Each implementation (malli-data-schema, storage, etc.) must support these types.

   Type constraints:
   - :uuid        - 128-bit UUID (java.util.UUID), version 4 random recommended
   - :text        - Unlimited length string, UTF-8 encoded
   - :int         - 64-bit signed integer (Long), range: -9223372036854775808 to 9223372036854775807
   - :bool        - Boolean true/false only
   - :numeric     - Arbitrary precision number (BigDecimal in Clojure, NUMERIC in PostgreSQL)
   - :timestamptz - Timestamp with timezone, microsecond precision (java.time.Instant)
   - :jsonb       - JSON data, maps and vectors only (no raw scalars at top level)
   - :bytes       - Binary data (byte array), no size limit but memory-constrained"
  {:uuid        {:description "UUID identifier"
                 :clojure-type 'java.util.UUID
                 :pg-type "UUID"}
   :text        {:description "Text/string value"
                 :clojure-type 'String
                 :pg-type "TEXT"
                 :max-length "unlimited"}
   :int         {:description "Integer number"
                 :clojure-type 'Long
                 :pg-type "BIGINT"
                 :min Long/MIN_VALUE
                 :max Long/MAX_VALUE}
   :bool        {:description "Boolean true/false"
                 :clojure-type 'Boolean
                 :pg-type "BOOLEAN"}
   :numeric     {:description "Numeric value (arbitrary precision)"
                 :clojure-type 'BigDecimal
                 :pg-type "NUMERIC"
                 :precision "arbitrary"}
   :timestamptz {:description "Timestamp with timezone"
                 :clojure-type 'java.time.Instant
                 :pg-type "TIMESTAMPTZ"
                 :precision "microseconds"}
   :jsonb       {:description "JSON data (maps/vectors)"
                 :clojure-type '(or clojure.lang.IPersistentMap clojure.lang.IPersistentVector)
                 :pg-type "JSONB"}
   :bytes       {:description "Binary data"
                 :clojure-type 'bytes
                 :pg-type "BYTEA"}})


(def supported-types
  "Set of supported type keywords."
  (set (keys types)))
