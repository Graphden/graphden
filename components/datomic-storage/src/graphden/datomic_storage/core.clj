(ns graphden.datomic-storage.core
  "Datomic Local implementation of Storage protocol."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.constraints :as constraints]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util.concurrent.locks
      ReentrantReadWriteLock)))


;; === Type mapping ===

(def type->datomic
  "Maps our field types to Datomic value types."
  {:uuid        :db.type/uuid
   :text        :db.type/string
   :int         :db.type/long
   :bool        :db.type/boolean
   :numeric     :db.type/bigdec
   :timestamptz :db.type/instant
   :jsonb       :db.type/string  ; Stored as EDN string
   :bytes       :db.type/bytes})


;; NOTE: Type information (:enum vs :ref, etc.) is preserved through the metadata system,
;; not through Datomic's native schema introspection. Both :enum and :ref map to
;; :db.type/ref in Datomic, but the original type is stored in graphden.metadata entities.


;; === Configuration ===

(def ^:dynamic *query-timeout-ms*
  "Timeout for Datomic queries in milliseconds. Can be rebound per-thread.
   Default is 30000 ms (30 seconds). Use `with-query-timeout` to temporarily change."
  30000)


(defn with-query-timeout
  "Executes f with a custom query timeout (in milliseconds).

   Example:
   (with-query-timeout 60000
     #(sp/query-entities storage :user {}))"
  [timeout-ms f]
  (binding [*query-timeout-ms* timeout-ms]
    (f)))


;; === Attribute naming ===

(defn- entity-attr
  "Creates a Datomic attribute ident for an entity field.
   E.g., :user/name -> user entity, name field"
  [entity-name field-name]
  (keyword (name entity-name) (name field-name)))


(defn- metadata-attr
  "Creates a metadata attribute ident."
  [attr-name]
  (keyword "graphden.metadata" (name attr-name)))


(defn- enum-value-ident
  "Creates a Datomic ident for an enum value.
   E.g., :status.value/active"
  [enum-name value-kw]
  (keyword (str (name enum-name) ".value") (name value-kw)))


;; === Connection validation ===

(defn- ensure-connection!
  "Ensures connection is available for CRUD operations.
   Throws :storage-not-initialized if conn-atom is nil.
   Returns the connection if valid."
  [conn-atom operation-name]
  (if-let [conn @conn-atom]
    conn
    (do
      (log/error "CRUD operation failed: storage not initialized" {:operation operation-name})
      (throw (ex-info "Cannot perform operation: storage not initialized"
                      {:type :storage-not-initialized
                       :operation operation-name})))))


;; Lock utilities imported from storage-protocol


;; === Default configurations ===

(def default-local-config
  "Default configuration for Datomic Local with in-memory storage."
  {:server-type :datomic-local
   :storage-dir :mem
   :system "graphden-dev"})


;; === Schema operations ===

(defn- field-type->datomic
  "Converts a field type to Datomic value type."
  [field-spec]
  (let [t (:type field-spec)]
    (case t
      :ref :db.type/ref
      :enum :db.type/ref
      :union :db.type/string  ; Stored as EDN string
      (get type->datomic t :db.type/string))))


(defn- single-field-unique-constraint?
  "Returns true if constraint is a single-field unique constraint."
  [constraint]
  (and (= (:type constraint) :unique)
       (= (count (:fields constraint)) 1)))


(defn- multi-field-unique-constraint?
  "Returns true if constraint is a multi-field unique constraint."
  [constraint]
  (and (= (:type constraint) :unique)
       (> (count (:fields constraint)) 1)))


(defn- warn-multi-field-constraints!
  "Logs info about multi-field unique constraints which require application-level enforcement.
   Datomic only supports single-attribute unique constraints natively, but we enforce
   multi-field constraints at the application level during create/update operations."
  [schema entity-name]
  (let [constraints (ds/entity-constraints schema entity-name)
        multi-field (filter multi-field-unique-constraint? constraints)]
    (doseq [c multi-field]
      (log/info "Multi-field unique constraint will be enforced at application level"
                {:entity entity-name
                 :fields (:fields c)}))))


(defn- get-single-field-constraints
  "Returns a set of field names that are part of single-field unique constraints."
  [schema entity-name]
  (let [constraints (ds/entity-constraints schema entity-name)]
    (->> constraints
         (filter single-field-unique-constraint?)
         (mapcat :fields)
         (set))))


(defn- get-multi-field-constraints
  "Returns a seq of multi-field unique constraints for an entity.
   Each constraint is {:type :unique :fields [:field1 :field2 ...]}."
  [schema entity-name]
  (let [constraints (ds/entity-constraints schema entity-name)]
    (filter multi-field-unique-constraint? constraints)))


(defn- validate-multi-field-unique-constraint!
  "Validates that a multi-field unique constraint is not violated.
   db - Datomic database value
   entity-name - entity name (e.g., :user)
   data - the data being inserted/updated
   constraint - the constraint to check {:type :unique :fields [...]}
   field-specs - field specifications (for handling ref types)
   exclude-id - optional id to exclude (for updates)"
  [db entity-name data constraint field-specs exclude-id]
  (let [fields (:fields constraint)
        ;; Get values for all constraint fields from data
        field-values (map #(get data %) fields)]
    ;; Only check if all fields have values
    (when (every? some? field-values)
      ;; Build a query to find existing records with same field values
      ;; For ref fields, we need to join through the referenced entity
      (let [id-attr (entity-attr entity-name :id)
            ref-var-counter (atom 0)
            ;; Build where clauses for each field value
            field-clauses (mapcat
                            (fn [[field value]]
                              (let [attr (entity-attr entity-name field)
                                    field-spec (get field-specs field)]
                                (if (and (= (:type field-spec) :ref) (uuid? value))
                                  ;; Ref field with UUID value: join through referenced entity
                                  (let [ref-var (symbol (str "?ref-" (swap! ref-var-counter inc)))
                                        ref-entity (:ref-entity field-spec)
                                        ref-id-attr (entity-attr ref-entity :id)]
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
            ;; Note: Catching broad Exception because Datomic can throw various
            ;; exception types (ExceptionInfo, RuntimeException, IllegalArgumentException)
            ;; depending on query structure and data issues.
            results (try
                      (d/q base-query db)
                      (catch Exception e
                        (throw (ex-info "Failed to validate unique constraint"
                                        {:type :validation-error/constraint-check-failed
                                         :entity entity-name
                                         :fields fields
                                         :query base-query
                                         :cause (ex-message e)}
                                        e))))
            ;; Filter out the exclude-id (for updates)
            conflicting (if exclude-id
                          (filter #(not= (second %) exclude-id) results)
                          results)]
        (when (seq conflicting)
          (throw (ex-info "Unique constraint violation"
                          {:type :constraint-violation/unique
                           :entity entity-name
                           :fields fields
                           :values (zipmap fields field-values)})))))))


(defn- validate-multi-field-constraints!
  "Validates all multi-field unique constraints for an entity during create/update.
   This provides application-level enforcement since Datomic doesn't support
   composite unique constraints natively."
  [db schema entity-name data field-specs exclude-id]
  (let [constraints (get-multi-field-constraints schema entity-name)]
    (doseq [constraint constraints]
      (validate-multi-field-unique-constraint! db entity-name data constraint field-specs exclude-id))))


(defn- build-field-schema
  "Builds Datomic schema for a single field.
   Adds :db/unique when field is part of a single-field unique constraint."
  [schema entity-name field-name field-spec]
  (let [attr-ident (entity-attr entity-name field-name)
        value-type (field-type->datomic field-spec)
        unique-fields (get-single-field-constraints schema entity-name)
        base-schema {:db/ident attr-ident
                     :db/valueType value-type
                     :db/cardinality :db.cardinality/one}]
    (if (contains? unique-fields field-name)
      (assoc base-schema :db/unique :db.unique/value)
      base-schema)))


(defn- build-id-schema
  "Builds Datomic schema for entity's :id attribute (UUID, unique identity)."
  [entity-name]
  {:db/ident (entity-attr entity-name :id)
   :db/valueType :db.type/uuid
   :db/cardinality :db.cardinality/one
   :db/unique :db.unique/identity})


(defn- build-enum-value-schema
  "Builds Datomic schema for an enum value (just an entity with :db/ident)."
  [enum-name value-kw]
  {:db/ident (enum-value-ident enum-name value-kw)})


(defn- build-metadata-schema
  "Builds schema for metadata attributes."
  []
  [{:db/ident (metadata-attr :uuid)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident (metadata-attr :kind)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident (metadata-attr :name)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident (metadata-attr :parent-uuid)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident (metadata-attr :field-type)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident (metadata-attr :field-nullable)
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident (metadata-attr :field-enum-name)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident (metadata-attr :field-ref-entity)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])


;; === Introspection ===

(defn- current-attrs
  "Returns all user-defined attributes (excluding system and metadata)."
  [db]
  (let [all-attrs (d/q '[:find ?ident ?type
                         :where
                         [?e :db/ident ?ident]
                         [?e :db/valueType ?vt]
                         [?vt :db/ident ?type]]
                       db)]
    (->> all-attrs
         (remove (fn [[ident _]]
                   (or (nil? (namespace ident))
                       (str/starts-with? (namespace ident) "db")
                       (str/starts-with? (namespace ident) "fressian")
                       (= (namespace ident) "graphden.metadata"))))
         (into {}))))


(defn- current-enum-values-db
  "Returns all enum value idents (those with .value in namespace)."
  [db]
  (let [all-idents (d/q '[:find ?ident
                          :where
                          [?e :db/ident ?ident]
                          (not [?e :db/valueType _])]
                        db)]
    (->> all-idents
         (map first)
         (filter #(and (namespace %)
                       (str/includes? (namespace %) ".value"))))))


(defn- metadata-schema-exists?
  "Checks if metadata schema attributes exist in the database.
   Returns false if the attribute doesn't exist.
   Re-throws unexpected exceptions (connection errors, etc.)."
  [db]
  (try
    (let [result (d/q '[:find ?e
                        :where [?e :db/ident :graphden.metadata/uuid]]
                      db)]
      (seq result))
    (catch clojure.lang.ExceptionInfo e
      ;; Datomic throws ExceptionInfo for various errors
      ;; Only suppress "not-an-entity" errors (attribute doesn't exist)
      (let [data (ex-data e)]
        (if (= (:db/error data) :db.error/not-an-entity)
          false
          (throw e))))))


(defn- read-metadata
  "Reads metadata entities from the database."
  [db]
  (when (metadata-schema-exists? db)
    (let [;; Query entities (no parent-uuid)
          entities (d/q '[:find ?uuid ?name
                          :where
                          [?e :graphden.metadata/uuid ?uuid]
                          [?e :graphden.metadata/kind :entity]
                          [?e :graphden.metadata/name ?name]]
                        db)
          ;; Query fields (with parent-uuid, field-type, field-nullable)
          fields (d/q '[:find ?uuid ?name ?parent-uuid ?field-type ?field-nullable
                        :where
                        [?e :graphden.metadata/uuid ?uuid]
                        [?e :graphden.metadata/kind :field]
                        [?e :graphden.metadata/name ?name]
                        [?e :graphden.metadata/parent-uuid ?parent-uuid]
                        [?e :graphden.metadata/field-type ?field-type]
                        [?e :graphden.metadata/field-nullable ?field-nullable]]
                      db)
          ;; Query fields with enum-name (optional attribute, only for enum fields)
          fields-enum-names (d/q '[:find ?uuid ?enum-name
                                   :where
                                   [?e :graphden.metadata/uuid ?uuid]
                                   [?e :graphden.metadata/kind :field]
                                   [?e :graphden.metadata/field-enum-name ?enum-name]]
                                 db)
          field-uuid->enum-name (into {} fields-enum-names)
          ;; Query fields with ref-entity (optional attribute, only for ref fields)
          fields-ref-entities (d/q '[:find ?uuid ?ref-entity
                                     :where
                                     [?e :graphden.metadata/uuid ?uuid]
                                     [?e :graphden.metadata/kind :field]
                                     [?e :graphden.metadata/field-ref-entity ?ref-entity]]
                                   db)
          field-uuid->ref-entity (into {} fields-ref-entities)
          ;; Query enums (no parent-uuid)
          enums (d/q '[:find ?uuid ?name
                       :where
                       [?e :graphden.metadata/uuid ?uuid]
                       [?e :graphden.metadata/kind :enum]
                       [?e :graphden.metadata/name ?name]]
                     db)
          ;; Query enum-values (with parent-uuid)
          enum-values (d/q '[:find ?uuid ?name ?parent-uuid
                             :where
                             [?e :graphden.metadata/uuid ?uuid]
                             [?e :graphden.metadata/kind :enum-value]
                             [?e :graphden.metadata/name ?name]
                             [?e :graphden.metadata/parent-uuid ?parent-uuid]]
                           db)]
      (when (or (seq entities) (seq fields) (seq enums) (seq enum-values))
        (let [;; Build entity uuid->name map
              entity-uuid->name (into {} entities)
              ;; Build enum uuid->name map
              enum-uuid->name (into {} enums)]
          {:entities entity-uuid->name
           :fields (into {}
                         (for [[uuid field-name parent-uuid field-type field-nullable] fields]
                           [uuid (cond-> {:entity (get entity-uuid->name parent-uuid)
                                          :field field-name
                                          :type field-type
                                          :nullable? field-nullable}
                                   ;; Include enum-name if present
                                   (get field-uuid->enum-name uuid)
                                   (assoc :enum-name (get field-uuid->enum-name uuid))
                                   ;; Include ref-entity if present
                                   (get field-uuid->ref-entity uuid)
                                   (assoc :ref-entity (get field-uuid->ref-entity uuid)))]))
           :enums enum-uuid->name
           :enum-values (into {}
                              (for [[uuid value-name parent-uuid] enum-values]
                                [uuid {:enum (get enum-uuid->name parent-uuid)
                                       :value value-name}]))})))))


(defn- build-entity-metadata-tx
  "Builds transaction data for a single entity's metadata."
  [schema entity-name]
  {:graphden.metadata/uuid (ds/entity-uuid schema entity-name)
   :graphden.metadata/kind :entity
   :graphden.metadata/name entity-name})


(defn- build-field-metadata-tx
  "Builds transaction data for a single field's metadata."
  [schema entity-name field-name field-spec]
  (cond-> {:graphden.metadata/uuid (:uuid field-spec)
           :graphden.metadata/kind :field
           :graphden.metadata/name field-name
           :graphden.metadata/parent-uuid (ds/entity-uuid schema entity-name)
           :graphden.metadata/field-type (:type field-spec)
           :graphden.metadata/field-nullable (get field-spec :nullable? false)}
    ;; Include enum-name for enum fields
    (= (:type field-spec) :enum)
    (assoc :graphden.metadata/field-enum-name (:enum-name field-spec))
    ;; Include ref-entity for ref fields
    (= (:type field-spec) :ref)
    (assoc :graphden.metadata/field-ref-entity (:ref-entity field-spec))))


(defn- build-enum-metadata-tx
  "Builds transaction data for a single enum's metadata."
  [enum-name {:keys [uuid]}]
  {:graphden.metadata/uuid uuid
   :graphden.metadata/kind :enum
   :graphden.metadata/name enum-name})


(defn- build-enum-value-metadata-tx
  "Builds transaction data for a single enum value's metadata."
  [parent-uuid value-kw value-uuid]
  {:graphden.metadata/uuid value-uuid
   :graphden.metadata/kind :enum-value
   :graphden.metadata/name value-kw
   :graphden.metadata/parent-uuid parent-uuid})


(defn- save-metadata!
  "Saves metadata to the database (retract old, then assert new).

   NOTE: Uses two separate transactions because Datomic doesn't allow
   retracting and asserting the same :db/unique value in a single transaction.
   If the second transaction fails after the first succeeds, we attempt to
   restore the old metadata to maintain consistency.

   SAFETY: If rollback also fails, we throw an exception with both errors
   to ensure the caller knows the database may be in an inconsistent state."
  [conn schema]
  ;; Capture old metadata for potential rollback
  (let [db (d/db conn)
        existing (d/q '[:find ?e
                        :where [?e :graphden.metadata/uuid _]]
                      db)
        ;; Pull full entity data for rollback
        old-metadata (when (seq existing)
                       (mapv (fn [[e]] (d/pull db '[*] e)) existing))]

    ;; First, retract all existing metadata
    (when (seq existing)
      (log/debug "Retracting old metadata" {:count (count existing)})
      (d/transact conn {:tx-data (vec (map (fn [[e]] [:db/retractEntity e]) existing))}))

    ;; Then add new metadata
    (let [tx-data
          (concat
            ;; Entities
            (map #(build-entity-metadata-tx schema %) (ds/entities schema))
            ;; Fields
            (mapcat (fn [entity-name]
                      (map (fn [[field-name field-spec]]
                             (build-field-metadata-tx schema entity-name field-name field-spec))
                           (ds/entity-fields schema entity-name)))
                    (ds/entities schema))
            ;; Enums
            (map (fn [[enum-name enum-def]] (build-enum-metadata-tx enum-name enum-def))
                 (ds/enums schema))
            ;; Enum values
            (mapcat (fn [[_enum-name {:keys [uuid values]}]]
                      (map (fn [[value-kw value-uuid]]
                             (build-enum-value-metadata-tx uuid value-kw value-uuid))
                           values))
                    (ds/enums schema)))]
      (when (seq tx-data)
        (try
          (log/debug "Asserting new metadata" {:count (count tx-data)})
          (d/transact conn {:tx-data (vec tx-data)})
          ;; Note: We intentionally catch broad Exception here because:
          ;; 1. Datomic can throw various exception types (ExceptionInfo, RuntimeException, etc.)
          ;; 2. ANY transaction failure should trigger a rollback attempt
          ;; 3. We re-throw the original exception after rollback attempt
          (catch Exception e
            (log/error e "Failed to save new metadata, attempting rollback")
            (if (seq old-metadata)
              (try
                ;; Remove :db/id from old data (Datomic will assign new ids)
                (let [restore-data (mapv #(dissoc % :db/id) old-metadata)]
                  (d/transact conn {:tx-data restore-data})
                  (log/info "Successfully restored old metadata after failure"))
                ;; Rollback can also fail with various exception types
                (catch Exception restore-ex
                  (log/error restore-ex "Failed to restore old metadata")
                  ;; Throw combined exception so caller knows DB may be inconsistent
                  (throw (ex-info "Metadata save failed and rollback also failed - database may be inconsistent"
                                  {:type :metadata-error/rollback-failed
                                   :original-error (ex-message e)
                                   :rollback-error (ex-message restore-ex)}
                                  e))))
              (log/warn "No old metadata to restore - this was likely first initialization"))
            (throw e)))))))


;; === Destructive change checks ===
;; Using shared utilities from sp/check-removed! and sp/check-type-change!


;; === Migration helpers ===

(defn- check-single-field-type!
  "Checks type compatibility for a single field during migration."
  [db old-metadata old-entity-name field-name field-spec]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (when old-field-info
      (let [old-attr (entity-attr old-entity-name (:field old-field-info))
            attr-exists? (seq (d/q '[:find ?e
                                     :in $ ?attr
                                     :where [?e :db/ident ?attr]]
                                   db old-attr))]
        ;; Check for metadata/DB inconsistency
        (when-not attr-exists?
          (throw (ex-info "Metadata/DB inconsistency: field exists in metadata but not in database"
                          {:type :metadata-inconsistency
                           :entity (keyword (namespace old-attr))
                           :field field-name
                           :expected-attr old-attr})))
        ;; Use type from metadata (preserves JSONB/Union correctly)
        (let [old-type (:type old-field-info)
              new-type (:type field-spec)
              old-nullable? (:nullable? old-field-info)
              new-nullable? (get field-spec :nullable? false)]
          (sp/check-type-change! (keyword (namespace old-attr)) field-name old-type new-type)
          (sp/check-nullable-change! (keyword (namespace old-attr)) field-name old-nullable? new-nullable?))))))


(defn- check-entity-fields-type!
  "Checks type compatibility for all fields of a single entity."
  [db old-metadata schema entity-name]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)]
    (when old-entity-name
      (run! (fn [[field-name field-spec]]
              (check-single-field-type! db old-metadata old-entity-name field-name field-spec))
            (ds/entity-fields schema entity-name)))))


(defn- process-existing-enum-value!
  "Processes a single enum value during migration (add if new)."
  [old-metadata enum-name value-kw value-uuid new-schema created-enum-values]
  (when-not (get (:enum-values old-metadata) value-uuid)
    (swap! new-schema conj (build-enum-value-schema enum-name value-kw))
    (swap! created-enum-values conj {:enum enum-name :value value-kw})))


(defn- process-single-enum!
  "Processes a single enum during migration."
  [old-metadata enum-name {:keys [uuid values]} created-enums renamed-enums new-schema created-enum-values]
  (if-let [old-enum-name (get (:enums old-metadata) uuid)]
    (do
      (when (not= old-enum-name enum-name)
        (swap! renamed-enums assoc old-enum-name enum-name))
      ;; Check for new values
      (run! (fn [[value-kw value-uuid]]
              (process-existing-enum-value! old-metadata enum-name value-kw value-uuid
                                            new-schema created-enum-values))
            values))
    (do
      (swap! created-enums conj enum-name)
      (run! (fn [[value-kw _]]
              (swap! new-schema conj (build-enum-value-schema enum-name value-kw))
              (swap! created-enum-values conj {:enum enum-name :value value-kw}))
            values))))


(defn- process-existing-field!
  "Processes an existing field during migration."
  [old-field-info entity-name field-name renamed-fields]
  (when (not= (:field old-field-info) field-name)
    (swap! renamed-fields conj {:entity entity-name
                                :old-field (:field old-field-info)
                                :new-field field-name})))


(defn- process-single-field!
  "Processes a single field during migration."
  [schema old-metadata entity-name field-name field-spec new-schema created-fields renamed-fields]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (if old-field-info
      (process-existing-field! old-field-info entity-name field-name renamed-fields)
      (do
        (swap! new-schema conj (build-field-schema schema entity-name field-name field-spec))
        (swap! created-fields conj {:entity entity-name :field field-name})))))


(defn- process-existing-entity!
  "Processes an existing entity during migration."
  [schema old-metadata entity-name old-entity-name renamed-entities
   new-schema created-fields renamed-fields]
  (when (not= old-entity-name entity-name)
    (swap! renamed-entities assoc old-entity-name entity-name))
  ;; Process fields
  (run! (fn [[field-name field-spec]]
          (process-single-field! schema old-metadata entity-name field-name field-spec
                                 new-schema created-fields renamed-fields))
        (ds/entity-fields schema entity-name)))


(defn- process-single-entity!
  "Processes a single entity during migration."
  [schema old-metadata entity-name created-entities renamed-entities
   new-schema created-fields renamed-fields]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)]
    (if old-entity-name
      (process-existing-entity! schema old-metadata entity-name old-entity-name
                                renamed-entities new-schema created-fields renamed-fields)
      (do
        (swap! created-entities conj entity-name)
        ;; Add :id attribute for new entity
        (swap! new-schema conj (build-id-schema entity-name))
        (run! (fn [[field-name field-spec]]
                (swap! new-schema conj (build-field-schema schema entity-name field-name field-spec))
                (swap! created-fields conj {:entity entity-name :field field-name}))
              (ds/entity-fields schema entity-name))))))


;; === Schema builders for initialization ===

(defn- build-enum-schemas
  "Builds all enum value schemas for initialization."
  [schema]
  (mapcat (fn [[enum-name {:keys [values]}]]
            (map (fn [[value-kw _]] (build-enum-value-schema enum-name value-kw))
                 values))
          (ds/enums schema)))


(defn- build-field-schemas
  "Builds all field schemas for initialization.
   Includes :id attribute for each entity."
  [schema]
  (mapcat (fn [entity-name]
            (cons (build-id-schema entity-name)
                  (map (fn [[field-name field-spec]]
                         (build-field-schema schema entity-name field-name field-spec))
                       (ds/entity-fields schema entity-name))))
          (ds/entities schema)))


;; Use shared functions from storage-protocol:
;; sp/collect-created-fields, sp/collect-created-enum-values,
;; sp/collect-field-uuids, sp/collect-enum-value-uuids


;; === Initialize ===

(defn- do-initialize
  "Performs initialization/migration of the database."
  [conn schema]
  ;; Log info about multi-field unique constraints (enforced at application level)
  (doseq [entity-name (ds/entities schema)]
    (warn-multi-field-constraints! schema entity-name))

  (let [db (d/db conn)
        old-metadata (read-metadata db)]

    (if (nil? old-metadata)
      ;; First-time initialization
      (let [metadata-schema (build-metadata-schema)
            enum-schema (build-enum-schemas schema)
            field-schema (build-field-schemas schema)
            all-schema (concat metadata-schema enum-schema field-schema)]

        ;; Transact all schema
        (when (seq all-schema)
          (d/transact conn {:tx-data (vec all-schema)}))

        ;; Save metadata
        (save-metadata! conn schema)

        ;; Return changes
        {:entities {:created (vec (ds/entities schema)) :renamed {}}
         :fields {:created (sp/collect-created-fields schema) :renamed []}
         :enums {:created (vec (keys (ds/enums schema))) :renamed {}}
         :enum-values {:created (sp/collect-created-enum-values schema)}})

      ;; Migration
      (do
        ;; Check for destructive changes
        (let [old-entity-uuids (set (keys (:entities old-metadata)))
              new-entity-uuids (set (map #(ds/entity-uuid schema %) (ds/entities schema)))]
          (sp/check-removed! "entities" old-entity-uuids new-entity-uuids
                             #(get (:entities old-metadata) %)))

        (let [old-field-uuids (set (keys (:fields old-metadata)))
              new-field-uuids (sp/collect-field-uuids schema)]
          (sp/check-removed! "fields" old-field-uuids new-field-uuids
                             #(get (:fields old-metadata) %)))

        (let [old-enum-uuids (set (keys (:enums old-metadata)))
              new-enum-uuids (set (map (fn [[_ {:keys [uuid]}]] uuid) (ds/enums schema)))]
          (sp/check-removed! "enums" old-enum-uuids new-enum-uuids
                             #(get (:enums old-metadata) %)))

        (let [old-value-uuids (set (keys (:enum-values old-metadata)))
              new-value-uuids (sp/collect-enum-value-uuids schema)]
          (sp/check-removed! "enum values" old-value-uuids new-value-uuids
                             #(get (:enum-values old-metadata) %)))

        ;; Check type changes
        (run! #(check-entity-fields-type! db old-metadata schema %) (ds/entities schema))

        ;; Compute changes and apply new schema
        (let [created-entities (atom [])
              renamed-entities (atom {})
              created-fields (atom [])
              renamed-fields (atom [])
              created-enums (atom [])
              renamed-enums (atom {})
              created-enum-values (atom [])
              new-schema (atom [])]

          ;; Process enums
          (run! (fn [[enum-name enum-def]]
                  (process-single-enum! old-metadata enum-name enum-def
                                        created-enums renamed-enums
                                        new-schema created-enum-values))
                (ds/enums schema))

          ;; Process entities and fields
          (run! #(process-single-entity! schema old-metadata %
                                         created-entities renamed-entities
                                         new-schema created-fields renamed-fields)
                (ds/entities schema))

          ;; Transact new schema
          (when (seq @new-schema)
            (d/transact conn {:tx-data @new-schema}))

          ;; Save metadata
          (save-metadata! conn schema)

          ;; Return changes
          {:entities {:created @created-entities :renamed @renamed-entities}
           :fields {:created @created-fields :renamed @renamed-fields}
           :enums {:created @created-enums :renamed @renamed-enums}
           :enum-values {:created @created-enum-values}})))))


;; === CRUD helpers ===

(defn- convert-field-value
  "Converts field values for Datomic storage:
   - Enum values (keywords) -> entity idents
   - Ref values (UUIDs) -> lookup refs
   Returns the original value for other field types."
  [field-specs field-name v]
  (let [field-spec (get field-specs field-name)]
    (case (:type field-spec)
      :enum (if (keyword? v)
              (enum-value-ident (:enum-name field-spec) v)
              v)
      :ref (if (uuid? v)
             ;; Convert UUID to lookup ref for the referenced entity type
             (let [ref-entity (:ref-entity field-spec)]
               [(entity-attr ref-entity :id) v])
             v)
      ;; Default: return as-is
      v)))


(defn- entity->tx
  "Converts entity map to Datomic transaction data.
   Uses namespaced attributes for the entity type.
   The :id field is stored as :entity-name/id (UUID).
   Enum fields are converted to entity idents.
   Ref fields are converted to lookup refs."
  [entity-name data id temp-id field-specs]
  (let [base-tx {:db/id temp-id
                 (entity-attr entity-name :id) id}]
    (reduce-kv (fn [acc k v]
                 (if (= k :id)
                   acc  ; Already handled above
                   (let [converted-v (convert-field-value field-specs k v)]
                     (assoc acc (entity-attr entity-name k) converted-v))))
               base-tx
               data)))


(defn- pull-entity
  "Pulls an entity by id (UUID) from the database.
   Queries by :entity-name/id attribute."
  [db entity-name id entity-fields]
  (let [id-attr (entity-attr entity-name :id)
        ;; Include :id in pattern along with other fields
        pattern (into [id-attr]
                      (map #(entity-attr entity-name %) entity-fields))
        ;; Find the entity by its :entity-name/id attribute
        eid (ffirst (d/q {:find '[?e]
                          :in '[$ ?id]
                          :where [['?e id-attr '?id]]}
                         db id))]
    (when eid
      (let [result (d/pull db pattern eid)]
        (reduce-kv (fn [acc k v]
                     (let [field-name (keyword (name k))]
                       (assoc acc field-name v)))
                   {}
                   result)))))


(defn- get-entity-fields
  "Gets field names for an entity from metadata."
  [db entity-name]
  (let [metadata (read-metadata db)]
    (->> (:fields metadata)
         (vals)
         (filter #(= (:entity %) entity-name))
         (map :field))))


(defn- get-fields-with-specs
  "Gets field specifications for an entity from metadata.
   Returns {field-name {:type ... :nullable? ... :enum-name ... :ref-entity ...}}."
  [db entity-name]
  (let [metadata (read-metadata db)]
    (->> (:fields metadata)
         (vals)
         (filter #(= (:entity %) entity-name))
         (map (fn [{:keys [field nullable? enum-name ref-entity] field-type :type}]
                [field (cond-> {:type field-type :nullable? nullable?}
                         enum-name (assoc :enum-name enum-name)
                         ref-entity (assoc :ref-entity ref-entity))]))
         (into {}))))


(defn- create-entity-impl
  "Creates a new entity in Datomic.
   Validates required fields before creating."
  [conn entity-name data]
  (let [db (d/db conn)
        field-specs (get-fields-with-specs db entity-name)]
    (when (seq field-specs)
      (sp/validate-required-fields! entity-name field-specs data))
    (let [id (or (:id data) (random-uuid))
          temp-id (str "new-entity-" (random-uuid))
          tx-data [(entity->tx entity-name (assoc data :id id) id temp-id field-specs)]]
      (d/transact conn {:tx-data tx-data})
      (let [new-db (d/db conn)
            fields (get-entity-fields new-db entity-name)]
        (pull-entity new-db entity-name id fields)))))


(defn- read-entity-impl
  "Reads an entity by id."
  [conn entity-name id]
  (let [db (d/db conn)
        fields (get-entity-fields db entity-name)]
    (pull-entity db entity-name id fields)))


(defn- update-entity-impl
  "Updates an entity by id.
   Validates required fields after merging."
  [conn entity-name id data]
  (let [db (d/db conn)
        fields (get-entity-fields db entity-name)
        field-specs (get-fields-with-specs db entity-name)
        id-attr (entity-attr entity-name :id)
        ;; Find entity id
        eid (ffirst (d/q {:find '[?e]
                          :in '[$ ?id]
                          :where [['?e id-attr '?id]]}
                         db id))]
    (when-not eid
      (throw (ex-info "Entity not found"
                      {:type :not-found
                       :entity entity-name
                       :id id})))
    (let [existing (pull-entity db entity-name id fields)
          updated (merge existing data {:id id})]
      (when (seq field-specs)
        (sp/validate-required-fields! entity-name field-specs updated))
      ;; Use actual entity id for update
      (let [tx-data [(entity->tx entity-name updated id eid field-specs)]]
        (d/transact conn {:tx-data tx-data})
        (let [new-db (d/db conn)]
          (pull-entity new-db entity-name id fields))))))


(defn- delete-entity-impl
  "Deletes an entity by id."
  [conn entity-name id]
  (let [db (d/db conn)
        id-attr (entity-attr entity-name :id)
        eid (ffirst (d/q {:find '[?e]
                          :in '[$ ?id]
                          :where [['?e id-attr '?id]]}
                         db id))]
    (if eid
      (do
        (d/transact conn {:tx-data [[:db/retractEntity eid]]})
        true)
      false)))


(defn- query-entities-impl
  "Queries entities by conditions.
   where must be nil or a map of field->value for equality matching."
  [conn entity-name where]
  (sp/validate-where-clause! where)
  (let [db (d/db conn)
        fields (get-entity-fields db entity-name)
        id-attr (entity-attr entity-name :id)
        pattern (into [id-attr] (map #(entity-attr entity-name %) fields))
        ;; Build where clauses - must have at least one clause to identify entities of this type
        base-where [['?e id-attr '_]]  ; Match entities that have an :id attribute
        where-clauses (if (empty? where)
                        base-where
                        (into base-where
                              (map (fn [[k v]]
                                     ['?e (entity-attr entity-name k) v])
                                   where)))
        query {:find '[?e]
               :where where-clauses}
        entity-ids (d/q query db)]
    (map (fn [[eid]]
           (let [result (d/pull db pattern eid)]
             (reduce-kv (fn [acc k v]
                          (let [field-name (keyword (name k))]
                            (assoc acc field-name v)))
                        {}
                        result)))
         entity-ids)))


;; === Batch CRUD helpers ===

(defn- create-entities-impl
  "Creates multiple entities in a single transaction.
   Throws :duplicate-ids if duplicate IDs found in batch."
  [conn entity-name data-seq]
  (if (empty? data-seq)
    []
    (do
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [db (d/db conn)
            field-specs (get-fields-with-specs db entity-name)
            ;; Validate all records first
            _ (when (seq field-specs)
                (doseq [data data-seq]
                  (sp/validate-required-fields! entity-name field-specs data)))
            ;; Prepare transaction data
            records (map (fn [data]
                           (let [id (or (:id data) (random-uuid))
                                 temp-id (str "new-entity-" (random-uuid))]
                             {:id id
                              :temp-id temp-id
                              :data (assoc data :id id)}))
                         data-seq)
            tx-data (map (fn [{:keys [id temp-id data]}]
                           (entity->tx entity-name data id temp-id field-specs))
                         records)]
        (d/transact conn {:tx-data (vec tx-data)})
        ;; Read back created entities
        (let [new-db (d/db conn)
              fields (get-entity-fields new-db entity-name)
              ids (map :id records)
              results (keep (fn [id] (pull-entity new-db entity-name id fields)) ids)
              expected-count (count data-seq)
              actual-count (count results)]
          ;; Validate that all records were created
          (when (not= expected-count actual-count)
            (throw (ex-info "Batch insert returned unexpected number of records"
                            {:type :batch-insert-mismatch
                             :entity-name entity-name
                             :expected-count expected-count
                             :actual-count actual-count})))
          results)))))


(defn- read-entities-impl
  "Reads multiple entities by ids. Returns {id -> record}."
  [conn entity-name ids]
  (if (empty? ids)
    {}
    (let [db (d/db conn)
          fields (get-entity-fields db entity-name)
          id-attr (entity-attr entity-name :id)
          pattern (into [id-attr] (map #(entity-attr entity-name %) fields))
          ;; Find all entity ids in one query
          results (d/q {:find '[?e ?id]
                        :in '[$ [?id ...]]
                        :where [['?e id-attr '?id]]}
                       db (vec ids))]
      (->> results
           (map (fn [[eid id]]
                  (let [result (d/pull db pattern eid)]
                    [id (reduce-kv (fn [acc k v]
                                     (let [field-name (keyword (name k))]
                                       (assoc acc field-name v)))
                                   {}
                                   result)])))
           (into {})))))


(defn- delete-entities-impl
  "Deletes multiple entities by ids. Returns count of deleted."
  [conn entity-name ids]
  (if (empty? ids)
    0
    (let [db (d/db conn)
          id-attr (entity-attr entity-name :id)
          ;; Find all entity ids in one query
          results (d/q {:find '[?e]
                        :in '[$ [?id ...]]
                        :where [['?e id-attr '?id]]}
                       db (vec ids))
          entity-ids (map first results)]
      (when (seq entity-ids)
        (d/transact conn {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) entity-ids)}))
      (count entity-ids))))


;; === ExecutionGraph helpers ===

(defn- collect-parent-chains-batch
  "Collects parent chains for multiple fns.
   Returns {fn-id -> [chain-fn-ids from child to root]}.
   Uses iterative approach to collect all parent chains at once."
  [db fn-ids]
  (if (empty? fn-ids)
    {}
    (loop [chains (into {} (map (fn [fid] [fid [fid]]) fn-ids))
           ;; Track current parents for each chain
           current-parents (into {} (map (fn [fid] [fid fid]) fn-ids))]
      (let [;; Get all current parent IDs that need parent lookup
            ids-to-lookup (set (filter some? (vals current-parents)))
            ;; Batch query parent-fn-ids for all current nodes
            parent-rows (when (seq ids-to-lookup)
                          (d/q '[:find ?fn-id ?parent-id
                                 :in $ [?fn-id ...]
                                 :where
                                 [?e :fn/id ?fn-id]
                                 [?e :fn/parent-fn-id ?parent-id]]
                               db (vec ids-to-lookup)))
            parent-map (into {} parent-rows)]
        (if (empty? parent-map)
          chains
          ;; Update chains and current-parents for next iteration
          (let [new-chains (reduce (fn [acc [origin current-id]]
                                     (if-let [parent-id (get parent-map current-id)]
                                       (update acc origin conj parent-id)
                                       acc))
                                   chains
                                   current-parents)
                new-parents (reduce (fn [acc [origin current-id]]
                                      (if-let [parent-id (get parent-map current-id)]
                                        (assoc acc origin parent-id)
                                        (dissoc acc origin)))
                                    current-parents
                                    current-parents)]
            (if (empty? new-parents)
              new-chains
              (recur new-chains new-parents))))))))


(defn- load-arg-values-batch
  "Loads all arg-values for a set of fn-ids.
   Returns seq of arg-value maps."
  [db fn-ids]
  (if (empty? fn-ids)
    []
    (let [rows (d/q '[:find ?id ?owner-fn-id ?arg-schema-id ?value
                      :in $ [?owner-id ...]
                      :where
                      [?e :arg-value/id ?id]
                      [?e :arg-value/owner-fn-id ?owner-fn-id]
                      [?e :arg-value/arg-schema-id ?arg-schema-id]
                      [?e :arg-value/value ?value]]
                    db (vec fn-ids))]
      (map (fn [[id owner-fn-id arg-schema-id value]]
             {:id id
              :owner-fn-id owner-fn-id
              :arg-schema-id arg-schema-id
              :value value})
           rows))))


(defn- merge-arg-values-for-chain
  "Gets merged arg-values for a parent chain (child overrides parent).
   Uses pre-loaded arg-values to avoid additional queries.
   Returns {arg-schema-id -> arg-value-map}."
  [all-arg-values chain]
  (when (seq chain)
    (let [chain-set (set chain)
          chain-pos (zipmap chain (range))
          ;; Filter arg-values belonging to this chain
          chain-arg-values (filter #(chain-set (:owner-fn-id %)) all-arg-values)]
      ;; Group by arg-schema-id, pick the one with lowest chain position (closest to target fn)
      (->> chain-arg-values
           (group-by :arg-schema-id)
           (map (fn [[arg-schema-id avs]]
                  [arg-schema-id (apply min-key #(get chain-pos (:owner-fn-id %) Integer/MAX_VALUE) avs)]))
           (into {})))))


(defn- extract-potential-fn-refs
  "Extracts potential fn-id references from arg-values.
   Returns set of UUIDs that might be fn references."
  [arg-values-map]
  (->> (vals arg-values-map)
       (map :value)
       (keep sp/try-parse-uuid)
       (set)))


(defn- classify-uuid-refs
  "Classifies UUID references as either fn refs or fn-result-value refs.
   Returns {:fn-ids #{...} :frv-ids #{...}}.
   Gracefully handles missing fn-result-value attribute (returns empty frv-ids)."
  [db uuid-candidates]
  (if (empty? uuid-candidates)
    {:fn-ids #{} :frv-ids #{}}
    (let [uuids-vec (vec uuid-candidates)
          fn-results (d/q '[:find ?fn-id
                            :in $ [?fn-id ...]
                            :where
                            [?e :fn/id ?fn-id]]
                          db uuids-vec)
          ;; Try to query fn-result-value, handle missing attribute gracefully
          frv-results (try
                        (d/q '[:find ?frv-id
                               :in $ [?frv-id ...]
                               :where
                               [?e :fn-result-value/id ?frv-id]]
                             db uuids-vec)
                        (catch clojure.lang.ExceptionInfo e
                          ;; :db.error/not-an-entity means attribute doesn't exist
                          (if (= :db.error/not-an-entity (:db/error (ex-data e)))
                            []
                            (throw e))))]
      {:fn-ids (set (map first fn-results))
       :frv-ids (set (map first frv-results))})))


(defn- load-fn-result-values-batch
  "Loads fn-result-values by their IDs.
   Returns {fn-result-value-id -> fn-result-value-record}."
  [db frv-ids]
  (if (empty? frv-ids)
    {}
    (let [rows (d/q '[:find ?id ?fn-id
                      :in $ [?id ...]
                      :where
                      [?e :fn-result-value/id ?id]
                      [?e :fn-result-value/fn-id ?fn-id]]
                    db (vec frv-ids))]
      (->> rows
           (map (fn [[id fn-id]]
                  [id {:id id
                       :fn-id fn-id}]))
           (into {})))))


(defn- load-fns-batch
  "Loads multiple fns by id. Returns {fn-id -> fn-record}."
  [db fn-ids]
  (if (empty? fn-ids)
    {}
    (let [;; Query all fns at once - required fields
          rows (d/q '[:find ?id ?name ?fn-schema-id
                      :in $ [?id ...]
                      :where
                      [?e :fn/id ?id]
                      [?e :fn/name ?name]
                      [?e :fn/fn-schema-id ?fn-schema-id]]
                    db (vec fn-ids))
          ;; Query parent-fn-ids separately (optional attribute)
          parent-rows (d/q '[:find ?id ?parent-fn-id
                             :in $ [?id ...]
                             :where
                             [?e :fn/id ?id]
                             [?e :fn/parent-fn-id ?parent-fn-id]]
                           db (vec fn-ids))
          parent-map (into {} parent-rows)]
      (->> rows
           (map (fn [[id fn-name fn-schema-id]]
                  [id {:id id
                       :name fn-name
                       :fn-schema-id fn-schema-id
                       :parent-fn-id (get parent-map id)}]))
           (into {})))))


(defn- load-fn-schemas-batch
  "Loads multiple fn-schemas by id. Returns {fn-schema-id -> fn-schema-record}."
  [db fn-schema-ids]
  (if (empty? fn-schema-ids)
    {}
    (let [rows (d/q '[:find ?id ?name ?returned-type
                      :in $ [?id ...]
                      :where
                      [?e :fn-schema/id ?id]
                      [?e :fn-schema/name ?name]
                      [?e :fn-schema/returned-type ?returned-type]]
                    db (vec fn-schema-ids))]
      (->> rows
           (map (fn [[id schema-name returned-type]]
                  [id {:id id
                       :name schema-name
                       :returned-type returned-type}]))
           (into {})))))


(defn- load-arg-schemas-batch
  "Loads arg-schemas for multiple fn-schema-ids. Returns {arg-schema-id -> arg-schema-record}."
  [db fn-schema-ids]
  (if (empty? fn-schema-ids)
    {}
    (let [rows (d/q '[:find ?id ?fn-schema-id ?name ?type ?required
                      :in $ [?fns-id ...]
                      :where
                      [?e :arg-schema/id ?id]
                      [?e :arg-schema/fn-schema-id ?fn-schema-id]
                      [?e :arg-schema/name ?name]
                      [?e :arg-schema/type ?type]
                      [(get-else $ ?e :arg-schema/required true) ?required]]
                    db (vec fn-schema-ids))]
      (->> rows
           (map (fn [[id fn-schema-id arg-name arg-type required]]
                  [id {:id id
                       :fn-schema-id fn-schema-id
                       :name arg-name
                       :type arg-type
                       :required required}]))
           (into {})))))


(defn- resolve-execution-graph-impl
  "Resolves complete execution graph for a function.
   Uses batched BFS to collect all transitively referenced functions and fn-result-values.
   Throws if iteration count exceeds sp/*max-graph-iterations*.

   This implementation uses batch queries to minimize database round-trips:
   1. Process pending fn-ids in batches
   2. Batch load parent chains
   3. Batch load arg-values for all chain members
   4. Extract fn-refs and fn-result-value refs, continue until graph is complete
   5. Final batch load of all fns, fn-schemas, arg-schemas, fn-result-values"
  [conn fn-id]
  (let [db (d/db conn)
        ;; Check if fn exists
        exists? (seq (d/q '[:find ?e
                            :in $ ?fn-id
                            :where
                            [?e :fn/id ?fn-id]]
                          db fn-id))]
    (when-not exists?
      (throw (ex-info "Function not found"
                      {:type :not-found
                       :fn-id fn-id})))
    ;; Phase 1: Discover all fn-ids and fn-result-values in the graph using batched BFS
    (loop [to-visit #{fn-id}
           visited #{}
           ;; Accumulate: fn-id -> parent-chain, fn-id -> merged-args
           all-chains {}
           all-merged-args {}
           all-frv-ids #{}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        ;; Phase 2: Batch load all data
        (let [all-fn-ids (set (keys all-chains))
              ;; Load all fns
              fns (load-fns-batch db all-fn-ids)
              ;; Get unique fn-schema-ids
              fn-schema-ids (->> (vals fns)
                                 (map :fn-schema-id)
                                 (set))
              ;; Load all fn-schemas
              fn-schemas (load-fn-schemas-batch db fn-schema-ids)
              ;; Load all arg-schemas
              arg-schemas (load-arg-schemas-batch db fn-schema-ids)
              ;; Load all fn-result-values
              fn-result-values (load-fn-result-values-batch db all-frv-ids)]
          {:fns fns
           :fn-schemas fn-schemas
           :arg-schemas arg-schemas
           :resolved-args all-merged-args
           :fn-result-values fn-result-values})
        ;; Process batch of pending fn-ids
        (let [batch (vec to-visit)
              new-visited (into visited batch)
              ;; Batch load parent chains
              chains (collect-parent-chains-batch db batch)
              ;; Get all fn-ids in all chains
              all-chain-fn-ids (->> (vals chains)
                                    (mapcat identity)
                                    (set))
              ;; Batch load arg-values for all chain members
              all-arg-values (load-arg-values-batch db all-chain-fn-ids)
              ;; Merge arg-values for each fn
              merged-args-batch (into {}
                                      (map (fn [fid]
                                             [fid (merge-arg-values-for-chain
                                                    all-arg-values
                                                    (get chains fid [fid]))]))
                                      batch)
              ;; Extract all potential refs (UUIDs)
              all-potential-refs (->> (vals merged-args-batch)
                                      (mapcat extract-potential-fn-refs)
                                      (set))
              ;; Remove already visited
              new-candidates (set/difference all-potential-refs new-visited)
              ;; Classify refs as fn or fn-result-value
              {:keys [fn-ids frv-ids]} (classify-uuid-refs db new-candidates)
              ;; Load fn-result-values to get their fn-ids
              new-frvs (load-fn-result-values-batch db frv-ids)
              ;; Add fn-ids from fn-result-values to visit set
              frv-fn-ids (set (map :fn-id (vals new-frvs)))
              ;; Combine direct fn refs + fn refs from fn-result-values
              all-new-fn-refs (set/union fn-ids (set/difference frv-fn-ids new-visited))]
          (recur all-new-fn-refs
                 new-visited
                 (merge all-chains chains)
                 (merge all-merged-args merged-args-batch)
                 (set/union all-frv-ids frv-ids)
                 (+ iter-count (count batch))))))))


;; === Storage record ===

(defrecord DatomicStorage
  [client-config db-name client-atom conn-atom schema-atom metadata-cache ^ReentrantReadWriteLock rw-lock]

  sp/Storage

  (initialize
    [_this schema]
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [client (d/client client-config)]
                            (reset! client-atom client)
                            ;; Store schema for multi-field constraint validation
                            (reset! schema-atom schema)
                            ;; Create database if it doesn't exist
                            ;; Uses optimistic approach: try to create and ignore "already exists" error.
                            ;; This is race-condition safe: if another thread creates the DB between
                            ;; our check and create, we simply catch the exception and continue.
                            (try
                              (d/create-database client {:db-name db-name})
                              (catch clojure.lang.ExceptionInfo e
                                ;; Datomic throws ExceptionInfo with :db/error when DB already exists
                                (when-not (= (:db/error (ex-data e)) :db.error/db-already-exists)
                                  (throw e))))
                            (let [conn (d/connect client {:db-name db-name})]
                              (reset! conn-atom conn)
                              ;; Perform initialization, then invalidate metadata cache on success
                              ;; This prevents cache invalidation on failed migrations.
                              (let [result (do-initialize conn schema)]
                                (reset! metadata-cache nil)
                                result))))))


  (close
    [_this]
    (sp/with-write-lock rw-lock
                        (fn []
                          (when-let [client @client-atom]
                            (d/delete-database client {:db-name db-name}))
                          (reset! conn-atom nil)
                          (reset! client-atom nil)))
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (sp/with-read-lock rw-lock
                       (fn []
                         (if-let [conn @conn-atom]
                           (let [db (d/db conn)
                                 attrs (current-attrs db)]
                             (->> (keys attrs)
                                  (map namespace)
                                  (filter some?)
                                  (set)
                                  (map keyword)
                                  (set)))
                           #{}))))


  (current-fields
    [_this entity-name]
    (sp/with-read-lock rw-lock
                       (fn []
                         (when-let [conn @conn-atom]
                           (let [db (d/db conn)
                                 metadata (read-metadata db)
                                 ;; Check if entity exists in metadata
                                 entity-exists? (some #(= % entity-name) (vals (:entities metadata)))]
                             (when entity-exists?
                               (let [entity-fields (->> (:fields metadata)
                                                        (vals)
                                                        (filter #(= (:entity %) entity-name)))]
                                 (into {}
                                       (map (fn [{:keys [field nullable?] field-type :type}]
                                              [field {:type field-type :nullable? nullable?}])
                                            entity-fields)))))))))


  (current-enums
    [_this]
    (sp/with-read-lock rw-lock
                       (fn []
                         (if-let [conn @conn-atom]
                           (let [db (d/db conn)
                                 enum-values (current-enum-values-db db)]
                             (->> enum-values
                                  (map #(-> (namespace %) (str/replace ".value" "") keyword))
                                  (set)))
                           #{}))))


  (current-enum-values
    [_this enum-name]
    (sp/with-read-lock rw-lock
                       (fn []
                         (when-let [conn @conn-atom]
                           (let [db (d/db conn)
                                 enum-values (current-enum-values-db db)
                                 enum-ns (str (name enum-name) ".value")
                                 values (->> enum-values
                                             (filter #(= (namespace %) enum-ns))
                                             (map #(keyword (name %)))
                                             (set))]
                             (when (seq values) values))))))


  (schema-metadata
    [_this]
    (sp/with-read-lock rw-lock
                       (fn []
                         (when-let [conn @conn-atom]
                           (read-metadata (d/db conn))))))


  sp/StorageCRUD

  (create-entity
    [_this entity-name data]
    ;; Validate data type before acquiring lock
    (sp/validate-data-is-map! entity-name data)
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [conn (ensure-connection! conn-atom :create-entity)
                                db (d/db conn)
                                field-specs (get-fields-with-specs db entity-name)]
                            ;; Validate multi-field unique constraints before creating
                            (when-let [schema @schema-atom]
                              (validate-multi-field-constraints! db schema entity-name data field-specs nil))
                            (create-entity-impl conn entity-name data)))))


  (read-entity
    [_this entity-name id]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [conn (ensure-connection! conn-atom :read-entity)]
                           (read-entity-impl conn entity-name id)))))


  (update-entity
    [_this entity-name id data]
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [conn (ensure-connection! conn-atom :update-entity)
                                db (d/db conn)
                                field-specs (get-fields-with-specs db entity-name)]
                            ;; Validate multi-field unique constraints before updating
                            (when-let [schema @schema-atom]
                              (validate-multi-field-constraints! db schema entity-name data field-specs id))
                            (update-entity-impl conn entity-name id data)))))


  (delete-entity
    [_this entity-name id]
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [conn (ensure-connection! conn-atom :delete-entity)]
                            (delete-entity-impl conn entity-name id)))))


  (query-entities
    [_this entity-name where]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [conn (ensure-connection! conn-atom :query-entities)]
                           (query-entities-impl conn entity-name where)))))


  sp/StorageBatchCRUD

  (create-entities
    [_this entity-name data-seq]
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [conn (ensure-connection! conn-atom :create-entities)
                                db (d/db conn)
                                field-specs (get-fields-with-specs db entity-name)]
                            ;; Validate multi-field unique constraints for each entity
                            (when-let [schema @schema-atom]
                              (doseq [data data-seq]
                                (validate-multi-field-constraints! db schema entity-name data field-specs nil)))
                            (create-entities-impl conn entity-name data-seq)))))


  (read-entities
    [_this entity-name ids]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [conn (ensure-connection! conn-atom :read-entities)]
                           (read-entities-impl conn entity-name ids)))))


  (delete-entities
    [_this entity-name ids]
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [conn (ensure-connection! conn-atom :delete-entities)]
                            (delete-entities-impl conn entity-name ids)))))


  sp/GraphConstraints

  (validate-parent-same-schema!
    [_this fn-id parent-fn-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-parent-same-schema! conn-atom fn-id parent-fn-id)))


  (validate-no-arg-override!
    [_this fn-id arg-schema-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-no-arg-override! conn-atom fn-id arg-schema-id)))


  (validate-arg-schema-belongs-to-fn!
    [_this fn-id arg-schema-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-arg-schema-belongs-to-fn! conn-atom fn-id arg-schema-id)))


  (validate-no-inheritance-cycle!
    [_this fn-id parent-fn-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-no-inheritance-cycle! conn-atom fn-id parent-fn-id)))


  (validate-no-dependency-cycle!
    [_this owner-fn-id value-fn-id]
    (sp/with-read-lock rw-lock
                       #(constraints/validate-no-dependency-cycle! conn-atom owner-fn-id value-fn-id)))


  sp/ExecutionGraph

  (resolve-execution-graph
    [_this fn-id]
    (sp/with-read-lock rw-lock
                       (fn []
                         (let [conn (ensure-connection! conn-atom :resolve-execution-graph)]
                           (resolve-execution-graph-impl conn fn-id))))))


(defn create-storage
  "Creates a new Datomic storage instance.

   Options:
   - :db-name - database name (default \"graphden\")
   - :client-config - Datomic client configuration map
                      (default: local in-memory, see default-local-config)

   Examples:

   ;; Local in-memory (default):
   (create-storage {:db-name \"my-db\"})

   ;; Local with file storage:
   (create-storage {:db-name \"my-db\"
                    :client-config {:server-type :datomic-local
                                    :storage-dir \"/path/to/data\"
                                    :system \"my-system\"}})

   ;; Pro with peer-server:
   (create-storage {:db-name \"my-db\"
                    :client-config {:server-type :peer-server
                                    :endpoint \"localhost:8998\"
                                    :secret \"your-secret\"
                                    :access-key \"your-key\"}})"
  [{:keys [db-name client-config]
    :or {db-name "graphden"
         client-config default-local-config}}]
  (->DatomicStorage client-config db-name (atom nil) (atom nil) (atom nil) (atom nil) (ReentrantReadWriteLock.)))
