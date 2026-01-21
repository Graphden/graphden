(ns graphden.postgres-storage.constraints
  "GraphConstraints implementation for PostgreSQL storage.
   Validates graph integrity constraints using SQL queries.
   Uses shared validation logic from storage-protocol."
  (:require
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


;; === ConstraintHelpers implementation for PostgreSQL ===
;;
;; This record implements the ConstraintHelpers protocol for PostgreSQL.
;; It uses optimized SQL queries with recursive CTEs to minimize database
;; round-trips when validating graph constraints (dependency cycles, etc.).
;;
;; Key optimizations:
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
                                 (util/query-opts))]
      (:fn_schema_id row)))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (let [query (sql/format {:select [:fn_schema_id]
                             :from [:arg_schema]
                             :where [:= :id arg-schema-id]}
                            {:quoted true})
          row (jdbc/execute-one! ds query
                                 (util/query-opts))]
      (:fn_schema_id row)))


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
                              (util/query-opts))]
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

(defn validate-arg-schema-belongs-to-fn!
  [ds fn-id arg-schema-id]
  (sp/validate-arg-schema-belongs-to-fn-impl (create-helpers ds) fn-id arg-schema-id))


(defn validate-no-dependency-cycle!
  [ds owner-fn-id value-fn-id]
  (sp/validate-no-dependency-cycle-impl (create-helpers ds) owner-fn-id value-fn-id))
