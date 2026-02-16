(ns graphden.storage.age.codec
  "Value codec for AGE storage.
   Handles JSONB, enum, and other type conversions."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.storage.protocol.interface :as sp])
  (:import
    (com.fasterxml.jackson.core
      JsonParseException)
    (java.sql
      Timestamp)
    (java.time
      Instant)
    (org.postgresql.util
      PGobject)))


;; === Naming utilities ===

(defn kw->snake-case
  "Converts a keyword to snake_case string."
  [kw]
  (-> (name kw)
      (str/replace "-" "_")))


(defn snake->kw
  "Converts a snake_case string to keyword."
  [s]
  (-> s
      (str/replace "_" "-")
      keyword))


(defn enum-value->sql
  "Converts enum keyword to SQL string."
  [v]
  (if (keyword? v)
    (kw->snake-case v)
    (str v)))


(defn sql->enum-value
  "Converts SQL string to enum keyword."
  [s]
  (when s
    (snake->kw s)))


;; === Known enum values ===

(def ^:private known-value-kind-values
  #{"null" "uuid" "text" "int" "bool" "numeric" "timestamptz" "jsonb" "bytes" "any" "fn"})


(defn- known-enum-value?
  [s]
  (contains? known-value-kind-values s))


;; === Low-level encoding/decoding ===

(defn- value->jsonb
  [v]
  (doto (PGobject.)
    (PGobject/.setType "jsonb")
    (PGobject/.setValue (json/generate-string v))))


(defn- parse-jsonb
  [pg-value]
  (when pg-value
    (try
      (json/parse-string pg-value true)
      (catch JsonParseException e
        (throw (ex-info "Failed to parse JSONB value"
                        {:type :parse-error/jsonb
                         :cause (Throwable/.getMessage e)}
                        e))))))


(defn- value->enum
  [v enum-name]
  (doto (PGobject.)
    (PGobject/.setType (kw->snake-case enum-name))
    (PGobject/.setValue (enum-value->sql v))))


(defn- instant->timestamp
  [^Instant instant]
  (Timestamp/from instant))


(defn- parse-pgobject
  [v]
  (let [pg-type (PGobject/.getType v)
        pg-value (PGobject/.getValue v)]
    (when pg-value
      (if (= pg-type "jsonb")
        (parse-jsonb pg-value)
        (sql->enum-value pg-value)))))


;; === Fallback columns ===

(def ^:private fallback-jsonb-columns
  #{:value})


(def ^:private fallback-timestamptz-columns
  #{:created-at})


;; === AgeValueCodec ===

(defrecord AgeValueCodec
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

          value))))


  (decode-value
    [_this value field-spec]
    (cond
      (instance? PGobject value)
      (parse-pgobject value)

      (and (string? value)
           (or (= :enum (:type field-spec))
               (and (nil? field-spec) (known-enum-value? value))))
      (sql->enum-value value)

      :else value))


  (encode-row
    [this row field-specs]
    (sp/generic-encode-row
      (partial sp/encode-value this)
      row
      field-specs
      {:key-transform (comp keyword kw->snake-case)
       :fallback-specs (merge
                         (into {} (map (fn [k] [k {:type :jsonb}]) fallback-jsonb-columns))
                         (into {} (map (fn [k] [k {:type :timestamptz}]) fallback-timestamptz-columns)))}))


  (decode-row
    [this row field-specs]
    (sp/generic-decode-row
      (partial sp/decode-value this)
      row
      field-specs
      {:key-transform (fn [col-key] (snake->kw (name col-key)))})))


(defn create-codec
  []
  (->AgeValueCodec))


(def ^:private default-codec (delay (create-codec)))


(defn encode-value
  [value field-spec]
  (let [effective-spec (or field-spec
                           (cond
                             (map? value) {:type :jsonb}
                             (instance? Instant value) {:type :timestamptz}
                             :else nil))]
    (sp/encode-value @default-codec value effective-spec)))


(defn encode-row
  [row field-specs]
  (sp/encode-row @default-codec row field-specs))


(defn decode-row
  [row field-specs]
  (sp/decode-row @default-codec row field-specs))


(defn row->entity
  ([row]
   (row->entity row nil))
  ([row field-specs]
   (when row
     (decode-row row field-specs))))
