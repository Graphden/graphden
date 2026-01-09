(ns graphden.datomic-storage.migration
  "Datomic schema migration helpers.

   Provides functions for:
   - First-time schema initialization
   - Schema migration with change tracking
   - Destructive change validation"
  (:require
    [clojure.set :as set]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.introspection :as introspection]
    [graphden.datomic-storage.metadata :as metadata]
    [graphden.datomic-storage.schema :as schema]
    [graphden.datomic-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]))


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


;; === Initialize ===

(defn do-first-init
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
    (metadata/save-metadata! conn schema)
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


(defn do-migration
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
    (metadata/save-metadata! conn schema)

    ;; Return changes
    (context->changes ctx)))


(defn do-initialize
  "Performs initialization/migration of the database.
   Delegates to do-first-init or do-migration based on existing metadata."
  [conn schema]
  ;; Log info about multi-field unique constraints (enforced at application level)
  (doseq [entity-name (ds/entities schema)]
    (schema/warn-multi-field-constraints! schema entity-name))

  (let [db (d/db conn)
        old-metadata (introspection/read-metadata db)]
    (if (nil? old-metadata)
      (do-first-init conn schema)
      (do-migration conn db old-metadata schema))))
