(ns graphden.fleet.control-loop-test
  "Pure control decision (`graphden.fleet.control-loop/plan-tick`,
   docs/FLEET_RFC.md §6.3) — sustained-hysteresis + initial-vs-rebalance split,
   tested without a fleet."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fleet.control-loop :as loop]))


(defn- cell
  [entry weight]
  {:org "o" :entry-fn-id entry :weight weight})


(def ^:private c1 #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private c2 #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private c3 #uuid "00000000-0000-0000-0000-000000000003")


(deftest initial-placements-land-immediately
  (testing "an unplaced cell is placed the same tick, no hysteresis"
    (let [inputs {:cells [(cell c1 1) (cell c2 1)]
                  :current {["o" c1] "e1"}          ; c2 unplaced
                  :executors ["e1" "e2"]}
          {:keys [initial-placements moves]} (loop/plan-tick inputs {} {})]
      (is (= [{:org "o" :entry-fn-id c2 :to "e2"}] initial-placements)
          "c2 placed on the idle pod at once")
      (is (empty? moves)))))


(deftest rebalance-waits-for-sustained-imbalance
  ;; three cells on e1, e2 idle — a real, floor-clearing imbalance. With
  ;; sustain-ticks 3 the move is withheld until the 3rd consecutive tick.
  (let [inputs {:cells [(cell c1 1) (cell c2 1) (cell c3 1)]
                :current {["o" c1] "e1" ["o" c2] "e1" ["o" c3] "e1"}
                :executors ["e1" "e2"]}
        opts {:sustain-ticks 3}
        t1 (loop/plan-tick inputs {} opts)
        t2 (loop/plan-tick inputs (:state t1) opts)
        t3 (loop/plan-tick inputs (:state t2) opts)]
    (testing "ticks 1 and 2 count but withhold the move"
      (is (empty? (:moves t1)))
      (is (= 1 (:over-count (:state t1))))
      (is (empty? (:moves t2)))
      (is (= 2 (:over-count (:state t2)))))
    (testing "tick 3 fires the move and resets the counter"
      (is (= 1 (count (:moves t3))))
      (is (= 0 (:over-count (:state t3)))))))


(deftest transient-spike-resets-the-counter
  (let [skewed {:cells [(cell c1 1) (cell c2 1) (cell c3 1)]
                :current {["o" c1] "e1" ["o" c2] "e1" ["o" c3] "e1"}
                :executors ["e1" "e2"]}
        balanced {:cells [(cell c1 1) (cell c2 1)]
                  :current {["o" c1] "e1" ["o" c2] "e2"}
                  :executors ["e1" "e2"]}
        opts {:sustain-ticks 3}
        t1 (loop/plan-tick skewed {} opts)
        t2 (loop/plan-tick balanced (:state t1) opts)]  ; imbalance vanished
    (is (= 1 (:over-count (:state t1))))
    (is (= 0 (:over-count (:state t2))) "a balanced tick resets the sustained counter")
    (is (empty? (:moves t2)))))


(deftest default-sustain-fires-on-first-qualifying-tick
  (testing "with sustain-ticks 1 (default) a floor-clearing imbalance moves now"
    (let [inputs {:cells [(cell c1 1) (cell c2 1) (cell c3 1)]
                  :current {["o" c1] "e1" ["o" c2] "e1" ["o" c3] "e1"}
                  :executors ["e1" "e2"]}
          {:keys [moves state]} (loop/plan-tick inputs {} {})]
      (is (= 1 (count moves)))
      (is (= 0 (:over-count state))))))


(deftest no-executors-plans-nothing
  (let [{:keys [initial-placements moves]}
        (loop/plan-tick {:cells [(cell c1 1)] :current {} :executors []} {} {})]
    (is (empty? initial-placements))
    (is (empty? moves))))
