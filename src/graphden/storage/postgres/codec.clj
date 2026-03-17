(ns graphden.storage.postgres.codec
  "Value codec for PostgreSQL storage.
   Handles JSONB, enum, and other type conversions."
  (:require
    [cheshire.core :as json]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp])
  (:import
    (com.fasterxml.jackson.core
      JsonParseException)
    (java.sql
      Timestamp)
    (java.time
      Instant)
    (org.postgresql.util
      PGobject)))


;; === Known enum values ===
;; When field-spec is not available, we need to detect enum values heuristically.
;; This set contains known value_kind enum values from graph-data-schema.

(def ^:private known-value-kind-values
  "Known values of the value_kind enum (SQL snake_case form)."
  #{"null" "uuid" "text" "int" "bool" "numeric" "timestamptz" "jsonb" "bytes" "any" "fn"})


(defn- known-enum-value?
  "Returns true if string is a known enum value.
   Used for heuristic detection when field-spec is not available."
  [s]
  (contains? known-value-kind-values s))


;; === Low-level encoding/decoding ===

(defn- ->pgobject
  "Creates a PGobject with given type and value.
   Common helper to reduce duplication."
  [pg-type pg-value]
  (doto (PGobject.)
    (PGobject/.setType pg-type)
    (PGobject/.setValue pg-value)))


(defn- value->jsonb
  "Wraps a value as JSONB PGobject for PostgreSQL."
  [v]
  (->pgobject "jsonb" (json/generate-string v)))


(defn- seqs-to-vectors
  "Recursively converts lazy seqs to vectors.
   Cheshire returns lazy seqs for JSON arrays, but we need vectors
   for proper conj behavior (append vs prepend)."
  [x]
  (cond
    (map? x) (persistent! (reduce-kv (fn [m k v] (assoc! m k (seqs-to-vectors v))) (transient {}) x))
    (sequential? x) (mapv seqs-to-vectors x)
    :else x))

(defn- parse-jsonb
  "Parses JSONB PGobject value to Clojure data.
   Returns nil for null values.
   Converts all arrays to vectors for proper conj behavior."
  [pg-value]
  (when pg-value
    (try
      (seqs-to-vectors (json/parse-string pg-value true))
      (catch JsonParseException e
        (throw (ex-info "Failed to parse JSONB value"
                        {:type :parse-error/jsonb
                         :raw-value (if (> (count pg-value) 100)
                                      (str (subs pg-value 0 100) "...")
                                      pg-value)
                         :cause (Throwable/.getMessage e)}
                        e))))))


(defn- value->enum
  "Converts keyword to PostgreSQL enum PGobject."
  [v enum-name]
  (->pgobject (util/kw->snake-case enum-name)
              (util/enum-value->sql v)))


(defn- instant->timestamp
  "Converts java.time.Instant to java.sql.Timestamp for PostgreSQL."
  [^Instant instant]
  (Timestamp/from instant))


(defn- parse-pgobject
  "Parses PGobject value based on its type.
   Returns nil for null PGobject values."
  [v]
  (let [pg-type (PGobject/.getType v)
        pg-value (PGobject/.getValue v)]
    (when pg-value
      (if (= pg-type "jsonb")
        (parse-jsonb pg-value)
        ;; For enums and other types, convert back to keyword
        (util/sql->enum-value pg-value)))))


;; === Known JSONB columns fallback ===
;; When field metadata is not available, these columns are treated as JSONB.

(def ^:private fallback-jsonb-columns
  "Columns always treated as JSONB even without field metadata.
   :value - Used in arg_value entity for polymorphic value storage."
  #{:value})


(def ^:private fallback-timestamptz-columns
  "Columns always treated as TIMESTAMPTZ even without field metadata.
   :created-at - Used in versioned entities (branch, *-version) for timestamps."
  #{:created-at})


;; === PostgresValueCodec implementation ===

(defrecord PostgresValueCodec
  []

  sp/StorageValueCodec

  (encode-value
    [_this value field-spec]
    (when (some? value)
      (let [field-type (:type field-spec)]
        (case field-type
          (:jsonb :union)
          (if (instance? PGobject value)
            value
            (value->jsonb value))

          :enum
          (if (keyword? value)
            (value->enum value (:enum-name field-spec))
            value)

          :timestamptz
          (if (instance? Instant value)
            (instant->timestamp value)
            value)

          ;; Other types pass through unchanged
          value))))


  (decode-value
    [_this value field-spec]
    (cond
      ;; PGobject (JSONB, custom types)
      (instance? PGobject value)
      (parse-pgobject value)

      ;; Enum field: PostgreSQL may return enum as plain string
      ;; Convert back to keyword when field-spec indicates enum
      ;; Also handle known enum values when field-spec is not available
      (and (string? value)
           (or (= :enum (:type field-spec))
               (and (nil? field-spec) (known-enum-value? value))))
      (util/sql->enum-value value)

      ;; Other values pass through unchanged
      :else value))


  (encode-row
    [this row field-specs]
    (sp/generic-encode-row
      (partial sp/encode-value this)
      row
      field-specs
      {:key-transform (comp keyword util/kw->snake-case)
       :fallback-specs (merge
                         (into {} (map (fn [k] [k {:type :jsonb}]) fallback-jsonb-columns))
                         (into {} (map (fn [k] [k {:type :timestamptz}]) fallback-timestamptz-columns)))}))


  (decode-row
    [this row field-specs]
    (sp/generic-decode-row
      (partial sp/decode-value this)
      row
      field-specs
      {:key-transform (fn [col-key] (util/snake->kw (name col-key)))})))


(defn create-codec
  "Creates a PostgreSQL value codec instance."
  []
  (->PostgresValueCodec))


(def ^:private default-codec (delay (create-codec)))


(defn encode-value
  "Encodes a single value using the default codec.
   Uses fallback specs for known JSONB/TIMESTAMPTZ columns when field-spec is nil."
  [value field-spec]
  (let [effective-spec (or field-spec
                           ;; Apply fallback if needed - check if value looks like it needs special handling
                           (cond
                             (map? value) {:type :jsonb}
                             (instance? Instant value) {:type :timestamptz}
                             :else nil))]
    (sp/encode-value @default-codec value effective-spec)))


(defn encode-row
  "Encodes a row using the default codec."
  [row field-specs]
  (sp/encode-row @default-codec row field-specs))


(defn decode-row
  "Decodes a row using the default codec."
  [row field-specs]
  (sp/decode-row @default-codec row field-specs))


(defn row->entity
  "Converts a JDBC result row to an entity map.
   Decodes all values using the default codec.
   Returns nil for nil input."
  ([row]
   (row->entity row nil))
  ([row field-specs]
   (when row
     (decode-row row field-specs))))
