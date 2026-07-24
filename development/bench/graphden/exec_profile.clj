(ns graphden.exec-profile
  "Ad-hoc executor hot-path micro-benchmark.

   Bootstraps a `[core]` graph against a throwaway PG container, syncs a
   `:map`-over-`:range` workload whose callback is a small composed chain (so ONE
   execute fires thousands of graph-node closures), then criterium quick-benches
   a fixed-size execute.

   Usage:  clj -M:dev:bench -m graphden.exec-profile

   Two claims were removed from this docstring rather than implemented: it named
   a `:profile` alias that does not exist in deps.edn (tools.deps ignores unknown
   aliases silently, so it ran anyway, just without whatever was intended), and
   it advertised alloc-profiling with a collapsed flame graph — there is no
   clj-async-profiler dependency in this project and no code here that profiles
   anything. A tool that documents an output it never produces sends whoever
   reads it looking for a file that was never written.

   Manual by design: prints, exits, stores nothing, compares against nothing. For
   the measurements that ARE kept and compared across runs see
   `docs/PERF_BUDGETS.md` — noting that the executor hot path is deliberately not
   instrumented there, because PERF_NOTES is explicit that this suite is
   CRUD/compile/HTTP-bound rather than execute-bound."
  (:require
    [criterium.core :as crit]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.interface :as exec]
    [graphden.packages.records :as records]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.packages.schema :as pkgs]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.core :as sys]
    [graphden.versioning.storage.core :as vs])
  (:import
    (org.testcontainers.containers
      PostgreSQLContainer)))


(defn- full-schema
  []
  (-> (mds/create-builder)
      (gds/extend-builder) (vts/extend-builder) (vds/extend-builder)
      (es/extend-builder) (svcs/extend-builder) (pkgs/extend-builder)
      (ds/build)))


(defn- versioned-storage
  [cfg]
  (let [storage (pg/create-storage cfg)]
    (sp/initialize storage (full-schema))
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (vs/wrap-with-versioning storage "main")))


;; Workload: map a composed callback over (range N). The callback is a
;; 2-node chain (add → mul) so each element drives several graph-node
;; closures — stressing the per-node path (arg-builders / volatile /
;; call-cache), not a single fat impl.
(def workload-fns
  [{:name :_bench-cb-add :parent :add :args {:nums [{:as :item} {:value 7}]}}
   {:name :_bench-cb :parent :mul :args {:nums [:_bench-cb-add {:value 3}]}}
   {:name :_bench-range :parent :range
    :args {:start {:value 0} :end {:as :n} :step {:value 1}}}
   {:name :bench-map-workload :parent :map
    :args {:func :_bench-cb :coll :_bench-range}}])


(defn- sync-and-invalidate!
  [ctx storage fn-defs]
  (fn-composition/sync-fns-to-storage! storage fn-defs)
  (let [ids (keep #(:id (first (sp/query-entities storage :fn {:name (name (:name %))})))
                  fn-defs)]
    (exec-ctx/invalidate-graph-cache! ctx ids)))


(defn -main
  [& _]
  (println "Starting throwaway PG container…")
  (let [container (doto (PostgreSQLContainer. "postgres:16.11-alpine")
                    (PostgreSQLContainer/.withStartupAttempts 3))]
    (try
      (PostgreSQLContainer/.start container)
      (let [cfg {:jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
                 :username (PostgreSQLContainer/.getUsername container)
                 :password (PostgreSQLContainer/.getPassword container)}
            storage (versioned-storage cfg)
            _ (sys/bootstrap-from-packages! storage ["core"] {:skip-type-check? true})
            ctx (exec/create-context {:storage storage})
            _ (cr/rebuild! ctx)]
        (println "Bootstrapped [core]; syncing workload…")
        (sync-and-invalidate! ctx storage workload-fns)
        (let [wl-id (:id (first (sp/query-entities storage :fn
                                                   {:name "bench-map-workload"})))
              run (fn [n] (doall (exec/execute ctx wl-id {:n n})))]
          (println "Sanity (n=5):" (run 5))
          (println "Warming up…")
          (dotimes [_ 30] (run 500))

          (println "\n=== criterium quick-bench: execute map over range 2000 ===")
          (crit/quick-bench (run 2000))
          (println "done.")))
      (finally
        (PostgreSQLContainer/.stop container)))))
