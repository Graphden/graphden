# Lesson 22 — Filters and views: look at the part of the graph you mean

**Goal**: by the end of this lesson you can narrow the Explorer to
the namespaces you work in, to everything built on a given fn, to
fns with a given effect, or to what nothing uses — combine those,
save the set as a **view**, and share a view with your team as an
fn in the graph. Browser filters affect your Explorer; saving or editing a graph view changes its shared definition.

**Concepts introduced**: **filter** (one chip, one predicate),
**view** (a named set of filters), the **view chip**, **+ filter**,
the **personal hide** (`⊘`), and a view **saved in the graph**
(the `explorer-view` fn).

## One idea, three depths

The Explorer shows the *whole* graph — every namespace, every fn.
On a real shared graph that is overwhelming, and most of it isn't
yours. Everything that narrows the tree is the same thing at three
depths:

- a **filter** is one predicate, shown as a chip under the filter
  box — lesson 20's kind chips are filters, and so is `in core`,
  `uses core.logic.const`, `fx io`, `unused`;
- the **active set** is the chips that are on — filters combine
  (AND), and the chip top-left names the set: **All functions**,
  `2 filters`, or a view's name;
- a **view** is a named, saved set — yours in this browser, or an
  fn in the graph when the team should have it too.

Changing a browser filter leaves the graph intact. A filter is about what is *listed*,
never about what is *reachable*: fns outside the set still exist,
still run, and references to them still resolve. Search always looks
across the whole graph, so a filtered-out fn is still findable by
name.

## + filter — the axes that take a value

Under the kind chips sits **+ filter**. It opens the menu of filters
that need a value:

```text
ADD A FILTER
  Namespaces — only these
    ☑ core     ☐ app     ☐ web      ← the graph's root namespaces
  Hidden by you — restore
    ↺ core.tests
  Uses a function
    + pick a fn — only what is built on it
  Effect
    io  db  network  state  time  random  env  process  raw-sql
  In a graph view
    ☐ api-surface                   ← the views saved in the graph
  Name contains
    [ part of a name ]
  ☐ Unused — nothing references or extends it
```

- **Namespaces** — tick the roots you work in; the tree shows those
  and their descendants and the namespace-less **(primitives)**
  bucket steps aside. Several roots OR together. The menu stays open
  while you compose a set.
- **Uses a function** — the fn picker (the Explorer's own search,
  with type hints). The tree becomes every fn that *transitively*
  extends or references the one you pick — a group nobody maintains
  by hand. Pick a second fn and members must use both.
- **Effect** — every fn whose computed effect footprint carries the
  kind; two kinds means both.
- **In a graph view** — every fn that is *also* a member of a view
  saved in the graph (below): the chip reads `view api-surface`.
- **Name contains** — the saved form of the filter box: a `name …`
  chip that stays on and can be part of a view.
- **Unused** — nothing in the graph extends, references or resolves
  the fn: the dead-code view. Combine it with a namespace — package
  leaves are public API and "unused in this graph" by design.

Each becomes a chip with an **×**; the × is the way back. **◍ all**
clears every filter at once. Two honest edges: a server-evaluated set
stops at 500 fns and the tree says *Showing 500 of N — add a filter
to narrow*; a chip that names a fn or view deleted since you saved it
turns **⚠** and matches nothing — its × is the fix.

## The personal hide — ⊘

Sometimes the noise is one namespace inside your scope — a scratch
area, an archive. Hover any namespace row and click its **⊘** (next
to rename / add / publish): the namespace vanishes from *your* tree
at any depth, and a `not core.tests` chip appears. Remove the chip
(or **↺** it in the + filter menu) to restore. It is a filter like
the others: the shared graph is untouched, teammates still see the
namespace, no permission is needed.

## The view chip

Top-left in the Build context bar — alongside the *branch* and
*packages* chips (Lessons 23 and 32) — the **view chip** names what
you are looking at. Click it:

```text
VIEWS
  ◍ All functions — no filters
  Device only
    ● on-const                ×
    web-services              ×
  In the graph
    api-surface   Edit filters   Edit graph
  Save the current filters
    [ View name ]  Save in graph   Device only
```

**Save in graph** is the primary action (also **Enter** in the name
field). It creates a versioned view for this branch. **Device only** saves
an independent named filter set in this browser and does not update a
same-named graph view. Device views and the active filters survive reload;
another device starts with its own preferences.

Pick a saved view to restore its conditions. Changing a chip detaches the
active set from its saved label. To update an existing graph view, use its
**Edit filters** button: the name field is prefilled and **Save changes**
updates that exact view identity. Ordinary filter edits keep this explicit
edit context; **◍ all**, applying another view or saving **Device only**
leaves it.

## A view saved in the graph

**Save in graph** turns the active set into an ordinary fn:

```clojure
{:name :api-surface
 :parent :explorer-view
 :args {:namespaces ["web" "app"]
        :effects ["network"]
        :uses :http-server}}
```

`explorer-view` runs a query over the current branch. Its conditions include
`name`, `uses`, `effects`, `kinds`, `problems`, `namespaces`, `exclude`,
`unused`, and membership in other views (`also`). The save operation retains
**every** selected uses / also reference, not just the first. References
carry fn identities, so a rename does not change what they mean. Multiple
uses and views intersect; namespace and kind choices OR within their axis.
Problem predicates evaluate the current failed / type-error / lint state.
The Apps kind uses the same installed addon data as the Apps panel.

The graph view is an ordinary fn, versioned and reviewable like any other
change. **Edit graph** opens it by identity; **▶ Run** returns matching
functions, the total count, and whether the displayed list was capped.
Saving or editing sends all conditions in one transaction. A failed save
leaves the draft in place. A view with computed clauses cannot be flattened
into editable chips; **Edit graph** is the explicit way to change it.
Inherited fixed clauses can also refuse replacement, preserving the original
view and explaining the graph fallback.

You can write one by hand too — extend `explorer-view` like any
base-fn, from the editor or an `fns.edn`.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=22)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

1. Click **+ filter**, tick `core` — the Explorer collapses to
   `core.*`, an `in core` chip appears, the view chip reads
   `1 filter`, and the "(primitives)" bucket disappears.
2. Tick a second root — the menu stays open; both trees are in
   scope, the chip reads `2 filters`.
3. Hover `core.strings` in the Explorer, click **⊘** — a
   `not core.strings` chip appears and the namespace is gone from
   your tree. Click the chip's × to bring it back.
4. Reload the page — your chips survive.
5. Click **◍ all** — the full graph is back.
6. **+ filter** → **+ pick a fn**, type `const`, pick `const`
   (`core.logic`). Inspect the computed members; then **◍ all** clears
   this exercise.
7. Turn on **Functions**. In **+ filter**, set **Name contains** to
   `tutorial-` and press **Enter**. Open the view chip, name the set
   `tutorial-function-view` and choose **Save in graph**. Both predicates
   must be saved. **Device only** is the separate browser option.
8. Open the view chip → **Edit filters** for this graph view. Change
   **Name contains** to `tutorial-function-view`; then reopen the view
   chip and **Save changes**. The Functions condition stays, and the same
   view is updated.
9. **Edit graph** beside the view opens its fn. Click **▶ Run**,
   acknowledge the displayed database effect and press **Run**. The result
   includes the view itself, which satisfies both conditions.
10. **◍ all** returns to the whole tree. Finish the guided lesson and
    delete its created view; cleanup uses the exact identity created by
    this session.

## Next

[Lesson 23 — Branches: fork, edit, diff, merge](23-branches.md): a
change that lives on its own branch until you fold it back.
