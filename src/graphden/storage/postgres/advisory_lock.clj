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
   ends → lock auto-released → another pod re-takes it on the next
   periodic reconcile tick (`:exec/service-reconciler`, ~15s). The
   crash emits no NOTIFY, so the periodic pass — not an event — is
   what heals a dropped singleton; the reconciler makes this real by
   dropping its `::not-our-lock` placeholders at the top of every pass,
   so a would-be owner re-attempts the lock each tick rather than idling
   until a NOTIFY.

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

   ## Pool slots

   A `:pool` service (`:cardinality :pool`, `:pool-size N`) must run on up
   to N pods at once. It gets N distinct keys — `msb+0 … msb+(N-1)` — and a
   pod holds the FIRST free one (`try-acquire-slot!`). A `:singleton` is
   the N=1 case (slot 0, key `msb+0` = `msb`), so the two share one code
   path. The `+slot` offset can only collide with another service whose msb
   is within N of this one — astronomically unlikely at these key widths.

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


(defn- slot-lock-key
  "Advisory-lock key for `service-id`'s pool `slot` — the service's base
   key offset by the slot index. Slot 0 is the base key, so a singleton
   (a pool of 1 using slot 0) and a pool's slot 0 are the same key."
  ^long [service-id slot]
  (unchecked-add (service-id->lock-key service-id) (long slot)))


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

(defn try-acquire-slot!
  "Attempt `pg_try_advisory_lock` for `service-id`'s pool `slot` on the
   pod's dedicated lock connection. Non-blocking — true when we acquired
   the slot, false when a sibling already holds it. `try-lock!` is the
   slot-0 special case.

   Thread-safety: the dedicated `Connection` is NOT thread-safe;
   call from a single coordination thread (the reconciler is
   serial today, so this isn't a concern, but if a future caller
   parallelises reconciles they need to lock around this call)."
  [^Connection conn service-id slot]
  (let [k (slot-lock-key service-id slot)
        rows (jdbc/execute! conn ["SELECT pg_try_advisory_lock(?) AS acquired" k])]
    (boolean (:acquired (first rows)))))


(defn release-slot!
  "Release the advisory lock for `service-id`'s pool `slot`. Returns true
   if this session held it (now released), false otherwise — same
   semantics as `pg_advisory_unlock`. `release-lock!` is the slot-0 case."
  [^Connection conn service-id slot]
  (let [k (slot-lock-key service-id slot)
        rows (jdbc/execute! conn ["SELECT pg_advisory_unlock(?) AS released" k])
        released? (boolean (:released (first rows)))]
    (when-not released?
      (log/debug "advisory unlock of service slot we don't own — no-op"
                 {:service-id service-id :slot slot}))
    released?))


(defn try-lock!
  "Attempt the singleton advisory lock (pool slot 0) for `service-id`.
   Non-blocking — true when acquired, false when another pod holds it."
  [^Connection conn service-id]
  (try-acquire-slot! conn service-id 0))


(defn release-lock!
  "Release the singleton advisory lock (pool slot 0) for `service-id`.
   Idempotent at the call-site level: unlocking a service we don't own
   logs a debug line and returns false."
  [^Connection conn service-id]
  (release-slot! conn service-id 0))


(defn release-all!
  "Release every advisory lock held by this session. Cheaper than
   tracking per-service locks and calling `release-lock!` for each
   — used from `stop-all!` during pod halt to free locks before the
   connection closes (Postgres would do it anyway, but explicit is
   visible in logs)."
  [^Connection conn]
  (jdbc/execute! conn ["SELECT pg_advisory_unlock_all()"])
  nil)


;; =============================================================================
;; Reconnecting holder
;;
;; A dropped lock connection (DB restart / network blip) silently ends the
;; Postgres session, which RELEASES every advisory lock this pod held — but
;; the pod still believes it owns those services. Left unhandled, a sibling
;; can take the lock and two pods run the same `:singleton` service.
;;
;; The holder wraps the connection in an atom so it can be transparently
;; reopened. `ensure-live!` reports whether it had to reconnect, and the
;; reconciler re-asserts ownership when it did.
;; =============================================================================

(defn create-lock-holder
  "Open the first lock connection and return a holder — an atom of
   `{:conn Connection :pg-opts opts}`. Pass the holder to `holder-conn`
   / `ensure-live!` / `close-holder!`."
  [pg-opts]
  (atom {:conn (create-lock-conn pg-opts) :pg-opts pg-opts}))


(defn holder-conn
  "The holder's current live `Connection`. Callers that ran `ensure-live!`
   this pass can pass this straight to `try-lock!` / `release-lock!`."
  ^Connection [holder]
  (:conn @holder))


(defn ensure-live!
  "Validate the holder's connection; if it's dead, close it and open a
   fresh one. Returns true IFF it reconnected — a reconnect means a new
   Postgres session that holds NONE of the pod's previous advisory locks,
   so the caller must re-acquire ownership of whatever it was running.

   `isValid` runs a lightweight validation query (1s timeout); a throw
   from it is treated as dead."
  [holder]
  (let [{:keys [conn pg-opts]} @holder]
    (if (try (Connection/.isValid conn 1) (catch Exception _ false))
      false
      (do
        (log/warn "service-locks connection is dead — reconnecting")
        (try (pg-conn/close-dedicated! conn "service-locks") (catch Exception _ nil))
        (reset! holder {:conn (create-lock-conn pg-opts) :pg-opts pg-opts})
        true))))


(defn close-holder!
  "Release every lock + close the holder's connection. Best-effort, for
   pod halt."
  [holder]
  (let [conn (:conn @holder)]
    (try (release-all! conn)
         (catch Exception e
           (log/warn e "service-locks release-all failed during holder close")))
    (pg-conn/close-dedicated! conn "service-locks")))
