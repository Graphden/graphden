(ns graphden.crud.fn-execution.stats
  "Usage rollups (Phase C1) — the WRITE and READ halves over `:usage-stat`.

   `bump!` is called at every execution's terminal transition (both the
   inline-resolution arms and the async reaper) with counts/duration ONLY —
   no args, no results, no error text ever reach this table, so it is safe
   to read across orgs and cheap to keep: one row per
   `(hour-bucket, org, fn, status)`, incremented in place by an atomic
   `INSERT … ON CONFLICT DO UPDATE`. Growth is bounded by distinct keys,
   not traffic.

   Raw SQL over the pg pool (NOT the storage protocol): `:usage-stat` is a
   non-versioned platform aggregate — the same create-time-reads contract as
   `tenancy.plan`'s quota counters — and the counter upsert has no protocol
   primitive. Reads scope org explicitly (tenant → own rows), independent of
   RLS, so the query is correct even on a superuser dev pool.

   Best-effort by design: a failed bump logs and never fails the execution."
  (:require
    [clojure.tools.logging :as log]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.time
      Instant
      ZoneOffset)
    (java.time.temporal
      ChronoUnit)))


(defn hour-bucket
  "`now-ms` truncated to its UTC clock hour, as a `java.sql.Timestamp`
   (pgjdbc can't infer a bind type for a bare `Instant`)."
  ^java.sql.Timestamp [now-ms]
  (-> (Instant/ofEpochMilli now-ms)
      (Instant/.atZone ZoneOffset/UTC)
      (java.time.ZonedDateTime/.truncatedTo ChronoUnit/HOURS)
      (java.time.ZonedDateTime/.toInstant)
      (java.sql.Timestamp/from)))


(defn bump!
  "Increment the rollup row for one finished execution. `pool` is the raw
   datasource (`(:pool pg-storage)`); `org` nil → \"public\" (platform /
   single-tenant / service runs). `duration-ms` nil (unknown) still counts
   the execution. Never throws — observability must not break execution."
  [pool {:keys [org fn-id status duration-ms now-ms]}]
  (when (and pool fn-id status)
    (try
      (jdbc/execute-one!
        pool
        [(str "INSERT INTO \"usage_stat\""
              " (id, bucket_start, org_id, fn_id, status, count, duration_ms_sum)"
              " VALUES (gen_random_uuid(), ?, ?, ?, ?, 1, ?)"
              " ON CONFLICT (bucket_start, org_id, fn_id, status)"
              " DO UPDATE SET count = usage_stat.count + 1,"
              " duration_ms_sum = coalesce(usage_stat.duration_ms_sum, 0)"
              "                   + coalesce(EXCLUDED.duration_ms_sum, 0)")
         (hour-bucket (or now-ms (System/currentTimeMillis)))
         (or org "public")
         fn-id
         (name status)
         duration-ms])
      (catch Exception e
        (log/warn e "usage-stat bump failed (execution unaffected)"
                  {:fn-id fn-id :status status})))))


(defn fn-stats
  "Aggregate rollups for ONE fn over the trailing `days` (default 7), scoped
   to `org` (nil → \"public\"): `{:runs N :failed N :cancelled N
   :duration-ms-sum N}` — zeros when no rows. Counts/durations only; safe for
   any caller that may see the fn."
  [pool org fn-id days]
  (when pool
    (let [row (jdbc/execute-one!
                pool
                [(str "SELECT"
                      " coalesce(sum(count), 0) AS runs,"
                      " coalesce(sum(count) FILTER (WHERE status = 'failed'), 0) AS failed,"
                      " coalesce(sum(count) FILTER (WHERE status = 'cancelled'), 0) AS cancelled,"
                      " coalesce(sum(duration_ms_sum), 0) AS duration_ms_sum"
                      " FROM \"usage_stat\""
                      " WHERE fn_id = ? AND org_id = ?"
                      " AND bucket_start >= now() - make_interval(days => ?)")
                 fn-id (or org "public") (int (or days 7))]
                {:builder-fn rs/as-unqualified-lower-maps})]
      ;; `sum()` comes back numeric (BigDecimal) — normalise to longs.
      {:runs (long (:runs row 0))
       :failed (long (:failed row 0))
       :cancelled (long (:cancelled row 0))
       :duration-ms-sum (long (:duration_ms_sum row 0))})))


(defn org-stats
  "Per-fn rollup rows for `org` over the trailing `days` (default 7), busiest
   first, capped at `limit` (default 50): `[{:fn-id … :runs … :failed …
   :duration-ms-sum …} …]`. The operator's per-org load view and the tenant's
   own-workspace view are the same query with a different `org`."
  [pool org days limit]
  (when pool
    (mapv (fn [r]
            {:fn-id (:fn_id r)
             :runs (long (:runs r))
             :failed (long (:failed r))
             :duration-ms-sum (long (or (:duration_ms_sum r) 0))})
          (jdbc/execute!
            pool
            [(str "SELECT fn_id,"
                  " coalesce(sum(count), 0) AS runs,"
                  " coalesce(sum(count) FILTER (WHERE status = 'failed'), 0) AS failed,"
                  " coalesce(sum(duration_ms_sum), 0) AS duration_ms_sum"
                  " FROM \"usage_stat\""
                  " WHERE org_id = ?"
                  " AND bucket_start >= now() - make_interval(days => ?)"
                  " GROUP BY fn_id ORDER BY runs DESC LIMIT ?")
             (or org "public") (int (or days 7)) (int (or limit 50))]
            {:builder-fn rs/as-unqualified-lower-maps}))))


(defn sweep-stats!
  "Retention: delete rollup rows older than `retention-days`. Returns the
   deleted count. Called by the cleanup scheduler alongside the execution
   TTL sweep."
  [pool retention-days]
  (when pool
    (-> (jdbc/execute-one!
          pool
          [(str "DELETE FROM \"usage_stat\""
                " WHERE bucket_start < now() - make_interval(days => ?)")
           (int (or retention-days 90))])
        :next.jdbc/update-count)))
