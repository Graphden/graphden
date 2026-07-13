(ns graphden.fleet.rebalance-test
  "Rebalance planner (`graphden.fleet.rebalance`, docs/FLEET_RFC.md §6.3). Pure
   steepest-descent + hysteresis over synthetic cells/placements — no container."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fleet.rebalance :as rebalance]))


(defn- cell
  [entry weight]
  {:org "o" :entry-fn-id entry :weight weight})


(def ^:private c1 #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private c2 #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private c3 #uuid "00000000-0000-0000-0000-000000000003")
(def ^:private c4 #uuid "00000000-0000-0000-0000-000000000004")


(deftest imbalance-is-max-minus-min
  (is (= 0.0 (rebalance/imbalance {"a" 5.0 "b" 5.0})))
  (is (= 3.0 (rebalance/imbalance {"a" 5.0 "b" 2.0})))
  (is (= 0.0 (rebalance/imbalance {"solo" 9.0})) "fewer than two pods → 0"))


(deftest balanced-fleet-plans-no-moves
  (let [cells [(cell c1 1) (cell c2 1) (cell c3 1) (cell c4 1)]
        current {["o" c1] "e1" ["o" c2] "e1" ["o" c3] "e2" ["o" c4] "e2"}
        {:keys [moves current-imbalance]} (rebalance/rebalance cells current ["e1" "e2"] {})]
    (is (= 0.0 current-imbalance))
    (is (empty? moves) "already even → nothing to do")))


(deftest skewed-fleet-flattens-with-fewest-moves
  ;; all three unit-weight cells on e1, e2 idle. Best 2-pod split is 2/1 (imb 1),
  ;; reachable in ONE move — the planner must not over-move to chase imb 0.
  (let [cells [(cell c1 1) (cell c2 1) (cell c3 1)]
        current {["o" c1] "e1" ["o" c2] "e1" ["o" c3] "e1"}
        {:keys [moves current-imbalance planned-imbalance improvement]}
        (rebalance/rebalance cells current ["e1" "e2"] {})]
    (is (= 3.0 current-imbalance))
    (is (= 1.0 planned-imbalance) "descends to the best 2-pod split, not lower")
    (is (= 2.0 improvement))
    (is (= 1 (count moves)) "one move suffices — churn minimised")
    (is (= {:org "o" :entry-fn-id c1 :from "e1" :to "e2"} (first moves))
        "moves the lowest-id cell off the busiest pod (deterministic)")))


(deftest hysteresis-floor-suppresses-marginal-moves
  ;; e1 carries one unit more than e2 — a real but tiny imbalance.
  (let [cells [(cell c1 1) (cell c2 1) (cell c3 1)]
        current {["o" c1] "e1" ["o" c2] "e1" ["o" c3] "e2"}]
    (testing "below the floor → no moves (not worth a compile+warm)"
      (let [{:keys [moves planned-imbalance]}
            (rebalance/rebalance cells current ["e1" "e2"] {:min-improvement 5.0})]
        (is (empty? moves))
        (is (= 1.0 planned-imbalance) "reports the untouched imbalance")))
    (testing "with no floor the same imbalance is left alone too (already optimal)"
      (let [{:keys [moves]} (rebalance/rebalance cells current ["e1" "e2"] {})]
        (is (empty? moves) "2/1 split is the best possible → no strictly-helping move")))))


(deftest unplaced-cells-are-surfaced-not-moved
  (let [cells [(cell c1 1) (cell c2 1) (cell c3 1)]
        ;; c3 has no holder — a freshly-appeared cell.
        current {["o" c1] "e1" ["o" c2] "e2"}
        {:keys [moves unplaced]} (rebalance/rebalance cells current ["e1" "e2"] {})]
    (is (= [["o" c3]] unplaced) "the holder-less cell is returned for initial placement")
    (is (empty? moves) "the placed cells are already balanced")))


(deftest max-moves-caps-the-plan
  (let [cells [(cell c1 1) (cell c2 1) (cell c3 1) (cell c4 1)]
        current {["o" c1] "e1" ["o" c2] "e1" ["o" c3] "e1" ["o" c4] "e1"}
        {:keys [moves]} (rebalance/rebalance cells current ["e1" "e2"] {:max-moves 1})]
    (is (= 1 (count moves)) "stops at the cap even if more moves would help further")))


;; ---------------------------------------------------------------------------
;; Overlap-aware rebalance (docs/FLEET_RFC.md T4.5) — opt-in via :w-overlap.
;; Among imbalance-reducing moves (the hard filter), prefer the one that
;; co-locates the cell with code it shares on the destination.
;; ---------------------------------------------------------------------------

(deftest rebalance-overlap-prefers-co-locating-move
  ;; p1 (busiest) holds c1 + c2 (equal weight); p2 (lightest) holds c3, whose
  ;; closure shares :a :b with c2 but nothing with c1. Moving EITHER c1 or c2
  ;; flattens load equally, so pure descent breaks the tie by key; overlap
  ;; instead moves the code-sharing cell (c2) onto its shared pod.
  (let [cells [{:org "o" :entry-fn-id c1 :weight 2.0 :closure #{:x :y}}
               {:org "o" :entry-fn-id c2 :weight 2.0 :closure #{:a :b}}
               {:org "o" :entry-fn-id c3 :weight 0.0 :closure #{:a :b :z}}]
        current {["o" c1] "p1" ["o" c2] "p1" ["o" c3] "p2"}
        execs ["p1" "p2"]]
    (testing "w-overlap 0 → pure churn-min descent breaks the tie by key (moves c1)"
      (let [{:keys [moves]} (rebalance/rebalance cells current execs {})]
        (is (= 1 (count moves)))
        (is (= c1 (:entry-fn-id (first moves))) "lower key wins the imbalance tie")))
    (testing "w-overlap > 0 → moves c2 instead, co-locating it with the shared code on p2"
      (let [{:keys [moves]} (rebalance/rebalance cells current execs {:w-overlap 2.0})]
        (is (= 1 (count moves)))
        (is (= c2 (:entry-fn-id (first moves))) "the code-sharing cell moves")
        (is (= "p2" (:to (first moves))))))
    (testing "overlap never overrides the load constraint — still exactly one balancing move"
      (let [{:keys [moves current-imbalance planned-imbalance]}
            (rebalance/rebalance cells current execs {:w-overlap 2.0})]
        (is (= 4.0 current-imbalance))
        (is (= 0.0 planned-imbalance) "load is still fully balanced")
        (is (= 1 (count moves)))))))
