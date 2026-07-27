(ns graphden.system.init.cleanup
  "Integrant init-key for the hourly execution-cleanup scheduler: sweeps
   `:fn-execution` rows past their per-status TTL and force-cancels
   zombie `:pending` rows older than 1h.

   Split out of `graphden.system.core` (which now only loads this ns for
   its `defmethod` side effects). No behaviour change. `sweep-executions!`
   and `as-instant` stay public / test-reachable as before."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp]
    [integrant.core :as ig]))


;; =============================================================================
;; Execution cleanup scheduler
;;
;; Runs hourly. Sweeps `:fn-execution` rows whose status + age exceeds
;; the per-status TTL (see `crud.fn-execution` ns-docstring). Single
;; scheduled-executor thread; halt-key shuts it down + awaits in-
;; flight work.
;; =============================================================================

(defn- one-hour
  []
  (* 60 60 1000))


(defn- as-instant
  "Storage codec returns timestamptz columns in different shapes
   depending on backend / driver / clj-reader (jdbc.next default is
   `java.time.Instant`; the pg driver returns `java.sql.Timestamp` for
   some configs; the EDN reader walks `#inst` literals as
   `java.util.Date`; serialised forms come back as ISO-8601 or
   SQL-style strings). Normalise to `java.time.Instant`."
  [x]
  (cond
    (nil? x) nil
    (instance? java.time.Instant x) x
    ;; Both java.sql.Timestamp and java.util.Date expose toInstant().
    (instance? java.util.Date x) (java.util.Date/.toInstant x)
    :else
    (let [s (str x)]
      (try (java.time.Instant/parse s)
           (catch java.time.format.DateTimeParseException _
             ;; SQL-style `2026-05-21 12:00:00.0` — rewrite to ISO.
             (let [iso (-> s
                           (str/replace #" " "T")
                           ;; drop trailing fractional-second zero pad
                           ;; that may not parse without a Z
                           (str/replace #"\.0+$" "")
                           (str "Z"))]
               (java.time.Instant/parse iso)))))))


(defn sweep-executions!
  "Delete `:fn-execution` rows past TTL; mark zombie `:pending` rows
   older than 1h as `:cancelled` so the row stops blocking the
   polling client.

   `now` is an injectable `Instant` for deterministic tests; defaults
   to wall-clock when omitted. Public (no `-` suffix) so tests can
   exercise it without `#'`-style var lookups."
  ([storage]
   (sweep-executions! storage (java.time.Instant/now)))
  ([storage now]
   (let [now-ms (java.time.Instant/.toEpochMilli now)
         ;; Per-status TTLs (in ms).
         ttl-ms {"succeeded" (* 7  24 60 60 1000)
                 "failed"    (* 30 24 60 60 1000)
                 "cancelled" (* 7  24 60 60 1000)}
         zombie-ms (one-hour)
         all (sp/query-entities storage :fn-execution {})
         age-of (fn [row stamp-key]
                  (when-let [t (as-instant (get row stamp-key))]
                    (- now-ms (java.time.Instant/.toEpochMilli t))))
         ;; storage may return :status as the enum keyword `:succeeded`
         ;; OR the bare string "succeeded" depending on codec; `name`
         ;; normalises to the bare token both ways.
         status-str (fn [row]
                      (let [s (:status row)]
                        (cond
                          (keyword? s) (name s)
                          (string? s) s
                          :else (str s))))]
     (doseq [row all]
       (let [status (status-str row)]
         (cond
           ;; Zombie sweep: pending > 1h gets force-cancelled so the
           ;; polling client stops waiting forever for a future that
           ;; died with the JVM.
           (and (= "pending" status)
                (when-let [a (age-of row :started-at)] (> a zombie-ms)))
           (sp/update-entity storage :fn-execution (:id row)
                             {:status :cancelled
                              :finished-at now
                              :error "zombie: pending > 1h, swept"})

           ;; TTL sweep: delete rows past per-status retention.
           (when-let [limit (get ttl-ms status)]
             (when-let [a (age-of row :finished-at)] (> a limit)))
           (sp/delete-entity storage :fn-execution (:id row))))))))


(defmethod ig/init-key :exec/cleanup-scheduler
  [_ {:keys [context period-ms]}]
  (let [storage (:storage context)
        period (or period-ms (one-hour))
        scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (log/info "Starting execution cleanup scheduler — period" period "ms")
    (java.util.concurrent.ScheduledExecutorService/.scheduleAtFixedRate
      scheduler
      ^Runnable (fn []
                  ;; Catch Exception (not Throwable) — Errors should
                  ;; propagate, the scheduler swallowing them is fine
                  ;; for OOM / StackOverflow cases.
                  (try (sweep-executions! storage)
                       (catch Exception e
                         (log/warn e "execution-cleanup sweep failed"))))
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
