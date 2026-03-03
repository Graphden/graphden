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
    [graphden.storage.protocol.graph :as spg]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


;; =============================================================================
;; PostgreSQL-specific HoneySQL helpers
;; =============================================================================

(defn- uuid-from-jsonb
  "Extracts UUID from JSONB if it matches UUID regex, else NULL.
   Pattern: CASE WHEN (col#>>'{}') ~ 'uuid-regex' THEN (col#>>'{}')::uuid ELSE NULL END"
  [col]
  [:raw (str "CASE WHEN (" (name col) "#>>'{}') ~ '" spg/uuid-regex-pattern
             "' THEN (" (name col) "#>>'{}')::uuid ELSE NULL END")])


;; =============================================================================
;; Recursive CTE for graph traversal
;; =============================================================================
;;
;; Complexity: O(1) round-trips (4-5 queries total) instead of O(depth)

(defn- build-graph-discovery-query
  "Builds HoneySQL map for recursive CTE graph discovery.

   Structure:
   1. fn_graph: recursively find all fn-ids (direct AND indirect via fn_usage)
   2. fu_refs: find all fn_usage references from discovered fns

   The key insight is that the recursive CTE must follow BOTH:
   - Direct fn references: arg_value.value -> fn.id
   - Indirect fn references: arg_value.value -> fn_usage.id -> fn_usage.fn_id

   PostgreSQL only allows ONE recursive term, so we combine both cases using
   LEFT JOINs and COALESCE to pick the matching fn_id."
  [root-fn-id max-depth]
  {:with-recursive
   [;; Base case + single recursive case handling both direct and indirect refs
    [:fn_graph
     {:union
      [;; Base case: root function
       {:select [:id [[:inline 0] :depth]]
        :from [:fn]
        :where [:= :id root-fn-id]}
       ;; Recursive case: handles BOTH direct fn refs AND indirect via fn_usage
       ;; Uses LEFT JOINs to try both paths, COALESCE picks whichever matched
       {:select-distinct
        [[[:raw "COALESCE(f_direct.id, f_via_fu.id)"]]
         [[:+ :g.depth [:inline 1]] :depth]]
        :from [[:fn_graph :g]]
        :join [[:fn_arg :fa] [:= :fa.fn_id :g.id]
               [:arg_value :av] [:= :av.id :fa.arg_value_id]]
        :left-join [;; Direct path: arg_value.value = fn.id
                    [:fn :f_direct] [:= :f_direct.id (uuid-from-jsonb :av.value)]
                    ;; Indirect path: arg_value.value = fn_usage.id -> fn_usage.fn_id
                    [:fn_usage :fu] [:= :fu.id (uuid-from-jsonb :av.value)]
                    [:fn :f_via_fu] [:= :f_via_fu.id :fu.fn_id]]
        :where [:and
                [:< :g.depth max-depth]
                ;; At least one path must match
                [:or [:is-not :f_direct.id nil] [:is-not :f_via_fu.id nil]]]}]}]

    ;; All fn_usage references from any fn in fn_graph
    [:fu_refs
     {:select-distinct [[:fu.id :fu_id] :fu.fn_id]
      :from [[:fn_graph :g]]
      :join [[:fn_arg :fa] [:= :fa.fn_id :g.id]
             [:arg_value :av] [:= :av.id :fa.arg_value_id]
             [:fn_usage :fu] [:= :fu.id (uuid-from-jsonb :av.value)]]}]]

   ;; Final select: union all results
   :union-all
   [{:select [[[:inline "fn"] :entity_type] :id [[:cast nil :uuid] :fn_id]]
     :from [:fn_graph]}
    {:select [[[:inline "fu"] :entity_type] [:fu_id :id] :fn_id]
     :from [:fu_refs]}]})


(defn- discover-graph-cte
  "Discovers all fn-ids and fn-usages in the dependency graph using recursive CTE.
   Returns {:fn-ids #{...} :fn-usages {fn-usage-id -> {:id ... :fn-id ...}}}.

   This is a SINGLE database query that traverses the entire graph.
   The CTE recursively follows UUID references in arg_value.value fields."
  [ds root-fn-id]
  (let [query-map (build-graph-discovery-query root-fn-id sp/*max-graph-iterations*)
        query (sql/format query-map)]
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
          query (sql/format {:select [:fa.fn_id :av.*]
                             :from [[:fn_arg :fa]]
                             :join [[:arg_value :av] [:= :av.id :fa.arg_value_id]]
                             :where [:in :fa.fn_id fn-ids-vec]})]
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
