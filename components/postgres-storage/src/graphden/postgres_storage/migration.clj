(ns graphden.postgres-storage.migration
  "Schema migration logic for PostgreSQL.
   Handles first-time initialization and incremental migrations."
  (:require
    [clojure.tools.logging :as log]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.postgres-storage.ddl :as ddl]
    [graphden.postgres-storage.introspection :as introspection]
    [graphden.postgres-storage.metadata :as metadata]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]
    [next.jdbc :as jdbc]))


;; === Migration context ===
;; A mutable context for collecting migration changes during the migration process.
;; Using a single context object reduces parameter passing and centralizes state.

(defn- create-migration-context
  "Creates a mutable context for tracking migration changes.
   All tracking atoms are centralized here for cleaner code."
  []
  {:created-enums (atom [])
   :renamed-enums (atom {})
   :created-enum-values (atom [])
   :created-entities (atom [])
   :renamed-entities (atom {})
   :created-fields (atom [])
   :renamed-fields (atom [])
   :columns-cache (atom {})})


(defn- context->changes
  "Extracts the final changes map from migration context."
  [ctx]
  {:entities {:created @(:created-entities ctx)
              :renamed @(:renamed-entities ctx)}
   :fields {:created @(:created-fields ctx)
            :renamed @(:renamed-fields ctx)}
   :enums {:created @(:created-enums ctx)
           :renamed @(:renamed-enums ctx)}
   :enum-values {:created @(:created-enum-values ctx)}})


;; === Collision checks ===
;; Note: These run in O(total_fields) time, not O(n²).
;; Each entity's fields are checked separately for meaningful error context.

(defn- check-entity-field-collisions!
  "Checks snake_case collisions for a single entity's fields."
  [schema entity-name]
  (util/check-snake-case-collisions! {:context "fields" :entity entity-name}
                                     (keys (ds/entity-fields schema entity-name))))


;; === First-time initialization ===

(defn- create-single-enum!
  "Creates a single enum during first-time initialization.
   Values are sorted alphabetically for deterministic ordering."
  [ds enum-name {:keys [values]}]
  (ddl/create-enum! ds enum-name (sort (keys values))))


(defn- create-single-entity!
  "Creates a single entity table during first-time initialization."
  [ds schema entity-name]
  (let [fields (ds/entity-fields schema entity-name)]
    (ddl/create-table! ds entity-name fields)
    (ddl/create-entity-constraints! ds schema entity-name)
    (ddl/create-ref-indexes! ds entity-name fields)))


;; === Type compatibility checks ===

(defn- check-single-field-type!
  "Checks type compatibility for a single field."
  [entity-name old-db-fields old-metadata field-name field-spec]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (when old-field-info
      (let [old-field-name (:field old-field-info)
            old-db-field (get old-db-fields old-field-name)]
        ;; Verify column exists in database
        (when (and (seq old-db-fields) (nil? old-db-field))
          (throw (ex-info "Metadata/DB inconsistency: field exists in metadata but not in database"
                          {:type :metadata-error/inconsistency
                           :entity entity-name
                           :field field-name
                           :expected-column (util/kw->snake-case old-field-name)})))
        ;; Use type from metadata (preserves original types correctly)
        (let [old-type (:type old-field-info)
              new-type (:type field-spec)
              old-nullable? (:nullable? old-field-info)
              new-nullable? (get field-spec :nullable? false)]
          (sp/check-type-change! entity-name field-name old-type new-type)
          (sp/check-nullable-change! entity-name field-name old-nullable? new-nullable?))))))


(defn- check-entity-fields-type!
  "Checks type compatibility for all fields of a single entity."
  [ds schema old-metadata entity-name]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)
        old-db-fields (when old-entity-name
                        (introspection/current-columns ds (util/kw->snake-case old-entity-name)))]
    (when old-entity-name
      (run! (fn [[field-name field-spec]]
              (check-single-field-type! entity-name old-db-fields old-metadata field-name field-spec))
            (ds/entity-fields schema entity-name)))))


;; === Enum migration ===

(defn- process-existing-enum-value!
  "Adds a new value to existing enum if not present in old metadata."
  [ds old-metadata enum-name value-kw value-uuid ctx]
  (when-not (get (:enum-values old-metadata) value-uuid)
    (ddl/add-enum-value! ds enum-name value-kw)
    (swap! (:created-enum-values ctx) conj {:enum enum-name :value value-kw})))


(defn- process-single-enum!
  "Processes a single enum during migration (rename or create)."
  [ds old-metadata enum-name {:keys [uuid values]} ctx]
  (if-let [old-enum-name (get (:enums old-metadata) uuid)]
    (do
      ;; Existing enum - check for rename
      (when (not= old-enum-name enum-name)
        (ddl/rename-enum! ds old-enum-name enum-name)
        (swap! (:renamed-enums ctx) assoc old-enum-name enum-name))
      ;; Add new values
      (run! (fn [[value-kw value-uuid]]
              (process-existing-enum-value! ds old-metadata enum-name value-kw value-uuid ctx))
            values))
    ;; New enum - values sorted alphabetically for deterministic ordering
    (do
      (ddl/create-enum! ds enum-name (sort (keys values)))
      (swap! (:created-enums ctx) conj enum-name)
      (run! (fn [[v _]] (swap! (:created-enum-values ctx) conj {:enum enum-name :value v}))
            values))))


;; === Field migration ===

(defn- get-cached-columns
  "Gets columns from cache or loads them from database."
  [ds table-name ctx]
  (let [cache (:columns-cache ctx)]
    (or (get @cache table-name)
        (let [cols (introspection/current-columns ds table-name)]
          (swap! cache assoc table-name cols)
          cols))))


(defn- process-existing-field!
  "Processes an existing field during migration (rename or type widening).
   Uses columns-cache to avoid redundant database queries."
  [ds entity-name field-name field-spec old-field-info ctx]
  ;; Check for rename
  (when (not= (:field old-field-info) field-name)
    (ddl/rename-column! ds entity-name (:field old-field-info) field-name)
    (swap! (:renamed-fields ctx) conj {:entity entity-name
                                       :old-field (:field old-field-info)
                                       :new-field field-name}))
  ;; Check for type widening - use cached columns
  (let [table-name (util/kw->snake-case entity-name)
        old-fields (get-cached-columns ds table-name ctx)
        old-type (:type (get old-fields field-name))
        new-type (:type field-spec)]
    (when (and old-type
               (not= old-type new-type)
               (sp/safe-type-change? old-type new-type))
      (ddl/alter-column-type! ds entity-name field-name
                              (util/field-type->pg field-spec)))))


(defn- process-single-field!
  "Processes a single field during migration."
  [ds old-metadata entity-name field-name field-spec ctx]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (if old-field-info
      (process-existing-field! ds entity-name field-name field-spec old-field-info ctx)
      ;; New field - add column and index if ref
      (do
        (ddl/add-column! ds entity-name field-name field-spec)
        (when (= :ref (:type field-spec))
          (ddl/create-ref-index! ds entity-name field-name))
        (swap! (:created-fields ctx) conj {:entity entity-name :field field-name})))))


;; === Entity migration ===

(defn- process-existing-entity!
  "Processes an existing entity during migration."
  [ds schema old-metadata entity-name old-entity-name ctx]
  ;; Check for rename
  (when (not= old-entity-name entity-name)
    (ddl/rename-table! ds old-entity-name entity-name)
    (swap! (:renamed-entities ctx) assoc old-entity-name entity-name))
  ;; Process fields
  (run! (fn [[field-name field-spec]]
          (process-single-field! ds old-metadata entity-name field-name field-spec ctx))
        (ds/entity-fields schema entity-name)))


(defn- process-single-entity!
  "Processes a single entity during migration (existing or new)."
  [ds schema old-metadata entity-name ctx]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)]
    (if old-entity-name
      (process-existing-entity! ds schema old-metadata entity-name old-entity-name ctx)
      ;; New entity
      (do
        (ddl/create-table! ds entity-name (ds/entity-fields schema entity-name))
        (ddl/create-entity-constraints! ds schema entity-name)
        (swap! (:created-entities ctx) conj entity-name)
        (run! (fn [[f _]] (swap! (:created-fields ctx) conj {:entity entity-name :field f}))
              (ds/entity-fields schema entity-name))))))


;; === Main migration functions ===

(defn do-first-init!
  "Performs first-time initialization within a transaction."
  [tx schema]
  ;; Create enums first (tables may reference them)
  (run! (fn [[enum-name enum-def]] (create-single-enum! tx enum-name enum-def))
        (ds/enums schema))
  ;; Create tables
  (run! #(create-single-entity! tx schema %) (ds/entities schema))
  ;; Save metadata
  (metadata/save-metadata-in-tx! tx schema)
  ;; Return changes
  (sp/build-first-init-changes schema))


(defn do-migration!
  "Performs schema migration within a transaction."
  [tx schema old-metadata]
  ;; Check for destructive changes (removals)
  (sp/check-all-removals! old-metadata schema)

  ;; Check type compatibility
  (run! #(check-entity-fields-type! tx schema old-metadata %) (ds/entities schema))

  ;; Create migration context to track all changes
  (let [ctx (create-migration-context)]
    ;; Apply enum changes
    (run! (fn [[enum-name enum-def]]
            (process-single-enum! tx old-metadata enum-name enum-def ctx))
          (ds/enums schema))

    ;; Apply entity/field changes
    (run! #(process-single-entity! tx schema old-metadata % ctx)
          (ds/entities schema))

    ;; Save metadata
    (metadata/save-metadata-in-tx! tx schema)

    ;; Return changes
    (context->changes ctx)))


(defn- log-migration-summary
  "Logs a summary of migration changes at info level."
  [changes first-init?]
  (let [entities-created (count (get-in changes [:entities :created] []))
        entities-renamed (count (get-in changes [:entities :renamed] {}))
        fields-created (count (get-in changes [:fields :created] []))
        fields-renamed (count (get-in changes [:fields :renamed] []))
        enums-created (count (get-in changes [:enums :created] []))
        enums-renamed (count (get-in changes [:enums :renamed] {}))
        enum-values-created (count (get-in changes [:enum-values :created] []))
        total-changes (+ entities-created entities-renamed
                         fields-created fields-renamed
                         enums-created enums-renamed
                         enum-values-created)]
    (if (zero? total-changes)
      (log/info "Schema migration completed: no changes needed")
      (log/info (if first-init? "Schema initialized" "Schema migrated")
                {:entities-created entities-created
                 :entities-renamed entities-renamed
                 :fields-created fields-created
                 :fields-renamed fields-renamed
                 :enums-created enums-created
                 :enums-renamed enums-renamed
                 :enum-values-created enum-values-created}))))


(defn do-initialize
  "Performs schema initialization/migration.
   All DDL and metadata operations are wrapped in a single transaction
   to ensure atomicity - either all changes succeed or none do.
   Logs a summary of changes at info level."
  [ds schema]
  ;; Check for snake_case naming collisions before any DDL
  (util/check-snake-case-collisions! {:context "entities"} (ds/entities schema))
  (run! #(check-entity-field-collisions! schema %) (ds/entities schema))
  (util/check-snake-case-collisions! {:context "enums"} (keys (ds/enums schema)))

  (metadata/ensure-metadata-table! ds)
  (let [metadata-rows (metadata/read-metadata-rows ds)
        old-metadata (metadata/parse-metadata metadata-rows)
        first-init? (nil? old-metadata)
        changes (jdbc/with-transaction [tx ds]
                                       (if first-init?
                                         (do-first-init! tx schema)
                                         (do-migration! tx schema old-metadata)))]
    (log-migration-summary changes first-init?)
    changes))
