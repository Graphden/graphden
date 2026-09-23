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
    [clojure.tools.logging :as log]))


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
                    (some? (:type-override-fn-id b)) (conj (:type-override-fn-id b))
                    ;; A `:resolved-value` binding references its resolver fn only
                    ;; through :resolver-fn-id — omitting it drops the resolver's
                    ;; closure (fn-not-found on first force). Mirrors the CTE +
                    ;; executor.compile.deps.
                    (some? (:resolver-fn-id b)) (conj (:resolver-fn-id b)))))
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
