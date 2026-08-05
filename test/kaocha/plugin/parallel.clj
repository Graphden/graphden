(ns kaocha.plugin.parallel
  "Per-suite parallel namespace runner.

   Overrides `:kaocha.type/clojure.test` -run multimethod at load time
   so a suite with `:kaocha/parallelism N > 1` runs its child
   namespaces on a bounded N-thread pool. Other suites fall through to
   the original single-threaded behaviour. Registered as
   `:kaocha.plugin/parallel` in tests.edn.

   Two opt-out mechanisms keep parallel runs honest:

   - `^:serial` NS meta — an NS-level opt-out for tests that mutate
     non-`^:dynamic` symbols via `with-redefs` (e.g. vault-test's
     httpkit/get); their writes are process-global and parallel runs
     race fatally. They run sequentially BEFORE the parallel set so
     the parallel slot count bounds wall-clock.

   - `isolation-vars` — a list of `^:dynamic` Vars naming
     process-global atoms (`types.core/*type-aliases-override*`,
     …). For each NS-thread we bind these to fresh atoms via
     `with-bindings`, so test code that mutates the named registry
     stays thread-local. Mirrors the executor's
     `registry/*registry-override*` pattern; extend the list when a
     new global-mutable surface gets used by tests in parallel.

   IMPLEMENTATION GOTCHA: kaocha lazy-loads
   `kaocha.type.clojure.test` / `kaocha.type.ns` from `testable/run`
   via `try-load-third-party-lib`. Without an EAGER require at the
   top of THIS NS, kaocha's own
   `(defmethod testable/-run :kaocha.type/clojure.test ...)` loads
   LATER and silently clobbers our defmethod. The `:require` below
   forces them loaded BEFORE we register."
  {:clj-kondo/config '{:linters {:unresolved-symbol {:level :off}}}}
  (:require
    [clojure.test :as t]
    [kaocha.plugin :refer [defplugin]]
    [kaocha.testable :as testable]
    [kaocha.type.clojure.test]
    [kaocha.type.ns])
  (:import
    (java.util.concurrent
      Callable
      ExecutorService
      Executors
      TimeUnit)))


(defn- ns-serial?
  [t]
  (boolean
    (when-let [id (:kaocha.testable/id t)]
      ;; kaocha's :kaocha.testable/id is a KEYWORD (e.g. :graphden.foo-test);
      ;; find-ns wants a symbol. Convert.
      (let [ns-sym (symbol (name id))]
        (when-let [n (try (find-ns ns-sym) (catch Exception _ nil))]
          (:serial (meta n)))))))


;; Process-global mutables that test code mutates as if it owned them
;; (clear, register, replace, …). Under parallel execution each NS
;; needs its own isolated copy so tests don't trample one another.
;;
;; Each entry is the fully-qualified symbol of a `^:dynamic` var
;; declared in production code; we bind it to a fresh atom per
;; NS-thread for the duration of that NS's run. We `requiring-resolve`
;; lazily so the plugin doesn't trigger production-NS load at
;; plugin-load time (the parallel-suite -run dispatch needs to
;; complete `try-load-third-party-lib` first).
;;
;; 2026-06-15 audit of `src/`-side process-global atoms (re-audited
;; 2026-08-04) — the entries below cover every test-contaminator
;; surface. The other defonce atoms are safe under parallel by
;; construction:
;;   - `executor.compile.lookups/cached-build-lookups-state`
;;     and `layout.data/cached-build-lookups-state` — identity-keyed by
;;     graph reference. Two ctxes never collide on the same key (the
;;     bounded LRU might evict a sibling's entry, but that's a perf
;;     hiccup, not a correctness bug).
;;   - `crud.fn-execution.persist/futures-registry` — UUID-keyed by
;;     execution-id. No key collisions across tests.
;;   - `executor.registry.core/refinement-impl-cache` — content-keyed
;;     by constraint shape. Cache values are deterministic (the
;;     compiled validator fn); concurrent writes for the same
;;     constraint produce equivalent values.
;;   - `types.core/fresh-counter` — monotonic int counter. Tests may
;;     see different values across runs but the values are just
;;     unique identifiers, not test inputs.
;;   - `schema.fields.types/custom-types-registry` — used by exactly
;;     one test ns (`schema.fields.types-test`) which both registers
;;     and unregisters in symmetric pairs.
;;   - `clients.vault/active-client` — production JVM-wide singleton;
;;     tests don't touch it.
;;   - `executor_runtime.core/system` — production singleton.
;;
;;   (`branch-router` active-router + the `route-collection` are NOT in this
;;   "tests don't touch" set — `smoke-pass-test` / `grants-admin-test`
;;   set them mid-run — so they're isolated below via
;;   `*active-router-override*`.)
;;
;; When a NEW global-mutable surface is added, default it to this list
;; unless one of the above by-construction reasons applies. Symptom
;; of a missed isolation: an integration test flakes intermittently
;; under `bb test` but passes under `bb test-sequential`.
(def ^:private isolation-vars
  '[graphden.system.branch-router/*epoch-state-override*
    graphden.types.core/*type-aliases-override*
    ;; compile-eager's always-fresh set lives behind a dynamic var so
    ;; two NS-threads racing on `compile-runtime/rebuild!` —
    ;; `prime-always-fresh!` resets via `set-always-fresh-fn-ids!` —
    ;; don't overwrite each other's `:time`/`:random` fn-id set. Without
    ;; this isolation the loser's always-fresh entries drop out and
    ;; timing-sensitive tests (clock read twice → see same value via
    ;; memo) flake under parallel runs.
    graphden.executor.compile-eager/*always-fresh-fn-ids*
    ;; Debug-P1 path-trace per-fn opt-in set — same shape/rationale as
    ;; always-fresh above: tests calling `set-traced-fn-ids!` on
    ;; parallel NS-threads must not clobber each other's sets.
    graphden.executor.compile-eager/*traced-fn-ids*
    ;; Debug-P3 ambient sample rate — sibling of the traced set: tests
    ;; calling `set-trace-sampling!` on parallel NS-threads must not
    ;; leak a 1.0 rate into a sibling whose selectively-traced fns
    ;; would then all record. Has a seeder entry (the numeric 0.01
    ;; default — the `{}` default seed would break the numeric read).
    graphden.executor.compile-eager/*trace-sample-rate*
    ;; rich-types-registry's thread-local override. Integration tests
    ;; that bootstrap their own package set (`compile-packages-test`,
    ;; `execute-http-test`, `smoke-pass-test`) write per-fn `:return` /
    ;; `:effects` / `:args` shapes that the executor's compile pass
    ;; reads via `ref-produces-callable?`. Without this isolation, a
    ;; sibling NS-thread's partial registry can leak in mid-compile and
    ;; the consumer reads a stale `:return` — surfacing as
    ;; `AFunction$1 cannot be cast to Associative` deep inside
    ;; compile-eager's arg-builder chain. Symptom historically caught
    ;; by `execute-http-test`'s parallel-mode flakes.
    ;;
    ;; This var has an isolation-var-seeder entry: the bound atom is
    ;; PRE-SEEDED with the global snapshot via
    ;; `snapshot-for-isolation`. Reads of `rich-type-of` happen deep
    ;; in the type-checker's `effective-ref-return` recursion (O(depth
    ;; × fn-defs)) — pre-seeding keeps each read at O(1) hash lookup
    ;; instead of the O(N) merge view that an empty-seed override
    ;; would have forced (smoke_pass_test demonstrated the cost: a
    ;; merge-on-read view tipped bootstrap from seconds into a 20-min
    ;; GC-thrashing hang).
    graphden.executor.registry.core/*rich-types-override*
    ;; The base-fn IMPLS registry override. Historically "covered via
    ;; `exec/with-clean-registry` instead of this list" — but that only
    ;; holds for NSes that remember the fixture: the 2026-08-04 audit
    ;; found two non-serial NSes (`executor.registry.core-test`'s
    ;; register-base-fns-test, `crud.fn-execution-test`'s ten
    ;; register-base-fn! sites) writing the ROOT atom directly with no
    ;; override on the stack. Binding it here is the plugin-level
    ;; backstop that makes the class impossible for future NSes too.
    ;; Seeded from the global snapshot (rich-types pattern); reads are
    ;; unchanged either way — `get-base-fn` falls through to the global
    ;; for names an override doesn't carry.
    graphden.executor.registry/*registry-override*
    ;; §4 Risk-2: the per-org type-alias slice index, rebuilt (reset!) by
    ;; `register-type-aliases-from-db!` in lockstep with the global aliases.
    ;; Without isolation a sibling NS-thread's rebuild overwrites it mid-run and
    ;; a tenant type-check reads another NS's per-org view. Seeded from the
    ;; global snapshot (like rich-types) so reads work before the NS's rebuild.
    graphden.executor.compile-runtime/*per-org-aliases-override*
    ;; §4 Risk-2: the per-org rich-types slice — same isolation need as
    ;; *rich-types-override* (its sibling above), seeded from the global per-org
    ;; snapshot so reads work before the NS's first record.
    graphden.executor.registry.core/*per-org-rich-override*
    ;; The branch/tenancy active-router singletons. `smoke-pass-test` +
    ;; `grants-admin-test` install a router mid-run via
    ;; `set-active-router!`; without per-thread isolation a sibling
    ;; NS-thread's merge handler reads the wrong router off the shared
    ;; global, calls `ctx-for` on it, and invalidates the wrong ctx —
    ;; leaving its own graph-cache stale (branches-lifecycle-test's
    ;; post-merge `fn-by-name` then returns nil). Seeded to `nil` so each
    ;; thread starts router-less and falls back to its own request ctx.
    graphden.system.branch-router/*active-router-override*
    graphden.system.route-collection/*active-collection-override*
    ;; The byo-mode memo. Without isolation a sibling NS-thread caching an org
    ;; as byo (or hosted) leaks into another test that reuses the org name
    ;; (`acme`/`beta`) — e.g. subdomain-test's request-scope 421s an apex
    ;; request whose org was cached byo elsewhere. Empty `{}` seed is the
    ;; correct default (no orgs cached).
    graphden.tenancy.context/*byo-cache-override*
    ;; The sensitive-field redaction registry. `errors_test` (parallel)
    ;; registers `:employee-ssn`-style extras and `redact_test` resets
    ;; the registry to defaults — a cross-NS race on the shared atom in
    ;; either direction. Seeded from the global snapshot so the default
    ;; names/patterns (+ any boot registrations) stay active per thread.
    graphden.storage.protocol.redaction/*sensitive-fields-override*
    ;; The per-branch type-check diagnostics store. CRUD post-mutation
    ;; checks and package-sync sweeps record/clear entries; without
    ;; isolation a sibling NS-thread's bootstrap sweep leaks failure
    ;; entries into another NS's counts. Default `{}` seed (no seeder
    ;; entry) — an empty store is the correct fresh-thread state, the
    ;; entries are derived, not configuration.
    graphden.types.diagnostics/*diagnostics-override*])


;; Per-var seeders. Some isolation atoms must start non-empty — the
;; rich-types one needs to inherit the global snapshot at bind time
;; or the test ns's first read returns nil and downstream type-check
;; consumers crash. Default seed is `{}`; add entries here when an
;; isolation var needs a richer initial state.
(def ^:private isolation-var-seeders
  '{graphden.executor.compile-eager/*trace-sample-rate*
    graphden.executor.compile-eager/trace-sample-rate-isolation-seed
    graphden.executor.registry.core/*rich-types-override*
    graphden.executor.registry.core/snapshot-for-isolation
    graphden.executor.registry/*registry-override*
    graphden.executor.registry/snapshot-for-isolation
    graphden.executor.compile-runtime/*per-org-aliases-override*
    graphden.executor.compile-runtime/per-org-snapshot-for-isolation
    graphden.executor.registry.core/*per-org-rich-override*
    graphden.executor.registry.core/per-org-rich-snapshot-for-isolation
    ;; active-router overrides seed to `nil` (not the default `{}`) so
    ;; `current-router` returns nil until the NS's own fixture sets it.
    graphden.system.branch-router/*active-router-override*
    graphden.system.branch-router/active-router-isolation-seed
    graphden.system.branch-router/*epoch-state-override*
    graphden.system.branch-router/epoch-state-seed
    graphden.system.route-collection/*active-collection-override*
    graphden.system.route-collection/active-collection-isolation-seed
    graphden.storage.protocol.redaction/*sensitive-fields-override*
    graphden.storage.protocol.redaction/sensitive-fields-isolation-seed})


(defn- seed-for
  [sym]
  (if-let [seeder-sym (get isolation-var-seeders sym)]
    (if-let [seeder-fn (try (requiring-resolve seeder-sym)
                            (catch Exception _ nil))]
      (seeder-fn)
      {})
    {}))


(defn- resolve-isolation-vars
  []
  (into {}
        (keep (fn [sym]
                (when-let [v (try (requiring-resolve sym)
                                  (catch Exception _ nil))]
                  [v (atom (seed-for sym))])))
        isolation-vars))


(defn- run-one
  [test test-plan load-error?]
  (let [test (cond-> test
               (and load-error? (not (::testable/load-error test)))
               (assoc ::testable/skip true))]
    (with-bindings (resolve-isolation-vars)
      (testable/run-testable test test-plan))))


(defn- run-testables-parallel
  [parallel-tests test-plan n load-error?]
  (let [pool (Executors/newFixedThreadPool ^int n)]
    (try
      (let [futures (mapv (fn [test]
                            (let [work (bound-fn []
                                         (run-one test test-plan load-error?))]
                              (ExecutorService/.submit pool ^Callable work)))
                          parallel-tests)]
        (mapv deref futures))
      (finally
        (ExecutorService/.shutdown pool)
        (ExecutorService/.awaitTermination pool 5 TimeUnit/MINUTES)))))


(defn- run-children-mixed
  "Same shape as `testable/run-testables` (vector of result-testables in
   the input order), but splits children into serial + parallel sets and
   runs the parallel set on an N-thread pool."
  [testables test-plan n]
  (let [load-error? (some ::testable/load-error testables)
        indexed (map-indexed vector testables)
        {serial-set true parallel-set false}
        (group-by (comp ns-serial? second) indexed)
        serial-results (mapv (fn [[i t]]
                               [i (run-one t test-plan load-error?)])
                             serial-set)
        parallel-tests (mapv second parallel-set)
        parallel-raw (run-testables-parallel parallel-tests test-plan n load-error?)
        parallel-results (mapv vector (map first parallel-set) parallel-raw)]
    (->> (concat serial-results parallel-results)
         (sort-by first)
         (mapv second))))


(defn- run-suite-maybe-parallel
  [testable test-plan]
  (t/do-report {:type :begin-test-suite})
  (let [;; KAOCHA_PARALLELISM env overrides per-suite config; lets
        ;; `bb test-sequential` force n=1 without editing tests.edn.
        env-n (some-> (System/getenv "KAOCHA_PARALLELISM") parse-long)
        n (or env-n (:kaocha/parallelism testable) 1)
        children (:kaocha.test-plan/tests testable)
        results (if (> n 1)
                  (run-children-mixed children test-plan n)
                  (testable/run-testables children test-plan))
        testable (-> testable
                     (dissoc :kaocha.test-plan/tests)
                     (assoc :kaocha.result/tests results))]
    (t/do-report {:type :end-test-suite
                  :kaocha/testable testable})
    testable))


(clojure.lang.MultiFn/.addMethod
  testable/-run :kaocha.type/clojure.test run-suite-maybe-parallel)


(defplugin kaocha.plugin/parallel)
