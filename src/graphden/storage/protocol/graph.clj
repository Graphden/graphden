(ns graphden.storage.protocol.graph
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
   - :args - Map of fn-id -> [arg records with resolved inheritance]

   Note: This namespace does NOT define protocols to avoid circular deps.
   The ExecutionGraphReader extension is done in interface.clj."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.config :as config]))


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


;; === UUID constants for SQL/Cypher queries ===

(def uuid-regex-pattern
  "PostgreSQL regex pattern for UUID validation.
   Format: 8-4-4-4-12 hexadecimal characters."
  "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


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
  [fns args args-by-fn args-by-id arg-roots])


(defn- build-args-by-fn-index
  "Builds index of fn-id -> [args].
   Provides O(1) lookup by fn-id instead of O(n) filter."
  [args]
  (reduce
    (fn [acc arg]
      (update acc (:fn-id arg) (fnil conj []) arg))
    {}
    args))


(defn- build-args-by-id-index
  "Builds index of arg-id -> arg.
   Provides O(1) lookup by arg-id for pass-through args resolution."
  [args]
  (into {} (map (fn [a] [(:id a) a])) args))


(defn- find-root-arg-id
  "Follows source-id chain to find the root arg (the one with name).
   Returns the root arg's id. Uses args-by-id for O(1) lookups."
  [args-by-id arg-id max-depth]
  (loop [current-id arg-id
         depth 0]
    (when (> depth max-depth)
      (throw (ex-info "Source-id chain exceeds maximum depth"
                      {:type :graph-error/source-chain-too-deep
                       :arg-id arg-id
                       :max-depth max-depth})))
    (let [arg (get args-by-id current-id)]
      (if-let [source-id (:source-id arg)]
        (recur source-id (inc depth))
        current-id))))


(defn- build-arg-roots-index
  "Builds index of arg-id -> root-arg-id.
   Root arg is the one at the end of source-id chain (has :name, no :source-id).
   Provides O(1) lookup for resolving arg names at runtime."
  [args-by-id]
  (let [max-depth 100]
    (reduce-kv
      (fn [acc arg-id _arg]
        (assoc acc arg-id (find-root-arg-id args-by-id arg-id max-depth)))
      {}
      args-by-id)))


(defn ->execution-graph
  "Creates an ExecutionGraphResult record from a map.
   Validates that all required keys are present and non-empty.
   Builds indexes for O(1) lookup."
  [{:keys [fns args]
    :or {args []}}]
  (when-not (map? fns)
    (throw (ex-info "ExecutionGraphResult requires :fns map"
                    {:type :invalid-data :received (type fns)})))
  (when (empty? fns)
    (throw (ex-info "ExecutionGraphResult :fns must contain at least target function"
                    {:type :invalid-data :hint "Check that fn-id exists in storage"})))
  (when-not (sequential? args)
    (throw (ex-info "ExecutionGraphResult requires :args sequence"
                    {:type :invalid-data :received (type args)})))
  (let [args-by-id (build-args-by-id-index args)
        arg-roots (build-arg-roots-index args-by-id)]
    (->ExecutionGraphResult fns args (build-args-by-fn-index args) args-by-id arg-roots)))


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


(defn get-graph-args
  "Returns all args from an execution graph.
   Prefer this over direct :args access for forward compatibility."
  [graph]
  (:args graph))


(defn get-graph-args-for-fn
  "Returns args for a specific fn-id from an execution graph.
   Uses pre-built index for O(1) lookup."
  [graph fn-id]
  (get (:args-by-fn graph) fn-id []))


(defn get-root-arg-name
  "Returns the name of the root arg for a given arg.
   Uses pre-built arg-roots index for O(1) lookup.
   Root arg is at the end of source-id chain (base-fn arg with :name)."
  [graph arg]
  (let [arg-id (:id arg)
        root-id (get (:arg-roots graph) arg-id arg-id)
        root-arg (get (:args-by-id graph) root-id)]
    (:name root-arg)))


;; === Graph Resolution BFS Algorithm ===
;;
;; These functions take loader-specific functions as parameters to avoid
;; protocol dependencies. Storage backends provide the loader functions.

(defn extract-fn-refs-from-args
  "Extracts fn-ids referenced in args.
   Returns set of fn-ids that need to be loaded.

   Two reference types:
   1. ref-id: direct fn reference (execute and use result)
   2. value with UUID: fn-id passed as value (for HOF)"
  [args]
  (->> args
       (mapcat (fn [arg]
                 (cond-> []
                   (some? (:ref-id arg)) (conj (:ref-id arg))
                   (and (some? (:value arg)) (uuid? (:value arg))) (conj (:value arg)))))
       (remove nil?)
       (set)))


(defn process-fn-node
  "Processes a single fn node during graph resolution.
   Returns {:fns updated-fns :args updated-args :new-fn-refs #{fn-ids-to-visit}}.

   Arguments:
   - load-fn-record: (fn [fn-id] -> fn-record)
   - load-args-for-fn: (fn [fn-id] -> [arg-records])
   - current-fn-id: UUID of fn to process
   - fns: current accumulated fns map
   - args: current accumulated args vector"
  [load-fn-record load-args-for-fn current-fn-id fns args]
  (if-let [fn-rec (load-fn-record current-fn-id)]
    (let [fn-args (load-args-for-fn current-fn-id)
          new-fn-refs (extract-fn-refs-from-args fn-args)
          ;; Also check parent-id reference
          parent-ref (when-let [parent-id (:parent-id fn-rec)]
                       #{parent-id})]
      {:fns (assoc fns current-fn-id fn-rec)
       :args (into args fn-args)
       :new-fn-refs (set/union new-fn-refs (or parent-ref #{}))})
    {:fns fns :args args :new-fn-refs #{}}))


(defn resolve-execution-graph-bfs
  "Shared BFS algorithm for execution graph resolution.
   Takes loader functions as parameters for backend-specific data access.

   Arguments:
   - load-fn-record: (fn [fn-id] -> fn-record)
   - load-args-for-fn: (fn [fn-id] -> [arg-records])
   - fn-id: starting function UUID

   Returns ExecutionGraphResult record."
  [load-fn-record load-args-for-fn fn-id]
  (loop [to-visit #{fn-id}
         visited #{fn-id}
         fns {}
         args []
         iter-count 0]
    (check-graph-iteration-limit! iter-count fn-id)
    (if (empty? to-visit)
      (->execution-graph {:fns fns :args args})
      (let [current-fn-id (first to-visit)
            rest-to-visit (disj to-visit current-fn-id)
            {:keys [fns args new-fn-refs]}
            (process-fn-node load-fn-record load-args-for-fn
                             current-fn-id fns args)
            new-to-visit (set/difference new-fn-refs visited)
            new-visited (set/union visited new-to-visit)]
        (recur (set/union rest-to-visit new-to-visit)
               new-visited
               fns
               args
               (inc iter-count))))))
