(ns graphden.field-types.interface
  "Centralized definition of supported field types.
   This is the single source of truth for type information across all backends.")


;; === Base Types ===

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
                 :clojure-type 'java.util.UUID}
   :text        {:description "Text/string value"
                 :clojure-type 'String
                 :max-length "unlimited"}
   :int         {:description "Integer number"
                 :clojure-type 'Long
                 :min Long/MIN_VALUE
                 :max Long/MAX_VALUE}
   :bool        {:description "Boolean true/false"
                 :clojure-type 'Boolean}
   :numeric     {:description "Numeric value (arbitrary precision)"
                 :clojure-type 'BigDecimal
                 :precision "arbitrary"}
   :timestamptz {:description "Timestamp with timezone"
                 :clojure-type 'java.time.Instant
                 :precision "microseconds"}
   :jsonb       {:description "JSON data (maps/vectors)"
                 :clojure-type '(or clojure.lang.IPersistentMap clojure.lang.IPersistentVector)}
   :bytes       {:description "Binary data"
                 :clojure-type 'bytes}})


(def supported-types
  "Set of supported type keywords."
  (set (keys types)))


;; === Backend Mappings ===

(def type-mappings
  "Complete type mapping reference for all storage backends.
   Keys are abstract types, values are maps of backend -> concrete type.

   Usage:
     (get-in type-mappings [:uuid :postgres]) ;=> \"UUID\"
     (get-in type-mappings [:int :datomic])   ;=> :db.type/long"
  {:uuid        {:postgres "UUID"        :datomic :db.type/uuid    :memory :any}
   :text        {:postgres "TEXT"        :datomic :db.type/string  :memory :any}
   :int         {:postgres "BIGINT"      :datomic :db.type/long    :memory :any}
   :bool        {:postgres "BOOLEAN"     :datomic :db.type/boolean :memory :any}
   :numeric     {:postgres "NUMERIC"     :datomic :db.type/bigdec  :memory :any}
   :timestamptz {:postgres "TIMESTAMPTZ" :datomic :db.type/instant :memory :any}
   :jsonb       {:postgres "JSONB"       :datomic :db.type/string  :memory :any}
   :bytes       {:postgres "BYTEA"       :datomic :db.type/bytes   :memory :any}
   ;; Special types for references
   :ref         {:postgres "UUID"        :datomic :db.type/ref     :memory :any}
   :enum        {:postgres :custom       :datomic :db.type/ref     :memory :any}
   :union       {:postgres "JSONB"       :datomic :db.type/string  :memory :any}})


;; === Type Widening Rules ===

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


;; === Type Equivalence ===

(def type-equivalents
  "Types that are equivalent (stored the same way in storage).
   Used for comparison to avoid false 'incompatible type' errors."
  #{#{:uuid :ref}    ; :ref is stored as UUID
    #{:jsonb :union}})  ; :union is stored as JSONB


(defn types-equivalent?
  "Returns true if two types are equivalent (stored the same way)."
  [type-a type-b]
  (some #(and (contains? % type-a) (contains? % type-b))
        type-equivalents))


;; === Runtime Type Validators ===

(def type-validators
  "Runtime type validators for each abstract type.
   Each validator is a function that takes a value and returns true if valid.
   Used for validating user-provided arguments at runtime."
  {:uuid        uuid?
   :ref         uuid?
   :fn          uuid?
   :int         int?
   :bool        boolean?
   :text        string?
   :numeric     number?
   :jsonb       #(or (map? %) (vector? %))
   :bytes       bytes?
   :timestamptz #(or (instance? java.time.Instant %)
                     (instance? java.time.LocalDateTime %)
                     (instance? java.util.Date %))
   :enum        keyword?
   :union       (constantly true)})  ; Union accepts any value


(defn valid-type?
  "Returns true if value matches the expected type.
   Returns true for unknown types (forward compatibility)."
  [type-kw value]
  (if-let [validator (get type-validators type-kw)]
    (validator value)
    true))  ; Unknown types are permissively accepted
