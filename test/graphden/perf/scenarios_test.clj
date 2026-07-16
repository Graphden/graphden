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
