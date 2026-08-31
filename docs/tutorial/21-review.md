# Lesson 21 — Review: propose, approve, and the protected merge

**Goal**: by the end of this lesson you can protect a branch with a
required-approvals rule, watch it refuse an unreviewed merge, and walk
a change through the propose → approve cycle until it may land.

**Concepts**: required approvals on the merge **target**, the 📤
propose / ✅ approve cycle, stale-approval dismissal, self-approval,
review comments (anchored and general), suggested changes, and why a
merged branch is permanent.

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=21)
> (no sign-up), or pick Lesson 21 in the editor's account menu.

This lesson builds on [Lesson 20](20-branches.md) — fork, edit, diff,
merge. Here the missing half: making a merge *conditional on review*.

## The rule lives on the target

On GitHub you protect `main` and require approving reviews; graphden
works the same way, and the knobs sit under `⋯` → **⚙ Protection…**
on the branch-popover row **of the branch being merged into**:

- **Required approvals** (0–3) — a merge into this branch is refused
  (409, *"requires N approval(s)…"*) until the proposal has N valid
  approvals.
- **Push only via merge** — no direct writes at all; the only way in
  is a merge (lesson 20 covers this one).
  Required approvals is a one-tap `0…3` segmented control — pick the
  number and the rule is saved immediately.
- **Count the author's own approval** — on by default, so a solo user
  is never locked out; untick it for genuine four-eyes review.

Approvals are **target-bound and content-aware**: an approval is
recorded for a merge into the proposal's *base* branch, and editing
the proposed branch afterwards dismisses the now-stale approvals —
like GitHub dismissing stale reviews on a new push.

## The cycle: 📤 → ✅ → ⇢

- **📤 Propose** marks a branch as submitted for review into its
  base. Proposed branches are the reviewer's to-do list — the popover
  header counts them.
- **✅ Approve** records your approval; the row's badge shows `n/N`
  and turns green when the requirement is met.
- **⇢ Merge** now lands. Before the requirement is met it answers
  409 with the shortfall.
- Every proposal carries a **comment thread** in its **💬 Review &
  comments** dialog (the row's ⋯ menu, or the Δ chip's cockpit) —
  alongside a collapsible "What changed" list and the suggestions.
- A comment can be **anchored to one element**: click 💬 on a change
  row in the dialog — or right in the **inspector's diff panel**
  while comparing — and the note pins to exactly that fn / arg /
  list item, GitHub-line-comment style. Anchored threads render
  inline under their row; if the element later drops out of the
  diff, the thread falls back to the general conversation with a
  context chip.
- The Review dialog also lists **Suggestions** — branches forked
  *off the proposal itself* and proposed back into it. A reviewer clicks
  **+ Suggest a change**, lands on a fresh `suggest-…` branch, edits
  with the full editor, and proposes it (`⋯` → 📤). The author then
  sees it in the proposal's Review dialog — with a collapsible Δ
  preview of what it changes — and applies it with one click
  (`⇢ apply` — an ordinary merge into the proposal, which also
  dismisses now-stale approvals). No new machinery: a suggestion is
  just the branch/propose/merge cycle one level down.

## Try it

1. Make something to change: extend `const` into `review-demo` and
   bind its `:value` to `1`.
2. Create a branch `tutorial-release` (from `main`) — it will play
   the protected trunk, without touching your real one.
3. In the branch popover, open `⋯` on the `tutorial-release` row,
   choose **⚙ Protection…** and set **Required approvals** to `1`.
4. Create `tutorial-feature` — it forks from the branch you are on,
   `tutorial-release`, so that is where its proposal aims.
5. Change `review-demo`'s value to `2` there, then switch back to
   `tutorial-release`.
6. Click `⇢` on the `tutorial-feature` row. Refused: *"requires 1
   approval(s)…"* — the rule holds even against the branch's author.
7. In the row's `⋯` menu click **📤 Propose for review**, then click
   `✅` on the row (approve). The badge reads `1/1`.
8. `⇢` would now land the merge.

## Why the tour stops before the merge

A merge in graphden is **by-reference**: no rows are copied — the
target simply starts reading the source branch's version rows. That
makes merges cheap, and it has a consequence worth knowing: a merged
branch has become part of its target's history and **can no longer be
deleted** while the target exists (deleting it would silently revert
the target's merged-in content — the server refuses). In real work
that permanence is the point: merged branches *are* the record, like
merged commits. In a tutorial sandbox it means the guided tour stops
one click short of `⇢`, so its cleanup can still remove both branches.

## Where this shows up next

- [Lesson 20](20-branches.md) — protected branches ("push only via
  merge"), conflicts, and branch-local fns that never merge.
- [VERSIONING.md](../VERSIONING.md) — the branch model underneath.
