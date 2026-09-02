# Lesson 17 — Finding your way: the lens and the Inspector

**Goal**: by the end of this lesson you can narrow the Explorer to
one *kind* of entity with the lens chips, and read everything
about a selected fn — bindings, runs, versions — in the right-hand
Inspector without opening a single popover.

**Concepts introduced**: the **lens** (kind-focus chips), **kind
markers**, and the **Inspector** panel with its four tabs.

## The lens — focus the tree on a kind

Under the Explorer's search box sits a row of **kind chips**:

```text
◍ all   λ fn   T types   🔒 secrets   ⚙ services 2   ▣ apps 1   ✓ tests 3
```

Click a chip and the tree narrows to rows of that kind — services
under the **services** chip, type definitions under **types**, and
so on. The chips are a *lens*, not a search: the tree keeps its
namespace shape, it just hides rows that don't match. **all**
clears the focus.

Details worth knowing:

- A fn can be several kinds at once (an app's handler may also be
  a service) — it matches *any* focused chip, and its row carries
  a marker per kind (⚙, ▣, 🔒).
- The trailing `fx marks` chip is a *detail* toggle, not a lens:
  it marks every fn whose execution carries effects with a small
  `fx` (hover it for the exact footprint — `io`, `time`, …)
  without hiding anything. Off by default; flip it on to see the
  footprints, off again and the tree returns to normal.
- **services** and **apps** show a live count; the **apps** chip
  appears only when the deployment has app routing at all
  (Lesson 27).
- Focusing **secrets** also reveals the **+ New secret** button
  (signed-in only) — creating secrets lives behind the lens that
  shows them (Lesson 13).
- The fn you currently have **selected** is never hidden, whatever
  the lens — if you can open it, it stays in the tree.
- Your lens choice is a per-browser preference (like the workspace
  scope from Lesson 19); it survives reload and affects nobody
  else.

The lens composes with the workspace (Lesson 19): the workspace
picks *which projects* you see, the lens picks *which kind of
rows* within them.

## The Inspector — the right panel

Click any fn — in the tree or any node on the graph canvas — and
the right-hand **Inspector** panel shows it. Four tabs:

- **Overview** — identity at a glance: name, namespace, parents,
  return type, effects — and **Used by**, the reverse index: every
  fn that *extends* the selected one, *references* it from an arg
  binding (with the slot named), or uses it as a resolver, plus the
  type-plane references when the row doubles as a type. Each row is
  a link — click it and the editor jumps to that caller. When an
  edit is refused with "In use — detach those first", this is the
  list it means.
- **Bindings** — the resolved slot/binding table: every slot the
  fn exposes, what binds it, where each binding was inherited
  from (the provenance story from Lesson 03).
- **Runs** — this fn's own execution history, live. There is no
  separate "Run page" — running is always the ▶ action on the
  row or node, and its history lands here (Lesson 12).
- **Versions** — the fn's version timeline across branches
  (Lesson 20).

The Inspector's head (name, namespace, description) renders
instantly from what the editor already knows; all four tabs,
Overview included, are fetched from the server when you open
them, so they're always current.

Two more reading affordances live outside the Inspector:

- **Peek** — a named fn on the canvas is a closed card (names are
  abstraction boundaries), and before peek the only way to read one
  was to navigate to it and lose your place. Now its **⋯ → 👁 Peek
  bindings** opens the same slot/binding table the Inspector's
  Bindings tab shows, in a floating panel right where you are —
  **Open** jumps, **Esc** or **×** closes and the canvas is
  untouched.
- **Recent** — the Explorer keeps your navigation trail: the last
  few named fns you selected render as rows just above the tree.
  Click one to go straight back; the ☆ on a row **pins** it above
  the trail permanently (★, until unpinned). The list hides while
  the filter is active (search owns that space).

The Inspector is the "read" side of the editor: popovers are for
*acting* (edit a binding, run, publish), the Inspector is for
*understanding* what's in front of you.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=17)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

1. Click the **⚙ services** chip — the tree collapses to service
   fns; note the count on the chip. Click **◍ all** to clear.
2. Focus **🔒 secrets** — the **+ New secret** button appears
   under the chips (signed-in only).
3. Select any fn and walk the Inspector tabs: **Bindings** shows
   the same slot table you'd assemble by hand from Lesson 03;
   **Runs** fills after you hit ▶ once.
4. Select `const` (core.logic) and open **Overview**: the Used-by
   section lists the crowd of fns that pin constants through it,
   "Extended by" first. Click a row — the editor jumps there.
5. Reload the page — your lens choice sticks.

## Next

That's the current end of the tutorial — new lessons are added as
features ship (see the [index](README.md)).
