(ns graphden.postgres-storage.constraints
  "GraphConstraints implementation for PostgreSQL storage.
   Validates graph integrity constraints using SQL queries.
   Uses shared validation logic from storage-protocol."
  (:require
    [graphden.storage-protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(def ^:private query-timeout-seconds
  "Default timeout for constraint queries (in seconds)."
  30)


;; === ConstraintHelpers implementation for PostgreSQL ===

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
                                  :timeout query-timeout-seconds})]
      (:fn_schema_id row)))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (let [query (sql/format {:select [:fn_schema_id]
                             :from [:arg_schema]
                             :where [:= :id arg-schema-id]}
                            {:quoted true})
          row (jdbc/execute-one! ds query
                                 {:builder-fn rs/as-unqualified-lower-maps
                                  :timeout query-timeout-seconds})]
      (:fn_schema_id row)))


  (get-parent-fn-id
    [_this fn-id]
    (let [query (sql/format {:select [:parent_fn_id]
                             :from [:fn]
                             :where [:= :id fn-id]}
                            {:quoted true})
          row (jdbc/execute-one! ds query
                                 {:builder-fn rs/as-unqualified-lower-maps
                                  :timeout query-timeout-seconds})]
      (:parent_fn_id row)))


  (collect-parent-chain
    [this fn-id]
    (let [parent-id (sp/get-parent-fn-id this fn-id)]
      (if-not parent-id
        #{}
        ;; Use recursive CTE to collect all ancestors
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
                                   :timeout query-timeout-seconds})]
          (set (map :id rows))))))


  (collect-arg-schema-ids-in-chain
    [this fn-id]
    (let [ancestor-ids (sp/collect-parent-chain this fn-id)]
      (if (empty? ancestor-ids)
        #{}
        (let [query (sql/format {:select [:arg_schema_id]
                                 :from [:arg_value]
                                 :where [:in :owner_fn_id (vec ancestor-ids)]}
                                {:quoted true})
              rows (jdbc/execute! ds query
                                  {:builder-fn rs/as-unqualified-lower-maps
                                   :timeout query-timeout-seconds})]
          (set (map :arg_schema_id rows))))))


  (collect-dependency-chain
    [_this owner-fn-id]
    ;; Use recursive CTE to traverse dependencies
    ;; arg_value.value is JSONB, refs are stored as UUIDs
    ;; Note: JSONB operators (#>>) require raw SQL
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
                               :timeout query-timeout-seconds})]
      (set (map :fn_id rows)))))


(defn create-helpers
  "Creates a ConstraintHelpers instance for PostgreSQL."
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
