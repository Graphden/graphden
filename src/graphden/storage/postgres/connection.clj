(ns graphden.storage.postgres.connection
  "Dedicated (non-pooled) JDBC Connection openers.

   The main storage uses HikariCP — connections rotate through the
   pool and can be reused by any caller. Two features need
   session-stable connections that CANNOT be returned to a shared
   pool:

   - `:db/notify-listener` LISTENs on a connection; the blocking
     `getNotifications` call holds the session for the listener's
     lifetime.
   - `:db/service-locks` holds Postgres advisory locks
     (`pg_try_advisory_lock`); locks are SESSION-scoped, so the
     connection that took the lock must be the one that holds it
     for the service's lifetime.

   These helpers open raw `java.sql.Connection`s via the PG JDBC
   driver, bypassing the pool. Caller owns the lifecycle."
  (:require
    [clojure.tools.logging :as log])
  (:import
    (java.sql
      Connection
      DriverManager)
    (java.util
      Properties)))


(defn open-dedicated!
  "Open a single PG Connection using the same credentials as the
   main pool. `purpose` is a short tag (`\"notify-listener\"`,
   `\"service-locks\"`) used only in log lines. autoCommit is set
   to true — both the LISTEN loop and the advisory-lock helpers
   manage their own statement boundaries."
  ^Connection [{:keys [jdbc-url username password]} purpose]
  (let [props (Properties.)]
    (when username (Properties/.setProperty props "user" username))
    (when password (Properties/.setProperty props "password" password))
    (let [conn (DriverManager/getConnection ^String jdbc-url props)]
      (Connection/.setAutoCommit conn true)
      (log/info "Opened dedicated PG connection" {:purpose purpose})
      conn)))


(defn close-dedicated!
  "Best-effort close. Idempotent. Logs but doesn't rethrow — used
   from halt-key! where we want shutdown to keep draining."
  [^Connection conn purpose]
  (when conn
    (try
      (when-not (Connection/.isClosed conn)
        (Connection/.close conn)
        (log/info "Closed dedicated PG connection" {:purpose purpose}))
      (catch Exception e
        (log/warn e "Failed to close dedicated PG connection" {:purpose purpose})))))
