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
   result against the current `:placement` map to derive moves."
  (:require
    [clojure.set :as set]))


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


(defn least-loaded
  "The executor with the smallest current load, ties broken by id order so the
   result is deterministic. Shared by the packer's LPT loop and the control
   loop's initial placement."
  [loads executors]
  (first (sort-by (juxt loads identity) executors)))


(defn best-target
  "The executor to place a cell on: least EFFECTIVE load, where effective load
   discounts overlap — a pod that already holds much of the cell's forward-
   closure serves it more cheaply (§ overlap-accounting, docs/FLEET_RFC.md T4.5).

   `pod-fns`   — `{executor-id → #{fn-ids it already holds}}`.
   `cell-fns`  — this cell's closure (`#{fn-ids}`), or nil when unknown.
   `w-overlap` — how much a shared fn discounts load (0.0 = pure load balance).

   Score = `load(e) − w-overlap · |cell-fns ∩ pod-fns(e)|`; ties broken by id so
   the result is deterministic. With `w-overlap` 0 or a nil `cell-fns` this is
   exactly `least-loaded`, so overlap is strictly opt-in and never perturbs the
   verified pure-LPT behaviour."
  [loads executors {:keys [pod-fns cell-fns w-overlap] :or {w-overlap 0.0}}]
  (if (or (zero? (double w-overlap)) (nil? cell-fns))
    (least-loaded loads executors)
    (first (sort-by (fn [e]
                      (let [overlap (count (set/intersection
                                             cell-fns (get pod-fns e #{})))]
                        [(- (double (loads e)) (* (double w-overlap) overlap)) e]))
                    executors))))


(defn pack
  "Assign every cell in `cells` to one of `executors`, LPT-greedy.

   `cells`     — seq of `{:org :entry-fn-id :weight}` (weight from
                 `fleet.metrics/cell-weight`).
   `executors` — seq of executor-id strings.
   `opts`      — optional `{:cell-fns {[org entry] #{fn-ids}} :w-overlap n}`.
                 When `:w-overlap` > 0 and a cell's closure is in `:cell-fns`,
                 placement prefers the pod that already holds more of it (co-
                 locating cells that share code). Omitted / 0 ⇒ pure LPT.

   Returns `{:placement {[org entry-fn-id] executor-id} :loads {executor-id
   total-weight}}`, or nil when there are no executors to place onto. Heaviest
   cell first, each onto the best target; equal-weight cells are ordered by
   entry-fn-id so the output is stable."
  ([cells executors] (pack cells executors {}))
  ([cells executors {:keys [cell-fns w-overlap] :or {w-overlap 0.0}}]
   (when (seq executors)
     (let [execs (vec executors)
           ordered (sort-by (juxt (comp - double :weight) :entry-fn-id) cells)]
       (loop [remaining ordered
              loads (zipmap execs (repeat 0.0))
              pod-fns (zipmap execs (repeat #{}))
              placement {}]
         (if-let [cell (first remaining)]
           (let [ck [(:org cell) (:entry-fn-id cell)]
                 cfns (get cell-fns ck)
                 target (best-target loads execs {:pod-fns pod-fns :cell-fns cfns
                                                  :w-overlap w-overlap})]
             (recur (next remaining)
                    (update loads target + (double (:weight cell)))
                    (if cfns (update pod-fns target into cfns) pod-fns)
                    (assoc placement ck target)))
           {:placement placement :loads loads}))))))
