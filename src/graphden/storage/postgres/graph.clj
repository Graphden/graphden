(ns graphden.storage.postgres.graph
  "ExecutionGraph resolution for PostgreSQL storage.
   Uses recursive CTE for O(1) round-trip graph traversal.

   ## Performance

   This implementation uses a single recursive CTE to discover all fn-ids
   in the dependency graph, then batch-loads all related data.

   Total queries: 2-3 (constant) regardless of graph depth.
   - 1 CTE query for graph discovery
   - 1 query for fns
   - 1 query for args

   ## Schema Design

   Simplified 2-entity model:
   - fn: function entity (base or composed via parent-id)
   - arg: argument entity (owns value or references fn via ref-id)

   Dependencies are found via:
   - arg.ref-id: direct fn reference (execute and use result)
   - fn.parent-id: inheritance from parent fn
   - arg.value with UUID: fn-id passed as value (for HOF)"
  (:require
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


;; =============================================================================
;; Recursive CTE for graph traversal
;; =============================================================================
;;
;; Complexity: O(1) round-trips (2-3 queries total) instead of O(depth)

(defn- build-graph-discovery-sql
  "Builds raw SQL for recursive CTE graph discovery.

   Structure:
   1. fn_graph: recursively find all fn-ids via arg.ref-id and fn.parent-id

   Three paths to find dependent fns:
   - arg.ref-id: FK to fn (for computed values)
   - fn.parent-id: FK to parent fn (for inheritance)
   - arg.value with UUID: extract UUID from value JSONB (for HOF references)

   Uses LATERAL UNNEST to generate multiple rows when a fn has multiple
   references (e.g., both ref-id and parent-id), ensuring all paths are followed."
  [root-fn-id max-depth]
  [(str "WITH RECURSIVE fn_graph AS ("
        ;; Base case: root function
        "  SELECT id, 0 AS depth FROM fn WHERE id = ?"
        "  UNION ALL"
        ;; Recursive term: LATERAL UNNEST expands multiple refs into rows
        "  SELECT DISTINCT refs.ref_id, g.depth + 1"
        "  FROM fn_graph g"
        "  JOIN fn fn_current ON fn_current.id = g.id"
        "  LEFT JOIN arg a ON a.fn_id = g.id,"
        "  LATERAL (SELECT unnest(ARRAY["
        "    a.ref_id,"
        "    fn_current.parent_id,"
        "    CASE WHEN a.value IS NOT NULL"
        "         AND jsonb_typeof(a.value) = 'string'"
        "         AND (a.value #>> '{}') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'"
        "         THEN CAST(a.value #>> '{}' AS uuid)"
        "         ELSE NULL END"
        "  ]) AS ref_id) refs"
        "  WHERE g.depth < ? AND refs.ref_id IS NOT NULL"
        ") SELECT DISTINCT id FROM fn_graph")
   root-fn-id max-depth])


(defn- discover-graph-cte
  "Discovers all fn-ids in the dependency graph using recursive CTE.
   Returns set of fn-ids.

   This is a SINGLE database query that traverses the entire graph.
   The CTE follows:
   - arg.ref-id FK references
   - fn.parent-id inheritance
   - UUID values in arg.value"
  [ds root-fn-id]
  (let [query (build-graph-discovery-sql root-fn-id sp/*max-graph-iterations*)]
    (util/with-sql-error-handling "Database error" :discover-graph-cte {:fn-id root-fn-id}
                                  (let [rows (jdbc/execute! ds query (util/query-opts))]
                                    (into #{} (map :id) rows)))))


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
                                      ;; Single pass: decode + build map in one traversal
                                      (into {} (map #(let [e (codec/row->entity %)] [(:id e) e])) rows))))))


(defn- load-fns-batch
  "Loads multiple fns by id. Returns {fn-id -> fn-record}."
  [ds fn-ids]
  (load-entities-batch ds :fn :id fn-ids))


(defn- load-args-for-fns-batch
  "Loads all args for multiple fn-ids in a single query.
   Returns vector of arg records."
  [ds fn-ids]
  (if (empty? fn-ids)
    []
    (let [fn-ids-vec (vec fn-ids)
          query (sql/format {:select [:*]
                             :from [:arg]
                             :where [:in :fn_id fn-ids-vec]})]
      (util/with-sql-error-handling "Database error" :load-args-batch {:fn-count (count fn-ids)}
                                    (let [rows (jdbc/execute! ds query (util/query-opts))]
                                      (mapv codec/row->entity rows))))))


(defn resolve-execution-graph
  "Resolves complete execution graph for a function.
   Uses recursive CTE for O(1) round-trip graph traversal.

   Query strategy:
   1. Single CTE query discovers all fn-ids in the graph
   2. Batch load: fns, args

   Total: 2-3 queries regardless of graph depth."
  [ds fn-id]
  ;; Phase 1: Discover all fn-ids using recursive CTE (1 query)
  (let [fn-ids (discover-graph-cte ds fn-id)]
    (when (empty? fn-ids)
      (throw (ex-info "Function not found"
                      {:type :not-found
                       :fn-id fn-id})))
    ;; Phase 2: Batch load all data (2 queries)
    (let [fns (load-fns-batch ds fn-ids)
          args (load-args-for-fns-batch ds fn-ids)]
      (sp/->execution-graph
        {:fns fns
         :args args}))))
