(ns graphden.schema.fields.types
  "Centralized definition of supported field types.
   This is the single source of truth for type information across all backends.")


;; === SQL Identifier Limits ===
;;
;; PostgreSQL limits unquoted identifiers to 63 characters.
;; This constant is used for validation in data schema builders and storage.

(def max-identifier-length
  "Maximum length for SQL identifiers (PostgreSQL limit).
   Entity names, field names, enum values must fit within this limit."
  63)


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
   - :jsonb       - JSON data, any JSON-serializable value (scalars, arrays, objects)
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
   :jsonb       {:description "JSON data (any JSON-serializable value)"
                 :clojure-type 'Object}
   :bytes       {:description "Binary data"
                 :clojure-type 'bytes}
   :sequence    {:description "Ordered sequence — items live in binding-list-item rows under a list-typed binding"
                 :clojure-type 'clojure.lang.Sequential}
   :keyword     {:description "Clojure keyword (`:foo-bar`). Inside JSONB the codec stores it as the tagged carrier `{:_kw \"foo-bar\"}` (the old `\":foo\"`-string scheme collided with user strings and is retired — see storage/postgres/codec.clj). Slots typed `:keyword` accept bare keyword literals in fn-defs (no `:literal? true` escape)."
                 :clojure-type 'clojure.lang.Keyword}})


(def supported-types
  "Set of supported type keywords."
  (set (keys types)))


;; === Backend Mappings ===

(def type-mappings
  "Postgres column type per abstract type — REFERENCE data documenting
   how each type is stored (the DDL layer keeps its own runtime map in
   `storage/postgres/util`). The former :datomic/:memory columns were
   dropped 2026-08-15: those backends never existed in this repo and
   the only accessor parameterised by backend was itself dead."
  {:uuid        "UUID"
   :text        "TEXT"
   :int         "BIGINT"
   :bool        "BOOLEAN"
   :numeric     "NUMERIC"
   :timestamptz "TIMESTAMPTZ"
   :jsonb       "JSONB"
   :bytes       "BYTEA"
   ;; :sequence is a marker type — concrete items live in
   ;; `binding-list-item` rows under a list-typed binding. No column
   ;; needed for the sequence itself; JSONB for forward compat with
   ;; dumps.
   :sequence    "JSONB"
   ;; :keyword stored as TEXT for dedicated columns (`(str kw)` /
   ;; `(keyword (subs s 1))`); inside JSONB values the codec uses the
   ;; `{:_kw …}` carrier regardless of slot type.
   :keyword     "TEXT"
   ;; Special types for references
   :ref         "UUID"
   ;; :ref-many = many-to-many; junction table, public API returns a
   ;; vector of UUIDs. Field-spec MUST include :ref-entity.
   :ref-many    :junction
   :enum        :custom
   :union       "JSONB"})


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
   Used for comparison to avoid false 'incompatible type' errors — a
   migration that only flips a field between two members of the same group
   is a storage no-op (no column rewrite). Groups follow `type-mappings`'
   `:postgres` column type."
  #{#{:uuid :ref}              ; :ref stored as UUID
    #{:jsonb :union :sequence} ; :union / :sequence stored as JSONB
    #{:text :keyword}})        ; :keyword stored as TEXT (via `(str kw)`)


(defn types-equivalent?
  "Returns true if two types are equivalent (stored the same way)."
  [type-a type-b]
  ;; Fast path: same type is always equivalent
  (or (= type-a type-b)
      (some #(and (contains? % type-a) (contains? % type-b))
            type-equivalents)))


;; === Runtime Type Validators ===

(def type-validators
  "Runtime type validators for each abstract type.
   Each validator is a function that takes a value and returns true if valid.
   Used for validating user-provided arguments at runtime."
  {:uuid        uuid?
   :ref         uuid?
   :ref-many    #(and (sequential? %) (every? uuid? %))
   :fn          uuid?
   :int         int?
   :bool        boolean?
   :text        string?
   :numeric     number?
   :jsonb       (constantly true)  ; Any JSON-serializable value
   :bytes       bytes?
   :timestamptz #(or (instance? java.time.Instant %)
                     (instance? java.time.LocalDateTime %)
                     (instance? java.util.Date %))
   :enum        keyword?
   :union       (constantly true)   ; Union accepts any value
   :any         (constantly true)   ; Any accepts any value (polymorphic type)
   :sequence    sequential?         ; Sequence of items (Clojure vector / seq)
   :null        (constantly true)}) ; Null/void type


(defn valid-type?
  "Returns true if value matches the expected type.
   Returns true for unknown types (forward compatibility)."
  [type-kw value]
  (if-let [validator (get type-validators type-kw)]
    (validator value)
    true))  ; Unknown types are permissively accepted
