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

- **Sidebar `tests` lens chip** (✓) — filters the tree to tests; test
  namespaces stay visible even before their leaves lazy-load. Each
  test row carries a status dot: green passed, red failed, grey
  stale/not-run (primed from `/api/tests/status`).
- **Diagnostics-drawer Tests panel** (the bar under the canvas) —
  summary line, Run-all button, per-test
  rows with status + error. LIVE via SSE ping + re-fetch:
  `GET /partials/tests-stream` pushes a server-time PING on write
  wakes and a 30 s keepalive (`run-tests!` emits a `test:updated`
  NOTIFY after every batch — immediately and again after a 2 s
  settle, covering the async terminal-row write); on each ping the
  editor re-fetches the always-fresh one-shot `GET /partials/tests`.
  The stream deliberately does NOT carry the panel body: a
  long-lived stream's captured render freezes data-dependent
  fragments (a `:time`-effect ping re-renders every tick by
  construction). The editor subscribes over fetch-streaming, not
  EventSource — the Authorization + X-Graphden-Branch headers ride
  the editor's patched fetch (`editor-tests.js`).

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
- `slot.type-fn-id` is not an edge in the compile-deps index, so a
  test reaching a changed type-row ONLY through a slot's declared
  type won't auto-run (same known gap as the service-restart blast).
