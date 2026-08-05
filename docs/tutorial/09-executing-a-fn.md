# Lesson 09 — Executing a fn: free-arg form, history, cancel

**Goal**: by the end of this lesson you can click `▶` on any
fn-card, supply its free arguments, inspect the result, replay
from history, and cancel a running execution.

**Concepts introduced**: `execute`, `free-arg form`, `effect-gate`,
`persist toggle`, `execute history`, `repeat`, `cancel`, `TTL`.

## Two modes — one-shot vs supervised

Lesson 10 introduces SERVICES — fns graphden keeps running
forever. This lesson is about the OTHER mode: one-shot
execution. You click `▶`, the fn runs, you see the result, the
runtime moves on. No supervision, no restart.

Run paths:

| Path | When to use |
|---|---|
| `▶ Run` in the row's `⋯` actions popover | Interactive — you click + view the result |
| `POST /api/execute` | Programmatic — scripts / curl / other services |
| Internal calls (refs in fn-graph) | Happens automatically when one fn refs another at runtime |

`▶` and `/api/execute` are the same code path; the button just
builds the request body for you.

## The free-arg form

If the fn has any FREE arguments (slots that no ancestor binds —
see lesson 04), the execute popover shows a form to supply
them. Field types match the slot's declared type:

- `:text` slot → text input
- `:int` slot → number input
- `:bool` slot → checkbox
- `:port` slot → number input + the refinement's range hint
- Record / list slots → nested form (see lesson 05)
- `:fn`-typed slot → fn-picker

Live validation runs as you type — `✓ OK` or `✗ <reason>` next
to the input. Submit is disabled until every required field is
valid.

### Try it

1. Open `:str-len` in the editor. It has one free arg
   `:string` (declared :text). Click `▶`.
2. The popover shows a `:string` field with the hint
   `Expected: text`. Type `hello world`.
3. Click `Run`. Result `11` appears.

If you create a tutorial fn-def with multiple free args, the
form lists them all. The card's free-arg strip at the bottom
mirrors the form so you can see what's needed at a glance.

## The effect gate

Every fn carries a set of effects it transitively touches
(computed from the impl + propagation through refs). Categories:

| Effect | Means |
|---|---|
| `:db` | Writes / reads graphden's storage |
| `:network` | Outbound HTTP / TCP |
| `:io` | Disk / filesystem |
| `:env` | Reads OS env vars |
| `:time` | Reads wall-clock time |
| `:random` | Non-deterministic input |
| `:process` | Spawns supervised background work (service-eligibility marker) |
| `:state` | Mutates in-graph state (`:swap` / `:reset` on a `:cell` / `:atom`) |
| `:raw-sql` | Raw SQL escape hatches (`:pg-query` & co) that bypass the storage protocol |

When you open the run popover for a fn with EFFECTS, it shows
a warning banner — `side effects:` followed by one chip per
category — plus a confirm checkbox:

```
side effects: [network] [db]
[ ] I understand this will produce side effects
[Run]   ← disabled until the box is ticked
```

The gate prevents accidental side effects: Run stays disabled
until you tick the acknowledgement. For pure fns (no effects
in the registry) neither the banner nor the checkbox appears —
the popover opens straight to the form (or the "No free
arguments" note) with Run enabled.

## The type-error gate

The effect gate's sibling: a fn whose current branch carries
recorded TYPE diagnostics (the ⚠ badge / "Type errors" panel —
Lesson 03) is refused at submit. The run comes back rejected
with a clear message — "Execution refused: fn '…' has unresolved
type errors — …" — naming the fn and the first error. There's
nothing to acknowledge away here: fix the fn or its bindings
(the fixing save clears the diagnostic), then run. This is the
flip side of type errors not blocking saves: you can keep a
half-typed sketch in the graph, but it won't execute.

## The persist toggle

By default, PURE runs are kept in memory only — visible for
the next few minutes (TTL), then garbage-collected. Tick the
`Save to history` checkbox in the popover and the result
writes a `:fn-execution` row.

Effectful runs don't get a choice: the checkbox comes
pre-ticked and locked (*"Automatically saved — runs that
produce side effects are always persisted for audit trail"*).
The persisted row carries:

- `:fn-id` + `:fn-version-id` (frozen at start time so the
  audit trail survives later fn-def edits)
- `:args` (the resolved free-arg values, capped at 256 KB)
- `:result` (capped at 5 MB; oversize results write a placeholder
  with a download link)
- `:effects` (the actual effect set the runtime saw, NOT the
  declared one — drift between the two surfaces in the editor)
- `:error` + `:error-data` on failure (capped at 4 KB)

Persisted executions show up in the fn-card's history panel
(see below) and survive restarts.

## The history panel

The popover header has a `History` toggle; clicking it fetches
and expands a panel listing this fn's PERSISTED runs (in-memory
non-persisted runs never appear there). Each row shows:

- The args used
- The status (`succeeded` / `failed` / `cancelled` / `running`)
- The result (truncated to a one-liner)
- A `Repeat` button — re-fills the form with the same args so
  you can re-run

History is per-fn (across branches). `Save to history` is what
controls whether a pure run's result survives the in-memory TTL
— effectful runs are always there.

## Cancel

Long-running executions (an `:http-get` that hangs, a
`:sleep` for 30 minutes) can be cancelled:

- From the popover during the run — a `Cancel` button appears
  in place of `Run`.
- From the history panel later — the running row has a `Cancel`
  action.

Cancel sets a flag the executor checks at each ref boundary
(`*cancel-check*`). Already-running impls don't get
interrupted in flight, but no new sub-ref starts after cancel
fires. For most fns this means a clean rollback; for impls
that spawned external work (HTTP, threads) the cleanup is
impl-specific.

## Programmatic execute

```bash
curl -X POST http://localhost:8080/api/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -d '{"fn-name": "str-len", "args": {"string": "hello"}}'
```

Returns immediately with `{:execution-id "..." :status "running"}`
when the fn is long-running; for fast fns the response carries
the result directly.

Poll status:

```bash
curl http://localhost:8080/api/execute/<id> ...
```

Cancel:

```bash
curl -X POST http://localhost:8080/api/execute/<id>/cancel ...
```

The shape of `:result` mirrors the in-memory return value —
JSON-encoded.

## TTL — what gets garbage-collected

Non-persisted executions live in an in-memory atom with a
configurable TTL (default 30 minutes). After the TTL elapses,
the row is dropped. Persisted executions are immune — they
live in PG until you delete them.

Two side effects of this:

1. Only persisted runs appear in the History panel — a pure
   run without `Save to history` leaves no visible trace once
   its in-memory row expires.
2. The TTL also bounds the cancel window — once a row's
   garbage-collected, you can't cancel an execution you no
   longer have a handle for.

## Try it (the persist + history loop)

1. On `:str-len`, run with `:string = "hello"` (no persist).
   See the result + a fresh history row.
2. Run again with `:string = "world"` (also no persist).
3. Tick `Save to history`. Run with `:string = "graphden"`.
4. Refresh the page. The first two runs are gone; the third
   (persisted) is still in history.
5. Click `Repeat` on the persisted row — the form pre-fills,
   you run again, get the same result.

## Tracing an execution

The execute popover also has a `Trace path` checkbox (off by
default — tracing adds a small capture cost to the run). When
checked, the run records which fns it traversed: one entry per
internal fn call, with the time each call took and whether it
was served from the per-run cache.

Try it:

1. Open ▶ on a composed fn (anything that references other
   fns — `:str-len` wrapped in your own fn-def works).
2. Tick `Trace path` (and `Save to history` if you want the
   trace to survive the page).
3. Run. Under the result you get a `Show path on canvas`
   button — click it.
4. The traversed fn cards light up with a blue ring, each
   wearing a small badge: `12ms` (time spent in that fn),
   `3× 12ms` (called 3 times, 12 ms total — loops and shared
   subtrees re-enter the same fn), or `cache` (the result was
   reused, no time spent).
5. A small panel at the bottom of the screen summarises the
   path. If the run traversed fns that aren't currently drawn
   on the canvas, it says `not on canvas: N fns` — hover it
   for their names. Click `✕ clear` (or navigate anywhere) to
   restore normal rendering.

Persisted traced runs keep their path: in the History panel,
rows with a recorded path show a `path` button that replays
the same highlight.

### Capturing values

By default the trace records only fn ids, timings and cache
flags — never the data flowing through. When you need the
data too, there is a second step:

1. Tick `Trace path` first. That unlocks the `+ capture
   values` checkbox next to it (it stays greyed out
   otherwise).
2. Tick `+ capture values`. A confirmation dialog appears
   with an estimated cost line — something like `Estimated
   cost: up to ~48 KB (~12 fns in this fn's reach)`. This is
   the expensive mode, so graphden asks explicitly; declining
   the dialog unticks the box.
3. Run, then `Show path on canvas`. Traversed cards now wear
   a second chip under the timing badge: `= value`. Click it
   to see that fn's captured return value, pretty-printed.
   When a fn ran several times, you see the last value (the
   popover says `Last of N captured invocations`).

Limits you may run into, each reported rather than silent:

- A single value larger than 4 KB is not captured — the chip
  shows `= 4KB+` and its popover explains the cap.
- If all captured values together exceed the total budget
  (16 MB), the oldest entries are dropped first and the
  bottom panel says `some values dropped`.
- A fn that touches `:secret`-typed data (lesson 07) shows a
  red `secret` badge, no timings and **no value chip** — its
  value is never even read by the capture machinery, in
  either mode.

What the trace never contains:

- **Values, unless you explicitly confirmed capture.** A
  plain `Trace path` run records fn ids, timings and cache
  flags only.
- **Secrets.** The capture pipeline redacts secret-touching
  fns at record time, values included.

## What we glossed over

- **Branch-aware execution** — the active branch picks which
  version of the fn-graph runs. Lesson 08 (already written)
  covers branches.
- **Service-mode execution** — fns marked as services run
  forever, supervised by graphden. Lesson 10.
- **HOF call shape** — how internal refs get their free args
  bound at compile time vs call time. Lesson 06.

## Next

Lesson 10 — Services ([already written](10-services.md))
