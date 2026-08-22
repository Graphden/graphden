(ns ^:perf graphden.perf.scenarios-test
  "How much SQL does each API-level operation issue?

   These are not correctness tests — `crud/entities-graph-test` already covers
   what these handlers return. They record what each one COSTS, in the only unit
   that survives leaving this machine: round trips to Postgres.

   Every one of them is an endpoint whose cost has already regressed at least
   once. `/api/graph/entities` was 95 ms / 4.5 MB before it was scoped;
   `list-secrets` scanned the graph until it learned to filter in SQL (~9x); a
   `:fn` create spent 477 ms of its 918 ms re-reading the whole graph to
   recompile one closure. In each case the query count moved first, and nobody
   was watching it — the wall-clock reading that finally exposed the problem
   arrived months later, attached to a complaint.

   For a while this file named three such regressions and measured two: the
   `list-secrets` scenario below was added by the 2026-08-22 test audit, which
   noticed that the one endpoint whose fix is quantified in the docstring
   (~9x) had no reading of its own. The layout scenario joins it because
   `POST /api/graph/layout` is what every card expansion in the editor costs,
   and nothing was watching that either.

   Kept out of `:unit` and `:integration` by `^:perf`: they exist to write
   numbers into `perf/runs/perf.edn`, not to assert behaviour, and a failing
   budget should send you to `bb perf`, not to a red test suite."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.perf.calibrate :as cal]
    [graphden.perf.sql :as psql]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *graph* nil)


(declare datasource-of)


(defn- graph-fixture
  [t]
  (exec/with-clean-registry
    #(let [graph (setup/bootstrap-crud-graph-from-golden!)]
       (try
         (binding [*graph* graph]
           ;; Calibrate once, here, against the same pool the scenarios use — a
           ;; reference measured on a different connection or at a different
           ;; moment would normalise against a machine this run never saw.
           (cal/record! (datasource-of (:storage graph)))
           (t))
         (finally (sp/close (:storage graph)))))))


(use-fixtures :once
  (setup/create-container-fixture)
  graph-fixture)


(defn- datasource-of
  "The HikariDataSource under whatever decorators wrap `storage`.

   `PostgresStorage` holds it as `:pool`; `VersionedStorage` names its inner
   handle `:base-storage` and tenancy's `OrgScopedStorage` names it `:base`
   (see `compile-runtime/storage-root`, which walks the same chain).

   Throws rather than returning nil, because `pg_stat_statements` is filtered by
   database oid: a reader pointed at the wrong database reports zero, every
   scenario looks free, and every budget passes. A silent zero here would make
   this whole file worthless while looking green."
  [storage]
  (loop [s storage]
    (cond
      (nil? s) (throw (ex-info "no connection pool under this storage"
                               {:type :perf/no-pool}))
      (:pool s) (:pool s)
      :else (recur (or (:base-storage s) (:base s) (:storage s))))))


(defn- record!
  [event f]
  (psql/record! event (datasource-of (:storage *graph*)) f))


(deftest ^:perf graph-entities-scopes-sql-cost
  (let [ds (datasource-of (:storage *graph*))]
    (psql/ensure-extension! ds))

  (testing "scope=tree — the sidebar's first paint"
    (let [{:keys [queries statements]}
          (record! :sql/graph-entities-tree
                   #(setup/via-graph *graph* :all-entities-handler
                                     {:request-method :get
                                      :uri "/api/graph/entities"
                                      :query-params {"scope" "tree"}}))]
      ;; No assertion on the number — `perf/budgets.edn` owns that, so the
      ;; budget lives in one reviewable place instead of being spread across
      ;; deftests. What IS asserted is that we measured anything at all: a
      ;; scenario silently reading zero would make every budget trivially pass,
      ;; which is the one failure mode this file cannot self-detect later.
      (is (pos? queries)
          "measured no SQL at all — pg_stat_statements is not seeing this DB")
      (is (seq statements)))))


(deftest ^:perf fn-create-sql-cost
  (testing "creating one :fn — the write path that used to re-read the graph"
    (let [{:keys [queries result]}
          (record! :sql/create-fn
                   #(setup/via-graph *graph* :process-create-entity
                                     {:uri "/api/entities/fn"
                                      :request-method :post
                                      :body (str "name=perf-probe-" (random-uuid))
                                      :headers {"content-type"
                                                "application/x-www-form-urlencoded"}}))]
      ;; Assert the operation SUCCEEDED before believing its cost. A scenario
      ;; that quietly 400s does no work, measures zero queries, and sails under
      ;; any budget — a perf suite that reports "free" for an endpoint that is
      ;; broken is worse than no perf suite. This is the only assertion here
      ;; that is about behaviour, and it earns its place.
      (is (= 200 (:status result))
          "the write must succeed, or its query count means nothing")
      (is (pos? queries)))))


(deftest ^:perf list-secrets-sql-cost
  (testing "GET /api/secrets — the endpoint that used to scan the graph"
    ;; The docstring's ~9x. Filtering moved into SQL; a change that walks the
    ;; fn graph again to find `:secret-leaf` descendants shows up here as a
    ;; query count proportional to the graph, long before anyone notices the
    ;; page got slow. Reading a graph with no secrets in it is fine for that:
    ;; a scan costs the same whether it finds anything or not, which is the
    ;; whole point of the regression.
    (let [{:keys [queries result]}
          (record! :sql/list-secrets
                   #(setup/via-graph *graph* :list-secrets-handler
                                     {:uri "/api/secrets"
                                      :request-method :get}))]
      (is (= 200 (:status result))
          "the read must succeed, or its query count means nothing")
      (is (pos? queries)))))


(deftest ^:perf graph-layout-sql-cost
  (testing "POST /api/graph/layout — what one card expansion costs"
    ;; Every expand click in the editor is this call, and steady-state it
    ;; costs ZERO round trips: the layout reads the graph the compile cache
    ;; already holds. That zero is the interesting number, not a measurement
    ;; failure — this is the scenario whose budget can be `:max 0`, so a
    ;; caching change that reverts to re-reading the graph per expansion is
    ;; caught by the FIRST query it issues rather than by a factor.
    ;;
    ;; Hence no `(pos? queries)` guard here, unlike its neighbours. The
    ;; "pg_stat_statements isn't seeing this DB" failure mode it exists to
    ;; catch is covered by the three scenarios above; adding it here would
    ;; make the file unable to express a legitimate zero.
    (let [root-id (get (:all-name->id *graph*) :web-server)
          {:keys [result]}
          (record! :sql/graph-layout
                   #(setup/via-graph *graph* :_layout-api-handler
                                     {:uri "/api/graph/layout"
                                      :request-method :post
                                      :body (str "{\"fn-id\":\"" root-id "\"}")
                                      :headers {"content-type" "application/json"}}))]
      (is (= 200 (:status result))
          "the layout must succeed, or its query count means nothing"))))
