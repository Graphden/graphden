(ns graphden.storage-protocol.graph
  "ExecutionGraph utilities and BFS algorithm.

   Contains:
   - ExecutionGraphResult record
   - Shared constants (timeouts, limits)
   - Graph utility functions
   - BFS algorithm for graph resolution

   Note: This namespace does NOT define protocols to avoid circular deps.
   The ExecutionGraphReader extension is done in interface.clj."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]))


;; === Shared constants ===

(def default-query-timeout-ms
  "Default timeout for storage queries in milliseconds.
   Used by PostgreSQL (via JDBC setQueryTimeout) and Datomic backends.
   Value: 30000ms (30 seconds) - reasonable default for most queries."
  30000)


(def default-max-depth
  "Default maximum recursion depth for function execution.
   Used by executor as default and by storage for parent chain limits.
   Value: 1000 - reasonable default for most use cases."
  1000)


(def default-max-unknown-types
  "Default maximum unknown types allowed per execution in forward compatibility mode.
   Acts as circuit breaker to prevent silent schema mismatch issues."
  10)


(def ^:dynamic *max-graph-iterations*
  "Maximum number of iterations when resolving execution graph.
   Prevents infinite loops in case of data inconsistencies.
   Default: 10000 (enough for complex graphs, catches runaway loops)."
  10000)


(defn with-max-graph-iterations
  "Executes f with a custom max-graph-iterations limit."
  [limit f]
  (binding [*max-graph-iterations* limit]
    (f)))


(defn check-graph-iteration-limit!
  "Checks if iteration count exceeds the limit.
   Logs warning at 80% of limit to help identify potential runaway graphs.
   Throws ExceptionInfo if limit is exceeded."
  [iteration-count fn-id]
  (let [warning-threshold (long (* 0.8 *max-graph-iterations*))]
    (when (and (> iteration-count warning-threshold)
               (< iteration-count *max-graph-iterations*))
      (log/warn "Graph resolution approaching iteration limit"
                {:fn-id fn-id
                 :iteration-count iteration-count
                 :max-iterations *max-graph-iterations*
                 :percent-used (int (* 100 (/ iteration-count *max-graph-iterations*)))})))
  (when (> iteration-count *max-graph-iterations*)
    (throw (ex-info "Execution graph resolution exceeded maximum iterations"
                    {:type :execution-error/graph-too-large
                     :fn-id fn-id
                     :max-iterations *max-graph-iterations*
                     :iteration-count iteration-count}))))


;; === Generic BFS Traversal ===

(defn traverse-bfs
  "Generic BFS traversal utility for in-memory graph operations.
   Returns set of all visited nodes.

   Parameters:
   - start-id: Starting node ID
   - get-neighbors-fn: (fn [node-id] -> seq of neighbor IDs)
     Function that returns neighbors for a given node.
     Only unvisited neighbors will be added to queue.

   Options (via opts map):
   - :max-iterations - Override default iteration limit (default: *max-graph-iterations*)
   - :context-id - ID for error context in limit messages (default: start-id)

   Example:
   ```clojure
   ;; Collect all function dependencies
   (traverse-bfs fn-id
     (fn [id]
       (->> (get-arg-values-for-fn id)
            (map :value)
            (filter uuid?)
            (filter fn-exists?))))
   ```

   Returns: Set of all visited node IDs (including start-id)"
  ([start-id get-neighbors-fn]
   (traverse-bfs start-id get-neighbors-fn {}))
  ([start-id get-neighbors-fn opts]
   (let [max-iter (or (:max-iterations opts) *max-graph-iterations*)
         context-id (or (:context-id opts) start-id)
         ;; Use PersistentQueue for O(1) enqueue/dequeue instead of vector O(n)
         init-queue (conj clojure.lang.PersistentQueue/EMPTY start-id)]
     (loop [queue init-queue
            visited #{start-id}
            iter-count 0]
       (when (> iter-count max-iter)
         (throw (ex-info "BFS traversal exceeded maximum iterations"
                         {:type :execution-error/traversal-too-large
                          :context-id context-id
                          :max-iterations max-iter
                          :iteration-count iter-count})))
       (if (empty? queue)
         visited
         (let [current-id (peek queue)
               rest-queue (pop queue)
               neighbors (get-neighbors-fn current-id)
               new-neighbors (remove visited neighbors)
               new-visited (into visited new-neighbors)]
           (recur (into rest-queue new-neighbors)
                  new-visited
                  (inc iter-count))))))))


;; === UUID parsing ===

(defn try-parse-uuid
  "Attempts to parse value as UUID. Returns UUID or nil.
   Handles UUIDs, UUID strings, and returns nil for non-UUID values."
  [v]
  (cond
    (uuid? v) v
    (string? v) (try
                  (java.util.UUID/fromString v)
                  (catch IllegalArgumentException _ nil))
    :else nil))


;; === ExecutionGraphResult record ===

(defrecord ExecutionGraphResult
  [fns fn-schemas arg-schemas resolved-args fn-result-values])


(defn ->execution-graph
  "Creates an ExecutionGraphResult record from a map.
   Validates that all required keys are present and non-empty."
  [{:keys [fns fn-schemas arg-schemas resolved-args fn-result-values]
    :or {fn-result-values {}}}]
  (when-not (map? fns)
    (throw (ex-info "ExecutionGraphResult requires :fns map"
                    {:type :invalid-data :received (type fns)})))
  (when (empty? fns)
    (throw (ex-info "ExecutionGraphResult :fns must contain at least target function"
                    {:type :invalid-data :hint "Check that fn-id exists in storage"})))
  (when-not (map? fn-schemas)
    (throw (ex-info "ExecutionGraphResult requires :fn-schemas map"
                    {:type :invalid-data :received (type fn-schemas)})))
  (when (empty? fn-schemas)
    (throw (ex-info "ExecutionGraphResult :fn-schemas must contain at least one schema"
                    {:type :invalid-data :hint "Check that fn has valid fn-schema-id"})))
  (when-not (map? arg-schemas)
    (throw (ex-info "ExecutionGraphResult requires :arg-schemas map"
                    {:type :invalid-data :received (type arg-schemas)})))
  (when-not (map? resolved-args)
    (throw (ex-info "ExecutionGraphResult requires :resolved-args map"
                    {:type :invalid-data :received (type resolved-args)})))
  (->ExecutionGraphResult fns fn-schemas arg-schemas resolved-args fn-result-values))


(defn execution-graph?
  "Returns true if x is an ExecutionGraphResult record."
  [x]
  (instance? ExecutionGraphResult x))


;; === Execution Graph Utilities ===

(defn merge-arg-values-for-chain
  "Merges arg-values from a parent chain where child overrides parent.
   Given a chain [child grandparent great-grandparent ...] and all arg-values,
   returns {arg-schema-id -> arg-value-record} with closest-to-child values winning."
  [all-arg-values chain]
  (when (seq chain)
    (let [chain-set (set chain)
          chain-pos (zipmap chain (range))
          chain-arg-values (filter #(chain-set (:owner-fn-id %)) all-arg-values)]
      (->> chain-arg-values
           (group-by :arg-schema-id)
           (map (fn [[arg-schema-id avs]]
                  [arg-schema-id (apply min-key #(get chain-pos (:owner-fn-id %) Long/MAX_VALUE) avs)]))
           (into {})))))


(defn extract-uuid-refs-from-arg-values
  "Extracts UUIDs referenced in arg-values.
   Returns set of UUIDs that could be fn or fn-result-value references."
  [arg-values-map]
  (->> (vals arg-values-map)
       (map :value)
       (keep try-parse-uuid)
       (set)))


;; === Graph Resolution BFS Algorithm ===
;;
;; These functions take loader-specific functions as parameters to avoid
;; protocol dependencies. Storage backends provide the loader functions.

(defn process-fn-node
  "Processes a single fn node during graph resolution.
   Returns {:graph updated-graph :new-fn-refs #{fn-ids-to-visit}}.

   Arguments:
   - load-fn-record: (fn [fn-id] -> fn-record)
   - load-fn-schema-record: (fn [fn-schema-id] -> fn-schema-record)
   - load-arg-schemas-for-fn-schema: (fn [fn-schema-id] -> {arg-schema-id -> record})
   - load-parent-chain: (fn [fn-id] -> [fn-id parent-id ...])
   - load-arg-values-for-fns: (fn [fn-ids] -> [arg-value-records])
   - classify-uuid-refs: (fn [uuid-refs] -> {:fn-refs #{} :frvs {}})
   - current-fn-id: UUID of fn to process
   - graph: current accumulated graph state"
  [load-fn-record load-fn-schema-record load-arg-schemas-for-fn-schema
   load-parent-chain load-arg-values-for-fns classify-uuid-refs
   current-fn-id graph]
  (if-let [fn-rec (load-fn-record current-fn-id)]
    (let [fn-schema-id (:fn-schema-id fn-rec)
          fn-schema (when-not (contains? (:fn-schemas graph) fn-schema-id)
                      (load-fn-schema-record fn-schema-id))
          new-arg-schemas (if fn-schema
                            (load-arg-schemas-for-fn-schema fn-schema-id)
                            {})
          chain (load-parent-chain current-fn-id)
          chain-arg-values (load-arg-values-for-fns chain)
          merged-args (merge-arg-values-for-chain chain-arg-values chain)
          all-refs (extract-uuid-refs-from-arg-values merged-args)
          {:keys [fn-refs frvs]} (classify-uuid-refs all-refs)]
      {:graph (-> graph
                  (update :fns assoc current-fn-id fn-rec)
                  (update :fn-schemas #(if fn-schema (assoc % fn-schema-id fn-schema) %))
                  (update :arg-schemas merge new-arg-schemas)
                  (update :resolved-args assoc current-fn-id merged-args)
                  (update :fn-result-values merge frvs))
       :new-fn-refs fn-refs})
    {:graph graph :new-fn-refs #{}}))


(defn resolve-execution-graph-bfs
  "Shared BFS algorithm for execution graph resolution.
   Takes loader functions as parameters for backend-specific data access.

   Arguments:
   - load-fn-record: (fn [fn-id] -> fn-record)
   - load-fn-schema-record: (fn [fn-schema-id] -> fn-schema-record)
   - load-arg-schemas-for-fn-schema: (fn [fn-schema-id] -> {arg-schema-id -> record})
   - load-parent-chain: (fn [fn-id] -> [fn-id parent-id ...])
   - load-arg-values-for-fns: (fn [fn-ids] -> [arg-value-records])
   - classify-uuid-refs: (fn [uuid-refs] -> {:fn-refs #{} :frvs {}})
   - fn-id: starting function UUID

   Returns ExecutionGraphResult record."
  [load-fn-record load-fn-schema-record load-arg-schemas-for-fn-schema
   load-parent-chain load-arg-values-for-fns classify-uuid-refs
   fn-id]
  (let [init-graph {:fns {} :fn-schemas {} :arg-schemas {}
                    :resolved-args {} :fn-result-values {}}]
    (loop [to-visit #{fn-id}
           visited #{fn-id}
           graph init-graph
           iter-count 0]
      (check-graph-iteration-limit! iter-count fn-id)
      (if (empty? to-visit)
        (->execution-graph graph)
        (let [current-fn-id (first to-visit)
              rest-to-visit (disj to-visit current-fn-id)
              {:keys [graph new-fn-refs]}
              (process-fn-node load-fn-record load-fn-schema-record
                               load-arg-schemas-for-fn-schema load-parent-chain
                               load-arg-values-for-fns classify-uuid-refs
                               current-fn-id graph)
              new-to-visit (set/difference new-fn-refs visited)
              new-visited (set/union visited new-to-visit)]
          (recur (set/union rest-to-visit new-to-visit)
                 new-visited
                 graph
                 (inc iter-count)))))))
