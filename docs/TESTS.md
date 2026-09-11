# Tests — the `tests` namespace convention

Graphden's analogue of a unit-test suite (Roadmap Block 3.1). No new
entity, field, or edge (PHILOSOPHY § "Tests are not a new entity or
field"): a test is an ordinary fn, discovered by namespace convention,
executed through the ordinary execute pipeline, its status derived
from ordinary `:fn-execution` rows.

## The convention

A **test** is a fn that:

- lives in a namespace whose dotted path contains the segment
  **`tests`** — `tests.parser`, `myproj.tests`, `myproj.tests.api`.
  Matched by SEGMENT, never substring (`testsuite` is not a test
  namespace). Any-segment placement (not root-only) keeps a project's
  tests inside the project's own root namespace, where the workspace
  chip can see them;
- has a name **not** prefixed with `_` — the `_`-private convention
  marks scaffolding, and a tests namespace needs private helpers like
  any other;
- has **all free args bound** — the runner executes with `{}` args;
  a fn with unbound args reports `not-runnable` instead of running.

A test **passes** when it executes without a throw.

Server predicate: `graphden.crud.test-runs/test-ns-path?`; JS mirror:
`isTestNsPath` in `editor-tests.js` — keep the two in sync.

## Assertion vocabulary (core.logic)

- **`:assert`** `{:value}` — throws
  `:execution-error/assertion-failed` on falsy (nil / false), passes
  the value through otherwise. Compose over `:equal?` / `:lt` /
  `:some?` / … for the condition.
- **`:assert-eq`** `{:actual :expected}` — throws with both operands
  in the error data unless `actual = expected`. The shared typevar
  gives the same sync-time cross-type-compare guard as `:equal?`.

Neither takes a `message` slot (principle 6): the test's NAME labels
the invariant, the error data carries the operands — and an optional
slot would surface as a free arg on every assert-composed test,
breaking zero-arg runnability. Failing operands ride `:error-data`,
never the visible message (secret-redaction rides the standard
execute-pipeline scrub chain).

```clojure
;; myproj.tests
{:name :sum-works
 :parent :assert-eq
 :args {:actual {:parent :add :args {:nums [2 2]}}
        :expected 4}}
```

## Running and statuses

- **POST `/api/tests/run`** — body `{fn-ids?: [...], timeout-ms?: n}`;
  runs the branch's tests (or the subset) SEQUENTIALLY through
  `fn-execution/apply-execute` — the standard pipeline: effect
  gating, redaction, per-org caps, persistence (`:persist? true`).
  Returns `{total, passed, failed, other, results}`. `other` counts
  `not-runnable` (unbound args), `rejected` (recorded type errors /
  capacity), `pending` (overran `timeout-ms`, default 10 s — the
  terminal status lands on the row asynchronously).
- **GET `/api/tests/status`** — every test on the current branch with
  the newest execution of its **CURRENT version**. `status` null =
  the current version never ran. Keying by current version makes an
  edited test read as **stale by construction** — no bookkeeping.
- Statuses are ordinary `:fn-execution` rows: the TTL sweeper applies
  (succeeded 7 d, failed 30 d), so an untouched green suite fades to
  stale after a week — re-run to refresh.

Both endpoints are branch-scoped (`X-Graphden-Branch` /
per-branch routing) and auth-required. Core:
`src/graphden/crud/test_runs.clj`; the HTTP face is the
`app/test-api` package module (thin `:_tests-run-apply` /
`:_tests-status-apply` boundary base-fns + graph composition).

## Editor surfaces

- **Explorer `tests` lens** (the ✓ chip, shortcut `t`) — filters the
  tree to tests; test namespaces stay visible even before their
  leaves lazy-load. The chip counts the branch's tests (and, in red,
  the failed ones); each test row
  carries a status dot: green passed, red failed, grey stale/not-run
  (primed from `/api/tests/status`). With the lens active the chip
  row reveals **▶ Run all** (`POST /api/tests/run`), and the editor
  keeps a LIVE signal open: `GET /partials/tests-stream` pushes a
  server-time PING on write wakes and a 30 s keepalive (`run-tests!`
  emits a `test:updated` NOTIFY after EVERY test — so a long suite's
  dots move one by one — and once more after a 2 s settle, covering
  the async terminal-row write); each ping re-primes the status
  cache. The stream deliberately carries a ping, not data: a
  long-lived stream's captured render freezes data-dependent
  fragments (a `:time`-effect ping re-renders every tick by
  construction). The editor subscribes over fetch-streaming, not
  EventSource — the Authorization + X-Graphden-Branch headers ride
  the editor's patched fetch (`editor-tests.js`).
- **Inspector › Test** (Bindings tab, for a fn that IS a test on the
  branch) — the status dot + label, the assertion's message on a
  failure, and **Run this test**: an htmx `POST
  /partials/inspector-test/run?fn-id=…` that runs the fn through
  `run-tests!` and renders the section back from the run's own
  result (the status join's terminal row lands asynchronously, so a
  re-read right after the run could still show the previous status).
  Graph-composed in `app/editor-provenance/fns.edn` (`_insp-test-*`)
  over the same two boundary base-fns as the JSON API.
- Retired 2026-09-04: the diagnostics-drawer Tests panel
  (`GET /partials/tests`) — the lens + Inspector are the surface (and
  the drawer under the canvas itself went the same day).

## Auto-run on writes (phase 2)

When a graph write lands, `crud.entities/invalidate!` already knows
the affected fn-id seeds and branch; `crud.test-autorun/
schedule-affected!` rides that hook (a best-effort sibling of the
service-restart blast):

1. reverse transitive closure of the seeds over the ctx's
   `:compile-deps` index (`transitive-blast` — the same walk the
   service restart uses), intersected with the branch's tests;
2. **purity gate** — only tests whose recorded effect closure is
   EMPTY auto-run; an effectful test (`:network`, `:db`, …) runs only
   from the explicit Run button. Unknown closure counts as NOT pure;
3. the run executes under `:allowed-effects #{}` — a hidden effect
   the static closure missed throws `:execution/forbidden-effect`
   instead of silently firing (the tenancy-gate stance: don't trust
   the static set);
4. debounced per `[org branch]` (500 ms, bursts coalesce), capped at
   25 tests per pass (`*max-auto-run*`; dropped tests keep their
   stale status), off-switch `*auto-run?*`;
5. the runner future conveys the writing request's dynamic bindings —
   tests execute under the writer's org, exactly as the writer would
   run them;
6. on completion a best-effort `test:updated` NOTIFY nudges SSE
   listeners.

Cold ctx (`:compile-deps` nil — fresh boot, post-full-clear) → no-op,
same contract as the service-restart blast; the Run button always
works. Single-pod semantics: the hook fires on the pod that took the
write; statuses land in shared storage either way.

## Platform tests

The shipped packages carry their own tests, the same way a project
does: `core.tests`, `web.tests`, `app.common.tests` (app-base),
`app.tests`, `app.registry.tests` (registry) — and the external
`mathx.tests`. Each is an ordinary `tests` module in its package
(`resources/packages/<pkg>/tests/fns.edn`), synced into every instance
with the rest of the package, `:assert-eq` / `:assert` over the
package's own fn-defs with inputs pinned. They are the graph-level
analogue of the Clojure unit suite for the fn-def layer: a base-fn's
Clojure test pins the impl, a platform test pins the COMPOSITION a
package builds on it.

Platform tests are tests like any other — discovered, listed under
the ✓ lens with their status dots, runnable one at a time from the
Inspector — with one explicit difference: **an all-tests run skips
them unless asked.** `POST /api/tests/run` (and the MCP `run-tests`
tool) take `platform?`; without it only the org's own tests run, so a
tenant's [Run all] spends the org's execution budget on the org's
tests. The ✓ chip counts the same set [Run all] runs. The status rows
carry `platform?` (`test-runs/platform-test-row?` —
`packages.owned/owned-fn-id?`, i.e. package-synced this boot; a
property of the deployment, not a column).

Auto-run is unchanged for them: a platform test re-runs when
something in its dependency closure is written — which, since platform
fns are package-guarded, means a branch that deliberately shadows a
platform fn. That is the case where you want them: change `:add` on a
branch and the `core.tests` covering it go red there.

The gate is the Clojure anchor `packages.platform-tests-test`: it
boots the full first-party bundle and runs `{:platform? true}` —
every platform test must pass, none may be `not-runnable`. A red
platform test lands nowhere. It costs ~9 min of the integration
suite (360 tests, sequential, through the full pipeline) — the price
of running them the way a user's tests run, not through a shortcut.

Authoring rules for a `tests` module: pure subjects only (no
`:db` / `:network` / `:time` / `:env` — those never auto-run and
would need a live system in CI); one invariant per test, named after
it; `_`-private helpers are scaffolding and must be referenced
(`bb graph-lint`); verify through `/mcp` on a `bb wt up` stack before
copying the EDN into the module (CLAUDE.md § Live graph verification).

## Coverage of the fn-def and package layers

Three different things are measured, and they answer different
questions (`bb coverage` docstring + `feedback_coverage_measurement`):

- **`src/` Clojure + the package layer** — cloverage over the unit
  suite (`bb coverage`; the CI `coverage` job floors form coverage on
  `main`). 2026-09-11: 70.16 % form / 81.27 % line over 2083 tests.
  Read a drop against WHICH namespaces moved: the number fell from
  73.15 % that week because the 42 impls.clj namespaces joined the
  denominator, not because tests were lost.
- **`resources/packages/**/impls.clj`** — the package layer joined the
  same report on 2026-09-10. The loader `eval`s impls from resources,
  so cloverage never found them: `scripts/coverage_src.clj` mirrors
  each impls.clj as a symlink at the classpath position its namespace
  name implies (`target/coverage-src`, regenerated by `bb coverage`),
  the `:coverage` deps alias puts that tree on the classpath and sets
  `-Dgraphden.impls.reuse-loaded=true`, under which
  `packages.loader/reuse-loaded-impls?` reuses the instrumented
  namespace instead of re-evaluating over it. Off the flag nothing
  changes.
- **Frontend JS** — no line coverage in CI (rule 3: e2e is measured
  by user-scenario coverage). A SNAPSHOT can be taken by hand:
  `GRAPHDEN_JS_COVERAGE=<dir> ./run-edit-tests.sh` against a
  `bb wt up` stack dumps V8 block coverage per browser, and
  `node tools/browser-test/js-coverage-report.js <dir>` reports lines
  per editor module (comment/blank lines excluded, like cloverage's
  line %). Snapshot 2026-09-10 — see `docs/TESTS_JS_COVERAGE.md`.

## Known limitations

- An unbound free arg blocks the run UNLESS its slot's DECLARED type
  explicitly admits nil (`[:union :null …]` — e.g. a `:nullable-text`
  slot) — those default to nil, type-soundly. Concrete types and
  `:any` stay blocking (conservative: an `:any` free is usually a
  forgotten binding, and running it as nil would pass vacuously).
  Note typevar unions (`[:union :null a]`) materialise as `:any`
  slots, so they block too.
- Effectful tests never auto-run (by design) and prompt the standard
  side-effect confirmation when run individually from the Run
  popover.
- ~~`slot.type-fn-id` is not an edge in the compile-deps index~~ —
  closed 2026-09-10: the declared type of every exposed slot is a
  forward dep (`compile/deps.clj`), so a type-row edit reaches the
  tests (and services) whose slots carry it through the same walk.
