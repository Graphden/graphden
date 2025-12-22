(ns graphden.datomic-storage.core
  "Datomic Local implementation of Storage protocol."
  (:require
    [clojure.set :as set]
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
    (let [;; Query entities that have parent-uuid
          with-parent (d/q '[:find ?uuid ?kind ?name ?parent-uuid
                             :where
                             [?e :graphden.metadata/uuid ?uuid]
                             [?e :graphden.metadata/kind ?kind]
                             [?e :graphden.metadata/name ?name]
                             [?e :graphden.metadata/parent-uuid ?parent-uuid]]
                           db)
          ;; Query entities without parent-uuid
          without-parent (d/q '[:find ?uuid ?kind ?name
                                :where
                                [?e :graphden.metadata/uuid ?uuid]
                                [?e :graphden.metadata/kind ?kind]
                                [?e :graphden.metadata/name ?name]
                                (not [?e :graphden.metadata/parent-uuid _])]
                              db)
          ;; Combine: add nil as parent-uuid for those without
          rows (concat with-parent
                       (map (fn [[uuid kind n]] [uuid kind n nil]) without-parent))]
      (when (seq rows)
        (let [uuid->row (into {} (map (fn [[uuid kind n parent]]
                                        [uuid {:kind kind :name n :parent-uuid parent}])
                                      rows))]
          (reduce
            (fn [acc [uuid {:keys [kind name parent-uuid]}]]
              (case kind
                :entity (assoc-in acc [:entities uuid] name)
                :field (let [parent-name (:name (get uuid->row parent-uuid))]
                         (assoc-in acc [:fields uuid] {:entity parent-name :field name}))
                :enum (assoc-in acc [:enums uuid] name)
                :enum-value (let [parent-name (:name (get uuid->row parent-uuid))]
                              (assoc-in acc [:enum-values uuid] {:enum parent-name :value name}))
                acc))
            {:entities {} :fields {} :enums {} :enum-values {}}
            uuid->row))))))


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
             :graphden.metadata/parent-uuid (ds/entity-uuid schema entity-name)})
          ;; Enums
          (for [[enum-name {:keys [uuid]}] (ds/enums schema)]
            {:graphden.metadata/uuid uuid
             :graphden.metadata/kind :enum
             :graphden.metadata/name enum-name})
          ;; Enum values
          (for [[enum-name {:keys [uuid values]}] (ds/enums schema)
                [value-kw value-uuid] values]
            {:graphden.metadata/uuid value-uuid
             :graphden.metadata/kind :enum-value
             :graphden.metadata/name value-kw
             :graphden.metadata/parent-uuid uuid}))]
    (when (seq tx-data)
      (d/transact conn {:tx-data (vec tx-data)}))))


;; === Destructive change checks ===

(defn- check-removed!
  "Checks for removed items and throws if any found."
  [item-type old-uuids new-uuids get-name-fn]
  (let [removed (set/difference old-uuids new-uuids)]
    (when (seq removed)
      (throw (ex-info (str "Destructive change: " item-type " removed")
                      {:type :destructive-change
                       :removed (vec (map get-name-fn removed))})))))


(defn- check-type-change!
  "Checks that a field type change is safe."
  [entity-name field-name old-type new-type]
  (when (and old-type
             (not (sp/safe-type-change? old-type new-type)))
    (throw (ex-info "Destructive change: incompatible type change"
                    {:type :destructive-change
                     :entity entity-name
                     :field field-name
                     :old-type old-type
                     :new-type new-type}))))


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
          (check-removed! "entities" old-entity-uuids new-entity-uuids
                          #(get (:entities old-metadata) %)))

        (let [old-field-uuids (set (keys (:fields old-metadata)))
              new-field-uuids (set (for [e (ds/entities schema)
                                         [_ spec] (ds/entity-fields schema e)]
                                     (:uuid spec)))]
          (check-removed! "fields" old-field-uuids new-field-uuids
                          #(get (:fields old-metadata) %)))

        (let [old-enum-uuids (set (keys (:enums old-metadata)))
              new-enum-uuids (set (map (fn [[_ {:keys [uuid]}]] uuid) (ds/enums schema)))]
          (check-removed! "enums" old-enum-uuids new-enum-uuids
                          #(get (:enums old-metadata) %)))

        (let [old-value-uuids (set (keys (:enum-values old-metadata)))
              new-value-uuids (set (for [[_ {:keys [values]}] (ds/enums schema)
                                         [_ uuid] values]
                                     uuid))]
          (check-removed! "enum values" old-value-uuids new-value-uuids
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
                          attr-info (ffirst (d/q '[:find ?type
                                                   :in $ ?attr
                                                   :where
                                                   [?e :db/ident ?attr]
                                                   [?e :db/valueType ?vt]
                                                   [?vt :db/ident ?type]]
                                                 db old-attr))
                          old-type (get datomic->type attr-info)
                          new-type (:type field-spec)]
                      (check-type-change! entity-name field-name old-type new-type))))))))

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
    (when-let [conn @conn-atom]
      (let [db (d/db conn)
            attrs (current-attrs db)]
        (->> (keys attrs)
             (map namespace)
             (filter some?)
             (set)
             (map keyword)
             (set)))))


  (current-fields
    [_this entity-name]
    (when-let [conn @conn-atom]
      (let [db (d/db conn)
            attrs (current-attrs db)
            entity-ns (name entity-name)
            entity-attrs (filter (fn [[k _]] (= (namespace k) entity-ns)) attrs)]
        (when (seq entity-attrs)
          (into {}
                (map (fn [[attr datomic-type]]
                       (let [field-name (keyword (name attr))
                             our-type (get datomic->type datomic-type :text)]
                         [field-name {:type our-type
                                      :nullable? true}]))  ; Datomic doesn't have NOT NULL
                     entity-attrs))))))


  (current-enums
    [_this]
    (when-let [conn @conn-atom]
      (let [db (d/db conn)
            enum-values (current-enum-values-db db)]
        (->> enum-values
             (map #(-> (namespace %) (str/replace ".value" "") keyword))
             (set)))))


  (current-enum-values
    [_this enum-name]
    (when-let [conn @conn-atom]
      (let [db (d/db conn)
            enum-values (current-enum-values-db db)
            enum-ns (str (name enum-name) ".value")]
        (let [values (->> enum-values
                          (filter #(= (namespace %) enum-ns))
                          (map #(keyword (name %)))
                          (set))]
          (when (seq values) values)))))


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
