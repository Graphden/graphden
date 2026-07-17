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
    [kaocha.plugin :refer [defplugin]]
    [kaocha.testable :as testable]))


(defn- parallel-run?
  "Will this run put more than one namespace on the pool?

   `pre-run` receives the TEST-PLAN, so the answer is knowable before anything
   boots. Both eager steps below exist purely to keep their cost off the
   parallel critical path — with one namespace there is no such path, and if
   that namespace needs the container or the golden it pays exactly the same
   either way (`get-container` and `ensure-golden!` are both lazy and JVM-wide
   idempotent). So the eager work can only lose here, never win."
  [test-plan]
  (< 1 (count (filter #(= :kaocha.type/ns (:kaocha.testable/type %))
                      (testable/test-seq test-plan)))))


(defplugin kaocha.plugin/shared-container
  "Manages shared PostgreSQL container lifecycle for tests."

  ;; Called before the entire test run starts — with the TEST-PLAN, so we know
  ;; how much of a run this is before paying for it.
  (pre-run [test-plan]
           (when (parallel-run? test-plan)
             ;; Start the container eagerly so it is ready when the parallel
             ;; namespaces start.
             (sc/get-container)
             ;; Eagerly bootstrap the golden DB for the default
             ;; `["core" "web" "app"]` bundle, off the test threads. Siblings
             ;; then pay only the ~100 ms template clone + ~1 s ctx rebuild —
             ;; the ~14 s sync stays outside the parallel critical path.
             ;; `ensure-golden!` is JVM-wide idempotent, so the first per-NS
             ;; call landing during a test still hits the cache.
             (sb/ensure-golden! ["core" "web" "app"]))
           ;; Skipped for a single-namespace run, which is every `--focus`. Both
           ;; steps are optimisations for the parallel case and neither can help
           ;; here: with one namespace there is no critical path to keep them
           ;; off, and a namespace that genuinely needs them pays the identical
           ;; cost lazily. Measured: `--focus` on one 33 ms pure-logic test cost
           ;; 8.5 s wall, all of it a container boot and a golden bootstrap it
           ;; never touched. That is the inner loop for anyone iterating on a
           ;; unit test.
           test-plan)

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
