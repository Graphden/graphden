(ns graphden.storage.age.crud
  "CRUD operations for AGE storage.
   Uses standard SQL tables for storage (same as postgres-storage).
   AGE graph is kept in sync via age.clj module."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.age.codec :as codec]
    [graphden.storage.postgres.metadata :as metadata]
    [graphden.storage.protocol.core :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.sql
      SQLException)))


;; === SQL Utilities ===

(defn- query-opts
  "Default query options."
  []
  {:builder-fn rs/as-unqualified-kebab-maps
   :timeout (sp/get-query-timeout-seconds)})


(defn- table-not-found?
  "Returns true if exception indicates table not found."
  [^SQLException e]
  (and e
       (or (str/includes? (SQLException/.getMessage e) "does not exist")
           (= "42P01" (SQLException/.getSQLState e)))))


(defmacro with-sql-error-handling
  "Wraps body with SQL error handling."
  [msg operation context & body]
  `(try
     (do ~@body)
     (catch SQLException e#
       (throw (ex-info ~msg
                       (merge {:type (classify-sql-error e#)
                               :operation ~operation
                               :sql-state (SQLException/.getSQLState e#)}
                              ~context)
                       e#)))))


(defn classify-sql-error
  "Classifies a SQL exception into error type."
  [^SQLException e]
  (let [sql-state (SQLException/.getSQLState e)
        message (SQLException/.getMessage e)]
    (cond
      (= "23505" sql-state) :unique-violation
      (= "23503" sql-state) :foreign-key-violation
      (or (= "42P01" sql-state)
          (str/includes? message "does not exist")) :table-not-found
      :else :database-error)))


(defn classify-error
  "Classifies an exception."
  [e]
  (if (instance? SQLException e)
    (classify-sql-error e)
    (or (-> e ex-data :type) :unknown-error)))


(defn wrap-error
  "Wraps an exception with context."
  [e operation context]
  (ex-info (ex-message e)
           (merge (ex-data e)
                  {:operation operation}
                  context)
           e))


;; === Introspection ===

(defn current-entities
  "Returns set of entity names in storage."
  [ds]
  (let [query ["SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                AND table_type = 'BASE TABLE'
                AND table_name NOT LIKE '\\_%' ESCAPE '\\'"]
        rows (jdbc/execute! ds query (query-opts))]
    (set (map (comp sp/snake->kw :table-name) rows))))


(defn current-enums
  "Returns set of enum type names."
  [ds]
  (let [query ["SELECT typname FROM pg_type
                WHERE typtype = 'e'
                AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')"]
        rows (jdbc/execute! ds query (query-opts))]
    (set (map (comp sp/snake->kw :typname) rows))))


(defn current-enum-values
  "Returns set of values for an enum type."
  [ds enum-name]
  (let [query ["SELECT enumlabel FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE typname = ?"
               (sp/kw->snake-case enum-name)]
        rows (jdbc/execute! ds query (query-opts))]
    (when (seq rows)
      (set (map (comp keyword :enumlabel) rows)))))


;; === Metadata Cache ===
;; Reuses postgres-storage metadata module for parsing


(defn- build-entity-fields-index
  "Builds index of entity-name -> field-specs."
  [metadata]
  (when metadata
    (->> (:fields metadata)
         vals
         (group-by :entity)
         (reduce-kv
           (fn [acc entity-name fields]
             (assoc acc entity-name
                    (into {}
                          (map (fn [{:keys [field nullable? enum-name] field-type :type}]
                                 [field (cond-> {:type field-type :nullable? nullable?}
                                          enum-name (assoc :enum-name enum-name))])
                               fields))))
           {}))))


(defn get-cached-metadata
  "Gets metadata from cache or reads from database."
  [pool metadata-cache rw-lock]
  (or @metadata-cache
      (sp/with-write-lock rw-lock
                          (fn []
                            (or @metadata-cache
                                (try
                                  (let [raw-metadata (metadata/parse-metadata-lenient
                                                       (metadata/read-metadata-rows pool))
                                        result (when raw-metadata
                                                 (assoc raw-metadata
                                                        :fields-by-entity (build-entity-fields-index raw-metadata)))]
                                    (reset! metadata-cache result)
                                    result)
                                  (catch SQLException e
                                    (when-not (table-not-found? e)
                                      (throw e)))))))))


(defn- get-entity-fields
  "Gets field specs for an entity."
  [pool metadata-cache rw-lock entity-name]
  (when-let [cached (get-cached-metadata pool metadata-cache rw-lock)]
    (get (:fields-by-entity cached) entity-name)))


(defn current-fields
  "Returns field definitions for an entity."
  [pool entity-name metadata-cache rw-lock]
  (when-let [cached (get-cached-metadata pool metadata-cache rw-lock)]
    (when (contains? (set (vals (:entities cached))) entity-name)
      (get (:fields-by-entity cached) entity-name))))


;; === CRUD Operations ===

(defn- entity->row
  [entity fields]
  (codec/encode-row entity fields))


(defn create-entity
  "Creates a new entity record."
  [ds entity-name data metadata-cache rw-lock]
  (let [fields (get-entity-fields ds metadata-cache rw-lock entity-name)]
    (sp/standard-crud-validations! entity-name data fields)
    (let [table-name (keyword (sp/kw->snake-case entity-name))
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
      (with-sql-error-handling "Database error" :create-entity {:entity-name entity-name :id id}
        (-> (jdbc/execute-one! ds query (query-opts))
            codec/row->entity)))))


(defn read-entity
  "Reads an entity by id."
  [ds entity-name id]
  (let [table-name (keyword (sp/kw->snake-case entity-name))
        query (sql/format {:select [:*]
                           :from [table-name]
                           :where [:= :id id]}
                          {:quoted true})]
    (with-sql-error-handling "Database error" :read-entity {:entity-name entity-name :id id}
      (-> (jdbc/execute-one! ds query (query-opts))
          codec/row->entity))))


(defn update-entity
  "Updates an entity by id."
  [ds entity-name id data metadata-cache rw-lock]
  (let [fields (get-entity-fields ds metadata-cache rw-lock entity-name)
        existing (read-entity ds entity-name id)]
    (when-not existing
      (throw (ex-info "Entity not found"
                      {:type :not-found
                       :entity entity-name
                       :id id})))
    (let [table-name (keyword (sp/kw->snake-case entity-name))
          updated (merge existing data {:id id})
          _ (when fields (sp/validate-required-fields! entity-name fields updated))
          row (entity->row (dissoc updated :id) fields)
          query (sql/format {:update table-name
                             :set row
                             :where [:= :id id]
                             :returning [:*]}
                            {:quoted true})]
      (with-sql-error-handling "Database error" :update-entity {:entity-name entity-name :id id}
        (-> (jdbc/execute-one! ds query (query-opts))
            codec/row->entity)))))


(defn delete-entity
  "Deletes an entity by id."
  [ds entity-name id]
  (let [table-name (keyword (sp/kw->snake-case entity-name))
        query (sql/format {:delete-from table-name
                           :where [:= :id id]}
                          {:quoted true})]
    (with-sql-error-handling "Database error" :delete-entity {:entity-name entity-name :id id}
      (pos? (:next.jdbc/update-count
              (jdbc/execute-one! ds query (query-opts)))))))


(defn query-entities
  "Queries entities by conditions."
  [ds entity-name where metadata-cache rw-lock]
  (let [fields (get-entity-fields ds metadata-cache rw-lock entity-name)]
    (sp/standard-query-validations! entity-name fields where)
    (let [table-name (keyword (sp/kw->snake-case entity-name))
          where-clause (when (seq where)
                         (into [:and]
                               (map (fn [[k v]]
                                      (let [col (keyword (sp/kw->snake-case k))
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
        (log/debug "Full table scan query" {:entity-name entity-name}))
      (with-sql-error-handling "Database error" :query-entities {:entity-name entity-name :where where}
        (let [rows (jdbc/execute! ds query (query-opts))]
          (map codec/row->entity rows))))))


;; === Batch Operations ===

(defn create-entities
  "Creates multiple entity records."
  [ds entity-name data-seq metadata-cache rw-lock]
  (if (empty? data-seq)
    []
    (let [fields (get-entity-fields ds metadata-cache rw-lock entity-name)]
      (sp/validate-batch-size! (count data-seq) :create-entities {:entity-name entity-name})
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [table-name (keyword (sp/kw->snake-case entity-name))
            records (vec (map (fn [data]
                                (when fields (sp/validate-required-fields! entity-name fields data))
                                (let [id (or (:id data) (random-uuid))]
                                  (assoc data :id id)))
                              data-seq))
            rows (map #(entity->row % fields) records)
            columns (vec (keys (first rows)))
            values (vec (map (fn [row] (mapv #(get row %) columns)) rows))
            query (sql/format {:insert-into table-name
                               :columns columns
                               :values values
                               :returning [:*]}
                              {:quoted true})]
        (with-sql-error-handling "Database error" :create-entities {:entity-name entity-name}
          (let [result-rows (jdbc/execute! ds query (query-opts))]
            (map codec/row->entity result-rows)))))))


(defn read-entities
  "Reads multiple entities by ids."
  [ds entity-name ids]
  (if (empty? ids)
    {}
    (let [table-name (keyword (sp/kw->snake-case entity-name))
          query (sql/format {:select [:*]
                             :from [table-name]
                             :where [:in :id (vec ids)]}
                            {:quoted true})]
      (with-sql-error-handling "Database error" :read-entities {:entity-name entity-name}
        (let [rows (jdbc/execute! ds query (query-opts))]
          (->> rows
               (map codec/row->entity)
               (map (juxt :id identity))
               (into {})))))))


(defn delete-entities
  "Deletes multiple entities by ids."
  [ds entity-name ids]
  (if (empty? ids)
    0
    (let [table-name (keyword (sp/kw->snake-case entity-name))
          query (sql/format {:delete-from table-name
                             :where [:in :id (vec ids)]}
                            {:quoted true})]
      (with-sql-error-handling "Database error" :delete-entities {:entity-name entity-name}
        (:next.jdbc/update-count
          (jdbc/execute-one! ds query (query-opts)))))))
