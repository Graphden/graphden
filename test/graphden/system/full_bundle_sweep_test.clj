(ns ^:integration graphden.system.full-bundle-sweep-test
  "The ONE place the full production-superset package bundle —
   core + storage + web + app + tenancy-admin — is type-check SWEPT
   (allowlist gate ON) and eagerly COMPILED in-JVM.

   Exists because of two audit-2 findings (2026-07-23):

   - the `storage` package was absent from every unit/perf bundle, so
     its fn-defs reached a sweep only in the docker integration
     stack;
   - `tenancy-admin` compiles only when the addon is wired, so its 16
     handler fn-defs hit the retired lambda-params guess's hard error
     at the GATE instead of at the unit tier.

   A defect this test catches (unknown type name, sweep failure
   outside the allowlist, ambiguous lambda-params, port collision)
   would otherwise surface ~40 minutes later in the serialized
   landing gate — or never, for code only a real deployment wires.

   Deliberately INLINE bootstrap (not the golden clone): the golden
   is built without `storage`/`tenancy-admin`, and this NS is the
   only consumer of the superset — a cached snapshot would amortize
   nothing. ~20 s, integration-tagged."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(deftest full-superset-bundle-sweeps-and-compiles
  (let [storage (setup/create-test-storage)]
    (try
      ;; Sweep ON, allowlist gate ON — an un-allowlisted type-check
      ;; failure anywhere in the superset throws here.
      (pkg-sync/bootstrap-from-packages! storage
                                         ["core" "storage" "web" "app"
                                          "tenancy-admin"]
                                         {:skip-type-check? false})
      (let [ctx (exec/create-context {:storage storage})]
        ;; Eager compile of EVERY fn — the production
        ;; `:exec/compiled-registry` path. Ambiguous lambda-params,
        ;; broken HOF wraps, and compile-time-value failures throw.
        (cr/rebuild! ctx)
        (is true "full superset bundle swept + compiled"))
      (finally (sp/close storage)))))
