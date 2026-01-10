(ns graphden.datomic-storage.codec
  "Value codec for Datomic storage.
   Handles enum, ref, and union type conversions.

   Implements StorageValueCodec protocol for consistent value
   encoding/decoding across all storage backends."
  (:require
    [clojure.edn :as edn]
    [graphden.datomic-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]))


;; === Low-level encoding/decoding ===

(defn- value->enum
  "Converts keyword to Datomic entity ident.
   Datomic enums are stored as entity references with idents."
  [v enum-name]
  (util/enum-value-ident enum-name v))


(defn- value->ref
  "Converts UUID to Datomic lookup ref.
   Datomic refs are stored as [attr-ident value] lookup refs."
  [v ref-entity]
  [(util/entity-attr ref-entity :id) v])


(defn- value->union
  "Encodes a union value for Datomic storage.
   Datomic doesn't have native union types, so we store as EDN string."
  [v]
  (pr-str v))


(defn- parse-union
  "Parses a union value from EDN storage format."
  [v]
  (when (string? v)
    (edn/read-string v)))


;; === DatomicValueCodec implementation ===

(defrecord DatomicValueCodec
  []

  sp/StorageValueCodec

  (encode-value
    [_this value field-spec]
    (when (some? value)
      (let [field-type (:type field-spec)]
        (case field-type
          :enum
          (if (keyword? value)
            (value->enum value (:enum-name field-spec))
            value)

          :ref
          (if (uuid? value)
            (value->ref value (:ref-entity field-spec))
            value)

          :union
          (value->union value)

          ;; Other types pass through unchanged
          value))))


  (decode-value
    [_this value field-spec]
    (let [field-type (:type field-spec)]
      (case field-type
        :union
        (parse-union value)

        ;; Datomic automatically resolves enum idents to keywords
        ;; and ref lookup refs to entity maps, so no special decoding needed
        ;; for most types
        value)))


  (encode-row
    [this row field-specs]
    (reduce-kv
      (fn [acc field-name value]
        (let [field-spec (get field-specs field-name)
              encoded (if field-spec
                        (sp/encode-value this value field-spec)
                        value)]
          (assoc acc field-name encoded)))
      {}
      row))


  (decode-row
    [this row field-specs]
    (reduce-kv
      (fn [acc field-name value]
        (let [field-spec (get field-specs field-name)
              decoded (if field-spec
                        (sp/decode-value this value field-spec)
                        value)]
          (assoc acc field-name decoded)))
      {}
      row)))


(defn create-codec
  "Creates a Datomic value codec instance."
  []
  (->DatomicValueCodec))


(def ^:private default-codec (delay (create-codec)))


(defn encode-value
  "Encodes a value using the default codec."
  [value field-spec]
  (sp/encode-value @default-codec value field-spec))


(defn decode-value
  "Decodes a value using the default codec."
  [value field-spec]
  (sp/decode-value @default-codec value field-spec))


(defn encode-row
  "Encodes a row using the default codec."
  [row field-specs]
  (sp/encode-row @default-codec row field-specs))


(defn decode-row
  "Decodes a row using the default codec."
  [row field-specs]
  (sp/decode-row @default-codec row field-specs))
