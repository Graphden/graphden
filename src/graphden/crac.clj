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
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.storage.postgres.connection :as pg-conn]
    [graphden.system.interface :as sys])
  (:import
    (com.zaxxer.hikari
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


(defn quiesce!
  "Close every live socket CRIU can't snapshot: suspend + drain the Hikari pool,
   close the LISTEN connection, close the advisory-lock connection. Best-effort
   per resource — one failure must not block the checkpoint."
  [system]
  (when-let [^HikariDataSource pool (pool-of system)]
    (try
      (when-let [mx (HikariDataSource/.getHikariPoolMXBean pool)]
        (HikariPoolMXBean/.suspendPool mx)
        (HikariPoolMXBean/.softEvictConnections mx))
      (catch Exception e (log/warn e "CRaC: pool suspend/evict failed"))))
  (when-let [listener (:db/notify-listener system)]
    (try
      (pg-conn/close-dedicated! @(:conn-atom listener) "notify-listener")
      (catch Exception e (log/warn e "CRaC: closing LISTEN connection failed"))))
  (when-let [holder (:db/service-locks system)]
    (try
      (pg-conn/close-dedicated! (pg-lock/holder-conn holder) "service-locks")
      (catch Exception e (log/warn e "CRaC: closing advisory-lock connection failed"))))
  (log/info "CRaC: quiesced DB resources for checkpoint"))


(defn resume!
  "Re-establish what `quiesce!` closed: resume the pool (connections re-open on
   demand) and heal the advisory-lock holder. The LISTEN loop self-reconnects on
   its next poll of the now-closed connection."
  [system]
  (when-let [^HikariDataSource pool (pool-of system)]
    (try
      (when-let [mx (HikariDataSource/.getHikariPoolMXBean pool)]
        (HikariPoolMXBean/.resumePool mx))
      (catch Exception e (log/warn e "CRaC: pool resume failed"))))
  (when-let [holder (:db/service-locks system)]
    (try
      (pg-lock/ensure-live! holder)
      (catch Exception e (log/warn e "CRaC: advisory-lock reconnect failed"))))
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
    (register-checkpoint-hooks! system)
    (spit "/tmp/graphden-crac.ready" "1")
    (log/info "CRaC: system warm + hooks registered — ready for checkpoint")
    @(promise)))
