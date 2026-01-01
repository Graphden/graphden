(ns graphden.postgres-storage.constraints
  "GraphConstraints implementation for PostgreSQL storage.
   Validates graph integrity constraints using SQL queries."
  (:require
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(def ^:private query-timeout-seconds
  "Default timeout for constraint queries (in seconds)."
  30)


(defn- get-fn-schema-id
  "Gets fn-schema-id for a fn record."
  [ds fn-id]
  (let [query (sql/format {:select [:fn_schema_id]
                           :from [:fn]
                           :where [:= :id fn-id]}
                          {:quoted true})
        row (jdbc/execute-one! ds query
                               {:builder-fn rs/as-unqualified-lower-maps
                                :timeout query-timeout-seconds})]
    (:fn_schema_id row)))


(defn- get-parent-fn-id
  "Gets parent-fn-id for a fn record."
  [ds fn-id]
  (let [query (sql/format {:select [:parent_fn_id]
                           :from [:fn]
                           :where [:= :id fn-id]}
                          {:quoted true})
        row (jdbc/execute-one! ds query
                               {:builder-fn rs/as-unqualified-lower-maps
                                :timeout query-timeout-seconds})]
    (:parent_fn_id row)))


(defn- get-arg-schema-fn-schema-id
  "Gets fn-schema-id for an arg-schema record."
  [ds arg-schema-id]
  (let [query (sql/format {:select [:fn_schema_id]
                           :from [:arg_schema]
                           :where [:= :id arg-schema-id]}
                          {:quoted true})
        row (jdbc/execute-one! ds query
                               {:builder-fn rs/as-unqualified-lower-maps
                                :timeout query-timeout-seconds})]
    (:fn_schema_id row)))


(defn- collect-parent-chain
  "Collects all ancestor fn-ids by following parent-fn-id links.
   Uses recursive CTE for efficient traversal.
   Returns a set of fn-ids (not including the starting fn-id)."
  [ds fn-id]
  (let [;; Get the initial parent
        parent-id (get-parent-fn-id ds fn-id)]
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


(defn- collect-arg-schema-ids-in-chain
  "Collects all arg-schema-ids defined in the parent chain (not including fn-id itself).
   Returns a set of arg-schema-ids."
  [ds fn-id]
  (let [ancestor-ids (collect-parent-chain ds fn-id)]
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


(defn- collect-dependency-chain
  "Collects all fn-ids that owner-fn depends on through arg-values.
   Uses recursive CTE for DFS traversal of value refs.
   Returns a set of fn-ids including the starting fn-id."
  [ds owner-fn-id]
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
    (set (map :fn_id rows))))


(defn validate-parent-same-schema!
  "Validates that parent-fn has the same fn-schema-id as fn.
   Throws :constraint-violation/parent-schema-mismatch on violation."
  [ds fn-id parent-fn-id]
  (when parent-fn-id
    (let [fn-schema-id (get-fn-schema-id ds fn-id)
          parent-schema-id (get-fn-schema-id ds parent-fn-id)]
      (when (and fn-schema-id parent-schema-id
                 (not= fn-schema-id parent-schema-id))
        (throw (ex-info "Parent fn has different fn-schema-id"
                        {:type :constraint-violation/parent-schema-mismatch
                         :fn-id fn-id
                         :parent-fn-id parent-fn-id
                         :fn-schema-id fn-schema-id
                         :parent-schema-id parent-schema-id}))))))


(defn validate-no-arg-override!
  "Validates that arg-schema-id is not already defined in the parent chain.
   Throws :constraint-violation/arg-already-defined on violation."
  [ds fn-id arg-schema-id]
  (let [parent-arg-schema-ids (collect-arg-schema-ids-in-chain ds fn-id)]
    (when (contains? parent-arg-schema-ids arg-schema-id)
      (throw (ex-info "Argument already defined in parent chain"
                      {:type :constraint-violation/arg-already-defined
                       :fn-id fn-id
                       :arg-schema-id arg-schema-id})))))


(defn validate-arg-schema-belongs-to-fn!
  "Validates that arg-schema belongs to fn's fn-schema.
   Throws :constraint-violation/arg-schema-mismatch on violation."
  [ds fn-id arg-schema-id]
  (let [fn-schema-id (get-fn-schema-id ds fn-id)
        arg-fn-schema-id (get-arg-schema-fn-schema-id ds arg-schema-id)]
    (when (and fn-schema-id arg-fn-schema-id
               (not= fn-schema-id arg-fn-schema-id))
      (throw (ex-info "Arg-schema does not belong to fn's schema"
                      {:type :constraint-violation/arg-schema-mismatch
                       :fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :fn-schema-id fn-schema-id
                       :arg-fn-schema-id arg-fn-schema-id})))))


(defn validate-no-inheritance-cycle!
  "Validates that setting parent-fn-id would not create an inheritance cycle.
   Throws :constraint-violation/inheritance-cycle on violation."
  [ds fn-id parent-fn-id]
  (when parent-fn-id
    ;; Check self-reference
    (when (= fn-id parent-fn-id)
      (throw (ex-info "Cannot set self as parent"
                      {:type :constraint-violation/inheritance-cycle
                       :fn-id fn-id
                       :parent-fn-id parent-fn-id})))
    ;; Check if fn-id appears in parent's ancestor chain
    (let [parent-ancestors (collect-parent-chain ds parent-fn-id)]
      (when (contains? parent-ancestors fn-id)
        (throw (ex-info "Setting parent would create inheritance cycle"
                        {:type :constraint-violation/inheritance-cycle
                         :fn-id fn-id
                         :parent-fn-id parent-fn-id
                         :cycle-through (conj parent-ancestors parent-fn-id)}))))))


(defn validate-no-dependency-cycle!
  "Validates that referencing value-fn-id would not create a dependency cycle.
   Throws :constraint-violation/dependency-cycle on violation."
  [ds owner-fn-id value-fn-id]
  (when value-fn-id
    ;; Check if owner-fn-id is in the dependency chain of value-fn-id
    (let [value-deps (collect-dependency-chain ds value-fn-id)]
      (when (contains? value-deps owner-fn-id)
        (throw (ex-info "Reference would create dependency cycle"
                        {:type :constraint-violation/dependency-cycle
                         :owner-fn-id owner-fn-id
                         :value-fn-id value-fn-id}))))))
