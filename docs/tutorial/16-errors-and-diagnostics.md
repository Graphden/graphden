# Lesson 16 — When something breaks, and when it just repeats: the problem lenses

**Goal**: know where to look when a run fails, when an edit doesn't
type-check, and when a composition already exists somewhere else —
and know why those are three different kinds of problem.

**Concepts**: the Explorer's **✕ failed**, **⚠ type errors** and
**⚐ lint** lenses (with counts on the chip, the namespace and the
fn), the Inspector's **Runs** / **Bindings** / **Lint** details,
runtime failure vs static diagnostic vs lint finding, error-tolerant
writes, marking a finding as not an issue.

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=16)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

## Three kinds of wrong

Graphden separates them on purpose, and each has its own lens:

| | **✕ failed** | **⚠ type errors** | **⚐ lint** |
|---|---|---|---|
| What it marks | fns with runs that FAILED and are still unresolved | fns with edits that don't type-check | fn-defs that duplicate one another, private fn-defs nothing uses |
| When a mark appears | at run time | at write time | whenever the graph changes |
| Scope | the branch you are on (plus its ancestors), last 7 days | the branch you are on, right now | the branch you are on, right now |
| Cleared by | fixing the fn, a clean re-run, or ✕ (dismiss) | fixing the binding | removing the copy, or **Not an issue** |
| Detail in the Inspector | **Runs** — message and ✕ | **Bindings** — the message under the argument | **Lint** — the finding, Not an issue / Restore |

A type error is not a failed run: the write **succeeded**, the fn is in
the graph, and the diagnostic rides alongside it (lesson 05 — "a
diagnostic, not a wall"). A failed run is the opposite: nothing was
written, something was attempted and threw.

A lint finding is neither: nothing failed and nothing is mistyped —
the graph simply already contains what you just built, or holds a
private helper that no longer earns its place. It is advice, so it is
the one list where you get to disagree.

All three are **lenses** in the Explorer (Lesson 17): click a chip
and the tree narrows to the fns involved, each namespace row counting
what is inside it and each fn row carrying its own mark — so the tree
answers *where*, and the Inspector, on the selected fn, answers *what*.

## ✕ failed — what failed and is still your problem

The **✕ failed** chip focuses the tree on fns with recent failed runs
that are still **unresolved** on the branch you are on; the chip
counts the runs, a namespace row shows `✕ 3`, the fn row `✕1`. Select
the fn and the Inspector's **Runs** tab opens with its unresolved
failures at the top:

```text
Unresolved failures
✕  2026-08-22 09:49:35   Malformed JSON.     ← dismiss · when · the message
7d: 4 runs · 1 failed · avg 12 ms            ← the usual history follows
```

It is a worklist, not a permanent scar — a failure leaves it as soon
as any of these happens:

- **You fix the fn.** A failure is pinned to the exact version that
  ran. Save a new version (or, on a branch, override the fn) and the
  old failure is not your code any more — the row disappears on its
  own.
- **A clean re-run.** If the same version later runs to success (the
  failure was transient — a network blip, a bad input), the failure
  clears without an edit.
- **You dismiss it.** The ✕ on the row in the Runs tab (or **✕ Dismiss
  all**, the button that appears under the chips while the ✕ lens is
  on) acknowledges the failure and hides it everywhere; the audit row
  itself stays for its retention window.

Failures follow branches the way code does: a branch **sees its
ancestors' failures** (it resolves the same broken version they ran),
but sibling branches never see each other's, and a parent never sees a
run that happened only on a child.

Three more things worth knowing about that list:

**It only counts runs you asked to keep.** A transient run — the plain
▶ with "Save to history" unticked (lesson 12) — leaves no audit row, so
a failure you did not persist never reaches the lens. Effectful runs
persist by themselves; pure ones are yours to keep.

**The mark is where you work.** The `✕1` sits on the fn's row and on
its card title on the canvas; selecting it puts the failure's message
and the bindings that produced it side by side.

**The message is already safe to read.** Error text and data are
redacted and scrubbed when the audit row is written, not when it is
displayed — so a secret that flowed into an exception (lesson 13) is
not sitting in the Inspector waiting to be shown to a teammate.

## ⚠ type errors — what the checker recorded

The **⚠ type errors** chip focuses on fns with diagnostics recorded for
the branch you are on; the chip counts the diagnostics, the fn row
carries `⚠2`, the card its ⚠ badge. Select the fn: in the Inspector's
**Bindings** tab the message sits right under the argument it objects
to:

```text
port   = "oops"   :int…                   ← the binding
Type-check failed in fn-def :tprobe
  arg :port ← (literal "oops")
  parent :web.http/http-server expects: [:refine :int [:and [:>= 1] [:<= 65535]]]
  actual:                :text
  hint: the literal value classifies as :text
```

That is the same text lesson 05 showed you on a single card's badge.
The lens is the branch-wide view of it: every mismatch that survived a
write, marked where it lives, including the ones on fns you are not
currently looking at.

Why the writes were allowed at all is lesson 05's point, worth
restating here: an editor that refuses a half-finished edit forces you
to construct changes in an order the type-checker approves of. Graphden
takes the diagnostic instead — so this lens is a **worklist**, and it
is expected to be non-empty while you are mid-change.

Marks leave when the mismatch is fixed. Nothing to acknowledge, no
state to clear: the next write re-checks the fn and the diagnostic
either re-records or does not.

## ⚐ lint — what the graph already has

The **⚐ lint** chip is the graph's own reviewer. It reads the branch
the way the package loader reads `fns.edn` — every fn-def as parents
plus bindings, with names and descriptions stripped — and marks three
things; select a marked fn and the Inspector's **Lint** section shows
the finding with the other fns involved as links:

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
Lint
duplicate-definition   tutorial-row-attrs  tutorial-page-attrs   ← rule · the fns, as links
                       2 fn-defs are the same definition (3 bound values)
                       [ Not an issue ]                          ← your say
```

The fix is lesson 02's move: keep one definition and let the other
place inherit or reference it. But sometimes the copy is deliberate —
two branches of an `if` that must stay separate entities, a
transitional state mid-refactor. Click **Not an issue** and the mark
goes away, and stays away until the group changes (a third copy
appears, say) or you **Restore** it — the section keeps the hidden
entries that name the fn.

That decision is not kept in some setting: it is written **into the
graph**, as a root fn named `lint-suppressions` — a `:const` whose
value is the list of findings you dismissed. It is versioned with the
branch, merges with it, and you can open it on the canvas and edit or
delete it like any other fn. Nothing in graphden's diagnostics is
stored state except what you explicitly said.

## The two panels next door

The diagnostics bar under the canvas keeps two panels you have met:

- **Tests** — the `tests` namespace's runner and live results
  (lesson 14); a test is also the **✓ tests** lens.
- **Debug** — the «catch next request» trap and the last captured
  trace (lesson 15).

And **Monitoring** — usage rollups: runs, failures and average
duration per fn (lesson 34 reads the same numbers for the plan's
ceilings) — is an org-level report, so it lives on the account menu's
**Organization** surface.

The ✕ lens answers *what broke and where*, Monitoring *how often*,
Debug *why*, ⚐ *where this already is*. In that order, most of the
time.

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
3. Click the **✕ failed** chip under the Explorer's filter. The tree
   narrows to `tutorial-bad-json`, `✕1` on its row and on its card.
   Select it and open the Inspector's **Runs** tab: the failure is at
   the top with `Malformed JSON.`
4. Watch the mark resolve itself: run `tutorial-bad-json` again, this
   time with `string` = `{}` (valid JSON), "Save to history" ticked.
   The chip reads nothing and the Runs tab's failures block is gone:
   the same version ran to success, so there is nothing left to fix.
   (The ✕ on a failure does the same by hand, for failures that no
   re-run will outrun.)
5. Now make a static mistake. Extend `:http-server` and bind its
   `port` to the literal `"oops"` (a string). The write SUCCEEDS, with
   a warning, and the card grows a red type-error badge.
6. Click **⚠ type errors**. The tree narrows to your fn, `⚠1` on its
   row; in the Inspector's **Bindings** tab the message sits under
   `port`, with the expected refinement and the actual `:text`.
7. Fix the binding — bind `8080` instead. The mark is gone; you did
   not have to dismiss it.
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

   Click **⚐ lint**. Both fns, `⚐1` each, the chip reading `1` — one
   finding, two members. Select either: the Inspector's **Lint**
   section shows `duplicate-definition`, both names as links, "2
   fn-defs are the same definition (3 bound values)".
9. Decide. Delete `tutorial-row-attrs` and the mark leaves on its own —
   or click **Not an issue** and watch the section swap to its hidden
   entry, while a new fn `lint-suppressions` appears at the root of
   the Explorer. Open it: the value is your decision, spelled out.
   **Restore** puts the finding back.

## Where this shows up next

- **Tests** (lesson 14) — a failing test is a failed run like any
  other, so the ✕ lens marks it too, with its assertion message in
  the Runs tab.
- **Debugging** (lesson 15) — from a failed row, the next question is
  usually "which node threw?", which is what the trace tree answers.
- **Plans** (lesson 34) — the same audit rows feed the usage counters;
  a plan's retention is what decides how far back the ✕ lens can look.
- **Packages** (lesson 28) — the same lint runs over graphden's own
  package corpus in CI (`bb graph-lint`); the rules and their
  calibration are in [GRAPH_LINT.md](../GRAPH_LINT.md).
