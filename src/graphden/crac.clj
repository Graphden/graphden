(ns graphden.crac
  "CRaC (Coordinated Restore at Checkpoint) integration — quiesce and resume the
   live OS resources around a checkpoint, so a WARM JVM image (packages loaded +
   graph compiled: the ~110 s that dominates boot) restores in tens of ms
   instead (docs/FLEET_RFC.md §5.1, development/crac/).

   Why this is small: the compiled registry is pure heap, and its closures are
   ctx-independent — they take the ExecutionContext at call time — so a
   checkpoint captures the expensive work with NO rewiring of the context. The
   only things CRIU can't snapshot are live sockets: the Hikari pool's
   connections, the LISTEN connection, and the advisory-lock connection. We
   close those before the checkpoint and re-establish them after, reusing each
   resource's own reconnect path:

   - Hikari pool  — suspend + drain before, resume after. The `HikariDataSource`
     OBJECT is unchanged (so the context's storage reference stays valid);
     only the physical connections are cycled. HikariCP 7.x has no built-in CRaC
     support, hence `allowPoolSuspension` + this manual drive.
   - LISTEN loop  — close its connection; the loop's next poll fails and its
     existing reconnect-with-backoff re-opens it (`storage.postgres.notify`).
   - advisory lock — close its connection; `resume!` calls `ensure-live!` to
     reconnect + the reconciler re-asserts ownership on its next tick.

   `org.crac` is a portable API: on a non-CRaC JVM the global context never
   fires, so `register-checkpoint-hooks!` is inert in the normal Temurin build
   and active only under a CRaC JDK (`-XX:CRaCCheckpointTo` / `-XX:CRaCRestoreFrom`)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.storage.postgres.connection :as pg-conn]
    [graphden.system.interface :as sys])
  (:import
    (com.zaxxer.hikari
      HikariConfigMXBean
      HikariDataSource
      HikariPoolMXBean)
    (org.crac
      Context
      Core
      Resource)))


(defn- pool-of
  "The `HikariDataSource` behind the `:db/postgres` storage, or nil."
  [system]
  (:pool (:db/postgres system)))


;; The pool's configured minimumIdle, stashed by `quiesce!` (which sets it to 0)
;; so `resume!` can restore it. One JVM, one pool → a single value is enough.
(defonce ^:private saved-min-idle (atom nil))


(defn- drain-pool!
  "Bring the Hikari pool to ZERO physical connections so no Postgres socket
   remains for CRIU to trip on. suspend (stop new borrows) + minimumIdle 0 (stop
   the housekeeper REOPENING idle connections — the reason a plain
   softEvictConnections leaves ~minIdle sockets open) + softEvict (close what's
   open), then poll until the count actually reaches 0 (softEvict closes async
   via Hikari's executor), bounded."
  [^HikariDataSource pool timeout-ms]
  (let [pool-mx (HikariDataSource/.getHikariPoolMXBean pool)
        cfg-mx (HikariDataSource/.getHikariConfigMXBean pool)]
    (reset! saved-min-idle (HikariConfigMXBean/.getMinimumIdle cfg-mx))
    (HikariPoolMXBean/.suspendPool pool-mx)
    (HikariConfigMXBean/.setMinimumIdle cfg-mx 0)
    (HikariPoolMXBean/.softEvictConnections pool-mx)
    (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
      (while (and (pos? (HikariPoolMXBean/.getTotalConnections pool-mx))
                  (< (System/currentTimeMillis) deadline))
        (Thread/sleep 25)))))


(defn quiesce!
  "Close every live socket CRIU can't snapshot: DRAIN the Hikari pool to zero
   connections, close the LISTEN connection, close the advisory-lock
   connection(s). Best-effort per resource — one failure must not block the
   checkpoint."
  [system]
  ;; Stop managed services FIRST — the http-kit web-server holds an open
  ;; ServerSocketChannel + an EPoll selector on :8080 that CRIU can't snapshot.
  ;; Stopping the services closes them (and frees any pool connection / per-
  ;; service advisory lock they hold, so the drain + lock-close below are clean).
  ;; resume! restarts them via a reconcile pass.
  (when-let [reconciler (:exec/service-reconciler system)]
    (try
      (recon/stop-all! (:running reconciler))
      (catch Exception e (log/warn e "CRaC: stopping services failed"))))
  (when-let [pool (pool-of system)]
    (try
      (drain-pool! pool 3000)
      (catch Exception e (log/warn e "CRaC: pool drain failed"))))
  (when-let [listener (:db/notify-listener system)]
    (try
      (pg-conn/close-dedicated! @(:conn-atom listener) "notify-listener")
      (catch Exception e (log/warn e "CRaC: closing LISTEN connection failed"))))
  (when-let [holder (:db/service-locks system)]
    (try
      (pg-conn/close-dedicated! (pg-lock/holder-conn holder) "service-locks")
      (catch Exception e (log/warn e "CRaC: closing advisory-lock connection failed"))))
  ;; Fleet controller (Phase 2) owns its OWN advisory-lock connection — close it
  ;; too, else it's another open socket when the controller is enabled.
  (when-let [holder (:holder (:exec/fleet-controller system))]
    (try
      (pg-conn/close-dedicated! (pg-lock/holder-conn holder) "fleet-controller")
      (catch Exception e (log/warn e "CRaC: closing fleet-controller connection failed"))))
  (log/info "CRaC: quiesced DB resources for checkpoint"))


(defn resume!
  "Re-establish what `quiesce!` closed: restore the pool's minimumIdle + resume
   borrows (connections re-open on demand) and heal the advisory-lock holder(s).
   The LISTEN loop self-reconnects on its next poll of the now-closed connection."
  [system]
  (when-let [^HikariDataSource pool (pool-of system)]
    (try
      (let [pool-mx (HikariDataSource/.getHikariPoolMXBean pool)
            cfg-mx (HikariDataSource/.getHikariConfigMXBean pool)]
        (when-let [mi @saved-min-idle]
          (HikariConfigMXBean/.setMinimumIdle cfg-mx mi))
        (HikariPoolMXBean/.resumePool pool-mx))
      (catch Exception e (log/warn e "CRaC: pool resume failed"))))
  (when-let [holder (:db/service-locks system)]
    (try
      (pg-lock/ensure-live! holder)
      (catch Exception e (log/warn e "CRaC: advisory-lock reconnect failed"))))
  (when-let [holder (:holder (:exec/fleet-controller system))]
    (try
      (pg-lock/ensure-live! holder)
      (catch Exception e (log/warn e "CRaC: fleet-controller reconnect failed"))))
  ;; Restart managed services LAST — the pool + advisory lock are healed above,
  ;; so the reconcile pass can read `:service` rows and re-bind the http-kit
  ;; listener on the restored JVM.
  (when-let [reconciler (:exec/service-reconciler system)]
    (try
      (recon/reconcile-once! (:context reconciler) (:running reconciler))
      (catch Exception e (log/warn e "CRaC: restarting services failed"))))
  (log/info "CRaC: resumed DB resources after restore"))


;; org.crac keeps only a WEAK reference to registered resources, so the hook
;; must be held strongly or it'd be GC'd and never fire. Hold it here.
(defonce ^:private registered-hook (atom nil))


(defn register-checkpoint-hooks!
  "Register an `org.crac.Resource` that `quiesce!`s the system before a
   checkpoint and `resume!`s it after a restore. Inert on a non-CRaC JVM.
   Returns the Resource."
  [system]
  (let [res (reify Resource
              (beforeCheckpoint [_ _] (quiesce! system))

              (afterRestore [_ _] (resume! system)))]
    (Context/.register (Core/getGlobalContext) res)
    (reset! registered-hook res)
    res))


(defn -main
  "Checkpoint entrypoint. Start the full system (warm: packages + compile +
   serving), register the CRaC hooks, and idle. At BUILD time the Dockerfile
   triggers `jcmd <pid> JDK.checkpoint` once `/tmp/graphden-crac.ready` appears;
   at RUN time the JVM is launched with `-XX:CRaCRestoreFrom` and resumes here."
  [& _args]
  (let [system (sys/start! :prod)]
    ;; Warm the checkpoint/restore handlers in a NORMAL JVM state before the
    ;; hook is armed. CRaC runs `beforeCheckpoint` on an internal thread, and a
    ;; class whose <clinit> runs there for the FIRST time fails with
    ;; ExceptionInInitializerError — e.g. `HikariConfigMXBean`, which nothing
    ;; touches in normal operation, so its first init would otherwise land
    ;; inside the checkpoint. A dry quiesce!→resume! cycle initialises every
    ;; class in both paths now, then leaves the system fully working (pool
    ;; resumed, advisory-lock reopened, the LISTEN loop self-reconnects).
    (log/info "CRaC: warming checkpoint/restore handlers…")
    (quiesce! system)
    (resume! system)
    (register-checkpoint-hooks! system)
    (spit "/tmp/graphden-crac.ready" "1")
    (log/info "CRaC: system warm + hooks registered — ready for checkpoint")
    @(promise)))
