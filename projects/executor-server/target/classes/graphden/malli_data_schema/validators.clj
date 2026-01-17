(ns graphden.malli-data-schema.validators
  "Validation functions for malli-data-schema."
  (:require
    [graphden.malli-data-schema.types :as types]
    [graphden.storage-protocol.interface :as sp]))


;; === Identifier validation ===

(def ^:private identifier-pattern
  "Pattern for valid identifiers (entity names, field names, enum values).
   Must start with lowercase letter, contain only lowercase letters/digits/hyphens.
   Lowercase-only ensures compatibility with PostgreSQL identifier rules
   (PostgreSQL folds unquoted identifiers to lowercase).
   kebab-case → snake_case conversion: my-entity → my_entity"
  #"^[a-z][a-z0-9]*(-[a-z0-9]+)*$")


;; Use centralized limit from storage-protocol config
(def ^:private max-identifier-length
  "Maximum length for identifiers. Uses centralized value from sp/max-identifier-length."
  sp/max-identifier-length)


(defn- valid-identifier-name?
  "Returns true if the keyword name is a valid identifier for SQL conversion."
  [kw]
  (and (keyword? kw)
       (re-matches identifier-pattern (name kw))))


(defn validate-identifier-name!
  "Validates that a keyword is suitable for use as a SQL identifier.
   Checks both pattern validity and length limit.
   Throws if the name would be invalid after kebab-case → snake_case conversion."
  [context kw]
  (when-not (valid-identifier-name? kw)
    (throw (ex-info (str "Invalid identifier name: " kw ". "
                         "Must start with lowercase letter, contain only lowercase letters/digits/hyphens.")
                    {:type :schema-error/invalid-identifier
                     :context context
                     :value kw
                     :pattern (str identifier-pattern)})))
  (let [kw-name (name kw)]
    (when (> (count kw-name) max-identifier-length)
      (throw (ex-info (str "Identifier name too long: " kw " (" (count kw-name) " chars). "
                           "PostgreSQL limits identifiers to " max-identifier-length " characters.")
                      {:type :schema-error/identifier-too-long
                       :context context
                       :value kw
                       :length (count kw-name)
                       :max-length max-identifier-length})))))


;; === Entity and field validation ===

(defn validate-entity-name
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


(defn validate-field-names
  "Validates field names in an entity definition."
  [entity-name fields]
  (run! #(validate-single-field-name entity-name %) (keys fields)))


;; === Field spec validation ===

(defn- check-extra-keys!
  "Throws if field-spec contains keys not in allowed-keys set."
  [entity-name field-name field-type field-spec allowed-keys]
  (let [extra-keys (apply disj (set (keys field-spec)) allowed-keys)]
    (when (seq extra-keys)
      (throw (ex-info (str "Field type " field-type " has unsupported attributes")
                      {:entity entity-name
                       :field field-name
                       :unsupported-keys extra-keys
                       :allowed-keys allowed-keys})))))


(defn- validate-field-type!
  "Validates that field-spec has a known :type. Returns the type."
  [entity-name field-name field-spec]
  (let [field-type (:type field-spec)]
    (when-not field-type
      (throw (ex-info "Field spec missing :type"
                      {:entity entity-name :field field-name :spec field-spec})))
    (when-not (contains? types/known-field-types field-type)
      (throw (ex-info (str "Unknown field type: " field-type)
                      {:entity entity-name
                       :field field-name
                       :type field-type
                       :known-types types/known-field-types})))
    field-type))


(defn- validate-nullable!
  "Validates :nullable? attribute if present."
  [entity-name field-name field-spec in-variant?]
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
                           :type (type nullable-val)})))))))


(defn- validate-ref-type!
  "Validates :ref field type requirements."
  [entity-name field-name field-spec base-allowed-keys]
  (when-not (:ref-entity field-spec)
    (throw (ex-info "Field type :ref requires :ref-entity"
                    {:entity entity-name :field field-name :spec field-spec})))
  (check-extra-keys! entity-name field-name :ref field-spec
                     (conj base-allowed-keys :ref-entity)))


(defn- validate-enum-type!
  "Validates :enum field type requirements."
  [entity-name field-name field-spec base-allowed-keys]
  (when-not (:enum-name field-spec)
    (throw (ex-info "Field type :enum requires :enum-name"
                    {:entity entity-name :field field-name :spec field-spec})))
  (check-extra-keys! entity-name field-name :enum field-spec
                     (conj base-allowed-keys :enum-name)))


(declare validate-field-spec)


(defn- validate-union-type!
  "Validates :union field type requirements."
  [entity-name field-name field-spec base-allowed-keys]
  (let [variants (:variants field-spec)]
    (when-not (vector? variants)
      (throw (ex-info "Field type :union requires :variants vector"
                      {:entity entity-name :field field-name :spec field-spec})))
    (run! (fn [[idx variant]]
            (validate-field-spec entity-name (str field-name "[" idx "]") variant true))
          (map-indexed vector variants)))
  (check-extra-keys! entity-name field-name :union field-spec
                     (conj base-allowed-keys :variants)))


(defn validate-field-spec
  "Validates a single field spec structure. Throws if invalid.
   When in-variant? is true, :nullable? and :uuid are not allowed (variants cannot have them)."
  ([entity-name field-name field-spec]
   (validate-field-spec entity-name field-name field-spec false))
  ([entity-name field-name field-spec in-variant?]
   (let [field-type (validate-field-type! entity-name field-name field-spec)
         base-allowed-keys (if in-variant? #{:type} #{:type :nullable? :uuid})]
     (validate-nullable! entity-name field-name field-spec in-variant?)
     (case field-type
       :ref (validate-ref-type! entity-name field-name field-spec base-allowed-keys)
       :enum (validate-enum-type! entity-name field-name field-spec base-allowed-keys)
       :union (validate-union-type! entity-name field-name field-spec base-allowed-keys)
       (check-extra-keys! entity-name field-name field-type field-spec base-allowed-keys)))))


(defn- validate-entity-field-specs
  "Validates all field specs for a single entity."
  [entity-name fields]
  (run! (fn [[field-name field-spec]]
          (validate-field-spec entity-name field-name field-spec))
        fields))


(defn validate-field-specs
  "Validates all field specs in all entities."
  [entities-map]
  (run! (fn [[entity-name fields]]
          (validate-entity-field-specs entity-name fields))
        entities-map))


;; === Reference validation ===

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


(defn validate-refs
  "Validates that all references in entities point to existing enums/entities.
   Returns nil if valid, or throws with details."
  [entities-map enums-map]
  (run! (fn [[entity-name fields]]
          (validate-entity-refs entity-name fields entities-map enums-map))
        entities-map))


;; === Union variant validation ===

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


(defn validate-union-variants
  "Validates union variants are not empty and have no duplicates."
  [entities-map]
  (run! (fn [[entity-name fields]]
          (validate-entity-union-variants entity-name fields))
        entities-map))


;; === Constraint validation ===

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


(defn validate-constraints
  "Validates that all constraint fields exist in their entities."
  [entities-map constraints-map]
  (run! (fn [[entity-name constraints]]
          (validate-entity-constraints entities-map entity-name constraints))
        constraints-map))


;; === UUID validation ===

(defn validate-uuid
  "Validates that a value is a UUID. Throws if not."
  [context value]
  (when-not (uuid? value)
    (throw (ex-info "UUID required"
                    (merge context {:value value :type (type value)})))))


(defn check-uuid-uniqueness
  "Checks that a new UUID doesn't conflict with existing ones.
   Uses the known-uuids map in builder for O(1) lookup.
   Throws if duplicate found."
  [known-uuids new-uuid new-location]
  (when-let [existing-location (get known-uuids new-uuid)]
    (throw (ex-info "Duplicate UUID"
                    {:uuid new-uuid
                     :new-location new-location
                     :existing-location existing-location}))))


(defn validate-single-enum-value
  "Validates a single enum value entry. Throws if invalid."
  [known-uuids enum-name entry]
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
  (check-uuid-uniqueness known-uuids (:uuid entry)
                         (str "enum value " enum-name "/" (:value entry))))
