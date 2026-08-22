(ns graphden.system.init.cleanup
  "Integrant init-key for the hourly cleanup scheduler. It owns the
   CADENCE; each sweep it drives lives with the data it reclaims —
   execution log in `crud.fn-execution.retention`, usage rollups in
   `crud.fn-execution.stats`, dead versioned entities in
   `versioning.storage.core`.

   `maybe-sweep-tombstones!` is public / test-reachable."
  (:require
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.retention :as retention]
    [graphden.crud.fn-execution.stats :as stats]
    [graphden.versioning.storage.core :as vcore]
    [integrant.core :as ig]))


;; =============================================================================
;; Cleanup scheduler
;;
;; Runs hourly on a single scheduled-executor thread; the halt-key shuts
;; it down and awaits in-flight work. Each sweep is wrapped on its own so
;; one failing reclamation never skips the others.
;; =============================================================================

(defn- one-hour
  []
  (* 60 60 1000))


(defn- tombstone-gc-retention-ms
  "Retention window for the tombstone GC, or nil (GC DISABLED — the safe
   default). OPT-IN via `GRAPHDEN_TOMBSTONE_GC_RETENTION_DAYS`: a
   self-hosted operator who relies on tombstones for audit must not have
   deleted rows silently reclaimed, so the GC only runs when a retention
   is explicitly configured. Cloud sets it (it bills on live storage)."
  []
  (some-> (System/getenv "GRAPHDEN_TOMBSTONE_GC_RETENTION_DAYS")
          not-empty parse-long (* 24 60 60 1000)))


(defn maybe-sweep-tombstones!
  "Run the tombstone GC when it's enabled AND at most once per `min-gap-ms`
   (the version-table scan is far heavier than the hourly execution sweep,
   so it runs on a daily-ish cadence off the same thread). `last-run` is an
   atom of the last-run epoch-ms. `storage` is the versioned storage;
   unwrapped to the base handle the GC needs."
  [storage last-run min-gap-ms now-ms]
  (when-let [retention (tombstone-gc-retention-ms)]
    (when (>= (- now-ms @last-run) min-gap-ms)
      (reset! last-run now-ms)
      (let [purged (vcore/tombstone-gc-sweep! (vcore/unwrap storage) retention)]
        (when (pos? (reduce + 0 (vals purged)))
          (log/info "tombstone-gc: reclaimed dead entities" purged))))))


(defmethod ig/init-key :exec/cleanup-scheduler
  [_ {:keys [context period-ms]}]
  (let [storage (:storage context)
        period (or period-ms (one-hour))
        gc-last-run (atom 0)
        gc-min-gap (* 24 (one-hour))
        scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (log/info "Starting execution cleanup scheduler — period" period "ms")
    (java.util.concurrent.ScheduledExecutorService/.scheduleAtFixedRate
      scheduler
      ^Runnable (fn []
                  ;; Catch Exception (not Throwable) — Errors should
                  ;; propagate, the scheduler swallowing them is fine
                  ;; for OOM / StackOverflow cases.
                  ;; Execution-log retention — TTL delete + zombie cancel
                  ;; + reclamation of the child rows nothing else deletes.
                  ;; Pool absent (bare test ctx) → no-op inside.
                  (try (retention/sweep-executions! (:pool (:pg-storage context)))
                       (catch Exception e
                         (log/warn e "execution-cleanup sweep failed")))
                  ;; Usage-rollup retention (Phase C1) — 90d of hourly
                  ;; buckets; bounded rows, so a daily-ish sweep on the
                  ;; hourly cadence costs nothing. Pool absent (bare test
                  ;; ctx) → no-op inside.
                  (try (stats/sweep-stats! (:pool (:pg-storage context)) 90)
                       (catch Exception e
                         (log/warn e "usage-stat retention sweep failed")))
                  ;; Storage reclamation (opt-in) — hard-purge entities dead
                  ;; on every branch, older than the configured retention.
                  ;; Daily cadence; disabled unless a retention env is set.
                  (try (maybe-sweep-tombstones! storage gc-last-run gc-min-gap
                                                (System/currentTimeMillis))
                       (catch Exception e
                         (log/warn e "tombstone-gc sweep failed"))))
      period period
      java.util.concurrent.TimeUnit/MILLISECONDS)
    scheduler))


(defmethod ig/halt-key! :exec/cleanup-scheduler
  [_ ^java.util.concurrent.ScheduledExecutorService scheduler]
  (when scheduler
    (log/info "Stopping execution cleanup scheduler...")
    (java.util.concurrent.ExecutorService/.shutdown scheduler)
    (try (java.util.concurrent.ExecutorService/.awaitTermination
           scheduler 5 java.util.concurrent.TimeUnit/SECONDS)
         (catch InterruptedException _ nil))))
