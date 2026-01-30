(ns graphden.datomic-storage.migration
  "Datomic schema migration helpers.
   Uses generic-migration pipeline with datomic-specific callbacks.

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
    [graphden.storage-protocol.generic-migration :as gm]
    [graphden.storage-protocol.interface :as sp]))


;; === Schema builders for initialization ===

(defn- build-enum-schemas
  "Builds all enum value schemas for initialization."
  [data-schema]
  (mapcat (fn [[enum-name {:keys [values]}]]
            (map (fn [[value-kw _]] (schema/build-enum-value-schema enum-name value-kw))
                 values))
          (ds/enums data-schema)))


(defn- build-field-schemas
  "Builds all field schemas for initialization.
   Includes :id attribute for each entity."
  [data-schema]
  (mapcat (fn [entity-name]
            (cons (schema/build-id-schema entity-name)
                  (map (fn [[field-name field-spec]]
                         (schema/build-field-schema data-schema entity-name field-name field-spec))
                       (ds/entity-fields data-schema entity-name))))
          (ds/entities data-schema)))


;; === First-time initialization ===

(defn do-first-init
  "First-time initialization: creates all schema and metadata.
   Returns changes map with all created entities/fields/enums."
  [conn data-schema]
  (let [metadata-schema (schema/build-metadata-schema)
        enum-schema (build-enum-schemas data-schema)
        field-schema (build-field-schemas data-schema)
        all-schema (concat metadata-schema enum-schema field-schema)]
    ;; Transact all schema
    (when (seq all-schema)
      (d/transact conn {:tx-data (vec all-schema)}))
    ;; Save metadata
    (metadata/save-metadata! conn data-schema)
    ;; Return changes using shared function
    (sp/build-first-init-changes data-schema)))


;; === Migration context validation ===

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


;; === Migration callbacks ===

(defn- make-datomic-callbacks
  "Creates the callbacks map for datomic migration."
  [conn db data-schema]
  {:make-field-verifier
   (fn [_old-metadata old-entity-name]
     (fn [entity-name field-name _field-spec old-field-info]
       (let [old-attr (util/entity-attr old-entity-name (:field old-field-info))
             attr-exists? (seq (d/q '[:find ?e
                                      :in $ ?attr
                                      :where [?e :db/ident ?attr]]
                                    db old-attr))]
         (when-not attr-exists?
           (throw (ex-info "Metadata/DB inconsistency: field exists in metadata but not in database"
                           {:type :metadata-error/inconsistency
                            :entity entity-name
                            :field field-name
                            :expected-attr old-attr}))))))

   :on-create-enum!
   (fn [ctx enum-name values]
     (run! (fn [[value-kw _]]
             (swap! (:new-schema ctx) conj (schema/build-enum-value-schema enum-name value-kw)))
           values))

   :on-add-enum-value!
   (fn [ctx enum-name value-kw]
     (swap! (:new-schema ctx) conj (schema/build-enum-value-schema enum-name value-kw)))

   :on-create-entity!
   (fn [ctx schema' entity-name]
     ;; Add :id attribute for new entity
     (swap! (:new-schema ctx) conj (schema/build-id-schema entity-name))
     (run! (fn [[field-name field-spec]]
             (swap! (:new-schema ctx) conj (schema/build-field-schema schema' entity-name field-name field-spec)))
           (ds/entity-fields schema' entity-name)))

   :on-create-field!
   (fn [ctx entity-name field-name field-spec]
     (swap! (:new-schema ctx) conj (schema/build-field-schema data-schema entity-name field-name field-spec)))

   ;; Datomic doesn't support rename at schema level - only tracked in metadata
   ;; (no :on-rename-enum!, :on-rename-entity!, :on-rename-field!)

   :save-metadata!
   (fn [schema']
     (metadata/save-metadata! conn schema'))

   :extra-context-keys
   {:new-schema (atom [])}

   :post-process!
   (fn [ctx]
     ;; Validate migration context consistency
     (validate-migration-context! ctx)
     ;; Transact accumulated schema
     (when (seq @(:new-schema ctx))
       (d/transact conn {:tx-data @(:new-schema ctx)})))})


(defn do-migration
  "Performs schema migration from old-metadata to new schema.
   Returns changes map with created/renamed entities/fields/enums."
  [conn db old-metadata data-schema]
  (gm/do-migration! (make-datomic-callbacks conn db data-schema) old-metadata data-schema))


;; === Entry point ===

(defn do-initialize
  "Performs initialization/migration of the database.
   Delegates to do-first-init or do-migration based on existing metadata."
  [conn data-schema]
  ;; Log info about multi-field unique constraints (enforced at application level)
  (doseq [entity-name (ds/entities data-schema)]
    (schema/warn-multi-field-constraints! data-schema entity-name))

  (let [db (d/db conn)
        old-metadata (introspection/read-metadata db)]
    (if (nil? old-metadata)
      (do-first-init conn data-schema)
      (do-migration conn db old-metadata data-schema))))
