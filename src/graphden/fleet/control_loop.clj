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


(defn- plan-initial-placements
  "Assign each `unplaced` cell-key to the best target, greedily and
   deterministically (unplaced keys sorted, running loads updated per pick).
   With `:w-overlap` > 0 and cells carrying a `:closure`, a new cell prefers the
   pod already holding more of its closure (co-location) — otherwise it is the
   least-loaded pod. Returns `[{:org :entry-fn-id :to} ...]`."
  [unplaced cells current executors {:keys [w-overlap] :or {w-overlap 0.0}}]
  (if (empty? executors)
    []
    (let [key-of (juxt :org :entry-fn-id)
          weight-of (into {} (map (juxt key-of (comp double :weight))) cells)
          closure-of (into {} (keep (fn [c] (when-let [cl (:closure c)] [(key-of c) cl]))) cells)
          ;; Seed each pod's held-fn set from the cells ALREADY placed on it, so
          ;; overlap is scored against real current contents, not an empty pod.
          init-pod-fns (reduce (fn [m c]
                                 (let [k (key-of c)
                                       e (get current k)]
                                   (if (and e (:closure c) (contains? m e))
                                     (update m e into (:closure c))
                                     m)))
                               (zipmap executors (repeat #{}))
                               cells)]
      (loop [remaining (sort unplaced)
             loads (packer/loads-of cells current executors)
             pod-fns init-pod-fns
             acc []]
        (if-let [k (first remaining)]
          (let [cfns (get closure-of k)
                target (packer/best-target loads executors
                                           {:pod-fns pod-fns :cell-fns cfns :w-overlap w-overlap})]
            (recur (rest remaining)
                   (update loads target + (weight-of k 0.0))
                   (if cfns (update pod-fns target into cfns) pod-fns)
                   (conj acc {:org (first k) :entry-fn-id (second k) :to target})))
          acc)))))


(defn plan-tick
  "Decide one control pass, PURE. Inputs:

     inputs — `{:cells [{:org :entry-fn-id :weight}] :current {[org entry] exec}
               :executors [id ...]}` — the live reading.
     state  — the prior tick's state (`{:over-count n}`, `{}` on the first tick).
     opts   — `{:min-improvement <double> :sustain-ticks <int> :max-moves <int>
               :w-overlap <double>}` — `:w-overlap` > 0 makes initial placement
               co-locate cells that share code (default 0 = pure load balance).

   Returns
     `{:initial-placements [{:org :entry-fn-id :to} ...]  ; apply every tick
       :moves [{:org :entry-fn-id :from :to} ...]         ; apply only when sustained
       :current-imbalance <double>
       :state {:over-count n}}`.

   Rebalance moves are held until the imbalance (already past the rebalancer's
   magnitude floor) has recurred for `:sustain-ticks` consecutive ticks; the
   counter resets when it fires or when a tick finds nothing worth moving.
   Initial placements are NOT gated — new load lands at once."
  [{:keys [cells current executors]} state {:keys [min-improvement sustain-ticks max-moves w-overlap]
                                            :or {min-improvement 0.0 sustain-ticks 1
                                                 max-moves Integer/MAX_VALUE w-overlap 0.0}}]
  (let [{:keys [moves unplaced current-imbalance]}
        (rebalance/rebalance cells current executors
                             {:min-improvement min-improvement :max-moves max-moves
                              :w-overlap w-overlap})
        would-move? (boolean (seq moves))
        over-count (if would-move? (inc (:over-count state 0)) 0)
        fire? (and would-move? (>= over-count sustain-ticks))]
    {:initial-placements (plan-initial-placements unplaced cells current executors
                                                  {:w-overlap w-overlap})
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
   (fn-count + org load): every tenant app cell (an `:app-route` row's
   `(org, handler-fn-id)`) unioned with whatever is already placed. An org can
   run several named apps, so it contributes one cell per route. Platform
   `:service` cells are deliberately EXCLUDED — the reconciler owns their
   advisory-lock singleton placement, so the fleet controller must not also
   move them.

   `opts` — `{:with-closure? bool}`. When true each cell also carries its
   `:closure` (forward-closure fn-set) for overlap-aware placement; computed
   only on demand since it walks every cell's closure each tick."
  ([storage forward-deps] (discover-cells storage forward-deps {}))
  ([storage forward-deps {:keys [with-closure?]}]
   (let [app-roots (keep (fn [r]
                           (when-let [h (:handler-fn-id r)]
                             {:org (:org r) :entry-fn-id h}))
                         (safe-query storage :app-route {}))
         placed-roots (map (fn [r] {:org (:org r) :entry-fn-id (:entry-fn-id r)})
                           (safe-query storage :placement {}))]
     (map (fn [{:keys [org entry-fn-id]}]
            (cond-> {:org org
                     :entry-fn-id entry-fn-id
                     :weight (metrics/cell-weight forward-deps storage org entry-fn-id)}
              with-closure? (assoc :closure (metrics/cell-closure forward-deps entry-fn-id))))
          (distinct (concat app-roots placed-roots))))))


(defn run-tick!
  "One control pass with side effects behind the `move-fn` seam. Reads the live
   cells + current placement, decides via `plan-tick`, then realises the decision
   by calling `move-fn` with `{:org :entry-fn-id :to-executor}` for each initial
   placement and then each (sustained) move — `move-fn` owns the `move-cell!` +
   directed transport. Returns the `plan-tick` decision (so the caller carries
   `:state` to the next tick).

   `env` — `{:storage :forward-deps :executors :move-fn}`. With `:w-overlap` > 0
   in `opts`, cells are discovered WITH their closures so placement can co-locate
   code-sharing cells."
  [{:keys [storage forward-deps executors move-fn]} state opts]
  (let [cells (discover-cells storage forward-deps
                              {:with-closure? (pos? (double (:w-overlap opts 0.0)))})
        current (current-placement storage)
        decision (plan-tick {:cells cells :current current :executors executors} state opts)]
    (doseq [{:keys [org entry-fn-id to]} (:initial-placements decision)]
      (move-fn {:org org :entry-fn-id entry-fn-id :to-executor to}))
    (doseq [{:keys [org entry-fn-id to]} (:moves decision)]
      (move-fn {:org org :entry-fn-id entry-fn-id :to-executor to}))
    decision))
