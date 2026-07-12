(ns graphden.fleet.rebalance
  "Rebalance planner (docs/FLEET_RFC.md §6.3). Given the fleet's cells with live
   weights and the CURRENT placement, decide the move set that evens per-pod
   load — moving the FEWEST cells, and only when the imbalance is worth a move.

   Two levers keep it from thrashing:

   - CHURN minimisation. Not a diff against a from-scratch LPT pack (that would
     relocate cells that are already fine). Instead a steepest-descent: while the
     busiest pod exceeds the lightest, move ONE cell from busiest→lightest — the
     one whose move most reduces the max−min spread — and stop the moment no
     single move helps. Each emitted move is one that genuinely flattens load.

   - HYSTERESIS. A move costs a compile + cache-warm on the target, so a marginal
     gain isn't worth it. The whole plan is discarded unless total improvement
     (starting minus final imbalance) exceeds `:min-improvement`. The controller
     adds the time dimension (imbalance must PERSIST across ticks) on top of this
     magnitude floor.

   Scope: rebalances cells that ALREADY have a holder in the fleet. A cell with
   no current holder (a freshly-appeared service/app, or one on a departed pod)
   is returned under `:unplaced` for the controller to place initially — keeping
   'balance existing load' and 'place new load' as separate, clear steps."
  (:require
    [graphden.fleet.packer :as packer]))


(defn imbalance
  "Load spread across executors — max minus min per-pod load. 0.0 when perfectly
   even (or fewer than two pods). The scalar the planner drives toward zero."
  [loads]
  (if (< (count loads) 2)
    0.0
    (- (double (apply max (vals loads)))
       (double (apply min (vals loads))))))


(defn- cell-key
  [{:keys [org entry-fn-id]}]
  [org entry-fn-id])


(defn- extremes
  "[lightest busiest] executor ids by current load, ties broken by id so the
   descent is deterministic."
  [loads executors]
  (let [ordered (sort-by (juxt loads identity) executors)]
    [(first ordered) (last ordered)]))


(defn- best-move
  "Among the cells currently on `busiest`, the key whose move to `lightest` most
   reduces imbalance — or nil if no such move strictly helps. `weight-of` maps a
   cell-key to its weight; `holder` is the evolving assignment."
  [loads holder weight-of busiest lightest]
  (let [current-imb (imbalance loads)
        on-busiest (keep (fn [[k e]] (when (= e busiest) k)) holder)]
    (->> on-busiest
         (map (fn [k]
                (let [w (weight-of k)
                      after (-> loads (update busiest - w) (update lightest + w))]
                  {:key k :imbalance (imbalance after)})))
         (filter #(< (:imbalance %) current-imb))
         (sort-by (juxt :imbalance (comp str :key)))
         first)))


(defn rebalance
  "Plan the moves that rebalance `cells` over `executors`, given `current`
   placement (`{[org entry] executor-id}`).

   `opts` — `{:min-improvement <double> :max-moves <int>}`. `:min-improvement`
   (default 0.0) is the hysteresis floor: the plan is dropped unless it improves
   imbalance by more than this. `:max-moves` caps the plan size.

   Returns
     `{:moves [{:org :entry-fn-id :from :to} ...]  ; churn-minimising, applied in order
       :unplaced [[org entry-fn-id] ...]           ; cells needing an initial placement
       :current-imbalance <double>
       :planned-imbalance <double>
       :improvement <double>}`.
   `:moves` is empty when the fleet is already balanced within the floor."
  [cells current executors {:keys [min-improvement max-moves]
                            :or {min-improvement 0.0 max-moves Integer/MAX_VALUE}}]
  (let [exec-set (set executors)
        placed? #(contains? exec-set (get current (cell-key %)))
        placed (filter placed? cells)
        unplaced (into [] (comp (remove placed?) (map cell-key)) cells)
        weight-of (into {} (map (juxt cell-key (comp double :weight))) placed)
        init-loads (packer/loads-of placed current executors)
        init-imb (imbalance init-loads)
        result (loop [loads init-loads
                      holder (select-keys current (map cell-key placed))
                      moves []]
                 (let [[lightest busiest] (extremes loads executors)]
                   (if (or (= lightest busiest)
                           (>= (count moves) max-moves)
                           (nil? busiest))
                     {:loads loads :moves moves}
                     (if-let [{:keys [key]} (best-move loads holder weight-of busiest lightest)]
                       (let [w (weight-of key)]
                         (recur (-> loads (update busiest - w) (update lightest + w))
                                (assoc holder key lightest)
                                (conj moves {:org (first key) :entry-fn-id (second key)
                                             :from busiest :to lightest})))
                       {:loads loads :moves moves}))))
        final-imb (imbalance (:loads result))
        improvement (- init-imb final-imb)
        keep? (> improvement min-improvement)]
    {:moves (if keep? (:moves result) [])
     :unplaced unplaced
     :current-imbalance init-imb
     :planned-imbalance (if keep? final-imb init-imb)
     :improvement improvement}))
