(ns graphden.malli-data-schema.core
  "Malli-based implementation of DataSchema protocol."
  (:require
   [graphden.data-schema-protocol.interface :as ds]
   [malli.core :as m]
   [malli.error :as me]))


(def base-types
  "Mapping of schema types to malli schemas."
  {:uuid :uuid
   :text :string
   :int :int
   :bool :boolean
   :numeric [:or :int :double]
   :timestamptz inst?
   :jsonb any?
   :bytes bytes?})


(declare make-field-schema)


(defn- make-variant-schema
  "Creates a malli schema for a single variant in a union."
  [variant-spec enums]
  (make-field-schema (assoc variant-spec :nullable? false) enums))


(defn- make-field-schema
  "Creates a malli schema for a field based on its specification."
  [field-spec enums]
  (let [{:keys [type nullable? enum-name variants]} field-spec
        base-schema (case type
                      :ref
                      :uuid

                      :enum
                      (let [enum-def (get enums enum-name)]
                        (into [:enum] (:values enum-def)))

                      :union
                      (into [:or] (map #(make-variant-schema % enums) variants))

                      ;; default
                      (get base-types type type))]
    (if nullable?
      [:maybe base-schema]
      base-schema)))


(defn- make-entity-schema
  "Creates a malli schema for an entity."
  [fields enums]
  (let [field-schemas (into [[:id :uuid]]
                            (for [[field-name field-spec] fields]
                              [field-name (make-field-schema field-spec enums)]))]
    (into [:map {:closed true}] field-schemas)))


(defrecord MalliDataSchema [enums-map entities-map compiled-schemas]
  ds/DataSchema

  (entities [_this]
    (keys entities-map))

  (entity-fields [_this entity-name]
    (get entities-map entity-name))

  (enums [_this]
    enums-map)

  (validate-entity [_this entity-name data]
    (if-let [schema (get compiled-schemas entity-name)]
      (when-not (m/validate schema data)
        {:errors (me/humanize (m/explain schema data))})
      {:errors {:entity [(str "Unknown entity: " entity-name)]}})))


(defrecord MalliDataSchemaBuilder [enums-map entities-map]
  ds/DataSchemaBuilder

  (add-enum [this enum-name values]
    (assoc-in this [:enums-map enum-name] {:values (set values)}))

  (add-entity [this entity-name fields]
    (assoc-in this [:entities-map entity-name] fields))

  (build [_this]
    (let [compiled (into {}
                         (for [[entity-name fields] entities-map]
                           [entity-name (make-entity-schema fields enums-map)]))]
      (->MalliDataSchema enums-map entities-map compiled))))


(defn create-builder
  "Creates a new MalliDataSchemaBuilder."
  []
  (->MalliDataSchemaBuilder {} {}))


(defn schema->malli
  "Returns the underlying malli schema for an entity.
   Useful for advanced validation or schema introspection."
  [data-schema entity-name]
  (get (:compiled-schemas data-schema) entity-name))
