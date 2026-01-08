(ns graphden.postgres-storage.codec
  "Value codec for PostgreSQL storage.
   Handles JSONB, enum, and other type conversions."
  (:require
    [cheshire.core :as json]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (com.fasterxml.jackson.core
      JsonParseException)
    (org.postgresql.util
      PGobject)))


;; === Low-level encoding/decoding ===

(defn- value->jsonb
  "Wraps a value as JSONB PGobject for PostgreSQL."
  [v]
  (doto (PGobject.)
    (PGobject/.setType "jsonb")
    (PGobject/.setValue (json/generate-string v))))


(defn- parse-jsonb
  "Parses JSONB PGobject value to Clojure data.
   Returns nil for null values."
  [pg-value]
  (when pg-value
    (try
      (json/parse-string pg-value true)
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
  (doto (PGobject.)
    (PGobject/.setType (util/kw->snake-case enum-name))
    (PGobject/.setValue (util/enum-value->sql v))))


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

          ;; Other types pass through unchanged
          value))))


  (decode-value
    [_this value _field-spec]
    (if (instance? PGobject value)
      (parse-pgobject value)
      value))


  (encode-row
    [this row field-specs]
    (reduce-kv
      (fn [acc field-name value]
        (let [col-name (keyword (util/kw->snake-case field-name))
              field-spec (or (get field-specs field-name)
                             ;; Fallback for known JSONB columns
                             (when (contains? fallback-jsonb-columns field-name)
                               {:type :jsonb}))
              encoded (if field-spec
                        (sp/encode-value this value field-spec)
                        value)]
          (assoc acc col-name encoded)))
      {}
      row))


  (decode-row
    [this row field-specs]
    (reduce-kv
      (fn [acc col-key value]
        (let [field-name (util/snake->kw (name col-key))
              field-spec (get field-specs field-name)
              decoded (sp/decode-value this value field-spec)]
          (assoc acc field-name decoded)))
      {}
      row)))


(defn create-codec
  "Creates a PostgreSQL value codec instance."
  []
  (->PostgresValueCodec))


;; === Convenience functions for backward compatibility ===

(def ^:private default-codec (delay (create-codec)))


(defn encode-row
  "Encodes a row using the default codec."
  [row field-specs]
  (sp/encode-row @default-codec row field-specs))


(defn decode-row
  "Decodes a row using the default codec."
  [row field-specs]
  (sp/decode-row @default-codec row field-specs))
