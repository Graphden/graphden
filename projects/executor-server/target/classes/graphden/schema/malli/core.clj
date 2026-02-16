(ns graphden.schema.malli.core
  "Malli-based implementation of DataSchema protocol."
  (:require
    [graphden.schema.malli.schema :as schema]
    [graphden.schema.malli.types :as types]
    [graphden.schema.malli.validators :as v]
    [graphden.schema.protocol.interface :as ds]
    [malli.core :as m]
    [malli.error :as me]))


;; Re-export for backwards compatibility
(def malli-type-mapping types/malli-type-mapping)


;; === MalliDataSchema record ===

(defrecord MalliDataSchema
  [enums-map entities-map entity-uuids-map constraints-map compiled-schemas]

  ds/DataSchema

  (entities
    [_this]
    (keys entities-map))


  (entity-uuid
    [_this entity-name]
    (get entity-uuids-map entity-name))


  (entity-fields
    [_this entity-name]
    (get entities-map entity-name))


  (enums
    [_this]
    enums-map)


  (enum-uuid
    [_this enum-name]
    (get-in enums-map [enum-name :uuid]))


  (validate-entity
    [_this entity-name data]
    (if-let [schema (get compiled-schemas entity-name)]
      (when-not (m/validate schema data)
        {:errors (me/humanize (m/explain schema data))})
      {:errors {:entity [(str "Unknown entity: " entity-name)]}}))


  (entity-constraints
    [_this entity-name]
    (get constraints-map entity-name [])))


;; === MalliDataSchemaBuilder record ===

(defrecord MalliDataSchemaBuilder
  [enums-map entities-map entity-uuids-map constraints-map known-uuids]

  ds/DataSchemaBuilder

  (add-enum
    [this enum-name enum-uuid values]
    ;; Validate enum-name
    (when-not (keyword? enum-name)
      (throw (ex-info "Enum name must be a keyword"
                      {:enum-name enum-name :type (type enum-name)})))
    ;; Check for duplicate enum name
    (when (contains? enums-map enum-name)
      (throw (ex-info (str "Duplicate enum name: " enum-name)
                      {:enum-name enum-name
                       :existing-values (keys (:values (get enums-map enum-name)))})))
    ;; Validate enum-uuid
    (v/validate-uuid {:context "enum" :enum-name enum-name} enum-uuid)
    ;; Check UUID uniqueness
    (v/check-uuid-uniqueness known-uuids enum-uuid (str "enum " enum-name))
    ;; Validate values format
    (when (empty? values)
      (throw (ex-info "Enum values cannot be empty"
                      {:enum-name enum-name})))
    (when-not (vector? values)
      (throw (ex-info "Enum values must be a vector"
                      {:enum-name enum-name :values values})))
    ;; Validate each value entry
    (run! #(v/validate-single-enum-value known-uuids enum-name %) values)
    ;; Check for duplicate values
    (let [value-keywords (map :value values)
          duplicates (for [[v freq] (frequencies value-keywords) :when (> freq 1)] v)]
      (when (seq duplicates)
        (throw (ex-info "Enum has duplicate values"
                        {:enum-name enum-name
                         :duplicates (vec duplicates)}))))
    ;; Check for duplicate UUIDs within values
    (let [value-uuids (map :uuid values)
          duplicates (for [[u freq] (frequencies value-uuids) :when (> freq 1)] u)]
      (when (seq duplicates)
        (throw (ex-info "Enum has duplicate value UUIDs"
                        {:enum-name enum-name
                         :duplicates (vec duplicates)}))))
    ;; Store as {:uuid enum-uuid :values {value-keyword value-uuid ...}}
    ;; Also add all UUIDs to known-uuids for O(1) future lookups
    (let [values-map (into {} (map (fn [{:keys [uuid value]}] [value uuid]) values))
          new-known-uuids (reduce (fn [acc {:keys [uuid value]}]
                                    (assoc acc uuid (str "enum value " enum-name "/" value)))
                                  (assoc known-uuids enum-uuid (str "enum " enum-name))
                                  values)]
      (-> this
          (assoc-in [:enums-map enum-name] {:uuid enum-uuid :values values-map})
          (assoc :known-uuids new-known-uuids))))


  (add-entity
    [this entity-name entity-uuid fields]
    (v/validate-entity-name entity-name)
    (v/validate-field-names entity-name fields)
    ;; Check for duplicate entity name
    (when (contains? entities-map entity-name)
      (throw (ex-info (str "Duplicate entity name: " entity-name)
                      {:entity-name entity-name
                       :existing-fields (keys (get entities-map entity-name))})))
    ;; Validate entity-uuid
    (v/validate-uuid {:context "entity" :entity-name entity-name} entity-uuid)
    ;; Check entity UUID uniqueness against existing UUIDs
    (v/check-uuid-uniqueness known-uuids entity-uuid (str "entity " entity-name))
    ;; Validate each field has :uuid and check for duplicates using reduce
    ;; The reduce accumulator tracks seen UUIDs within this entity for duplicate detection
    (reduce
      (fn [seen [field-name field-spec]]
        (when-not (contains? field-spec :uuid)
          (throw (ex-info "Field missing :uuid"
                          {:entity entity-name :field field-name :spec field-spec})))
        (let [field-uuid (:uuid field-spec)]
          (v/validate-uuid {:context "field" :entity entity-name :field field-name}
                           field-uuid)
          ;; Check field UUID against entity UUID
          (when (= field-uuid entity-uuid)
            (throw (ex-info "Duplicate UUID"
                            {:uuid field-uuid
                             :new-location (str "field " entity-name "/" field-name)
                             :existing-location (str "entity " entity-name)})))
          ;; Check field UUID against other fields in this entity
          (when (contains? seen field-uuid)
            (throw (ex-info "Duplicate UUID within entity"
                            {:entity entity-name
                             :field field-name
                             :uuid field-uuid})))
          ;; Check field UUID uniqueness against existing UUIDs in builder
          (v/check-uuid-uniqueness known-uuids field-uuid
                                   (str "field " entity-name "/" field-name))
          (conj seen field-uuid)))
      #{}
      fields)
    ;; Build new known-uuids map with entity and all field UUIDs
    (let [new-known-uuids (reduce
                            (fn [acc [field-name field-spec]]
                              (assoc acc (:uuid field-spec)
                                     (str "field " entity-name "/" field-name)))
                            (assoc known-uuids entity-uuid (str "entity " entity-name))
                            fields)]
      (-> this
          (assoc-in [:entities-map entity-name] fields)
          (assoc-in [:entity-uuids-map entity-name] entity-uuid)
          (assoc :known-uuids new-known-uuids))))


  (add-constraint
    [this entity-name constraint]
    (let [constraint-type (:type constraint)
          fields (:fields constraint)]
      ;; Validate :type
      (when-not constraint-type
        (throw (ex-info "Constraint missing :type"
                        {:entity entity-name :constraint constraint})))
      (when-not (contains? types/known-constraint-types constraint-type)
        (throw (ex-info (str "Unknown constraint type: " constraint-type)
                        {:entity entity-name
                         :constraint constraint
                         :known-types types/known-constraint-types})))
      ;; Validate :fields is a non-empty vector of keywords
      (when-not (vector? fields)
        (throw (ex-info "Constraint :fields must be a vector"
                        {:entity entity-name :constraint constraint})))
      (when (empty? fields)
        (throw (ex-info "Constraint :fields cannot be empty"
                        {:entity entity-name :constraint constraint})))
      (let [non-keywords (remove keyword? fields)]
        (when (seq non-keywords)
          (throw (ex-info "Constraint :fields must contain only keywords"
                          {:entity entity-name
                           :constraint constraint
                           :invalid-fields (vec non-keywords)}))))
      ;; Reject extra attributes
      (let [extra-keys (disj (set (keys constraint)) :type :fields)]
        (when (seq extra-keys)
          (throw (ex-info "Constraint has unsupported attributes"
                          {:entity entity-name
                           :constraint constraint
                           :unsupported-keys extra-keys}))))
      ;; Check for duplicate constraint
      (let [existing (get constraints-map entity-name [])
            normalized {:type constraint-type :fields fields}]
        (when (some #(= normalized (select-keys % [:type :fields])) existing)
          (throw (ex-info "Duplicate constraint"
                          {:entity entity-name :constraint constraint})))))
    (update-in this [:constraints-map entity-name] (fnil conj []) constraint))


  (build
    [_this]
    (v/validate-field-specs entities-map)
    (v/validate-union-variants entities-map)
    (v/validate-refs entities-map enums-map)
    (v/validate-constraints entities-map constraints-map)
    (let [compiled (into {}
                         (for [[entity-name fields] entities-map]
                           [entity-name (schema/make-entity-schema fields enums-map)]))]
      (->MalliDataSchema enums-map entities-map entity-uuids-map constraints-map compiled))))


(defn create-builder
  "Creates a new MalliDataSchemaBuilder."
  []
  (->MalliDataSchemaBuilder {} {} {} {} {}))


(defn schema->malli
  "Returns the underlying malli schema for an entity.
   Useful for advanced validation or schema introspection."
  [data-schema entity-name]
  (get (:compiled-schemas data-schema) entity-name))
