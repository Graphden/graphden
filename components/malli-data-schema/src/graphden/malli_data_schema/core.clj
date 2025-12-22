(ns graphden.malli-data-schema.core
  "Malli-based implementation of DataSchema protocol."
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [malli.core :as m]
    [malli.error :as me]))


(def malli-type-mapping
  "Mapping of field-types to malli schemas."
  {:uuid        :uuid
   :text        :string
   :int         :int
   :bool        :boolean
   :numeric     [:or :int :double]
   :timestamptz inst?
   :jsonb       any?
   :bytes       bytes?})


(def ^:private known-field-types
  "All valid field types."
  (into #{:ref :enum :union} (keys malli-type-mapping)))


;; === Validation helpers ===

(defn- validate-field-spec
  "Validates a single field spec structure. Throws if invalid."
  [entity-name field-name field-spec]
  (let [field-type (:type field-spec)]
    ;; Check :type is present
    (when-not field-type
      (throw (ex-info "Field spec missing :type"
                      {:entity entity-name :field field-name :spec field-spec})))
    ;; Check :type is known
    (when-not (contains? known-field-types field-type)
      (throw (ex-info (str "Unknown field type: " field-type)
                      {:entity entity-name
                       :field field-name
                       :type field-type
                       :known-types known-field-types})))
    ;; Type-specific validation
    (case field-type
      :ref
      (when-not (:ref-entity field-spec)
        (throw (ex-info "Field type :ref requires :ref-entity"
                        {:entity entity-name :field field-name :spec field-spec})))
      :enum
      (when-not (:enum-name field-spec)
        (throw (ex-info "Field type :enum requires :enum-name"
                        {:entity entity-name :field field-name :spec field-spec})))
      :union
      (let [variants (:variants field-spec)]
        (when-not (vector? variants)
          (throw (ex-info "Field type :union requires :variants vector"
                          {:entity entity-name :field field-name :spec field-spec})))
        ;; Recursively validate each variant
        (doseq [[idx variant] (map-indexed vector variants)]
          (validate-field-spec entity-name (str field-name "[" idx "]") variant)))
      ;; Default: base types - no extra validation needed
      nil)))


(defn- validate-field-specs
  "Validates all field specs in all entities."
  [entities-map]
  (doseq [[entity-name fields] entities-map
          [field-name field-spec] fields]
    (validate-field-spec entity-name field-name field-spec)))


(defn- collect-field-refs
  "Collects all :ref-entity and :enum-name references from a field spec."
  [field-spec]
  (let [{:keys [enum-name ref-entity variants]
         field-type :type} field-spec]
    (case field-type
      :ref [{:ref-type :entity :ref-name ref-entity}]
      :enum [{:ref-type :enum :ref-name enum-name}]
      :union (mapcat collect-field-refs variants)
      [])))


(defn- validate-refs
  "Validates that all references in entities point to existing enums/entities.
   Returns nil if valid, or throws with details."
  [entities-map enums-map]
  (doseq [[entity-name fields] entities-map
          [field-name field-spec] fields]
    (doseq [{:keys [ref-type ref-name]} (collect-field-refs field-spec)]
      (case ref-type
        :entity
        (when-not (contains? entities-map ref-name)
          (throw (ex-info (str "Unknown entity reference: " ref-name)
                          {:entity entity-name
                           :field field-name
                           :ref-entity ref-name
                           :available-entities (keys entities-map)})))
        :enum
        (when-not (contains? enums-map ref-name)
          (throw (ex-info (str "Unknown enum reference: " ref-name)
                          {:entity entity-name
                           :field field-name
                           :enum-name ref-name
                           :available-enums (keys enums-map)})))))))


(defn- validate-union-variants
  "Validates union variants are not empty and have no duplicates."
  [entities-map]
  (doseq [[entity-name fields] entities-map
          [field-name field-spec] fields
          :when (= (:type field-spec) :union)
          :let [variants (:variants field-spec)]]
    (when (empty? variants)
      (throw (ex-info "Union variants cannot be empty"
                      {:entity entity-name :field field-name})))
    (let [variant-types (map :type variants)
          duplicates (for [[t freq] (frequencies variant-types) :when (> freq 1)] t)]
      (when (seq duplicates)
        (throw (ex-info "Union has duplicate variant types"
                        {:entity entity-name
                         :field field-name
                         :duplicates (vec duplicates)}))))))


(declare make-field-schema)


(defn- make-variant-schema
  "Creates a malli schema for a single variant in a union."
  [variant-spec enums]
  (make-field-schema (assoc variant-spec :nullable? false) enums))


(defn- make-field-schema
  "Creates a malli schema for a field based on its specification."
  [field-spec enums]
  (let [{:keys [nullable? enum-name variants]
         field-type :type} field-spec
        base-schema (case field-type
                      :ref
                      :uuid

                      :enum
                      (let [enum-def (get enums enum-name)]
                        (into [:enum] (:values enum-def)))

                      :union
                      (into [:or] (map #(make-variant-schema % enums) variants))

                      ;; default: lookup in malli-type-mapping
                      (get malli-type-mapping field-type field-type))]
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


(defrecord MalliDataSchema
  [enums-map entities-map constraints-map compiled-schemas]

  ds/DataSchema

  (entities
    [_this]
    (keys entities-map))


  (entity-fields
    [_this entity-name]
    (get entities-map entity-name))


  (enums
    [_this]
    enums-map)


  (validate-entity
    [_this entity-name data]
    (if-let [schema (get compiled-schemas entity-name)]
      (when-not (m/validate schema data)
        {:errors (me/humanize (m/explain schema data))})
      {:errors {:entity [(str "Unknown entity: " entity-name)]}}))


  (entity-constraints
    [_this entity-name]
    (get constraints-map entity-name [])))


(defn- validate-constraints
  "Validates that all constraint fields exist in their entities."
  [entities-map constraints-map]
  (doseq [[entity-name constraints] constraints-map
          constraint constraints
          :let [fields (:fields constraint)
                entity-fields (get entities-map entity-name)]]
    (when-not entity-fields
      (throw (ex-info (str "Constraint references unknown entity: " entity-name)
                      {:entity entity-name :constraint constraint})))
    (doseq [field fields]
      (when-not (or (= field :id) (contains? entity-fields field))
        (throw (ex-info (str "Constraint references unknown field: " field)
                        {:entity entity-name
                         :field field
                         :constraint constraint
                         :available-fields (conj (set (keys entity-fields)) :id)}))))))


(defrecord MalliDataSchemaBuilder
  [enums-map entities-map constraints-map]

  ds/DataSchemaBuilder

  (add-enum
    [this enum-name values]
    (when (contains? enums-map enum-name)
      (throw (ex-info (str "Duplicate enum name: " enum-name)
                      {:enum-name enum-name
                       :existing-values (:values (get enums-map enum-name))})))
    (when (empty? values)
      (throw (ex-info "Enum values cannot be empty"
                      {:enum-name enum-name})))
    (assoc-in this [:enums-map enum-name] {:values (set values)}))


  (add-entity
    [this entity-name fields]
    (when (contains? entities-map entity-name)
      (throw (ex-info (str "Duplicate entity name: " entity-name)
                      {:entity-name entity-name
                       :existing-fields (keys (get entities-map entity-name))})))
    (assoc-in this [:entities-map entity-name] fields))


  (add-constraint
    [this entity-name constraint]
    (update-in this [:constraints-map entity-name] (fnil conj []) constraint))


  (build
    [_this]
    (validate-field-specs entities-map)
    (validate-union-variants entities-map)
    (validate-refs entities-map enums-map)
    (validate-constraints entities-map constraints-map)
    (let [compiled (into {}
                         (for [[entity-name fields] entities-map]
                           [entity-name (make-entity-schema fields enums-map)]))]
      (->MalliDataSchema enums-map entities-map constraints-map compiled))))


(defn create-builder
  "Creates a new MalliDataSchemaBuilder."
  []
  (->MalliDataSchemaBuilder {} {} {}))


(defn schema->malli
  "Returns the underlying malli schema for an entity.
   Useful for advanced validation or schema introspection."
  [data-schema entity-name]
  (get (:compiled-schemas data-schema) entity-name))
