(ns graphden.system.fleet-controller-test
  "Leader-gate GLUE for `:exec/fleet-controller` (`fleet-controller-tick!` in
   `graphden.system.core`). The pure decision (`plan-tick` / `run-tick!`) is
   covered in `graphden.fleet.*`; this covers the integrant-side gating the fleet
   tests can't reach — only the advisory-lock holder ticks, a non-leader resets
   its sustained-imbalance streak so a failover starts clean, and a thrown tick is
   swallowed (so it can't kill the ScheduledExecutorService)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fleet.control-loop :as fleet-loop]
    [graphden.fleet.discovery :as fleet-discovery]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.system.core :as sys]))


(def ^:private tick! #'sys/fleet-controller-tick!)


(defn- ctx
  []
  {:storage ::storage :compile-deps (atom {:forward-deps {}})})


(deftest fleet-controller-tick-leader-gate-test
  (let [calls (atom [])
        state (atom {:over-count 2})]
    (with-redefs [pg-lock/ensure-live! (fn [_] (swap! calls conj :ensure-live) false)
                  pg-lock/holder-conn (fn [_] ::conn)
                  fleet-discovery/fleet-executors (fn [] ["e1" "e2"])
                  fleet-loop/run-tick! (fn [_env _st _opts]
                                         (swap! calls conj :run-tick)
                                         {:state {:over-count 5}
                                          :moves [] :initial-placements []})]
      (testing "leader (holds the lock) → ensure-live! then run-tick!, carries state"
        (with-redefs [pg-lock/try-lock! (fn [_ _] true)]
          (tick! (ctx) ::holder state {:sustain-ticks 3}))
        (is (= [:ensure-live :run-tick] @calls))
        (is (= {:over-count 5} @state) "the decision's :state is carried to the next tick"))
      (testing "non-leader (a sibling holds the lock) → does NOT tick, resets its streak"
        (reset! calls [])
        (reset! state {:over-count 2})
        (with-redefs [pg-lock/try-lock! (fn [_ _] false)]
          (tick! (ctx) ::holder state {:sustain-ticks 3}))
        (is (= [:ensure-live] @calls) "run-tick! never runs off-leader")
        (is (= {} @state) "streak reset so a failover starts from a clean count")))))


(deftest fleet-controller-tick-swallows-a-thrown-tick-test
  ;; A tick that throws must not escape the scheduled runnable — an uncaught
  ;; throw would cancel the ScheduledExecutorService and freeze the controller.
  (let [state (atom {:over-count 1})]
    (with-redefs [pg-lock/ensure-live! (fn [_] false)
                  pg-lock/holder-conn (fn [_] ::conn)
                  pg-lock/try-lock! (fn [_ _] true)
                  fleet-discovery/fleet-executors (fn [] ["e1"])
                  fleet-loop/run-tick! (fn [_ _ _] (throw (ex-info "boom" {})))]
      (tick! (ctx) ::holder state {})           ; must not throw
      (is (= {:over-count 1} @state)
          "state is untouched — the throw is caught before the reset"))))
