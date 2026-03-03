(ns graphden.storage.postgres.graph
  "ExecutionGraph resolution for PostgreSQL storage.
   Uses recursive CTE for O(1) round-trip graph traversal.

   ## Performance

   This implementation uses a single recursive CTE to discover all fn-ids
   in the dependency graph, then batch-loads all related data.

   Total queries: 4-5 (constant) regardless of graph depth.
   - 1 CTE query for graph discovery
   - 1 query for fns
   - 1 query for fn-schemas
   - 1 query for arg-schemas
   - 1 query for arg-values"
  (:require
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


;; === Recursive CTE for graph traversal ===
;;
;; This implementation uses a single recursive CTE to discover all fn-ids
;; in the dependency graph, then batch-loads all related data.
;;
;; Complexity: O(1) round-trips (3-4 queries total) instead of O(depth)

(def ^:private uuid-regex
  "Regex pattern for UUID validation in PostgreSQL."
  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


(defn- discover-graph-cte
  "Discovers all fn-ids and fn-usages in the dependency graph using recursive CTE.
   Returns {:fn-ids #{...} :fn-usages {fn-usage-id -> {:id ... :fn-id ...}}}.

   This is a SINGLE database query that traverses the entire graph.
   The CTE recursively follows UUID references in arg_value.value fields."
  [ds root-fn-id]
  (let [;; Recursive CTE query that discovers all fn-ids and fn-usage refs
        ;;
        ;; Structure:
        ;; 1. fn_graph: recursively find all fn-ids (direct references)
        ;; 2. fu_refs: find fn_usage references and their target fn-ids
        ;; 3. combined: union all discovered fn-ids
        ;; Note: av.value is JSONB, so we extract text using #>>'{}'
        ;; This handles JSON strings like "uuid" → uuid
        query [(str
                 "WITH RECURSIVE fn_graph AS ("
                 ;; Base case: root function
                 "  SELECT id, 0 as depth FROM fn WHERE id = ? "
                 "  UNION "
                 ;; Recursive case: functions referenced in arg_values
                 "  SELECT DISTINCT f.id, g.depth + 1 "
                 "  FROM fn_graph g "
                 "  JOIN fn_arg fa ON fa.fn_id = g.id "
                 "  JOIN arg_value av ON av.id = fa.arg_value_id "
                 "  JOIN fn f ON f.id = CASE "
                 "    WHEN (av.value#>>'{}') ~ '" uuid-regex "' THEN (av.value#>>'{}')::uuid "
                 "    ELSE NULL END "
                 "  WHERE g.depth < ? "  ;; depth limit for safety
                 "), "
                 ;; Find fn_usage references (indirect fn references)
                 "fu_refs AS ("
                 "  SELECT DISTINCT fu.id as fu_id, fu.fn_id "
                 "  FROM fn_graph g "
                 "  JOIN fn_arg fa ON fa.fn_id = g.id "
                 "  JOIN arg_value av ON av.id = fa.arg_value_id "
                 "  JOIN fn_usage fu ON fu.id = CASE "
                 "    WHEN (av.value#>>'{}') ~ '" uuid-regex "' THEN (av.value#>>'{}')::uuid "
                 "    ELSE NULL END "
                 "), "
                 ;; Get all fn-ids from fn_usages that aren't already in fn_graph
                 "fu_fn_ids AS ("
                 "  SELECT DISTINCT fn_id as id FROM fu_refs "
                 "  WHERE fn_id NOT IN (SELECT id FROM fn_graph) "
                 ") "
                 ;; Return all data
                 "SELECT 'fn' as entity_type, id, NULL::uuid as fn_id FROM fn_graph "
                 "UNION ALL "
                 "SELECT 'fn' as entity_type, id, NULL::uuid as fn_id FROM fu_fn_ids "
                 "UNION ALL "
                 "SELECT 'fu' as entity_type, fu_id as id, fn_id FROM fu_refs")
               root-fn-id
               sp/*max-graph-iterations*]]
    (util/with-sql-error-handling "Database error" :discover-graph-cte {:fn-id root-fn-id}
      (let [rows (jdbc/execute! ds query (util/query-opts))]
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
          rows)))))


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


(defn- load-arg-values-for-fns-batch
  "Loads all arg-values for multiple fn-ids in a single query.
   Returns {fn-id -> {arg-schema-id -> arg-value}}."
  [ds fn-ids]
  (if (empty? fn-ids)
    {}
    (let [fn-ids-vec (vec fn-ids)
          query [(str "SELECT fa.fn_id, av.* "
                      "FROM fn_arg fa "
                      "JOIN arg_value av ON av.id = fa.arg_value_id "
                      "WHERE fa.fn_id = ANY(?)")
                 (into-array java.util.UUID fn-ids-vec)]]
      (util/with-sql-error-handling "Database error" :load-arg-values-batch {:fn-count (count fn-ids)}
        (let [rows (jdbc/execute! ds query (util/query-opts))]
          (->> rows
               (group-by :fn_id)
               (reduce-kv
                 (fn [acc fn-id rows-for-fn]
                   (assoc acc fn-id
                          (->> rows-for-fn
                               (map (fn [row]
                                      (codec/row->entity (dissoc row :fn_id))))
                               (map (juxt :arg-schema-id identity))
                               (into {}))))
                 (zipmap fn-ids-vec (repeat {})))))))))


(defn resolve-execution-graph
  "Resolves complete execution graph for a function.
   Uses recursive CTE for O(1) round-trip graph traversal.

   Query strategy:
   1. Single CTE query discovers all fn-ids and fn-usages in the graph
   2. Batch load: fns, fn-schemas, arg-schemas, arg-values

   Total: 4-5 queries regardless of graph depth (was O(depth) queries)."
  [ds fn-id]
  ;; Phase 1: Discover all fn-ids using recursive CTE (1 query)
  (let [{:keys [fn-ids fn-usages]} (discover-graph-cte ds fn-id)]
    (when (empty? fn-ids)
      (throw (ex-info "Function not found"
                      {:type :not-found
                       :fn-id fn-id})))
    ;; Phase 2: Batch load all data (3-4 queries)
    (let [;; Load all fns
          fns (load-fns-batch ds fn-ids)
          ;; Get unique fn-schema-ids
          fn-schema-ids (->> (vals fns)
                             (map :fn-schema-id)
                             (set))
          ;; Load all fn-schemas
          fn-schemas (load-fn-schemas-batch ds fn-schema-ids)
          ;; Load all arg-schemas
          arg-schemas (load-arg-schemas-batch ds fn-schema-ids)
          ;; Load all arg-values for all fns
          resolved-args (load-arg-values-for-fns-batch ds fn-ids)]
      (sp/->execution-graph
        {:fns fns
         :fn-schemas fn-schemas
         :arg-schemas arg-schemas
         :resolved-args resolved-args
         :fn-usages fn-usages}))))
