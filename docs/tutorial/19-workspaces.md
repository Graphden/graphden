# Lesson 19 — Filters and views: look at the part of the graph you mean

**Goal**: by the end of this lesson you can narrow the Explorer to
the namespaces you work in, to everything built on a given fn, to
fns with a given effect, or to what nothing uses — combine those,
save the set as a **view**, and share a view with your team as an
fn in the graph. All without changing anything for anyone else.

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
  box — lesson 17's kind chips are filters, and so is `in core`,
  `uses core.logic.const`, `fx io`, `unused`;
- the **active set** is the chips that are on — filters combine
  (AND), and the chip top-left names the set: **All functions**,
  `2 filters`, or a view's name;
- a **view** is a named, saved set — yours in this browser, or an
  fn in the graph when the team should have it too.

Nothing here changes the graph. A filter is about what is *listed*,
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
clears every filter at once.

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
*packages* chips (Lessons 20 and 29) — the **view chip** names what
you are looking at. Click it:

```text
VIEWS
  ◍ All functions — no filters
  Your views
    ● on-const                ×
    web-services              ×
  In the graph
    api-surface               ↗
  Save the current filters
    [ View name ]  Save view   Save in the graph…
```

**Save view** names the active set; the chip reads the name from
then on, and editing the set (any chip on or off) detaches it — the
chip reads `3 filters` until you save again. Pick a saved view to
get its set back. Views and the active set are per browser: another
device starts at **All functions**, signing out does not clear them.

## A view saved in the graph

**Save in the graph…** turns the active set into an ordinary fn:

```clojure
{:name :api-surface
 :parent :explorer-view
 :args {:namespaces ["web" "app"]
        :effects ["network"]
        :uses :http-server}}
```

`explorer-view` is a base-fn whose slots are the filter axes —
`name`, `uses` (a `:fn-ref`: the fn's *identity*, so a rename never
empties the view and the edge is not a dependency), `effects`,
`kinds`, `namespaces`, `exclude`, `unused`, and `also` (another view,
also by reference — members must be in it too; that view may `also` a
third, so views compose by chaining instead of repeating chips). A
view fn is versioned, branch-scoped and reviewable like any other
change; every editor on the branch lists it under **In the graph**;
**↗** opens it on the canvas; **▶ Run** answers the member list. The
problem chips (failed / type errors / lint) are this branch's live
state, not a definition, and are not saved.

You can write one by hand too — extend `explorer-view` like any
base-fn, from the editor or an `fns.edn`.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=19)
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
   (core.logic). The tree is now the fns built on `:const`. Open the
   view chip, name it `on-const`, press **Enter**: the chip reads
   `on-const`.
7. In the same popover, **Save in the graph…** — accept the name.
   A fn `on-const` (parent `explorer-view`) appears in the graph; the
   popover lists it under **In the graph**, and **▶ Run** on its
   card answers the member list.
8. **◍ all**, then reopen the view chip and pick `on-const` again —
   the set comes back.

## Next

[Lesson 20 — Branches: fork, edit, diff, merge](20-branches.md): a
change that lives on its own branch until you fold it back.
