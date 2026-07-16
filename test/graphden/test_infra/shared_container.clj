(ns graphden.test-infra.shared-container
  "Global shared PostgreSQL container for all tests, plus per-namespace
   logical-DB isolation so multiple test namespaces can run in parallel
   against the same container without trampling each other's schemas.

   Two tiers of sharing:

   1. **Container** — ONE `PostgreSQLContainer` is started lazily on
      first access and stopped after all tests complete. Replaces the
      old per-namespace-container pattern (38+ container startups).

   2. **Database (per-namespace)** — every test namespace that installs
      `shared-container-fixture` gets its OWN logical database on the
      shared cluster. `clean-database-fast!`'s `DROP SCHEMA public
      CASCADE` then operates inside that NS-owned DB, so parallel
      namespaces under `:kaocha/parallelism` can run concurrently
      without each other's truncates clobbering live tables.

   Usage in tests:
     (require '[graphden.test-infra.shared-container :as sc])
     (sc/get-container)            ; PG container (starts if needed)
     (sc/get-config)               ; per-NS DB config (cluster default
                                   ;   when no NS fixture is active)
     (use-fixtures :once (sc/shared-container-fixture #'*container*))

   The container is stopped via kaocha's `:kaocha.plugin/shared-container`
   `post-run` hook; the plugin also drops every per-NS DB created during
   the run via `drop-all-ns-databases!`."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp]
    [graphden.util.counters :as counters]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      SQLException)
    (org.testcontainers.containers
      PostgreSQLContainer)))


;; =============================================================================
;; Global State
;; =============================================================================

(def ^:private container-atom
  "Atom holding the shared PostgreSQL container.
   nil = not started, PostgreSQLContainer = running."
  (atom nil))


(def ^:private container-lock
  "Lock for thread-safe container initialization."
  (Object.))


(def default-postgres-image
  "Default PostgreSQL Docker image for tests."
  "postgres:16-alpine")


(def ^:private ns-databases-atom
  "In-memory record of per-namespace logical databases this JVM has
   created on the shared cluster. Drained by `drop-all-ns-databases!`
   at the end of the test run. The atom doubles as an idempotency
   guard: re-entering `ensure-ns-database!` for the same NS skips the
   `CREATE DATABASE` round-trip."
  (atom #{}))


(def ^:dynamic *ns-db-config*
  "Per-namespace JDBC config override. Set by `shared-container-fixture`
   so every call to `get-config` from within a fixture'd namespace
   returns that NS's dedicated DB on the shared cluster. nil = no
   fixture active → fall back to the cluster's bootstrap DB."
  nil)


(defn- external-pg-config
  "Reads the `GRAPHDEN_TEST_PG_*` env vars when `_PG_JDBC_URL` is set;
   nil otherwise. Set in CI / multi-process `bb test-parallel` to point
   all workers at a single shared cluster (e.g. the local
   docker-compose `graphden-postgres` on port 5435) instead of each
   spinning up its own testcontainer — saves ~3 s × (workers − 1) of
   container-startup. The cluster's bootstrap DB must support
   superuser `CREATE DATABASE` / `DROP DATABASE` since per-NS DBs are
   created and dropped from it; the dev compose's `graphden` user is
   superuser by default."
  []
  (when-let [url (System/getenv "GRAPHDEN_TEST_PG_JDBC_URL")]
    {:jdbc-url url
     :username (or (System/getenv "GRAPHDEN_TEST_PG_USERNAME") "graphden")
     :password (or (System/getenv "GRAPHDEN_TEST_PG_PASSWORD") "graphden")
     :pool-size 2}))


;; =============================================================================
;; Container Lifecycle
;; =============================================================================

(defn- create-container
  "Creates and starts a new PostgreSQL container.
   Configured for high concurrency (500 connections) to support parallel tests."
  []
  (log/info "Starting shared PostgreSQL test container...")
  ;; The whole point of this namespace is that this happens ONCE, replacing the
  ;; old per-NS pattern's 38+ startups. "Once" is a claim the suite could not
  ;; check until now — and a second boot costs ~3 s and, worse, means some
  ;; caller bypassed `get-container`'s double-checked lock.
  (counters/count! :fixture/container-boot)
  (let [start-time (System/currentTimeMillis)
        container (doto (PostgreSQLContainer. ^String default-postgres-image)
                    (PostgreSQLContainer/.withStartupAttempts 3)
                    ;; `pg_stat_statements` is how a scenario counts its own SQL
                    ;; without a line of instrumentation in the storage layer —
                    ;; and it counts NORMALISED statements, so an N+1 surfaces as
                    ;; one row reading calls=200 rather than 200 rows to eyeball.
                    ;; It takes shared memory, so it must be preloaded at startup
                    ;; and cannot be switched on per-scenario. Its cost lands on
                    ;; query planning (~1%), against `graphden.perf.sql`'s ability
                    ;; to gate an exact query count on any machine at any load.
                    (PostgreSQLContainer/.withCommand
                      (str "postgres -c max_connections=500"
                           " -c shared_preload_libraries=pg_stat_statements"))
                    (PostgreSQLContainer/.start))]
    (when-not (PostgreSQLContainer/.isRunning container)
      (throw (ex-info "Failed to start shared PostgreSQL test container"
                      {:image default-postgres-image})))
    (log/info "Shared PostgreSQL container started in"
              (- (System/currentTimeMillis) start-time) "ms")
    container))


(defn get-container
  "Returns the shared PostgreSQL container, starting it if necessary.
   Thread-safe: only one container will be created even with concurrent
   calls. Returns nil in external-PG mode (GRAPHDEN_TEST_PG_JDBC_URL
   set) — we don't manage the cluster's lifecycle there, so callers
   must read connection info via `get-config` / `base-cluster-config`
   instead of poking the container directly."
  []
  (when-not (external-pg-config)
    (or @container-atom
        (locking container-lock
          (or @container-atom
              (let [container (create-container)]
                (reset! container-atom container)
                container))))))


(def ^:private created-storages
  "Every per-NS test storage the bootstrap helpers hand out, tracked so
   the post-run hook can close any a namespace forgot to. An unclosed
   storage keeps its HikariCP pool alive for the whole JVM — but at the
   test pool-size of 2 that is only ~few-MB total across the suite (a
   before/after measurement put it inside GC noise), NOT the ~177 MB of
   `finding H`, which is the fixed working set of a fully-loaded test JVM
   (compiled packages + registries + golden bootstrap), not a leak. This
   backstop is cheap insurance so the footprint can't grow if the pool
   size is raised or many leaking namespaces are added later; most `:once`
   fixtures already close their own storage in a `finally`."
  (atom #{}))


(defn register-storage!
  "Record `storage` for suite-end close. Returns `storage` so it can wrap
   a create call inline. Idempotent (set semantics)."
  [storage]
  (swap! created-storages conj storage)
  storage)


(defn close-all-storages!
  "Close every registered storage, then forget them. Defensive: HikariCP
   close is idempotent, so a namespace that already closed its own storage
   (the normal path) just makes this a no-op for that handle."
  []
  (let [handles @created-storages]
    (when (seq handles)
      (log/info "Closing" (count handles) "tracked test storages"))
    (doseq [s handles]
      (try (sp/close s) (catch Exception e (log/debug e "close-all-storages!")))))
  (reset! created-storages #{}))


(defn stop-container!
  "Stops the shared container if running.
   Called by kaocha hooks plugin after all tests complete.
   No-op in external-PG mode — we don't own that lifecycle."
  []
  (when-let [container @container-atom]
    (log/info "Stopping shared PostgreSQL test container...")
    (PostgreSQLContainer/.stop container)
    (reset! container-atom nil)
    (log/info "Shared PostgreSQL container stopped")))


(defn running?
  "Returns true if the shared container is running."
  []
  (when-let [container @container-atom]
    (PostgreSQLContainer/.isRunning container)))


;; =============================================================================
;; Configuration
;; =============================================================================

(defn base-cluster-config
  "JDBC config for the cluster's bootstrap database. Used as the
   connection target for `CREATE DATABASE` / `DROP DATABASE` admin
   work. Source depends on mode:
     - external-PG mode (env `GRAPHDEN_TEST_PG_JDBC_URL` set) → the
       env vars, no testcontainer involved.
     - default → the testcontainers PostgreSQL container's
       `getJdbcUrl` / `getUsername` / `getPassword`."
  []
  (or (external-pg-config)
      (let [container (get-container)]
        {:jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
         :username (PostgreSQLContainer/.getUsername container)
         :password (PostgreSQLContainer/.getPassword container)
         :pool-size 2})))


(defn jdbc-url-with-database
  "Replace the database segment of a `jdbc:postgresql://<host>:<port>/<db>?…`
   URL with `db-name`, leaving host/port/query-params intact."
  [jdbc-url db-name]
  (str/replace jdbc-url
               #"^(jdbc:postgresql://[^/]+/)[^?]*"
               (str "$1" db-name)))


(defn get-config
  "Returns connection configuration. When a per-namespace fixture is
   active (`*ns-db-config*` bound) — returns that NS's dedicated DB.
   Otherwise — falls back to the cluster's bootstrap DB. Both shapes
   are `{:jdbc-url :username :password :pool-size}`."
  []
  (or *ns-db-config* (base-cluster-config)))


;; =============================================================================
;; Per-namespace logical-database isolation
;; =============================================================================

(defn sanitize-db-name
  "Build a Postgres-safe DB name from a namespace symbol/string. The
   identifier limit is 63 chars; we use `test_<sanitized>_<6hex>` and
   truncate `<sanitized>` to fit."
  [ns-ident]
  (let [raw (-> ns-ident str
                str/lower-case
                (str/replace #"[^a-z0-9]" "_"))
        prefix "test_"
        ;; 6 hex chars + leading underscore = 7. Total budget 63.
        suffix-len 7
        max-name (- 63 (count prefix) suffix-len)
        truncated (if (> (count raw) max-name)
                    (subs raw 0 max-name)
                    raw)
        ;; Hash drawn from the FULL name so two namespaces with the same
        ;; truncated prefix still collision-resist.
        h (-> (str ns-ident) .hashCode (Math/abs)
              (mod 0xffffff)
              (->> (format "%06x")))]
    (str prefix truncated "_" h)))


(defn ensure-ns-database!
  "Create the per-namespace logical DB on the shared cluster if it
   doesn't already exist. Returns the full `{:jdbc-url …}` config map
   pointing at that DB. Idempotent: re-entering for the same NS in
   this JVM is a cheap atom lookup. `CREATE DATABASE` races are
   harmless — Postgres rejects duplicates and we catch + log.

   `template-db` (optional) — when set, the new DB is created via
   `CREATE DATABASE … TEMPLATE \"<template-db>\"`. Postgres clones the
   template's filesystem image in ~100 ms, so a heavy bootstrap
   (schema + base-fns + ~700 fn-defs) needs to run only once on the
   template DB; sibling NSes inherit the cloned state and only pay
   `rebuild!` (~1 s w/ compile-all LRU). The template DB must have no
   active connections at clone time — `shared_bootstrap`'s
   `ensure-golden!` closes its storage before returning."
  ([ns-ident] (ensure-ns-database! ns-ident nil))
  ([ns-ident template-db]
   (let [db (sanitize-db-name ns-ident)
         cluster (base-cluster-config)]
     (when-not (contains? @ns-databases-atom db)
       ;; Counted apart on purpose. A TEMPLATE clone is the ~100 ms fast path; a
       ;; bare CREATE means this NS reached the cluster WITHOUT a golden, and
       ;; will therefore pay the ~14 s bootstrap itself. The two are one `if`
       ;; apart in the code and ~140× apart in cost, and no wall-clock report
       ;; distinguishes them from a slow host.
       (counters/count! (if template-db
                          :fixture/ns-db-clone
                          :fixture/ns-db-create-bare))
       (with-open [conn (jdbc/get-connection {:jdbcUrl (:jdbc-url cluster)
                                              :user (:username cluster)
                                              :password (:password cluster)})]
         (try
           (jdbc/execute! conn [(if template-db
                                  (str "CREATE DATABASE \"" db
                                       "\" TEMPLATE \"" template-db "\"")
                                  (str "CREATE DATABASE \"" db "\""))])
           (catch SQLException e
             ;; 42P04 = duplicate_database — fine, sibling beat us to it.
             (when-not (= "42P04" (SQLException/.getSQLState e))
               (log/warn e "CREATE DATABASE failed for" db)))))
       (swap! ns-databases-atom conj db))
     (update cluster :jdbc-url jdbc-url-with-database db))))


(defn- drop-one-ns-database!
  "Two-phase per-DB drop:
     1. Plain `DROP DATABASE` — succeeds when nothing's connected.
        If something IS connected, PG returns SQLSTATE 55006
        (`object_in_use`); catch + log.
     2. Fall back to `DROP DATABASE … WITH (FORCE)` (PG 13+), which
        sends SIGTERM to leftover backends.

   The two-phase pattern matters because phase-2's FORCE raises
   `SQLSTATE 57P01` (`admin_shutdown`) inside any backend that's
   still actively executing a query — under the previous one-shot
   FORCE call, in-flight test reads would die mid-query if the
   post-run hook fired before a parallel NS finished closing its
   pool (the `process-delete-entity-fn-binding-test` flake). Phase
   1 gives the cleanup-of-the-cleanup a window; phase 2 is the
   guarantee."
  [conn db]
  (try
    (jdbc/execute! conn [(str "DROP DATABASE IF EXISTS \"" db "\"")])
    (catch SQLException e
      (if (= "55006" (SQLException/.getSQLState e))
        (do
          (log/warn "DB" db "still in use at drop time — falling back to WITH (FORCE)")
          (try
            (jdbc/execute! conn
                           [(str "DROP DATABASE IF EXISTS \"" db "\" WITH (FORCE)")])
            (catch Exception e2
              (log/warn e2 "WITH (FORCE) drop also failed for" db))))
        (log/warn e "Failed to drop test DB" db)))))


(defn drop-all-ns-databases!
  "Best-effort teardown: drop every per-NS DB created during this
   run. See `drop-one-ns-database!` for the two-phase semantics that
   avoid killing in-flight test queries. Called from the kaocha
   post-run hook BEFORE container shutdown."
  []
  (let [dbs @ns-databases-atom]
    (when (seq dbs)
      (let [{:keys [jdbc-url username password]} (base-cluster-config)]
        (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                               :user username
                                               :password password})]
          (doseq [db dbs]
            (drop-one-ns-database! conn db))))
      (reset! ns-databases-atom #{}))))


;; =============================================================================
;; Fixtures
;; =============================================================================

(defn shared-container-fixture
  "Creates a :once fixture that (a) wires the shared container into
   `container-var` and (b) gives this namespace its own logical DB on
   the cluster, exposed via `*ns-db-config*`. Together that lets
   parallel namespaces (`:kaocha/parallelism > 1`) run concurrently
   without truncate races.

   The NS name is captured at fixture-construction time — i.e. when
   `use-fixtures` runs at namespace-load. At that moment `*ns*` is the
   test namespace, which is exactly the binding we want for the DB
   name (kaocha's worker thread that later invokes the fixture has a
   different `*ns*`).

   Example:
     (def ^:dynamic *container* nil)
     (use-fixtures :once (shared-container-fixture #'*container*))"
  [container-var]
  (let [test-ns (ns-name *ns*)]
    (fn [f]
      (let [_container (get-container)
            ns-cfg (ensure-ns-database! test-ns)]
        (with-bindings {container-var (get-container)
                        #'*ns-db-config* ns-cfg}
          (f))))))


;; =============================================================================
;; JVM Shutdown Hook (fallback cleanup)
;; =============================================================================

(Runtime/.addShutdownHook
  (Runtime/getRuntime)
  (Thread.
    ^Runnable
    (fn []
      (when (running?)
        (log/info "JVM shutdown: stopping shared PostgreSQL container")
        (try (drop-all-ns-databases!)
             (catch Exception _ nil))
        (stop-container!)))))
