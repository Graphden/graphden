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
| `▶` button on the fn-card | Interactive — you click + view the result |
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

When you click `▶` on a fn with EFFECTS, the popover shows a
gate row:

```
This fn will: 🌐 network, 💾 db. Continue?
[ Cancel ]  [ Run anyway ]
```

The gate prevents accidental side effects. For pure fns (no
effects in the registry) the gate doesn't show — `▶` runs
immediately.

## The persist toggle

By default, execute results are kept in memory only — visible
for the next few minutes (TTL), then garbage-collected.

Flip the `Persist` checkbox in the popover and the result
writes a `:fn-execution` row carrying:

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

Below the free-arg form, the popover has a `History` section
listing recent executions of this fn — both persisted and
in-memory-not-yet-gc'd. Each row shows:

- The args used
- The status (`succeeded` / `failed` / `cancelled` / `running`)
- The result (truncated to a one-liner)
- A `Repeat` button — re-fills the form with the same args so
  you can re-run

History is per-fn (across branches). The persist toggle is what
controls whether a result survives the in-memory TTL.

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
curl http://localhost:8080/api/executions/<id> ...
```

Cancel:
```bash
curl -X POST http://localhost:8080/api/executions/<id>/cancel ...
```

The shape of `:result` mirrors the in-memory return value —
JSON-encoded.

## TTL — what gets garbage-collected

Non-persisted executions live in an in-memory atom with a
configurable TTL (default 30 minutes). After the TTL elapses,
the row is dropped. Persisted executions are immune — they
live in PG until you delete them.

Two side effects of this:

1. The history panel for a heavily-used fn shows the
   persisted + recent-in-memory mix. Older non-persisted runs
   drop off without notice.
2. The TTL also bounds the cancel window — once a row's
   garbage-collected, you can't cancel an execution you no
   longer have a handle for.

## Try it (the persist + history loop)

1. On `:str-len`, run with `:string = "hello"` (no persist).
   See the result + a fresh history row.
2. Run again with `:string = "world"` (also no persist).
3. Toggle `Persist`. Run with `:string = "graphden"`.
4. Refresh the page. The first two runs are gone; the third
   (persisted) is still in history.
5. Click `Repeat` on the persisted row — the form pre-fills,
   you run again, get the same result.

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
