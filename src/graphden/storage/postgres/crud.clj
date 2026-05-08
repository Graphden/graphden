(ns graphden.storage.postgres.crud
  "CRUD operations for PostgreSQL storage.
   Generic entity operations for create, read, update, delete, query."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.errors :as errors]
    [graphden.storage.postgres.junction :as junction]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


(defn- entity->row
  [entity fields]
  (codec/encode-row entity fields))


(defn- split-ref-many-batch
  "Pull ref-many junction data off each record. Returns
   `[columnar-records ref-many-records]` (parallel vectors)."
  [records fields]
  (let [split (mapv #(junction/extract-ref-many-data % fields) records)]
    [(mapv first split) (mapv second split)]))


(defn- collect-batch-columns
  "Union of every key across `rows` — different records may carry
   different fields (e.g. some have `:name`, some don't). Using just
   the first row's keys would silently drop columns present only
   later."
  [rows]
  (vec (into #{} (mapcat keys) rows)))


(defn- batch-row-values
  "Extract `[v1 v2 …]` for every row, in `columns` order."
  [rows columns]
  (mapv (fn [row] (mapv #(get row %) columns)) rows))


(defn- merge-back-ref-many
  "Pair `result-rows` from JDBC back with the `ref-many-records` we
   stripped off before persistence, so the returned records reflect
   the full shape we wrote."
  [result-rows ref-many-records]
  (mapv (fn [row rm-data] (merge (codec/row->entity row) rm-data))
        result-rows
        ref-many-records))


(defn- write-junction-rows!
  "Write ref-many junction rows for every record in the batch in
   ONE INSERT per (entity, field). `replace?` controls whether the
   owner's existing rows are deleted first (update / upsert) or
   inserted fresh (create)."
  [ds entity-name batch-ids ref-many-records fields replace?]
  (when (junction/has-ref-many? fields)
    (let [pairs (filterv #(seq (second %))
                         (map vector batch-ids ref-many-records))]
      (when (seq pairs)
        (if replace?
          (junction/replace-ref-many-fields-batch! ds entity-name pairs fields)
          (junction/write-ref-many-fields-batch! ds entity-name pairs))))))


(defn create-entity
  "Creates a new entity record in the database.
   Returns the created record with generated id if not provided.
   Validates required fields if fields metadata is provided.
   Throws with :unique-violation type if unique constraint violated.
   Throws with :invalid-data type if data is not a map.

   For :ref-many fields: writes to junction tables after entity row is created."
  [ds entity-name data fields]
  (sp/standard-crud-validations! entity-name data fields)
  (let [table-name (keyword (util/kw->snake-case entity-name))
        id (or (:id data) (random-uuid))
        record (assoc data :id id)
        ;; Split off ref-many fields - they go into junction tables
        [columnar-data ref-many-data] (junction/extract-ref-many-data record fields)
        row (entity->row columnar-data fields)
        columns (keys row)
        values (vals row)
        query (sql/format {:insert-into table-name
                           :columns columns
                           :values [values]
                           :returning [:*]}
                          {:quoted true})]
    (util/with-sql-error-handling "Database error" :create-entity {:entity-name entity-name :id id}
                                  (let [created (-> (jdbc/execute-one! ds query (util/query-opts))
                                                    codec/row->entity)]
                                    (when (seq ref-many-data)
                                      (junction/write-ref-many-fields! ds entity-name (:id created) ref-many-data))
                                    ;; Merge back the ref-many fields so the returned record
                                    ;; reflects what was written.
                                    (merge created ref-many-data)))))


(defn read-entity
  "Reads an entity by id. Returns nil if not found.
   Throws with :table-not-found if entity table doesn't exist.

   For :ref-many fields: loads from junction tables and populates the field as
   a vector of UUIDs. Pass `fields` (the entity field specs) to enable this."
  ([ds entity-name id]
   (read-entity ds entity-name id nil))
  ([ds entity-name id fields]
   (let [table-name (keyword (util/kw->snake-case entity-name))
         query (sql/format {:select [:*]
                            :from [table-name]
                            :where [:= :id id]}
                           {:quoted true})]
     (util/with-sql-error-handling "Database error" :read-entity {:entity-name entity-name :id id}
                                   (when-let [row (jdbc/execute-one! ds query (util/query-opts))]
                                     (let [record (codec/row->entity row)]
                                       (if (and fields (junction/has-ref-many? fields))
                                         (first (junction/populate-ref-many-fields ds entity-name [record] fields))
                                         record)))))))


(defn update-entity
  "Updates an entity by id. Returns the updated record.
   Throws :not-found if entity doesn't exist.
   Throws :unique-violation if update violates unique constraint.
   Throws :constraint-violation/has-descendants if arg has descendants.
   Validates required fields if fields metadata is provided.

   For :ref-many fields: replaces junction rows (delete + insert)."
  [ds entity-name id data fields]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        existing (read-entity ds entity-name id fields)]
    (when-not existing
      (throw (ex-info "Entity not found"
                      {:type :not-found
                       :entity entity-name
                       :id id})))
    (let [updated (merge existing data {:id id})]
      (when fields
        (sp/validate-required-fields! entity-name fields updated))
      (let [[columnar-data ref-many-data] (junction/extract-ref-many-data updated fields)
            row (entity->row (dissoc columnar-data :id) fields)
            ;; If only ref-many fields changed, columnar row may be empty
            do-column-update? (seq row)
            query (when do-column-update?
                    (sql/format {:update table-name
                                 :set row
                                 :where [:= :id id]
                                 :returning [:*]}
                                {:quoted true}))]
        (util/with-sql-error-handling "Database error" :update-entity {:entity-name entity-name :id id}
                                      (let [updated-row (if do-column-update?
                                                          (-> (jdbc/execute-one! ds query (util/query-opts))
                                                              codec/row->entity)
                                                          (dissoc columnar-data nil))]
                                        ;; Replace junction rows for any ref-many fields actually present in the update
                                        (when (and (seq ref-many-data) fields)
                                          (junction/replace-ref-many-fields! ds entity-name id ref-many-data fields))
                                        (merge updated-row ref-many-data)))))))


(defn delete-entity
  "Deletes an entity by id. Returns true if entity existed and was deleted.
   Throws :foreign-key-violation if entity is referenced by other records.
   Throws :constraint-violation/has-descendants if arg has descendants."
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
                                      (cond
                                        (nil? encoded-v)
                                        [:is col nil]

                                        ;; Collection = IN clause (for batch lookups)
                                        (and (or (vector? v) (set? v) (seq? v))
                                             (not (map? v)))
                                        [:in col (vec (map #(codec/encode-value % field-spec) v))]

                                        :else
                                        [:= col encoded-v])))
                                  where)))
        query (sql/format (cond-> {:select [:*]
                                   :from [table-name]}
                            where-clause (assoc :where where-clause))
                          {:quoted true})]
    (when-not where-clause
      (log/debug "Full table scan query (no where clause)" {:entity-name entity-name}))
    (util/with-sql-error-handling "Database error" :query-entities {:entity-name entity-name :where where}
                                  (let [rows (jdbc/execute! ds query (util/query-opts))
                                        records (mapv #(codec/row->entity % fields) rows)]
                                    (if (and fields (junction/has-ref-many? fields))
                                      (junction/populate-ref-many-fields ds entity-name records fields)
                                      records)))))


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
            records (mapv (fn [data]
                            (when fields
                              (sp/validate-required-fields! entity-name fields data))
                            (let [id (or (:id data) (random-uuid))]
                              (assoc data :id id)))
                          data-seq)
            batch-size (count records)
            batch-ids (mapv :id records)
            [columnar-records ref-many-records] (split-ref-many-batch records fields)
            rows (mapv #(entity->row % fields) columnar-records)
            columns (collect-batch-columns rows)
            values (batch-row-values rows columns)
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
        (write-junction-rows! ds entity-name batch-ids ref-many-records fields false)
        (merge-back-ref-many result-rows ref-many-records)))))


(defn read-entities
  "Reads multiple entities by ids. Returns {id -> record} for found records.
   Throws :table-not-found if entity table doesn't exist.

   For :ref-many fields: loads from junction tables and populates fields as
   vectors of UUIDs. Pass `fields` (the entity field specs) to enable this."
  ([ds entity-name ids]
   (read-entities ds entity-name ids nil))
  ([ds entity-name ids fields]
   (if (empty? ids)
     {}
     (let [table-name (keyword (util/kw->snake-case entity-name))
           query (sql/format {:select [:*]
                              :from [table-name]
                              :where [:in :id (vec ids)]}
                             {:quoted true})]
       (util/with-sql-error-handling "Database error" :read-entities {:entity-name entity-name :count (count ids)}
                                     (let [rows (jdbc/execute! ds query (util/query-opts))
                                           records (mapv codec/row->entity rows)
                                           populated (if (and fields (junction/has-ref-many? fields))
                                                       (junction/populate-ref-many-fields ds entity-name records fields)
                                                       records)]
                                       (into {} (map (fn [e] [(:id e) e])) populated)))))))


(defn update-entities
  "Updates multiple entity records in a single batch.
   Each record must have :id. Returns seq of updated records.
   Uses PostgreSQL UPDATE ... FROM (VALUES ...) for efficient batch update.
   Throws :not-found if any entity doesn't exist."
  [ds entity-name data-seq fields]
  (if (empty? data-seq)
    []
    (do
      (sp/validate-batch-size! (count data-seq) :update-entities {:entity-name entity-name})
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [table-name-str (util/kw->snake-case entity-name)
            ;; Validate all records have :id
            missing-ids (vec (remove :id data-seq))]
        (when (seq missing-ids)
          (throw (ex-info "Each record must have :id for batch update"
                          {:type :invalid-data
                           :entity-name entity-name
                           :count (count missing-ids)})))
        (let [records (vec data-seq)
              batch-size (count records)
              batch-ids (mapv :id records)
              [columnar-records ref-many-records] (split-ref-many-batch records fields)
              rows (mapv #(entity->row % fields) columnar-records)
              columns (collect-batch-columns rows)
              update-columns (vec (remove #{:id} columns))]
          ;; If no columns to update (only :id provided), just verify existence and return
          (if (empty? update-columns)
            (let [existing (read-entities ds entity-name batch-ids)
                  missing (vec (remove #(contains? existing %) batch-ids))]
              (when (seq missing)
                (throw (ex-info "Entity not found"
                                {:type :not-found
                                 :entity-name entity-name
                                 :missing-ids missing})))
              (map #(get existing (:id %)) records))
            ;; Normal case: have columns to update
            (let [values (batch-row-values rows columns)
                  ;; Build column type casts for VALUES clause
                  ;; PostgreSQL needs explicit type casts for UUID and other types
                  column-types (mapv (fn [col]
                                       (cond
                                         (= col :id) "uuid"
                                         ;; Check first non-nil value for type inference
                                         :else (let [sample (some #(get % col) rows)]
                                                 (cond
                                                   (uuid? sample) "uuid"
                                                   (boolean? sample) "boolean"
                                                   (int? sample) "bigint"
                                                   (instance? java.time.Instant sample) "timestamptz"
                                                   :else nil))))
                                     columns)
                  ;; Build VALUES clause with type casts using ? placeholders
                  ;; JDBC uses ? placeholders, not PostgreSQL's $N
                  values-sql (str "VALUES "
                                  (str/join
                                    ", "
                                    (map (fn [row-vals]
                                           (str "("
                                                (str/join
                                                  ", "
                                                  (map-indexed
                                                    (fn [col-idx _v]
                                                      (if-let [type-cast (get column-types col-idx)]
                                                        (str "?::" type-cast)
                                                        "?"))
                                                    row-vals))
                                                ")"))
                                         values)))
                  ;; Column aliases for the VALUES subquery
                  col-aliases (str/join ", " (map #(str "\"" (name %) "\"") columns))
                  ;; SET clause: col = v.col for each update column
                  set-clause (str/join
                               ", "
                               (map #(let [col-str (str "\"" (name %) "\"")]
                                       (str col-str " = v." col-str))
                                    update-columns))
                  ;; Build full UPDATE ... FROM (VALUES ...) AS v(...) WHERE t.id = v.id
                  sql (str "UPDATE \"" table-name-str "\" AS t SET "
                           set-clause
                           " FROM (" values-sql ") AS v(" col-aliases ")"
                           " WHERE t.\"id\" = v.\"id\""
                           " RETURNING t.*")
                  ;; Flatten values for parameters
                  params (vec (mapcat identity values))
                  query (into [sql] params)
                  result-rows (try
                                (jdbc/execute! ds query (util/query-opts))
                                (catch java.sql.SQLException e
                                  (let [wrapped (errors/wrap-sql-error e "Database error" :update-entities
                                                                       {:entity-name entity-name
                                                                        :batch-ids batch-ids})]
                                    (throw (sp/wrap-batch-error wrapped -1 batch-size nil))))
                                (catch Exception e
                                  (throw (sp/wrap-batch-error e -1 batch-size nil))))
                  actual-count (count result-rows)]
              ;; Validate that all records were updated
              (when (not= batch-size actual-count)
                (let [updated-ids (set (map :id result-rows))
                      missing (vec (remove updated-ids batch-ids))]
                  (throw (ex-info "Entity not found"
                                  {:type :not-found
                                   :entity-name entity-name
                                   :missing-ids missing
                                   :expected-count batch-size
                                   :actual-count actual-count}))))
              (write-junction-rows! ds entity-name batch-ids ref-many-records fields true)
              (merge-back-ref-many result-rows ref-many-records))))))))


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
            [columnar-records ref-many-records] (split-ref-many-batch records fields)
            rows (mapv #(entity->row % fields) columnar-records)
            columns (collect-batch-columns rows)
            values (batch-row-values rows columns)
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
        (write-junction-rows! ds entity-name batch-ids ref-many-records fields true)
        (merge-back-ref-many result-rows ref-many-records)))))


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
