(ns graphden.postgres-storage.crud
  "CRUD operations for PostgreSQL storage.
   Generic entity operations for create, read, update, delete, query."
  (:require
    [cheshire.core :as json]
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (com.fasterxml.jackson.core
      JsonParseException)
    (org.postgresql.util
      PGobject)))


;; Use shared timeout utility from util.clj
(def ^:private get-query-timeout util/get-query-timeout-seconds)


;; Use shared macro from util.clj with CRUD-specific prefix
(defmacro ^:private with-crud-error-handling
  "Wraps CRUD operation body with SQLException handling.
   Uses 'Database error' prefix for log messages."
  [operation context & body]
  `(util/with-sql-error-handling "Database error" ~operation ~context ~@body))


(defn- parse-pgobject
  "Parses PGobject values (like JSONB) to Clojure data.
   Wraps JSON parsing errors with context information.
   Returns nil for null PGobject values (SQL NULL stored in JSONB column)."
  [v]
  (if (instance? PGobject v)
    (let [pg-type (PGobject/.getType v)
          pg-value (PGobject/.getValue v)]
      ;; Handle NULL values - PGobject can have null value for SQL NULL
      (when pg-value
        (if (= pg-type "jsonb")
          (try
            (json/parse-string pg-value true)
            ;; Catch only JSON parsing errors - other exceptions (OOM, connection issues)
            ;; should propagate as-is to avoid masking infrastructure problems
            (catch JsonParseException e
              (throw (ex-info "Failed to parse JSONB value"
                              {:type :parse-error/jsonb
                               :raw-value (if (> (count pg-value) 100)
                                            (str (subs pg-value 0 100) "...")
                                            pg-value)
                               :cause (Throwable/.getMessage e)}
                              e))))
          pg-value)))
    v))


(defn- row->entity
  "Converts a database row to entity map.
   Converts snake_case column names to kebab-case keywords.
   Parses JSONB values to Clojure data."
  [row]
  (when row
    (reduce-kv (fn [acc k v]
                 (let [new-key (util/snake->kw (name k))
                       parsed-v (parse-pgobject v)]
                   (assoc acc new-key parsed-v)))
               {}
               row)))


(defn- value->jsonb
  "Wraps a value as JSONB PGobject for PostgreSQL."
  [v]
  (doto (PGobject.)
    (PGobject/.setType "jsonb")
    (PGobject/.setValue (json/generate-string v))))


(defn- jsonb-column?
  "Returns true if field type should be stored as JSONB."
  [field-type]
  (contains? #{:jsonb :union} field-type))


;; === Fallback JSONB columns ===
;;
;; These columns are always treated as JSONB, even when field metadata is not available.
;; This is a safety mechanism for:
;;
;; 1. Direct CRUD calls without schema context - some internal operations may call
;;    create-entity/update-entity without the full field metadata
;;
;; 2. Specific entity types with known JSONB fields - the :value field in arg_value
;;    entities stores polymorphic values (refs, literals) as JSONB
;;
;; IMPORTANT: If you add new entities with JSONB fields that might be accessed
;; without field metadata, add them here. However, the preferred approach is to
;; always pass field metadata to CRUD operations.
;;
;; Current fallbacks:
;; - :value - Used in arg_value entity for polymorphic value storage (:union type)
(def ^:private fallback-jsonb-columns
  #{:value})


(defn- extract-jsonb-columns
  "Extracts set of column names that should be stored as JSONB from fields map.
   Falls back to known JSONB columns when fields is nil."
  [fields]
  (if (nil? fields)
    fallback-jsonb-columns
    (let [from-schema (->> fields
                           (filter (fn [[_ spec]] (jsonb-column? (:type spec))))
                           (map first)
                           (set))]
      ;; Merge with fallback to ensure known JSONB columns are always included
      (into from-schema fallback-jsonb-columns))))


(defn- extract-enum-columns
  "Extracts map of {field-name -> enum-name} for enum fields.
   Returns empty map when fields is nil."
  [fields]
  (if (nil? fields)
    {}
    (->> fields
         (filter (fn [[_ spec]] (= (:type spec) :enum)))
         (map (fn [[field-name spec]] [field-name (:enum-name spec)]))
         (into {}))))


(defn- maybe-wrap-jsonb
  "Wraps value as JSONB if column is in jsonb-columns set.
   Returns nil as-is (SQL NULL) rather than converting to JSON null.
   Note: `false` is correctly wrapped as JSONB since (some? false) => true."
  [jsonb-columns col-name v]
  (if (and (contains? jsonb-columns col-name)
           (some? v)
           (not (instance? PGobject v)))
    (value->jsonb v)
    v))


(defn- maybe-convert-enum
  "Converts enum value (keyword) to PGobject for PostgreSQL enum columns.
   Returns nil as-is. Non-keyword values pass through unchanged."
  [enum-columns col-name v]
  (if-let [enum-name (get enum-columns col-name)]
    (if (keyword? v)
      (doto (PGobject.)
        (PGobject/.setType (util/kw->snake-case enum-name))
        (PGobject/.setValue (util/enum-value->sql v)))
      v)
    v))


(defn- entity->row
  "Converts entity map to database row.
   Converts kebab-case keywords to snake_case column names.
   Converts enum values (keywords) to snake_case strings.
   Wraps JSONB column values appropriately based on fields metadata."
  [entity jsonb-columns enum-columns]
  (reduce-kv (fn [acc k v]
               (let [new-key (keyword (util/kw->snake-case k))
                     enum-v (maybe-convert-enum enum-columns k v)
                     wrapped-v (maybe-wrap-jsonb jsonb-columns k enum-v)]
                 (assoc acc new-key wrapped-v)))
             {}
             entity))


(defn create-entity
  "Creates a new entity record in the database.
   Returns the created record with generated id if not provided.
   Validates required fields if fields metadata is provided.
   Throws with :unique-violation type if unique constraint violated.
   Throws with :invalid-data type if data is not a map."
  [ds entity-name data fields]
  (sp/validate-data-is-map! entity-name data)
  (when fields
    (sp/validate-required-fields! entity-name fields data))
  (let [table-name (keyword (util/kw->snake-case entity-name))
        jsonb-cols (extract-jsonb-columns fields)
        enum-cols (extract-enum-columns fields)
        id (or (:id data) (random-uuid))
        record (assoc data :id id)
        row (entity->row record jsonb-cols enum-cols)
        columns (keys row)
        values (vals row)
        query (sql/format {:insert-into table-name
                           :columns columns
                           :values [values]
                           :returning [:*]}
                          {:quoted true})]
    (with-crud-error-handling :create-entity {:entity-name entity-name :id id}
      (-> (jdbc/execute-one! ds query
                             {:builder-fn rs/as-unqualified-lower-maps
                              :timeout (get-query-timeout)})
          row->entity))))


(defn read-entity
  "Reads an entity by id. Returns nil if not found.
   Throws with :table-not-found if entity table doesn't exist."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:select [:*]
                           :from [table-name]
                           :where [:= :id id]}
                          {:quoted true})]
    (with-crud-error-handling :read-entity {:entity-name entity-name :id id}
      (-> (jdbc/execute-one! ds query
                             {:builder-fn rs/as-unqualified-lower-maps
                              :timeout (get-query-timeout)})
          row->entity))))


(defn update-entity
  "Updates an entity by id. Returns the updated record.
   Throws :not-found if entity doesn't exist.
   Throws :unique-violation if update violates unique constraint.
   Validates required fields if fields metadata is provided."
  [ds entity-name id data fields]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        jsonb-cols (extract-jsonb-columns fields)
        enum-cols (extract-enum-columns fields)
        existing (read-entity ds entity-name id)]
    (when-not existing
      (throw (ex-info "Entity not found"
                      {:type :not-found
                       :entity entity-name
                       :id id})))
    (let [updated (merge existing data {:id id})]
      (when fields
        (sp/validate-required-fields! entity-name fields updated))
      (let [row (entity->row (dissoc updated :id) jsonb-cols enum-cols)
            query (sql/format {:update table-name
                               :set row
                               :where [:= :id id]
                               :returning [:*]}
                              {:quoted true})]
        (with-crud-error-handling :update-entity {:entity-name entity-name :id id}
          (-> (jdbc/execute-one! ds query
                                 {:builder-fn rs/as-unqualified-lower-maps
                                  :timeout (get-query-timeout)})
              row->entity))))))


(defn delete-entity
  "Deletes an entity by id. Returns true if entity existed and was deleted.
   Throws :foreign-key-violation if entity is referenced by other records."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:delete-from table-name
                           :where [:= :id id]}
                          {:quoted true})]
    (with-crud-error-handling :delete-entity {:entity-name entity-name :id id}
      (pos? (:next.jdbc/update-count
              (jdbc/execute-one! ds query {:timeout (get-query-timeout)}))))))


(defn query-entities
  "Queries entities by conditions.
   where is a map of field->value for equality matching.
   Supports nil values (generates IS NULL instead of = NULL).
   Returns a sequence of matching entities.
   Throws :table-not-found if entity table doesn't exist.
   Throws :invalid-where-clause if where is not nil or a map."
  [ds entity-name where]
  (sp/validate-where-clause! where)
  (let [table-name (keyword (util/kw->snake-case entity-name))
        where-clause (when (seq where)
                       (into [:and]
                             (map (fn [[k v]]
                                    (let [col (keyword (util/kw->snake-case k))]
                                      (if (nil? v)
                                        [:is col nil]
                                        [:= col v])))
                                  where)))
        query (sql/format (cond-> {:select [:*]
                                   :from [table-name]}
                            where-clause (assoc :where where-clause))
                          {:quoted true})]
    (with-crud-error-handling :query-entities {:entity-name entity-name :where where}
      (let [rows (jdbc/execute! ds query
                                {:builder-fn rs/as-unqualified-lower-maps
                                 :timeout (get-query-timeout)})]
        (map row->entity rows)))))


;; === Batch CRUD operations ===

(defn create-entities
  "Creates multiple entity records in a single transaction.
   Returns a sequence of created records with generated ids.
   Throws :unique-violation if any unique constraint violated.
   Throws :duplicate-ids if duplicate IDs found in batch."
  [ds entity-name data-seq fields]
  (if (empty? data-seq)
    []
    (do
      (sp/validate-no-duplicate-ids! entity-name data-seq)
      (let [table-name (keyword (util/kw->snake-case entity-name))
            jsonb-cols (extract-jsonb-columns fields)
            enum-cols (extract-enum-columns fields)
            ;; Prepare all records with IDs
            records (map (fn [data]
                           (when fields
                             (sp/validate-required-fields! entity-name fields data))
                           (let [id (or (:id data) (random-uuid))]
                             (assoc data :id id)))
                         data-seq)
            ;; Convert to rows
            rows (map #(entity->row % jsonb-cols enum-cols) records)
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
                              {:quoted true})]
        (with-crud-error-handling :create-entities {:entity-name entity-name :count (count data-seq)}
          (let [result-rows (jdbc/execute! ds query
                                           {:builder-fn rs/as-unqualified-lower-maps
                                            :timeout (get-query-timeout)})
                expected-count (count data-seq)
                actual-count (count result-rows)]
            ;; Validate that all records were inserted
            (when (not= expected-count actual-count)
              (throw (ex-info "Batch insert returned unexpected number of records"
                              {:type :batch-insert-mismatch
                               :entity-name entity-name
                               :expected-count expected-count
                               :actual-count actual-count})))
            (map row->entity result-rows)))))))


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
      (with-crud-error-handling :read-entities {:entity-name entity-name :count (count ids)}
        (let [rows (jdbc/execute! ds query
                                  {:builder-fn rs/as-unqualified-lower-maps
                                   :timeout (get-query-timeout)})]
          (->> rows
               (map row->entity)
               (map (juxt :id identity))
               (into {})))))))


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
      (with-crud-error-handling :delete-entities {:entity-name entity-name :count (count ids)}
        (:next.jdbc/update-count
          (jdbc/execute-one! ds query {:timeout (get-query-timeout)}))))))


;; === ExecutionGraph ===

(def ^:private max-parent-chain-depth
  "Maximum depth for parent chain traversal to prevent runaway recursion.
   Uses shared default-max-depth from storage-protocol for consistency."
  sp/default-max-depth)


(defn- collect-parent-chains-batch
  "Collects parent chains for multiple fns using recursive CTE.
   Returns {fn-id -> [chain-fn-ids from child to root]}.
   Limits recursion depth to max-parent-chain-depth to prevent runaway queries."
  [ds fn-ids]
  (if (empty? fn-ids)
    {}
    (let [fn-ids-vec (vec fn-ids)
          ;; Use recursive CTE to get all parent chains at once
          ;; The 'origin' column tracks which starting fn-id each chain belongs to
          ;; depth < max-parent-chain-depth prevents infinite recursion
          query (sql/format
                  {:with-recursive
                   [[:parent_chain
                     {:union-all
                      [{:select [:id :parent_fn_id [:id :origin] [[:raw "0"] :depth]]
                        :from [:fn]
                        :where [:in :id fn-ids-vec]}
                       {:select [:f.id :f.parent_fn_id :pc.origin [[:+ :pc.depth [:raw "1"]] :depth]]
                        :from [[:fn :f]]
                        :join [[:parent_chain :pc] [:= :f.id :pc.parent_fn_id]]
                        :where [:< :pc.depth max-parent-chain-depth]}]}]]
                   :select [:id :origin :depth]
                   :from [:parent_chain]
                   :order-by [:origin :depth]}
                  {:quoted true})]
      (with-crud-error-handling :collect-parent-chains {:fn-count (count fn-ids)}
        (let [rows (jdbc/execute! ds query
                                  {:builder-fn rs/as-unqualified-lower-maps
                                   :timeout (get-query-timeout)})
              ;; Group by origin and extract ordered chain
              result (->> rows
                          (group-by :origin)
                          (map (fn [[origin chain-rows]]
                                 [origin (mapv :id (sort-by :depth chain-rows))]))
                          (into {}))
              ;; Warn if any chain reached the depth limit
              max-depth-found (apply max 0 (map :depth rows))]
          (when (>= max-depth-found max-parent-chain-depth)
            (log/warn "Parent chain reached maximum depth limit"
                      {:max-depth max-parent-chain-depth
                       :fn-ids fn-ids-vec}))
          result)))))


(defn- load-arg-values-batch
  "Loads all arg-values for a set of fn-ids.
   Returns seq of arg-value records."
  [ds fn-ids]
  (if (empty? fn-ids)
    []
    (let [query (sql/format {:select [:*]
                             :from [:arg_value]
                             :where [:in :owner_fn_id (vec fn-ids)]}
                            {:quoted true})]
      (with-crud-error-handling :load-arg-values {:fn-count (count fn-ids)}
        (let [rows (jdbc/execute! ds query
                                  {:builder-fn rs/as-unqualified-lower-maps
                                   :timeout (get-query-timeout)})]
          (map row->entity rows))))))


(defn- classify-uuid-refs
  "Classifies UUID references as either fn refs or fn-result-value refs.
   Returns {:fn-ids #{...} :frv-ids #{...}}.
   Gracefully handles missing fn_result_value table (returns empty frv-ids)."
  [ds uuid-candidates]
  (if (empty? uuid-candidates)
    {:fn-ids #{} :frv-ids #{}}
    (let [uuids-vec (vec uuid-candidates)
          ;; Query fn table
          fn-query (sql/format {:select [:id]
                                :from [:fn]
                                :where [:in :id uuids-vec]}
                               {:quoted true})
          ;; Query fn_result_value table (may not exist in older schemas)
          frv-query (sql/format {:select [:id]
                                 :from [:fn_result_value]
                                 :where [:in :id uuids-vec]}
                                {:quoted true})]
      (with-crud-error-handling :classify-uuid-refs {:candidate-count (count uuid-candidates)}
        (let [fn-rows (jdbc/execute! ds fn-query
                                     {:builder-fn rs/as-unqualified-lower-maps
                                      :timeout (get-query-timeout)})
              ;; Try to query fn_result_value, but handle table-not-found gracefully
              frv-rows (try
                         (jdbc/execute! ds frv-query
                                        {:builder-fn rs/as-unqualified-lower-maps
                                         :timeout (get-query-timeout)})
                         (catch java.sql.SQLException e
                           ;; 42P01 = undefined_table in PostgreSQL
                           (if (= "42P01" (java.sql.SQLException/.getSQLState e))
                             []
                             (throw e))))]
          {:fn-ids (set (map :id fn-rows))
           :frv-ids (set (map :id frv-rows))})))))


(defn- load-entities-batch
  "Generic batch loader. Loads entities from table where key-column matches values.
   Returns {id -> record} map keyed by :id field of each record."
  [ds table key-column values]
  (if (empty? values)
    {}
    (let [query (sql/format {:select [:*]
                             :from [table]
                             :where [:in key-column (vec values)]}
                            {:quoted true})]
      (with-crud-error-handling :load-entities-batch {:table table :count (count values)}
        (let [rows (jdbc/execute! ds query
                                  {:builder-fn rs/as-unqualified-lower-maps
                                   :timeout (get-query-timeout)})]
          (->> rows
               (map row->entity)
               (map (juxt :id identity))
               (into {})))))))


(defn- load-fns-batch
  "Loads multiple fns by id. Returns {fn-id -> fn-record}."
  [ds fn-ids]
  (load-entities-batch ds :fn :id fn-ids))


(defn- load-fn-schemas-batch
  "Loads multiple fn-schemas by id. Returns {fn-schema-id -> fn-schema-record}."
  [ds fn-schema-ids]
  (load-entities-batch ds :fn_schema :id fn-schema-ids))


(defn- load-arg-schemas-batch
  "Loads arg-schemas for multiple fn-schema-ids. Returns {arg-schema-id -> arg-schema-record}."
  [ds fn-schema-ids]
  (load-entities-batch ds :arg_schema :fn_schema_id fn-schema-ids))


(defn- load-fn-result-values-batch
  "Loads fn-result-values by their IDs.
   Returns {fn-result-value-id -> fn-result-value-record}."
  [ds frv-ids]
  (load-entities-batch ds :fn_result_value :id frv-ids))


(defn resolve-execution-graph
  "Resolves complete execution graph for a function.
   Uses batched BFS to collect all transitively referenced functions and fn-result-values.
   Throws if iteration count exceeds sp/*max-graph-iterations*.

   This implementation uses batch queries to minimize database round-trips:
   1. Process pending fn-ids in batches
   2. Batch load parent chains using recursive CTE
   3. Batch load arg-values for all chain members
   4. Extract fn-refs and fn-result-value refs, continue until graph is complete
   5. Final batch load of all fns, fn-schemas, arg-schemas, fn-result-values"
  [ds fn-id]
  (let [root-fn (read-entity ds :fn fn-id)]
    (when-not root-fn
      (throw (ex-info "Function not found"
                      {:type :not-found
                       :fn-id fn-id})))
    ;; Phase 1: Discover all fn-ids and fn-result-values in the graph using batched BFS
    (loop [to-visit #{fn-id}
           visited #{}
           ;; Accumulate: fn-id -> parent-chain, fn-id -> merged-args
           all-chains {}
           all-merged-args {}
           all-frv-ids #{}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        ;; Phase 2: Batch load all data
        (let [all-fn-ids (set (keys all-chains))
              ;; Load all fns
              fns (load-fns-batch ds all-fn-ids)
              ;; Get unique fn-schema-ids
              fn-schema-ids (->> (vals fns)
                                 (map :fn-schema-id)
                                 (set))
              ;; Load all fn-schemas
              fn-schemas (load-fn-schemas-batch ds fn-schema-ids)
              ;; Load all arg-schemas
              arg-schemas (load-arg-schemas-batch ds fn-schema-ids)
              ;; Load all fn-result-values
              fn-result-values (load-fn-result-values-batch ds all-frv-ids)]
          {:fns fns
           :fn-schemas fn-schemas
           :arg-schemas arg-schemas
           :resolved-args all-merged-args
           :fn-result-values fn-result-values})
        ;; Process batch of pending fn-ids
        (let [batch (vec to-visit)
              new-visited (into visited batch)
              ;; Batch load parent chains
              chains (collect-parent-chains-batch ds batch)
              ;; Get all fn-ids in all chains
              all-chain-fn-ids (->> (vals chains)
                                    (mapcat identity)
                                    (set))
              ;; Batch load arg-values for all chain members
              all-arg-values (load-arg-values-batch ds all-chain-fn-ids)
              ;; Merge arg-values for each fn
              merged-args-batch (into {}
                                      (map (fn [fid]
                                             [fid (sp/merge-arg-values-for-chain
                                                    all-arg-values
                                                    (get chains fid [fid]))]))
                                      batch)
              ;; Extract all potential refs (UUIDs)
              all-potential-refs (->> (vals merged-args-batch)
                                      (mapcat sp/extract-uuid-refs-from-arg-values)
                                      (set))
              ;; Remove already visited
              new-candidates (set/difference all-potential-refs new-visited)
              ;; Classify refs as fn or fn-result-value
              {:keys [fn-ids frv-ids]} (classify-uuid-refs ds new-candidates)
              ;; Load fn-result-values to get their fn-ids
              new-frvs (load-fn-result-values-batch ds frv-ids)
              ;; Add fn-ids from fn-result-values to visit set
              frv-fn-ids (set (map :fn-id (vals new-frvs)))
              ;; Combine direct fn refs + fn refs from fn-result-values
              all-new-fn-refs (set/union fn-ids (set/difference frv-fn-ids new-visited))]
          (recur all-new-fn-refs
                 new-visited
                 (merge all-chains chains)
                 (merge all-merged-args merged-args-batch)
                 (set/union all-frv-ids frv-ids)
                 (+ iter-count (count batch))))))))
