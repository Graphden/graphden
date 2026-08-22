(ns graphden.crud.fn-execution.retention
  "Retention for the execution log — the sweep that force-cancels zombie
   `:pending` rows and deletes finished ones past their per-status TTL,
   plus the reclamation of their child rows.

   Raw SQL over the pg pool (NOT the storage protocol), for the same
   reasons `stats.clj` is: `:fn-execution` and its two child tables are
   non-versioned platform tables, the predicate is a TIME RANGE (the
   protocol's `:where` expresses only `=` / `IN` / `IS NULL`), and a
   retention sweep must not pull the table into the JVM to filter it
   there — this runs hourly against a table that grows with traffic.

   Three facts drive the shape:

   - The child tables carry NO foreign key: `:ref` fields become plain
     indexed UUID columns (`postgres/ddl.clj` emits `REFERENCES … ON
     DELETE CASCADE` only for junction tables), so deleting a parent
     leaves its `:fn-execution-arg` / `:fn-execution-arg-item` rows
     behind and nothing else in the system ever removes them. The sweep
     reclaims them by ANTI-JOIN, which also clears the backlog earlier
     sweeps orphaned — no migration, no id list to carry between
     statements, idempotent on every tick.
   - Nothing writes a child before its parent (`create-pending-with-
     args!` creates the execution row first, `persist-args!` the arg row
     before its items), so an anti-join can never race a half-written
     execution into deletion.
   - pgjdbc can't infer a bind type for a bare `Instant` (same note as
     `stats/hour-bucket`), so cutoffs go over the wire as
     `java.sql.Timestamp`, and enum columns are compared as
     `status::text = ?` so the status stays a bound parameter instead of
     an interpolated literal."
  (:require
    [clojure.string :as str]
    [next.jdbc :as jdbc])
  (:import
    (java.time
      Instant)))


(def ^:private zombie-pending-ms
  "A `:pending` row older than this lost its future to a JVM restart —
   flip it to `:cancelled` so the polling client stops waiting forever."
  (* 60 60 1000))


(def ^:private retention-days
  "Per-status retention for FINISHED executions. `:pending` is absent on
   purpose — it has no finish stamp to age against and is handled by the
   zombie arm instead."
  (array-map
    "succeeded" 7
    "failed" 30
    "cancelled" 7))


(defn- cutoff
  "`now` minus `ms`, as the `java.sql.Timestamp` pgjdbc can bind."
  ^java.sql.Timestamp [^Instant now ms]
  (java.sql.Timestamp/from (Instant/.minusMillis now (long ms))))


(defn- days-ms
  [days]
  (* (long days) 24 60 60 1000))


(defn- update-count
  [result]
  (or (:next.jdbc/update-count result) 0))


(defn- cancel-zombies!
  "Force-cancel `:pending` rows whose future died with the JVM."
  [pool ^Instant now]
  (update-count
    (jdbc/execute-one!
      pool
      [(str "UPDATE \"fn_execution\""
            " SET status = 'cancelled', finished_at = ?,"
            "     error = 'zombie: pending > 1h, swept'"
            " WHERE status::text = 'pending' AND started_at < ?")
       (java.sql.Timestamp/from now)
       (cutoff now zombie-pending-ms)])))


(defn- delete-expired!
  "Delete finished rows past their per-status retention. One arm per
   entry in `retention-days` — built from the map so a retention change
   is a one-line edit, with the status still a bound parameter."
  [pool ^Instant now]
  (let [arms (vec retention-days)]
    (update-count
      (jdbc/execute-one!
        pool
        (into [(str "DELETE FROM \"fn_execution\""
                    " WHERE finished_at IS NOT NULL AND ("
                    (str/join " OR "
                              (repeat (count arms)
                                      "(status::text = ? AND finished_at < ?)"))
                    ")")]
              (mapcat (fn [[status days]] [status (cutoff now (days-ms days))]))
              arms)))))


(defn- reclaim-orphans!
  "Delete child rows whose parent is gone — the rows the TTL delete above
   just detached, plus every row detached by an earlier sweep. Args
   before items, so items detached by this very statement are reclaimed
   on the same tick."
  [pool]
  (let [args (update-count
               (jdbc/execute-one!
                 pool
                 [(str "DELETE FROM \"fn_execution_arg\" a"
                       " WHERE NOT EXISTS (SELECT 1 FROM \"fn_execution\" e"
                       "                    WHERE e.id = a.execution_id)")]))
        items (update-count
                (jdbc/execute-one!
                  pool
                  [(str "DELETE FROM \"fn_execution_arg_item\" i"
                        " WHERE NOT EXISTS (SELECT 1 FROM \"fn_execution_arg\" a"
                        "                    WHERE a.id = i.execution_arg_id)")]))]
    {:args args :items items}))


(defn sweep-executions!
  "Run one retention pass over the execution log: cancel zombies, delete
   finished rows past TTL, reclaim orphaned child rows. Four set-based
   statements, no result set read into the JVM.

   `pool` is the raw datasource (`(:pool pg-storage)`); nil → no-op, so a
   bare test context costs nothing. `now` is an injectable `Instant` for
   deterministic tests.

   Returns `{:cancelled N :deleted N :args N :items N}`."
  ([pool] (sweep-executions! pool (Instant/now)))
  ([pool ^Instant now]
   (when pool
     (let [cancelled (cancel-zombies! pool now)
           deleted (delete-expired! pool now)]
       (merge {:cancelled cancelled :deleted deleted}
              (reclaim-orphans! pool))))))
