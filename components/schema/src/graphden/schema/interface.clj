(ns graphden.schema.interface
  "Schema provider protocol - abstraction over schema libraries (Malli, Spec, etc.)")


(defprotocol SchemaProvider
  "Protocol for schema validation and introspection.
   Implementations can use Malli, clojure.spec, Plumatic Schema, etc."

  (validate
    [this schema-key data]
    "Validate data against schema. Returns {:valid? bool :errors [...]}")

  (coerce
    [this schema-key data]
    "Coerce data to match schema (e.g., string->keyword)")

  (get-fields
    [this schema-key]
    "Get field definitions: [{:name :foo :type :keyword :optional? false} ...]")

  (get-relations
    [this]
    "Get relations between entities: {:node->parent {:from :node :to :node :field :parent-name}}")

  (get-derived-queries
    [this]
    "Get set of derived query names: #{:root-ancestor :full-args}"))


;; Wrapper functions for cleaner API
(defn validate*
  "Validate data against schema identified by schema-key"
  [provider schema-key data]
  (validate provider schema-key data))


(defn coerce*
  "Coerce data to match schema"
  [provider schema-key data]
  (coerce provider schema-key data))


(defn get-fields*
  "Get field definitions for entity"
  [provider schema-key]
  (get-fields provider schema-key))


(defn get-relations*
  "Get all relations"
  [provider]
  (get-relations provider))


(defn get-derived-queries*
  "Get set of derived queries"
  [provider]
  (get-derived-queries provider))
