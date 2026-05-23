(ns graphden.packages.core.concurrency.impls
  "Implementations for core/concurrency base-fns. Each wraps ONE
   Clojure / Java primitive — `(force ...)`, `Thread/sleep`,
   `(Thread. ...)`, `(Thread/isInterrupted)` — so the imperative
   pieces of long-running patterns can be composed at the fn-def
   layer instead of buried inside monolithic base-fns."
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


;; =============================================================================
;; :do — force steps in declaration order, return last
;;
;; `:steps` is declared `:lazy-seq-args` in the registry below, so each
;; item arrives as a `delay`. We force them sequentially; side effects
;; therefore fire left-to-right and the impl is honest about its
;; "imperative sequencing" semantics. Without lazy-seq-args the
;; executor is free to resolve args in parallel and the ordering
;; would be undefined.
;; =============================================================================

(defbase do-fn
  [steps]
  (let [forced (mapv force steps)]
    (last forced)))


;; =============================================================================
;; :sleep — Thread/sleep. Honors interrupt so a sleeping loop body
;; sees the parent :future's stopper without delay.
;; =============================================================================

(defbase sleep-fn
  [ms]
  (cr/record-effect! :time)
  (when (pos? (long ms))
    (Thread/sleep (long ms)))
  nil)


;; =============================================================================
;; :sleep-until-ms — block until wall-clock target. Companion to :sleep,
;; composes with :cron-next-after without intermediate arithmetic.
;; =============================================================================

(defbase sleep-until-ms-fn
  [target-ms]
  (cr/record-effect! :time)
  (let [now (System/currentTimeMillis)
        delta (- (long target-ms) now)]
    (when (pos? delta)
      (Thread/sleep delta)))
  nil)


;; =============================================================================
;; :future — spawn body in daemon thread, return stopper-thunk.
;;
;; Catches InterruptedException so a clean shutdown via the returned
;; stopper doesn't surface as a noisy stack trace. Other exceptions
;; are logged (the body crashed) but don't propagate to the spawner —
;; the spawn call returns normally, the future just dies.
;;
;; Daemon flag = thread doesn't keep the JVM alive on its own. The
;; service registry's halt path explicitly stops everything; daemon
;; is the safety net for un-supervised futures (test fixtures etc.).
;; =============================================================================

(defbase future-fn
  [body]
  (cr/record-effect! :process)
  (let [thread (Thread.
                 ^Runnable
                 (fn []
                   (try
                     (body)
                     (catch InterruptedException _ nil)
                     (catch Exception e
                       (log/warn e "future body threw"))))
                 "graphden-future")]
    (Thread/.setDaemon thread true)
    (Thread/.start thread)
    (fn stopper []
      (Thread/.interrupt thread))))


;; =============================================================================
;; :loop-until-interrupted — run body repeatedly while !interrupted
;;
;; Two exit paths:
;;   1. Body returns normally — loop checks isInterrupted, exits if true.
;;   2. Blocking call inside body throws InterruptedException — caught
;;      here, loop exits.
;;
;; Either way, cooperative shutdown is clean. NOT a base-fn that should
;; be a service directly (no :process effect): it loops on the CURRENT
;; thread. Compose with :future to get the spawn.
;; =============================================================================

(defbase loop-until-interrupted-fn
  [body]
  (try
    (while (not (Thread/.isInterrupted (Thread/currentThread)))
      (body))
    (catch InterruptedException _ nil))
  nil)


;; =============================================================================
;; :cron-next-after — Quartz CronExpression parse + next-fire math.
;;
;; Two-step:
;;   1. Construct a CronExpression (parses + validates the cron string;
;;      throws ParseException on malformed). Rethrown as
;;      `:cron/parse-error` ex-info so callers see a stable :type tag.
;;   2. .getNextValidTimeAfter (java.util.Date.) — Quartz internally
;;      walks cron fields forward; returns a Date.
;;
;; Returns the Date as epoch-ms long. The caller composes with
;; current-time-ms via :sub to compute the sleep duration.
;; =============================================================================

(defbase cron-next-after-fn
  [cron now-ms]
  (let [expr (try (org.quartz.CronExpression. ^String cron)
                  (catch java.text.ParseException e
                    (throw (ex-info (str "Invalid cron expression: "
                                         (.getMessage e))
                                    {:type :cron/parse-error
                                     :cron cron}))))
        now-date (java.util.Date. (long now-ms))
        next-date (org.quartz.CronExpression/.getNextValidTimeAfter expr now-date)]
    (java.util.Date/.getTime next-date)))


(def impls
  {:do {:impl do-fn :lazy-seq-args #{:steps}}
   :sleep sleep-fn
   :sleep-until-ms sleep-until-ms-fn
   :future future-fn
   :loop-until-interrupted loop-until-interrupted-fn
   :cron-next-after cron-next-after-fn})
