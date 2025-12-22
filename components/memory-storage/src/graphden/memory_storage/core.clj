(ns graphden.memory-storage.core
  "In-memory implementation of Storage protocol."
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.storage-protocol.interface :as sp]))


;; === Internal helpers ===

(defn- build-metadata-from-schema
  "Builds metadata structure from DataSchema for first-time initialization."
  [schema]
  (let [entities-meta (into {}
                            (for [entity-name (ds/entities schema)]
                              [(ds/entity-uuid schema entity-name) entity-name]))
        fields-meta (into {}
                          (for [entity-name (ds/entities schema)
                                [field-name field-spec] (ds/entity-fields schema entity-name)]
                            [(:uuid field-spec)
                             {:entity entity-name :field field-name}]))
        enums-data (ds/enums schema)
        enums-meta (into {}
                         (for [[enum-name {:keys [uuid]}] enums-data]
                           [uuid enum-name]))
        enum-values-meta (into {}
                               (for [[enum-name {:keys [values]}] enums-data
                                     [value-kw value-uuid] values]
                                 [value-uuid {:enum enum-name :value value-kw}]))]
    {:entities entities-meta
     :fields fields-meta
     :enums enums-meta
     :enum-values enum-values-meta}))


(defn- build-entities-structure
  "Builds :entities structure from DataSchema."
  [schema]
  (into {}
        (for [entity-name (ds/entities schema)]
          [entity-name
           {:fields (into {}
                          (for [[field-name field-spec] (ds/entity-fields schema entity-name)]
                            [field-name {:type (:type field-spec)
                                         :nullable? (get field-spec :nullable? false)}]))}])))


(defn- build-enums-structure
  "Builds :enums structure from DataSchema."
  [schema]
  (into {}
        (for [[enum-name {:keys [values]}] (ds/enums schema)]
          [enum-name {:values (set (keys values))}])))


(defn- check-removed-entities
  "Checks for entities that exist in metadata but not in schema. Throws on removal."
  [old-metadata schema]
  (let [old-uuids (set (keys (:entities old-metadata)))
        new-uuids (set (map #(ds/entity-uuid schema %) (ds/entities schema)))]
    (sp/check-removed! "entities" old-uuids new-uuids
                       #(get (:entities old-metadata) %))))


(defn- check-removed-fields
  "Checks for fields that exist in metadata but not in schema. Throws on removal."
  [old-metadata schema]
  (let [old-uuids (set (keys (:fields old-metadata)))
        new-uuids (set (for [entity-name (ds/entities schema)
                             [_ field-spec] (ds/entity-fields schema entity-name)]
                         (:uuid field-spec)))]
    (sp/check-removed! "fields" old-uuids new-uuids
                       #(get (:fields old-metadata) %))))


(defn- check-removed-enums
  "Checks for enums that exist in metadata but not in schema. Throws on removal."
  [old-metadata schema]
  (let [old-uuids (set (keys (:enums old-metadata)))
        new-uuids (set (map (fn [[_ {:keys [uuid]}]] uuid) (ds/enums schema)))]
    (sp/check-removed! "enums" old-uuids new-uuids
                       #(get (:enums old-metadata) %))))


(defn- check-removed-enum-values
  "Checks for enum values that exist in metadata but not in schema. Throws on removal."
  [old-metadata schema]
  (let [old-uuids (set (keys (:enum-values old-metadata)))
        new-uuids (set (for [[_ {:keys [values]}] (ds/enums schema)
                             [_ value-uuid] values]
                         value-uuid))]
    (sp/check-removed! "enum values" old-uuids new-uuids
                       #(get (:enum-values old-metadata) %))))


(defn- check-type-changes
  "Checks that all field type changes are safe. Throws on unsafe change."
  [old-state old-metadata schema]
  (doseq [entity-name (ds/entities schema)]
    (let [entity-uuid (ds/entity-uuid schema entity-name)
          old-entity-name (get (:entities old-metadata) entity-uuid)
          old-fields (get-in old-state [:entities old-entity-name :fields])]
      (when old-fields
        (doseq [[field-name field-spec] (ds/entity-fields schema entity-name)]
          (let [field-uuid (:uuid field-spec)
                old-field-info (get (:fields old-metadata) field-uuid)]
            (when old-field-info
              (let [old-field-name (:field old-field-info)
                    old-type (get-in old-fields [old-field-name :type])
                    new-type (:type field-spec)
                    old-nullable? (get-in old-fields [old-field-name :nullable?])
                    new-nullable? (get field-spec :nullable? false)]
                (sp/check-type-change! entity-name field-name old-type new-type)
                (sp/check-nullable-change! entity-name field-name old-nullable? new-nullable?)))))))))


(defn- compute-entity-changes
  "Computes created and renamed entities."
  [old-metadata schema]
  (let [old-uuid->name (:entities old-metadata)
        created (vec (for [entity-name (ds/entities schema)
                           :let [uuid (ds/entity-uuid schema entity-name)]
                           :when (not (contains? old-uuid->name uuid))]
                       entity-name))
        renamed (into {}
                      (for [entity-name (ds/entities schema)
                            :let [uuid (ds/entity-uuid schema entity-name)
                                  old-name (get old-uuid->name uuid)]
                            :when (and old-name (not= old-name entity-name))]
                        [old-name entity-name]))]
    {:created created :renamed renamed}))


(defn- compute-field-changes
  "Computes created and renamed fields."
  [old-metadata schema]
  (let [old-uuid->info (:fields old-metadata)
        created (vec (for [entity-name (ds/entities schema)
                           [field-name field-spec] (ds/entity-fields schema entity-name)
                           :let [uuid (:uuid field-spec)]
                           :when (not (contains? old-uuid->info uuid))]
                       {:entity entity-name :field field-name}))
        renamed (vec (for [entity-name (ds/entities schema)
                           [field-name field-spec] (ds/entity-fields schema entity-name)
                           :let [uuid (:uuid field-spec)
                                 old-info (get old-uuid->info uuid)]
                           :when (and old-info (not= (:field old-info) field-name))]
                       {:entity entity-name
                        :old-field (:field old-info)
                        :new-field field-name}))]
    {:created created :renamed renamed}))


(defn- compute-enum-changes
  "Computes created and renamed enums."
  [old-metadata schema]
  (let [old-uuid->name (:enums old-metadata)
        enums-data (ds/enums schema)
        created (vec (for [[enum-name {:keys [uuid]}] enums-data
                           :when (not (contains? old-uuid->name uuid))]
                       enum-name))
        renamed (into {}
                      (for [[enum-name {:keys [uuid]}] enums-data
                            :let [old-name (get old-uuid->name uuid)]
                            :when (and old-name (not= old-name enum-name))]
                        [old-name enum-name]))]
    {:created created :renamed renamed}))


(defn- compute-enum-value-changes
  "Computes created enum values."
  [old-metadata schema]
  (let [old-uuid->info (:enum-values old-metadata)
        enums-data (ds/enums schema)
        created (vec (for [[enum-name {:keys [values]}] enums-data
                           [value-kw value-uuid] values
                           :when (not (contains? old-uuid->info value-uuid))]
                       {:enum enum-name :value value-kw}))]
    {:created created}))


(defn- migrate-data
  "Migrates existing data when entities/fields are renamed."
  [old-data old-metadata schema]
  (let [entity-uuid->old-name (:entities old-metadata)
        entity-uuid->new-name (into {}
                                    (for [entity-name (ds/entities schema)]
                                      [(ds/entity-uuid schema entity-name) entity-name]))
        field-uuid->old-info (:fields old-metadata)
        ;; Build field renames per entity
        field-renames (into {}
                            (for [entity-name (ds/entities schema)
                                  :let [entity-uuid (ds/entity-uuid schema entity-name)
                                        old-entity-name (get entity-uuid->old-name entity-uuid)]
                                  :when old-entity-name]
                              [entity-uuid
                               (into {}
                                     (for [[field-name field-spec] (ds/entity-fields schema entity-name)
                                           :let [field-uuid (:uuid field-spec)
                                                 old-info (get field-uuid->old-info field-uuid)]
                                           :when (and old-info (not= (:field old-info) field-name))]
                                       [(:field old-info) field-name]))]))]
    (into {}
          (for [[entity-uuid entity-new-name] entity-uuid->new-name
                :let [old-entity-name (get entity-uuid->old-name entity-uuid)
                      entity-data (get old-data old-entity-name)]
                :when entity-data]
            [entity-new-name
             (let [renames (get field-renames entity-uuid {})]
               (if (empty? renames)
                 entity-data
                 (into {}
                       (for [[row-id row] entity-data]
                         [row-id
                          (into {}
                                (for [[k v] row]
                                  [(get renames k k) v]))]))))]))))


(defn- do-initialize
  "Performs initialization, returns [new-state changes]."
  [state schema]
  (let [old-state @state
        old-metadata (:metadata old-state)]
    (if (nil? old-metadata)
      ;; First-time initialization
      (let [new-metadata (build-metadata-from-schema schema)
            new-entities (build-entities-structure schema)
            new-enums (build-enums-structure schema)
            new-state {:entities new-entities
                       :enums new-enums
                       :metadata new-metadata
                       :data {}}
            changes {:entities {:created (vec (ds/entities schema)) :renamed {}}
                     :fields {:created (vec (for [e (ds/entities schema)
                                                  [f _] (ds/entity-fields schema e)]
                                              {:entity e :field f}))
                              :renamed []}
                     :enums {:created (vec (keys (ds/enums schema))) :renamed {}}
                     :enum-values {:created (vec (for [[enum-name {:keys [values]}] (ds/enums schema)
                                                       [v _] values]
                                                   {:enum enum-name :value v}))}}]
        [new-state changes])
      ;; Migration
      (do
        ;; Check for destructive changes
        (check-removed-entities old-metadata schema)
        (check-removed-fields old-metadata schema)
        (check-removed-enums old-metadata schema)
        (check-removed-enum-values old-metadata schema)
        (check-type-changes old-state old-metadata schema)
        ;; Compute changes
        (let [entity-changes (compute-entity-changes old-metadata schema)
              field-changes (compute-field-changes old-metadata schema)
              enum-changes (compute-enum-changes old-metadata schema)
              enum-value-changes (compute-enum-value-changes old-metadata schema)
              ;; Build new state
              new-metadata (build-metadata-from-schema schema)
              new-entities (build-entities-structure schema)
              new-enums (build-enums-structure schema)
              new-data (migrate-data (:data old-state) old-metadata schema)
              new-state {:entities new-entities
                         :enums new-enums
                         :metadata new-metadata
                         :data new-data}
              changes {:entities entity-changes
                       :fields field-changes
                       :enums enum-changes
                       :enum-values enum-value-changes}]
          [new-state changes])))))


;; === Storage record ===

(defrecord MemoryStorage
  [state]

  sp/Storage

  (initialize
    [_this schema]
    (let [[new-state changes] (do-initialize state schema)]
      (reset! state new-state)
      changes))


  (close
    [_this]
    (reset! state {:entities {}
                   :enums {}
                   :metadata nil
                   :data {}})
    nil)


  sp/StorageIntrospection

  (current-entities
    [_this]
    (set (keys (:entities @state))))


  (current-fields
    [_this entity-name]
    (get-in @state [:entities entity-name :fields]))


  (current-enums
    [_this]
    (set (keys (:enums @state))))


  (current-enum-values
    [_this enum-name]
    (get-in @state [:enums enum-name :values]))


  (schema-metadata
    [_this]
    (:metadata @state)))


(defn create-storage
  "Creates a new in-memory storage instance."
  []
  (->MemoryStorage (atom {:entities {}
                          :enums {}
                          :metadata nil
                          :data {}})))
