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


(defn- build-field-schema
  "Builds Datomic schema for a single field."
  [entity-name field-name field-spec]
  (let [attr-ident (entity-attr entity-name field-name)
        value-type (field-type->datomic field-spec)]
    {:db/ident attr-ident
     :db/valueType value-type
     :db/cardinality :db.cardinality/one}))


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
          (for [entity-name (ds/entities schema)]
            {:graphden.metadata/uuid (ds/entity-uuid schema entity-name)
             :graphden.metadata/kind :entity
             :graphden.metadata/name entity-name})
          ;; Fields
          (for [entity-name (ds/entities schema)
                [field-name field-spec] (ds/entity-fields schema entity-name)]
            {:graphden.metadata/uuid (:uuid field-spec)
             :graphden.metadata/kind :field
             :graphden.metadata/name field-name
             :graphden.metadata/parent-uuid (ds/entity-uuid schema entity-name)
             :graphden.metadata/field-type (:type field-spec)
             :graphden.metadata/field-nullable (get field-spec :nullable? false)})
          ;; Enums
          (for [[enum-name {:keys [uuid]}] (ds/enums schema)]
            {:graphden.metadata/uuid uuid
             :graphden.metadata/kind :enum
             :graphden.metadata/name enum-name})
          ;; Enum values
          (for [[_enum-name {:keys [uuid values]}] (ds/enums schema)
                [value-kw value-uuid] values]
            {:graphden.metadata/uuid value-uuid
             :graphden.metadata/kind :enum-value
             :graphden.metadata/name value-kw
             :graphden.metadata/parent-uuid uuid}))]
    (when (seq tx-data)
      (d/transact conn {:tx-data (vec tx-data)}))))


;; === Destructive change checks ===
;; Using shared utilities from sp/check-removed! and sp/check-type-change!


;; === Initialize ===

(defn- do-initialize
  "Performs initialization/migration of the database."
  [conn schema]
  (let [db (d/db conn)
        old-metadata (read-metadata db)]

    (if (nil? old-metadata)
      ;; First-time initialization
      (let [;; Build schema for metadata attributes
            metadata-schema (build-metadata-schema)
            ;; Build schema for enum values (as ident entities)
            enum-schema (for [[enum-name {:keys [values]}] (ds/enums schema)
                              [value-kw _] values]
                          (build-enum-value-schema enum-name value-kw))
            ;; Build schema for entity attributes
            field-schema (for [entity-name (ds/entities schema)
                               [field-name field-spec] (ds/entity-fields schema entity-name)]
                           (build-field-schema entity-name field-name field-spec))
            all-schema (concat metadata-schema enum-schema field-schema)]

        ;; Transact all schema
        (when (seq all-schema)
          (d/transact conn {:tx-data (vec all-schema)}))

        ;; Save metadata
        (save-metadata! conn schema)

        ;; Return changes
        {:entities {:created (vec (ds/entities schema)) :renamed {}}
         :fields {:created (vec (for [e (ds/entities schema)
                                      [f _] (ds/entity-fields schema e)]
                                  {:entity e :field f}))
                  :renamed []}
         :enums {:created (vec (keys (ds/enums schema))) :renamed {}}
         :enum-values {:created (vec (for [[enum-name {:keys [values]}] (ds/enums schema)
                                           [v _] values]
                                       {:enum enum-name :value v}))}})

      ;; Migration
      (do
        ;; Check for destructive changes
        (let [old-entity-uuids (set (keys (:entities old-metadata)))
              new-entity-uuids (set (map #(ds/entity-uuid schema %) (ds/entities schema)))]
          (sp/check-removed! "entities" old-entity-uuids new-entity-uuids
                             #(get (:entities old-metadata) %)))

        (let [old-field-uuids (set (keys (:fields old-metadata)))
              new-field-uuids (set (for [e (ds/entities schema)
                                         [_ spec] (ds/entity-fields schema e)]
                                     (:uuid spec)))]
          (sp/check-removed! "fields" old-field-uuids new-field-uuids
                             #(get (:fields old-metadata) %)))

        (let [old-enum-uuids (set (keys (:enums old-metadata)))
              new-enum-uuids (set (map (fn [[_ {:keys [uuid]}]] uuid) (ds/enums schema)))]
          (sp/check-removed! "enums" old-enum-uuids new-enum-uuids
                             #(get (:enums old-metadata) %)))

        (let [old-value-uuids (set (keys (:enum-values old-metadata)))
              new-value-uuids (set (for [[_ {:keys [values]}] (ds/enums schema)
                                         [_ uuid] values]
                                     uuid))]
          (sp/check-removed! "enum values" old-value-uuids new-value-uuids
                             #(get (:enum-values old-metadata) %)))

        ;; Check type changes
        (doseq [entity-name (ds/entities schema)]
          (let [entity-uuid (ds/entity-uuid schema entity-name)
                old-entity-name (get (:entities old-metadata) entity-uuid)]
            (when old-entity-name
              (doseq [[field-name field-spec] (ds/entity-fields schema entity-name)]
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
                                         :entity entity-name
                                         :field field-name
                                         :expected-attr old-attr})))
                      ;; Use type from metadata (preserves JSONB/Union correctly)
                      (let [old-type (:type old-field-info)
                            new-type (:type field-spec)
                            old-nullable? (:nullable? old-field-info)
                            new-nullable? (get field-spec :nullable? false)]
                        (sp/check-type-change! entity-name field-name old-type new-type)
                        (sp/check-nullable-change! entity-name field-name old-nullable? new-nullable?)))))))))

        ;; Compute changes and apply new schema
        (let [;; Compute entity changes
              created-entities (atom [])
              renamed-entities (atom {})

              ;; Compute field changes
              created-fields (atom [])
              renamed-fields (atom [])

              ;; Compute enum changes
              created-enums (atom [])
              renamed-enums (atom {})

              ;; Compute enum value changes
              created-enum-values (atom [])

              ;; Schema to transact
              new-schema (atom [])]

          ;; Process enums
          (doseq [[enum-name {:keys [uuid values]}] (ds/enums schema)]
            (if-let [old-enum-name (get (:enums old-metadata) uuid)]
              (do
                (when (not= old-enum-name enum-name)
                  (swap! renamed-enums assoc old-enum-name enum-name))
                ;; Check for new values
                (doseq [[value-kw value-uuid] values]
                  (when-not (get (:enum-values old-metadata) value-uuid)
                    (swap! new-schema conj (build-enum-value-schema enum-name value-kw))
                    (swap! created-enum-values conj {:enum enum-name :value value-kw}))))
              (do
                (swap! created-enums conj enum-name)
                (doseq [[value-kw _] values]
                  (swap! new-schema conj (build-enum-value-schema enum-name value-kw))
                  (swap! created-enum-values conj {:enum enum-name :value value-kw})))))

          ;; Process entities and fields
          (doseq [entity-name (ds/entities schema)]
            (let [entity-uuid (ds/entity-uuid schema entity-name)
                  old-entity-name (get (:entities old-metadata) entity-uuid)]
              (if old-entity-name
                (do
                  (when (not= old-entity-name entity-name)
                    (swap! renamed-entities assoc old-entity-name entity-name))
                  ;; Process fields
                  (doseq [[field-name field-spec] (ds/entity-fields schema entity-name)]
                    (let [field-uuid (:uuid field-spec)
                          old-field-info (get (:fields old-metadata) field-uuid)]
                      (if old-field-info
                        (when (not= (:field old-field-info) field-name)
                          (swap! renamed-fields conj {:entity entity-name
                                                      :old-field (:field old-field-info)
                                                      :new-field field-name}))
                        (do
                          (swap! new-schema conj (build-field-schema entity-name field-name field-spec))
                          (swap! created-fields conj {:entity entity-name :field field-name}))))))
                (do
                  (swap! created-entities conj entity-name)
                  (doseq [[field-name field-spec] (ds/entity-fields schema entity-name)]
                    (swap! new-schema conj (build-field-schema entity-name field-name field-spec))
                    (swap! created-fields conj {:entity entity-name :field field-name}))))))

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


;; === Storage record ===

(defrecord DatomicStorage
  [client-config db-name client-atom conn-atom]

  sp/Storage

  (initialize
    [_this schema]
    (let [client (d/client client-config)]
      (reset! client-atom client)
      (d/create-database client {:db-name db-name})
      (let [conn (d/connect client {:db-name db-name})]
        (reset! conn-atom conn)
        (do-initialize conn schema))))


  (close
    [_this]
    (when-let [client @client-atom]
      (d/delete-database client {:db-name db-name}))
    (reset! conn-atom nil)
    (reset! client-atom nil)
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (if-let [conn @conn-atom]
      (let [db (d/db conn)
            attrs (current-attrs db)]
        (->> (keys attrs)
             (map namespace)
             (filter some?)
             (set)
             (map keyword)
             (set)))
      #{}))


  (current-fields
    [_this entity-name]
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
                       entity-fields)))))))


  (current-enums
    [_this]
    (if-let [conn @conn-atom]
      (let [db (d/db conn)
            enum-values (current-enum-values-db db)]
        (->> enum-values
             (map #(-> (namespace %) (str/replace ".value" "") keyword))
             (set)))
      #{}))


  (current-enum-values
    [_this enum-name]
    (when-let [conn @conn-atom]
      (let [db (d/db conn)
            enum-values (current-enum-values-db db)
            enum-ns (str (name enum-name) ".value")
            values (->> enum-values
                        (filter #(= (namespace %) enum-ns))
                        (map #(keyword (name %)))
                        (set))]
        (when (seq values) values))))


  (schema-metadata
    [_this]
    (when-let [conn @conn-atom]
      (read-metadata (d/db conn)))))


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
  (->DatomicStorage client-config db-name (atom nil) (atom nil)))
