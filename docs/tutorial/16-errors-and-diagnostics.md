# Lesson 16 — When something breaks, and when it just repeats: the diagnostics bar

**Goal**: know where to look when a run fails, when an edit doesn't
type-check, and when a composition already exists somewhere else —
and know why those are three different lists.

**Concepts**: the diagnostics bar's **Failed runs** tab (recent failed
runs), **Type errors** tab (recorded diagnostics for this branch) and
**Lint** tab (structure the graph already has), runtime failure vs
static diagnostic vs lint finding, error-tolerant writes, marking a
finding as not an issue.

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=16)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

## Three kinds of wrong

Graphden separates them on purpose, and each has its own panel:

| | **Failed runs** | **Type errors** | **Lint** |
|---|---|---|---|
| What it lists | runs that FAILED and are still unresolved | edits that don't type-check | fn-defs that duplicate one another, private fn-defs nothing uses |
| When a row appears | at run time | at write time | whenever the graph changes |
| Scope | the branch you are on (plus its ancestors), last 7 days | the branch you are on, right now | the branch you are on, right now |
| Cleared by | fixing the fn, a clean re-run, or ✕ (dismiss) | fixing the binding | removing the copy, or **Not an issue** |

A type error is not a failed run: the write **succeeded**, the fn is in
the graph, and the diagnostic rides alongside it (lesson 05 — "a
diagnostic, not a wall"). A failed run is the opposite: nothing was
written, something was attempted and threw.

All three are also **lenses** in the Explorer (Lesson 17): the
**✕ failed** / **⚠ type errors** / **⚐ lint** chips narrow the tree to
the fns involved, each namespace and each row carrying its count — so
the drawer is where you read the details, the tree is where you see
where they are.

A lint finding is neither: nothing failed and nothing is mistyped —
the graph simply already contains what you just built, or holds a
private helper that no longer earns its place. It is advice, so it is
the one list where you get to disagree.

All three live in the **diagnostics bar** under the canvas — a
collapsible drawer whose tabs (Failed runs, Type errors, Lint, Tests,
Debug) open without leaving the editor, so a fn link in a row selects
it on the canvas while the list stays open.

## Failed runs — what failed and is still your problem

The **Failed runs** tab lists the recent failed runs that are still
**unresolved** on the branch you are on, newest first. Each row is:

```text
✕                                   ← dismiss this failure
eprobe                              ← the fn, as a link
2026-08-22 09:49:35                 ← when it finished
Malformed JSON.                     ← the error message
▸ details                           ← the error data, expandable
```

The panel is a worklist, not a permanent scar — a row leaves it as
soon as any of these happens:

- **You fix the fn.** A failure is pinned to the exact version that
  ran. Save a new version (or, on a branch, override the fn) and the
  old failure is not your code any more — the row disappears on its
  own.
- **A clean re-run.** If the same version later runs to success (the
  failure was transient — a network blip, a bad input), the failure
  clears without an edit.
- **You dismiss it.** The ✕ on the row (or **Dismiss all** above the
  list) acknowledges the failure and hides it everywhere; the audit
  row itself stays for its retention window.

Failures follow branches the way code does: a branch **sees its
ancestors' failures** (it resolves the same broken version they ran),
but sibling branches never see each other's, and a parent never sees a
run that happened only on a child.

Three more things worth knowing about that list:

**It only holds runs you asked to keep.** A transient run — the plain
▶ with "Save to history" unticked (lesson 12) — leaves no audit row, so
a failure you did not persist never reaches this panel. Effectful runs
persist by themselves; pure ones are yours to keep.

**The fn name is a link.** Clicking it selects that fn on the canvas,
which is usually the next thing you want: read the bindings that
produced the failure. The Inspector's **Runs** tab then lists the same
unresolved failures for that fn at the top — message and ✕ — so the
dismissal can happen where you read the fn.

**The message is already safe to read.** Error text and data are
redacted and scrubbed when the audit row is written, not when it is
displayed — so a secret that flowed into an exception (lesson 13) is
not sitting in this list waiting to be shown to a teammate.

## Type errors — what the checker recorded

The **Type errors** tab lists the diagnostics recorded for the branch
you are on. One row per diagnostic:

```text
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

The same message sits under the offending argument in the Inspector's
**Bindings** tab — select the fn and the row for `port` carries the
checker's text, so the fix happens where the binding is.

## Lint — what the graph already has

The **Lint** tab is the graph's own reviewer. It reads the branch the
way the package loader reads `fns.edn` — every fn-def as parents plus
bindings, with names and descriptions stripped — and reports three
things, each as a row with the fns involved as links:

- **duplicate-definition** — two or more named fn-defs with the same
  parent and the same bindings. The same definition, written twice.
- **duplicate-after-expansion** — the same graph once the private
  `_`-helpers are inlined: you split it into different helpers, or
  spread it over two namespaces, but it computes the same thing.
- **unreferenced-private** — a `_`-private fn-def that nothing
  references any more.

Small coincidences are not reported. Two accessors that both read
`:id` off a row are the normal way to give each code path its own
child (lesson 04); the lint only speaks up when the shared structure
carries at least three bound values — a copied graph, not a habit.

```text
duplicate-definition                                 ← the rule
tutorial-row-attrs   tutorial-page-attrs             ← the fns, as links
2 fn-defs are the same definition (3 bound values)   ← the message
                                    [ Not an issue ] ← your say
```

The fix is lesson 02's move: keep one definition and let the other
place inherit or reference it. But sometimes the copy is deliberate —
two branches of an `if` that must stay separate entities, a
transitional state mid-refactor. Click **Not an issue** and the row
goes away, and stays away until the group changes (a third copy
appears, say) or you **Restore** it from the panel's hidden list.

That decision is not kept in some setting: it is written **into the
graph**, as a root fn named `lint-suppressions` — a `:const` whose
value is the list of findings you dismissed. It is versioned with the
branch, merges with it, and you can open it on the canvas and edit or
delete it like any other fn. Nothing in graphden's diagnostics is
stored state except what you explicitly said.

The Inspector shows the same rows for the selected fn under a **Lint**
heading, with the same **Not an issue** / **Restore** — so you see
"this already exists as `x`" and decide about it while you are still
looking at the fn, without a trip to the drawer.

## The two panels next door

Two more observability panels you have met:

- **Debug** — the «catch next request» trap and the last captured
  trace (lesson 15), the bar's last tab.
- **Monitoring** — usage rollups: runs, failures and average duration
  per fn (lesson 34 reads the same numbers for the plan's ceilings);
  it is an org-level report, so it lives on the account menu's
  **Organization** surface.

Failed runs answers *what broke*, Monitoring answers *how often*,
Debug answers *why*, Lint answers *where this already is*. In that
order, most of the time.

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
3. Click **Failed runs** in the diagnostics bar under the canvas. Your run
   is the top row. Expand `details` — the error data carries the typed
   `:validation-error/malformed-json`, which is what a client would
   have seen.
4. Watch the row resolve itself: run `tutorial-bad-json` again, this
   time with `string` = `{}` (valid JSON), "Save to history" ticked.
   Reopen the drawer — the failure is gone: the same version ran to
   success, so there is nothing left to fix. (The ✕ on a row does the
   same by hand, for failures that no re-run will outrun.)
5. Now make a static mistake. Extend `:http-server` and bind its
   `port` to the literal `"oops"` (a string). The write SUCCEEDS, with
   a warning, and the card grows a red type-error badge.
6. Open **Type errors** in the same bar. There is your row,
   with the expected refinement and the actual `:text`.
7. Fix the binding — bind `8080` instead — and reload the panel. The
   row is gone; you did not have to dismiss it.
8. Now build the same thing twice. Paste both:

   ```edn
   {:name :tutorial-page-attrs
    :parent :assoc
    :args {:map {:class "page"} :key "title" :value :tutorial-bad-json}}
   ```

   ```edn
   {:name :tutorial-row-attrs
    :parent :assoc
    :args {:map {:class "page"} :key "title" :value :tutorial-bad-json}}
   ```

   Open **Lint**. One row: `duplicate-definition`, both names as
   links, "2 fn-defs are the same definition (3 bound values)". Click
   either name — the Inspector's **Lint** section repeats the finding
   for that fn.
9. Decide. Delete `tutorial-row-attrs` and the row leaves on its own —
   or click **Not an issue** and watch it move into the panel's hidden
   list, while a new fn `lint-suppressions` appears at the root of the
   Explorer. Open it: the value is your decision, spelled out.
   **Restore** from the hidden list puts the row back.

## Where this shows up next

- **Tests** (lesson 14) — a failing test is a failed run like any
  other, so it lands in Failed runs too, with its assertion message.
- **Debugging** (lesson 15) — from a failed row, the next question is
  usually "which node threw?", which is what the trace tree answers.
- **Plans** (lesson 34) — the same audit rows feed the usage counters;
  a plan's retention is what decides how far back Failed runs can look.
- **Packages** (lesson 28) — the same lint runs over graphden's own
  package corpus in CI (`bb graph-lint`); the rules and their
  calibration are in [GRAPH_LINT.md](../GRAPH_LINT.md).
