(ns graphden.memory-storage.migration
  "Memory storage migration helpers.

   Provides functions for:
   - Validating type/nullable changes during schema migration
   - Renaming fields and entities in existing data
   - Building metadata structures from schema"
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.storage-protocol.interface :as sp]))


;; === Schema structure builders ===

(defn build-entities-structure
  "Builds :entities structure from DataSchema."
  [schema]
  (into {}
        (for [entity-name (ds/entities schema)]
          [entity-name
           {:fields (into {}
                          (for [[field-name field-spec] (ds/entity-fields schema entity-name)]
                            [field-name {:type (:type field-spec)
                                         :nullable? (get field-spec :nullable? false)}]))
            :constraints (ds/entity-constraints schema entity-name)}])))


(defn build-enums-structure
  "Builds :enums structure from DataSchema."
  [schema]
  (into {}
        (for [[enum-name {:keys [values]}] (ds/enums schema)]
          [enum-name {:values (set (keys values))}])))


;; === Type change validation ===

(defn- validate-single-field-change!
  "Validates a single field for type/nullable changes. Throws on unsafe change."
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


(defn- validate-entity-type-changes!
  "Validates all field type changes for a single entity. Throws on unsafe change."
  [entity-name old-state old-metadata schema]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)
        old-fields (get-in old-state [:entities old-entity-name :fields])]
    (when old-fields
      (run! (fn [[field-name field-spec]]
              (validate-single-field-change! entity-name field-name field-spec old-fields old-metadata))
            (ds/entity-fields schema entity-name)))))


(defn validate-type-changes!
  "Validates that all field type changes are safe. Throws on unsafe change."
  [old-state old-metadata schema]
  (run! #(validate-entity-type-changes! % old-state old-metadata schema)
        (ds/entities schema)))


;; === Data migration (renaming) ===

(defn- rename-row-fields
  "Renames fields in a single row using the renames map.
   Type hints for hot-path performance (JIT optimization)."
  ^clojure.lang.IPersistentMap [^clojure.lang.IPersistentMap renames ^clojure.lang.IPersistentMap row]
  (persistent!
    (reduce-kv (fn [acc k v]
                 (assoc! acc (get renames k k) v))
               (transient {})
               row)))


(defn- rename-entity-rows
  "Renames fields in all rows of an entity."
  [renames entity-data]
  (persistent!
    (reduce-kv (fn [acc row-id row]
                 (assoc! acc row-id (rename-row-fields renames row)))
               (transient {})
               entity-data)))


(defn- build-entity-uuid-mapping
  "Builds mapping from entity UUID to new entity name from schema."
  [schema]
  (into {}
        (map (fn [entity-name]
               [(ds/entity-uuid schema entity-name) entity-name]))
        (ds/entities schema)))


(defn- compute-field-renames-for-entity
  "Computes field renames for a single entity.
   Returns map of {old-field-name -> new-field-name} for renamed fields."
  [schema entity-name field-uuid->old-info]
  (reduce (fn [acc [field-name field-spec]]
            (let [field-uuid (:uuid field-spec)
                  old-info (get field-uuid->old-info field-uuid)]
              (if (and old-info (not= (:field old-info) field-name))
                (assoc acc (:field old-info) field-name)
                acc)))
          {}
          (ds/entity-fields schema entity-name)))


(defn- build-all-field-renames
  "Builds field renames for all entities.
   Returns map of {entity-uuid -> {old-field-name -> new-field-name}}."
  [schema entity-uuid->old-name field-uuid->old-info]
  (reduce (fn [acc entity-name]
            (let [entity-uuid (ds/entity-uuid schema entity-name)
                  old-entity-name (get entity-uuid->old-name entity-uuid)]
              (if-not old-entity-name
                acc
                (let [renames (compute-field-renames-for-entity
                                schema entity-name field-uuid->old-info)]
                  (assoc acc entity-uuid renames)))))
          {}
          (ds/entities schema)))


(defn migrate-data
  "Migrates existing data when entities/fields are renamed.
   Uses transients for O(n) performance instead of O(n²)."
  [old-data old-metadata schema]
  (let [entity-uuid->old-name (:entities old-metadata)
        entity-uuid->new-name (build-entity-uuid-mapping schema)
        field-renames (build-all-field-renames
                        schema entity-uuid->old-name (:fields old-metadata))]
    (reduce-kv (fn [acc entity-uuid entity-new-name]
                 (let [old-entity-name (get entity-uuid->old-name entity-uuid)
                       entity-data (get old-data old-entity-name)]
                   (if-not entity-data
                     acc
                     (let [renames (get field-renames entity-uuid {})]
                       (assoc acc entity-new-name
                              (if (empty? renames)
                                entity-data
                                (rename-entity-rows renames entity-data)))))))
               {}
               entity-uuid->new-name)))
