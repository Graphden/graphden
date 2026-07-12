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
