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

   The record contains all data needed to execute a function under
   the slot/binding model:
   - :fns        - Map of fn-id -> fn record
   - :slots      - vector of slot rows reachable from the target
   - :fn-slots   - vector of (fn-id, slot-id, position) junction rows
   - :bindings   - vector of binding rows (per-fn slot customizations)
   - :list-items - vector of binding-list-item rows
   plus by-key convenience indexes (`fn-slots-by-fn`,
   `bindings-by-fn`, `items-by-binding`).

   Note: This namespace does NOT define protocols to avoid circular
   deps. Protocol surface lives alongside `StorageCRUD` /
   `ExecutionGraph` in this package's `core` ns."
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
;;
;; Slot/fn-slot/binding model: each field carries the corresponding
;; entity collection plus convenience indexes. The legacy `:args`
;; field is dropped — there is no `arg` table anymore.

(defrecord ExecutionGraphResult
  [fns slots fn-slots bindings list-items
   fn-slots-by-fn bindings-by-fn items-by-binding])


(defn- index-by-key
  [k coll]
  (reduce (fn [acc r] (update acc (get r k) (fnil conj []) r)) {} coll))


(defn ->execution-graph
  "Creates an ExecutionGraphResult record from a map carrying the
   slot/fn-slot/binding entities. Builds convenience indexes."
  [{:keys [fns slots fn-slots bindings list-items]
    :or {slots [] fn-slots [] bindings [] list-items []}}]
  (when-not (map? fns)
    (throw (ex-info "ExecutionGraphResult requires :fns map"
                    {:type :invalid-data :received (type fns)})))
  (when (empty? fns)
    (throw (ex-info "ExecutionGraphResult :fns must contain at least the target fn"
                    {:type :invalid-data :hint "Check that fn-id exists in storage"})))
  (->ExecutionGraphResult
    fns
    (vec slots)
    (vec fn-slots)
    (vec bindings)
    (vec list-items)
    (index-by-key :fn-id fn-slots)
    (index-by-key :fn-id bindings)
    (index-by-key :binding-id list-items)))


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


(defn get-graph-slots
  [graph]
  (:slots graph))


(defn get-graph-fn-slots
  [graph]
  (:fn-slots graph))


(defn get-graph-bindings
  [graph]
  (:bindings graph))


(defn get-graph-list-items
  [graph]
  (:list-items graph))


(defn get-bindings-for-fn
  "Returns bindings for a specific fn-id. O(1) via the index."
  [graph fn-id]
  (get (:bindings-by-fn graph) fn-id []))


(defn get-fn-slots-for-fn
  "Returns fn-slot junction rows for a specific fn-id. O(1)."
  [graph fn-id]
  (get (:fn-slots-by-fn graph) fn-id []))


(defn get-items-for-binding
  "Returns the binding-list-item rows for a binding-id. O(1)."
  [graph binding-id]
  (get (:items-by-binding graph) binding-id []))


;; === Graph Resolution BFS Algorithm ===
;;
;; These functions take loader-specific functions as parameters to avoid
;; protocol dependencies. Storage backends provide the loader functions.

(defn- extract-fn-refs-from-bindings
  [bindings]
  (into #{}
        (mapcat (fn [b]
                  (cond-> []
                    (some? (:ref-fn-id b)) (conj (:ref-fn-id b))
                    (some? (:type-override-fn-id b)) (conj (:type-override-fn-id b)))))
        bindings))


(defn- extract-fn-refs-from-items
  [items]
  (into #{} (keep :ref-fn-id) items))


(defn process-fn-node
  "Process one fn during BFS. Loaders return: fn record, fn-slot
   junctions, bindings, and per-binding items. Returns
   {:fns :fn-slots :bindings :list-items :new-fn-refs}."
  [{:keys [load-fn-record load-fn-slots-for-fn
           load-bindings-for-fn load-items-for-binding]}
   current-fn-id state]
  (let [{:keys [fns fn-slots bindings list-items]} state]
    (if-let [fn-rec (load-fn-record current-fn-id)]
      (let [fs   (load-fn-slots-for-fn current-fn-id)
            bs   (load-bindings-for-fn current-fn-id)
            items (mapcat (fn [b] (load-items-for-binding (:id b))) bs)
            ref-from-bs (extract-fn-refs-from-bindings bs)
            ref-from-items (extract-fn-refs-from-items items)
            parent-refs (into #{} (remove nil?) (:parent-ids fn-rec))
            type-refs (into #{}
                            (keep #(get fn-rec %))
                            [:base-fn-id :element-fn-id :return-type-fn-id])]
        {:fns        (assoc fns current-fn-id fn-rec)
         :fn-slots   (into fn-slots fs)
         :bindings   (into bindings bs)
         :list-items (into list-items items)
         :new-fn-refs (reduce into #{}
                              [ref-from-bs ref-from-items parent-refs type-refs])})
      (assoc state :new-fn-refs #{}))))


(defn resolve-execution-graph-bfs
  "Shared BFS resolution for the slot/fn-slot/binding model. Loaders:
     :load-fn-record         (fn [fn-id] → fn-row)
     :load-fn-slots-for-fn   (fn [fn-id] → [fn-slot-row …])
     :load-bindings-for-fn   (fn [fn-id] → [binding-row …])
     :load-items-for-binding (fn [binding-id] → [item-row …])
     :load-all-slots         (fn [] → [slot-row …])

   Slots are pulled in bulk (no per-fn lookup) since they're a small
   immutable set that's shared across fns via fn-slot junctions."
  [{:keys [load-all-slots] :as loaders} fn-id]
  (loop [to-visit #{fn-id}
         visited #{fn-id}
         state {:fns {} :fn-slots [] :bindings [] :list-items []}
         iter-count 0]
    (check-graph-iteration-limit! iter-count fn-id)
    (if (empty? to-visit)
      (->execution-graph (assoc state :slots (load-all-slots)))
      (let [current-id (first to-visit)
            rest-to-visit (disj to-visit current-id)
            {:keys [new-fn-refs] :as state'}
            (process-fn-node loaders current-id state)
            new-to-visit (set/difference new-fn-refs visited)
            new-visited (set/union visited new-to-visit)]
        (recur (set/union rest-to-visit new-to-visit)
               new-visited
               (dissoc state' :new-fn-refs)
               (inc iter-count))))))
