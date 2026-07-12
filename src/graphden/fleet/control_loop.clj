(ns graphden.fleet.control-loop
  "Autonomous placement controller (docs/FLEET_RFC.md §6.3) — the brain that
   DECIDES moves (Phase 2), on top of the Phase-1 machinery that executes them.

   This namespace holds the PURE decision (`plan-tick`); the running loop + its
   leader-lock + side-effecting execution live in the integrant component that
   drives it. One control pass:

     1. place any cell that has no holder yet (initial placement — no hysteresis,
        new load must land somewhere immediately);
     2. rebalance existing load, but only when the imbalance has PERSISTED — the
        rebalancer's magnitude floor (`:min-improvement`) says the gap is big
        enough to bother, and this loop's `:sustain-ticks` says it has lasted
        long enough to not be a transient spike.

   Keeping the decision pure means the whole policy — thresholds, sustained
   counting, initial-vs-rebalance split — is unit-tested without a fleet."
  (:require
    [graphden.fleet.metrics :as metrics]
    [graphden.fleet.packer :as packer]
    [graphden.fleet.rebalance :as rebalance]
    [graphden.storage.protocol.core :as sp]))


(defn- least-loaded
  [loads executors]
  (first (sort-by (juxt loads identity) executors)))


(defn- plan-initial-placements
  "Assign each `unplaced` cell-key to the least-loaded executor, greedily and
   deterministically (unplaced keys sorted, running loads updated per pick).
   Returns `[{:org :entry-fn-id :to} ...]`."
  [unplaced cells current executors]
  (if (empty? executors)
    []
    (let [weight-of (into {} (map (juxt (juxt :org :entry-fn-id) (comp double :weight))) cells)]
      (loop [remaining (sort unplaced)
             loads (packer/loads-of cells current executors)
             acc []]
        (if-let [k (first remaining)]
          (let [target (least-loaded loads executors)]
            (recur (rest remaining)
                   (update loads target + (weight-of k 0.0))
                   (conj acc {:org (first k) :entry-fn-id (second k) :to target})))
          acc)))))


(defn plan-tick
  "Decide one control pass, PURE. Inputs:

     inputs — `{:cells [{:org :entry-fn-id :weight}] :current {[org entry] exec}
               :executors [id ...]}` — the live reading.
     state  — the prior tick's state (`{:over-count n}`, `{}` on the first tick).
     opts   — `{:min-improvement <double> :sustain-ticks <int> :max-moves <int>}`.

   Returns
     `{:initial-placements [{:org :entry-fn-id :to} ...]  ; apply every tick
       :moves [{:org :entry-fn-id :from :to} ...]         ; apply only when sustained
       :current-imbalance <double>
       :state {:over-count n}}`.

   Rebalance moves are held until the imbalance (already past the rebalancer's
   magnitude floor) has recurred for `:sustain-ticks` consecutive ticks; the
   counter resets when it fires or when a tick finds nothing worth moving.
   Initial placements are NOT gated — new load lands at once."
  [{:keys [cells current executors]} state {:keys [min-improvement sustain-ticks max-moves]
                                            :or {min-improvement 0.0 sustain-ticks 1
                                                 max-moves Integer/MAX_VALUE}}]
  (let [{:keys [moves unplaced current-imbalance]}
        (rebalance/rebalance cells current executors
                             {:min-improvement min-improvement :max-moves max-moves})
        would-move? (boolean (seq moves))
        over-count (if would-move? (inc (:over-count state 0)) 0)
        fire? (and would-move? (>= over-count sustain-ticks))]
    {:initial-placements (plan-initial-placements unplaced cells current executors)
     :moves (if fire? moves [])
     :current-imbalance current-imbalance
     :state {:over-count (if fire? 0 over-count)}}))


;; =============================================================================
;; Reading the live fleet (the inputs `plan-tick` decides over)
;; =============================================================================

(defn- safe-query
  "Query, tolerating an entity the deployment doesn't define (`:org` is
   tenancy-only) — a missing entity reads as no rows, not a crash."
  [storage entity where]
  (try
    (sp/query-entities storage entity where)
    (catch Exception _ nil)))


(defn current-placement
  "The fleet's current placement as `{[org entry-fn-id] executor-id}`, taking the
   highest-epoch row per cell (defensive against a stale duplicate a partial move
   might leave)."
  [storage]
  (into {}
        (map (fn [[k rows]]
               [k (:executor-id (first (sort-by :epoch > rows)))]))
        (group-by (juxt :org :entry-fn-id) (safe-query storage :placement {}))))


(defn discover-cells
  "The cells the fleet manages, each weighted by `metrics/cell-weight`
   (fn-count + org load): every tenant app cell (an `:org` row's
   `:handler-fn-id`) unioned with whatever is already placed. Platform
   `:service` cells are deliberately EXCLUDED — the reconciler owns their
   advisory-lock singleton placement, so the fleet controller must not also
   move them."
  [storage forward-deps]
  (let [org-roots (keep (fn [o]
                          (when-let [h (:handler-fn-id o)]
                            {:org (:name o) :entry-fn-id h}))
                        (safe-query storage :org {}))
        placed-roots (map (fn [r] {:org (:org r) :entry-fn-id (:entry-fn-id r)})
                          (safe-query storage :placement {}))]
    (map (fn [{:keys [org entry-fn-id]}]
           {:org org
            :entry-fn-id entry-fn-id
            :weight (metrics/cell-weight forward-deps storage org entry-fn-id)})
         (distinct (concat org-roots placed-roots)))))


(defn run-tick!
  "One control pass with side effects behind the `move-fn` seam. Reads the live
   cells + current placement, decides via `plan-tick`, then realises the decision
   by calling `move-fn` with `{:org :entry-fn-id :to-executor}` for each initial
   placement and then each (sustained) move — `move-fn` owns the `move-cell!` +
   directed transport. Returns the `plan-tick` decision (so the caller carries
   `:state` to the next tick).

   `env` — `{:storage :forward-deps :executors :move-fn}`."
  [{:keys [storage forward-deps executors move-fn]} state opts]
  (let [cells (discover-cells storage forward-deps)
        current (current-placement storage)
        decision (plan-tick {:cells cells :current current :executors executors} state opts)]
    (doseq [{:keys [org entry-fn-id to]} (:initial-placements decision)]
      (move-fn {:org org :entry-fn-id entry-fn-id :to-executor to}))
    (doseq [{:keys [org entry-fn-id to]} (:moves decision)]
      (move-fn {:org org :entry-fn-id entry-fn-id :to-executor to}))
    decision))
