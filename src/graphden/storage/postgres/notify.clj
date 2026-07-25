(ns graphden.storage.postgres.notify
  "Postgres LISTEN/NOTIFY transport for cross-process events.

   Carries `:service` row writes (→ `services.reconciler`) and fn-graph
   invalidation events (→ `:exec/compiled-registry`) between pods. Every
   executor pod keeps ONE dedicated `Connection` LISTENing on the
   `graphden_events` channel; a background thread polls
   `getNotifications` and dispatches to a registered callback set. On a
   connection drop (DB restart / network blip) the loop reconnects with
   backoff + re-LISTENs, so the pod doesn't go permanently deaf.

   Wire format on the channel is a minimal `<kind>:<op>:<payload>`
   string — keeps the 8KB NOTIFY payload limit comfortable and
   avoids JSON parsing in the hot loop. Kinds:

   - `service:write:<service-uuid>`  — `:service` row inserted / updated
   - `service:delete:<service-uuid>` — row deleted
   - `fn:invalidate:<fn-uuid>|<branch-uuid>`
                                     — delta invalidation of one fn's
                                       compiled closure (empty fn-uuid ⇒
                                       full-clear)
   - `execution:cancel:<execution-uuid>`
                                     — cancel a running `:fn-execution`.
                                       The `futures-registry` is
                                       per-process, so the pod that got
                                       the HTTP cancel fans out and the
                                       owning pod acts on it.

   The `|<branch-uuid>` suffix is optional and names the branch the write
   landed on. The receiving pod needs it: a fn edit on `dev` must not
   recompile `main`, and an edit on `main` must recompile every cached
   branch that inherits from it. A payload without the suffix parses to
   `:branch-id nil` and the receiver falls back to invalidating its base
   ctx — which is what pods did before the field existed.

   Callbacks pattern-match on `:kind` to opt in."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.postgres.connection :as pg-conn]
    [graphden.storage.postgres.errors :as pg-errors]
    [graphden.util.backoff :as backoff]
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
  "Wire-format → `{:kind <keyword> :op <keyword> :id <string>
                   :branch-id <string> :org-id <string>}`. nil when the
   payload doesn't match the expected shape — defensive parsing so a
   malformed NOTIFY from a future graphden version doesn't crash the loop.

   The payload tail is positional, `<id>|<branch>|<org>`, and blank trailing
   segments are dropped: `id`, `id|branch`, or `id|branch-or-empty|org`. A
   segment that is absent OR blank OMITS its key (not nil) — event maps are
   compared by exact shape in tests, and `service`-kind consumers never carry
   a branch/org, so a nil-valued key would be noise. `:org-id` (added for
   precise SSE fan-out) rides in the third slot; older payloads without it
   just omit the key."
  [payload]
  (when (string? payload)
    (let [parts (str/split payload #":" 3)]
      (when (= 3 (count parts))
        (let [[id branch-id org-id epochs] (str/split (nth parts 2) #"\|" 4)]
          (cond-> {:kind (keyword (nth parts 0))
                   :op (keyword (nth parts 1))
                   :id (or id "")}
            (not (str/blank? branch-id)) (assoc :branch-id branch-id)
            (not (str/blank? org-id)) (assoc :org-id org-id)
            ;; 4th slot (audit-7): the writer's exact graph-epoch bump
            ;; values, comma-joined — the receiving pod marks them
            ;; COVERED so its lazy epoch validation doesn't heal over a
            ;; delta this very event already applied. Older payloads
            ;; omit the slot → no coverage → one coarse heal, safe.
            (not (str/blank? epochs))
            (assoc :epochs (into []
                                 (keep #(try (Long/parseLong %)
                                             (catch NumberFormatException _ nil)))
                                 (str/split epochs #",")))))))))


(defn format-payload
  "Inverse of `parse-payload`. Later slots force earlier (possibly
   empty) ones to be present so the positions line up."
  [{:keys [kind op id branch-id org-id epochs]}]
  (let [ep (when (seq epochs) (str/join "," epochs))]
    (str (name kind) ":" (name op) ":" (or id "")
         (cond
           ep (str "|" (or branch-id "") "|" (or org-id "") "|" ep)
           org-id (str "|" (or branch-id "") "|" org-id)
           branch-id (str "|" branch-id)
           :else ""))))


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


(def ^:private default-poll-timeout-ms
  "Production poll timeout — low enough that halt is responsive, high
   enough that an idle pod doesn't busy-poll. Tests can shrink it via
   `:poll-timeout-ms` on `create-listener` to cut Thread/sleep waits."
  1000)


(defn- reconnect!
  "Replace the dead LISTEN connection in `conn-atom` with a fresh one +
   re-`LISTEN`. Best-effort closes the old conn first."
  [conn-atom pg-opts]
  (try (pg-conn/close-dedicated! @conn-atom "notify-listener")
       (catch Exception _))
  (let [c (pg-conn/open-dedicated! pg-opts "notify-listener")]
    ;; If the LISTEN statement throws (the fresh conn dropping in the window
    ;; between open and LISTEN), close `c` — otherwise `reconnect-with-backoff!`
    ;; catches, retries, and opens another, leaking one dedicated connection
    ;; per failed attempt during a flapping DB.
    (try
      (run-listen! c)
      (reset! conn-atom c)
      (catch Exception t
        (try (pg-conn/close-dedicated! c "notify-listener") (catch Exception _))
        (throw t)))))


(defn- reconnect-with-backoff!
  "Loop until reconnect succeeds or `running?` flips false. Exponential
   backoff 1s → 30s cap so a prolonged DB outage doesn't hammer it."
  [conn-atom pg-opts running? cause]
  (log/warn cause "NOTIFY connection lost — reconnecting")
  (loop [backoff-ms backoff/initial-ms]
    (when @running?
      (Thread/sleep (long backoff-ms))
      (when-not (try (reconnect! conn-atom pg-opts)
                     (log/info "NOTIFY listener reconnected")
                     true
                     (catch Exception re
                       (log/warn re "NOTIFY reconnect attempt failed — retrying")
                       false))
        (recur (backoff/next-ms backoff-ms))))))


(defn- listen-loop
  "Blocking loop — `poll-once!` on the current connection, then
   dispatch, while `running?` is true. Runs on a dedicated thread
   spawned by `create-listener`. On a connection-class failure it
   reconnects (fresh conn + re-LISTEN, with backoff) rather than
   spinning forever on a dead connection."
  [conn-atom pg-opts callbacks running? poll-timeout-ms]
  (try
    (while @running?
      (try
        (doseq [event (poll-once! @conn-atom poll-timeout-ms)]
          (dispatch! callbacks event))
        (catch InterruptedException _
          (Thread/.interrupt (Thread/currentThread)))
        (catch Exception e
          (when @running?
            (if (pg-errors/connection-error? e)
              (reconnect-with-backoff! conn-atom pg-opts running? e)
              (do (log/error e "NOTIFY listen loop iteration failed — continuing")
                  (Thread/sleep (long poll-timeout-ms))))))))
    (finally
      (log/info "NOTIFY listen loop exiting"))))


;; =============================================================================
;; Public: create / register / close
;; =============================================================================

(defn create-listener
  "Open the LISTEN connection, subscribe to `graphden_events`, start
   the background dispatch thread. Returns a handle map; pass it to
   `register!` / `unregister!` / `close-listener!`.

   Optional `:poll-timeout-ms` (default 1000) shrinks the
   `getNotifications` blocking interval. Tests use ~250 to cut their
   post-emit `Thread/sleep` wait windows."
  ([pg-opts] (create-listener pg-opts {}))
  ([pg-opts {:keys [poll-timeout-ms]
             :or {poll-timeout-ms default-poll-timeout-ms}}]
   (let [conn (pg-conn/open-dedicated! pg-opts "notify-listener")
         conn-atom (atom conn)
         callbacks (atom #{})
         running? (atom true)]
     (run-listen! conn)
     (let [thread (doto (Thread. ^Runnable
                         #(listen-loop conn-atom pg-opts callbacks running? poll-timeout-ms)
                                 "graphden-notify-listener")
                    (Thread/.setDaemon true)
                    Thread/.start)]
       {:conn-atom conn-atom
        :pg-opts pg-opts
        :callbacks callbacks
        :running? running?
        :thread thread}))))


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
  [{:keys [conn-atom ^Thread thread running?]}]
  (when running? (reset! running? false))
  (when thread
    (try
      (Thread/.join thread 2000)
      (catch InterruptedException _ nil)))
  (when conn-atom
    (pg-conn/close-dedicated! @conn-atom "notify-listener")))


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
