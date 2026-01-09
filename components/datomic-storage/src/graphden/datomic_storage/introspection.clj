(ns graphden.datomic-storage.introspection
  "Datomic introspection helpers.

   Provides functions for querying database schema and metadata:
   - current-attrs: user-defined attributes
   - current-enum-values-db: enum value idents
   - metadata-schema-exists?: check if metadata schema exists
   - read-metadata: read all metadata entities"
  (:require
    [clojure.string :as str]
    [datomic.client.api :as d]))


(defn current-attrs
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


(defn current-enum-values-db
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


(defn metadata-schema-exists?
  "Checks if metadata schema attributes exist in the database.
   Returns false if the attribute doesn't exist.
   Re-throws unexpected exceptions (connection errors, etc.)."
  [db]
  (try
    (let [result (d/q '[:find ?e
                        :where [?e :db/ident :graphden.metadata/uuid]]
                      db)]
      (seq result))
    (catch clojure.lang.ExceptionInfo e
      ;; Datomic throws ExceptionInfo for various errors
      ;; Only suppress "not-an-entity" errors (attribute doesn't exist)
      (let [data (ex-data e)]
        (if (= (:db/error data) :db.error/not-an-entity)
          false
          (throw e))))))


(defn read-metadata
  "Reads metadata entities from the database.
   Returns map with :entities, :fields, :enums, :enum-values keys or nil if no metadata."
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
          ;; Query fields with enum-name (optional attribute, only for enum fields)
          fields-enum-names (d/q '[:find ?uuid ?enum-name
                                   :where
                                   [?e :graphden.metadata/uuid ?uuid]
                                   [?e :graphden.metadata/kind :field]
                                   [?e :graphden.metadata/field-enum-name ?enum-name]]
                                 db)
          field-uuid->enum-name (into {} fields-enum-names)
          ;; Query fields with ref-entity (optional attribute, only for ref fields)
          fields-ref-entities (d/q '[:find ?uuid ?ref-entity
                                     :where
                                     [?e :graphden.metadata/uuid ?uuid]
                                     [?e :graphden.metadata/kind :field]
                                     [?e :graphden.metadata/field-ref-entity ?ref-entity]]
                                   db)
          field-uuid->ref-entity (into {} fields-ref-entities)
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
                           [uuid (cond-> {:entity (get entity-uuid->name parent-uuid)
                                          :field field-name
                                          :type field-type
                                          :nullable? field-nullable}
                                   ;; Include enum-name if present
                                   (get field-uuid->enum-name uuid)
                                   (assoc :enum-name (get field-uuid->enum-name uuid))
                                   ;; Include ref-entity if present
                                   (get field-uuid->ref-entity uuid)
                                   (assoc :ref-entity (get field-uuid->ref-entity uuid)))]))
           :enums enum-uuid->name
           :enum-values (into {}
                              (for [[uuid value-name parent-uuid] enum-values]
                                [uuid {:enum (get enum-uuid->name parent-uuid)
                                       :value value-name}]))})))))
