(ns graphden.storage.postgres.advisory-lock
  "Postgres session-scoped advisory locks for per-service ownership
   in multi-pod deployments.

   ## Why session-scoped, not transaction-scoped

   `pg_try_advisory_xact_lock` is shorter-lived (auto-released at
   commit), which makes it useless for service ownership: we need
   to hold the lock for as long as the service is running, not just
   during a reconcile pass. `pg_try_advisory_lock` (session-scoped)
   stays held until the connection's session ends — either by
   `pg_advisory_unlock` or by connection close. Pod crash → session
   ends → lock auto-released → another pod can take over.

   ## Why a dedicated connection

   Advisory locks live on the SESSION (Postgres connection). If we
   took a lock through a pooled connection, returning the connection
   leaves the lock held but the session reachable by any other
   caller — they could `pg_advisory_unlock` accidentally. By using
   ONE dedicated connection per pod, we get a stable session that
   nobody else uses.

   ## Key choice

   Postgren's advisory-lock key is a `bigint`. We derive it from the
   service-id UUID's `Most Significant Bits` long. Collisions across
   service-ids would need two UUIDv5 values whose first 64 bits
   match — astronomically unlikely for our scale.

   The single dedicated connection holds ALL of this pod's service
   locks simultaneously — Postgres advisory locks are independent
   per `(classid, objid)` key, so one connection can hold N locks.

   ## Single-pod behaviour

   In single-pod deployments, every `try-lock` succeeds immediately
   (no contender), every `release-lock` succeeds. Behaviour is
   identical to pre-coordination."
  (:require
    [clojure.tools.logging :as log]
    [graphden.storage.postgres.connection :as pg-conn]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection)
    (java.util
      UUID)))


(defn- service-id->lock-key
  "Service UUID → bigint advisory-lock key.

   Take the UUID's MSB (most-significant bits) as a long. That's a
   64-bit signed integer derived deterministically from the
   service-id, which is itself deterministic from
   `(package-name, service-name)` — so the key is stable across
   restarts and across pods."
  ^long [service-id]
  (UUID/.getMostSignificantBits ^UUID (if (uuid? service-id) service-id (UUID/fromString service-id))))


;; =============================================================================
;; Lock-connection lifecycle
;; =============================================================================

(defn create-lock-conn
  "Open the dedicated lock connection. Caller is responsible for
   passing the same `^Connection` to every `try-lock!` /
   `release-lock!` call on this pod, and for `close-lock-conn!` at
   shutdown."
  ^Connection [pg-opts]
  (pg-conn/open-dedicated! pg-opts "service-locks"))


(defn close-lock-conn!
  "Close the lock connection. Postgres releases every session-held
   advisory lock at this moment — sibling pods can take over within
   the next reconcile pass."
  [^Connection conn]
  (pg-conn/close-dedicated! conn "service-locks"))


;; =============================================================================
;; Lock operations
;; =============================================================================

(defn try-lock!
  "Attempt `pg_try_advisory_lock(key)` for `service-id` on the
   pod's dedicated lock connection. Non-blocking — returns true
   when we acquired the lock, false when another pod already holds
   it.

   Thread-safety: the dedicated `Connection` is NOT thread-safe;
   call from a single coordination thread (the reconciler is
   serial today, so this isn't a concern, but if a future caller
   parallelises reconciles they need to lock around this call)."
  [^Connection conn service-id]
  (let [k (service-id->lock-key service-id)
        rows (jdbc/execute! conn ["SELECT pg_try_advisory_lock(?) AS acquired" k])]
    (boolean (:acquired (first rows)))))


(defn release-lock!
  "Release the advisory lock for `service-id`. Returns true if a
   lock was held by this session (and is now released), false if
   no such lock was held — same semantics as
   `pg_advisory_unlock`.

   Idempotent at the call-site level: calling for a service we
   don't own logs a debug line and returns false."
  [^Connection conn service-id]
  (let [k (service-id->lock-key service-id)
        rows (jdbc/execute! conn ["SELECT pg_advisory_unlock(?) AS released" k])
        released? (boolean (:released (first rows)))]
    (when-not released?
      (log/debug "advisory unlock of service we don't own — no-op"
                 {:service-id service-id}))
    released?))


(defn release-all!
  "Release every advisory lock held by this session. Cheaper than
   tracking per-service locks and calling `release-lock!` for each
   — used from `stop-all!` during pod halt to free locks before the
   connection closes (Postgres would do it anyway, but explicit is
   visible in logs)."
  [^Connection conn]
  (jdbc/execute! conn ["SELECT pg_advisory_unlock_all()"])
  nil)
