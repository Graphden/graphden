(ns graphden.postgres-storage.crud
  "CRUD operations for PostgreSQL storage.
   Generic entity operations for create, read, update, delete, query."
  (:require
    [cheshire.core :as json]
    [clojure.set :as set]
    [clojure.string :as str]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (org.postgresql.util
      PGobject)))


(def ^:private query-timeout-seconds
  "Default timeout for CRUD queries (in seconds)."
  30)


(defn- parse-pgobject
  "Parses PGobject values (like JSONB) to Clojure data.
   Wraps JSON parsing errors with context information."
  [v]
  (if (instance? PGobject v)
    (let [pg-type (PGobject/.getType v)
          pg-value (PGobject/.getValue v)]
      (if (= pg-type "jsonb")
        (try
          (json/parse-string pg-value true)
          (catch Exception e
            (throw (ex-info "Failed to parse JSONB value"
                            {:type :parse-error/jsonb
                             :raw-value (if (> (count pg-value) 100)
                                          (str (subs pg-value 0 100) "...")
                                          pg-value)
                             :cause (Throwable/.getMessage e)}
                            e))))
        pg-value))
    v))


(defn- row->entity
  "Converts a database row to entity map.
   Converts snake_case column names to kebab-case keywords.
   Parses JSONB values to Clojure data."
  [row]
  (when row
    (reduce-kv (fn [acc k v]
                 (let [new-key (keyword (str/replace (name k) "_" "-"))
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


;; Fallback JSONB columns for cases when field metadata is not available.
;; This ensures that known JSONB columns are properly wrapped even when
;; crud functions are called directly without field metadata.
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


(defn- maybe-wrap-jsonb
  "Wraps value as JSONB if column is in jsonb-columns set.
   Returns nil as-is (SQL NULL) rather than converting to JSON null."
  [jsonb-columns col-name v]
  (if (and (contains? jsonb-columns col-name)
           (some? v)
           (not (instance? PGobject v)))
    (value->jsonb v)
    v))


(defn- entity->row
  "Converts entity map to database row.
   Converts kebab-case keywords to snake_case column names.
   Wraps JSONB column values appropriately based on fields metadata."
  [entity jsonb-columns]
  (reduce-kv (fn [acc k v]
               (let [new-key (keyword (str/replace (name k) "-" "_"))
                     wrapped-v (maybe-wrap-jsonb jsonb-columns k v)]
                 (assoc acc new-key wrapped-v)))
             {}
             entity))


(defn create-entity
  "Creates a new entity record in the database.
   Returns the created record with generated id if not provided.
   Validates required fields if fields metadata is provided."
  [ds entity-name data fields]
  (when fields
    (sp/validate-required-fields! entity-name fields data))
  (let [table-name (keyword (util/kw->snake-case entity-name))
        jsonb-cols (extract-jsonb-columns fields)
        id (or (:id data) (random-uuid))
        record (assoc data :id id)
        row (entity->row record jsonb-cols)
        columns (keys row)
        values (vals row)
        query (sql/format {:insert-into table-name
                           :columns columns
                           :values [values]
                           :returning [:*]}
                          {:quoted true})]
    (-> (jdbc/execute-one! ds query
                           {:builder-fn rs/as-unqualified-lower-maps
                            :timeout query-timeout-seconds})
        row->entity)))


(defn read-entity
  "Reads an entity by id. Returns nil if not found."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:select [:*]
                           :from [table-name]
                           :where [:= :id id]}
                          {:quoted true})]
    (-> (jdbc/execute-one! ds query
                           {:builder-fn rs/as-unqualified-lower-maps
                            :timeout query-timeout-seconds})
        row->entity)))


(defn update-entity
  "Updates an entity by id. Returns the updated record.
   Throws if entity not found.
   Validates required fields if fields metadata is provided."
  [ds entity-name id data fields]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        jsonb-cols (extract-jsonb-columns fields)
        existing (read-entity ds entity-name id)]
    (when-not existing
      (throw (ex-info "Entity not found"
                      {:type :not-found
                       :entity entity-name
                       :id id})))
    (let [updated (merge existing data {:id id})]
      (when fields
        (sp/validate-required-fields! entity-name fields updated))
      (let [row (entity->row (dissoc updated :id) jsonb-cols)
            query (sql/format {:update table-name
                               :set row
                               :where [:= :id id]
                               :returning [:*]}
                              {:quoted true})]
        (-> (jdbc/execute-one! ds query
                               {:builder-fn rs/as-unqualified-lower-maps
                                :timeout query-timeout-seconds})
            row->entity)))))


(defn delete-entity
  "Deletes an entity by id. Returns true if entity existed and was deleted."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:delete-from table-name
                           :where [:= :id id]}
                          {:quoted true})
        result (jdbc/execute-one! ds query
                                  {:timeout query-timeout-seconds})]
    (pos? (or (:next.jdbc/update-count result) 0))))


(defn query-entities
  "Queries entities by conditions.
   where is a map of field->value for equality matching.
   Returns a sequence of matching entities."
  [ds entity-name where]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        where-clause (when (seq where)
                       (into [:and]
                             (map (fn [[k v]]
                                    [:= (keyword (util/kw->snake-case k)) v])
                                  where)))
        query (sql/format (cond-> {:select [:*]
                                   :from [table-name]}
                            where-clause (assoc :where where-clause))
                          {:quoted true})
        rows (jdbc/execute! ds query
                            {:builder-fn rs/as-unqualified-lower-maps
                             :timeout query-timeout-seconds})]
    (map row->entity rows)))


;; === ExecutionGraph ===

(defn- collect-parent-chain-sql
  "Collects parent chain for a fn using recursive CTE.
   Returns vector of fn-ids from child to root."
  [ds fn-id]
  (let [query (sql/format
                {:with-recursive
                 [[:parent_chain
                   {:union-all
                    [{:select [:id :parent_fn_id]
                      :from [:fn]
                      :where [:= :id fn-id]}
                     {:select [:f.id :f.parent_fn_id]
                      :from [[:fn :f]]
                      :join [[:parent_chain :pc] [:= :f.id :pc.parent_fn_id]]}]}]]
                 :select [:id]
                 :from [:parent_chain]}
                {:quoted true})
        rows (jdbc/execute! ds query
                            {:builder-fn rs/as-unqualified-lower-maps
                             :timeout query-timeout-seconds})]
    (mapv :id rows)))


(defn- merge-arg-values-for-chain
  "Gets merged arg-values for a parent chain (child overrides parent).
   Returns {arg-schema-id -> arg-value-record}."
  [ds chain]
  (when (seq chain)
    (let [query (sql/format {:select [:*]
                             :from [:arg_value]
                             :where [:in :owner_fn_id (vec chain)]}
                            {:quoted true})
          rows (jdbc/execute! ds query
                              {:builder-fn rs/as-unqualified-lower-maps
                               :timeout query-timeout-seconds})
          arg-values (map row->entity rows)
          ;; Create position map for chain (lower = higher priority)
          chain-pos (zipmap chain (range))]
      ;; Group by arg-schema-id, pick the one with lowest chain position (closest to target fn)
      (->> arg-values
           (group-by :arg-schema-id)
           (map (fn [[arg-schema-id avs]]
                  [arg-schema-id (apply min-key #(get chain-pos (:owner-fn-id %) Integer/MAX_VALUE) avs)]))
           (into {})))))


(defn- extract-fn-refs
  "Extracts fn-id references from arg-values.
   A value is a fn-ref if it's a UUID (or parseable as UUID) and exists in fn table."
  [ds arg-values-map]
  (let [uuid-values (->> (vals arg-values-map)
                         (map :value)
                         (keep sp/try-parse-uuid)
                         (distinct)
                         (vec))]
    (if (empty? uuid-values)
      #{}
      (let [query (sql/format {:select [:id]
                               :from [:fn]
                               :where [:in :id uuid-values]}
                              {:quoted true})
            rows (jdbc/execute! ds query
                                {:builder-fn rs/as-unqualified-lower-maps
                                 :timeout query-timeout-seconds})]
        (set (map :id rows))))))


(defn resolve-execution-graph
  "Resolves complete execution graph for a function.
   Uses BFS to collect all transitively referenced functions.
   Throws if iteration count exceeds sp/max-graph-iterations.

   PERFORMANCE NOTE: This implementation has N+1 query issues. For each fn node
   in the graph, it makes separate queries for fn, fn-schema, arg-schemas,
   parent chain, arg-values, and fn-refs. For deep/wide graphs this can result
   in many database round-trips.

   Potential optimizations:
   1. Batch load fns when their IDs are known (collect all to-visit, query once)
   2. Use JOINs to fetch fn + fn-schema + arg-schemas in single query
   3. Prefetch all arg-values for known fn-ids in batch
   4. Use recursive CTE to resolve entire graph in single query

   For now, caching at the executor level mitigates this for repeated executions."
  [ds fn-id]
  (let [root-fn (read-entity ds :fn fn-id)]
    (when-not root-fn
      (throw (ex-info "Function not found"
                      {:type :not-found
                       :fn-id fn-id})))
    (loop [to-visit #{fn-id}
           visited #{}
           fns {}
           fn-schemas {}
           arg-schemas {}
           resolved-args {}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        {:fns fns
         :fn-schemas fn-schemas
         :arg-schemas arg-schemas
         :resolved-args resolved-args}
        (let [current-fn-id (first to-visit)
              rest-to-visit (disj to-visit current-fn-id)]
          (if (contains? visited current-fn-id)
            (recur rest-to-visit visited fns fn-schemas arg-schemas resolved-args
                   (inc iter-count))
            (let [fn-rec (or (get fns current-fn-id)
                             (read-entity ds :fn current-fn-id))]
              (if-not fn-rec
                (recur rest-to-visit (conj visited current-fn-id)
                       fns fn-schemas arg-schemas resolved-args
                       (inc iter-count))
                (let [fn-schema-id (:fn-schema-id fn-rec)
                      ;; Load fn-schema if not already loaded
                      fn-schema (or (get fn-schemas fn-schema-id)
                                    (read-entity ds :fn-schema fn-schema-id))
                      ;; Load arg-schemas if not already loaded
                      new-arg-schemas (if (contains? fn-schemas fn-schema-id)
                                        {}
                                        (->> (query-entities ds :arg-schema {:fn-schema-id fn-schema-id})
                                             (map (juxt :id identity))
                                             (into {})))
                      ;; Get parent chain and merge arg-values
                      chain (collect-parent-chain-sql ds current-fn-id)
                      merged-args (merge-arg-values-for-chain ds chain)
                      ;; Find referenced fns
                      ref-fn-ids (extract-fn-refs ds merged-args)
                      new-to-visit (set/difference ref-fn-ids visited)]
                  (recur (set/union rest-to-visit new-to-visit)
                         (conj visited current-fn-id)
                         (assoc fns current-fn-id fn-rec)
                         (if fn-schema
                           (assoc fn-schemas fn-schema-id fn-schema)
                           fn-schemas)
                         (merge arg-schemas new-arg-schemas)
                         (assoc resolved-args current-fn-id merged-args)
                         (inc iter-count)))))))))))
