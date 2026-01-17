(ns graphden.memory-storage.graph
  "ExecutionGraph resolution for memory storage.

   Provides functions for:
   - Function parent chain collection
   - UUID reference classification (fn vs fn-result-value)
   - BFS-based execution graph resolution"
  (:require
    [clojure.set :as set]
    [graphden.memory-storage.crud :as crud]
    [graphden.storage-protocol.interface :as sp]))


(defn- collect-fn-parent-chain
  "Collects all fn-ids in the parent chain (including the fn itself)."
  [state fn-id]
  (loop [current-id fn-id
         chain []]
    (if-not current-id
      chain
      (let [fn-rec (crud/get-record state :fn current-id)]
        (recur (:parent-fn-id fn-rec) (conj chain current-id))))))


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


(defn- process-fn-node
  "Processes a single function node during graph resolution.
   Returns updated graph state with the function's data added."
  [state indexes current-fn-id graph]
  (let [{:keys [arg-values-by-owner arg-schemas-by-fn-schema
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
            ;; Merge arg-values from parent chain
            chain (collect-fn-parent-chain state current-fn-id)
            chain-arg-values (mapcat #(get arg-values-by-owner % []) chain)
            merged-args (sp/merge-arg-values-for-chain chain-arg-values chain)
            ;; Extract and classify UUID refs
            all-refs (sp/extract-uuid-refs-from-arg-values merged-args)
            {new-fn-refs :fn-refs new-frvs :frvs}
            (classify-refs all-refs fns-data fn-result-values-data)]
        {:graph (-> graph
                    (update :fns assoc current-fn-id fn-rec)
                    (update :fn-schemas #(if fn-schema (assoc % fn-schema-id fn-schema) %))
                    (update :arg-schemas merge new-arg-schemas)
                    (update :resolved-args assoc current-fn-id merged-args)
                    (update :fn-result-values merge new-frvs))
         :new-fn-refs new-fn-refs}))))


(defn resolve-execution-graph-impl
  "Resolves execution graph starting from fn-id.
   Uses BFS to collect all transitively referenced functions and fn-result-values.
   Builds indexes once for O(N+M) performance instead of O(N*M).
   Throws if iteration count exceeds sp/*max-graph-iterations*.
   Optimization: visited check at insertion time, not extraction time."
  [state fn-id]
  (let [indexes {:arg-values-by-owner (group-by :owner-fn-id (vals (crud/get-entity-data state :arg-value)))
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
