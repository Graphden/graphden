(ns graphden.storage.postgres.source-session
  "Session-incarnation evidence for cross-connection source certificates.
   This module never authorizes rows or enables replay. Only explicitly known
   direct/session pools qualify; transaction pooling cannot preserve markers.
   The certificate caller still owns the first-LOCK schema boundary and every
   fresh schema/XID/physical-membership check."
  (:require
    [graphden.storage.postgres.util :as pg]
    [next.jdbc.result-set :as rs])
  (:import
    (com.zaxxer.hikari.pool
      HikariProxyConnection)
    (java.lang.ref
      WeakReference)
    (java.security
      SecureRandom)
    (java.sql
      Connection
      Timestamp)
    (java.util
      WeakHashMap)
    (org.postgresql
      PGConnection)
    (org.postgresql.core
      TransactionState)
    (org.postgresql.jdbc
      PgConnection)))


(def ^:private witness-seal (Object.))
(def ^:private max-registrations 256)
(defonce ^:private registrations (WeakHashMap.))
(def ^:private random (SecureRandom.))
(def ^:private query-options {:builder-fn rs/as-unqualified-lower-maps :timeout 1})


(deftype Manager
  [seal state]

  Object

  (toString [_] "SourceSessionManager"))


(deftype Marker
  [seal state]

  Object

  (toString [_] "SourceSessionMarker"))


(deftype Origin
  [seal state]

  Object

  (toString [_] "SourceSessionOrigin"))


(defn create-manager
  "One lifecycle owner per known datasource generation. Explicit :mode must
   be :direct or :session; unknown/transaction pooling returns nil. The owner
   must retire its pool when replacing this manager. A physical session cannot
   acquire another manager's marker pair, even after that owner is closed."
  [{:keys [mode max-sessions]}]
  (when (#{:direct :session} mode)
    (when-not (and (pos-int? max-sessions) (<= max-sessions max-registrations))
      (throw (ex-info "Invalid source session bound" {:type ::invalid-bound})))
    (Manager. witness-seal {:id (Object.) :mode mode :max-sessions max-sessions :closed? (atom false)})))


(defn- manager-state
  [manager]
  (when (and (instance? Manager manager) (identical? witness-seal (.-seal ^Manager manager)))
    (.-state ^Manager manager)))


(defn- marker-state
  [marker]
  (when (and (instance? Marker marker) (identical? witness-seal (.-seal ^Marker marker)))
    (.-state ^Marker marker)))


(defn- physical-connection
  [connection]
  ;; Do not invoke arbitrary unwrap implementations. Pool/adaptor ownership is
  ;; additionally proved by the consumer before calling this module.
  (when (and (#{PgConnection HikariProxyConnection} (class connection))
             (not (.isClosed ^Connection connection)))
    (let [physical (.unwrap ^Connection connection PGConnection)]
      (when (= PgConnection (class physical)) physical))))


(defn- execute!
  [connection check! query]
  (check!)
  (let [rows (pg/exec! connection query query-options)]
    (check!)
    rows))


(defn- random-keys
  []
  (let [a (.nextLong random)]
    (loop [b (.nextLong random)]
      (if (= a b) (recur (.nextLong random)) [a b]))))


(defn- prune-closed!
  []
  ;; WeakHashMap values must never retain their physical key strongly.
  (let [entries (.iterator (.entrySet registrations))]
    (while (.hasNext entries)
      (let [entry (.next entries) connection (.getKey ^java.util.Map$Entry entry)]
        (when (.isClosed ^Connection connection) (.remove entries))))))


(defn- reserve-marker!
  [owner physical]
  (locking registrations
    (prune-closed!)
    (if-let [existing (.get registrations physical)]
      (let [{:keys [owner-id status]} (marker-state existing)]
        (when (and (identical? (:id owner) owner-id) (= :active @status))
          [existing false]))
      (when (and (< (.size registrations) max-registrations)
                 (< (count (filter #(identical? (:id owner) (:owner-id (marker-state %)))
                                   (.values registrations)))
                    (:max-sessions owner)))
        (let [marker (Marker. witness-seal {:owner-id (:id owner) :closed? (:closed? owner)
                                            :physical (WeakReference. physical) :keys (random-keys)
                                            :status (atom :pending)})]
          (.put registrations physical marker)
          [marker true])))))


(defn- release-keys!
  [connection lock-keys]
  ;; Called only on the caller's owned autocommit borrow. If cleanup itself
  ;; fails, close the physical session: returning live orphan markers to a
  ;; pool would make the lock bound fictitious. Never unlock a different key.
  (try
    (pg/exec! connection
              ["SELECT pg_advisory_unlock(?) AS first, pg_advisory_unlock(?) AS second"
               (first lock-keys) (second lock-keys)] query-options)
    (catch Throwable error
      (when-let [physical (physical-connection connection)]
        (try (.close ^Connection physical)
             (catch Throwable close-error (.addSuppressed error close-error))))
      (throw error))))


(defn ensure-marker!
  "Before owning RR, register a random nonblocking lock pair once per physical
   session. Repeated calls perform no SQL or reentrant acquisitions. Unknown
   modes/wrappers, occupied bounds and retired sessions return nil. SQL/cancel
   errors propagate; callers must unwind the borrow before ordinary fallback.
   A collision or lost pair never reinstalls old keys on that physical object."
  [manager ^Connection connection check!]
  (check!)
  (when-let [owner (manager-state manager)]
    (when-let [physical (physical-connection connection)]
      (when (and (not @(:closed? owner)) (.getAutoCommit connection)
                 (= TransactionState/IDLE (.getTransactionState ^PgConnection physical)))
        (when-let [[marker fresh?] (reserve-marker! owner physical)]
          (if-not fresh?
            marker
            (let [{:keys [status] lock-keys :keys} (marker-state marker)
                  attempted? (volatile! false)
                  failure (volatile! nil)]
              (try
                (check!)
                (vreset! attempted? true)
                (let [row (first (execute! connection check!
                                           ["SELECT pg_try_advisory_lock(?) AS first, pg_try_advisory_lock(?) AS second"
                                            (first lock-keys) (second lock-keys)]))]
                  (when (and (true? (:first row)) (true? (:second row))
                             (not @(:closed? owner)))
                    (reset! status :active)
                    marker))
                (catch Throwable error
                  (vreset! failure error)
                  (throw error))
                (finally
                  (when (or (not= :active @status) @(:closed? owner))
                    (reset! status :retired)
                    (when @attempted?
                      (try (release-keys! connection lock-keys)
                           (catch Throwable cleanup-error
                             (if-let [error @failure]
                               (.addSuppressed ^Throwable error cleanup-error)
                               (throw cleanup-error)))))))))))))))


(defn close-manager!
  "Revoke publication/use without executing SQL on potentially borrowed
   connections. The lifecycle owner must close/retire its pool to release the
   two session locks. Existing marker registrations prevent lock growth if a
   new manager is accidentally attached to those still-live physical handles."
  [manager]
  (when-let [state (manager-state manager)]
    (reset! (:closed? state) true)
    nil))


(defn- marker-live?
  [state]
  (and state (not @(:closed? state)) (= :active @(:status state))
       (when-let [physical (.get ^WeakReference (:physical state))]
         (not (.isClosed ^Connection physical)))))


(defn- guarded-connection
  [connection]
  (when-let [physical (physical-connection connection)]
    (when (and (not (.getAutoCommit ^Connection connection))
               (.isReadOnly ^Connection connection)
               ;; getTransactionIsolation may issue driver SQL. Refuse an IDLE
               ;; transaction before that call, preserving first-LOCK ordering.
               (= TransactionState/OPEN (.getTransactionState ^PgConnection physical))
               (= Connection/TRANSACTION_REPEATABLE_READ (.getTransactionIsolation ^Connection connection)))
      physical)))


(def ^:private marker-condition
  (str "2=(SELECT count(*) FROM pg_locks l WHERE l.pid=a.pid "
       "AND l.database=a.datid AND l.locktype='advisory' AND l.granted "
       "AND l.mode='ExclusiveLock' AND l.objsubid=1 "
       "AND ((l.classid::bigint << 32) | l.objid::bigint) IN (?,?))"))


(def ^:private identity-query
  (str "SELECT a.pid,a.backend_start,a.datid::text AS database,a.usesysid::text AS role "
       "FROM pg_stat_activity a WHERE a.pid=pg_backend_pid() AND " marker-condition))


(defn- origin-present?
  [connection check! {:keys [pid start database role] lock-keys :keys}]
  ;; Activity statistics are transaction cached independently of MVCC. Only
  ;; clear them AFTER the certificate's first relation LOCK, never before it.
  (execute! connection check! ["SELECT pg_stat_clear_snapshot()"])
  (true?
    (:present
      (first
        (execute! connection check!
                  [(str "SELECT EXISTS (SELECT 1 FROM pg_stat_activity a "
                        "WHERE a.pid=? AND a.backend_start=? AND a.datid::text=? AND a.usesysid::text=? "
                        "AND a.datid=(SELECT oid FROM pg_database WHERE datname=current_database()) "
                        "AND " marker-condition ") AS present")
                   pid (Timestamp/from start) database role (first lock-keys) (second lock-keys)])))))


(defn seal-origin
  "Seal a marker's actual backend identity inside capture RR AFTER first LOCK.
   check! must include the caller's active certificate-guard check. No origin
   can be read from wire data. This evidence replaces only physical incarnation;
   it says nothing about authorization, schema, XID or unchanged source rows."
  [marker connection check!]
  (check!)
  (let [state (marker-state marker)]
    (when (marker-live? state)
      (when-let [physical (guarded-connection connection)]
        (when (identical? physical (.get ^WeakReference (:physical state)))
          (execute! connection check! ["SELECT pg_stat_clear_snapshot()"])
          (let [{:keys [pid backend_start database role]}
                (first (execute! connection check! (into [identity-query] (:keys state))))
                origin-identity (when (and (= pid (.getBackendPID ^PgConnection physical))
                                           backend_start database role)
                                  {:pid pid :start (.toInstant ^Timestamp backend_start)
                                   :database database :role role :keys (:keys state)})]
            (if origin-identity
              (Origin. witness-seal {:marker marker :identity origin-identity})
              (do (reset! (:status state) :retired) nil))))))))


(defn- registered-destination?
  [origin-state physical]
  (locking registrations
    (let [state (marker-state (.get registrations physical))]
      (and (marker-live? state)
           (identical? (:owner-id origin-state) (:owner-id state))))))


(defn origin-live?
  "Fresh destination-RR proof, after its first LOCK and before any cached row.
   Hidden backend_start, missing locks, wrong database/session or retired local
   origin yields false. The destination must have ensure-marker! from the SAME
   manager before RR: the origin cannot attest an unknown destination pool's
   session mode. Never reuse the boolean outside this guarded call."
  [origin connection check!]
  (check!)
  (boolean
    (when (and (instance? Origin origin) (identical? witness-seal (.-seal ^Origin origin)))
      (let [{:keys [marker] origin-identity :identity} (.-state ^Origin origin)
            state (marker-state marker)]
        (and (marker-live? state)
             (when-let [physical (guarded-connection connection)]
               (and (registered-destination? state physical)
                    (origin-present? connection check! origin-identity))))))))


(defn stats
  "Counts only; no random keys, backend identities or pool references."
  [manager]
  (when-let [owner (manager-state manager)]
    (locking registrations
      (prune-closed!)
      (let [states (keep #(let [state (marker-state %)]
                            (when (identical? (:id owner) (:owner-id state)) state))
                         (.values registrations))]
        {:registered (count states)
         :active (count (filter #(and (= :active @(:status %)) (not @(:closed? %))) states))
         :closed? @(:closed? owner)}))))
