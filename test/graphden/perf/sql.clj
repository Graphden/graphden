(ns graphden.perf.sql
  "Counts the SQL a scenario issues — measured at the database, not in our code.

   Why at the database
   ===================
   The alternative is instrumenting the storage layer, and there is nothing to
   instrument: `jdbc/execute!` is called from ~30 sites across
   `storage/postgres/{crud,graph,ddl,metadata,migration,introspection}.clj`,
   with no single seam. Wrapping the pool would catch connections, not queries.
   `pg_stat_statements` sees every statement by construction, needs no
   application change at all, and cannot drift out of sync with the code the way
   a hand-placed counter can.

   It also counts the RIGHT thing. Statements are normalised before counting, so
   an N+1 arrives pre-diagnosed as one row reading `calls=200` — you get the
   offending statement text, not 200 lines to read. `list-secrets` (~9x, fixed by
   filtering in SQL instead of scanning the graph) and `/api/secrets` being
   O(graph) are exactly this shape.

   Why a count and not a duration
   ==============================
   \"This endpoint issues 3 queries\" is 3 on a laptop and 3 at load average 75.
   `docs/PERF_NOTES.md` lists ~5-40 ms for the `/api/graph/entities` scopes —
   numbers that cannot be asserted anywhere, because the spread is the host's,
   not the code's. The query count underneath them is assertable exactly, and it
   is what actually regressed every time one of those endpoints got slow.

   Scenario counts are recorded through `graphden.util.counters`, the same
   mechanism the fixture counters use — so the kaocha perf plugin persists them
   and `perf/budgets.edn` gates them with no separate machinery."
  (:require
    [graphden.util.counters :as counters]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(defn ensure-extension!
  "Idempotent. The container preloads the library (see
   `shared-container/create-container`); this creates the VIEW in whichever
   database `ds` points at. Safe to call per scenario."
  [ds]
  (jdbc/execute! ds ["CREATE EXTENSION IF NOT EXISTS pg_stat_statements"])
  nil)


(defn- reset-stats!
  "Reset only THIS database's entries. `pg_stat_statements` is cluster-wide, and
   the suite runs namespaces in parallel against one cluster with a database
   each — a bare `pg_stat_statements_reset()` would erase a sibling namespace's
   in-flight measurement. The dbid filter is what makes this safe to use from a
   parallel suite at all."
  [ds]
  (jdbc/execute! ds ["SELECT pg_stat_statements_reset(0, (SELECT oid FROM pg_database WHERE datname = current_database()), 0)"])
  nil)


(defn- read-stats
  "This database's statements since the last reset, worst-first. The reader's own
   bookkeeping is excluded — `pg_stat_statements` records the query that reads
   `pg_stat_statements`, and counting our own measurement as the code's work
   would be a lie in the direction that makes us look bad."
  [ds]
  ;; `:builder-fn rs/as-unqualified-maps` matches `storage/sql/pg.clj` — without
  ;; it next.jdbc qualifies every key by its table (`:pg_stat_statements/calls`),
  ;; `(:calls r)` reads nil, and the sum below NPEs.
  (->> (jdbc/execute! ds ["SELECT calls, rows, query FROM pg_stat_statements
                           WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                             AND query NOT ILIKE '%pg_stat_statements%'
                           ORDER BY calls DESC"]
                      {:builder-fn rs/as-unqualified-maps})
       (mapv (fn [r] {:calls (:calls r) :rows (:rows r) :query (:query r)}))))


(defn measure
  "Run `f` once to warm, then again under measurement. Returns
   `{:result :queries :rows :statements}` for the SECOND call.

   `:queries` sums `calls` across normalised statements — the number of round
   trips the scenario actually made. `:statements` is the breakdown, so a
   failure report can name the statement that fired 200 times.

   Why the warm-up is not optional
   ===============================
   Without it the count is not reproducible, and a budget on an irreproducible
   count is a flake with a rationale. Measured here: `scope=tree` read 3 queries
   in one run and 1 in the next, purely because kaocha randomises test order and
   a scenario that happened to run second found the compiled registry, graph
   cache and type-alias registry already warm.

   Steady state is also the honest target. Cold-cache cost is paid once per
   process; the number that decides whether an endpoint is expensive is what it
   costs on the millionth request, and that is what this measures.

   `ds` must reach the same database the scenario writes through, or the dbid
   filter reports zero and the scenario looks free."
  [ds f]
  (ensure-extension! ds)
  (f)
  (reset-stats! ds)
  (let [result (f)
        statements (read-stats ds)]
    {:result result
     :queries (reduce + 0 (map :calls statements))
     :rows (reduce + 0 (map :rows statements))
     :statements statements}))


(defn record!
  "Measure `f` and record its query count under `event` for `perf/budgets.edn`.

   Returns the full measurement so a caller can assert on the breakdown too.
   Records the COUNT, not the duration: a budget of `{:max 3}` on a scenario
   means three round trips, and it means that identically on every machine that
   will ever run it."
  [event ds f]
  (let [{:keys [queries] :as m} (measure ds f)]
    (counters/count! event queries)
    m))
