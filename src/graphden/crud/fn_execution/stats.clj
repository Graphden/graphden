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
    (let [;; Boundary coercion: the graph's `:fn-stats-raw` receives the
          ;; fn-id off a query param as a STRING; binding it raw against
          ;; the uuid column threw `uuid = character varying` and the
          ;; history strip silently degraded to zeros (tutorial finding
          ;; 2026-08-26, "7d: 0 runs" over a populated list).
          fn-id (cond-> fn-id (string? fn-id) parse-uuid)
          row (jdbc/execute-one!
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


(defn org-summary
  "One-row rollup for `org` over the trailing `days` (default 7): total runs,
   failed, and the run-weighted average duration (ms). Counts + durations
   only — privacy-safe. Zeros when no rows / no pool."
  [pool org days]
  (let [r (when pool
            (jdbc/execute-one!
              pool
              [(str "SELECT coalesce(sum(count), 0) AS runs,"
                    " coalesce(sum(count) FILTER (WHERE status = 'failed'), 0) AS failed,"
                    " coalesce(sum(duration_ms_sum), 0) AS duration_ms_sum"
                    " FROM \"usage_stat\""
                    " WHERE org_id = ?"
                    " AND bucket_start >= now() - make_interval(days => ?)")
               (or org "public") (int (or days 7))]
              {:builder-fn rs/as-unqualified-lower-maps}))
        runs (long (or (:runs r) 0))
        dur (long (or (:duration_ms_sum r) 0))]
    {:runs runs
     :failed (long (or (:failed r) 0))
     :avg-ms (if (pos? runs) (quot dur runs) 0)}))


(defn- with-runs-pct
  "Attach `:runs-pct` (0-100, share of the collection max) to each row —
   boundary shaping for the Stats panel's CSS bars, so the graph needs no
   cross-row max/percent arithmetic."
  [rows]
  (let [mx (transduce (map :runs) max 0 rows)]
    (mapv #(assoc % :runs-pct (if (pos? mx)
                                (long (quot (* 100 (long (:runs %))) mx))
                                0))
          rows)))


(defn org-daily
  "Per-DAY series for `org` over the trailing `days` (default 7), oldest
   first: `[{:day \"YYYY-MM-DD\" :runs :failed :avg-ms :runs-pct} …]`. Days
   with no runs are omitted (the panel renders the gap). Counts + durations
   only; `:runs-pct` is the share of the window's busiest day (CSS bars)."
  [pool org days]
  (when pool
    (with-runs-pct
      (mapv (fn [r]
              (let [runs (long (or (:runs r) 0))
                    dur (long (or (:duration_ms_sum r) 0))]
                {:day (some-> (:day r) str)
                 :runs runs
                 :failed (long (or (:failed r) 0))
                 :avg-ms (if (pos? runs) (quot dur runs) 0)}))
            (jdbc/execute!
              pool
              [(str "SELECT to_char(date_trunc('day', bucket_start), 'YYYY-MM-DD') AS day,"
                    " coalesce(sum(count), 0) AS runs,"
                    " coalesce(sum(count) FILTER (WHERE status = 'failed'), 0) AS failed,"
                    " coalesce(sum(duration_ms_sum), 0) AS duration_ms_sum"
                    " FROM \"usage_stat\""
                    " WHERE org_id = ?"
                    " AND bucket_start >= now() - make_interval(days => ?)"
                    " GROUP BY 1 ORDER BY 1 ASC")
               (or org "public") (int (or days 7))]
              {:builder-fn rs/as-unqualified-lower-maps})))))


(defn org-fn-stats-named
  "Like `org-stats` but joins the fn's display name and shapes `:avg-ms`, for
   the editor's top-fns table: `[{:fn-id :fn-name :runs :failed :avg-ms} …]`,
   busiest first, capped at `limit`. A since-deleted fn falls back to its id."
  [pool org days limit]
  (when pool
    (with-runs-pct
      (mapv (fn [r]
              (let [runs (long (or (:runs r) 0))
                    dur (long (or (:duration_ms_sum r) 0))]
                {:fn-id (:fn_id r)
                 :fn-name (or (:fn_name r) (some-> (:fn_id r) str))
                 :runs runs
                 :failed (long (or (:failed r) 0))
                 :avg-ms (if (pos? runs) (quot dur runs) 0)}))
            (jdbc/execute!
              pool
              [(str "SELECT s.fn_id,"
                    " f.name AS fn_name,"
                    " coalesce(sum(s.count), 0) AS runs,"
                    " coalesce(sum(s.count) FILTER (WHERE s.status = 'failed'), 0) AS failed,"
                    " coalesce(sum(s.duration_ms_sum), 0) AS duration_ms_sum"
                    " FROM \"usage_stat\" s"
                    " LEFT JOIN \"fn\" f ON f.id = s.fn_id"
                    " WHERE s.org_id = ?"
                    " AND s.bucket_start >= now() - make_interval(days => ?)"
                    " GROUP BY s.fn_id, f.name ORDER BY runs DESC LIMIT ?")
               (or org "public") (int (or days 7)) (int (or limit 20))]
              {:builder-fn rs/as-unqualified-lower-maps})))))


(defn org-all-stats
  "Per-ORG rollup over the trailing `days` — `[{:org :runs :failed :avg-ms
   :runs-pct} …]`, busiest first, capped at `limit`. The OPERATOR's
   cross-org view (counts + durations only; no private data). Callers are
   responsible for restricting it to the platform context — see the
   `:usage-all-org-stats` base-fn."
  [pool days limit]
  (when pool
    (with-runs-pct
      (mapv (fn [r]
              (let [runs (long (or (:runs r) 0))
                    dur (long (or (:duration_ms_sum r) 0))]
                {:org (:org_id r)
                 :runs runs
                 :failed (long (or (:failed r) 0))
                 :avg-ms (if (pos? runs) (quot dur runs) 0)}))
            (jdbc/execute!
              pool
              [(str "SELECT org_id,"
                    " coalesce(sum(count), 0) AS runs,"
                    " coalesce(sum(count) FILTER (WHERE status = 'failed'), 0) AS failed,"
                    " coalesce(sum(duration_ms_sum), 0) AS duration_ms_sum"
                    " FROM \"usage_stat\""
                    " WHERE bucket_start >= now() - make_interval(days => ?)"
                    " GROUP BY org_id ORDER BY runs DESC LIMIT ?")
               (int (or days 7)) (int (or limit 20))]
              {:builder-fn rs/as-unqualified-lower-maps})))))


(defn org-totals
  "Per-ORG totals over the trailing `minutes` — `[{:org :runs :failed} …]`,
   busiest first. Feeds the built-in error-spike alerter: one row per org that
   ran anything in the window, counts only (no private data)."
  [pool minutes]
  (when pool
    (mapv (fn [r]
            {:org (:org_id r)
             :runs (long (:runs r))
             :failed (long (:failed r))})
          (jdbc/execute!
            pool
            [(str "SELECT org_id,"
                  " coalesce(sum(count), 0) AS runs,"
                  " coalesce(sum(count) FILTER (WHERE status = 'failed'), 0) AS failed"
                  " FROM \"usage_stat\""
                  " WHERE bucket_start >= now() - make_interval(mins => ?)"
                  " GROUP BY org_id ORDER BY runs DESC")
             (int (or minutes 60))]
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
