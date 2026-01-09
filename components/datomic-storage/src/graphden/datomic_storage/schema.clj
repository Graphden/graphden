(ns graphden.datomic-storage.schema
  "Schema operations for Datomic storage.

   Contains:
   - Field type conversion to Datomic types
   - Unique constraint detection and validation
   - Schema building for entities, fields, enums
   - Metadata schema definition"
  (:require
    [clojure.tools.logging :as log]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]))


;; === Field type conversion ===

(defn field-type->datomic
  "Converts a field type to Datomic value type."
  [field-spec]
  (let [t (:type field-spec)]
    (case t
      :ref :db.type/ref
      :enum :db.type/ref
      :union :db.type/string  ; Stored as EDN string
      (get util/type->datomic t :db.type/string))))


;; === Constraint helpers ===

(defn single-field-unique-constraint?
  "Returns true if constraint is a single-field unique constraint."
  [constraint]
  (and (= (:type constraint) :unique)
       (= (count (:fields constraint)) 1)))


(defn multi-field-unique-constraint?
  "Returns true if constraint is a multi-field unique constraint."
  [constraint]
  (and (= (:type constraint) :unique)
       (> (count (:fields constraint)) 1)))


(defn warn-multi-field-constraints!
  "Logs debug info about multi-field unique constraints which require application-level enforcement.
   Datomic only supports single-attribute unique constraints natively, but we enforce
   multi-field constraints at the application level during create/update operations."
  [schema entity-name]
  (let [constraints (ds/entity-constraints schema entity-name)
        multi-field (filter multi-field-unique-constraint? constraints)]
    (doseq [c multi-field]
      (log/debug "Multi-field unique constraint will be enforced at application level"
                 {:entity entity-name
                  :fields (:fields c)}))))


(defn get-single-field-constraints
  "Returns a set of field names that are part of single-field unique constraints."
  [schema entity-name]
  (let [constraints (ds/entity-constraints schema entity-name)]
    (->> constraints
         (filter single-field-unique-constraint?)
         (mapcat :fields)
         (set))))


(defn get-multi-field-constraints
  "Returns a seq of multi-field unique constraints for an entity.
   Each constraint is {:type :unique :fields [:field1 :field2 ...]}."
  [schema entity-name]
  (let [constraints (ds/entity-constraints schema entity-name)]
    (filter multi-field-unique-constraint? constraints)))


;; === Multi-field constraint validation ===

(defn validate-multi-field-unique-constraint!
  "Validates that a multi-field unique constraint is not violated.
   db - Datomic database value
   entity-name - entity name (e.g., :user)
   data - the data being inserted/updated
   constraint - the constraint to check {:type :unique :fields [...]}
   field-specs - field specifications (for handling ref types)
   exclude-id - optional id to exclude (for updates)"
  [db entity-name data constraint field-specs exclude-id]
  (let [fields (:fields constraint)
        ;; Validate all constraint fields exist in schema
        _ (doseq [field fields]
            (when-not (contains? field-specs field)
              (throw (ex-info "Constraint references non-existent field"
                              {:type :validation-error/constraint-check-failed
                               :entity entity-name
                               :constraint-fields fields
                               :missing-field field
                               :available-fields (vec (keys field-specs))}))))
        ;; Get values for all constraint fields from data
        field-values (map #(get data %) fields)]
    ;; Only check if all fields have values
    (when (every? some? field-values)
      ;; Build a query to find existing records with same field values
      ;; For ref fields, we need to join through the referenced entity
      (let [id-attr (util/entity-attr entity-name :id)
            ref-var-counter (atom 0)
            ;; Build where clauses for each field value
            field-clauses (mapcat
                            (fn [[field value]]
                              (let [attr (util/entity-attr entity-name field)
                                    field-spec (get field-specs field)]
                                (if (and (= (:type field-spec) :ref) (uuid? value))
                                  ;; Ref field with UUID value: join through referenced entity
                                  (let [ref-var (symbol (str "?ref-" (swap! ref-var-counter inc)))
                                        ref-entity (:ref-entity field-spec)
                                        ref-id-attr (util/entity-attr ref-entity :id)]
                                    [['?e attr ref-var]
                                     [ref-var ref-id-attr value]])
                                  ;; Regular field: direct comparison
                                  [['?e attr value]])))
                            (map vector fields field-values))
            ;; Build the complete query
            base-query (vec (concat '[:find ?e ?id :where]
                                    field-clauses
                                    [['?e id-attr '?id]]))
            ;; Execute query with error handling
            ;; Datomic can throw ExceptionInfo for query errors, RuntimeException for
            ;; connection issues, and IllegalArgumentException for malformed queries.
            ;; We catch these specifically to avoid masking critical errors like OOM.
            wrap-constraint-error (fn [e suffix]
                                    (ex-info (str "Failed to validate unique constraint" suffix)
                                             {:type :validation-error/constraint-check-failed
                                              :entity entity-name
                                              :fields fields
                                              :query base-query
                                              :cause (ex-message e)}
                                             e))
            results (try
                      (d/q base-query db)
                      (catch clojure.lang.ExceptionInfo e
                        (throw (wrap-constraint-error e "")))
                      (catch IllegalArgumentException e
                        (throw (wrap-constraint-error e ": invalid query")))
                      (catch RuntimeException e
                        (throw (wrap-constraint-error e ": runtime error"))))
            ;; Filter out the exclude-id (for updates)
            conflicting (if exclude-id
                          (filter #(not= (second %) exclude-id) results)
                          results)]
        (when (seq conflicting)
          ;; Redact sensitive values to prevent data leakage
          ;; Include conflicting-id for debugging (helps identify which record conflicts)
          (throw (ex-info (str "Unique constraint violation on " (name entity-name)
                               " fields: " (pr-str fields))
                          {:type :constraint-violation/unique
                           :entity entity-name
                           :fields fields
                           :values (sp/redact-sensitive-map (zipmap fields field-values))
                           :conflicting-id (second (first conflicting))})))))))


(defn validate-multi-field-constraints!
  "Validates all multi-field unique constraints for an entity during create/update.
   This provides application-level enforcement since Datomic doesn't support
   composite unique constraints natively."
  [db schema entity-name data field-specs exclude-id]
  (let [constraints (get-multi-field-constraints schema entity-name)]
    (doseq [constraint constraints]
      (validate-multi-field-unique-constraint! db entity-name data constraint field-specs exclude-id))))


;; === Schema builders ===

(defn build-field-schema
  "Builds Datomic schema for a single field.
   Adds :db/unique when field is part of a single-field unique constraint."
  [schema entity-name field-name field-spec]
  (let [attr-ident (util/entity-attr entity-name field-name)
        value-type (field-type->datomic field-spec)
        unique-fields (get-single-field-constraints schema entity-name)
        base-schema {:db/ident attr-ident
                     :db/valueType value-type
                     :db/cardinality :db.cardinality/one}]
    (if (contains? unique-fields field-name)
      (assoc base-schema :db/unique :db.unique/value)
      base-schema)))


(defn build-id-schema
  "Builds Datomic schema for entity's :id attribute (UUID, unique identity)."
  [entity-name]
  {:db/ident (util/entity-attr entity-name :id)
   :db/valueType :db.type/uuid
   :db/cardinality :db.cardinality/one
   :db/unique :db.unique/identity})


(defn build-enum-value-schema
  "Builds Datomic schema for an enum value (just an entity with :db/ident)."
  [enum-name value-kw]
  {:db/ident (util/enum-value-ident enum-name value-kw)})


(defn build-metadata-schema
  "Builds schema for metadata attributes."
  []
  [{:db/ident (util/metadata-attr :uuid)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident (util/metadata-attr :kind)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident (util/metadata-attr :name)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident (util/metadata-attr :parent-uuid)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident (util/metadata-attr :field-type)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident (util/metadata-attr :field-nullable)
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident (util/metadata-attr :field-enum-name)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident (util/metadata-attr :field-ref-entity)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])
