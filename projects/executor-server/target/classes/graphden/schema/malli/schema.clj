(ns graphden.schema.malli.schema
  "Malli schema generation for entities."
  (:require
    [graphden.schema.malli.types :as types]))


(declare make-field-schema)


(defn- make-variant-schema
  "Creates a malli schema for a single variant in a union."
  [variant-spec enums]
  (make-field-schema (assoc variant-spec :nullable? false) enums))


(defn make-field-schema
  "Creates a malli schema for a field based on its specification."
  [field-spec enums]
  (let [field-type (:type field-spec)
        nullable? (get field-spec :nullable? false)
        base-schema (case field-type
                      :ref
                      :uuid

                      :enum
                      ;; enum existence validated by validate-refs during build
                      (into [:enum] (keys (:values (get enums (:enum-name field-spec)))))

                      :union
                      (into [:or] (map #(make-variant-schema % enums)
                                       (:variants field-spec)))

                      ;; Semantic types for graph execution model
                      :any :any  ; Accepts any value (polymorphic argument)
                      :fn :uuid  ; Function reference (stored as UUID)

                      ;; default: lookup in malli-type-mapping
                      (get types/malli-type-mapping field-type field-type))]
    (if nullable?
      [:maybe base-schema]
      base-schema)))


(defn make-entity-schema
  "Creates a malli schema for an entity."
  [fields enums]
  (let [field-schemas (into [[:id :uuid]]
                            (for [[field-name field-spec] fields]
                              (let [nullable? (get field-spec :nullable? false)
                                    base-entry [field-name (make-field-schema field-spec enums)]]
                                (if nullable?
                                  ;; Nullable fields are optional - key can be omitted
                                  [field-name {:optional true} (make-field-schema field-spec enums)]
                                  base-entry))))]
    (into [:map {:closed true}] field-schemas)))
