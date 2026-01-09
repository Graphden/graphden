(ns graphden.datomic-storage.core
  "Datomic Local implementation of Storage protocol.

   ## Module Structure (Future Refactoring Roadmap)

   This file is large (~1600 LOC) and could be split similarly to postgres-storage:

   - core.clj       - Storage record, initialization, connection management
   - schema.clj     - Type mapping, attribute naming, schema builders
   - crud.clj       - CRUD operations, batch operations
   - migration.clj  - Migration helpers, destructive change detection
   - graph.clj      - ExecutionGraph resolution, dependency traversal
   - introspection.clj - current-entities, current-fields, current-enums

   For comparison, postgres-storage is split into ~10 files with clear separation.
   This refactoring would improve readability and maintainability."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.constraints :as constraints]
    [graphden.datomic-storage.schema :as schema]
    [graphden.datomic-storage.util :as util]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util.concurrent.locks
      ReentrantReadWriteLock)))


;; === Configuration ===
;; Query timeout is for API consistency with postgres-storage.
;; Note: Datomic Client API does not support native query timeout, so the timeout
;; is only used for API compatibility and does not actually limit Datomic query time.

(def ^:dynamic *query-timeout-ms*
  "Query timeout in milliseconds for API compatibility with postgres-storage.

   IMPORTANT: Datomic Client API does not support native query timeout.
   This var exists for API compatibility but does NOT actually limit query time.

   Default is 30000 ms (30 seconds)."
  sp/default-query-timeout-ms)


(defn with-query-timeout
  "Executes f with a custom query timeout binding.

   NOTE: Datomic Client API does not enforce this timeout on queries.
   This function exists for API compatibility with postgres-storage.

   Example:
   (with-query-timeout 60000
     #(sp/query-entities storage :user {}))"
  [timeout-ms f]
  (binding [*query-timeout-ms* timeout-ms]
    (f)))


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


;; === Metadata transaction builders (pure functions for testability) ===

(defn build-all-metadata-tx-data
  "Builds complete metadata transaction data from schema.
   Pure function - no side effects, easy to test.

   Arguments:
   - schema: DataSchema to extract metadata from

   Returns sequence of transaction maps ready for Datomic transact."
  [schema]
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
            (ds/enums schema))))


(defn- fetch-existing-metadata
  "Fetches existing metadata entities from database.
   Returns {:entity-ids [[eid] ...] :full-data [pulled-entity ...]}."
  [db]
  (let [existing (d/q '[:find ?e
                        :where [?e :graphden.metadata/uuid _]]
                      db)]
    {:entity-ids existing
     :full-data (when (seq existing)
                  (mapv (fn [[e]] (d/pull db '[*] e)) existing))}))


(defn- retract-metadata!
  "Retracts existing metadata entities."
  [conn entity-ids]
  (when (seq entity-ids)
    (log/debug "Retracting old metadata" {:count (count entity-ids)})
    (d/transact conn {:tx-data (vec (map (fn [[e]] [:db/retractEntity e]) entity-ids))})))


(defn- assert-metadata!
  "Asserts new metadata, rolling back on failure.
   Returns nil on success, throws on failure."
  [conn tx-data old-metadata]
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
        (throw e)))))


(defn- save-metadata!
  "Saves metadata to the database (retract old, then assert new).

   NOTE: Uses two separate transactions because Datomic doesn't allow
   retracting and asserting the same :db/unique value in a single transaction.
   This causes :db.error/datoms-conflict for metadata UUID updates.

   If the second transaction fails after the first succeeds, we attempt to
   restore the old metadata to maintain consistency.

   SAFETY: If rollback also fails, we throw an exception with both errors
   to ensure the caller knows the database may be in an inconsistent state.

   This function orchestrates the smaller pure/side-effecting functions:
   - build-all-metadata-tx-data (pure, testable)
   - fetch-existing-metadata (read)
   - retract-metadata! (write)
   - assert-metadata! (write with rollback)"
  [conn schema]
  ;; Capture old metadata for potential rollback
  (let [db (d/db conn)
        {:keys [entity-ids full-data]} (fetch-existing-metadata db)]
    ;; First, retract all existing metadata
    (retract-metadata! conn entity-ids)
    ;; Then add new metadata
    (let [tx-data (build-all-metadata-tx-data schema)]
      (assert-metadata! conn tx-data full-data))))


;; === Destructive change checks ===
;; Using shared utilities from sp/check-removed! and sp/check-type-change!


;; === Migration helpers ===

(defn- check-single-field-type!
  "Checks type compatibility for a single field during migration."
  [db old-metadata old-entity-name field-name field-spec]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (when old-field-info
      (let [old-attr (util/entity-attr old-entity-name (:field old-field-info))
            attr-exists? (seq (d/q '[:find ?e
                                     :in $ ?attr
                                     :where [?e :db/ident ?attr]]
                                   db old-attr))]
        ;; Check for metadata/DB inconsistency
        (when-not attr-exists?
          (throw (ex-info "Metadata/DB inconsistency: field exists in metadata but not in database"
                          {:type :metadata-error/inconsistency
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
    (swap! new-schema conj (schema/build-enum-value-schema enum-name value-kw))
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
              (swap! new-schema conj (schema/build-enum-value-schema enum-name value-kw))
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
        (swap! new-schema conj (schema/build-field-schema schema entity-name field-name field-spec))
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
        (swap! new-schema conj (schema/build-id-schema entity-name))
        (run! (fn [[field-name field-spec]]
                (swap! new-schema conj (schema/build-field-schema schema entity-name field-name field-spec))
                (swap! created-fields conj {:entity entity-name :field field-name}))
              (ds/entity-fields schema entity-name))))))


;; === Schema builders for initialization ===

(defn- build-enum-schemas
  "Builds all enum value schemas for initialization."
  [schema]
  (mapcat (fn [[enum-name {:keys [values]}]]
            (map (fn [[value-kw _]] (schema/build-enum-value-schema enum-name value-kw))
                 values))
          (ds/enums schema)))


(defn- build-field-schemas
  "Builds all field schemas for initialization.
   Includes :id attribute for each entity."
  [schema]
  (mapcat (fn [entity-name]
            (cons (schema/build-id-schema entity-name)
                  (map (fn [[field-name field-spec]]
                         (schema/build-field-schema schema entity-name field-name field-spec))
                       (ds/entity-fields schema entity-name))))
          (ds/entities schema)))


;; Use shared functions from storage-protocol:
;; sp/collect-created-fields, sp/collect-created-enum-values,
;; sp/collect-field-uuids, sp/collect-enum-value-uuids


;; === Initialize ===

(defn- do-first-init
  "First-time initialization: creates all schema and metadata.
   Returns changes map with all created entities/fields/enums."
  [conn schema]
  (let [metadata-schema (schema/build-metadata-schema)
        enum-schema (build-enum-schemas schema)
        field-schema (build-field-schemas schema)
        all-schema (concat metadata-schema enum-schema field-schema)]
    ;; Transact all schema
    (when (seq all-schema)
      (d/transact conn {:tx-data (vec all-schema)}))
    ;; Save metadata
    (save-metadata! conn schema)
    ;; Return changes using shared function
    (sp/build-first-init-changes schema)))


;; Use sp/check-all-removals! from storage-protocol for destructive change validation


(defn- create-migration-context
  "Creates mutable context for tracking migration changes."
  []
  {:created-entities (atom [])
   :renamed-entities (atom {})
   :created-fields (atom [])
   :renamed-fields (atom [])
   :created-enums (atom [])
   :renamed-enums (atom {})
   :created-enum-values (atom [])
   :new-schema (atom [])})


(defn- validate-migration-context!
  "Validates that migration context is internally consistent.
   Catches logical errors that could indicate partial/corrupted state.
   Throws if validation fails."
  [ctx]
  (let [created-entities (set @(:created-entities ctx))
        renamed-entities-old (set (keys @(:renamed-entities ctx)))
        renamed-entities-new (set (vals @(:renamed-entities ctx)))
        created-enums (set @(:created-enums ctx))
        renamed-enums-old (set (keys @(:renamed-enums ctx)))]
    ;; Entity cannot be both created and renamed (from old name)
    (when-let [overlap (seq (set/intersection created-entities renamed-entities-old))]
      (throw (ex-info "Migration context inconsistency: entity both created and renamed-from"
                      {:type :migration-error/context-inconsistent
                       :overlap overlap})))
    ;; Created entity name shouldn't match a renamed-to name (would indicate duplicate)
    (when-let [overlap (seq (set/intersection created-entities renamed-entities-new))]
      (throw (ex-info "Migration context inconsistency: entity created with same name as rename target"
                      {:type :migration-error/context-inconsistent
                       :overlap overlap})))
    ;; Enum cannot be both created and renamed
    (when-let [overlap (seq (set/intersection created-enums renamed-enums-old))]
      (throw (ex-info "Migration context inconsistency: enum both created and renamed-from"
                      {:type :migration-error/context-inconsistent
                       :overlap overlap})))))


(defn- context->changes
  "Extracts changes map from migration context.
   Validates context consistency before returning."
  [ctx]
  (validate-migration-context! ctx)
  {:entities {:created @(:created-entities ctx) :renamed @(:renamed-entities ctx)}
   :fields {:created @(:created-fields ctx) :renamed @(:renamed-fields ctx)}
   :enums {:created @(:created-enums ctx) :renamed @(:renamed-enums ctx)}
   :enum-values {:created @(:created-enum-values ctx)}})


(defn- do-migration
  "Performs schema migration from old-metadata to new schema.
   Returns changes map with created/renamed entities/fields/enums."
  [conn db old-metadata schema]
  ;; Validate no destructive changes
  (sp/check-all-removals! old-metadata schema)
  ;; Check type compatibility
  (run! #(check-entity-fields-type! db old-metadata schema %) (ds/entities schema))

  ;; Process changes
  (let [ctx (create-migration-context)]
    ;; Process enums
    (run! (fn [[enum-name enum-def]]
            (process-single-enum! old-metadata enum-name enum-def
                                  (:created-enums ctx) (:renamed-enums ctx)
                                  (:new-schema ctx) (:created-enum-values ctx)))
          (ds/enums schema))

    ;; Process entities and fields
    (run! #(process-single-entity! schema old-metadata %
                                   (:created-entities ctx) (:renamed-entities ctx)
                                   (:new-schema ctx) (:created-fields ctx) (:renamed-fields ctx))
          (ds/entities schema))

    ;; Transact new schema
    (when (seq @(:new-schema ctx))
      (d/transact conn {:tx-data @(:new-schema ctx)}))

    ;; Save metadata
    (save-metadata! conn schema)

    ;; Return changes
    (context->changes ctx)))


(defn- do-initialize
  "Performs initialization/migration of the database.
   Delegates to do-first-init or do-migration based on existing metadata."
  [conn schema]
  ;; Log info about multi-field unique constraints (enforced at application level)
  (doseq [entity-name (ds/entities schema)]
    (schema/warn-multi-field-constraints! schema entity-name))

  (let [db (d/db conn)
        old-metadata (read-metadata db)]
    (if (nil? old-metadata)
      (do-first-init conn schema)
      (do-migration conn db old-metadata schema))))


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
              (util/enum-value-ident (:enum-name field-spec) v)
              v)
      :ref (if (uuid? v)
             ;; Convert UUID to lookup ref for the referenced entity type
             (let [ref-entity (:ref-entity field-spec)]
               [(util/entity-attr ref-entity :id) v])
             v)
      ;; Default: return as-is
      v)))


(defn- entity->tx
  "Converts entity map to Datomic transaction data.
   Uses namespaced attributes for the entity type.
   The :id field is stored as :entity-name/id (UUID).
   Enum fields are converted to entity idents.
   Ref fields are converted to lookup refs.
   Type hints for hot-path performance (called during batch operations)."
  ^clojure.lang.IPersistentMap [entity-name ^clojure.lang.IPersistentMap data id temp-id ^clojure.lang.IPersistentMap field-specs]
  (let [base-tx {:db/id temp-id
                 (util/entity-attr entity-name :id) id}]
    (reduce-kv (fn [acc k v]
                 (if (= k :id)
                   acc  ; Already handled above
                   (let [converted-v (convert-field-value field-specs k v)]
                     (assoc acc (util/entity-attr entity-name k) converted-v))))
               base-tx
               data)))


(defn- pull-entity
  "Pulls an entity by id (UUID) from the database.
   Queries by :entity-name/id attribute."
  [db entity-name id entity-fields]
  (let [id-attr (util/entity-attr entity-name :id)
        ;; Include :id in pattern along with other fields
        pattern (into [id-attr]
                      (map #(util/entity-attr entity-name %) entity-fields))
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
        id-attr (util/entity-attr entity-name :id)
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
        id-attr (util/entity-attr entity-name :id)
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
        id-attr (util/entity-attr entity-name :id)
        pattern (into [id-attr] (map #(util/entity-attr entity-name %) fields))
        ;; Build where clauses - must have at least one clause to identify entities of this type
        base-where [['?e id-attr '_]]  ; Match entities that have an :id attribute
        where-clauses (if (empty? where)
                        base-where
                        (into base-where
                              (map (fn [[k v]]
                                     ['?e (util/entity-attr entity-name k) v])
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
          id-attr (util/entity-attr entity-name :id)
          pattern (into [id-attr] (map #(util/entity-attr entity-name %) fields))
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
          id-attr (util/entity-attr entity-name :id)
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


(defn- classify-and-load-refs
  "Classifies UUID references and loads fn-result-values in combined queries.
   Returns {:fn-ids #{...} :frv-ids #{...} :fn-result-values {...}}.
   Gracefully handles missing fn-result-value attribute."
  [db uuid-candidates]
  (if (empty? uuid-candidates)
    {:fn-ids #{} :frv-ids #{} :fn-result-values {}}
    (let [uuids-vec (vec uuid-candidates)
          ;; Query fn refs
          fn-results (d/q '[:find ?fn-id
                            :in $ [?fn-id ...]
                            :where
                            [?e :fn/id ?fn-id]]
                          db uuids-vec)
          ;; Query fn-result-values WITH their fn-ids (combined classify + load)
          ;; Handle missing attribute gracefully
          frv-results (try
                        (d/q '[:find ?frv-id ?fn-id
                               :in $ [?frv-id ...]
                               :where
                               [?e :fn-result-value/id ?frv-id]
                               [?e :fn-result-value/fn-id ?fn-id]]
                             db uuids-vec)
                        (catch clojure.lang.ExceptionInfo e
                          ;; :db.error/not-an-entity means attribute doesn't exist
                          (if (= :db.error/not-an-entity (:db/error (ex-data e)))
                            []
                            (throw e))))]
      {:fn-ids (set (map first fn-results))
       :frv-ids (set (map first frv-results))
       :fn-result-values (->> frv-results
                              (map (fn [[frv-id fn-id]]
                                     [frv-id {:id frv-id :fn-id fn-id}]))
                              (into {}))})))


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


(defn- check-fn-exists!
  "Throws :not-found if function does not exist in database."
  [db fn-id]
  (let [exists? (seq (d/q '[:find ?e
                            :in $ ?fn-id
                            :where
                            [?e :fn/id ?fn-id]]
                          db fn-id))]
    (when-not exists?
      (throw (ex-info "Function not found"
                      {:type :not-found
                       :fn-id fn-id})))))


(defn- load-final-graph-data
  "Phase 2: Batch load all data for the discovered graph.
   fn-result-values are already loaded during discovery (optimization).
   Returns the complete execution graph map."
  [db all-chains all-merged-args all-fn-result-values]
  (let [all-fn-ids (set (keys all-chains))
        fns (load-fns-batch db all-fn-ids)
        fn-schema-ids (->> (vals fns)
                           (map :fn-schema-id)
                           (set))
        fn-schemas (load-fn-schemas-batch db fn-schema-ids)
        arg-schemas (load-arg-schemas-batch db fn-schema-ids)]
    (sp/->execution-graph
      {:fns fns
       :fn-schemas fn-schemas
       :arg-schemas arg-schemas
       :resolved-args all-merged-args
       :fn-result-values all-fn-result-values})))


(defn- process-discovery-batch
  "Processes a batch of fn-ids during graph discovery.
   Returns map with :next-to-visit, :new-visited, :chains, :merged-args, :fn-result-values."
  [db batch visited]
  (let [new-visited (into visited batch)
        ;; Batch load parent chains
        chains (collect-parent-chains-batch db batch)
        all-chain-fn-ids (->> (vals chains) (mapcat identity) (set))
        ;; Batch load and merge arg-values
        all-arg-values (load-arg-values-batch db all-chain-fn-ids)
        merged-args-batch (into {}
                                (map (fn [fid]
                                       [fid (sp/merge-arg-values-for-chain
                                              all-arg-values
                                              (get chains fid [fid]))]))
                                batch)
        ;; Find new references
        all-potential-refs (->> (vals merged-args-batch)
                                (mapcat sp/extract-uuid-refs-from-arg-values)
                                (set))
        new-candidates (set/difference all-potential-refs new-visited)
        ;; Classify AND load fn-result-values in one query (optimization)
        {:keys [fn-ids fn-result-values]} (classify-and-load-refs db new-candidates)
        frv-fn-ids (set (map :fn-id (vals fn-result-values)))
        next-to-visit (set/union fn-ids (set/difference frv-fn-ids new-visited))]
    {:next-to-visit next-to-visit
     :new-visited new-visited
     :chains chains
     :merged-args merged-args-batch
     :fn-result-values fn-result-values}))


(defn- resolve-execution-graph-impl
  "Resolves complete execution graph for a function.
   Uses batched BFS to collect all transitively referenced functions and fn-result-values.
   Throws if iteration count exceeds sp/*max-graph-iterations*.

   This implementation uses batch queries to minimize database round-trips:
   1. Process pending fn-ids in batches
   2. Batch load parent chains
   3. Batch load arg-values for all chain members
   4. Extract fn-refs and fn-result-value refs, continue until graph is complete
   5. Final batch load of all fns, fn-schemas, arg-schemas
   Note: fn-result-values are loaded during discovery (optimization)"
  [conn fn-id]
  (let [db (d/db conn)]
    (check-fn-exists! db fn-id)
    ;; Phase 1: Discover all fn-ids and fn-result-values using batched BFS
    (loop [to-visit #{fn-id}
           visited #{}
           all-chains {}
           all-merged-args {}
           all-fn-result-values {}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        ;; Phase 2: Load final graph data (fn-result-values already loaded)
        (load-final-graph-data db all-chains all-merged-args all-fn-result-values)
        ;; Process batch
        (let [batch (vec to-visit)
              result (process-discovery-batch db batch visited)]
          (recur (:next-to-visit result)
                 (:new-visited result)
                 (merge all-chains (:chains result))
                 (merge all-merged-args (:merged-args result))
                 (merge all-fn-result-values (:fn-result-values result))
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
                              ;; Perform initialization, then invalidate metadata cache on success.
                              ;; Cache invalidation is intentionally AFTER do-initialize completes:
                              ;; - If do-initialize throws, cache stays nil (safe: fresh start on retry)
                              ;; - If do-initialize succeeds, cache is reset to nil (forces refresh)
                              ;; Note: try-finally is NOT needed here because cache starts as nil,
                              ;; so failed initialization leaves system in consistent state.
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
                              (schema/validate-multi-field-constraints! db schema entity-name data field-specs nil))
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
                              (schema/validate-multi-field-constraints! db schema entity-name data field-specs id))
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
                                (schema/validate-multi-field-constraints! db schema entity-name data field-specs nil)))
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
                           (resolve-execution-graph-impl conn fn-id)))))


  sp/StorageErrorClassifier

  (classify-error
    [_this exception]
    (cond
      ;; ExceptionInfo with :db/error key is a Datomic-specific error
      (and (instance? clojure.lang.ExceptionInfo exception)
           (:db/error (ex-data exception)))
      (let [db-error (:db/error (ex-data exception))]
        (case db-error
          :db.error/unique-conflict :unique-violation
          :db.error/not-found :not-found
          :db.error/datoms-conflict :unique-violation
          :db.error/invalid-entity-id :not-found
          :db.error/cas-failed :concurrent-modification
          :unknown-datomic-error))

      ;; ExceptionInfo with our own :type key
      (and (instance? clojure.lang.ExceptionInfo exception)
           (:type (ex-data exception)))
      (:type (ex-data exception))

      :else :unknown-datomic-error))


  (wrap-error
    [this exception operation context]
    (let [error-type (sp/classify-error this exception)
          error-data (merge {:type error-type
                             :operation operation
                             :message (ex-message exception)}
                            context
                            (when (instance? clojure.lang.ExceptionInfo exception)
                              (select-keys (ex-data exception) [:db/error])))]
      (ex-info (str "Datomic error during " (name operation) ": " (ex-message exception))
               error-data
               exception))))


(defn create-storage
  "Creates a new Datomic storage instance.

   Options:
   - :db-name - database name (default \"graphden\")
   - :client-config - Datomic client configuration map
                      (default: local in-memory, see util/default-local-config)

   Validates:
   - db-name must be alphanumeric with hyphens, starting with letter
   - client-config must have valid :server-type
   - Required keys for each server-type are present

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
         client-config util/default-local-config}}]
  (util/validate-db-name! db-name)
  (util/validate-client-config! client-config)
  (log/info "Creating Datomic storage" {:db-name db-name :server-type (:server-type client-config)})
  (->DatomicStorage client-config db-name (atom nil) (atom nil) (atom nil) (atom nil) (ReentrantReadWriteLock.)))
