(ns ^:integration graphden.system.full-bundle-sweep-test
  "The ONE place the full PUBLIC package bundle — core + storage + web + app —
   is type-check SWEPT (allowlist gate ON) and eagerly COMPILED in-JVM.

   Exists because of an audit-2 finding (2026-07-23): the `storage` package was
   absent from every unit/perf bundle, so its fn-defs reached a sweep only in
   the docker integration stack.

   (`tenancy-admin` moved to the private graphden-tenancy repo in the open-core
   split; its own suite there sweeps it with the allowlist gate on.)

   A defect this test catches (unknown type name, sweep failure
   outside the allowlist, ambiguous lambda-params, port collision)
   would otherwise surface ~40 minutes later in the serialized
   landing gate — or never, for code only a real deployment wires.

   Deliberately INLINE bootstrap (not the golden clone): the golden
   is built without `storage`, and this NS is the only consumer of the
   superset — a cached snapshot would amortize nothing. ~20 s, integration-tagged."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.config :as sp-config]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(deftest full-superset-bundle-sweeps-and-compiles
  (let [storage (setup/create-test-storage)]
    (try
      ;; Sweep ON, allowlist gate ON — an un-allowlisted type-check
      ;; failure anywhere in the superset throws here.
      ;; The FULL prod boot set — including the OPTIONAL packages
      ;; (registry, mcp) and app-base. 2026-08-05 lesson: they were
      ;; absent here, so a cross-package record-shape break (mcp's
      ;; create-branch parsed vs the widened :_create-branch-data
      ;; shape) passed this test and killed the CANDIDATE image boot
      ;; in the landing gate's e2e phase instead.
      ;; `*max-batch-size*` shrunk to 100: the bulk sync MUST bind its
      ;; own ceiling (sync-fn-entities-from-packages!) — 2026-08-15 the
      ;; cloud graph outgrew the global 10000 cap and every FRESH-DB
      ;; boot died :batch-error/batch-too-large while incremental
      ;; deployments kept working. This binding makes the superset
      ;; bootstrap overflow any un-ceilinged batch path immediately.
      (binding [sp-config/*max-batch-size* 100]
        (pkg-sync/bootstrap-from-packages! storage
                                           ["core" "storage" "web" "app-base"
                                            "app" "registry" "mcp"]
                                           {:skip-type-check? false}))
      (let [ctx (exec/create-context {:storage storage})]
        ;; Eager compile of EVERY fn — the production
        ;; `:exec/compiled-registry` path. Ambiguous lambda-params,
        ;; broken HOF wraps, and compile-time-value failures throw.
        (cr/rebuild! ctx)
        (is true "full superset bundle swept + compiled"))
      (finally (sp/close storage)))))
