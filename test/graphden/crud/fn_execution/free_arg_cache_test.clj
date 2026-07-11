(ns graphden.crud.fn-execution.free-arg-cache-test
  "Unit tests for the free-arg-slot-map memo. Pure — no storage, no
   container. The end-to-end 'is it actually faster + still correct on
   the real graph' check is done live in the REPL window (see
   docs/adr/ADR-free-arg-slot-map-perf.md § Verification)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.fn-execution.free-arg-cache :as fac]))


(use-fixtures :each (fn [t] (fac/clear!) (t) (fac/clear!)))


;; Keys mirror the real caller shape: [storage-id branch-id fn-id].
(def ^:private k1 [1 "main" :fn-1])
(def ^:private k2 [1 "dev" :fn-1])   ; different branch
(def ^:private k3 [1 "main" :fn-2])  ; different fn
(def ^:private k4 [2 "main" :fn-1])  ; different storage


(deftest computes-once-per-key
  (testing "compute-fn runs on miss, not on subsequent hits"
    (let [calls (atom 0)
          thunk (fn [] (swap! calls inc) {:a :slot-a})]
      (is (= {:a :slot-a} (fac/get-or-compute k1 thunk)))
      (is (= {:a :slot-a} (fac/get-or-compute k1 thunk)))
      (is (= {:a :slot-a} (fac/get-or-compute k1 thunk)))
      (is (= 1 @calls) "cached after first compute"))))


(deftest keyed-by-storage-branch-and-fn
  (testing "distinct storage, branch, or fn = distinct entry"
    (let [calls (atom 0)
          thunk (fn [] (swap! calls inc) {})]
      (fac/get-or-compute k1 thunk)
      (fac/get-or-compute k2 thunk)   ; different branch
      (fac/get-or-compute k3 thunk)   ; different fn
      (fac/get-or-compute k4 thunk)   ; different storage
      (is (= 4 @calls) "no cross-key collision")
      (fac/get-or-compute k1 thunk)   ; repeat first key
      (is (= 4 @calls) "repeat is a hit"))))


(deftest caches-empty-and-nil-results
  (testing "a genuine {} result is cached (not re-treated as a miss)"
    (let [calls (atom 0)]
      (fac/get-or-compute k1 (fn [] (swap! calls inc) {}))
      (fac/get-or-compute k1 (fn [] (swap! calls inc) {}))
      (is (= 1 @calls))))
  (testing "a nil compute result round-trips as nil and is cached"
    (let [calls (atom 0)]
      (is (nil? (fac/get-or-compute k3 (fn [] (swap! calls inc) nil))))
      (is (nil? (fac/get-or-compute k3 (fn [] (swap! calls inc) nil))))
      (is (= 1 @calls) "cached nil is a hit, not a recompute"))))


(deftest clear-drops-everything
  (testing "clear! forces the next call to recompute"
    (let [calls (atom 0)
          thunk (fn [] (swap! calls inc) {})]
      (fac/get-or-compute k1 thunk)
      (is (= 1 (fac/size)))
      (fac/clear!)
      (is (zero? (fac/size)))
      (fac/get-or-compute k1 thunk)
      (is (= 2 @calls) "recomputed after clear"))))
