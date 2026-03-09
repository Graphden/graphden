(ns graphden.storage.postgres.crud
  "CRUD operations for PostgreSQL storage.
   Generic entity operations for create, read, update, delete, query."
  (:require
    [clojure.tools.logging :as log]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.errors :as errors]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


(defn- entity->row
  [entity fields]
  (codec/encode-row entity fields))


(defn create-entity
  "Creates a new entity record in the database.
   Returns the created record with generated id if not provided.
   Validates required fields if fields metadata is provided.
   Throws with :unique-violation type if unique constraint violated.
   Throws with :invalid-data type if data is not a map."
  [ds entity-name data fields]
  (sp/standard-crud-validations! entity-name data fields)
  (let [table-name (keyword (util/kw->snake-case entity-name))
        id (or (:id data) (random-uuid))
        record (assoc data :id id)
        row (entity->row record fields)
        columns (keys row)
        values (vals row)
        query (sql/format {:insert-into table-name
                           :columns columns
                           :values [values]
                           :returning [:*]}
                          {:quoted true})]
    (util/with-sql-error-handling "Database error" :create-entity {:entity-name entity-name :id id}
                                  (-> (jdbc/execute-one! ds query (util/query-opts))
                                      codec/row->entity))))


(defn read-entity
  "Reads an entity by id. Returns nil if not found.
   Throws with :table-not-found if entity table doesn't exist."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:select [:*]
                           :from [table-name]
                           :where [:= :id id]}
                          {:quoted true})]
    (util/with-sql-error-handling "Database error" :read-entity {:entity-name entity-name :id id}
                                  (-> (jdbc/execute-one! ds query (util/query-opts))
                                      codec/row->entity))))


(defn update-entity
  "Updates an entity by id. Returns the updated record.
   Throws :not-found if entity doesn't exist.
   Throws :unique-violation if update violates unique constraint.
   Validates required fields if fields metadata is provided."
  [ds entity-name id data fields]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        existing (read-entity ds entity-name id)]
    (when-not existing
      (throw (ex-info "Entity not found"
                      {:type :not-found
                       :entity entity-name
                       :id id})))
    (let [updated (merge existing data {:id id})]
      (when fields
        (sp/validate-required-fields! entity-name fields updated))
      (let [row (entity->row (dissoc updated :id) fields)
            query (sql/format {:update table-name
                               :set row
                               :where [:= :id id]
                               :returning [:*]}
                              {:quoted true})]
        (util/with-sql-error-handling "Database error" :update-entity {:entity-name entity-name :id id}
                                      (-> (jdbc/execute-one! ds query (util/query-opts))
                                          codec/row->entity))))))


(defn delete-entity
  "Deletes an entity by id. Returns true if entity existed and was deleted.
   Throws :foreign-key-violation if entity is referenced by other records."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:delete-from table-name
                           :where [:= :id id]}
                          {:quoted true})]
    (util/with-sql-error-handling "Database error" :delete-entity {:entity-name entity-name :id id}
                                  (pos? (:next.jdbc/update-count
                                          (jdbc/execute-one! ds query (util/query-opts)))))))


(defn query-entities
  "Queries entities by conditions.
   where is a map of field->value for equality matching.
   Supports nil values (generates IS NULL instead of = NULL).
   Returns a sequence of matching entities.
   Throws :table-not-found if entity table doesn't exist.
   Throws :invalid-where-clause if where is not nil or a map.

   Note: Empty where clause ({} or nil) returns all entities (full table scan).
   This is logged at DEBUG level to help identify unintended full scans."
  [ds entity-name where fields]
  (sp/standard-query-validations! entity-name fields where)
  (let [table-name (keyword (util/kw->snake-case entity-name))
        where-clause (when (seq where)
                       (into [:and]
                             (map (fn [[k v]]
                                    (let [col (keyword (util/kw->snake-case k))
                                          field-spec (get fields k)
                                          encoded-v (codec/encode-value v field-spec)]
                                      (if (nil? encoded-v)
                                        [:is col nil]
                                        [:= col encoded-v])))
                                  where)))
        query (sql/format (cond-> {:select [:*]
                                   :from [table-name]}
                            where-clause (assoc :where where-clause))
                          {:quoted true})]
    (when-not where-clause
      (log/debug "Full table scan query (no where clause)" {:entity-name entity-name}))
    (util/with-sql-error-handling "Database error" :query-entities {:entity-name entity-name :where where}
                                  (let [rows (jdbc/execute! ds query (util/query-opts))]
                                    (map codec/row->entity rows)))))


;; === Batch CRUD operations ===

(defn create-entities
  "Creates multiple entity records in a single transaction.
   Returns a sequence of created records with generated ids.
   Throws :unique-violation if any unique constraint violated.
   Throws :duplicate-ids if duplicate IDs found in batch.
   Throws :batch-error/batch-too-large if batch exceeds *max-batch-size*.

   Note: PostgreSQL batch INSERT uses a single statement, so on failure
   the exact failing record index is unknown. Error context includes
   batch-size and all record IDs for debugging."
  [ds entity-name data-seq fields]
  (if (empty? data-seq)
    []
    (do
      (sp/validate-batch-size! (count data-seq) :create-entities {:entity-name entity-name})
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [table-name (keyword (util/kw->snake-case entity-name))
            ;; Prepare all records with IDs
            records (vec (map (fn [data]
                                (when fields
                                  (sp/validate-required-fields! entity-name fields data))
                                (let [id (or (:id data) (random-uuid))]
                                  (assoc data :id id)))
                              data-seq))
            batch-size (count records)
            batch-ids (mapv :id records)
            ;; Convert to rows using codec
            rows (map #(entity->row % fields) records)
            ;; Get consistent column order from first row
            columns (vec (keys (first rows)))
            ;; Extract values in column order
            values (vec (map (fn [row]
                               (mapv #(get row %) columns))
                             rows))
            query (sql/format {:insert-into table-name
                               :columns columns
                               :values values
                               :returning [:*]}
                              {:quoted true})
            result-rows (try
                          (jdbc/execute! ds query (util/query-opts))
                          (catch java.sql.SQLException e
                            ;; Wrap SQL exceptions with proper classification and batch context
                            ;; Index is -1 because PostgreSQL batch INSERT doesn't reveal which row failed
                            (let [wrapped (errors/wrap-sql-error e "Database error" :create-entities
                                                                 {:entity-name entity-name
                                                                  :batch-ids batch-ids})]
                              (throw (sp/wrap-batch-error wrapped -1 batch-size nil))))
                          (catch Exception e
                            ;; Non-SQL exceptions (rare)
                            (throw (sp/wrap-batch-error e -1 batch-size nil))))
            expected-count batch-size
            actual-count (count result-rows)]
        ;; Validate that all records were inserted
        (when (not= expected-count actual-count)
          (throw (ex-info "Batch insert returned unexpected number of records"
                          {:type :batch-insert-mismatch
                           :entity-name entity-name
                           :expected-count expected-count
                           :actual-count actual-count})))
        (map codec/row->entity result-rows)))))


(defn read-entities
  "Reads multiple entities by ids. Returns {id -> record} for found records.
   Throws :table-not-found if entity table doesn't exist."
  [ds entity-name ids]
  (if (empty? ids)
    {}
    (let [table-name (keyword (util/kw->snake-case entity-name))
          query (sql/format {:select [:*]
                             :from [table-name]
                             :where [:in :id (vec ids)]}
                            {:quoted true})]
      (util/with-sql-error-handling "Database error" :read-entities {:entity-name entity-name :count (count ids)}
                                    (let [rows (jdbc/execute! ds query (util/query-opts))]
                                      (->> rows
                                           (map codec/row->entity)
                                           (map (juxt :id identity))
                                           (into {})))))))


(defn update-entities
  "Updates multiple entity records in a single batch.
   Each record must have :id. Returns seq of updated records.
   Uses PostgreSQL UPDATE ... FROM VALUES for efficient batch update.
   Throws :not-found if any entity doesn't exist."
  [ds entity-name data-seq fields]
  (if (empty? data-seq)
    []
    (do
      (sp/validate-batch-size! (count data-seq) :update-entities {:entity-name entity-name})
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [table-name (keyword (util/kw->snake-case entity-name))
            ;; Validate all records have :id
            _ (doseq [data data-seq]
                (when-not (:id data)
                  (throw (ex-info "Each record must have :id for batch update"
                                  {:type :invalid-data
                                   :entity-name entity-name
                                   :data (sp/redact-sensitive-map data)}))))
            records (vec data-seq)
            batch-size (count records)
            ;; Convert to rows
            rows (map #(entity->row % fields) records)
            ;; Get consistent column order from first row
            columns (vec (keys (first rows)))
            ;; Build individual UPDATE statements and execute in transaction
            results (try
                      (jdbc/with-transaction [tx ds]
                        (doall
                          (map (fn [record]
                                 (let [row (entity->row (dissoc record :id) fields)
                                       query (sql/format {:update table-name
                                                          :set row
                                                          :where [:= :id (:id record)]
                                                          :returning [:*]}
                                                         {:quoted true})
                                       result (jdbc/execute-one! tx query (util/query-opts))]
                                   (when-not result
                                     (throw (ex-info "Entity not found"
                                                     {:type :not-found
                                                      :entity entity-name
                                                      :id (:id record)})))
                                   result))
                               records)))
                      (catch java.sql.SQLException e
                        (let [wrapped (errors/wrap-sql-error e "Database error" :update-entities
                                                             {:entity-name entity-name})]
                          (throw (sp/wrap-batch-error wrapped -1 batch-size nil)))))]
        (map codec/row->entity results)))))


(defn upsert-entities
  "Inserts or updates multiple entity records using INSERT ... ON CONFLICT DO UPDATE.
   Each record must have :id. Returns seq of upserted records.
   Uses single SQL statement for efficiency."
  [ds entity-name data-seq fields]
  (if (empty? data-seq)
    []
    (do
      (sp/validate-batch-size! (count data-seq) :upsert-entities {:entity-name entity-name})
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [table-name (keyword (util/kw->snake-case entity-name))
            ;; Validate all records have :id
            _ (doseq [data data-seq]
                (when-not (:id data)
                  (throw (ex-info "Each record must have :id for upsert"
                                  {:type :invalid-data
                                   :entity-name entity-name
                                   :data (sp/redact-sensitive-map data)}))))
            records (vec data-seq)
            batch-size (count records)
            batch-ids (mapv :id records)
            ;; Convert to rows using codec
            rows (map #(entity->row % fields) records)
            ;; Get consistent column order from first row
            columns (vec (keys (first rows)))
            ;; Extract values in column order
            values (vec (map (fn [row]
                               (mapv #(get row %) columns))
                             rows))
            ;; Build ON CONFLICT DO UPDATE SET for all columns except :id
            ;; HoneySQL auto-generates SET col = EXCLUDED.col when given a vector
            update-columns (vec (remove #{:id} columns))
            query (sql/format {:insert-into table-name
                               :columns columns
                               :values values
                               :on-conflict [:id]
                               :do-update-set update-columns
                               :returning [:*]}
                              {:quoted true})
            result-rows (try
                          (jdbc/execute! ds query (util/query-opts))
                          (catch java.sql.SQLException e
                            (let [wrapped (errors/wrap-sql-error e "Database error" :upsert-entities
                                                                 {:entity-name entity-name
                                                                  :batch-ids batch-ids})]
                              (throw (sp/wrap-batch-error wrapped -1 batch-size nil))))
                          (catch Exception e
                            (throw (sp/wrap-batch-error e -1 batch-size nil))))
            expected-count batch-size
            actual-count (count result-rows)]
        ;; Validate that all records were upserted
        (when (not= expected-count actual-count)
          (throw (ex-info "Batch upsert returned unexpected number of records"
                          {:type :batch-upsert-mismatch
                           :entity-name entity-name
                           :expected-count expected-count
                           :actual-count actual-count})))
        (map codec/row->entity result-rows)))))


(defn delete-entities
  "Deletes multiple entities by ids. Returns count of deleted records.
   Throws :foreign-key-violation if any entity is referenced."
  [ds entity-name ids]
  (if (empty? ids)
    0
    (let [table-name (keyword (util/kw->snake-case entity-name))
          query (sql/format {:delete-from table-name
                             :where [:in :id (vec ids)]}
                            {:quoted true})]
      (util/with-sql-error-handling "Database error" :delete-entities {:entity-name entity-name :count (count ids)}
                                    (:next.jdbc/update-count
                                      (jdbc/execute-one! ds query (util/query-opts)))))))
