(ns graphden.postgres-storage.graph
  "ExecutionGraph resolution for PostgreSQL storage.
   Uses batched queries with recursive CTEs for efficient graph traversal."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.postgres-storage.codec :as codec]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


(defmacro ^:private with-crud-error-handling
  [operation context & body]
  `(util/with-sql-error-handling "Database error" ~operation ~context ~@body))


(defn- row->entity
  [row]
  (when row
    (codec/decode-row row nil)))


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
        (let [rows (jdbc/execute! ds query (util/query-opts))
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
        (let [rows (jdbc/execute! ds query (util/query-opts))]
          (map row->entity rows))))))


(defn- classify-and-load-refs
  "Classifies UUID references and loads fn-result-values in a single query.
   Returns {:fn-ids #{...} :frv-ids #{...} :fn-result-values {...}}.

   Uses UNION ALL to classify refs and load frv data in one round-trip."
  [ds uuid-candidates]
  (if (empty? uuid-candidates)
    {:fn-ids #{} :frv-ids #{} :fn-result-values {}}
    (let [uuids-vec (vec uuid-candidates)
          ;; Combined query: classify refs AND load frv data in one query
          ;; Returns rows with entity_type = 'fn' or 'frv'
          combined-query
          [(str "SELECT 'fn' as entity_type, id, NULL::uuid as fn_id FROM fn WHERE id = ANY(?)"
                " UNION ALL "
                "SELECT 'frv' as entity_type, id, fn_id FROM fn_result_value WHERE id = ANY(?)")
           (into-array java.util.UUID uuids-vec)
           (into-array java.util.UUID uuids-vec)]]
      (with-crud-error-handling :classify-and-load-refs {:candidate-count (count uuid-candidates)}
        (let [rows (jdbc/execute! ds combined-query (util/query-opts))]
          (reduce
            (fn [acc row]
              (case (:entity_type row)
                "fn" (update acc :fn-ids conj (:id row))
                "frv" (-> acc
                          (update :frv-ids conj (:id row))
                          (assoc-in [:fn-result-values (:id row)]
                                    {:id (:id row) :fn-id (:fn_id row)}))
                acc))
            {:fn-ids #{} :frv-ids #{} :fn-result-values {}}
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
      (with-crud-error-handling :load-entities-batch {:table table :count (count values)}
        (let [rows (jdbc/execute! ds query (util/query-opts))]
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


(defn- read-entity
  "Reads single entity by id for initial fn lookup."
  [ds entity-name id]
  (let [table-name (keyword (util/kw->snake-case entity-name))
        query (sql/format {:select [:*] :from [table-name] :where [:= :id id]}
                          {:quoted true})]
    (with-crud-error-handling :read-entity {:entity-name entity-name :id id}
      (-> (jdbc/execute-one! ds query (util/query-opts))
          row->entity))))


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
           ;; Accumulate: fn-id -> parent-chain, fn-id -> merged-args, frv-id -> frv
           all-chains {}
           all-merged-args {}
           all-fn-result-values {}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        ;; Phase 2: Batch load all data (fns, fn-schemas, arg-schemas)
        ;; Note: fn-result-values are already loaded during discovery
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
              arg-schemas (load-arg-schemas-batch ds fn-schema-ids)]
          (sp/->execution-graph
            {:fns fns
             :fn-schemas fn-schemas
             :arg-schemas arg-schemas
             :resolved-args all-merged-args
             :fn-result-values all-fn-result-values}))
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
              ;; Classify refs AND load fn-result-values in one query (optimization)
              {:keys [fn-ids fn-result-values]}
              (classify-and-load-refs ds new-candidates)
              ;; Add fn-ids from fn-result-values to visit set
              frv-fn-ids (set (map :fn-id (vals fn-result-values)))
              ;; Combine direct fn refs + fn refs from fn-result-values
              all-new-fn-refs (set/union fn-ids (set/difference frv-fn-ids new-visited))]
          (recur all-new-fn-refs
                 new-visited
                 (merge all-chains chains)
                 (merge all-merged-args merged-args-batch)
                 (merge all-fn-result-values fn-result-values)
                 (+ iter-count (count batch))))))))
