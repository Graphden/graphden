(ns graphden.malli-data-schema.core
  "Malli-based implementation of DataSchema protocol."
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.field-types.interface :as ft]
    [malli.core :as m]
    [malli.error :as me]))


(def ^:private jsonb-schema
  "Schema for JSON-compatible values (recursive)."
  [:schema {:registry {::json [:or
                               :nil
                               :boolean
                               :int
                               :double
                               :string
                               [:vector [:ref ::json]]
                               [:map-of :string [:ref ::json]]]}}
   [:ref ::json]])


(def malli-type-mapping
  "Mapping of field-types to malli schemas."
  {:uuid        :uuid
   :text        :string
   :int         :int
   :bool        :boolean
   :numeric     [:or :int :double]
   :timestamptz inst?
   :jsonb       jsonb-schema
   :bytes       bytes?})


(def ^:private known-field-types
  "All valid field types.
   Includes storage types from field-types plus semantic types:
   - :ref, :enum, :union - structural types
   - :any - polymorphic type (accepts any value)
   - :fn - function reference type (stored as UUID)"
  (into #{:ref :enum :union :any :fn} ft/supported-types))


(def ^:private known-constraint-types
  "All valid constraint types."
  #{:unique})


;; === Validation helpers ===

(def ^:private identifier-pattern
  "Pattern for valid identifiers (entity names, field names, enum values).
   Must start with lowercase letter, contain only lowercase letters/digits/hyphens.
   Lowercase-only ensures compatibility with PostgreSQL identifier rules
   (PostgreSQL folds unquoted identifiers to lowercase).
   kebab-case → snake_case conversion: my-entity → my_entity"
  #"^[a-z][a-z0-9]*(-[a-z0-9]+)*$")


(defn- valid-identifier-name?
  "Returns true if the keyword name is a valid identifier for SQL conversion."
  [kw]
  (and (keyword? kw)
       (re-matches identifier-pattern (name kw))))


(defn- validate-identifier-name!
  "Validates that a keyword is suitable for use as a SQL identifier.
   Throws if the name would be invalid after kebab-case → snake_case conversion."
  [context kw]
  (when-not (valid-identifier-name? kw)
    (throw (ex-info (str "Invalid identifier name: " kw ". "
                         "Must start with lowercase letter, contain only lowercase letters/digits/hyphens.")
                    {:type :schema-error/invalid-identifier
                     :context context
                     :value kw
                     :pattern (str identifier-pattern)}))))


(defn- validate-entity-name
  "Validates entity name is a valid keyword."
  [entity-name]
  (when-not (keyword? entity-name)
    (throw (ex-info "Entity name must be a keyword"
                    {:entity-name entity-name
                     :type (type entity-name)}))))


(defn- validate-single-field-name
  "Validates a single field name. Throws if invalid."
  [entity-name field-name]
  (when-not (keyword? field-name)
    (throw (ex-info "Field name must be a keyword"
                    {:entity entity-name
                     :field-name field-name
                     :type (type field-name)})))
  (when (= field-name :id)
    (throw (ex-info "Field name :id is reserved (implicit primary key)"
                    {:entity entity-name}))))


(defn- validate-field-names
  "Validates field names in an entity definition."
  [entity-name fields]
  (run! #(validate-single-field-name entity-name %) (keys fields)))


(defn- validate-field-spec
  "Validates a single field spec structure. Throws if invalid.
   When in-variant? is true, :nullable? and :uuid are not allowed (variants cannot have them)."
  ([entity-name field-name field-spec]
   (validate-field-spec entity-name field-name field-spec false))
  ([entity-name field-name field-spec in-variant?]
   (let [field-type (:type field-spec)
         ;; Allowed keys depend on whether we're in a variant context
         ;; Variants don't have :uuid or :nullable?
         base-allowed-keys (if in-variant? #{:type} #{:type :nullable? :uuid})]
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
     ;; Check :nullable? - not allowed in variants, must be boolean otherwise
     (when (contains? field-spec :nullable?)
       (if in-variant?
         (throw (ex-info "Union variant cannot have :nullable? attribute"
                         {:entity entity-name :field field-name :spec field-spec}))
         (let [nullable-val (:nullable? field-spec)]
           (when-not (boolean? nullable-val)
             (throw (ex-info "Field :nullable? must be a boolean"
                             {:entity entity-name
                              :field field-name
                              :nullable? nullable-val
                              :type (type nullable-val)}))))))
     ;; Type-specific validation
     (case field-type
       :ref
       (do
         (when-not (:ref-entity field-spec)
           (throw (ex-info "Field type :ref requires :ref-entity"
                           {:entity entity-name :field field-name :spec field-spec})))
         (let [allowed-keys (conj base-allowed-keys :ref-entity)
               extra-keys (apply disj (set (keys field-spec)) allowed-keys)]
           (when (seq extra-keys)
             (throw (ex-info "Field type :ref has unsupported attributes"
                             {:entity entity-name
                              :field field-name
                              :unsupported-keys extra-keys
                              :allowed-keys allowed-keys})))))
       :enum
       (do
         (when-not (:enum-name field-spec)
           (throw (ex-info "Field type :enum requires :enum-name"
                           {:entity entity-name :field field-name :spec field-spec})))
         (let [allowed-keys (conj base-allowed-keys :enum-name)
               extra-keys (apply disj (set (keys field-spec)) allowed-keys)]
           (when (seq extra-keys)
             (throw (ex-info "Field type :enum has unsupported attributes"
                             {:entity entity-name
                              :field field-name
                              :unsupported-keys extra-keys
                              :allowed-keys allowed-keys})))))
       :union
       (do
         (let [variants (:variants field-spec)]
           (when-not (vector? variants)
             (throw (ex-info "Field type :union requires :variants vector"
                             {:entity entity-name :field field-name :spec field-spec})))
           ;; Recursively validate each variant (with in-variant? = true)
           (run! (fn [[idx variant]]
                   (validate-field-spec entity-name (str field-name "[" idx "]") variant true))
                 (map-indexed vector variants)))
         (let [allowed-keys (conj base-allowed-keys :variants)
               extra-keys (apply disj (set (keys field-spec)) allowed-keys)]
           (when (seq extra-keys)
             (throw (ex-info "Field type :union has unsupported attributes"
                             {:entity entity-name
                              :field field-name
                              :unsupported-keys extra-keys
                              :allowed-keys allowed-keys})))))
       ;; Default: base types
       (let [extra-keys (apply disj (set (keys field-spec)) base-allowed-keys)]
         (when (seq extra-keys)
           (throw (ex-info (str "Field type " field-type " has unsupported attributes")
                           {:entity entity-name
                            :field field-name
                            :unsupported-keys extra-keys
                            :allowed-keys base-allowed-keys}))))))))


(defn- validate-entity-field-specs
  "Validates all field specs for a single entity."
  [entity-name fields]
  (run! (fn [[field-name field-spec]]
          (validate-field-spec entity-name field-name field-spec))
        fields))


(defn- validate-field-specs
  "Validates all field specs in all entities."
  [entities-map]
  (run! (fn [[entity-name fields]]
          (validate-entity-field-specs entity-name fields))
        entities-map))


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


(defn- validate-entity-ref
  "Validates an entity reference. Throws if invalid."
  [entity-name field-name ref-name entities-map]
  (when-not (contains? entities-map ref-name)
    (throw (ex-info (str "Unknown entity reference: " ref-name)
                    {:entity entity-name
                     :field field-name
                     :ref-entity ref-name
                     :available-entities (keys entities-map)}))))


(defn- validate-enum-ref
  "Validates an enum reference. Throws if invalid."
  [entity-name field-name ref-name enums-map]
  (when-not (contains? enums-map ref-name)
    (throw (ex-info (str "Unknown enum reference: " ref-name)
                    {:entity entity-name
                     :field field-name
                     :enum-name ref-name
                     :available-enums (keys enums-map)}))))


(defn- validate-single-ref
  "Validates a single reference. Throws if invalid."
  [entity-name field-name ref-type ref-name entities-map enums-map]
  (case ref-type
    :entity (validate-entity-ref entity-name field-name ref-name entities-map)
    :enum (validate-enum-ref entity-name field-name ref-name enums-map)))


(defn- validate-field-refs
  "Validates all references in a single field. Throws if any invalid."
  [entity-name field-name field-spec entities-map enums-map]
  (run! (fn [{:keys [ref-type ref-name]}]
          (validate-single-ref entity-name field-name ref-type ref-name entities-map enums-map))
        (collect-field-refs field-spec)))


(defn- validate-entity-refs
  "Validates all references in a single entity's fields."
  [entity-name fields entities-map enums-map]
  (run! (fn [[field-name field-spec]]
          (validate-field-refs entity-name field-name field-spec entities-map enums-map))
        fields))


(defn- validate-refs
  "Validates that all references in entities point to existing enums/entities.
   Returns nil if valid, or throws with details."
  [entities-map enums-map]
  (run! (fn [[entity-name fields]]
          (validate-entity-refs entity-name fields entities-map enums-map))
        entities-map))


(defn- variant-identity
  "Returns a normalized identity for a variant to detect duplicates.
   For :ref includes :ref-entity, for :enum includes :enum-name."
  [variant]
  (case (:type variant)
    :ref (select-keys variant [:type :ref-entity])
    :enum (select-keys variant [:type :enum-name])
    (select-keys variant [:type])))


(defn- validate-single-union-field
  "Validates a single union field's variants. Throws if invalid."
  [entity-name field-name variants]
  (when (empty? variants)
    (throw (ex-info "Union variants cannot be empty"
                    {:entity entity-name :field field-name})))
  (let [variant-ids (map variant-identity variants)
        duplicates (for [[v freq] (frequencies variant-ids) :when (> freq 1)] v)]
    (when (seq duplicates)
      (throw (ex-info "Union has duplicate variants"
                      {:entity entity-name
                       :field field-name
                       :duplicates (vec duplicates)})))))


(defn- validate-entity-union-variants
  "Validates union variants for a single entity's fields."
  [entity-name fields]
  (run! (fn [[field-name field-spec]]
          (when (= (:type field-spec) :union)
            (validate-single-union-field entity-name field-name (:variants field-spec))))
        fields))


(defn- validate-union-variants
  "Validates union variants are not empty and have no duplicates."
  [entities-map]
  (run! (fn [[entity-name fields]]
          (validate-entity-union-variants entity-name fields))
        entities-map))


(declare make-field-schema)


(defn- make-variant-schema
  "Creates a malli schema for a single variant in a union."
  [variant-spec enums]
  (make-field-schema (assoc variant-spec :nullable? false) enums))


(defn- make-field-schema
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
                      (get malli-type-mapping field-type field-type))]
    (if nullable?
      [:maybe base-schema]
      base-schema)))


(defn- make-entity-schema
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


(defn- validate-constraint-field
  "Validates a single constraint field exists. Throws if invalid."
  [entity-name entity-fields constraint field]
  (when-not (or (= field :id) (contains? entity-fields field))
    (throw (ex-info (str "Constraint references unknown field: " field)
                    {:entity entity-name
                     :field field
                     :constraint constraint
                     :available-fields (conj (set (keys entity-fields)) :id)}))))


(defn- validate-single-constraint
  "Validates a single constraint. Throws if invalid."
  [entity-name entity-fields constraint]
  (when-not entity-fields
    (throw (ex-info (str "Constraint references unknown entity: " entity-name)
                    {:entity entity-name :constraint constraint})))
  (run! #(validate-constraint-field entity-name entity-fields constraint %)
        (:fields constraint)))


(defn- validate-entity-constraints
  "Validates all constraints for a single entity."
  [entities-map entity-name constraints]
  (let [entity-fields (get entities-map entity-name)]
    (run! #(validate-single-constraint entity-name entity-fields %) constraints)))


(defn- validate-constraints
  "Validates that all constraint fields exist in their entities."
  [entities-map constraints-map]
  (run! (fn [[entity-name constraints]]
          (validate-entity-constraints entities-map entity-name constraints))
        constraints-map))


(defn- validate-uuid
  "Validates that a value is a UUID. Throws if not."
  [context value]
  (when-not (uuid? value)
    (throw (ex-info "UUID required"
                    (merge context {:value value :type (type value)})))))


(defn- check-uuid-uniqueness
  "Checks that a new UUID doesn't conflict with existing ones.
   Uses the known-uuids map in builder for O(1) lookup.
   Throws if duplicate found."
  [builder new-uuid new-location]
  (when-let [existing-location (get (:known-uuids builder) new-uuid)]
    (throw (ex-info "Duplicate UUID"
                    {:uuid new-uuid
                     :new-location new-location
                     :existing-location existing-location}))))


(defn- validate-single-enum-value
  "Validates a single enum value entry. Throws if invalid."
  [builder enum-name entry]
  (when-not (map? entry)
    (throw (ex-info "Each enum value must be a map with :uuid and :value"
                    {:enum-name enum-name :entry entry})))
  (when-not (contains? entry :uuid)
    (throw (ex-info "Enum value missing :uuid"
                    {:enum-name enum-name :entry entry})))
  (when-not (contains? entry :value)
    (throw (ex-info "Enum value missing :value"
                    {:enum-name enum-name :entry entry})))
  (validate-uuid {:context "enum value" :enum-name enum-name :value (:value entry)}
                 (:uuid entry))
  (when-not (keyword? (:value entry))
    (throw (ex-info "Enum value :value must be a keyword"
                    {:enum-name enum-name :entry entry})))
  ;; Validate enum value name is suitable for SQL identifier conversion
  (validate-identifier-name! {:enum-name enum-name :enum-value (:value entry)}
                             (:value entry))
  (check-uuid-uniqueness builder (:uuid entry)
                         (str "enum value " enum-name "/" (:value entry))))


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
    (validate-uuid {:context "enum" :enum-name enum-name} enum-uuid)
    ;; Check UUID uniqueness
    (check-uuid-uniqueness this enum-uuid (str "enum " enum-name))
    ;; Validate values format
    (when (empty? values)
      (throw (ex-info "Enum values cannot be empty"
                      {:enum-name enum-name})))
    (when-not (vector? values)
      (throw (ex-info "Enum values must be a vector"
                      {:enum-name enum-name :values values})))
    ;; Validate each value entry
    (run! #(validate-single-enum-value this enum-name %) values)
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
    (validate-entity-name entity-name)
    (validate-field-names entity-name fields)
    ;; Check for duplicate entity name
    (when (contains? entities-map entity-name)
      (throw (ex-info (str "Duplicate entity name: " entity-name)
                      {:entity-name entity-name
                       :existing-fields (keys (get entities-map entity-name))})))
    ;; Validate entity-uuid
    (validate-uuid {:context "entity" :entity-name entity-name} entity-uuid)
    ;; Check entity UUID uniqueness against existing UUIDs
    (check-uuid-uniqueness this entity-uuid (str "entity " entity-name))
    ;; Validate each field has :uuid and check for duplicates using reduce
    ;; The reduce accumulator tracks seen UUIDs within this entity for duplicate detection
    (reduce
      (fn [seen [field-name field-spec]]
        (when-not (contains? field-spec :uuid)
          (throw (ex-info "Field missing :uuid"
                          {:entity entity-name :field field-name :spec field-spec})))
        (let [field-uuid (:uuid field-spec)]
          (validate-uuid {:context "field" :entity entity-name :field field-name}
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
          (check-uuid-uniqueness this field-uuid
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
      (when-not (contains? known-constraint-types constraint-type)
        (throw (ex-info (str "Unknown constraint type: " constraint-type)
                        {:entity entity-name
                         :constraint constraint
                         :known-types known-constraint-types})))
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
    (validate-field-specs entities-map)
    (validate-union-variants entities-map)
    (validate-refs entities-map enums-map)
    (validate-constraints entities-map constraints-map)
    (let [compiled (into {}
                         (for [[entity-name fields] entities-map]
                           [entity-name (make-entity-schema fields enums-map)]))]
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
