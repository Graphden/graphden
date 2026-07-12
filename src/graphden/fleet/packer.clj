(ns graphden.fleet.packer
  "Cell placement packer (docs/FLEET_RFC.md §6.3). Given the fleet's cells (each
   with a `fleet.metrics/cell-weight`) and the available executors, decide the
   DESIRED placement — which executor should hold which cell — so per-pod load is
   as even as possible.

   Algorithm: LPT (longest-processing-time-first) greedy — sort cells heaviest
   first, place each on the currently least-loaded executor. This is the classic
   2-approximation for multiprocessor makespan (minimising the busiest pod's
   load), it is deterministic (ties broken by id), and it naturally SPREADS an
   org's cells across pods for load levelling rather than isolating them — which
   is the goal (§3.2: spread for load, not for isolation).

   Budget-free by design: the packer BALANCES; whether the busiest pod exceeds a
   per-pod budget (⇒ scale out) is a separate read over the returned `:loads`, so
   the packing policy and the capacity policy stay independent. Pure — the
   controller (Phase 2) feeds it live weights + the executor set and diffs the
   result against the current `:placement` map to derive moves.")


(defn loads-of
  "Per-executor total weight for an ARBITRARY placement (not necessarily the
   packer's own). Every executor in `executors` appears, at 0.0 if it holds
   nothing. A cell whose holder isn't among `executors` (unplaced, or a departed
   pod) contributes to no total — its weight is the caller's to re-place. Used by
   the rebalancer to score the CURRENT placement against a packed one."
  [cells placement executors]
  (reduce (fn [loads {:keys [org entry-fn-id weight]}]
            (let [holder (get placement [org entry-fn-id])]
              (cond-> loads
                (contains? loads holder) (update holder + (double weight)))))
          (zipmap executors (repeat 0.0))
          cells))


(defn- least-loaded
  "The executor with the smallest current load, ties broken by id order so the
   packing is deterministic across runs."
  [loads executors]
  (first (sort-by (juxt loads identity) executors)))


(defn pack
  "Assign every cell in `cells` to one of `executors`, LPT-greedy.

   `cells`     — seq of `{:org :entry-fn-id :weight}` (weight from
                 `fleet.metrics/cell-weight`).
   `executors` — seq of executor-id strings.

   Returns `{:placement {[org entry-fn-id] executor-id} :loads {executor-id
   total-weight}}`, or nil when there are no executors to place onto. Heaviest
   cell first, each onto the least-loaded pod; equal-weight cells are ordered by
   entry-fn-id so the output is stable."
  [cells executors]
  (when (seq executors)
    (let [execs (vec executors)
          ordered (sort-by (juxt (comp - double :weight) :entry-fn-id) cells)]
      (loop [remaining ordered
             loads (zipmap execs (repeat 0.0))
             placement {}]
        (if-let [cell (first remaining)]
          (let [target (least-loaded loads execs)]
            (recur (next remaining)
                   (update loads target + (double (:weight cell)))
                   (assoc placement [(:org cell) (:entry-fn-id cell)] target)))
          {:placement placement :loads loads})))))
