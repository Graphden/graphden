(ns graphden.system.fleet-controller-test
  "Runs in the parallel pool: the shared PG advisory-lock fns are
   stubbed per-thread via `pg-lock/*impl-override*`, and the fleet
   collaborators (`fleet-executors`, `run-tick!`) are injected through
   the tick's opts map (`:executors-fn` / `:run-tick-fn`) — no
   process-global `with-redefs` root-rebinds remain.

   Leader-gate GLUE for `:exec/fleet-controller` (`fleet-controller-tick!` in
   `graphden.system.init.fleet`). The pure decision (`plan-tick` / `run-tick!`) is
   covered in `graphden.fleet.*`; this covers the integrant-side gating the fleet
   tests can't reach — only the advisory-lock holder ticks, a non-leader resets
   its sustained-imbalance streak so a failover starts clean, and a thrown tick is
   swallowed (so it can't kill the ScheduledExecutorService)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.system.init.fleet :as fleet]))


(def ^:private tick! #'fleet/fleet-controller-tick!)


(defn- ctx
  []
  {:storage ::storage :compile-deps (atom {:forward-deps {}})})


(deftest fleet-controller-tick-leader-gate-test
  (let [calls (atom [])
        state (atom {:over-count 2})
        opts {:sustain-ticks 3
              :executors-fn (fn [] ["e1" "e2"])
              :run-tick-fn (fn [_env _st _opts]
                             (swap! calls conj :run-tick)
                             {:state {:over-count 5}
                              :moves [] :initial-placements []})}]
    (binding [pg-lock/*impl-override*
              {:ensure-live! (fn [_] (swap! calls conj :ensure-live) false)
               :holder-conn (fn [_] ::conn)}]
      (testing "leader (holds the lock) → ensure-live! then run-tick!, carries state"
        (binding [pg-lock/*impl-override* (assoc pg-lock/*impl-override*
                                                 :try-lock! (fn [_ _] true))]
          (tick! (ctx) ::holder state opts))
        (is (= [:ensure-live :run-tick] @calls))
        (is (= {:over-count 5} @state) "the decision's :state is carried to the next tick"))
      (testing "non-leader (a sibling holds the lock) → does NOT tick, resets its streak"
        (reset! calls [])
        (reset! state {:over-count 2})
        (binding [pg-lock/*impl-override* (assoc pg-lock/*impl-override*
                                                 :try-lock! (fn [_ _] false))]
          (tick! (ctx) ::holder state opts))
        (is (= [:ensure-live] @calls) "run-tick! never runs off-leader")
        (is (= {} @state) "streak reset so a failover starts from a clean count")))))


(deftest fleet-controller-tick-swallows-a-thrown-tick-test
  ;; A tick that throws must not escape the scheduled runnable — an uncaught
  ;; throw would cancel the ScheduledExecutorService and freeze the controller.
  (let [state (atom {:over-count 1})
        opts {:executors-fn (fn [] ["e1"])
              :run-tick-fn (fn [_ _ _] (throw (ex-info "boom" {})))}]
    (binding [pg-lock/*impl-override* {:ensure-live! (fn [_] false)
                                       :holder-conn (fn [_] ::conn)
                                       :try-lock! (fn [_ _] true)}]
      (tick! (ctx) ::holder state opts)         ; must not throw
      (is (= {:over-count 1} @state)
          "state is untouched — the throw is caught before the reset"))))


(deftest lock-id-is-per-release
  ;; Advisory locks are DB-wide and a mixed fleet's releases share one
  ;; Postgres — a constant key made two releases' controllers contend for
  ;; one lock (the winner saw only its own SRV and mis-placed every org).
  (testing "blank scope keeps the historic constant (single-release fleets)"
    (is (= #uuid "f1ee7c07-0000-0000-0000-000000000001"
           (fleet/fleet-controller-lock-id nil)
           (fleet/fleet-controller-lock-id ""))))
  (testing "a scope derives a stable, distinct key per release"
    (let [shared (fleet/fleet-controller-lock-id "_http._tcp.gd-shared.ns.svc")
          dedicated (fleet/fleet-controller-lock-id "_http._tcp.gd-acme.ns.svc")]
      (is (= shared (fleet/fleet-controller-lock-id "_http._tcp.gd-shared.ns.svc"))
          "deterministic — every pod of a release contends for the same lock")
      (is (not= shared dedicated) "different releases stop colliding")
      (is (not= shared (fleet/fleet-controller-lock-id nil))))))
