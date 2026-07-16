(ns kaocha.plugin.shared-container
  "Kaocha plugin for managing the shared PostgreSQL test container.

   This plugin starts a single PostgreSQL container before all tests run
   and stops it after all tests complete. This replaces per-namespace
   container fixtures with a single shared container, dramatically
   reducing test startup time.

   Registered as :kaocha.plugin/shared-container in tests.edn"
  {:clj-kondo/config '{:linters {:unresolved-symbol {:level :off}}}}
  (:require
    [graphden.test-infra.shared-bootstrap :as sb]
    [graphden.test-infra.shared-container :as sc]
    [kaocha.plugin :refer [defplugin]]))


(defplugin kaocha.plugin/shared-container
  "Manages shared PostgreSQL container lifecycle for tests."

  ;; Called before the entire test run starts
  (pre-run [config]
           ;; Start container eagerly before any tests run
           ;; This ensures container is ready when parallel namespaces start
           (sc/get-container)
           ;; Eagerly bootstrap the golden DB for the default
           ;; `["core" "web" "app"]` bundle off the test threads.
           ;; Sibling tests then only pay the ~100 ms template
           ;; clone + ~1 s ctx rebuild — the ~14 s sync stays
           ;; outside the parallel critical path. `ensure-golden!`
           ;; is JVM-wide idempotent, so the first per-NS call
           ;; that lands during a test still hits the cache.
           (sb/ensure-golden! ["core" "web" "app"])
           config)

  ;; Called after the entire test run completes
  (post-run [result]
            ;; Close any per-NS storage a namespace forgot to (finding H
            ;; backstop) FIRST — releasing its pool's connections before
            ;; we DROP its database. Then drop per-NS DBs (their template
            ;; ref must be gone before the template DB itself drops), the
            ;; golden templates, and finally shut the container down.
            ;; Order matters — DROP needs a live cluster.
            (sc/close-all-storages!)
            (sc/drop-all-ns-databases!)
            (sb/drop-all-golden-databases!)
            (sc/stop-container!)
            result))
