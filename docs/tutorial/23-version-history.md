# Lesson 23 — Version history: what changed, and going back

**Goal**: read a fn's version timeline across branches, and put an
earlier version back — without leaving the editor, and without losing
the version you replaced.

**Concepts**: identity plane vs version plane, `:fn-version` row,
`⌛` history popover, *restore*, *switch to branch*, append-only
history.

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=23)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

## Every edit writes a row

Lesson 20 introduced branches; this is the other half of the same
machinery. A fn has **two planes**:

- the **identity plane** — one row, created once: the fn's `id`, its
  `parent-ids`, its namespace. This is what other fns point at, and it
  is the same row on every branch.
- the **version plane** — one row per edit, per branch, holding the
  fn's own top-level fields: `name`, `description`, `return-type`,
  `constraint`, `expects-effects`, and the `deleted-at` tombstone. This
  is what a read RESOLVES for the branch you are on.

So editing a description does not overwrite anything. It appends a
`:fn-version` row, and the branch's reads start resolving to it. The
old row is still there — which is what makes the rest of this lesson
possible.

## The ⌛ popover

Open any fn's row-actions (`⋯`) and click **⌛**. The popover lists
every version row for that fn, newest first, across **every branch** —
not just the one you are on:

```text
main    2026-08-22 09:48   restore   second
main    2026-08-22 09:48   restore   first
feat-x  2026-08-21 17:02   switch    restore
```

Each row carries:

- **the branch it was written on**, so a fn you edited on `feat-x`
  shows both timelines in one list;
- **restore** — write this version's fn-level fields onto the branch
  you are currently on;
- **switch** — move the editor to that row's branch (only on rows
  whose branch is not the current one).

Click a row to expand it: the executions recorded against that exact
version load underneath (lesson 12's history, sliced by version rather
than by fn). That is how you answer "did this break when I renamed it /
changed its declared type?" — the runs sit on either side of the edit.

One caveat worth carrying: only edits to the fn's OWN fields cut a new
`:fn-version`. Rebinding a slot writes a `:binding-version` and leaves
the fn's version id unchanged, so binding-only edits all land in the
same bucket here. The timeline is per-fn-row, not per-behaviour —
[VERSIONING.md § `:fn-version` ≠ "functional behaviour"](../VERSIONING.md)
is explicit about the trade.

## Restore is an edit, not a rewind

Click **restore** on an older row. The confirm names the branch and the
timestamp you are restoring from, and says what will happen:

> This writes a new version row with the historic fn-level fields
> (description, return-type, constraint, …) on the current branch.

Read that literally. Restore does not delete the versions after the one
you picked, and it does not move a pointer backwards. It **appends** a
new version carrying the old values. Right after restoring "first" over
"second", the timeline reads:

```text
main    09:48   first     ← the restore
main    09:48   second
main    09:48   first
main    09:48   (created)
```

History stays append-only, so a restore is itself undoable — restore
the row you were on before, and you are back. There is no state in
which you have lost work by pressing this button.

## What restore does NOT touch

Fn-level fields only — the ones listed above. **Bindings are not
restored.** A binding is its own entity with its own version rows
(lesson 03), so:

- restoring a fn after you rebound one of its slots leaves the new
  binding in place;
- a value you want back comes from that binding's own history, edited
  the same way you edited it in the first place.

This is a deliberate line, not an omission: bindings are what a fn
means, and silently reverting them from a fn-level action would undo
edits the reader never named. See
[VERSIONING.md § Subtleties](../VERSIONING.md).

## Deletion is a version too

Deleting a fn writes a tombstone version — `deleted-at` set — rather
than removing rows. That is why lesson 01's cleanup says "deletes are
soft, nothing is lost for good", and why a deleted fn's name is free
again immediately (the name-collision check reads the resolved view,
not the history).

It also means the ⌛ popover is where a deleted fn's past still lives,
for as long as you can reach its id.

## Try it

1. Pick any fn-def of yours — `:greet` from lesson 01 does — and edit
   its **description** twice (⋯ → `i`), so the timeline has something
   to show.
2. Open ⋯ → **⌛**. Three rows: the two edits and the create.
3. Click the OLDEST row to expand it. If that version ever ran, its
   executions are listed; a version that never ran says so.
4. Click **restore** on the middle row and confirm. The Inspector's
   description changes back — and a FOURTH row appears at the top of
   the popover. Nothing was removed.
5. Now bind a value on the fn (lesson 03), restore an older version
   again, and note the binding is untouched. Fn-level fields and
   binding values have separate histories.

## Where this shows up next

- **Branches** (Lesson 20) — the same version rows, read through a
  different branch chain. A merge picks version rows; a `:branch-local?`
  fn's rows deliberately do not travel.
- **Debugging** (lesson 15) — a trace names the version it ran, so a
  failed run points at the exact row in this popover.
