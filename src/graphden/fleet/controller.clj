(ns graphden.fleet.controller
  "Placement controller (docs/FLEET_RFC.md §6.3). Phase 1 scope: EXECUTE a move
   on command — the rebalance DECISION (which cell moves where) is still human /
   heuristic; Phase 2 automates it with a packer + hysteresis.

   A move is a three-step dance that keeps the cell CONTINUOUSLY served while it
   relocates from its current holder to a target executor:

     1. target LOADS the cell   — compile its forward-closure; target is ready.
     2. placement epoch BUMPED  — the routing map flips to the target. New
                                  requests forward there; in-flight requests
                                  keep draining on the source.
     3. source EVICTS the cell  — drop the now-unrouted closure.

   The ORDER is the correctness argument:

   - load BEFORE the flip ⇒ no request ever routes to a pod that hasn't compiled
     the cell (else a `421`/miss storm during the move).
   - a target-load FAILURE aborts BEFORE the flip ⇒ a failed move is a no-op,
     never an outage — the source stays the holder, routing unchanged.
   - evict AFTER the flip ⇒ requests already in flight on the source still find
     their fns. An evict failure is post-flip: routing is already correct, so it
     leaks the source's closure (reclaimed on the next reconcile / restart), it
     does not break the move.

   The cross-pod effects are SEAMS — `move-cell!` owns the sequencing + the
   placement write; `load-on` / `evict-on` own the transport (a directed, ACKed
   command in prod; a direct in-process `load-cell!` / `evict-cell!` in tests and
   single-node). That keeps the orchestration + its invariants fully in-JVM
   testable, independent of how a command reaches another pod.

   Seam contract:
     load-on  : (fn [executor-id root-fn-id]) → truthy IFF the target has
                compiled the cell. MUST be synchronous / ACKed — the flip
                depends on it, so a fire-and-forget transport is not admissible
                here (it would flip before the target is ready).
     evict-on : (fn [executor-id root-fn-id]) → ignored. Best-effort, post-flip."
  (:require
    [clojure.tools.logging :as log]
    [graphden.fleet.placement :as placement]))


(defn move-cell!
  "Relocate `(org, entry-fn-id)`'s cell to `to-executor`, keeping it served
   throughout (see ns docstring). Reads the current placement for the source +
   epoch, so it also performs an INITIAL placement (no current holder ⇒ load +
   assign, no evict).

   `cmd` keys: `:org` `:entry-fn-id` `:to-executor` `:load-on` `:evict-on`.

   Returns:
     {:ok true  :from <id-or-nil> :to <id> :epoch <n>}          — moved.
     {:ok true  :from <id> :to <id> :epoch <n> :noop true}      — already there.
     {:ok false :reason :no-target}                             — nil target.
     {:ok false :reason :target-load-failed :from <id> :to <id>} — aborted pre-flip."
  [storage {:keys [org entry-fn-id to-executor load-on evict-on]}]
  (let [current (placement/placement-for storage org entry-fn-id)
        from (:executor-id current)
        epoch (:epoch current)]
    (cond
      (nil? to-executor)
      {:ok false :reason :no-target}

      (= from to-executor)
      ;; Already placed here — idempotent success, no load/flip/evict churn.
      {:ok true :from from :to to-executor :epoch epoch :noop true}

      :else
      (let [loaded? (try
                      (boolean (load-on to-executor entry-fn-id))
                      (catch Exception e
                        (log/warn e "move-cell! target load threw — aborting move (no flip)"
                                  {:org org :entry-fn-id entry-fn-id :to to-executor})
                        false))]
        (if-not loaded?
          {:ok false :reason :target-load-failed :from from :to to-executor}
          (let [new-epoch (inc (or epoch 0))]
            ;; Flip the routing map: new requests forward to the target now; the
            ;; source keeps serving whatever it already admitted.
            (placement/assign! storage {:org org
                                        :entry-fn-id entry-fn-id
                                        :executor-id to-executor
                                        :epoch new-epoch})
            ;; Source drops the cell. Post-flip, so a failure only leaks the
            ;; source's closure (reclaimed later) — the move already succeeded.
            (when from
              (try
                (evict-on from entry-fn-id)
                (catch Exception e
                  (log/warn e "move-cell! source evict threw — cell relocated, source closure leaked until reclaim"
                            {:org org :entry-fn-id entry-fn-id :from from}))))
            {:ok true :from from :to to-executor :epoch new-epoch}))))))
