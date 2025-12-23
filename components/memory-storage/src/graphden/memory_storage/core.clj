(ns graphden.memory-storage.core
  "In-memory implementation of Storage protocol."
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.storage-protocol.interface :as sp]))


;; === Internal helpers ===


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


(defn- check-single-field-change
  "Checks a single field for type/nullable changes. Throws on unsafe change."
  [entity-name field-name field-spec old-fields old-metadata]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (when old-field-info
      (let [old-field-name (:field old-field-info)
            old-type (get-in old-fields [old-field-name :type])
            new-type (:type field-spec)
            old-nullable? (get-in old-fields [old-field-name :nullable?])
            new-nullable? (get field-spec :nullable? false)]
        (sp/check-type-change! entity-name field-name old-type new-type)
        (sp/check-nullable-change! entity-name field-name old-nullable? new-nullable?)))))


(defn- check-entity-type-changes
  "Checks all field type changes for a single entity. Throws on unsafe change."
  [entity-name old-state old-metadata schema]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)
        old-fields (get-in old-state [:entities old-entity-name :fields])]
    (when old-fields
      (run! (fn [[field-name field-spec]]
              (check-single-field-change entity-name field-name field-spec old-fields old-metadata))
            (ds/entity-fields schema entity-name)))))


(defn- check-type-changes
  "Checks that all field type changes are safe. Throws on unsafe change."
  [old-state old-metadata schema]
  (run! #(check-entity-type-changes % old-state old-metadata schema)
        (ds/entities schema)))


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
      (let [new-metadata (sp/build-metadata-from-schema schema)
            new-entities (build-entities-structure schema)
            new-enums (build-enums-structure schema)
            new-state {:entities new-entities
                       :enums new-enums
                       :metadata new-metadata
                       :data {}}
            changes (sp/build-first-init-changes schema)]
        [new-state changes])
      ;; Migration
      (do
        ;; Check for destructive changes
        (sp/check-all-removals! old-metadata schema)
        (check-type-changes old-state old-metadata schema)
        ;; Compute changes
        (let [entity-changes (sp/compute-entity-changes old-metadata schema)
              field-changes (sp/compute-field-changes old-metadata schema)
              enum-changes (sp/compute-enum-changes old-metadata schema)
              enum-value-changes (sp/compute-enum-value-changes old-metadata schema)
              ;; Build new state
              new-metadata (sp/build-metadata-from-schema schema)
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
