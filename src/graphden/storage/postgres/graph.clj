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
   - 1 query for arg-values

   ## Schema Design

   arg_value has two mutually exclusive fields (XOR constraint):
   - value: JSONB literal value
   - fn_usage_id: FK to fn_usage (execute and use result)

   This allows direct JOINs without regex-based UUID extraction."
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
;; Complexity: O(1) round-trips (4-5 queries total) instead of O(depth)

(defn- build-graph-discovery-query
  "Builds HoneySQL map for recursive CTE graph discovery.

   Structure:
   1. fn_graph: recursively find all fn-ids via fn_usage and :fn type arg references
   2. fu_refs: find all fn_usage references from discovered fns

   Two paths to find dependent fns (handled via LEFT JOINs in single recursive term):
   - arg_value.fn_usage_id: FK to fn_usage -> fn_usage.fn_id (for computed values)
   - arg_schema.type = 'fn': extract UUID from arg_value.value JSONB (for HOF references)"
  [root-fn-id max-depth]
  {:with-recursive
   [;; Base case + single recursive term using LEFT JOINs for both paths
    [:fn_graph
     {:union-all
      [;; Base case: root function
       {:select [:id [[:inline 0] :depth]]
        :from [:fn]
        :where [:= :id root-fn-id]}
       ;; Recursive term: handles both fn_usage and :fn type paths via LEFT JOINs
       {:select-distinct
        [[[:coalesce :f_fu.id :f_fn.id] :id]
         [[:+ :g.depth [:inline 1]] :depth]]
        :from [[:fn_graph :g]]
        :join [[:fn_arg :fa] [:= :fa.fn_id :g.id]
               [:arg_value :av] [:= :av.id :fa.arg_value_id]]
        :left-join [;; Path 1: fn_usage references
                    [:fn_usage :fu] [:= :fu.id :av.fn_usage_id]
                    [:fn :f_fu] [:= :f_fu.id :fu.fn_id]
                    ;; Path 2: :fn type arg values (need parent fn for schema lookup)
                    [:fn :fn_parent] [:= :fn_parent.id :g.id]
                    [:arg_schema :as_] [:and
                                        [:= :as_.fn_schema_id :fn_parent.fn_schema_id]
                                        [:= :as_.id :av.arg_schema_id]
                                        [:= :as_.type [:inline "fn"]]]
                    ;; Only cast to UUID when arg_schema.type = 'fn' (via as_.id check)
                    ;; Use CASE to avoid casting non-UUID values
                    [:fn :f_fn] [:= :f_fn.id
                                 [[:raw "CASE WHEN as_.id IS NOT NULL AND av.value IS NOT NULL
                                              THEN CAST(trim(both '\"' from (av.value)::text) AS uuid)
                                              ELSE NULL END"]]]]
        :where [:and
                [:< :g.depth max-depth]
                [:or
                 [:is-not :f_fu.id nil]
                 [:is-not :f_fn.id nil]]]}]}]

    ;; All fn_usage references from any fn in fn_graph
    [:fu_refs
     {:select-distinct [[:fu.id :fu_id] :fu.fn_id]
      :from [[:fn_graph :g]]
      :join [[:fn_arg :fa] [:= :fa.fn_id :g.id]
             [:arg_value :av] [:= :av.id :fa.arg_value_id]
             [:fn_usage :fu] [:= :fu.id :av.fn_usage_id]]}]]

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
   The CTE follows FK references via arg_value.fn_usage_id."
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
