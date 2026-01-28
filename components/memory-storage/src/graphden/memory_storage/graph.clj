(ns graphden.memory-storage.graph
  "ExecutionGraph resolution for memory storage.

   Provides functions for:
   - UUID reference classification (fn vs fn-result-value)
   - BFS-based execution graph resolution

   Schema (normalized arg-value):
   - arg-value: pure value (arg-schema-id, value) - no owner
   - fn-arg: binding (fn-id, arg-schema-id, arg-value-id)
   - call-site-arg: binding (fn-result-value-id, arg-schema-id, arg-value-id)

   To get arg-values for a fn, join fn-arg -> arg-value."
  (:require
    [clojure.set :as set]
    [graphden.memory-storage.crud :as crud]
    [graphden.storage-protocol.interface :as sp]))


(defn- classify-refs
  "Classifies UUID references as fn refs or fn-result-value refs.
   Returns {:fn-refs #{...} :frv-refs #{...} :frvs {...}}."
  [all-refs fns-data fn-result-values-data]
  (reduce (fn [acc ref-id]
            (condp contains? ref-id
              ;; Direct fn reference
              fns-data
              (update acc :fn-refs conj ref-id)

              ;; fn-result-value reference
              fn-result-values-data
              (let [frv (get fn-result-values-data ref-id)]
                (-> acc
                    (update :frv-refs conj ref-id)
                    (update :frvs assoc ref-id frv)
                    ;; Also visit the fn that frv points to
                    (update :fn-refs conj (:fn-id frv))))

              ;; Unknown UUID - skip
              acc))
          {:fn-refs #{} :frv-refs #{} :frvs {}}
          all-refs))


(defn- arg-values-to-map
  "Converts a sequence of arg-value records to {arg-schema-id -> arg-value}."
  [arg-values]
  (into {} (map (juxt :arg-schema-id identity) arg-values)))


(defn- process-fn-node
  "Processes a single function node during graph resolution.
   Returns updated graph state with the function's data added.

   Uses arg-values-by-fn index which is built by joining fn-arg -> arg-value."
  [state indexes current-fn-id graph]
  (let [{:keys [arg-values-by-fn arg-schemas-by-fn-schema
                fns-data fn-result-values-data]} indexes
        fn-rec (crud/get-record state :fn current-fn-id)]
    (if-not fn-rec
      ;; fn doesn't exist, skip (might be literal value that looks like UUID)
      {:graph graph :new-fn-refs #{}}
      (let [fn-schema-id (:fn-schema-id fn-rec)
            fn-schema (crud/get-record state :fn-schema fn-schema-id)
            ;; Get arg-schemas for this fn-schema using pre-built index
            new-arg-schemas (if (contains? (:fn-schemas graph) fn-schema-id)
                              {}
                              (->> (get arg-schemas-by-fn-schema fn-schema-id [])
                                   (map (juxt :id identity))
                                   (into {})))
            ;; Get arg-values for this fn via fn-arg join (pre-built index)
            arg-values (get arg-values-by-fn current-fn-id [])
            resolved-args (arg-values-to-map arg-values)
            ;; Extract and classify UUID refs
            all-refs (sp/extract-uuid-refs-from-arg-values resolved-args)
            {new-fn-refs :fn-refs new-frvs :frvs}
            (classify-refs all-refs fns-data fn-result-values-data)]
        {:graph (-> graph
                    (update :fns assoc current-fn-id fn-rec)
                    (update :fn-schemas #(if fn-schema (assoc % fn-schema-id fn-schema) %))
                    (update :arg-schemas merge new-arg-schemas)
                    (update :resolved-args assoc current-fn-id resolved-args)
                    (update :fn-result-values merge new-frvs))
         :new-fn-refs new-fn-refs}))))


(defn- build-arg-values-by-fn-index
  "Builds index: fn-id -> [arg-values...] by joining fn-arg -> arg-value.
   Returns map where each fn-id maps to vector of arg-value records."
  [fn-args-data arg-values-data]
  (reduce
    (fn [acc fn-arg]
      (let [fn-id (:fn-id fn-arg)
            arg-value-id (:arg-value-id fn-arg)
            arg-value (get arg-values-data arg-value-id)]
        (if arg-value
          (update acc fn-id (fnil conj []) arg-value)
          acc)))
    {}
    (vals fn-args-data)))


(defn resolve-execution-graph-impl
  "Resolves execution graph starting from fn-id.
   Uses BFS to collect all transitively referenced functions and fn-result-values.
   Builds indexes once for O(N+M) performance instead of O(N*M).
   Throws if iteration count exceeds sp/*max-graph-iterations*.
   Optimization: visited check at insertion time, not extraction time.

   Schema (normalized):
   - arg-value has no owner-fn-id
   - fn-arg binds fn -> arg-value
   - We join through fn-arg to get arg-values for each fn"
  [state fn-id]
  (let [fn-args-data (crud/get-entity-data state :fn-arg)
        arg-values-data (crud/get-entity-data state :arg-value)
        indexes {:arg-values-by-fn (build-arg-values-by-fn-index fn-args-data arg-values-data)
                 :arg-schemas-by-fn-schema (group-by :fn-schema-id (vals (crud/get-entity-data state :arg-schema)))
                 :fns-data (crud/get-entity-data state :fn)
                 :fn-result-values-data (crud/get-entity-data state :fn-result-value)}
        init-graph {:fns {} :fn-schemas {} :arg-schemas {}
                    :resolved-args {} :fn-result-values {}}]
    (loop [to-visit #{fn-id}
           visited #{fn-id}  ; Mark as visited when added to queue
           graph init-graph
           iter-count 0]
      (sp/check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        (sp/->execution-graph graph)
        (let [current-fn-id (first to-visit)
              rest-to-visit (disj to-visit current-fn-id)
              {:keys [graph new-fn-refs]}
              (process-fn-node state indexes current-fn-id graph)
              ;; Only add truly new nodes (not yet visited)
              new-to-visit (set/difference new-fn-refs visited)
              new-visited (set/union visited new-to-visit)]
          (recur (set/union rest-to-visit new-to-visit)
                 new-visited
                 graph
                 (inc iter-count)))))))
