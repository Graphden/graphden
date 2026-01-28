(ns graphden.storage-protocol.graph
  "ExecutionGraph utilities and BFS algorithm.

   Contains:
   - ExecutionGraphResult record
   - Shared constants (timeouts, limits)
   - Graph utility functions
   - BFS algorithm for graph resolution

   ## Why BFS (Breadth-First Search)?

   We use BFS instead of DFS for graph resolution because:
   1. More predictable memory usage (queue vs recursive stack)
   2. Better for detecting cycles early (same depth explored together)
   3. Easier to implement iteration limits (count queue operations)
   4. Natural batching of queries at the same depth level

   ## Cycle Detection

   Cycles in the execution graph are prevented by:
   1. Visited set: tracks already-processed fn-ids
   2. UNION in recursive CTEs: automatically deduplicates (SQL level)
   3. *max-graph-iterations*: hard limit on total iterations

   ## ExecutionGraphResult Structure

   The record contains all data needed to execute a function:
   - :fns - Map of fn-id -> fn record
   - :fn-schemas - Map of fn-schema-id -> fn-schema record
   - :arg-schemas - Map of arg-schema-id -> arg-schema record
   - :resolved-args - Map of fn-id -> {arg-schema-id -> arg-value}
   - :call-sites - Map of call-site-id -> call-site record

   Note: This namespace does NOT define protocols to avoid circular deps.
   The ExecutionGraphReader extension is done in interface.clj."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.storage-protocol.config :as config]))


;; === Shared constants ===

;; Re-export from config for backwards compatibility
(def default-query-timeout-ms
  "Default timeout for storage queries in milliseconds.
   Re-exported from config - see config/default-query-timeout-ms for details."
  config/default-query-timeout-ms)


(def default-max-depth
  "Default maximum recursion depth for function execution.
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

(defn- check-bfs-iteration-limit!
  "Throws if BFS iteration count exceeds limit."
  [iter-count max-iter context-id]
  (when (> iter-count max-iter)
    (throw (ex-info "BFS traversal exceeded maximum iterations"
                    {:type :execution-error/traversal-too-large
                     :context-id context-id
                     :max-iterations max-iter
                     :iteration-count iter-count}))))


(defn- bfs-step
  "Processes a single BFS step.
   Returns updated state map with :queue, :visited."
  [queue visited get-neighbors-fn]
  (let [current-id (peek queue)
        rest-queue (pop queue)
        neighbors (get-neighbors-fn current-id)
        new-neighbors (remove visited neighbors)
        new-visited (into visited new-neighbors)]
    {:queue (into rest-queue new-neighbors)
     :visited new-visited}))


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
       (check-bfs-iteration-limit! iter-count max-iter context-id)
       (if (empty? queue)
         visited
         (let [{new-queue :queue new-visited :visited}
               (bfs-step queue visited get-neighbors-fn)]
           (recur new-queue new-visited (inc iter-count))))))))


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
  [fns fn-schemas arg-schemas resolved-args call-sites arg-schemas-by-fn-schema])


(defn- build-arg-schemas-index
  "Builds index of fn-schema-id -> {arg-schema-id -> arg-schema}.
   Provides O(1) lookup by fn-schema-id instead of O(n) filter."
  [arg-schemas]
  (reduce-kv
    (fn [acc arg-schema-id arg-schema]
      (update acc (:fn-schema-id arg-schema) assoc arg-schema-id arg-schema))
    {}
    arg-schemas))


(defn ->execution-graph
  "Creates an ExecutionGraphResult record from a map.
   Validates that all required keys are present and non-empty.
   Builds arg-schemas-by-fn-schema index for O(1) lookup."
  [{:keys [fns fn-schemas arg-schemas resolved-args call-sites]
    :or {call-sites {}}}]
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
  (->ExecutionGraphResult fns fn-schemas arg-schemas resolved-args call-sites
                          (build-arg-schemas-index arg-schemas)))


(defn execution-graph?
  "Returns true if x is an ExecutionGraphResult record."
  [x]
  (instance? ExecutionGraphResult x))


;; === ExecutionGraph Accessor Functions ===
;;
;; These provide stable API for accessing graph data, insulating callers
;; from internal record structure changes.

(defn get-graph-fns
  "Returns the fns map from an execution graph.
   Prefer this over direct :fns access for forward compatibility."
  [graph]
  (:fns graph))


(defn get-graph-fn-schemas
  "Returns the fn-schemas map from an execution graph.
   Prefer this over direct :fn-schemas access for forward compatibility."
  [graph]
  (:fn-schemas graph))


(defn get-graph-arg-schemas
  "Returns the arg-schemas map from an execution graph.
   Prefer this over direct :arg-schemas access for forward compatibility."
  [graph]
  (:arg-schemas graph))


(defn get-graph-resolved-args
  "Returns the resolved-args map from an execution graph.
   Prefer this over direct :resolved-args access for forward compatibility."
  [graph]
  (:resolved-args graph))


(defn get-graph-call-sites
  "Returns the call-sites map from an execution graph.
   Prefer this over direct :call-sites access for forward compatibility."
  [graph]
  (:call-sites graph))


;; === Execution Graph Utilities ===

(defn extract-uuid-refs-from-arg-values
  "Extracts UUIDs referenced in arg-values.
   Returns set of UUIDs that could be fn or call-site references."
  [arg-values-map]
  (->> (vals arg-values-map)
       (map :value)
       (keep try-parse-uuid)
       (set)))


(defn- arg-values-to-map
  "Converts a sequence of arg-value records to {arg-schema-id -> arg-value}."
  [arg-values]
  (into {} (map (juxt :arg-schema-id identity) arg-values)))


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
   - load-arg-values-for-fn: (fn [fn-id] -> [arg-value-records])
   - classify-uuid-refs: (fn [uuid-refs] -> {:fn-refs #{} :call-sites {}})
   - current-fn-id: UUID of fn to process
   - graph: current accumulated graph state"
  [load-fn-record load-fn-schema-record load-arg-schemas-for-fn-schema
   load-arg-values-for-fn classify-uuid-refs
   current-fn-id graph]
  (if-let [fn-rec (load-fn-record current-fn-id)]
    (let [fn-schema-id (:fn-schema-id fn-rec)
          fn-schema (when-not (contains? (:fn-schemas graph) fn-schema-id)
                      (load-fn-schema-record fn-schema-id))
          new-arg-schemas (if fn-schema
                            (load-arg-schemas-for-fn-schema fn-schema-id)
                            {})
          ;; Load arg-values directly for this fn
          arg-values (load-arg-values-for-fn current-fn-id)
          resolved-args (arg-values-to-map arg-values)
          all-refs (extract-uuid-refs-from-arg-values resolved-args)
          {:keys [fn-refs call-sites]} (classify-uuid-refs all-refs)]
      {:graph (-> graph
                  (update :fns assoc current-fn-id fn-rec)
                  (update :fn-schemas #(if fn-schema (assoc % fn-schema-id fn-schema) %))
                  (update :arg-schemas merge new-arg-schemas)
                  (update :resolved-args assoc current-fn-id resolved-args)
                  (update :call-sites merge call-sites))
       :new-fn-refs fn-refs})
    {:graph graph :new-fn-refs #{}}))


(defn resolve-execution-graph-bfs
  "Shared BFS algorithm for execution graph resolution.
   Takes loader functions as parameters for backend-specific data access.

   Arguments:
   - load-fn-record: (fn [fn-id] -> fn-record)
   - load-fn-schema-record: (fn [fn-schema-id] -> fn-schema-record)
   - load-arg-schemas-for-fn-schema: (fn [fn-schema-id] -> {arg-schema-id -> record})
   - load-arg-values-for-fn: (fn [fn-id] -> [arg-value-records])
   - classify-uuid-refs: (fn [uuid-refs] -> {:fn-refs #{} :call-sites {}})
   - fn-id: starting function UUID

   Returns ExecutionGraphResult record."
  [load-fn-record load-fn-schema-record load-arg-schemas-for-fn-schema
   load-arg-values-for-fn classify-uuid-refs
   fn-id]
  (let [init-graph {:fns {} :fn-schemas {} :arg-schemas {}
                    :resolved-args {} :call-sites {}}]
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
                               load-arg-schemas-for-fn-schema
                               load-arg-values-for-fn classify-uuid-refs
                               current-fn-id graph)
              new-to-visit (set/difference new-fn-refs visited)
              new-visited (set/union visited new-to-visit)]
          (recur (set/union rest-to-visit new-to-visit)
                 new-visited
                 graph
                 (inc iter-count)))))))
