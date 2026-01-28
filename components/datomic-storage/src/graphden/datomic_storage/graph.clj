(ns graphden.datomic-storage.graph
  "Datomic ExecutionGraph resolution helpers.

   Provides functions for:
   - Loading arg-values, fns, fn-schemas, arg-schemas in batches
   - Resolving complete execution graphs"
  (:require
    [clojure.set :as set]
    [datomic.client.api :as d]
    [graphden.storage-protocol.interface :as sp]))


(defn- load-arg-values-for-fn
  "Loads all arg-values for a single fn-id via fn-arg join.
   Returns seq of arg-value maps.

   With normalized schema:
   - fn-arg binds fn-id to arg-value-id
   - We join fn-arg -> arg-value to get values for this fn"
  [db fn-id]
  (let [rows (d/q '[:find ?id ?arg-schema-id ?value
                    :in $ ?fn-id
                    :where
                    [?fa :fn-arg/fn-id ?fn-id]
                    [?fa :fn-arg/arg-value-id ?av-id]
                    [?av :arg-value/id ?id]
                    [?av :arg-value/arg-schema-id ?arg-schema-id]
                    [?av :arg-value/value ?value]]
                  db fn-id)]
    (map (fn [[id arg-schema-id value]]
           {:id id
            :arg-schema-id arg-schema-id
            :value value})
         rows)))


(defn- classify-and-load-refs
  "Classifies UUID references and loads call-sites in combined queries.
   Returns {:fn-ids #{...} :call-site-ids #{...} :call-sites {...}}.
   Gracefully handles missing call-site attribute."
  [db uuid-candidates]
  (if (empty? uuid-candidates)
    {:fn-ids #{} :call-site-ids #{} :call-sites {}}
    (let [uuids-vec (vec uuid-candidates)
          ;; Query fn refs
          fn-results (d/q '[:find ?fn-id
                            :in $ [?fn-id ...]
                            :where
                            [?e :fn/id ?fn-id]]
                          db uuids-vec)
          ;; Query call-sites WITH their fn-ids (combined classify + load)
          ;; Handle missing attribute gracefully
          cs-results (try
                       (d/q '[:find ?cs-id ?fn-id
                              :in $ [?cs-id ...]
                              :where
                              [?e :call-site/id ?cs-id]
                              [?e :call-site/fn-id ?fn-id]]
                            db uuids-vec)
                       (catch clojure.lang.ExceptionInfo e
                         ;; :db.error/not-an-entity means attribute doesn't exist
                         (if (= :db.error/not-an-entity (:db/error (ex-data e)))
                           []
                           (throw e))))]
      {:fn-ids (set (map first fn-results))
       :call-site-ids (set (map first cs-results))
       :call-sites (->> cs-results
                        (map (fn [[cs-id fn-id]]
                               [cs-id {:id cs-id :fn-id fn-id}]))
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
                    db (vec fn-ids))]
      (->> rows
           (map (fn [[id fn-name fn-schema-id]]
                  [id {:id id
                       :name fn-name
                       :fn-schema-id fn-schema-id}]))
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


(defn- arg-values-to-map
  "Converts a sequence of arg-value records to {arg-schema-id -> arg-value}."
  [arg-values]
  (into {} (map (juxt :arg-schema-id identity) arg-values)))


(defn- load-final-graph-data
  "Phase 2: Batch load all data for the discovered graph.
   call-sites are already loaded during discovery (optimization).
   Returns the complete execution graph map."
  [db all-resolved-args all-call-sites]
  (let [all-fn-ids (set (keys all-resolved-args))
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
       :resolved-args all-resolved-args
       :call-sites all-call-sites})))


(defn- process-discovery-batch
  "Processes a batch of fn-ids during graph discovery.
   Returns map with :next-to-visit, :new-visited, :resolved-args, :call-sites."
  [db batch visited]
  (let [new-visited (into visited batch)
        ;; Load and map arg-values for each fn
        resolved-args-batch (into {}
                                  (map (fn [fid]
                                         (let [arg-values (load-arg-values-for-fn db fid)]
                                           [fid (arg-values-to-map arg-values)])))
                                  batch)
        ;; Find new references
        all-potential-refs (->> (vals resolved-args-batch)
                                (mapcat sp/extract-uuid-refs-from-arg-values)
                                (set))
        new-candidates (set/difference all-potential-refs new-visited)
        ;; Classify AND load call-sites in one query (optimization)
        {:keys [fn-ids call-sites]} (classify-and-load-refs db new-candidates)
        cs-fn-ids (set (map :fn-id (vals call-sites)))
        next-to-visit (set/union fn-ids (set/difference cs-fn-ids new-visited))]
    {:next-to-visit next-to-visit
     :new-visited new-visited
     :resolved-args resolved-args-batch
     :call-sites call-sites}))


(defn resolve-execution-graph-impl
  "Resolves complete execution graph for a function.
   Uses batched BFS to collect all transitively referenced functions and call-sites.
   Throws if iteration count exceeds sp/*max-graph-iterations*.

   This implementation uses batch queries to minimize database round-trips:
   1. Process pending fn-ids in batches
   2. Load arg-values for each fn directly
   3. Extract fn-refs and call-site refs, continue until graph is complete
   4. Final batch load of all fns, fn-schemas, arg-schemas
   Note: call-sites are loaded during discovery (optimization)"
  [conn fn-id]
  (let [db (d/db conn)]
    (check-fn-exists! db fn-id)
    ;; Phase 1: Discover all fn-ids and call-sites using batched BFS
    (loop [to-visit #{fn-id}
           visited #{}
           all-resolved-args {}
           all-call-sites {}
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        ;; Phase 2: Load final graph data (call-sites already loaded)
        (load-final-graph-data db all-resolved-args all-call-sites)
        ;; Process batch
        (let [batch (vec to-visit)
              result (process-discovery-batch db batch visited)]
          (recur (:next-to-visit result)
                 (:new-visited result)
                 (merge all-resolved-args (:resolved-args result))
                 (merge all-call-sites (:call-sites result))
                 (+ iter-count (count batch))))))))
