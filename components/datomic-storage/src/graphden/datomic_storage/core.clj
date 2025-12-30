(ns graphden.datomic-storage.core
  "Datomic Local implementation of Storage protocol."
  (:require
    [clojure.string :as str]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.storage-protocol.interface :as sp]))


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


(def datomic->type
  "Reverse mapping from Datomic types to our types."
  {:db.type/uuid    :uuid
   :db.type/string  :text
   :db.type/long    :int
   :db.type/boolean :bool
   :db.type/bigdec  :numeric
   :db.type/instant :timestamptz
   :db.type/bytes   :bytes
   :db.type/ref     :ref})


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
  (if (= (:type constraint) :unique)
    (= (count (:fields constraint)) 1)
    false))


(defn- get-single-field-constraints
  "Returns a set of field names that are part of single-field unique constraints."
  [schema entity-name]
  (let [constraints (ds/entity-constraints schema entity-name)]
    (->> constraints
         (filter single-field-unique-constraint?)
         (mapcat :fields)
         (set))))


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
  "Checks if metadata schema attributes exist in the database."
  [db]
  (try
    (let [result (d/q '[:find ?e
                        :where [?e :db/ident :graphden.metadata/uuid]]
                      db)]
      (seq result))
    (catch Exception _
      false)))


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
                           [uuid {:entity (get entity-uuid->name parent-uuid)
                                  :field field-name
                                  :type field-type
                                  :nullable? field-nullable}]))
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
  {:graphden.metadata/uuid (:uuid field-spec)
   :graphden.metadata/kind :field
   :graphden.metadata/name field-name
   :graphden.metadata/parent-uuid (ds/entity-uuid schema entity-name)
   :graphden.metadata/field-type (:type field-spec)
   :graphden.metadata/field-nullable (get field-spec :nullable? false)})


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
  "Saves metadata to the database (retract old, assert new)."
  [conn schema]
  ;; First, retract all existing metadata
  (let [db (d/db conn)
        existing (d/q '[:find ?e
                        :where [?e :graphden.metadata/uuid _]]
                      db)]
    (when (seq existing)
      (d/transact conn {:tx-data (vec (map (fn [[e]] [:db/retractEntity e]) existing))})))

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
      (d/transact conn {:tx-data (vec tx-data)}))))


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


(defn- collect-created-fields
  "Collects all field creation info for changes report."
  [schema]
  (vec (mapcat (fn [e]
                 (map (fn [[f _]] {:entity e :field f})
                      (ds/entity-fields schema e)))
               (ds/entities schema))))


(defn- collect-created-enum-values
  "Collects all enum value creation info for changes report."
  [schema]
  (vec (mapcat (fn [[enum-name {:keys [values]}]]
                 (map (fn [[v _]] {:enum enum-name :value v})
                      values))
               (ds/enums schema))))


(defn- collect-field-uuids
  "Collects all field UUIDs from schema."
  [schema]
  (set (mapcat (fn [e]
                 (map (fn [[_ spec]] (:uuid spec))
                      (ds/entity-fields schema e)))
               (ds/entities schema))))


(defn- collect-enum-value-uuids
  "Collects all enum value UUIDs from schema."
  [schema]
  (set (mapcat (fn [[_ {:keys [values]}]]
                 (map second values))
               (ds/enums schema))))


;; === Initialize ===

(defn- do-initialize
  "Performs initialization/migration of the database."
  [conn schema]
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
         :fields {:created (collect-created-fields schema) :renamed []}
         :enums {:created (vec (keys (ds/enums schema))) :renamed {}}
         :enum-values {:created (collect-created-enum-values schema)}})

      ;; Migration
      (do
        ;; Check for destructive changes
        (let [old-entity-uuids (set (keys (:entities old-metadata)))
              new-entity-uuids (set (map #(ds/entity-uuid schema %) (ds/entities schema)))]
          (sp/check-removed! "entities" old-entity-uuids new-entity-uuids
                             #(get (:entities old-metadata) %)))

        (let [old-field-uuids (set (keys (:fields old-metadata)))
              new-field-uuids (collect-field-uuids schema)]
          (sp/check-removed! "fields" old-field-uuids new-field-uuids
                             #(get (:fields old-metadata) %)))

        (let [old-enum-uuids (set (keys (:enums old-metadata)))
              new-enum-uuids (set (map (fn [[_ {:keys [uuid]}]] uuid) (ds/enums schema)))]
          (sp/check-removed! "enums" old-enum-uuids new-enum-uuids
                             #(get (:enums old-metadata) %)))

        (let [old-value-uuids (set (keys (:enum-values old-metadata)))
              new-value-uuids (collect-enum-value-uuids schema)]
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

(defn- entity->tx
  "Converts entity map to Datomic transaction data.
   Uses namespaced attributes for the entity type.
   The :id field is stored as :entity-name/id (UUID)."
  [entity-name data id temp-id]
  (let [base-tx {:db/id temp-id
                 (entity-attr entity-name :id) id}]
    (reduce-kv (fn [acc k v]
                 (if (= k :id)
                   acc  ; Already handled above
                   (assoc acc (entity-attr entity-name k) v)))
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


(defn- create-entity-impl
  "Creates a new entity in Datomic."
  [conn entity-name data]
  (let [id (or (:id data) (random-uuid))
        temp-id (str "new-entity-" (random-uuid))
        tx-data [(entity->tx entity-name (assoc data :id id) id temp-id)]]
    (d/transact conn {:tx-data tx-data})
    (let [new-db (d/db conn)
          fields (get-entity-fields new-db entity-name)]
      (pull-entity new-db entity-name id fields))))


(defn- read-entity-impl
  "Reads an entity by id."
  [conn entity-name id]
  (let [db (d/db conn)
        fields (get-entity-fields db entity-name)]
    (pull-entity db entity-name id fields)))


(defn- update-entity-impl
  "Updates an entity by id."
  [conn entity-name id data]
  (let [db (d/db conn)
        fields (get-entity-fields db entity-name)
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
          updated (merge existing data {:id id})
          ;; Use actual entity id for update
          tx-data [(entity->tx entity-name updated id eid)]]
      (d/transact conn {:tx-data tx-data})
      (let [new-db (d/db conn)]
        (pull-entity new-db entity-name id fields)))))


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
  "Queries entities by conditions."
  [conn entity-name where]
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


;; === GraphConstraints helpers ===

(defn- get-fn-schema-id
  "Gets fn-schema-id for a fn record."
  [db fn-id]
  (ffirst (d/q '[:find ?schema-id
                 :in $ ?fn-id
                 :where
                 [?e :fn/id ?fn-id]
                 [?e :fn/fn-schema-id ?schema-id]]
               db fn-id)))


(defn- get-parent-fn-id
  "Gets parent-fn-id for a fn record."
  [db fn-id]
  (ffirst (d/q '[:find ?parent-id
                 :in $ ?fn-id
                 :where
                 [?e :fn/id ?fn-id]
                 [?e :fn/parent-fn-id ?parent-id]]
               db fn-id)))


(defn- get-arg-schema-fn-schema-id
  "Gets fn-schema-id for an arg-schema record."
  [db arg-schema-id]
  (ffirst (d/q '[:find ?schema-id
                 :in $ ?arg-schema-id
                 :where
                 [?e :arg-schema/id ?arg-schema-id]
                 [?e :arg-schema/fn-schema-id ?schema-id]]
               db arg-schema-id)))


(defn- collect-parent-chain
  "Collects all ancestor fn-ids by following parent-fn-id links.
   Returns a set of fn-ids (not including the starting fn-id)."
  [db fn-id]
  (loop [current-id (get-parent-fn-id db fn-id)
         ancestor-ids #{}]
    (if (or (nil? current-id) (contains? ancestor-ids current-id))
      ancestor-ids
      (recur (get-parent-fn-id db current-id)
             (conj ancestor-ids current-id)))))


(defn- collect-arg-schema-ids-in-chain
  "Collects all arg-schema-ids defined in the parent chain (not including fn-id itself)."
  [db fn-id]
  (let [ancestor-ids (collect-parent-chain db fn-id)]
    (if (empty? ancestor-ids)
      #{}
      (let [results (d/q '[:find ?arg-schema-id
                           :in $ [?owner-id ...]
                           :where
                           [?e :arg-value/owner-fn-id ?owner-id]
                           [?e :arg-value/arg-schema-id ?arg-schema-id]]
                         db (vec ancestor-ids))]
        (set (map first results))))))


(defn- collect-dependency-chain
  "Collects all fn-ids that owner-fn depends on through arg-values.
   DFS traversal of value refs."
  [db owner-fn-id]
  (loop [to-visit [owner-fn-id]
         visited #{}]
    (if (empty? to-visit)
      visited
      (let [current-id (first to-visit)
            rest-to-visit (rest to-visit)]
        (if (contains? visited current-id)
          (recur rest-to-visit visited)
          (let [;; Get arg-values for current fn
                arg-values (d/q '[:find ?value
                                  :in $ ?owner-id
                                  :where
                                  [?e :arg-value/owner-fn-id ?owner-id]
                                  [?e :arg-value/value ?value]]
                                db current-id)
                ;; Get fn references from arg-values (UUIDs that are fn refs)
                ref-fn-ids (->> arg-values
                                (map first)
                                (filter uuid?)
                                ;; Check if this UUID is actually a fn
                                (filter (fn [fn-id]
                                          (seq (d/q '[:find ?e
                                                      :in $ ?fn-id
                                                      :where
                                                      [?e :fn/id ?fn-id]]
                                                    db fn-id)))))]
            (recur (concat rest-to-visit ref-fn-ids)
                   (conj visited current-id))))))))


(defn- validate-parent-same-schema-impl!
  "Validates that parent-fn has the same fn-schema-id as fn."
  [db fn-id parent-fn-id]
  (when parent-fn-id
    (let [fn-schema-id (get-fn-schema-id db fn-id)
          parent-schema-id (get-fn-schema-id db parent-fn-id)]
      (when (and fn-schema-id parent-schema-id
                 (not= fn-schema-id parent-schema-id))
        (throw (ex-info "Parent fn has different fn-schema-id"
                        {:type :constraint-violation/parent-schema-mismatch
                         :fn-id fn-id
                         :parent-fn-id parent-fn-id
                         :fn-schema-id fn-schema-id
                         :parent-schema-id parent-schema-id}))))))


(defn- validate-no-arg-override-impl!
  "Validates that arg-schema-id is not already defined in the parent chain."
  [db fn-id arg-schema-id]
  (let [parent-arg-schema-ids (collect-arg-schema-ids-in-chain db fn-id)]
    (when (contains? parent-arg-schema-ids arg-schema-id)
      (throw (ex-info "Argument already defined in parent chain"
                      {:type :constraint-violation/arg-already-defined
                       :fn-id fn-id
                       :arg-schema-id arg-schema-id})))))


(defn- validate-arg-schema-belongs-to-fn-impl!
  "Validates that arg-schema belongs to fn's fn-schema."
  [db fn-id arg-schema-id]
  (let [fn-schema-id (get-fn-schema-id db fn-id)
        arg-fn-schema-id (get-arg-schema-fn-schema-id db arg-schema-id)]
    (when (and fn-schema-id arg-fn-schema-id
               (not= fn-schema-id arg-fn-schema-id))
      (throw (ex-info "Arg-schema does not belong to fn's schema"
                      {:type :constraint-violation/arg-schema-mismatch
                       :fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :fn-schema-id fn-schema-id
                       :arg-fn-schema-id arg-fn-schema-id})))))


(defn- validate-no-inheritance-cycle-impl!
  "Validates that setting parent-fn-id would not create an inheritance cycle."
  [db fn-id parent-fn-id]
  (when parent-fn-id
    ;; Check self-reference
    (when (= fn-id parent-fn-id)
      (throw (ex-info "Cannot set self as parent"
                      {:type :constraint-violation/inheritance-cycle
                       :fn-id fn-id
                       :parent-fn-id parent-fn-id})))
    ;; Check if fn-id appears in parent's ancestor chain
    (let [parent-ancestors (collect-parent-chain db parent-fn-id)]
      (when (contains? parent-ancestors fn-id)
        (throw (ex-info "Setting parent would create inheritance cycle"
                        {:type :constraint-violation/inheritance-cycle
                         :fn-id fn-id
                         :parent-fn-id parent-fn-id
                         :cycle-through (conj parent-ancestors parent-fn-id)}))))))


(defn- validate-no-dependency-cycle-impl!
  "Validates that referencing value-fn-id would not create a dependency cycle."
  [db owner-fn-id value-fn-id]
  (when value-fn-id
    ;; Check if owner-fn-id is in the dependency chain of value-fn-id
    (let [value-deps (collect-dependency-chain db value-fn-id)]
      (when (contains? value-deps owner-fn-id)
        (throw (ex-info "Reference would create dependency cycle"
                        {:type :constraint-violation/dependency-cycle
                         :owner-fn-id owner-fn-id
                         :value-fn-id value-fn-id}))))))


;; === Storage record ===

(defrecord DatomicStorage
  [client-config db-name client-atom conn-atom lock]

  sp/Storage

  (initialize
    [_this schema]
    (locking lock
      (let [client (d/client client-config)]
        (reset! client-atom client)
        ;; Create database if it doesn't exist (idempotent)
        (try
          (d/create-database client {:db-name db-name})
          (catch Exception e
            ;; Ignore "already exists" errors for idempotency
            (when-not (str/includes? (ex-message e) "already exists")
              (throw e))))
        (let [conn (d/connect client {:db-name db-name})]
          (reset! conn-atom conn)
          (do-initialize conn schema)))))


  (close
    [_this]
    (locking lock
      (when-let [client @client-atom]
        (d/delete-database client {:db-name db-name}))
      (reset! conn-atom nil)
      (reset! client-atom nil))
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (locking lock
      (if-let [conn @conn-atom]
        (let [db (d/db conn)
              attrs (current-attrs db)]
          (->> (keys attrs)
               (map namespace)
               (filter some?)
               (set)
               (map keyword)
               (set)))
        #{})))


  (current-fields
    [_this entity-name]
    (locking lock
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
                         entity-fields))))))))


  (current-enums
    [_this]
    (locking lock
      (if-let [conn @conn-atom]
        (let [db (d/db conn)
              enum-values (current-enum-values-db db)]
          (->> enum-values
               (map #(-> (namespace %) (str/replace ".value" "") keyword))
               (set)))
        #{})))


  (current-enum-values
    [_this enum-name]
    (locking lock
      (when-let [conn @conn-atom]
        (let [db (d/db conn)
              enum-values (current-enum-values-db db)
              enum-ns (str (name enum-name) ".value")
              values (->> enum-values
                          (filter #(= (namespace %) enum-ns))
                          (map #(keyword (name %)))
                          (set))]
          (when (seq values) values)))))


  (schema-metadata
    [_this]
    (locking lock
      (when-let [conn @conn-atom]
        (read-metadata (d/db conn)))))


  sp/StorageCRUD

  (create-entity
    [_this entity-name data]
    (locking lock
      (when-let [conn @conn-atom]
        (create-entity-impl conn entity-name data))))


  (read-entity
    [_this entity-name id]
    (locking lock
      (when-let [conn @conn-atom]
        (read-entity-impl conn entity-name id))))


  (update-entity
    [_this entity-name id data]
    (locking lock
      (when-let [conn @conn-atom]
        (update-entity-impl conn entity-name id data))))


  (delete-entity
    [_this entity-name id]
    (locking lock
      (if-let [conn @conn-atom]
        (delete-entity-impl conn entity-name id)
        false)))


  (query-entities
    [_this entity-name where]
    (locking lock
      (if-let [conn @conn-atom]
        (query-entities-impl conn entity-name where)
        [])))


  sp/GraphConstraints

  (validate-parent-same-schema!
    [_this fn-id parent-fn-id]
    (locking lock
      (when-let [conn @conn-atom]
        (validate-parent-same-schema-impl! (d/db conn) fn-id parent-fn-id))))


  (validate-no-arg-override!
    [_this fn-id arg-schema-id]
    (locking lock
      (when-let [conn @conn-atom]
        (validate-no-arg-override-impl! (d/db conn) fn-id arg-schema-id))))


  (validate-arg-schema-belongs-to-fn!
    [_this fn-id arg-schema-id]
    (locking lock
      (when-let [conn @conn-atom]
        (validate-arg-schema-belongs-to-fn-impl! (d/db conn) fn-id arg-schema-id))))


  (validate-no-inheritance-cycle!
    [_this fn-id parent-fn-id]
    (locking lock
      (when-let [conn @conn-atom]
        (validate-no-inheritance-cycle-impl! (d/db conn) fn-id parent-fn-id))))


  (validate-no-dependency-cycle!
    [_this owner-fn-id value-fn-id]
    (locking lock
      (when-let [conn @conn-atom]
        (validate-no-dependency-cycle-impl! (d/db conn) owner-fn-id value-fn-id)))))


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
  (->DatomicStorage client-config db-name (atom nil) (atom nil) (Object.)))
