(ns graphden.storage.sql.pg
  "HoneySQL → next.jdbc bridge for application-level Postgres access
   against graphden's own pooled datasource.

   Reached from the `storage/pg` base-fns; each entry takes the
   executor ctx and a HoneySQL map and returns rows (`pg-query`) or
   the affected row count (`pg-execute`).

   Distinct from `web/sql/impls.clj` (per-call JDBC + creds for
   arbitrary external databases): these helpers reuse the SAME
   HikariCP pool that backs graphden's own storage so we don't open
   a fresh connection per call."
  (:require
    [honey.sql :as honey]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(def ^:dynamic *tx-connection*
  "Per-transaction `java.sql.Connection`, bound by `pg-tx` around the
   body callable. When non-nil, `pg-query` / `pg-execute` route their
   work through this connection so the whole body sees a single
   atomic view. When nil (the default) each call acquires its own
   connection from the pool.

   This is the dynamic-var pattern next.jdbc / clojure.java.jdbc use
   for transaction propagation — graphden can't thread ctx through
   the executor's HOF-callable invocation (ctx is closure-captured
   at compile time), so the var is the cleanest channel."
  nil)


(defn- pg-target
  "Return what next.jdbc should execute against:
   - the current transaction's `Connection` if `pg-tx` set one,
   - otherwise the `HikariDataSource` pulled from the executor ctx.

   ctx → :storage → unwrap the decorator chain to the backend holding the
       pool: `VersionedStorage` via `:base-storage`, `OrgScopedStorage` via
       `:base`, and any further decorator the same way → `:pool`.

   Raw SQL runs against the pool directly; under tenancy the pool carries RLS
   (`graphden.current_org`, wired by the addon's datasource-wrap), so org
   scoping is still enforced at the DB level even though we bypass the
   app-level `OrgScopedStorage` filter here.

   Throws if no storage / no pool is wired so the caller gets an
   actionable error instead of NPE deep inside next.jdbc."
  [ctx]
  (or *tx-connection*
      (let [storage (or (:storage ctx)
                        (throw (ex-info "storage.pg: no storage on executor context"
                                        {:type :storage-pg/no-storage})))
            ;; Walk :base-storage (VersionedStorage) / :base (OrgScopedStorage)
            ;; until a backend with a :pool is found — handles arbitrary
            ;; decorator nesting (e.g. Versioned over OrgScoped over Postgres).
            pool    (loop [s storage]
                      (cond
                        (:pool s)         (:pool s)
                        (:base-storage s) (recur (:base-storage s))
                        (:base s)         (recur (:base s))
                        :else             nil))]
        (when-not pool
          (throw (ex-info "storage.pg: storage has no :pool (datasource)"
                          {:type :storage-pg/no-datasource
                           :storage-class (some-> storage class .getName)})))
        pool)))


(defn- format-hsql
  "Turn a HoneySQL map into `[sql & params]` for next.jdbc. Validates
   the shape up-front (must be a map) and wraps any honeysql parse
   error with our `:storage-pg/invalid-honeysql` tag so callers can
   distinguish 'you wrote a bad HoneySQL map' from 'execution failed
   at the database'."
  [hsql]
  (when-not (map? hsql)
    (throw (ex-info "storage.pg: hsql must be a HoneySQL map"
                    {:type :storage-pg/invalid-honeysql
                     :hsql hsql
                     :hsql-class (some-> hsql class .getName)})))
  (try
    (honey/format hsql)
    (catch IllegalArgumentException e
      (throw (ex-info (str "storage.pg: invalid HoneySQL map: " (Throwable/.getMessage e))
                      {:type :storage-pg/invalid-honeysql
                       :hsql hsql}
                      e)))))


(defn pg-query
  "Run a SELECT-shape HoneySQL map against graphden's datasource.
   Returns a vector of rows; each row is `{:column value}` with
   unqualified, lower-snake-case column keys (next.jdbc default).

   Inside a `pg-tx` body the query runs on the transaction's
   connection; otherwise a fresh pool connection is used.

   The vector is realised eagerly before returning, so the lazy
   result-set never outlives the connection scope (next.jdbc closes
   the connection on `execute!` return)."
  [ctx hsql]
  (let [target (pg-target ctx)
        stmt   (format-hsql hsql)]
    (vec (jdbc/execute! target stmt {:builder-fn rs/as-unqualified-maps}))))


(defn pg-execute
  "Run a mutation-shape HoneySQL map (INSERT / UPDATE / DELETE / DDL)
   against graphden's datasource. Returns the affected row count as a
   long: 0 for DDL, the `next.jdbc/update-count` for DML.

   Inside a `pg-tx` body the mutation runs on the transaction's
   connection; otherwise a fresh pool connection is used."
  [ctx hsql]
  (let [target (pg-target ctx)
        stmt   (format-hsql hsql)
        result (jdbc/execute-one! target stmt {:return-keys false})]
    (long (or (:next.jdbc/update-count result) 0))))


(defn pg-tx
  "Run `body-fn` inside a JDBC transaction. Opens a connection from
   graphden's pool, binds it to `*tx-connection*` for the duration of
   the body, commits on normal return or rolls back on throw, and
   returns whatever `body-fn` returned.

   `body-fn` is a 0-arg callable — the executor's HOF-wrap delivers
   the user's fn-graph in that shape. Inside the body, all
   `pg-query` / `pg-execute` calls automatically route through the
   bound connection because of `*tx-connection*`.

   Nested `pg-tx` is allowed and uses the SAME connection — JDBC
   doesn't support true nested transactions, so we just reuse the
   outer one (semantically: the inner block's writes commit/abort
   with the outer)."
  [ctx body-fn]
  (if *tx-connection*
    ;; Already inside a transaction — reuse it. No new BEGIN/COMMIT.
    (body-fn)
    (let [pool (pg-target ctx)]
      (jdbc/transact pool
                     (fn [conn]
                       (binding [*tx-connection* conn]
                         (body-fn)))))))
