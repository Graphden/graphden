(ns graphden.system.init.fleet
  "Integrant init-key for the leader-locked fleet placement controller
   (docs/FLEET_RFC.md §6.3). Reads live cell weights + the executor set
   and applies the pure `control-loop/plan-tick` decision on a periodic,
   advisory-lock-gated tick."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.fleet.command :as fleet-command]
    [graphden.fleet.control-loop :as fleet-loop]
    [graphden.fleet.discovery :as fleet-discovery]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.util.counters :as counters]
    [integrant.core :as ig]))


;; =============================================================================
;; Fleet placement controller (docs/FLEET_RFC.md §6.3, Phase 2)
;;
;; A leader-locked periodic tick that reads live cell weights + the executor set
;; and applies the pure `control-loop/plan-tick` decision — placing new cells and
;; rebalancing sustained imbalance via the directed cell-command transport. Every
;; fleet pod runs the component; a single advisory lock (its own dedicated
;; connection, distinct from the reconciler's) elects ONE controller, so two
;; pods can't fight (§6.3 Safety). Inert unless this pod is a fleet member
;; (`GRAPHDEN_EXECUTOR_ID` set) — single-tenant / self-hosted never starts it.
;; =============================================================================

(defn fleet-controller-lock-id
  "Advisory-lock key that elects a release's single controller. Advisory
   locks are DB-wide and a mixed fleet's releases share one Postgres, so a
   constant key made two releases' controllers contend for ONE lock — the
   winner saw only its own SRV membership and tried to place every org onto
   its own pods (the 2026-08-23 audit's mixed-fleet wedge). The key is now
   derived from `scope` — the release identity (`GRAPHDEN_FLEET_LOCK_SCOPE`,
   defaulting to `GRAPHDEN_FLEET_DNS`, which is per-release by construction:
   each release has its own headless Service). Same scope → same lock
   (leader election within the release); different releases → different
   locks, each managing its own scoped cell set (`scope-cells`). Blank
   scope keeps the historic constant, so single-release fleets are
   unchanged."
  [scope]
  (if (str/blank? (str scope))
    #uuid "f1ee7c07-0000-0000-0000-000000000001"
    (java.util.UUID/nameUUIDFromBytes
      (String/.getBytes (str "graphden-fleet-controller/" scope) "UTF-8"))))


(defn- csv-set
  [s]
  (when-not (str/blank? (str s))
    (into #{} (comp (map str/trim) (remove str/blank?)) (str/split (str s) #","))))


(defn- fleet-controller-opts
  "Controller knobs from env (read directly, like the other fleet vars):
   `:sustain-ticks` (imbalance must persist this many ticks before a move),
   `:min-improvement` (magnitude floor), `:max-moves` (per-tick cap),
   `:w-overlap` (overlap-accounting weight — > 0 co-locates code-sharing cells;
   default 0 keeps pure load-balancing, so overlap is strictly opt-in),
   `:shard-orgs` (this release's `GRAPHDEN_EXECUTOR_ORGS` — a dedicated
   release's controller manages ONLY its shard) and `:exclude-orgs`
   (`GRAPHDEN_FLEET_EXCLUDE_ORGS` — the shared release's list of orgs that
   dedicated releases own; see `control-loop/scope-cells`)."
  []
  {:sustain-ticks (or (some-> (System/getenv "GRAPHDEN_FLEET_SUSTAIN_TICKS") parse-long) 3)
   :min-improvement (or (some-> (System/getenv "GRAPHDEN_FLEET_MIN_IMPROVEMENT") parse-double) 0.0)
   :max-moves (or (some-> (System/getenv "GRAPHDEN_FLEET_MAX_MOVES") parse-long) Integer/MAX_VALUE)
   :w-overlap (or (some-> (System/getenv "GRAPHDEN_FLEET_OVERLAP_WEIGHT") parse-double) 0.0)
   :shard-orgs (csv-set (System/getenv "GRAPHDEN_EXECUTOR_ORGS"))
   :exclude-orgs (csv-set (System/getenv "GRAPHDEN_FLEET_EXCLUDE_ORGS"))})


(defn- fleet-controller-tick!
  "One control pass, leader-gated. Re-asserts the advisory lock (re-acquiring
   after a lock-conn reconnect, or failing if a sibling took over); only the
   holder ticks. A non-leader resets its streak so a failover starts clean.

   Test seams via `opts` (the tick is invoked directly by its glue test with a
   hand-built opts map, so no dynamic vars / `with-redefs` root-rebinds are
   needed — those are process-global and force `^:serial` pins):
   `:executors-fn` (default `fleet-discovery/fleet-executors`) and
   `:run-tick-fn` (default `fleet-loop/run-tick!`). The init-key never sets
   them (`fleet-controller-opts` reads only env), so production is unchanged."
  [ctx holder state-atom opts]
  (try
    (pg-lock/ensure-live! holder)
    (if (pg-lock/try-lock! (pg-lock/holder-conn holder)
                           (or (:lock-id opts)
                               (fleet-controller-lock-id
                                 (or (System/getenv "GRAPHDEN_FLEET_LOCK_SCOPE")
                                     (System/getenv "GRAPHDEN_FLEET_DNS")))))
      (let [executors-fn (or (:executors-fn opts) fleet-discovery/fleet-executors)
            run-tick-fn (or (:run-tick-fn opts) fleet-loop/run-tick!)
            env {:storage (:storage ctx)
                 :forward-deps (:forward-deps (some-> (:compile-deps ctx) deref))
                 :executors (executors-fn)
                 :move-fn (fn [cmd] (fleet-command/execute-move! ctx cmd))}
            decision (run-tick-fn env @state-atom opts)]
        (reset! state-atom (:state decision))
        ;; Ops surface: these ride the existing counters-snapshot pipeline
        ;; (docs/MONITORING.md) — the first fleet observability beyond
        ;; /internal/fleet/status and log-grep.
        (counters/count! :fleet/ticks)
        (when (seq (:initial-placements decision))
          (counters/count! :fleet/initial-placements (count (:initial-placements decision))))
        (when (seq (:moves decision))
          (counters/count! :fleet/rebalance-moves (count (:moves decision))))
        (when (or (seq (:moves decision)) (seq (:initial-placements decision)))
          (log/info "Fleet controller applied placement"
                    {:initial (count (:initial-placements decision))
                     :moves (count (:moves decision))
                     :imbalance (:current-imbalance decision)})))
      (reset! state-atom {}))
    (catch Exception e
      (counters/count! :fleet/tick-failures)
      (log/warn e "Fleet controller tick failed — will retry next tick"))))


(defmethod ig/init-key :exec/fleet-controller
  [_ {:keys [context pg-opts enabled? period-ms]}]
  ;; `enabled?` is the fleet identity (`GRAPHDEN_EXECUTOR_ID`) — a non-blank
  ;; string on a fleet member, nil/false otherwise. Guard against a literal
  ;; `false` (whose `(str false)` = "false" is non-blank) reading as enabled.
  ;; `GRAPHDEN_FLEET_CONTROLLER=off|false|0` is the operator's explicit
  ;; kill-switch (helm `fleet.controllerEnabled: false`) — a release that
  ;; must never run a controller (e.g. a dedicated shard the shared
  ;; release's controller manages) stays a plain fleet member.
  (if (or (not (and enabled? (not (str/blank? (str enabled?)))))
          (contains? #{"off" "false" "0"}
                     (some-> (System/getenv "GRAPHDEN_FLEET_CONTROLLER") str/lower-case)))
    (do (log/info "Fleet controller disabled (not a fleet member, or switched off)") nil)
    (let [holder (pg-lock/create-lock-holder pg-opts)
          state-atom (atom {})
          opts (fleet-controller-opts)
          period (or period-ms 30000)
          scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
      (log/info "Starting fleet controller — period" period "ms," opts)
      (java.util.concurrent.ScheduledExecutorService/.scheduleAtFixedRate
        scheduler
        ^Runnable (fn [] (fleet-controller-tick! context holder state-atom opts))
        period period java.util.concurrent.TimeUnit/MILLISECONDS)
      {:scheduler scheduler :holder holder :state state-atom})))


(defmethod ig/halt-key! :exec/fleet-controller [_ component]
  (when component
    (let [{:keys [scheduler holder]} component]
      (when scheduler
        (java.util.concurrent.ExecutorService/.shutdown
          ^java.util.concurrent.ExecutorService scheduler)
        (try (java.util.concurrent.ExecutorService/.awaitTermination
               ^java.util.concurrent.ExecutorService scheduler 5 java.util.concurrent.TimeUnit/SECONDS)
             (catch InterruptedException _ nil)))
      (when holder (pg-lock/close-holder! holder))
      (log/info "Fleet controller stopped"))))
