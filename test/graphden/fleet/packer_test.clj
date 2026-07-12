(ns graphden.fleet.packer-test
  "Cell placement packer (`graphden.fleet.packer`, docs/FLEET_RFC.md §6.3). Pure
   LPT-greedy — synthetic cells + executor lists, no container."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fleet.packer :as packer]))


(defn- cell
  [org entry weight]
  {:org org :entry-fn-id entry :weight weight})


(def ^:private c1 #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private c2 #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private c3 #uuid "00000000-0000-0000-0000-000000000003")
(def ^:private c4 #uuid "00000000-0000-0000-0000-000000000004")


(deftest lpt-balances-load-across-executors
  (testing "heaviest-first onto least-loaded evens the per-pod totals"
    ;; weights 10, 5, 5 over 2 pods: 10→a, 5→b, 5→b ⇒ a=10, b=10.
    (let [{:keys [placement loads]}
          (packer/pack [(cell "o" c1 10) (cell "o" c2 5) (cell "o" c3 5)]
                       ["a" "b"])]
      (is (= {"a" 10.0 "b" 10.0} loads) "both pods carry equal load")
      (is (= "a" (placement ["o" c1])) "the heavy cell lands alone on one pod")
      (is (= "b" (placement ["o" c2])))
      (is (= "b" (placement ["o" c3]))))))


(deftest spreads-one-orgs-cells-across-pods
  (testing "an org's equal-weight cells fan out, not pile onto one pod"
    (let [{:keys [placement loads]}
          (packer/pack [(cell "acme" c1 1) (cell "acme" c2 1)
                        (cell "acme" c3 1) (cell "acme" c4 1)]
                       ["a" "b"])]
      (is (= {"a" 2.0 "b" 2.0} loads))
      (is (= 2 (count (filter #(= "a" %) (vals placement)))))
      (is (= 2 (count (filter #(= "b" %) (vals placement))))))))


(deftest single-executor-holds-everything
  (let [{:keys [placement loads]}
        (packer/pack [(cell "o" c1 3) (cell "o" c2 7)] ["solo"])]
    (is (= {"solo" 10.0} loads))
    (is (= {["o" c1] "solo" ["o" c2] "solo"} placement))))


(deftest deterministic-across-input-order
  (testing "equal-weight cells order by entry-fn-id, so packing is stable"
    (let [a (packer/pack [(cell "o" c1 5) (cell "o" c2 5) (cell "o" c3 5)] ["x" "y"])
          b (packer/pack [(cell "o" c3 5) (cell "o" c1 5) (cell "o" c2 5)] ["x" "y"])]
      (is (= (:placement a) (:placement b)) "same result regardless of input order"))))


(deftest edge-cases
  (testing "no executors → nil (nothing can be placed)"
    (is (nil? (packer/pack [(cell "o" c1 1)] [])))
    (is (nil? (packer/pack [] []))))
  (testing "no cells → empty placement, every pod at zero load"
    (is (= {:placement {} :loads {"a" 0.0 "b" 0.0}}
           (packer/pack [] ["a" "b"])))))


;; ---------------------------------------------------------------------------
;; Overlap-aware placement (docs/FLEET_RFC.md T4.5) — opt-in via :w-overlap.
;; ---------------------------------------------------------------------------

(deftest best-target-overlap-vs-pure-load
  (let [loads {"a" 0.0 "b" 0.0}
        pod-fns {"a" #{} "b" #{:x :y}}
        execs ["a" "b"]]
    (testing "w-overlap 0 ignores b's overlap → least-loaded id tiebreak picks a"
      (is (= "a" (packer/best-target loads execs
                                     {:pod-fns pod-fns :cell-fns #{:x :y} :w-overlap 0.0}))))
    (testing "w-overlap > 0 pulls the cell to b, which already holds its closure"
      (is (= "b" (packer/best-target loads execs
                                     {:pod-fns pod-fns :cell-fns #{:x :y} :w-overlap 1.0}))))
    (testing "nil cell-fns → pure least-loaded even with a positive weight"
      (is (= "a" (packer/best-target loads execs
                                     {:pod-fns pod-fns :cell-fns nil :w-overlap 1.0}))))
    (testing "a big load gap still dominates a small overlap discount"
      (is (= "a" (packer/best-target {"a" 0.0 "b" 5.0} execs
                                     {:pod-fns pod-fns :cell-fns #{:x :y} :w-overlap 1.0}))))))


(deftest pack-co-locates-cells-that-share-code
  (let [cells [(cell "o" c1 1) (cell "o" c2 1) (cell "o" c3 1)]
        cell-fns {["o" c1] #{:a :b :c}
                  ["o" c2] #{:a :b :d}    ; shares :a :b with c1
                  ["o" c3] #{:x :y :z}}]  ; disjoint
    (testing "pure LPT (default opts) spreads the two equal-weight sharing cells"
      (let [{:keys [placement]} (packer/pack cells ["p1" "p2"])]
        (is (not= (placement ["o" c1]) (placement ["o" c2])))))
    (testing "w-overlap co-locates c1 + c2 (shared :a :b) while c3 balances away"
      (let [{:keys [placement]} (packer/pack cells ["p1" "p2"] {:cell-fns cell-fns :w-overlap 2.0})]
        (is (= (placement ["o" c1]) (placement ["o" c2])) "sharing cells land together")
        (is (not= (placement ["o" c1]) (placement ["o" c3])) "the disjoint cell still balances")))
    (testing "w-overlap 0 reproduces pure LPT exactly (opt-in, no regression)"
      (is (= (packer/pack cells ["p1" "p2"])
             (packer/pack cells ["p1" "p2"] {:cell-fns cell-fns :w-overlap 0.0}))))))
