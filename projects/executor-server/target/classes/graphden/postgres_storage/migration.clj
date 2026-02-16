(ns graphden.postgres-storage.migration
  "Schema migration logic for PostgreSQL.
   Handles first-time initialization and incremental migrations.
   Uses generic-migration pipeline with postgres-specific callbacks."
  (:require
    [clojure.tools.logging :as log]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.postgres-storage.ddl :as ddl]
    [graphden.postgres-storage.introspection :as introspection]
    [graphden.postgres-storage.metadata :as metadata]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.generic-migration :as gm]
    [graphden.storage-protocol.interface :as sp]
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
    (ddl/create-ref-indexes! ds entity-name fields)))


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
       (fn [entity-name field-name _field-spec old-field-info]
         (let [old-field-name (:field old-field-info)
               old-db-field (get old-db-fields old-field-name)]
           (when (and (seq old-db-fields) (nil? old-db-field))
             (throw (ex-info "Metadata/DB inconsistency: field exists in metadata but not in database"
                             {:type :metadata-error/inconsistency
                              :entity entity-name
                              :field field-name
                              :expected-column (util/kw->snake-case old-field-name)})))))))

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
     (ddl/add-column! tx entity-name field-name field-spec)
     (when (= :ref (:type field-spec))
       (ddl/create-ref-index! tx entity-name field-name)))

   :on-rename-entity!
   (fn [_ctx old-name new-name]
     (ddl/rename-table! tx old-name new-name))

   :on-rename-field!
   (fn [_ctx entity-name old-name new-name]
     (ddl/rename-column! tx entity-name old-name new-name))

   :on-existing-field!
   (fn [ctx entity-name field-name field-spec _old-field-info]
     ;; Type widening - use cached columns
     (let [table-name (util/kw->snake-case entity-name)
           old-fields (get-cached-columns tx table-name ctx)
           old-type (:type (get old-fields field-name))
           new-type (:type field-spec)]
       (when (and old-type
                  (not= old-type new-type)
                  (sp/safe-type-change? old-type new-type))
         (ddl/alter-column-type! tx entity-name field-name
                                 (util/field-type->pg field-spec)))))

   :save-metadata!
   (fn [schema']
     (metadata/save-metadata-in-tx! tx schema'))

   :extra-context-keys
   {:columns-cache (atom {})}})


(defn do-migration!
  "Performs schema migration within a transaction."
  [tx schema old-metadata]
  (gm/do-migration! (make-postgres-callbacks tx) old-metadata schema))


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
