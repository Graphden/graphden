(ns graphden.datomic-storage.crud
  "Datomic CRUD and batch operations helpers.

   Provides implementation functions for:
   - Single entity CRUD (create, read, update, delete, query)
   - Batch operations (create-entities, read-entities, delete-entities)"
  (:require
    [datomic.client.api :as d]
    [graphden.datomic-storage.introspection :as introspection]
    [graphden.datomic-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]))


;; === CRUD helpers ===

(defn- convert-field-value
  "Converts field values for Datomic storage:
   - Enum values (keywords) -> entity idents
   - Ref values (UUIDs) -> lookup refs
   Returns the original value for other field types."
  [field-specs field-name v]
  (let [field-spec (get field-specs field-name)]
    (case (:type field-spec)
      :enum (if (keyword? v)
              (util/enum-value-ident (:enum-name field-spec) v)
              v)
      :ref (if (uuid? v)
             ;; Convert UUID to lookup ref for the referenced entity type
             (let [ref-entity (:ref-entity field-spec)]
               [(util/entity-attr ref-entity :id) v])
             v)
      ;; Default: return as-is
      v)))


(defn- entity->tx
  "Converts entity map to Datomic transaction data.
   Uses namespaced attributes for the entity type.
   The :id field is stored as :entity-name/id (UUID).
   Enum fields are converted to entity idents.
   Ref fields are converted to lookup refs.
   Type hints for hot-path performance (called during batch operations)."
  ^clojure.lang.IPersistentMap [entity-name ^clojure.lang.IPersistentMap data id temp-id ^clojure.lang.IPersistentMap field-specs]
  (let [base-tx {:db/id temp-id
                 (util/entity-attr entity-name :id) id}]
    (reduce-kv (fn [acc k v]
                 (if (= k :id)
                   acc  ; Already handled above
                   (let [converted-v (convert-field-value field-specs k v)]
                     (assoc acc (util/entity-attr entity-name k) converted-v))))
               base-tx
               data)))


(defn- pull-entity
  "Pulls an entity by id (UUID) from the database.
   Queries by :entity-name/id attribute."
  [db entity-name id entity-fields]
  (let [id-attr (util/entity-attr entity-name :id)
        ;; Include :id in pattern along with other fields
        pattern (into [id-attr]
                      (map #(util/entity-attr entity-name %) entity-fields))
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


(defn get-entity-fields
  "Gets field names for an entity from metadata."
  [db entity-name]
  (let [metadata (introspection/read-metadata db)]
    (->> (:fields metadata)
         (vals)
         (filter #(= (:entity %) entity-name))
         (map :field))))


(defn get-fields-with-specs
  "Gets field specifications for an entity from metadata.
   Returns {field-name {:type ... :nullable? ... :enum-name ... :ref-entity ...}}."
  [db entity-name]
  (let [metadata (introspection/read-metadata db)]
    (->> (:fields metadata)
         (vals)
         (filter #(= (:entity %) entity-name))
         (map (fn [{:keys [field nullable? enum-name ref-entity] field-type :type}]
                [field (cond-> {:type field-type :nullable? nullable?}
                         enum-name (assoc :enum-name enum-name)
                         ref-entity (assoc :ref-entity ref-entity))]))
         (into {}))))


(defn create-entity-impl
  "Creates a new entity in Datomic.
   Validates required fields before creating."
  [conn entity-name data]
  (let [db (d/db conn)
        field-specs (get-fields-with-specs db entity-name)]
    (when (seq field-specs)
      (sp/validate-required-fields! entity-name field-specs data))
    (let [id (or (:id data) (random-uuid))
          temp-id (str "new-entity-" (random-uuid))
          tx-data [(entity->tx entity-name (assoc data :id id) id temp-id field-specs)]]
      (d/transact conn {:tx-data tx-data})
      (let [new-db (d/db conn)
            fields (get-entity-fields new-db entity-name)]
        (pull-entity new-db entity-name id fields)))))


(defn read-entity-impl
  "Reads an entity by id."
  [conn entity-name id]
  (let [db (d/db conn)
        fields (get-entity-fields db entity-name)]
    (pull-entity db entity-name id fields)))


(defn update-entity-impl
  "Updates an entity by id.
   Validates required fields after merging."
  [conn entity-name id data]
  (let [db (d/db conn)
        fields (get-entity-fields db entity-name)
        field-specs (get-fields-with-specs db entity-name)
        id-attr (util/entity-attr entity-name :id)
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
          updated (merge existing data {:id id})]
      (when (seq field-specs)
        (sp/validate-required-fields! entity-name field-specs updated))
      ;; Use actual entity id for update
      (let [tx-data [(entity->tx entity-name updated id eid field-specs)]]
        (d/transact conn {:tx-data tx-data})
        (let [new-db (d/db conn)]
          (pull-entity new-db entity-name id fields))))))


(defn delete-entity-impl
  "Deletes an entity by id."
  [conn entity-name id]
  (let [db (d/db conn)
        id-attr (util/entity-attr entity-name :id)
        eid (ffirst (d/q {:find '[?e]
                          :in '[$ ?id]
                          :where [['?e id-attr '?id]]}
                         db id))]
    (if eid
      (do
        (d/transact conn {:tx-data [[:db/retractEntity eid]]})
        true)
      false)))


(defn query-entities-impl
  "Queries entities by conditions.
   where must be nil or a map of field->value for equality matching."
  [conn entity-name where]
  (sp/validate-where-clause! where)
  (let [db (d/db conn)
        fields (get-entity-fields db entity-name)
        field-specs (zipmap fields (repeat {:type :any}))  ; Datomic fields for validation
        _ (sp/validate-where-clause-fields! entity-name field-specs where)
        id-attr (util/entity-attr entity-name :id)
        pattern (into [id-attr] (map #(util/entity-attr entity-name %) fields))
        ;; Build where clauses - must have at least one clause to identify entities of this type
        base-where [['?e id-attr '_]]  ; Match entities that have an :id attribute
        where-clauses (if (empty? where)
                        base-where
                        (into base-where
                              (map (fn [[k v]]
                                     ['?e (util/entity-attr entity-name k) v])
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


;; === Batch CRUD helpers ===

(defn create-entities-impl
  "Creates multiple entities in a single transaction.
   Throws :duplicate-ids if duplicate IDs found in batch.
   Includes batch context in errors for debugging."
  [conn entity-name data-seq]
  (if (empty? data-seq)
    []
    (do
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [db (d/db conn)
            field-specs (get-fields-with-specs db entity-name)
            total-count (count data-seq)
            ;; Validate all records first with batch context
            _ (when (seq field-specs)
                (doseq [[idx data] (map-indexed vector data-seq)]
                  (try
                    (sp/validate-required-fields! entity-name field-specs data)
                    (catch clojure.lang.ExceptionInfo e
                      (throw (sp/wrap-batch-error e idx total-count (:id data)))))))
            ;; Prepare transaction data
            records (mapv (fn [data]
                            (let [id (or (:id data) (random-uuid))
                                  temp-id (str "new-entity-" (random-uuid))]
                              {:id id
                               :temp-id temp-id
                               :data (assoc data :id id)}))
                          data-seq)
            tx-data (mapv (fn [{:keys [id temp-id data]}]
                            (entity->tx entity-name data id temp-id field-specs))
                          records)]
        ;; Wrap transaction in error handling
        (try
          (d/transact conn {:tx-data tx-data})
          (catch Exception e
            (throw (ex-info "Batch create failed"
                            {:type :batch-create-failed
                             :entity-name entity-name
                             :batch-size total-count
                             :record-ids (mapv :id records)}
                            e))))
        ;; Read back created entities
        (let [new-db (d/db conn)
              fields (get-entity-fields new-db entity-name)
              ids (mapv :id records)
              results (keep (fn [id] (pull-entity new-db entity-name id fields)) ids)
              expected-count total-count
              actual-count (count results)]
          ;; Validate that all records were created
          (when (not= expected-count actual-count)
            (throw (ex-info "Batch insert returned unexpected number of records"
                            {:type :batch-insert-mismatch
                             :entity-name entity-name
                             :expected-count expected-count
                             :actual-count actual-count})))
          results)))))


(defn read-entities-impl
  "Reads multiple entities by ids. Returns {id -> record}."
  [conn entity-name ids]
  (if (empty? ids)
    {}
    (let [db (d/db conn)
          fields (get-entity-fields db entity-name)
          id-attr (util/entity-attr entity-name :id)
          pattern (into [id-attr] (map #(util/entity-attr entity-name %) fields))
          ;; Find all entity ids in one query
          results (d/q {:find '[?e ?id]
                        :in '[$ [?id ...]]
                        :where [['?e id-attr '?id]]}
                       db (vec ids))]
      (->> results
           (map (fn [[eid id]]
                  (let [result (d/pull db pattern eid)]
                    [id (reduce-kv (fn [acc k v]
                                     (let [field-name (keyword (name k))]
                                       (assoc acc field-name v)))
                                   {}
                                   result)])))
           (into {})))))


(defn delete-entities-impl
  "Deletes multiple entities by ids. Returns count of deleted.
   Includes batch context in errors for debugging."
  [conn entity-name ids]
  (if (empty? ids)
    0
    (let [db (d/db conn)
          id-attr (util/entity-attr entity-name :id)
          ;; Find all entity ids in one query
          results (d/q {:find '[?e]
                        :in '[$ [?id ...]]
                        :where [['?e id-attr '?id]]}
                       db (vec ids))
          entity-ids (mapv first results)]
      (when (seq entity-ids)
        (try
          (d/transact conn {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) entity-ids)})
          (catch Exception e
            (throw (ex-info "Batch delete failed"
                            {:type :batch-delete-failed
                             :entity-name entity-name
                             :batch-size (count ids)
                             :deleted-count (count entity-ids)}
                            e)))))
      (count entity-ids))))
