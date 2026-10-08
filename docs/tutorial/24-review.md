# Lesson 24 — Review: propose, approve, and the protected merge

**Goal**: by the end of this lesson you can protect a branch with a
required-approvals rule, watch it refuse an unreviewed merge, and walk
a change through the propose → approve cycle until it may land.

**Concepts**: required approvals on the merge **target**, the 📤
propose / ✅ approve cycle, stale-approval dismissal, self-approval,
review comments (anchored and general), suggested changes, and why a
merged branch is permanent.

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=24)
> (no sign-up), or pick Lesson 24 under **Interactive tutorial** in the
> editor's account menu.

This lesson builds on [Lesson 23](23-branches.md) — fork, edit, diff,
merge. Here the missing half: making a merge *conditional on review*.

## The rule lives on the target

On GitHub you protect `main` and require approving reviews; graphden
works the same way, and the knobs sit under `⋯` → **⚙ Protection…**
on the branch-popover row **of the branch being merged into**:

- **Required approvals** (0–3) — a merge into this branch is refused
  (409, *"requires N approval(s)…"*) until the proposal has N valid
  approvals. It is a one-tap `0…3` segmented control — pick the
  number and the rule is saved immediately.
- **Push only via merge** — no direct writes at all; the only way in
  is a merge (lesson 23 covers this one).
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
  and turns green when the requirement is met. Both buttons toggle:
  📤 on a proposed branch **withdraws the proposal**, and a pressed
  ✅ (your approval applies to the current proposal) **withdraws your approval**.
  After the source changes, the old approval remains recorded but the button
  is unpressed: click it to approve the changed content.
- **⇢ Merge** now lands. Before the requirement is met it answers
  409 with the shortfall.
- The Review dialog opens with **Verified on <branch>** — the
  proposal's own tests (passed / failed / never ran) and its newest
  runs, so a reviewer reads what the author actually verified before
  reading the diff.
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

1. Create `tutorial-release` from `main` first. Extend `const` into
   `review-demo` on that owned branch and bind its `:value` to `1`.
2. In its branch-popover `⋯` menu choose **⚙ Protection…** and set
   **Required approvals** to `1`.
3. Create `tutorial-feature` from the release branch. Change the value
   to `2`, return to the release and try **⇢**. The target refuses the
   unreviewed merge.
4. Choose **📤 Propose for review**, then **Δ** to read the change.
   In the Inspector, open the function's **💬** thread, save a comment,
   then save an answer in the same thread. A draft is not a saved reply.
5. Exit comparison and approve: the badge reads `1/1`.
6. Return to the feature and change the value to `3`. On the release,
   the earlier approval is stale and the badge reads `0/1`.
7. Compare again, read the changed value and the preserved conversation,
   then approve the current content. The badge returns to `1/1`.
8. **⇢** would now land the reviewed value. Finish this guided lesson
   without merging, return to `main` and delete its owned child branches.

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
Lesson 23 performs an actual merge and remerge between sibling sandbox
branches: removing the target releases its merge reference before the
source and common base are deleted.

## Where this shows up next

- [Lesson 23](23-branches.md) — protected branches ("push only via
  merge"), conflicts, and branch-local fns that never merge.
- [VERSIONING.md](../VERSIONING.md) — the branch model underneath.
