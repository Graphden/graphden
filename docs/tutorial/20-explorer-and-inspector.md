# Lesson 20 — Finding your way: kind filters and the Inspector

**Goal**: by the end of this lesson you can narrow the Explorer to
one *kind* of entity with the kind chips, and read everything about
a selected fn — bindings, runs, versions — in the right-hand
Inspector without opening a single popover.

**Concepts introduced**: the **kind filters** (the chips under the
filter box), **kind markers**, and the **Inspector** panel with its
four tabs.

## The kind chips — one row of filters

Under the Explorer's filter box sits a row of **kind chips**:

```text
◍ all   λ fn   T types   🔒 secrets   ⚙ services 2   ▣ apps 1   ✓ tests 3
✕ failed 1   ⚠ type errors 2   ⚐ lint 1
+ filter
```

Click a chip and the tree narrows to rows of that kind — services
under **services**, type definitions under **types**, and so on.
A chip is a **filter**, not a search: the tree keeps its namespace
shape, it just hides rows that don't match. A second chip adds to
the selection (services *or* apps); clicking a pressed chip removes
it; **◍ all** clears *every* filter at once — the one gesture that
always brings the whole tree back.

Details worth knowing:

- A fn can be several kinds at once (an app's handler may also be
  a service) — it matches *any* pressed kind chip, and its row
  carries a marker per kind (⚙, ▣, 🔒).
- The trailing `fx marks` chip is a *detail* toggle, not a filter:
  it marks every fn whose execution carries effects with a small
  `fx` (hover it for the exact footprint — `io`, `time`, …)
  without hiding anything. Off by default; flip it on to see the
  footprints, off again and the tree returns to normal.
- **services**, **tests** and **apps** show a live count (tests also
  count their failures, `· N✗`); the **apps** chip
  appears only when the deployment has app routing at all
  (Lesson 30).
- Pressing **secrets** also reveals the **+ New secret** button
  (signed-in only) — creating secrets lives behind the filter that
  shows them (Lesson 16).
- The fn you currently have **selected** is never hidden, whatever
  the filters — if you can open it, it stays in the tree.
- Your filters are a per-browser preference; they survive reload
  and affect nobody else. The chip top-left that reads
  **All functions** counts them (`2 filters`) — Lesson 22 makes it
  a named **view**.

### Problem chips

The second row — **✕ failed**, **⚠ type errors**, **⚐ lint** —
filters on what needs attention rather than on what a row *is*; it
composes with the kind chips (a failing fn is still a fn). The counts,
the marks on rows and cards, and where each mark leads in the
Inspector are [lesson 19](19-errors-and-diagnostics.md)'s subject.

### The other filters

Kinds are the fixed half of the row. **+ filter** opens the rest —
*only these namespaces*, *only what is built on this fn*, *only fns
with this effect*, *unused* — each of which appears as its own chip
with an ×. Those, and saving a set of chips as a **view**, are
[lesson 22](22-workspaces.md).

### Folding the panel away

The `<` in the Explorer's header (or `Space e`) collapses the whole
panel to a slim tab at the left edge — it still names the branch you
are on; click the tab (or `Space e` again) to bring the panel back. A
tour step that needs the Explorer expands it for you.

### From a card back to the tree

`⋯` on a card → the **ns** badge shows the fn's namespace path and
offers **Reveal in Explorer** — the tree unfolds to the row and
selects it. The `↗` beside it opens the fn in a new tab.

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
  from (the provenance story from Lesson 04).
- **Runs** — this fn's own execution history, live. There is no
  separate "Run page" — running is always the ▶ action on the
  row or node, and its history lands here (Lesson 15).
- **Versions** — the fn's version timeline across branches
  (Lesson 23).

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
  the filter — or any filter chip — is active (the search owns that
  space).

The cards themselves have one setting: **Settings → Appearance → Graph
cards**. *Compact* hides the type / effect strips on every card until
you reveal them — the same facts stay one click away in the Inspector,
and a large graph gets noticeably calmer.

The Inspector is the "read" side of the editor: popovers are for
*acting* (edit a binding, run, publish), the Inspector is for
*understanding* what's in front of you.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=20)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

1. Click the **⚙ services** chip — the tree collapses to service
   fns; note the count on the chip. Click **◍ all** to clear.
2. Press **🔒 secrets** — the **+ New secret** button appears
   under the chips (signed-in only).
3. Select any fn and walk the Inspector tabs: **Bindings** shows
   the same slot table you'd assemble by hand from Lesson 04;
   **Runs** fills after you hit ▶ once.
4. Select `const` (core.logic) and open **Overview**: the Used-by
   section lists the crowd of fns that pin constants through it,
   "Extended by" first. Click a row — the editor jumps there.
5. Reload the page — your chips stick, and the chip top-left counts
   them.
6. Build Lesson 19's duplicate pair (`tutorial-page-attrs` /
   `tutorial-row-attrs`) and click **⚐ lint**: the tree collapses to
   the two rows, each with `⚐1`, their namespace row with `⚐ 2`, the
   chip with `1` — one finding, two members. Delete one of the pair
   and the chip reads nothing.

## Next

[Lesson 21 — Working without the mouse](21-keyboard-and-accessibility.md):
everything this lesson did with clicks — find, read, walk the graph —
from the keyboard.
