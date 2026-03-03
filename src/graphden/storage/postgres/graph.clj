(ns graphden.storage.postgres.graph
  "ExecutionGraph resolution for PostgreSQL storage.
   Uses batched queries for efficient graph traversal."
  (:require
    [clojure.set :as set]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


(defn- load-arg-values-for-fn
  "Loads all arg-values for a single fn-id via fn_arg join.
   Returns seq of arg-value records.

   With normalized schema:
   - fn_arg binds fn_id to arg_value_id
   - We join fn_arg -> arg_value to get values for this fn"
  [ds fn-id]
  (let [query (sql/format {:select [:av.*]
                           :from [[:fn_arg :fa]]
                           :join [[:arg_value :av] [:= :av.id :fa.arg_value_id]]
                           :where [:= :fa.fn_id fn-id]}
                          {:quoted true})]
    (util/with-sql-error-handling "Database error" :load-arg-values {:fn-id fn-id}
                                  (let [rows (jdbc/execute! ds query (util/query-opts))]
                                    (map codec/row->entity rows)))))


(defn- classify-and-load-refs
  "Classifies UUID references and loads fn-usages in a single query.
   Returns {:fn-ids #{...} :fn-usage-ids #{...} :fn-usages {...}}.

   Uses UNION ALL to classify refs and load fn-usage data in one round-trip."
  [ds uuid-candidates]
  (if (empty? uuid-candidates)
    {:fn-ids #{} :fn-usage-ids #{} :fn-usages {}}
    (let [uuids-vec (vec uuid-candidates)
          ;; Combined query: classify refs AND load fn-usage data in one query
          ;; Returns rows with entity_type = 'fn' or 'fu'
          combined-query
          [(str "SELECT 'fn' as entity_type, id, NULL::uuid as fn_id FROM fn WHERE id = ANY(?)"
                " UNION ALL "
                "SELECT 'fu' as entity_type, id, fn_id FROM fn_usage WHERE id = ANY(?)")
           (into-array java.util.UUID uuids-vec)
           (into-array java.util.UUID uuids-vec)]]
      (util/with-sql-error-handling "Database error" :classify-and-load-refs {:candidate-count (count uuid-candidates)}
                                    (let [rows (jdbc/execute! ds combined-query (util/query-opts))]
                                      (reduce
                                        (fn [acc row]
                                          (case (:entity_type row)
                                            "fn" (update acc :fn-ids conj (:id row))
                                            "fu" (-> acc
                                                     (update :fn-usage-ids conj (:id row))
                                                     (assoc-in [:fn-usages (:id row)]
                                                               {:id (:id row) :fn-id (:fn_id row)}))
                                            acc))
                                        {:fn-ids #{} :fn-usage-ids #{} :fn-usages {}}
                                        rows))))))


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
      (util/with-sql-error-handling "Database error" :load-entities-batch {:table table :count (count values)}
                                    (let [rows (jdbc/execute! ds query (util/query-opts))]
                                      (->> rows
                                           (map codec/row->entity)
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


(defn- read-entity
  "Reads single entity by id for initial fn lookup."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:select [:*] :from [table-name] :where [:= :id id]}
                          {:quoted true})]
    (util/with-sql-error-handling "Database error" :read-entity {:entity-name entity-name :id id}
                                  (-> (jdbc/execute-one! ds query (util/query-opts))
                                      codec/row->entity))))


(defn- arg-values-to-map
  "Converts a sequence of arg-value records to {arg-schema-id -> arg-value}."
  [arg-values]
  (into {} (map (juxt :arg-schema-id identity) arg-values)))


(defn resolve-execution-graph
  "Resolves complete execution graph for a function.
   Uses batched BFS to collect all transitively referenced functions and fn-usages.
   Throws if iteration count exceeds sp/*max-graph-iterations*.

   This implementation uses batch queries to minimize database round-trips:
   1. Process pending fn-ids in batches
   2. Load arg-values for each fn directly
   3. Extract fn-refs and fn-usage refs, continue until graph is complete
   4. Final batch load of all fns, fn-schemas, arg-schemas"
  [ds fn-id]
  (let [root-fn (read-entity ds :fn fn-id)]
    (when-not root-fn
      (throw (ex-info "Function not found"
                      {:type :not-found
                       :fn-id fn-id})))
    ;; Phase 1: Discover all fn-ids and fn-usages in the graph using batched BFS
    (loop [to-visit #{fn-id}
           visited #{}
           ;; Accumulate: fn-id -> resolved-args, fn-usage-id -> fn-usage
           all-resolved-args {}
           all-fn-usages {}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        ;; Phase 2: Batch load all data (fns, fn-schemas, arg-schemas)
        (let [all-fn-ids (set (keys all-resolved-args))
              ;; Load all fns
              fns (load-fns-batch ds all-fn-ids)
              ;; Get unique fn-schema-ids
              fn-schema-ids (->> (vals fns)
                                 (map :fn-schema-id)
                                 (set))
              ;; Load all fn-schemas
              fn-schemas (load-fn-schemas-batch ds fn-schema-ids)
              ;; Load all arg-schemas
              arg-schemas (load-arg-schemas-batch ds fn-schema-ids)]
          (sp/->execution-graph
            {:fns fns
             :fn-schemas fn-schemas
             :arg-schemas arg-schemas
             :resolved-args all-resolved-args
             :fn-usages all-fn-usages}))
        ;; Process batch of pending fn-ids
        (let [batch (vec to-visit)
              new-visited (into visited batch)
              ;; Load arg-values for each fn in the batch
              resolved-args-batch (into {}
                                        (map (fn [fid]
                                               (let [arg-values (load-arg-values-for-fn ds fid)]
                                                 [fid (arg-values-to-map arg-values)])))
                                        batch)
              ;; Extract all potential refs (UUIDs)
              all-potential-refs (->> (vals resolved-args-batch)
                                      (mapcat sp/extract-uuid-refs-from-arg-values)
                                      (set))
              ;; Remove already visited
              new-candidates (set/difference all-potential-refs new-visited)
              ;; Classify refs AND load fn-usages in one query
              {:keys [fn-ids fn-usages]}
              (classify-and-load-refs ds new-candidates)
              ;; Add fn-ids from fn-usages to visit set
              fu-fn-ids (set (map :fn-id (vals fn-usages)))
              ;; Combine direct fn refs + fn refs from fn-usages
              all-new-fn-refs (set/union fn-ids (set/difference fu-fn-ids new-visited))]
          (recur all-new-fn-refs
                 new-visited
                 (merge all-resolved-args resolved-args-batch)
                 (merge all-fn-usages fn-usages)
                 (+ iter-count (count batch))))))))
