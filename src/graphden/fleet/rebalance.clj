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
    [clojure.set :as set]
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
   reduces imbalance — or nil if no such move strictly helps. Reducing imbalance
   is a HARD filter; among the moves that pass it, the pick minimises
   `imbalance-after − w-overlap·overlap(cell-closure, lightest's fns)`, so with
   `w-overlap` > 0 a move that also CO-LOCATES the cell with code it shares on the
   destination wins the tie (memory + a cheaper move). `w-overlap` 0 (or absent
   closures) reduces exactly to the pure churn-minimising descent. `weight-of` /
   `closure-of` map a cell-key to its weight / forward-closure; `pod-fns` maps an
   executor to the fn-set it currently holds."
  [loads holder weight-of closure-of pod-fns busiest lightest w-overlap]
  (let [current-imb (imbalance loads)
        dest-fns (get pod-fns lightest #{})
        on-busiest (keep (fn [[k e]] (when (= e busiest) k)) holder)]
    (->> on-busiest
         (map (fn [k]
                (let [w (weight-of k)
                      after (-> loads (update busiest - w) (update lightest + w))
                      imb (imbalance after)
                      ov (if-let [cl (get closure-of k)]
                           (count (set/intersection cl dest-fns))
                           0)]
                  {:key k :imbalance imb
                   :score (- imb (* (double w-overlap) ov))})))
         (filter #(< (:imbalance %) current-imb))
         (sort-by (juxt :score (comp str :key)))
         first)))


(defn rebalance
  "Plan the moves that rebalance `cells` over `executors`, given `current`
   placement (`{[org entry] executor-id}`).

   `opts` — `{:min-improvement <double> :max-moves <int> :w-overlap <double>}`.
   `:min-improvement` (default 0.0) is the hysteresis floor: the plan is dropped
   unless it improves imbalance by more than this. `:max-moves` caps the plan
   size. `:w-overlap` (default 0.0) makes an imbalance-reducing move prefer a
   destination that already holds the cell's code (needs cells carrying a
   `:closure`); 0 keeps the pure churn-minimising descent.

   Returns
     `{:moves [{:org :entry-fn-id :from :to} ...]  ; churn-minimising, applied in order
       :unplaced [[org entry-fn-id] ...]           ; cells needing an initial placement
       :current-imbalance <double>
       :planned-imbalance <double>
       :improvement <double>}`.
   `:moves` is empty when the fleet is already balanced within the floor."
  [cells current executors {:keys [min-improvement max-moves w-overlap]
                            :or {min-improvement 0.0 max-moves Integer/MAX_VALUE
                                 w-overlap 0.0}}]
  (let [exec-set (set executors)
        placed? #(contains? exec-set (get current (cell-key %)))
        placed (filter placed? cells)
        unplaced (into [] (comp (remove placed?) (map cell-key)) cells)
        weight-of (into {} (map (juxt cell-key (comp double :weight))) placed)
        closure-of (into {} (keep (fn [c] (when-let [cl (:closure c)] [(cell-key c) cl]))) placed)
        ;; Seed each pod's held-fn set from the cells currently on it, so overlap
        ;; is scored against real contents. A moved cell's fns are ADDED to the
        ;; destination as the descent proceeds (not removed from the source — an
        ;; over-estimate that only affects the secondary tiebreak, never the exact
        ;; imbalance descent).
        init-pod-fns (reduce (fn [m c]
                               (let [k (cell-key c) e (get current k)]
                                 (if (and e (:closure c) (contains? m e))
                                   (update m e into (:closure c))
                                   m)))
                             (zipmap executors (repeat #{}))
                             placed)
        init-loads (packer/loads-of placed current executors)
        init-imb (imbalance init-loads)
        result (loop [loads init-loads
                      holder (select-keys current (map cell-key placed))
                      pod-fns init-pod-fns
                      moves []]
                 (let [[lightest busiest] (extremes loads executors)]
                   (if (or (= lightest busiest)
                           (>= (count moves) max-moves)
                           (nil? busiest))
                     {:loads loads :moves moves}
                     (if-let [{:keys [key]} (best-move loads holder weight-of closure-of
                                                       pod-fns busiest lightest w-overlap)]
                       (let [w (weight-of key)
                             cl (get closure-of key)]
                         (recur (-> loads (update busiest - w) (update lightest + w))
                                (assoc holder key lightest)
                                (cond-> pod-fns cl (update lightest into cl))
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
