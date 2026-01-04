(ns graphden.postgres-storage.constraints
  "GraphConstraints implementation for PostgreSQL storage.
   Validates graph integrity constraints using SQL queries.
   Uses shared validation logic from storage-protocol."
  (:require
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


;; Use shared timeout utility from util.clj
(def ^:private get-query-timeout util/get-query-timeout-seconds)


;; === ConstraintHelpers implementation for PostgreSQL ===
;;
;; This record implements the ConstraintHelpers protocol for PostgreSQL.
;; It uses optimized SQL queries with recursive CTEs to minimize database
;; round-trips when validating graph constraints (parent chains, cycles, etc.).
;;
;; Key optimizations:
;; - collect-parent-chain: Uses recursive CTE instead of N queries
;; - collect-arg-schema-ids-in-chain: Single CTE + JOIN instead of N+1 queries
;; - collect-dependency-chain: Recursive CTE with automatic cycle prevention via UNION

(defrecord PostgresConstraintHelpers
  [ds]

  sp/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (let [query (sql/format {:select [:fn_schema_id]
                             :from [:fn]
                             :where [:= :id fn-id]}
                            {:quoted true})
          row (jdbc/execute-one! ds query
                                 {:builder-fn rs/as-unqualified-lower-maps
                                  :timeout (get-query-timeout)})]
      (:fn_schema_id row)))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (let [query (sql/format {:select [:fn_schema_id]
                             :from [:arg_schema]
                             :where [:= :id arg-schema-id]}
                            {:quoted true})
          row (jdbc/execute-one! ds query
                                 {:builder-fn rs/as-unqualified-lower-maps
                                  :timeout (get-query-timeout)})]
      (:fn_schema_id row)))


  (get-parent-fn-id
    [_this fn-id]
    (let [query (sql/format {:select [:parent_fn_id]
                             :from [:fn]
                             :where [:= :id fn-id]}
                            {:quoted true})
          row (jdbc/execute-one! ds query
                                 {:builder-fn rs/as-unqualified-lower-maps
                                  :timeout (get-query-timeout)})]
      (:parent_fn_id row)))


  (collect-parent-chain
    [this fn-id]
    (let [parent-id (sp/get-parent-fn-id this fn-id)]
      (if-not parent-id
        #{}
        ;; Use recursive CTE to collect all ancestors in the parent-fn-id chain.
        ;;
        ;; SQL equivalent:
        ;; WITH RECURSIVE ancestors AS (
        ;;   -- Base case: start with the direct parent
        ;;   SELECT id, parent_fn_id FROM fn WHERE id = <parent-id>
        ;;   UNION ALL
        ;;   -- Recursive case: follow parent_fn_id links up the chain
        ;;   SELECT f.id, f.parent_fn_id FROM fn f
        ;;   JOIN ancestors a ON f.id = a.parent_fn_id
        ;; )
        ;; SELECT id FROM ancestors WHERE id <> <fn-id>
        ;;
        ;; The UNION ALL is used because we need all rows (no duplicates possible
        ;; in a tree structure). The WHERE clause excludes fn-id itself from results.
        (let [query (sql/format
                      {:with-recursive
                       [[:ancestors
                         {:union-all
                          [{:select [:id :parent_fn_id]
                            :from [:fn]
                            :where [:= :id parent-id]}
                           {:select [:f.id :f.parent_fn_id]
                            :from [[:fn :f]]
                            :join [[:ancestors :a] [:= :f.id :a.parent_fn_id]]}]}]]
                       :select [:id]
                       :from [:ancestors]
                       :where [:<> :id fn-id]}
                      {:quoted true})
              rows (jdbc/execute! ds query
                                  {:builder-fn rs/as-unqualified-lower-maps
                                   :timeout (get-query-timeout)})]
          (set (map :id rows))))))


  (collect-arg-schema-ids-in-chain
    [this fn-id]
    ;; Optimized: single query using CTE to collect parent chain and arg-schema-ids
    ;; in one database round-trip instead of N+1 queries.
    ;;
    ;; SQL equivalent:
    ;; WITH RECURSIVE ancestors AS (
    ;;   -- Same recursive CTE as collect-parent-chain
    ;;   SELECT id, parent_fn_id FROM fn WHERE id = <parent-id>
    ;;   UNION ALL
    ;;   SELECT f.id, f.parent_fn_id FROM fn f
    ;;   JOIN ancestors a ON f.id = a.parent_fn_id
    ;; )
    ;; -- Join with arg_value to get all arg-schema-ids defined in the chain
    ;; SELECT DISTINCT av.arg_schema_id
    ;; FROM arg_value av
    ;; JOIN ancestors anc ON av.owner_fn_id = anc.id
    ;;
    ;; This query finds all arg-schema-ids that are already defined in any
    ;; ancestor function, used to prevent re-definition at lower levels.
    (let [parent-id (sp/get-parent-fn-id this fn-id)]
      (if-not parent-id
        #{}
        (let [query (sql/format
                      {:with-recursive
                       [[:ancestors
                         {:union-all
                          [{:select [:id :parent_fn_id]
                            :from [:fn]
                            :where [:= :id parent-id]}
                           {:select [:f.id :f.parent_fn_id]
                            :from [[:fn :f]]
                            :join [[:ancestors :a] [:= :f.id :a.parent_fn_id]]}]}]]
                       :select-distinct [:av.arg_schema_id]
                       :from [[:arg_value :av]]
                       :join [[:ancestors :anc] [:= :av.owner_fn_id :anc.id]]}
                      {:quoted true})
              rows (jdbc/execute! ds query
                                  {:builder-fn rs/as-unqualified-lower-maps
                                   :timeout (get-query-timeout)})]
          (set (map :arg_schema_id rows))))))


  (collect-dependency-chain
    [_this owner-fn-id]
    ;; Use recursive CTE to traverse all function dependencies through arg_value.
    ;; This is used for cycle detection when adding new dependencies.
    ;;
    ;; SQL equivalent:
    ;; WITH RECURSIVE deps AS (
    ;;   -- Base case: start with the owner function
    ;;   SELECT <owner-fn-id>::uuid AS fn_id
    ;;   UNION
    ;;   -- Recursive case: follow references stored in arg_value.value
    ;;   SELECT (av.value #>> '{}')::uuid
    ;;   FROM deps d
    ;;   JOIN arg_value av ON av.owner_fn_id = d.fn_id
    ;;   WHERE av.value #>> '{}' IS NOT NULL
    ;;     AND EXISTS (SELECT 1 FROM fn WHERE id = (av.value #>> '{}')::uuid)
    ;; )
    ;; SELECT DISTINCT fn_id FROM deps
    ;;
    ;; Notes:
    ;; - UNION (not UNION ALL) is used to prevent infinite loops by
    ;;   automatically deduplicating already-visited nodes
    ;; - arg_value.value is JSONB; #>> '{}' extracts the root value as text
    ;; - The EXISTS check ensures we only follow valid fn references
    ;; - The cast to uuid converts the text representation to UUID type
    (let [query (sql/format
                  {:with-recursive
                   [[:deps
                     {:union
                      [;; Base case: start with owner-fn-id
                       {:select [[[:cast owner-fn-id :uuid] :fn_id]]}
                       ;; Recursive case: follow fn references in arg_values
                       {:select [[[:cast [:raw "av.value #>> '{}'"] :uuid]]]
                        :from [[:deps :d]]
                        :join [[:arg_value :av] [:= :av.owner_fn_id :d.fn_id]]
                        :where [:and
                                [:is-not [:raw "av.value #>> '{}'"] nil]
                                [:exists {:select [[[:raw "1"]]]
                                          :from [:fn]
                                          :where [:= :id [:cast [:raw "av.value #>> '{}'"] :uuid]]}]]}]}]]
                   :select-distinct [:fn_id]
                   :from [:deps]}
                  {:quoted true})
          rows (jdbc/execute! ds query
                              {:builder-fn rs/as-unqualified-lower-maps
                               :timeout (get-query-timeout)})]
      (set (map :fn_id rows)))))


(defn create-helpers
  "Creates a ConstraintHelpers instance for PostgreSQL.
   The helpers instance provides optimized SQL queries for constraint validation.

   Parameters:
   - ds: A JDBC datasource (connection pool or connection)

   Returns:
   A PostgresConstraintHelpers record implementing the ConstraintHelpers protocol."
  [ds]
  (->PostgresConstraintHelpers ds))


;; === Validation functions using shared implementations ===

(defn validate-parent-same-schema!
  "Validates that parent-fn has the same fn-schema-id as fn.
   Throws :constraint-violation/parent-schema-mismatch on violation."
  [ds fn-id parent-fn-id]
  (sp/validate-parent-same-schema-impl (create-helpers ds) fn-id parent-fn-id))


(defn validate-no-arg-override!
  "Validates that arg-schema-id is not already defined in the parent chain.
   Throws :constraint-violation/arg-already-defined on violation."
  [ds fn-id arg-schema-id]
  (sp/validate-no-arg-override-impl (create-helpers ds) fn-id arg-schema-id))


(defn validate-arg-schema-belongs-to-fn!
  "Validates that arg-schema belongs to fn's fn-schema.
   Throws :constraint-violation/arg-schema-mismatch on violation."
  [ds fn-id arg-schema-id]
  (sp/validate-arg-schema-belongs-to-fn-impl (create-helpers ds) fn-id arg-schema-id))


(defn validate-no-inheritance-cycle!
  "Validates that setting parent-fn-id would not create an inheritance cycle.
   Throws :constraint-violation/inheritance-cycle on violation."
  [ds fn-id parent-fn-id]
  (sp/validate-no-inheritance-cycle-impl (create-helpers ds) fn-id parent-fn-id))


(defn validate-no-dependency-cycle!
  "Validates that referencing value-fn-id would not create a dependency cycle.
   Throws :constraint-violation/dependency-cycle on violation."
  [ds owner-fn-id value-fn-id]
  (sp/validate-no-dependency-cycle-impl (create-helpers ds) owner-fn-id value-fn-id))
