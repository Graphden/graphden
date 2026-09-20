# Lesson 02 — Reading a card

**Goal**: by the end of this lesson you can open any fn in the editor
and read what the canvas shows — which cards it drew and why, what the
rows on a card are, how to unfold and fold, and where to find what a
namespace, a fn, or a slot is *for*.

**Concepts introduced**: `card`, `ancestor rows`, `level`,
`closed card`, `unfold / fold`, `hover preview`, `description`
(namespace / fn / slot), `↖ provenance`.

## The card is the fn's ancestry

Open a composed fn and its card is a stack of rows. The top row is
the fn itself; each row below is an ancestor — parent, grandparent,
… — down to the base function whose Clojure impl finally runs. For
`app.routes/health` (the fn behind `GET /health` on every graphden):

```text
health         ← the fn you opened
get-route      ← its parent: pins the HTTP method
route          ← the generic reitit route
list           ← the base function: [path method-data]
```

The card is a chain visualizer: click a row's name and you have
walked one hop up the inheritance. (Two names sharing one row is a fn
with two parents — [Lesson 03](03-parents-and-inheritance.md).)

## What the canvas draws when you open a fn

Only what the fn **itself** binds, plus every slot still free:

- a **literal** becomes a small value node — `path` → `"/health"`;
- a **reference** to a named fn becomes a second card — `handler` →
  `health-handler`;
- a **free** slot becomes a `+` placeholder (lesson 01's `:nums`).

The second card is **closed**. A named fn is a boundary: its own
bindings — what it is built from — are not drawn until you ask. That
is why opening a fn that wires ten named pieces together still fits on
one screen: you see the composition, one hop deep.

What you do *not* see at first: bindings made by the fn's ancestors.
`get-route` pins `method` to `"get"`, but `health` inherits that
already decided, so no `method` edge is drawn — it is not `health`'s
input. Which brings us to the rows.

## Unfolding: rows are levels

Each row is a level. **Clicking a row draws what that ancestor bound**
— and every ancestor above it. Click `get-route` on the `health` card
and a `method` → `"get"` edge appears; the value node carries a `↖`
that names where it came from (*Inherited from get-route ← route*)
and jumps there.

Levels are set, not toggled:

- clicking a **deeper** row unfolds down to it (the rows in between
  come along);
- clicking a **shallower** row folds back up to it;
- clicking the **top row** — the fn's own name — folds everything.

Two rows that add nothing of their own (`route` and `list` here — the
route's items are `path` and a method-map whose inputs are the `method`
and `handler` you already see) unfold together as one block.

**Hover before you click.** Pointing at a row previews the unfold:
the canvas draws what the click would add, and puts it away when the
pointer leaves. It costs nothing, so use it to read a card — click only
when you want the layout to stay.

**A card with two parents on one row** (multiple inheritance,
lesson 03) treats each half as its own switch: click one parent's name
to unfold just that parent's bindings, click it again to fold it;
once both halves are unfolded the row is simply "expanded to this
level" like any other.

## Opening a closed card

A card that arrived as a value — the fn bound into some slot — is
drawn closed. Unfold it the same way: click the row **under its
name** (its first parent) and its own bindings appear. On `health`'s
canvas, click `json-ok-response` on the `health-handler` card and
`body` → `health-json-body` appears — closed in turn. Each hop is one
click. Reopen `health` later and the closed cards are back: the
unfolding is your reading position, not part of the graph.

If you want to read a closed card without changing the canvas at all,
its `⋯` → `👁 Peek` opens its bindings in a floating panel
([Lesson 20](20-explorer-and-inspector.md)).

## Descriptions live at three levels

Anything that can be named can be described, and one glyph — `i` —
marks the description everywhere:

| Level | Where to read it | Where to write it |
|---|---|---|
| **Namespace** | the `i` at the right end of its row in the Explorer | same `i` — click to pin, then **✎ Edit** |
| **fn** | the Inspector's head when the fn is selected; `⋯` → `i` on any card row; the `i` on its Explorer row | `⋯` → `i` → **✎ Edit** |
| **Slot** | the `i` after the type chip on the edge label | same `i` → **✎ Edit**, signed in |

A slot description is written where the slot was **declared**
(`route` declares `method`: *"HTTP method literal — e.g. "GET" /
"POST"…"*) and travels with the slot to every fn that inherits it.
A slot nobody described says so — `(no description)` — which is the
same `i` you would click to add one.

In `fns.edn` the three are `:description` on the namespace map, on
the fn-def, and inside an arg's spec:

```edn
{:name :route
 :description "Generic reitit route — `[path method-data]`. …"
 :parent :list
 :args {:items  [{:as :path} :method-map]
        :method {:type :keyword-or-text
                 :description "HTTP method literal — e.g. `\"GET\"` / `\"POST\"`. …"}}}
```

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=02)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

In the running editor (nothing is created in this lesson):

1. Type `health` in the Explorer filter and click the `health` row
   (in `app.routes`). The Inspector's head shows its description:
   *GET /health — current health status as JSON.*
2. Read the card: four rows — `health`, `get-route`, `route`, `list`.
   To its right, `path` → `"/health"` and `handler` → the closed
   `health-handler` card. No `method` edge.
3. Hover `get-route` on the `health` card — the `method` → `"get"`
   edge is previewed. Click the row to keep it. Hover the value's `↖`:
   *Inherited from get-route ← route*.
4. Hover the `i` after `method` on the edge label: the slot's
   description. Compare with the `i` after `path`: *(no description)*.
5. Click the `health` row (the top one): the card folds back.
6. Click `json-ok-response` on the `health-handler` card — the row
   under its name. Its own binding appears: `body` →
   `health-json-body`, closed in turn.
7. In the Explorer, hover the `routes` row and its `i` at the right
   end: the namespace's description.

## What we glossed over

- **Why `route` and `list` add nothing when unfolded** — the list's
  items *are* `path` and the method-map, already on the canvas as
  `path` and `handler`; how a slot travels up through references is
  [Lesson 05](05-free-arguments.md).
- **What the rows mean at the data level** — `parent-ids` and slot
  inheritance, [Lesson 03](03-parents-and-inheritance.md) and
  [Lesson 04](04-slots-and-bindings.md).
- **Reading the other panels** — the Inspector's Bindings table, kind
  filters, Peek: [Lesson 20](20-explorer-and-inspector.md).

## Next

[Lesson 03 — Parents and inheritance](03-parents-and-inheritance.md)
