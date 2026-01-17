(ns graphden.storage-protocol.field-types
  "Canonical field types supported by all storage backends.

   Each backend maps these types to their native types:
   - PostgreSQL: uuid→UUID, text→TEXT, int→BIGINT, etc.
   - Datomic: uuid→:db.type/uuid, text→:db.type/string, etc.
   - Memory: stored as-is with Clojure native types")


(def canonical-field-types
  "Set of canonical field types supported by all storage backends.
   - :uuid - UUID/GUID values
   - :text - Variable-length text strings
   - :int - 64-bit integers (BIGINT)
   - :bool - Boolean true/false
   - :numeric - Arbitrary precision decimal numbers
   - :timestamptz - Timestamp with timezone
   - :jsonb - JSON data (stored as native JSON or EDN string)
   - :bytes - Binary data
   - :ref - Reference to another entity (stored as UUID)
   - :enum - Enumerated value (backend-specific storage)
   - :union - Union type (stored as JSON/EDN)"
  #{:uuid :text :int :bool :numeric :timestamptz :jsonb :bytes :ref :enum :union})


(defn canonical-type?
  "Returns true if the type keyword is a canonical field type."
  [type-kw]
  (contains? canonical-field-types type-kw))


(def type-category
  "Categorizes field types for common handling patterns.
   - :primitive - Simple scalar values
   - :reference - References to other entities
   - :complex - Structured data types"
  {:uuid        :primitive
   :text        :primitive
   :int         :primitive
   :bool        :primitive
   :numeric     :primitive
   :timestamptz :primitive
   :bytes       :primitive
   :jsonb       :complex
   :ref         :reference
   :enum        :primitive
   :union       :complex})


(defn reference-type?
  "Returns true if the field type is a reference type (:ref)."
  [type-kw]
  (= :reference (get type-category type-kw)))


(defn complex-type?
  "Returns true if the field type is a complex type (:jsonb, :union)."
  [type-kw]
  (= :complex (get type-category type-kw)))
