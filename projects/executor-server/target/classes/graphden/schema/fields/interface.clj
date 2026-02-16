(ns graphden.schema.fields.interface
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
   :union       (constantly true)   ; Union accepts any value
   :any         (constantly true)   ; Any accepts any value (polymorphic type)
   :null        (constantly true)}) ; Null/void type


(defn valid-type?
  "Returns true if value matches the expected type.
   Returns true for unknown types (forward compatibility)."
  [type-kw value]
  (if-let [validator (get type-validators type-kw)]
    (validator value)
    true))  ; Unknown types are permissively accepted


;; === Custom Type Registry ===
;;
;; Allows registering custom field types at runtime.
;; Useful for domain-specific types that need special encoding/decoding.

;; Registry for custom field types registered via plugins.
;; Structure: {type-kw {:validator fn, :encoder fn, :decoder fn, :backend-mappings {...}}}
(defonce ^:private custom-types-registry (atom {}))


(defn register-custom-type!
  "Registers a custom field type with encoder/decoder/validator.

   Arguments:
   - type-kw: Keyword name for the custom type (e.g., :email, :phone)
   - opts: Map with keys:
     - :validator - (fn [value] -> boolean) - validates values of this type
     - :encoder - (fn [value] -> storage-value) - converts to storage format
     - :decoder - (fn [storage-value] -> value) - converts from storage format
     - :backend-mappings - (optional) Map of {:postgres \"TYPE\" :datomic :db.type/...}
     - :description - (optional) Human-readable description

   Example:
   ```clojure
   (register-custom-type! :email
     {:validator #(and (string? %) (re-matches #\".+@.+\\..+\" %))
      :encoder identity
      :decoder identity
      :backend-mappings {:postgres \"TEXT\" :datomic :db.type/string :memory :any}
      :description \"Email address\"})
   ```

   Returns nil."
  [type-kw {:keys [validator encoder decoder backend-mappings description]
            :or {validator (constantly true)
                 encoder identity
                 decoder identity
                 backend-mappings {:postgres "TEXT" :datomic :db.type/string :memory :any}
                 description "Custom type"}}]
  (when-not (keyword? type-kw)
    (throw (ex-info "Custom type key must be a keyword"
                    {:type :invalid-custom-type
                     :type-kw type-kw})))
  (when (contains? supported-types type-kw)
    (throw (ex-info "Cannot override built-in type"
                    {:type :invalid-custom-type
                     :type-kw type-kw
                     :built-in-types supported-types})))
  (swap! custom-types-registry assoc type-kw
         {:validator validator
          :encoder encoder
          :decoder decoder
          :backend-mappings backend-mappings
          :description description})
  nil)


(defn unregister-custom-type!
  "Removes a custom field type from the registry.
   Returns nil."
  [type-kw]
  (swap! custom-types-registry dissoc type-kw)
  nil)


(defn get-custom-type
  "Returns custom type spec or nil if not registered."
  [type-kw]
  (get @custom-types-registry type-kw))


(defn custom-type?
  "Returns true if type-kw is a registered custom type."
  [type-kw]
  (contains? @custom-types-registry type-kw))


(defn all-custom-types
  "Returns set of all registered custom type keywords."
  []
  (set (keys @custom-types-registry)))


(defn all-supported-types
  "Returns set of all supported types (built-in + custom)."
  []
  (into supported-types (all-custom-types)))


(defn get-type-validator
  "Returns validator function for type (built-in or custom).
   Returns (constantly true) for unknown types."
  [type-kw]
  (or (get type-validators type-kw)
      (:validator (get-custom-type type-kw))
      (constantly true)))


(defn get-type-encoder
  "Returns encoder function for custom type, or identity for built-in types."
  [type-kw]
  (if-let [custom (get-custom-type type-kw)]
    (:encoder custom)
    identity))


(defn get-type-decoder
  "Returns decoder function for custom type, or identity for built-in types."
  [type-kw]
  (if-let [custom (get-custom-type type-kw)]
    (:decoder custom)
    identity))


(defn get-backend-mapping
  "Returns backend type mapping for a type (built-in or custom).

   Arguments:
   - type-kw: Type keyword
   - backend: Backend keyword (:postgres, :datomic, :memory)

   Returns the backend-specific type or nil."
  [type-kw backend]
  (or (get-in type-mappings [type-kw backend])
      (get-in (get-custom-type type-kw) [:backend-mappings backend])))
