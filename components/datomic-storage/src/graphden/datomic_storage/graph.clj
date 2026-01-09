(ns graphden.datomic-storage.graph
  "Datomic ExecutionGraph resolution helpers.

   Provides functions for:
   - Collecting parent chains for functions
   - Loading arg-values, fns, fn-schemas, arg-schemas in batches
   - Resolving complete execution graphs"
  (:require
    [clojure.set :as set]
    [datomic.client.api :as d]
    [graphden.storage-protocol.interface :as sp]))


(defn- collect-parent-chains-batch
  "Collects parent chains for multiple fns.
   Returns {fn-id -> [chain-fn-ids from child to root]}.
   Uses iterative approach to collect all parent chains at once."
  [db fn-ids]
  (if (empty? fn-ids)
    {}
    (loop [chains (into {} (map (fn [fid] [fid [fid]]) fn-ids))
           ;; Track current parents for each chain
           current-parents (into {} (map (fn [fid] [fid fid]) fn-ids))]
      (let [;; Get all current parent IDs that need parent lookup
            ids-to-lookup (set (filter some? (vals current-parents)))
            ;; Batch query parent-fn-ids for all current nodes
            parent-rows (when (seq ids-to-lookup)
                          (d/q '[:find ?fn-id ?parent-id
                                 :in $ [?fn-id ...]
                                 :where
                                 [?e :fn/id ?fn-id]
                                 [?e :fn/parent-fn-id ?parent-id]]
                               db (vec ids-to-lookup)))
            parent-map (into {} parent-rows)]
        (if (empty? parent-map)
          chains
          ;; Update chains and current-parents for next iteration
          (let [new-chains (reduce (fn [acc [origin current-id]]
                                     (if-let [parent-id (get parent-map current-id)]
                                       (update acc origin conj parent-id)
                                       acc))
                                   chains
                                   current-parents)
                new-parents (reduce (fn [acc [origin current-id]]
                                      (if-let [parent-id (get parent-map current-id)]
                                        (assoc acc origin parent-id)
                                        (dissoc acc origin)))
                                    current-parents
                                    current-parents)]
            (if (empty? new-parents)
              new-chains
              (recur new-chains new-parents))))))))


(defn- load-arg-values-batch
  "Loads all arg-values for a set of fn-ids.
   Returns seq of arg-value maps."
  [db fn-ids]
  (if (empty? fn-ids)
    []
    (let [rows (d/q '[:find ?id ?owner-fn-id ?arg-schema-id ?value
                      :in $ [?owner-id ...]
                      :where
                      [?e :arg-value/id ?id]
                      [?e :arg-value/owner-fn-id ?owner-fn-id]
                      [?e :arg-value/arg-schema-id ?arg-schema-id]
                      [?e :arg-value/value ?value]]
                    db (vec fn-ids))]
      (map (fn [[id owner-fn-id arg-schema-id value]]
             {:id id
              :owner-fn-id owner-fn-id
              :arg-schema-id arg-schema-id
              :value value})
           rows))))


(defn- classify-and-load-refs
  "Classifies UUID references and loads fn-result-values in combined queries.
   Returns {:fn-ids #{...} :frv-ids #{...} :fn-result-values {...}}.
   Gracefully handles missing fn-result-value attribute."
  [db uuid-candidates]
  (if (empty? uuid-candidates)
    {:fn-ids #{} :frv-ids #{} :fn-result-values {}}
    (let [uuids-vec (vec uuid-candidates)
          ;; Query fn refs
          fn-results (d/q '[:find ?fn-id
                            :in $ [?fn-id ...]
                            :where
                            [?e :fn/id ?fn-id]]
                          db uuids-vec)
          ;; Query fn-result-values WITH their fn-ids (combined classify + load)
          ;; Handle missing attribute gracefully
          frv-results (try
                        (d/q '[:find ?frv-id ?fn-id
                               :in $ [?frv-id ...]
                               :where
                               [?e :fn-result-value/id ?frv-id]
                               [?e :fn-result-value/fn-id ?fn-id]]
                             db uuids-vec)
                        (catch clojure.lang.ExceptionInfo e
                          ;; :db.error/not-an-entity means attribute doesn't exist
                          (if (= :db.error/not-an-entity (:db/error (ex-data e)))
                            []
                            (throw e))))]
      {:fn-ids (set (map first fn-results))
       :frv-ids (set (map first frv-results))
       :fn-result-values (->> frv-results
                              (map (fn [[frv-id fn-id]]
                                     [frv-id {:id frv-id :fn-id fn-id}]))
                              (into {}))})))


(defn- load-fns-batch
  "Loads multiple fns by id. Returns {fn-id -> fn-record}."
  [db fn-ids]
  (if (empty? fn-ids)
    {}
    (let [;; Query all fns at once - required fields
          rows (d/q '[:find ?id ?name ?fn-schema-id
                      :in $ [?id ...]
                      :where
                      [?e :fn/id ?id]
                      [?e :fn/name ?name]
                      [?e :fn/fn-schema-id ?fn-schema-id]]
                    db (vec fn-ids))
          ;; Query parent-fn-ids separately (optional attribute)
          parent-rows (d/q '[:find ?id ?parent-fn-id
                             :in $ [?id ...]
                             :where
                             [?e :fn/id ?id]
                             [?e :fn/parent-fn-id ?parent-fn-id]]
                           db (vec fn-ids))
          parent-map (into {} parent-rows)]
      (->> rows
           (map (fn [[id fn-name fn-schema-id]]
                  [id {:id id
                       :name fn-name
                       :fn-schema-id fn-schema-id
                       :parent-fn-id (get parent-map id)}]))
           (into {})))))


(defn- load-fn-schemas-batch
  "Loads multiple fn-schemas by id. Returns {fn-schema-id -> fn-schema-record}."
  [db fn-schema-ids]
  (if (empty? fn-schema-ids)
    {}
    (let [rows (d/q '[:find ?id ?name ?returned-type
                      :in $ [?id ...]
                      :where
                      [?e :fn-schema/id ?id]
                      [?e :fn-schema/name ?name]
                      [?e :fn-schema/returned-type ?returned-type]]
                    db (vec fn-schema-ids))]
      (->> rows
           (map (fn [[id schema-name returned-type]]
                  [id {:id id
                       :name schema-name
                       :returned-type returned-type}]))
           (into {})))))


(defn- load-arg-schemas-batch
  "Loads arg-schemas for multiple fn-schema-ids. Returns {arg-schema-id -> arg-schema-record}."
  [db fn-schema-ids]
  (if (empty? fn-schema-ids)
    {}
    (let [rows (d/q '[:find ?id ?fn-schema-id ?name ?type ?required
                      :in $ [?fns-id ...]
                      :where
                      [?e :arg-schema/id ?id]
                      [?e :arg-schema/fn-schema-id ?fn-schema-id]
                      [?e :arg-schema/name ?name]
                      [?e :arg-schema/type ?type]
                      [(get-else $ ?e :arg-schema/required true) ?required]]
                    db (vec fn-schema-ids))]
      (->> rows
           (map (fn [[id fn-schema-id arg-name arg-type required]]
                  [id {:id id
                       :fn-schema-id fn-schema-id
                       :name arg-name
                       :type arg-type
                       :required required}]))
           (into {})))))


(defn- check-fn-exists!
  "Throws :not-found if function does not exist in database."
  [db fn-id]
  (let [exists? (seq (d/q '[:find ?e
                            :in $ ?fn-id
                            :where
                            [?e :fn/id ?fn-id]]
                          db fn-id))]
    (when-not exists?
      (throw (ex-info "Function not found"
                      {:type :not-found
                       :fn-id fn-id})))))


(defn- load-final-graph-data
  "Phase 2: Batch load all data for the discovered graph.
   fn-result-values are already loaded during discovery (optimization).
   Returns the complete execution graph map."
  [db all-chains all-merged-args all-fn-result-values]
  (let [all-fn-ids (set (keys all-chains))
        fns (load-fns-batch db all-fn-ids)
        fn-schema-ids (->> (vals fns)
                           (map :fn-schema-id)
                           (set))
        fn-schemas (load-fn-schemas-batch db fn-schema-ids)
        arg-schemas (load-arg-schemas-batch db fn-schema-ids)]
    (sp/->execution-graph
      {:fns fns
       :fn-schemas fn-schemas
       :arg-schemas arg-schemas
       :resolved-args all-merged-args
       :fn-result-values all-fn-result-values})))


(defn- process-discovery-batch
  "Processes a batch of fn-ids during graph discovery.
   Returns map with :next-to-visit, :new-visited, :chains, :merged-args, :fn-result-values."
  [db batch visited]
  (let [new-visited (into visited batch)
        ;; Batch load parent chains
        chains (collect-parent-chains-batch db batch)
        all-chain-fn-ids (->> (vals chains) (mapcat identity) (set))
        ;; Batch load and merge arg-values
        all-arg-values (load-arg-values-batch db all-chain-fn-ids)
        merged-args-batch (into {}
                                (map (fn [fid]
                                       [fid (sp/merge-arg-values-for-chain
                                              all-arg-values
                                              (get chains fid [fid]))]))
                                batch)
        ;; Find new references
        all-potential-refs (->> (vals merged-args-batch)
                                (mapcat sp/extract-uuid-refs-from-arg-values)
                                (set))
        new-candidates (set/difference all-potential-refs new-visited)
        ;; Classify AND load fn-result-values in one query (optimization)
        {:keys [fn-ids fn-result-values]} (classify-and-load-refs db new-candidates)
        frv-fn-ids (set (map :fn-id (vals fn-result-values)))
        next-to-visit (set/union fn-ids (set/difference frv-fn-ids new-visited))]
    {:next-to-visit next-to-visit
     :new-visited new-visited
     :chains chains
     :merged-args merged-args-batch
     :fn-result-values fn-result-values}))


(defn resolve-execution-graph-impl
  "Resolves complete execution graph for a function.
   Uses batched BFS to collect all transitively referenced functions and fn-result-values.
   Throws if iteration count exceeds sp/*max-graph-iterations*.

   This implementation uses batch queries to minimize database round-trips:
   1. Process pending fn-ids in batches
   2. Batch load parent chains
   3. Batch load arg-values for all chain members
   4. Extract fn-refs and fn-result-value refs, continue until graph is complete
   5. Final batch load of all fns, fn-schemas, arg-schemas
   Note: fn-result-values are loaded during discovery (optimization)"
  [conn fn-id]
  (let [db (d/db conn)]
    (check-fn-exists! db fn-id)
    ;; Phase 1: Discover all fn-ids and fn-result-values using batched BFS
    (loop [to-visit #{fn-id}
           visited #{}
           all-chains {}
           all-merged-args {}
           all-fn-result-values {}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        ;; Phase 2: Load final graph data (fn-result-values already loaded)
        (load-final-graph-data db all-chains all-merged-args all-fn-result-values)
        ;; Process batch
        (let [batch (vec to-visit)
              result (process-discovery-batch db batch visited)]
          (recur (:next-to-visit result)
                 (:new-visited result)
                 (merge all-chains (:chains result))
                 (merge all-merged-args (:merged-args result))
                 (merge all-fn-result-values (:fn-result-values result))
                 (+ iter-count (count batch))))))))
