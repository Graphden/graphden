# Function Execution

Run any fn-graph from the editor (or HTTP), get the result inline
when it's fast, or hand off to async polling when it isn't. Persisted
runs feed the per-fn history sidebar.

This is the **execution-as-event** model — every `(future …)` we
submit is a moment-in-time fact that can be replayed but not
rewritten.

## Architecture overview

```
   POST /api/execute  (the :execute route — a graph fn-def)
        │
        ▼
   parse-execute-request   (json → {fn-id args timeout-ms persist?})
        │
        ▼
   graph validation :cond  (fn exists, args fit free-args, caps —
        │                    `:_execute-validation` in app/execution/fns.edn;
        │                    no Clojure mirror, the graph is the sole path)
        ▼
   apply-execute  ──► (future …)  ──► executor.compile-runtime/execute
        │                                  │
        ├──◄ deref f timeout ──► inline:   │
        │   {status: succeeded, result}    │ ── future resolves
        │                                  │
        └──► timeout fires:                ▼
             persist :pending,  ◄── record-completion! tail-future
             return :execution-id          writes :status :succeeded
                                           | :failed | :cancelled
                                           + :result/:error/:finished-at
```

**Nothing custom for "long-running" or "worker pool" — it's a plain
`(future …)`** that the HTTP handler waits on with a timeout. Long
fns gracefully degrade to polling.

## Storage schema

Three non-versioned entities + one enum live in
`src/graphden/schema/executions/schema.clj`:

### `:fn-execution`

| Field              | Type            | Notes                                                                       |
|--------------------|-----------------|-----------------------------------------------------------------------------|
| `:id`              | `:uuid`         | Returned to the client as `execution-id`.                                   |
| `:fn-version-id`   | `:ref :fn-version` | Frozen snapshot of which version ran. Base `:fn-id` derives via JOIN.     |
| `:started-at`      | `:timestamptz`  | When the future was submitted.                                              |
| `:finished-at`     | `:timestamptz` (null until resolved) | Duration computed at read-time (`finished - started`). No denorm. |
| `:status`          | `:execution-status` enum | `:pending` `:succeeded` `:failed` `:cancelled`.                |
| `:result`          | `:jsonb` (null) | Capped at 5 MB. Oversize → `:result nil` + `:result-truncated? true`.       |
| `:result-truncated?` | `:bool` (null) | Set when the 5 MB cap fires.                                                |
| `:error`           | `:text` (null)  | Exception message, truncated to 4 KB.                                       |
| `:error-data`      | `:jsonb` (null) | `ex-data`, truncated to 64 KB (or `{:type … :truncated true}`).             |
| `:declared-effects`| `:jsonb` (null) | Snapshot of `:effects` from rich-types registry at submit time.             |
| `:runtime-effects` | `:jsonb` (null) | Observed effects captured via `cr/record-effect!` inside instrumented impls. Diffed against `:declared-effects` to surface drift in the editor's result pane. |
| `:user-id`         | `:uuid` (null)  | Reserved for future user/session auth.                                      |
| `:cancel-requested?` | `:bool` (null) | Set by `/cancel`; observed by `*cancel-check*`.                            |

### `:fn-execution-arg`

Mirrors `:binding` shape — one row per free-arg supplied:

| Field                | Type            | Notes                                                  |
|----------------------|-----------------|--------------------------------------------------------|
| `:execution-id`      | `:ref :fn-execution` |                                                 |
| `:slot-id`           | `:ref :slot`    | Which free-arg.                                        |
| `:value`             | `:jsonb` (null) | XOR with `:ref-fn-version-id`.                         |
| `:ref-fn-version-id` | `:ref :fn-version` (null) | Frozen snapshot of ref-targets (HOF args).   |

Unique on `(execution-id, slot-id)`.

### `:fn-execution-arg-item`

Sequence content under list-typed args (mirrors
`:binding-list-item`):

| Field                | Type            |
|----------------------|-----------------|
| `:execution-arg-id`  | `:ref :fn-execution-arg` |
| `:position`          | `:int`          |
| `:value`             | `:jsonb` (null) |
| `:ref-fn-version-id` | `:ref :fn-version` (null) |

Unique on `(execution-arg-id, position)`.

### Why non-versioned

Executions are **events** (immutable historical facts). Mutations
are limited to `:status` flipping `:pending → terminal`. Versioning
machinery would record meaningless one-step trails — not in
`versioning.storage.resolution/entity-config`.

## HTTP API

All endpoints require bearer-token auth (`auth-required-middleware`).

### `POST /api/execute`

**Request:**

```jsonc
{
  "fn-id":      "uuid",       // XOR with fn-name
  "fn-name":    "add",
  "args":       {"a": 1, "b": {"ref": "uuid"}, "c": [1, 2, 3]},
  "timeout-ms": 10000,         // default 10000, cap 60000
  "persist?":   false,         // default false
  "trace?":     false,         // default false — Debug P1 path capture
  "capture-values?": false     // default false — Debug P3 value capture
                               //   (implies trace?; the editor sets it only
                               //   behind an explicit confirm dialog)
}
```

Arg value shape (per slot):

| Form                           | Stored as                                          |
|--------------------------------|----------------------------------------------------|
| `1`, `"hi"`, `true`, `null`    | `:value` (literal jsonb)                           |
| `{"ref": "uuid"}`              | `:ref-fn-version-id` (resolves logical fn → version) |
| `[1, 2, {"ref": "..."}]`       | Per-item `:fn-execution-arg-item` rows             |

**Response — 4 shapes:**

```jsonc
// a) finished within timeout, NOT persisted (default for pure fast fns):
{"status": "succeeded", "result": …, "declared-effects": null}

// b) finished within timeout, persisted (?persist=true OR effects ≠ #{}):
{"status": "succeeded", "result": …, "declared-effects": ["env"],
 "execution-id": "uuid"}

// c) timeout fired — polling mode:
{"status": "pending", "execution-id": "uuid"}

// d) pre-flight validation failed (400):
{"status": "rejected", "error": "...", "error-data": {"reason": "..."}}
```

**Auto-persist matrix:**

| Condition                              | Persist? |
|----------------------------------------|----------|
| client `persist?=true`                 | yes      |
| `declared-effects` ≠ #{} (audit trail) | yes      |
| timeout fired (need polling target)    | yes      |
| pure fn finished inline AND ¬persist?  | NO       |

### `GET /api/execute/:id`

Returns the full row + nested `:args` array (with per-item `:items`
sub-array for list args). 404 when row not found (after TTL cleanup
or wrong id).

### `POST /api/execute/:id/cancel`

```jsonc
{"ok": true, "cancel-requested": true}
```

Sets `:cancel-requested? true` + `future-cancel`. **Best-effort**:

- The executor's `*cancel-check*` dyn-var is bound by the future
  wrapper to a closure that reads the per-execution cancel-flag atom.
- On the next caller→callee transition inside `execute`, the check
  throws `InterruptedException` → record-completion! writes
  `:status :cancelled`.
- **Blocking JDBC** / sleep / blocking IO inside a base-fn won't
  respond to `Thread.interrupt()` without explicit cooperation
  (e.g., `Statement.cancel()`). Documented as a soft contract.
- **Multi-pod**: `futures-registry` is per-process, so the pod that
  receives the cancel is usually not the pod running the execution.
  When it doesn't own the future it emits `execution:cancel:<id>` on
  `graphden_events`; every pod calls `persist/cancel-local!` and at
  most one owns it. Setting the DB flag alone would do nothing —
  `*cancel-check*` reads the in-process atom, not the row. See
  [SCALING.md](SCALING.md).

### `GET /api/executions?fn-id=X`

```jsonc
{"ok": true, "executions": [/* up to 20 most-recent rows for fn-X */]}
```

Lists rows across all versions of base `:fn-id`, ordered
`:started-at` desc, hard-limited at 20. Summary shape (no nested
args). Editor's History panel calls this.

## Concurrency caps

Two caps gate how many executions run at once, with different scopes
(see `crud.fn-execution.persist/acquire-execution-slot!`):

| Cap | Env | Default | Scope |
|-----|-----|---------|-------|
| Global | `GRAPHDEN_MAX_CONCURRENT_EXECUTIONS` | 128 | Per-POD. Protects the JVM's unbounded soloExecutor from thread exhaustion. |
| Per-org | `GRAPHDEN_MAX_CONCURRENT_EXECUTIONS_PER_ORG` | 32 | **Fleet-wide for tenants** (counts pending `:fn-execution` rows in shared storage, so N pods share one budget); per-pod atom for the public/platform org. |

Hitting either cap rejects the request without creating a row or future:
`{:ok false :status :rejected :error-data {:reason :over-capacity}}`. See
[SCALING.md § Fleet-wide per-org quota](SCALING.md).

A second submit-time refusal precedes even the cap check (error-tolerance
Phase 4): an fn with RECORDED type diagnostics on the current branch is
rejected with `{:ok false :status :rejected :http-status 400 :error-data
{:reason :unresolved-type-errors :diagnostics […]}}` — the message names
the fn + its first error. See [ERROR_CODES.md](ERROR_CODES.md)
§ execute rejection.

## Wire caps

| Field                   | Cap     | Behaviour on overflow                          |
|-------------------------|---------|------------------------------------------------|
| `:result`               | 5 MB    | Store nil + `:result-truncated? true`         |
| `:args` (sum, serialised) | 256 KB | Pre-flight reject with 413 / `:args-too-large` |
| `:error`                | 4 KB    | Truncate with `…`                              |
| `:error-data`           | 64 KB   | Truncate JSON path, keep `:type` if possible   |
| `:timeout-ms`           | 60 s    | Reject `:timeout-out-of-range`                 |

## TTL cleanup

Integrant component `:exec/cleanup-scheduler` runs a single
`ScheduledExecutorService` thread hourly (override via
`CLEANUP_PERIOD_MS` env var).

| Status      | Retention | Action when past                              |
|-------------|-----------|-----------------------------------------------|
| `:succeeded` | 7 days   | DELETE                                        |
| `:failed`    | 30 days  | DELETE                                        |
| `:cancelled` | 7 days   | DELETE                                        |
| `:pending` > 1 h | (zombie sweep) | Flip to `:cancelled` (NOT delete); next sweep applies the 7-day TTL |

Implementation: `sweep-executions! storage now` (in
`graphden.system.init.cleanup`). The `now` argument is injectable so tests
can verify TTL behaviour without sleeping real time.

## Usage rollups (`:usage-stat`)

Every terminal transition (both inline arms in `apply-execute` and the async
arms in `record-completion!`) also increments a **pre-aggregated rollup row**:
one per `(UTC-hour bucket, org, fn, status)` with a run count and summed
wall-clock duration — via an atomic `INSERT … ON CONFLICT DO UPDATE`
(`graphden.crud.fn-execution.stats/bump!`, best-effort: a failed bump logs and
never fails the execution). The table stores **counts and durations only** —
never args, results, or error text — so it is privacy-safe to aggregate and
grows with distinct keys, not traffic.

Reads: the `:usage-fn-stats` graph fn-def — a `:quot`-derived average over
the `:fn-stats-raw` read base-fn (`{:runs :failed :cancelled :avg-ms}`
for one fn over a trailing window, scoped to the CURRENT org) feeds the
"7d: N runs · M failed · avg K ms" strip at the top of the editor's
execute-history panel; `org-stats` (src-level) lists an org's busiest fns for
operator tooling. Retention: the cleanup scheduler sweeps buckets older than
90 days (`sweep-stats!`) — trends outlive the raw `:fn-execution` TTLs, which
is the point.

## Error log (`GET /partials/error-log`)

The editor's **Errors** sidebar section lists the current org's recent FAILED
executions (newest first) straight off the `:fn-execution` audit rows — no new
storage. Privacy holds because the write path already sanitised each row:
`redact-outcome` hides secret-tainted bodies, `scrub-outcome` replaces
internal error types with an opaque `ref:` on the cloud. Rows render the fn
name as a native `#hash` link (the editor's hashchange navigation), the finish
time, the error text, and a collapsible ex-data block. Backed by the
`:recent-failures` base-fn (`graphden.crud.fn-execution.errors`) — raw SQL
with an explicit org filter, 7-day window, 50-row cap. Visibility follows the
row TTLs (failed rows sweep after 30 days).

## Editor UI

The ▶ button on a fn-card root row (auth-required, visible only to
signed-in users) opens the **execute popover**:

- One type-aware form per free-arg, loaded via `/api/value-form` —
  same widgets that drive binding edits.
- Red **effects banner** when `:declared-effects ≠ #{}`, listing one
  chip per category (db / env / io / network / time / random).
- **Effect-confirm gate** — checkbox "I understand this will produce
  side effects" required before Run when effects exist; pure fns skip.
- **Persist toggle** — "Save to history". Pre-checked + disabled for
  effect-bearing fns (backend auto-persists those).
- **Run** → POST `/api/execute` → response handled:
  - `:succeeded` → inline type-aware result pane (scalar chip / list
    bullets / record table / JSON `<pre>` with > 50 KB truncation).
  - `:pending` → spinner + execution-id + polling with exponential
    backoff (500 ms → 1 s → 2 s → 5 s → 30 s) + Cancel button.
  - `:failed` → error pane with message + ex-data.
- **History toggle** in the header — lazily fetches `/api/executions`
  and renders a collapsible panel. Each row: status chip + time +
  duration + result preview. Click expands full result; **Repeat**
  refills the form widgets with that run's args.

Source: `resources/packages/app/editor/editor-execute*.js`.

## Runtime effect tracing

Each `apply-execute` future binds a fresh `*effect-trace*` atom-set
(see `graphden.executor.compile-runtime`). Effectful base-fn impls
call `(cr/record-effect! :env)` etc. to declare what they're about
to do; the reaper snapshots the set onto the row's
`:runtime-effects` field at terminal status.

Categories: same vocabulary as rich-type-of `:effects` —
`:db`, `:env`, `:io`, `:network`, `:time`, `:random`, `:process`,
`:state`, `:raw-sql`.

Currently instrumented (in `resources/packages/core/system/impls.clj`):

- `:env` → `:env`
- `:current-time-ms` → `:time`
- `:read-resource-or-nil`, `:slurp` → `:io`

(`:jvm-version` and `:heap-memory` are fn-def compositions in
`fns.edn`, not `impls.clj` defbases — their `:io` propagates from
the JVM/heap primitives they compose.)

Adding instrumentation is a one-liner inside the `defbase` body:

```clojure
(defbase my-effectful-impl [arg]
  (cr/record-effect! :network)
  (http-get arg))
```

`(cr/record-effect! cat)` is a no-op outside an execution trace
context, so direct unit-tests of the impl don't need special setup.

The editor's execute popover surfaces the runtime set as a "ran:"
strip below the result. Two drift visuals:

- **`execute-effects-drift`** (red dashed outline) — observed at
  runtime but NOT in `:declared-effects`. Impl widened its effect
  set vs the rich-type promise; either update the rich-type or
  remove the un-promised effect call.
- **`execute-effects-unobserved`** (grey dashed outline, muted) —
  declared in the rich-type but NOT observed on this run. Could be
  a legitimate conditional branch that didn't fire (e.g. a `:db`
  call inside an `:if :then` that took the `:else` arm), OR an
  over-declaration in the `:effects` set that's worth cleaning up.

The unobserved chips render only when a runtime trace WAS recorded
for this row — runs from before instrumentation existed (or for fns
that never call `record-effect!`) leave both fields empty and the
strip is suppressed entirely.

## Cancellation contract

The `*cancel-check*` dynamic var (`graphden.executor.compile-runtime`)
is checked at the top of every `execute` invocation. Default
unbinding is `nil` (no-op). The fn-execution future wrapper binds it
to a closure that reads the per-execution cancel-flag atom and
throws `InterruptedException` when flipped.

**For long-running impl bodies** (e.g., compute loops, scan jobs)
that want to participate cooperatively:

```clojure
(defbase my-slow-loop [coll]
  (reduce (fn [acc x]
            (cr/check-cancel!)         ; cooperatively yields
            (process x))
          [] coll))
```

`(cr/check-cancel!)` is a no-op outside the execution context, so
impls can call it unconditionally.

## Tests

| File                                                      | Coverage                                      |
|-----------------------------------------------------------|-----------------------------------------------|
| `test/graphden/crud/fn_execution_test.clj`                | parse / validate (5 rejection reasons) / apply (inline + persisted + args rows) / get / cancel (flag + real interrupt) / list (4 deftests) / TTL sweep (3 deftests, includes regression for `as-instant` Date handling) / failed-path / args-too-large / result-truncation |
| `tools/browser-test/edit-execute.test.js`                 | E2E: ▶ popover, fill args, Run, inline result, History panel reveal |

Total: ~25 deftests / ~90 assertions backend, 8 assertions browser.
