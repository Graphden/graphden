(ns graphden.storage.postgres.notify
  "Postgres LISTEN/NOTIFY transport for cross-process events.

   Used today by `services.reconciler` to react to `:service` row
   writes from other pods; the same mechanism will carry fn-def
   invalidation events in Block 7 sub-block B. Every executor pod
   keeps ONE dedicated `Connection` LISTENing on the
   `graphden_events` channel; a background thread polls
   `getNotifications` and dispatches to a registered callback set.

   Wire format on the channel is a minimal `<kind>:<op>:<payload>`
   string — keeps the 8KB NOTIFY payload limit comfortable and
   avoids JSON parsing in the hot loop. Today's kinds:

   - `service:write:<service-uuid>`  — `:service` row inserted or
                                       updated
   - `service:delete:<service-uuid>` — row deleted

   Future kinds (Block 7 sub-block B) will likely include
   `fn:invalidate:<fn-uuid>` and similar; callbacks pattern-match
   on `:kind` to opt in."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.postgres.connection :as pg-conn]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection
      Statement)
    (org.postgresql
      PGConnection
      PGNotification)))


(def channel-name "graphden_events")


(defn parse-payload
  "Wire-format → `{:kind <keyword> :op <keyword> :id <string>}`. nil
   when the payload doesn't match the expected shape — used for
   defensive parsing so a malformed NOTIFY from a future graphden
   version doesn't crash the loop."
  [payload]
  (when (string? payload)
    (let [parts (str/split payload #":" 3)]
      (when (= 3 (count parts))
        {:kind (keyword (nth parts 0))
         :op (keyword (nth parts 1))
         :id (nth parts 2)}))))


(defn format-payload
  "Inverse of `parse-payload`. Used by emitters."
  [{:keys [kind op id]}]
  (str (name kind) ":" (name op) ":" (or id "")))


;; =============================================================================
;; LISTEN connection + dispatch loop
;; =============================================================================

(defn- run-listen!
  "Execute `LISTEN graphden_events` on the connection. The session
   stays subscribed until the connection closes."
  [^Connection conn]
  (with-open [stmt ^Statement (Connection/.createStatement conn)]
    (Statement/.execute stmt (str "LISTEN " channel-name))))


(defn- poll-once!
  "Drain the connection's pending notifications. Returns the vec of
   `{:kind :op :id}` maps successfully parsed.

   `PGConnection.getNotifications(timeout-ms)` blocks up to the
   timeout if no notifications are pending. We use a short timeout
   so the loop can check the `running?` flag and exit cleanly on
   halt."
  [^Connection conn timeout-ms]
  (let [pg ^PGConnection (Connection/.unwrap conn PGConnection)
        notifs ^"[Lorg.postgresql.PGNotification;" (PGConnection/.getNotifications pg (int timeout-ms))]
    (if notifs
      (vec (keep (fn [^PGNotification n]
                   (let [raw (PGNotification/.getParameter n)
                         parsed (parse-payload raw)]
                     (when-not parsed
                       (log/warn "Unrecognised NOTIFY payload" {:raw raw}))
                     parsed))
                 notifs))
      [])))


(defn- dispatch!
  "Invoke each callback with the parsed event. Callback exceptions
   are isolated (logged, don't break the loop) so one buggy listener
   doesn't take down the others."
  [callbacks event]
  (doseq [cb @callbacks]
    (try
      (cb event)
      (catch Exception e
        (log/error e "NOTIFY dispatch callback threw" {:event event})))))


(defn- listen-loop
  "Blocking loop — `poll-once!` then dispatch, while `running?` is
   true. Runs on a dedicated thread spawned by `create-listener`.

   Timeout is 1s: low enough that halt is responsive, high enough
   that an idle pod doesn't busy-poll."
  [^Connection conn callbacks running?]
  (try
    (while @running?
      (try
        (doseq [event (poll-once! conn 1000)]
          (dispatch! callbacks event))
        (catch InterruptedException _
          (Thread/.interrupt (Thread/currentThread)))
        (catch Exception e
          (when @running?
            (log/error e "NOTIFY listen loop iteration failed — continuing"))
          (Thread/sleep 1000))))
    (finally
      (log/info "NOTIFY listen loop exiting"))))


;; =============================================================================
;; Public: create / register / close
;; =============================================================================

(defn create-listener
  "Open the LISTEN connection, subscribe to `graphden_events`, start
   the background dispatch thread. Returns a handle map; pass it to
   `register!` / `unregister!` / `close-listener!`."
  [pg-opts]
  (let [conn (pg-conn/open-dedicated! pg-opts "notify-listener")
        callbacks (atom #{})
        running? (atom true)]
    (run-listen! conn)
    (let [thread (doto (Thread. ^Runnable
                        #(listen-loop conn callbacks running?)
                                "graphden-notify-listener")
                   (Thread/.setDaemon true)
                   Thread/.start)]
      {:connection conn
       :callbacks callbacks
       :running? running?
       :thread thread})))


(defn register!
  "Add a callback to the listener's dispatch set. Idempotent — adding
   the same callable twice is a no-op."
  [{:keys [callbacks]} callback]
  (swap! callbacks conj callback)
  callback)


(defn unregister!
  "Remove a previously-registered callback."
  [{:keys [callbacks]} callback]
  (swap! callbacks disj callback)
  nil)


(defn close-listener!
  "Stop the dispatch thread + close the connection. Idempotent —
   calling twice is harmless."
  [{:keys [^Connection connection ^Thread thread running?]}]
  (when running? (reset! running? false))
  (when thread
    (try
      (Thread/.join thread 2000)
      (catch InterruptedException _ nil)))
  (pg-conn/close-dedicated! connection "notify-listener"))


;; =============================================================================
;; Emit — short-lived statement against the main pool
;; =============================================================================

(defn make-emitter
  "Build a `(fn [event])` closure that sends a NOTIFY on
   `graphden_events`. `ds` is the HikariCP DataSource from the
   `:db/postgres` integrant key — short-lived `pg_notify` SQL is
   safe to run through the pool because it commits before the
   connection returns.

   Best-effort: catches and logs any SQLException so a write that
   succeeded at the row level isn't rolled back by a transient
   NOTIFY failure. The reconciler's mutation-driven reconcile path
   IS the primary correctness mechanism; the NOTIFY just speeds up
   propagation to sibling pods."
  [ds]
  (fn emit-notify
    [event]
    (try
      (jdbc/execute! ds ["SELECT pg_notify(?, ?)" channel-name (format-payload event)])
      nil
      (catch Exception e
        (log/warn e "NOTIFY emit failed — sibling pods may lag until next mutation"
                  {:event event})))))


(defn noop-emitter
  "Sentinel emitter for ctx that never crosses pod boundaries
   (tests with single in-process storage). Drops every event."
  [_event]
  nil)
