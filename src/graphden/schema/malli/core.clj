(ns graphden.schema.malli.core
  "Malli-based implementation of DataSchema protocol."
  (:require
    [clojure.set :as set]
    [graphden.schema.malli.types :as types]
    [graphden.schema.malli.validators :as v]
    [graphden.schema.protocol.protocol :as ds]))


;; === MalliDataSchema record ===

(defrecord MalliDataSchema
  [enums-map entities-map entity-uuids-map constraints-map retired-fields-map]

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


  (entity-constraints
    [_this entity-name]
    (get constraints-map entity-name []))


  (retired-fields
    [_this]
    (or retired-fields-map {})))


;; === MalliDataSchemaBuilder record ===

(defrecord MalliDataSchemaBuilder
  [enums-map entities-map entity-uuids-map constraints-map known-uuids retired-fields-map]

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
    ;; Check for duplicate values (single-pass with early exit)
    (when-let [dup (v/find-first-duplicate (map :value values))]
      (throw (ex-info "Enum has duplicate values"
                      {:enum-name enum-name
                       :duplicates [dup]})))
    ;; Check for duplicate UUIDs within values (single-pass with early exit)
    (when-let [dup (v/find-first-duplicate (map :uuid values))]
      (throw (ex-info "Enum has duplicate value UUIDs"
                      {:enum-name enum-name
                       :duplicates [dup]})))
    ;; Store as {:uuid enum-uuid :values {value-keyword value-uuid ...}}
    ;; Also add all UUIDs to known-uuids for O(1) future lookups
    ;; Single-pass: build both maps simultaneously
    (let [{:keys [values-map new-known-uuids]}
          (reduce (fn [acc {:keys [uuid value]}]
                    (-> acc
                        (assoc-in [:values-map value] uuid)
                        (assoc-in [:new-known-uuids uuid] (str "enum value " enum-name "/" value))))
                  {:values-map {}
                   :new-known-uuids (assoc known-uuids enum-uuid (str "enum " enum-name))}
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
    ;; Validate each field and build known-uuids in single pass
    ;; The reduce accumulator tracks: seen UUIDs for duplicate detection + new-known-uuids map
    (let [new-known-uuids
          (:new-known-uuids
            (reduce
              (fn [{:keys [seen new-known-uuids]} [field-name field-spec]]
                (when-not (contains? field-spec :uuid)
                  (throw (ex-info "Field missing :uuid"
                                  {:entity entity-name :field field-name :spec field-spec})))
                (let [field-uuid (:uuid field-spec)
                      field-location (str "field " entity-name "/" field-name)]
                  (v/validate-uuid {:context "field" :entity entity-name :field field-name}
                                   field-uuid)
                  ;; Check field UUID against entity UUID
                  (when (= field-uuid entity-uuid)
                    (throw (ex-info "Duplicate UUID"
                                    {:uuid field-uuid
                                     :new-location field-location
                                     :existing-location (str "entity " entity-name)})))
                  ;; Check field UUID against other fields in this entity
                  (when (contains? seen field-uuid)
                    (throw (ex-info "Duplicate UUID within entity"
                                    {:entity entity-name
                                     :field field-name
                                     :uuid field-uuid})))
                  ;; Check field UUID uniqueness against existing UUIDs in builder
                  (v/check-uuid-uniqueness known-uuids field-uuid field-location)
                  {:seen (conj seen field-uuid)
                   :new-known-uuids (assoc new-known-uuids field-uuid field-location)}))
              {:seen #{}
               :new-known-uuids (assoc known-uuids entity-uuid (str "entity " entity-name))}
              fields))]
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
      ;; Reject extra attributes (`:nulls-not-distinct?` toggles PG 15+
      ;; NULLS-NOT-DISTINCT uniqueness — see postgres/ddl create-constraint!).
      (let [extra-keys (set/difference (set (keys constraint)) #{:type :fields :nulls-not-distinct?})]
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


  (retire-field
    [this entity-name field-name field-uuid]
    (when-not (keyword? entity-name)
      (throw (ex-info "Entity name must be a keyword"
                      {:entity-name entity-name :type (type entity-name)})))
    (when-not (keyword? field-name)
      (throw (ex-info "Field name must be a keyword"
                      {:field-name field-name :type (type field-name)})))
    (when-not (uuid? field-uuid)
      (throw (ex-info "Retired field UUID must be a UUID"
                      {:entity-name entity-name :field-name field-name
                       :uuid field-uuid :type (type field-uuid)})))
    ;; Sanity: the field MUST already be removed from the entity's
    ;; current spec — retire-field is a tombstone, not a "schedule
    ;; removal" mechanism. Catches the common mistake of declaring a
    ;; retired field while it's still present.
    (when (contains? (get entities-map entity-name {}) field-name)
      (throw (ex-info (str "Cannot retire field still present in entity spec: "
                           entity-name "/" field-name
                           " — drop the field's entry from add-entity first.")
                      {:entity-name entity-name :field-name field-name})))
    ;; The tombstone uuid stays RESERVED: a later field reusing it
    ;; would make the migration layer see the dropped column as a
    ;; rename target while process-retired-fields! simultaneously
    ;; drops it. Register it in known-uuids so the reuse fails loudly
    ;; at build time. (Idempotent re-declaration of the same tombstone
    ;; is fine — the uniqueness check only fires for a DIFFERENT
    ;; location claiming the uuid.)
    (let [tombstone-loc (str "retired field " entity-name "/" field-name)]
      (when-not (= tombstone-loc (get known-uuids field-uuid))
        (v/check-uuid-uniqueness known-uuids field-uuid tombstone-loc))
      (-> this
          (assoc-in [:retired-fields-map entity-name field-name] field-uuid)
          (assoc-in [:known-uuids field-uuid] tombstone-loc))))


  (build
    [_this]
    (v/validate-field-specs entities-map)
    (v/validate-union-variants entities-map)
    (v/validate-refs entities-map enums-map)
    (v/validate-constraints entities-map constraints-map)
    (->MalliDataSchema enums-map entities-map entity-uuids-map constraints-map
                       (or retired-fields-map {}))))


(defn create-builder
  "Creates a new MalliDataSchemaBuilder."
  []
  (->MalliDataSchemaBuilder {} {} {} {} {} {}))
