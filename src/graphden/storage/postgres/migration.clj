(ns graphden.storage.postgres.migration
  "Schema migration logic for PostgreSQL.
   Handles first-time initialization and incremental migrations.
   Uses generic-migration pipeline with postgres-specific callbacks."
  (:require
    [clojure.tools.logging :as log]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.ddl :as ddl]
    [graphden.storage.postgres.introspection :as introspection]
    [graphden.storage.postgres.metadata :as metadata]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.generic-migration :as gm]
    [next.jdbc :as jdbc]))


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
    (ddl/create-ref-indexes! ds entity-name fields)
    ;; Junction tables for :ref-many fields (must be created AFTER all entity tables exist
    ;; to satisfy FK constraints; deferred via second pass below)
    nil))


(defn- create-entity-junction-tables!
  "Creates junction tables for an entity's :ref-many fields.
   Must be called after all entity tables exist."
  [ds schema entity-name]
  (let [fields (ds/entity-fields schema entity-name)]
    (ddl/create-junction-tables! ds entity-name fields)))


(defn do-first-init!
  "Performs first-time initialization within a transaction."
  [tx schema]
  ;; Create enums first (tables may reference them)
  (run! (fn [[enum-name enum-def]] (create-single-enum! tx enum-name enum-def))
        (ds/enums schema))
  ;; Create tables (entity tables first, junction tables in second pass)
  (run! #(create-single-entity! tx schema %) (ds/entities schema))
  ;; Junction tables (require entity tables to exist for FKs)
  (run! #(create-entity-junction-tables! tx schema %) (ds/entities schema))
  ;; Save metadata
  (metadata/save-metadata-in-tx! tx schema)
  ;; Return changes
  (sp/build-first-init-changes schema))


;; === Migration callbacks ===

(defn- get-cached-columns
  "Gets columns from cache or loads them from database."
  [ds table-name ctx]
  (let [cache (:columns-cache ctx)]
    (or (get @cache table-name)
        (let [cols (introspection/current-columns ds table-name)]
          (swap! cache assoc table-name cols)
          cols))))


(defn- make-postgres-callbacks
  "Creates the callbacks map for postgres migration."
  [tx]
  {:make-field-verifier
   (fn [_old-metadata old-entity-name]
     (let [old-db-fields (introspection/current-columns tx (util/kw->snake-case old-entity-name))]
       (fn [entity-name field-name field-spec old-field-info]
         ;; :ref-many fields are stored in junction tables, not as columns - skip column check
         (when-not (= :ref-many (:type field-spec))
           (let [old-field-name (:field old-field-info)
                 old-db-field (get old-db-fields old-field-name)]
             (when (and (seq old-db-fields) (nil? old-db-field))
               (throw (ex-info "Metadata/DB inconsistency: field exists in metadata but not in database"
                               {:type :metadata-error/inconsistency
                                :entity entity-name
                                :field field-name
                                :expected-column (util/kw->snake-case old-field-name)}))))))))

   :on-create-enum!
   (fn [_ctx enum-name values]
     (ddl/create-enum! tx enum-name (sort (keys values))))

   :on-add-enum-value!
   (fn [_ctx enum-name value-kw]
     (ddl/add-enum-value! tx enum-name value-kw))

   :on-rename-enum!
   (fn [_ctx old-name new-name]
     (ddl/rename-enum! tx old-name new-name))

   :on-create-entity!
   (fn [_ctx schema' entity-name]
     (ddl/create-table! tx entity-name (ds/entity-fields schema' entity-name))
     (ddl/create-entity-constraints! tx schema' entity-name)
     (ddl/create-ref-indexes! tx entity-name (ds/entity-fields schema' entity-name)))

   :on-create-field!
   (fn [_ctx entity-name field-name field-spec]
     (cond
       (= :ref-many (:type field-spec))
       ;; M2M field: create junction table instead of a column
       (ddl/create-junction-table! tx entity-name field-name field-spec)

       :else
       (do
         (ddl/add-column! tx entity-name field-name field-spec)
         (when (= :ref (:type field-spec))
           (ddl/create-ref-index! tx entity-name field-name)))))

   :on-rename-entity!
   (fn [_ctx old-name new-name]
     (ddl/rename-table! tx old-name new-name))

   :on-rename-field!
   (fn [_ctx entity-name old-name new-name]
     (ddl/rename-column! tx entity-name old-name new-name))

   :on-delete-field!
   (fn [_ctx entity-name field-name]
     (ddl/drop-column! tx entity-name field-name))

   :on-existing-field!
   (fn [ctx entity-name field-name field-spec _old-field-info]
     ;; Type widening - use cached columns
     (let [table-name (util/kw->snake-case entity-name)
           old-fields (get-cached-columns tx table-name ctx)
           old-field (get old-fields field-name)
           old-type (:type old-field)
           new-type (:type field-spec)]
       ;; Nullability: a schema flip false→true leaves the DB column NOT NULL,
       ;; so a nil write the schema now permits would fail at PG. Drop it.
       ;; (true→false is rejected upstream as destructive, so only this
       ;; direction reaches here.)
       (when (and old-field
                  (not (:nullable? old-field))
                  (:nullable? field-spec))
         (ddl/alter-column-drop-not-null! tx entity-name field-name))
       ;; EQUIVALENT types (`:uuid`↔`:ref`, `:jsonb`↔`:union`) share one PG
       ;; type, so introspection reports `:uuid`/`:jsonb` while the schema
       ;; says `:ref`/`:union` — `not=` is true but there's nothing to
       ;; rewrite. Without this guard every ref/union column fired an
       ;; ACCESS-EXCLUSIVE-locking no-op ALTER on EVERY startup migration.
       ;; Only a genuine (non-equivalent) safe widening should rewrite.
       (when (and old-type
                  (not= old-type new-type)
                  (not (sp/types-equivalent? old-type new-type))
                  (sp/safe-type-change? old-type new-type))
         (ddl/alter-column-type! tx entity-name field-name
                                 (util/field-type->pg field-spec)))))

   :save-metadata!
   (fn [schema']
     (metadata/save-metadata-in-tx! tx schema'))

   :extra-context-keys
   {:columns-cache (atom {})}})


;; === Legacy index cleanup ===
;;
;; Constraint declarations are processed declaratively, but the
;; migration framework has no "the schema no longer declares this
;; constraint, drop it" step — constraints are only created on entity
;; creation. When we retire a UNIQUE we have to drop the PG index by
;; name explicitly. Keep this list small; each entry corresponds to a
;; documented retirement in the schema files.

(def ^:private retired-indexes
  "Indexes that the schema USED to declare and no longer does. Dropped
   idempotently on every migration pass so cross-version dev DBs stay
   clean. See the matching `NOTE — … was retired` comment in the
   schema file for each entry."
  ["idx_binding_list_item_binding_id_position_unique"])


(defn- drop-retired-indexes!
  "Issue `DROP INDEX IF EXISTS` for every entry in `retired-indexes`.
   Safe on a fresh DB (the index doesn't exist), safe on a migrated DB
   that already dropped it (idempotent)."
  [tx]
  (doseq [idx-name retired-indexes]
    (jdbc/execute! tx [(str "DROP INDEX IF EXISTS \"" idx-name "\"")])))


(defn- ensure-field-indexes!
  "Idempotently `CREATE INDEX IF NOT EXISTS` for every `:indexed? true` field
   across the whole schema. `create-ref-indexes!` only fires from the
   entity-create callback, so a flag newly added to an EXISTING table's field
   (e.g. `:binding-version :ref-fn-id`) would never land on already-migrated
   dev/prod DBs. Running this on every migration pass closes that gap."
  [tx schema]
  (doseq [entity-name (ds/entities schema)]
    (ddl/create-field-indexes! tx entity-name (ds/entity-fields schema entity-name))))


(defn do-migration!
  "Performs schema migration within a transaction."
  [tx schema old-metadata]
  (drop-retired-indexes! tx)
  (let [changes (gm/do-migration! (make-postgres-callbacks tx) old-metadata schema)]
    ;; Side-effect after the migration; return the migration changes, not
    ;; the doseq's nil (callers read `:created`/`:renamed` off this).
    (ensure-field-indexes! tx schema)
    changes))


;; === Logging ===

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


;; === Entry point ===

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
