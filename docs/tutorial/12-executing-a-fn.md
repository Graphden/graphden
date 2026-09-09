# Lesson 12 — Executing a fn: free-arg form, history, cancel

**Goal**: by the end of this lesson you can click `▶` on any
fn-card, supply its free arguments, inspect the result, replay
from history, and cancel a running execution.

**Concepts introduced**: `execute`, `free-arg form`, `effect-gate`,
`persist toggle`, `execute history`, `repeat`, `cancel`, `retention`.

## Two modes — one-shot vs supervised

Lesson 32 introduces SERVICES — fns graphden keeps running
forever. This lesson is about the OTHER mode: one-shot
execution. You click `▶`, the fn runs, you see the result, the
runtime moves on. No supervision, no restart.

Run paths:

| Path | When to use |
|---|---|
| `▶ Run` in the row's `⋯` actions popover | Interactive — you click + view the result |
| The Inspector's **Runs** tab (right panel) | The selected fn's own run history, live — there's no separate Run page; running is always the ▶ action, history lives here |
| `POST /api/execute` | Programmatic — scripts / curl / other services |
| Internal calls (refs in fn-graph) | Happens automatically when one fn refs another at runtime |

`▶` and `/api/execute` are the same code path; the button just
builds the request body for you.

## The free-arg form

If the fn has any FREE arguments (slots that no ancestor binds —
see lesson 04), the Run pane shows a form to supply
them. Field types match the slot's declared type:

- `:text` slot → text input
- `:int` slot → number input
- `:bool` slot → checkbox
- `:port` slot → number input + the refinement's range hint
- Record / list slots → nested form (see lesson 05)
- `:fn`-typed slot → fn-picker

Live validation runs as you type — a `✓` or `✗ <reason>` marker
next to the input. The marker is advice, not a lock: the server
re-checks the args on submit and rejects a mismatch outright.

### Try it

1. Open `:str-len` in the editor. It has one free arg
   `:string` (declared :text). Click `▶`.
2. The Run pane opens in the right panel's **Runs** tab, with one
   field named after the free slot, `:string`. Type `hello`.
3. Click `Run`. Result `5` appears.

If you create a tutorial fn-def with multiple free args, the
form lists them all. The placeholder `+` edges on the card
mirror the form so you can see what's needed at a glance.

## Typed result representations

The result pane doesn't always print text — it first asks *what
type* the result is and picks a representation:

- A **numeric series** (`[:list :numeric]` — ints or floats)
  renders as an inline **sparkline** with a value-count caption
  instead of a bullet list.
- A **list of records** (keyword-keyed maps — a storage query, an
  API selection) renders as a **table**: columns from the first
  record's keys, a visible row-count caption.
- A fn whose return type is **`:hiccup-node`** (a component — a
  form, a card, anything you'd normally insert into a page)
  renders as a **Component preview**: the markup, live, inside a
  fully sandboxed frame (no scripts, no access to your session).
  You see what the component looks like without inserting it
  anywhere.
- Everything else falls back to the shape panes you'll see below
  (list bullets / record table / scalar).

The dispatch runs on the fn's declared or inferred return type —
the same type you see in the inspector's RETURNS strip — with the
runtime value's shape as a fallback, so even an untyped sketch
returning `[3 1 4]` sparklines.

### Try it

1. Open `:range` (core.collections) and click `▶`. Set `end` to
   `12`, leave `start`/`step` at their defaults.
2. Run. Instead of twelve bullet rows you get a rising sparkline
   and the caption `12 values`.
3. Create a component fn-def: **New fn** → name `hello-card`,
   parent `:wrap-element`, bind `:tag` to `div` and `:content` to
   `hello from a card`.
4. Click `▶` → Run on `hello-card`. The pane shows **Component
   preview** — your `<div>` rendered as markup in a sandboxed
   frame, because the fn's inferred return type is `:hiccup-node`.
5. **Self-host only:** open `/preview` with no parameters — the
   **components gallery**: every `:hiccup-node`-returning fn as a
   card, pure zero-arg components rendered live, the rest one click
   away.
6. **Self-host only:** the preview caption also carries **"Open
   interactive preview ↗"** — it opens `/preview?fn-id=…` in a new
   tab, where the component runs as a REAL page: htmx swaps fire,
   forms submit, custom scripts run. An effectful component first
   shows a confirm page mirroring the Run gate. (On multi-tenant
   cloud this link is hidden and the route answers 403 — live
   org-authored scripts must not run on the editor origin; that's
   what the apps domain is for.)

Representations are themselves graph code: the type→repr table is
the `:_value-repr-registry` fn-def (`app.reprs` namespace), each
repr a pure `value → hiccup` fn-def whose output is sanitized
before the editor inlines it. On a self-hosted deployment an admin
extends the system by adding a repr fn-def plus one registry row —
no server change. (On cloud the shipped registry is read-only for
tenants.)

## The effect gate

Every fn carries a set of effects it transitively touches
(computed from the impl + propagation through refs) — one keyword
per category, `:db`, `:network`, `:env` and the rest; lesson 13
lists all ten and explains where they come from.

When you open the Run pane for a fn with EFFECTS, it shows
a warning banner — `side effects:` followed by one chip per
category — plus a confirm checkbox:

```text
side effects: [network] [db]
[ ] I understand this will produce side effects
[Run]   ← disabled until the box is ticked
```

The gate prevents accidental side effects: Run stays disabled
until you tick the acknowledgement. For pure fns (no effects
in the registry) neither the banner nor the checkbox appears —
the pane opens straight to the form (or the "No free
arguments" note) with Run enabled.

## The type-error gate

The effect gate's sibling: a fn whose current branch carries
recorded TYPE diagnostics (the ⚠ badge / the Explorer's ⚠ type
errors lens — Lesson 03) is refused at submit. The run comes back rejected
with a clear message — "Execution refused: fn '…' has unresolved
type errors — …" — naming the fn and the first error. There's
nothing to acknowledge away here: fix the fn or its bindings
(the fixing save clears the diagnostic), then run. This is the
flip side of type errors not blocking saves: you can keep a
half-typed sketch in the graph, but it won't execute.

## The persist toggle

By default, PURE runs are not stored: the result shows in the
pane and that is all — no row is written, so there is nothing to
come back to after a reload. Tick the `Save to history` checkbox
in the pane and the run writes a `:fn-execution` row.

Effectful runs don't get a choice: the checkbox comes
pre-ticked and locked (*"Automatically saved — runs that
produce side effects are always persisted for audit trail"*).
The persisted row carries:

- `:fn-id` + `:fn-version-id` (frozen at start time so the
  audit trail survives later fn-def edits)
- `:args` (the resolved free-arg values, capped at 256 KB)
- `:result` (capped at 5 MB; an oversize result is stored as `nil`
  with `:result-truncated? true`)
- `:effects` (the actual effect set the runtime saw, NOT the
  declared one — drift between the two surfaces in the editor)
- `:error` + `:error-data` on failure (capped at 4 KB)

Persisted executions show up in the runs list under the form
(see below), survive restarts, and are swept by retention (below).

## The history list

The Runs tab doubles as this fn's history: below the form sits
the list of its PERSISTED runs (a run without `Save to history`
leaves no row, so it never appears there). Each row shows:

- The args used
- The status (`succeeded` / `failed` / `cancelled` / `pending` —
  a still-running persisted row is `pending` until it resolves)
- The result (truncated to a one-liner)
- A `Repeat` button — re-fills the form with the same args so
  you can re-run

History is per fn, for the version the current branch resolves —
the one `▶` would run now. `Save to history` is what decides
whether a pure run gets a row at all — effectful runs are always
there.

## Cancel

Long-running executions (an `:http-get` that hangs, a
`:sleep` for 30 minutes) can be cancelled:

- From the Run pane during the run — a `Cancel` button appears
  while the run is pending.
- Programmatically — `POST /api/execute/<id>/cancel` (below).

Cancel sets a flag the executor checks at each ref boundary
(`*cancel-check*`). Already-running impls don't get
interrupted in flight, but no new sub-ref starts after cancel
fires. For most fns this means a clean rollback; for impls
that spawned external work (HTTP, threads) the cleanup is
impl-specific.

## Programmatic execute

```bash
curl -X POST http://localhost:9002/api/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -d '{"fn-name": "str-len", "args": {"string": "hello"}}'
```

Returns immediately with `{:execution-id "..." :status "pending"}`
when the fn is long-running; for fast fns the response carries
the result directly.

Poll status:

```bash
curl http://localhost:9002/api/execute/<id> ...
```

Cancel:

```bash
curl -X POST http://localhost:9002/api/execute/<id>/cancel ...
```

The shape of `:result` mirrors the in-memory return value —
JSON-encoded.

## Retention — what gets swept

Persisted rows are not kept forever. An hourly sweep deletes
`succeeded` and `cancelled` runs after **7 days** and `failed`
runs after **30 days**; a row stuck in `pending` for over an hour
is flipped to `cancelled` first and then follows the 7-day rule.

Two consequences:

1. Only persisted runs appear in the Runs tab — a pure run
   without `Save to history` leaves no row, so there is nothing
   to `Repeat` later.
2. Cancel needs a pending row. A run that outlives the inline
   wait is written as `pending` (whether or not you ticked the
   box) and hands back its id — that id is what the `Cancel`
   button and `/cancel` act on.

## Try it (the persist + history loop)

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=12)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

1. On `:str-len`, run with `:string = "hello"` (no persist).
   See the result; the runs list under the form does not change.
2. Run again with `:string = "world"` (also no persist).
3. Tick `Save to history`. Run with `:string = "graphden"`.
4. Refresh the page. The first two runs left no trace; the third
   (persisted) is in the list.
5. Click `Repeat` on the persisted row — the form pre-fills,
   you run again, get the same result.

## Tracing an execution

The Run pane also has a `Trace path` checkbox (and, behind it,
`+ capture values`): the run records which fns it traversed, and
optionally what each returned. That is lesson 15's subject — the
canvas highlight, the step-through call tree, and the capture caps.

## What we glossed over

- **Branch-aware execution** — the active branch picks which
  version of the fn-graph runs. Lesson 20 covers branches.
- **Service-mode execution** — fns marked as services run
  forever, supervised by graphden. Lesson 32.
- **HOF call shape** — how internal refs get their free args
  bound at compile time vs call time. Lesson 06.

## Next

[Lesson 13 — Effects and the `:secret` type-marker](13-effects-and-secrets.md)
