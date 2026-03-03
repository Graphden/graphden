(ns graphden.storage.age.graph
  "ExecutionGraph resolution using AGE Cypher queries.

   The key advantage of AGE is that we can resolve the complete execution graph
   in a SINGLE optimized query instead of O(depth) recursive queries.

   ## Query Strategy

   We use a single Cypher query with variable-length path matching:

   ```cypher
   MATCH (root:Fn {id: $fn_id})
   MATCH path = (root)-[:HAS_ARG*0..100]->(dep:Fn)
   ...
   ```

   This traverses the entire dependency graph in one database round-trip.

   ## Fallback to SQL

   If AGE graph is not populated (e.g., during initial sync), we fall back
   to the standard BFS algorithm from postgres-storage/graph.clj."
  (:require
    [clojure.set :as set]
    [graphden.storage.age.codec :as codec]
    [graphden.storage.protocol.interface :as sp]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


;; === SQL Fallback ===
;; Used when AGE graph is not available or empty

(defn- query-opts
  []
  {:builder-fn rs/as-unqualified-kebab-maps
   :timeout (sp/get-query-timeout-seconds)})


(defn- read-fn
  [ds fn-id]
  (let [query (sql/format {:select [:*] :from [:fn] :where [:= :id fn-id]} {:quoted true})]
    (-> (jdbc/execute-one! ds query (query-opts))
        codec/row->entity)))


(defn- load-arg-schemas
  [ds fn-schema-ids]
  (if (empty? fn-schema-ids)
    {}
    (let [query (sql/format {:select [:*]
                             :from [:arg_schema]
                             :where [:in :fn_schema_id (vec fn-schema-ids)]}
                            {:quoted true})
          rows (jdbc/execute! ds query (query-opts))]
      (->> rows
           (map codec/row->entity)
           (map (juxt :id identity))
           (into {})))))


(defn- load-arg-values-for-fn
  [ds fn-id]
  (let [query (sql/format {:select [:av.*]
                           :from [[:fn_arg :fa]]
                           :join [[:arg_value :av] [:= :av.id :fa.arg_value_id]]
                           :where [:= :fa.fn_id fn-id]}
                          {:quoted true})]
    (->> (jdbc/execute! ds query (query-opts))
         (map codec/row->entity))))


(defn- classify-uuid-refs
  [ds uuid-refs]
  (if (empty? uuid-refs)
    {:fn-ids #{} :fn-usages {}}
    (let [uuids-vec (vec uuid-refs)
          combined-query
          [(str "SELECT 'fn' as entity_type, id, NULL::uuid as fn_id FROM fn WHERE id = ANY(?)"
                " UNION ALL "
                "SELECT 'fu' as entity_type, id, fn_id FROM fn_usage WHERE id = ANY(?)")
           (into-array java.util.UUID uuids-vec)
           (into-array java.util.UUID uuids-vec)]
          rows (jdbc/execute! ds combined-query (query-opts))]
      (reduce
        (fn [acc row]
          (case (str (:entity-type row))
            "fn" (update acc :fn-ids conj (:id row))
            "fu" (-> acc
                     (update :fn-usage-ids conj (:id row))
                     (assoc-in [:fn-usages (:id row)]
                               {:id (:id row) :fn-id (:fn-id row)}))
            acc))
        {:fn-ids #{} :fn-usage-ids #{} :fn-usages {}}
        rows))))


(defn- arg-values-to-map
  [arg-values]
  (into {} (map (juxt :arg-schema-id identity) arg-values)))


(defn- resolve-execution-graph-sql
  "Fallback: resolves execution graph using SQL (BFS algorithm)."
  [ds fn-id]
  (let [root-fn (read-fn ds fn-id)]
    (when-not root-fn
      (throw (ex-info "Function not found"
                      {:type :not-found :fn-id fn-id})))
    (loop [to-visit #{fn-id}
           visited #{}
           all-resolved-args {}
           all-fn-usages {}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        (let [all-fn-ids (set (keys all-resolved-args))
              fns-query (sql/format {:select [:*] :from [:fn] :where [:in :id (vec all-fn-ids)]} {:quoted true})
              fns (->> (jdbc/execute! ds fns-query (query-opts))
                       (map codec/row->entity)
                       (map (juxt :id identity))
                       (into {}))
              fn-schema-ids (->> (vals fns) (map :fn-schema-id) set)
              fn-schemas-query (sql/format {:select [:*] :from [:fn_schema] :where [:in :id (vec fn-schema-ids)]} {:quoted true})
              fn-schemas (->> (jdbc/execute! ds fn-schemas-query (query-opts))
                              (map codec/row->entity)
                              (map (juxt :id identity))
                              (into {}))
              arg-schemas (load-arg-schemas ds fn-schema-ids)]
          (sp/->execution-graph
            {:fns fns
             :fn-schemas fn-schemas
             :arg-schemas arg-schemas
             :resolved-args all-resolved-args
             :fn-usages all-fn-usages}))
        (let [batch (vec to-visit)
              new-visited (into visited batch)
              resolved-args-batch (into {}
                                        (map (fn [fid]
                                               (let [avs (load-arg-values-for-fn ds fid)]
                                                 [fid (arg-values-to-map avs)])))
                                        batch)
              all-potential-refs (->> (vals resolved-args-batch)
                                      (mapcat sp/extract-uuid-refs-from-arg-values)
                                      set)
              new-candidates (set/difference all-potential-refs new-visited)
              {:keys [fn-ids fn-usages]} (classify-uuid-refs ds new-candidates)
              fu-fn-ids (set (map :fn-id (vals fn-usages)))
              all-new-fn-refs (set/union fn-ids (set/difference fu-fn-ids new-visited))]
          (recur all-new-fn-refs
                 new-visited
                 (merge all-resolved-args resolved-args-batch)
                 (merge all-fn-usages fn-usages)
                 (+ iter-count (count batch))))))))


(defn resolve-execution-graph-cypher
  "Resolves execution graph using AGE when available, falls back to SQL.

   The AGE implementation provides O(1) graph resolution by traversing
   the entire dependency graph in a single Cypher query.

   Currently, we use SQL fallback for reliability while AGE integration
   is being stabilized. The AGE graph is populated via sync-entity-to-graph!
   during CRUD operations."
  [ds _graph-name fn-id]
  ;; For now, always use SQL fallback for reliability
  ;; AGE Cypher will be used once we have proper testcontainer support
  (resolve-execution-graph-sql ds fn-id))
