# Lesson 29 — When something breaks: the two error panels

**Goal**: know where to look when a run fails and when an edit
doesn't type-check — and know why those are two different lists.

**Concepts**: the diagnostics bar's **Errors** tab (recent failed
runs) and **Type errors** tab (recorded diagnostics for this branch),
runtime failure vs static diagnostic, error-tolerant writes.

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=29)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

## Two kinds of wrong

Graphden separates them on purpose, and each has its own panel:

| | **Errors** | **Type errors** |
|---|---|---|
| What it lists | runs that FAILED | edits that don't type-check |
| When a row appears | at run time | at write time |
| Scope | your org, last 7 days | the branch you are on, right now |
| Cleared by | time (the audit rows expire) | fixing the binding |

A type error is not a failed run: the write **succeeded**, the fn is in
the graph, and the diagnostic rides alongside it (lesson 05 — "a
diagnostic, not a wall"). A failed run is the opposite: nothing was
written, something was attempted and threw.

Both live in the **diagnostics bar** under the canvas — a collapsible
drawer whose tabs (Errors, Type errors, Tests, Debug) open without
leaving the editor, so a fn link in a row selects it on the canvas
while the list stays open.

## Errors — the last things that failed

The **Errors** tab lists your org's recent failed executions, newest
first. Each row is:

```
eprobe                              ← the fn, as a link
2026-08-22 09:49:35                 ← when it finished
Malformed JSON.                     ← the error message
▸ details                           ← the error data, expandable
```

Three things are worth knowing about that list:

**It only holds runs you asked to keep.** A transient run — the plain
▶ with "Save to history" unticked (lesson 09) — leaves no audit row, so
a failure you did not persist never reaches this panel. Effectful runs
persist by themselves; pure ones are yours to keep.

**The fn name is a link.** Clicking it selects that fn on the canvas,
which is usually the next thing you want: read the bindings that
produced the failure.

**The message is already safe to read.** Error text and data are
redacted and scrubbed when the audit row is written, not when it is
displayed — so a secret that flowed into an exception (lesson 07) is
not sitting in this list waiting to be shown to a teammate.

## Type errors — what the checker recorded

The **Type errors** tab lists the diagnostics recorded for the branch
you are on. One row per diagnostic:

```
tprobe            ← the fn
port              ← the arg
Type-check failed in fn-def :tprobe
  arg :port ← (literal "oops")
  parent :web.http/http-server expects: [:refine :int [:and [:>= 1] [:<= 65535]]]
  actual:                :text
  hint: the literal value classifies as :text
```

That is the same text lesson 05 showed you on a single card's badge.
The panel is the branch-wide view of it: every mismatch that survived a
write, in one place, including the ones on fns you are not currently
looking at.

Why the writes were allowed at all is lesson 05's point, worth
restating here: an editor that refuses a half-finished edit forces you
to construct changes in an order the type-checker approves of. Graphden
takes the diagnostic instead — so this panel is a **worklist**, and it
is expected to be non-empty while you are mid-change.

Rows leave the list when the mismatch is fixed. Nothing to acknowledge,
no state to clear: the next write re-checks the fn and the diagnostic
either re-records or does not.

## The two panels next door

Two more observability panels you have met:

- **Debug** — the «catch next request» trap and the last captured
  trace (lesson 27), the bar's last tab.
- **Monitoring** — usage rollups: runs, failures and average duration
  per fn (lesson 18 reads the same numbers for the plan's ceilings);
  it is an org-level report, so it lives on the account menu's
  **Organization** surface.

Errors answers *what broke*, Monitoring answers *how often*, Debug
answers *why*. In that order, most of the time.

## Try it

1. Build something that will fail at run time. `:parse-json` is the
   shortest honest example:

   ```edn
   {:name :tutorial-bad-json
    :parent :parse-json}
   ```

2. Run it (⋯ → ▶) with `string` = `not json at all`, and **tick “Save
   to history”** before pressing Run. The result pane shows
   `Malformed JSON.`
3. Click **Errors** in the diagnostics bar under the canvas. Your run
   is the top row. Expand `details` — the error data carries the typed
   `:validation-error/malformed-json`, which is what a client would
   have seen.
4. Now make a static mistake. Extend `:http-server` and bind its
   `port` to the literal `"oops"` (a string). The write SUCCEEDS, with
   a warning, and the card grows a red type-error badge.
5. Open **Type errors** in the same bar. There is your row,
   with the expected refinement and the actual `:text`.
6. Fix the binding — bind `8080` instead — and reload the panel. The
   row is gone; you did not have to dismiss it.

## Where this shows up next

- **Tests** (lesson 26) — a failing test is a failed run like any
  other, so it lands in Errors too, with its assertion message.
- **Debugging** (lesson 27) — from a failed row, the next question is
  usually "which node threw?", which is what the trace tree answers.
- **Plans** (lesson 18) — the same audit rows feed the usage counters;
  a plan's retention is what decides how far back Errors can look.
