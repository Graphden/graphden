# Function Execution

Run any fn-graph from the editor (or HTTP), get the result inline
when it's fast, or hand off to async polling when it isn't. Persisted
runs feed the per-fn history sidebar.

This is the **execution-as-event** model — every `(future …)` we
submit is a moment-in-time fact that can be replayed but not
rewritten.

## Architecture overview

```text
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

Every run is submitted to a **bounded `ThreadPoolExecutor`**
(`make-execution-pool` in `crud/fn_execution/persist.clj`): `core == max`
workers (`GRAPHDEN_MAX_CONCURRENT_EXECUTIONS`, default 128) plus a bounded
`LinkedBlockingQueue`. Runs beyond the worker count **park in the queue**;
only when the queue is ALSO full does submit throw
`RejectedExecutionException`, which the handler turns into `503` +
`Retry-After` (`:error-data {:reason :queue-full}`) — never an unbounded
queue, never a silent drop. A watchdog (`*max-execution-wall-ms*`)
hard-kills a runaway. The HTTP handler waits on the submitted future with a
timeout; long fns gracefully degrade to polling.

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

### `GET /api/executions?fn-id=X` (or `?fn-version-id=V`)

```jsonc
{"ok": true, "executions": [/* most-recent rows, latest first */]}
```

Pass **`?fn-id=X`** to list runs of the version that resolves on the
**current branch** (the one ▶ would execute now — not every historical
version), or **`?fn-version-id=V`** to list runs of one specific version
(the `⌛` history panel's per-version expand uses this; it wins when both
params are present). Ordered `:started-at` desc. **`?limit`** is clamped
to `1–100`, defaulting to `20` when absent or non-numeric. Summary shape
(no nested args). Editor's History panel calls this.

## Concurrency caps

Two caps gate how many executions run at once, with different scopes
**and different overflow behaviour** (see
`crud.fn-execution.persist/acquire-execution-slot!` and
`make-execution-pool`):

| Cap | Env | Default | Scope | Overflow |
|-----|-----|---------|-------|----------|
| Global | `GRAPHDEN_MAX_CONCURRENT_EXECUTIONS` | 128 | Per-POD. Sizes the bounded execution pool's worker count. | **Parks in the pool queue** (bounded `LinkedBlockingQueue`); it no longer rejects at admission. Only when workers AND queue are both full does submit throw → `503` + `Retry-After` `{:reason :queue-full}`. |
| Per-org | `GRAPHDEN_MAX_CONCURRENT_EXECUTIONS_PER_ORG` | 32 | **Fleet-wide for tenants** (counts pending `:fn-execution` rows in shared storage, so N pods share one budget); per-pod atom for the public/platform org. | **Rejects at admission**, no row or future created: `{:ok false :status :rejected :error-data {:reason :over-capacity}}`. |

The per-org cap is the admission gate. The global cap moved to the bounded
pool: it queues work (`park-then-503`) rather than dropping it, and the
per-pod bound remains the exact thread-exhaustion safety net. See
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

Implementation: `sweep-executions! pool now` (in
`graphden.crud.fn-execution.retention`; `graphden.system.init.cleanup` owns
only the cadence). The `now` argument is injectable so tests can verify TTL
behaviour without sleeping real time.

The sweep is four set-based statements — zombie `UPDATE`, TTL `DELETE`, then
two anti-join `DELETE`s that reclaim `:fn-execution-arg` /
`:fn-execution-arg-item` rows whose parent is gone. Those child tables carry
no foreign key (`:ref` fields become plain indexed UUID columns), so nothing
else removes them; the anti-join also clears rows detached before this sweep
existed.

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

The editor's **Errors** sidebar section lists the current branch view's
**unresolved** recent failures (newest first) straight off the `:fn-execution`
audit rows — no new storage beyond two columns (`:branch-id` stamped at
create, `:acknowledged-at` set by dismiss). Privacy holds because the write
path already sanitised each row: `redact-outcome` hides secret-tainted
bodies, `scrub-outcome` replaces internal error types with an opaque `ref:`
on the cloud. Rows render a ✕ dismiss button, the fn name as a native `#hash`
link (the editor's hashchange navigation), the finish time, the error text,
and a collapsible ex-data block; the list header carries **Dismiss all**.

A failure counts as unresolved only while ALL of these hold (the panel is a
worklist — every red counter has an action that clears it):

- the run happened on the current branch or one of its ancestors
  (branch-chain; `branch_id IS NULL` pre-feature rows show everywhere) —
  siblings never see each other's failures, a parent never sees a child's;
- the failing `fn-version` is still what the branch resolves for that fn —
  shipping a fix, a branch-local override, or deleting the fn clears it;
- no later `succeeded` run of the same version exists — a clean re-run
  clears a transient failure;
- the row wasn't dismissed (`POST /partials/error-log/ack?id=X` /
  `/partials/error-log/ack-all`, both auth-required; they respond with the
  refreshed body for the htmx swap).

Backed by the `:recent-failures` / `:failure-ack` / `:failure-ack-all`
base-fns (`graphden.crud.fn-execution.errors`) — raw SQL with an explicit
org filter, 7-day window, 50-row cap; the still-current-version half of the
predicate goes through the versioned resolver. The audit rows themselves
keep their TTLs (failed rows sweep after 30 days) regardless of dismissal.

## Editor UI

The ▶ button on a fn-card root row (auth-required, visible only to
signed-in users) opens the **Run pane** — it selects the fn and lands
the right inspector on its **Runs** tab, so the canvas stays fully
visible (and pannable) next to the form, the result, and the fn's
run history below:

- One type-aware form per free-arg, loaded via `/api/value-form` —
  same widgets that drive binding edits.
- Red **effects banner** when `:declared-effects ≠ #{}`, listing one
  chip per category (db / env / io / network / time / random).
- **Effect-confirm gate** — checkbox "I understand this will produce
  side effects" required before Run when effects exist; pure fns skip.
- **Persist toggle** — "Save to history". Pre-checked + disabled for
  effect-bearing fns (backend auto-persists those).
- **Run** → POST `/api/execute` → response handled:
  - `:succeeded` → inline type-aware result pane. Dispatch order in
    `:_er-succeeded-body` (`app/execution/fns.edn`): truncated → nil
    → **typed repr** (`:_value-repr-registry`, § below) →
    **component preview** (declared `:hiccup-node` return →
    sandboxed iframe) → HTML-response iframe → > 50 KB JSON
    truncation → list bullets / record table / scalar chip.
  - `:pending` → spinner + execution-id + polling with exponential
    backoff (500 ms → 1 s → 2 s → 5 s → 30 s) + Cancel button.
  - `:failed` → error pane with message + ex-data.
- **History toggle** in the header — lazily fetches `/api/executions`
  and renders a collapsible panel. Each row: status chip + time +
  duration + result preview. Click expands full result; **Repeat**
  refills the form widgets with that run's args. Rows whose execution
  stored a `:path-trace` additionally carry **path** (aggregate
  canvas highlight, `editor-path-view.js`) and **tree** (step-through
  call tree, below) buttons.

Source: `resources/packages/app/editor/editor-execute*.js`.

## Typed value representations

The result pane's first non-trivial dispatch tier is the
**value-repr registry** — the read-side sibling of the value-form
system: `:_value-form-registry` answers "how do I EDIT a value of
this type", `:_value-repr-registry` (`app/reprs/fns.edn`) answers
"how do I SHOW one".

- **Registry** — a `:const` list of `[type-name repr-fn-name]` pairs
  (vector type-name = structural key, `["list" "numeric"]` →
  `[:list :numeric]`), matched most-specific-first by `subtype?` —
  the same shared-list pattern and matching code
  (`crud.value-form/pick-form-fn`) as the form registry. Adding a
  repr = one fn-def + one registry row; no Clojure change.
- **Dispatch type** — the executed fn's declared/inferred RETURN
  type from the id-keyed rich-types registry (`:fn-id` reaches the
  partial via a read-time fn-version join in `get-execution` for
  persisted rows, and via an editor-stamped body field on the
  inline `POST /partials/execute-result-inline` path). When the
  registry knows nothing narrower than `:any`, the runtime value's
  literal classification is the fallback — so a numeric list from
  an untyped ad-hoc fn still sparklines.
- **Repr fns are pure `value → hiccup`** — enforced at runtime, not
  by declaration: the repr subtree executes under
  `:allowed-effects #{}`, so the first `record-effect!` of any
  category throws and the pane falls back to shape-based rendering.
- **Output is sanitized** (`graphden.web.hiccup-sanitize`) before
  the editor inlines it: allowlist of presentation + SVG tags, fixed
  attribute allowlist (no `on*`, no `hx-*`/`data-*` — the pane runs
  `htmx.process` — no URL-bearing attributes, paint values checked),
  `h-raw` RawStrings collapse to escaped text, bounded depth/size.
  Repr output is editor-DOM content, i.e. the same stored-XSS
  surface class as `:resource-override` — the sanitizer keeps the
  property independent of who authored the repr fn.
- Shipped reprs: `_repr-numeric-list` — an SVG sparkline (240×48,
  min..max scaled, stride-downsampled via `svg-polyline-points`)
  plus a value-count caption, registered for `["list" "numeric"]`;
  `_repr-record-list` — a real TABLE for a list of keyword-keyed
  records (columns from the first record's sorted keys, complex
  cells as JSON, 50-row cap with a visible count), registered for
  `["list" "keyword-map"]` — the everyday storage-query /
  API-selection result stops rendering as a bullet list of JSON
  dumps; `_repr-asset-tag` — for `:script-tag` / `:style-tag`
  returns (the `web.html` page-asset refinements of `:hiccup-node`,
  TYPES.md § Named types): a `‹script› tag` caption + the tag's
  source body in a `<pre>` (`src:` line for bundle-loading tags) —
  an invisible-by-nature element would otherwise reach the
  component-preview iframe and render as a blank box;
  `_repr-source-text` — a formatted `<pre>` for `:js-source` /
  `:css-source` strings (one shared target for both rows: two
  constraint-less refinements over `:text` are structurally
  identical under `subtype?`, so per-language rows could not
  dispatch deterministically).

**Component preview** is the neighbouring tier, not a registry row:
when the fn's declared return type is semantically `:hiccup-node`
(mutual-`subtype?` equality — one-way `⊆` would swallow every
scalar, `:hiccup-node` being a union), the result renders as markup
in a fully sandboxed iframe (`sandbox=""` — no scripts, no
same-origin; unlike the HTML-response pane's `allow-scripts`). This
is the static half of a devcards-style component preview. The
narrowed `:script-tag` / `:style-tag` returns deliberately fail the
mutual-equality test (refinement ⊂ base, never ⊇) — a page asset is
not a visual component, and its repr above wins the dispatch anyway.

## Interactive component preview — `GET /preview`

The dynamic half: the component-preview caption carries an **"Open
interactive preview ↗"** link to `GET /preview?fn-id=<uuid>`
(`&branch=` preserved), which renders the component as a REAL page —
components stylesheet, htmx, and the runtime dispatcher scripts all
live — in a **new tab**. A tab keeps the editor DOM out of reach,
avoids the global `frame-ancestors 'none'` / `X-Frame-Options: DENY`
headers entirely (no framing), and authenticates like any page
navigation (the session cookie under accounts; an auth-off self-host
is open by the same `:get-auth-required` seam).

- **Self-host only.** Under an active tenancy addon the route
  answers 403 and the editor hides the link (the same
  affordance+route double gate as the Assets panel): the page would
  run org-authored markup and scripts on the EDITOR origin with the
  viewer's session in scope — the stored-XSS class the
  `:resource-override` "cloud writes are system-only" rule and the
  apps-domain split exist to prevent. The cloud path is a future
  apps-domain preview owned by the tenancy addon.
- **The render is a standard execute-pipeline run**
  (`:_execute-validation` + `:_execute-apply` over a query-built
  parsed map), so the type-error gate, the capacity cap and the
  effectful-run auto-persist audit trail apply unchanged.
- **Effect-confirm mirror of the Run gate**: a component whose
  declared effects are non-empty — or UNKNOWN (no rich-types entry;
  the preview auto-executes on page load, so unknown fails closed) —
  gets a confirm page with the Run pane's effect chips and a
  "Render anyway" link carrying `effects=confirm`.
- Non-components (return type not semantically `:hiccup-node`)
  get a 400; unknown fn-ids a 404.
- Everything is graph-composed fn-defs (`:_pv-*` in
  `app/editor-execute/fns.edn`) — zero new base-fns.

**Components gallery** — `GET /preview` with NO `fn-id` (devcards
L3): one card per named fn whose return type is semantically
`:hiccup-node`, sorted by namespace-path + name, capped at 60 with a
visible overflow count. A PURE component with zero free args renders
LIVE inside its card (per-card `:_execute-apply`; inline pure runs
write no rows); effectful/unknown or parameterised components show
their effect chips / a hint and defer to their single-fn page (where
the confirm gate applies). Same self-host-only 403 gate. Missing vs
malformed `fn-id` is decided on the RAW query param — absent serves
the gallery, present-but-unparseable is a 400. The `:filter` /
`:sort-by` callbacks stay pure per the HOF contracts; the effectful
lookups live in `:map` callbacks (`:_pvg-*`).

Tests: `graphden.packages.app.value-repr-test`,
`graphden.web.hiccup-sanitize-test`,
`graphden.packages.app.preview-page-test`.

## Path trace — the call tree

A `trace?` submission records one entry per `:ref` invocation into
the row's `:path-trace` jsonb (`capture-values?` additionally stores
each non-hidden frame's return, 4 KB/entry). Every entry carries
`:seq` (entry-order frame number) and `:parent-seq` (the frame that
forced it), so the completion-ordered flat vector reassembles into
the **call tree**.

- `GET /partials/execute-trace?id=X` renders the tree server-side —
  one row per frame in depth-first order, fn names joined on the
  current branch, chips for duration / `cache` / `secret` /
  `unknown type`, collapsible value viewers. Frames whose parent
  entry was truncated away (byte cap, 10k entry cap) surface as
  roots instead of vanishing.
- The editor opens it from a history row's **tree** button or the
  Debug panel's «open last captured trace»; `editor-trace-view.js`
  owns row-click / ◀ ▶ / arrow-key stepping with canvas highlight.

Secret handling is capture-time AND read-time:

- capture classifies every frame via the registry
  (`registry/trace-capture-class`) — a secret-touching frame records
  `{:hidden :secret}` (value never read), a frame with **no**
  registry entry fails CLOSED as `{:hidden :unknown-type}`;
- a `:secret`-returning (or unknown) frame **poisons** every open
  ancestor — consumers of its output record
  `:value-hidden :secret-derived` instead of a value;
- every read of a stored trace re-redacts through the CURRENT
  registry (`persist/re-redact-path-trace`) — a fn that became
  secret after the run stops serving its historical values, and the
  ancestor chain re-poisons via the stored `:parent-seq` links.

## Debug: catch next request

One-shot, TTL-bounded trap on a branch's web handler
(`graphden.crud.debug-capture`; hook in
`branch-router/ring-callable-for-ctx`, unarmed cost = one atom deref).
While armed, the **next matching HTTP request** runs with the path
trace bound (optionally value capture) and persists a standard
`:fn-execution` row: the sanitized request as the handler's arg
(credential headers — `authorization`, `cookie`, `x-api-key`, … —
are stripped BEFORE anything is stored; body capped at 64 KB), the
`Set-Cookie`-stripped Ring response as the result, the call tree as
`:path-trace`. The captured run then replays through the same
history/trace UI as any traced run.

- `POST /api/debug/catch` — arm (replace). Body `{path-prefix?,
  capture-values?, ttl-ms?}`. No prefix = catch-all **minus** the
  editor-infra paths (`/api/`, `/partials/`, `/assets/`, `/events/`,
  `/auth/`, `/version`) so the editor's own polling can't consume the
  trap; an explicit prefix targets whatever it names. TTL default
  10 min, cap 1 h.
- `POST /api/debug/catch/cancel` — disarm.
- `GET /api/debug/catch/status` — `{armed, trap,
  last-captured-execution-id}`.

Scoping: one trap per `(org, branch)`, armed and fired under the
SAME org (a trap can never fire on another org's request). The
registry is runtime-only in-process state (the `*traced-fn-ids*`
doctrine — no stored field; restart disarms). Org-keyed sharding
routes an org's requests to its own pod, so arming editor and
captured request meet on one process by construction.

Editor surface: the diagnostics drawer's **Debug** tab
(`/partials/debug-catch` +
`editor-debug.js`) — arm form (path prefix, capture-values behind
the explicit confirm), armed status with Cancel, «open last captured
trace».

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

The editor's Run pane surfaces the runtime set as a "ran:"
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
| `test/graphden/crud/fn_execution_test.clj`                | parse / validate (rejection reasons) / apply (inline + persisted + args rows) / get / cancel (flag + real interrupt) / list (branch-scoped + per-version + `?limit` clamp) / bounded-pool queue-full → 503 / per-org over-capacity / TTL sweep (incl. `as-instant` Date regression) / failed-path / args-too-large / result-truncation / exec-stats rollups |
| `tools/browser-test/edit-execute.test.js`                 | E2E: ▶ Run pane, fill args, Run, inline result, history row reveal |

Total: ~59 deftests / ~267 assertions backend, 8 assertions browser.
