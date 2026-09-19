# Lesson 01 — Anatomy of a fn-def

**Goal**: by the end of this lesson you can write a fn-def by hand
and explain each part of it.

**Concepts introduced**: `fn-def`, `:name`, `:parent`, `:args`,
`base function`, `fns.edn`.

## The smallest meaningful fn-def

```edn
{:name :hello-handler
 :parent :const
 :args  {:value {:status 200 :body "Hello from Graphden!"}}}
```

That's a complete fn-def. Three keys:

- **`:name`** — what you'll call this fn elsewhere. Names are
  unique **per namespace** (like vars in a Clojure ns): two
  namespaces may each define `:get-user`, and a bare reference
  resolves to your own namespace's fn first; referencing a
  same-named fn elsewhere uses the qualified form
  (`:other.ns/get-user`).
- **`:parent`** — the fn this one inherits from. Here `:const`
  is a base function (a built-in primitive) that returns its
  `:value` argument unchanged when executed.
- **`:args`** — values that fill in the parent's slots. `:const`
  exposes one slot called `:value`; we bind it to a map.

Executing `:hello-handler` returns the map `{:status 200 :body "Hello from Graphden!"}`.

## Where fn-defs live

Two places:

1. **`resources/packages/<pkg>/<module>/fns.edn`** — a vector of
   fn-def maps, loaded at startup. This is the source-of-truth
   form. See [PACKAGES.md](../PACKAGES.md).
2. **Inside the running editor** — when you click `+` in a
   namespace, the editor creates a fn entity directly via
   `POST /api/entities/fn`. Same shape, different entry point.

For this lesson assume you're typing into the editor. The
`fns.edn` form is what you'd write for code review.

## Two kinds of fn

Look at the `:parent` field. There are two kinds of fn you can
parent to:

| Kind | What it is | Example | How to spot one |
|---|---|---|---|
| **Base function** | A small Clojure impl wrapping one library call | `:const`, `:add`, `:render-hiccup`, `:pg-query` | Has a `:return-type-fn-id`, no `:parent-ids` |
| **fn-def** | A pure composition — no Clojure, just bindings | `:hello-handler`, `:web-server`, `:get-route` | Has at least one `:parent-ids` |

You can parent a new fn-def to either kind. Inheritance works
the same way for both.

## A fn-def that uses a fn-def

Now let's compose two:

```edn
{:name :hello-handler
 :parent :const
 :args  {:value {:status 200 :body "Hello!"}}}

{:name :hello-route
 :parent :assoc
 :args  {:map {} :key "handler" :value :hello-handler}}
```

`:hello-route` parents to `:assoc` — a base function that returns
`{key value}` merged into `map`. The interesting part: `:value` is
bound to `:hello-handler` (the keyword form of its name). When
`:hello-route` runs:

1. The executor sees the ref `:hello-handler` in slot `:value`.
2. It looks at `:assoc`'s slot type for `:value` — not `:fn`-typed.
3. So it **executes** `:hello-handler` (gets the response map)
   and uses that as the value of `:value`.

The same syntax in a `:fn`-typed slot would behave differently —
the fn-id would be passed unchanged for the parent to invoke. We'll
cover that in lesson 07 when we hit higher-order functions.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=01)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

In the running editor:

1. At the bottom of the Explorer click **New namespace**, type
   `tutorial` and press Enter.
2. Expand the `tutorial` row, hover it and click its **+**. Choose
   **New graph…**, type `one-plus-one` and press Enter. The new fn
   opens on the canvas.
3. On the card click **set parent…**, type `add` in the picker and
   pick `core.arithmetic.add` — the primitive that sums the numbers
   in its `:nums` list. Inheriting it exposes `:nums` as a `+`
   placeholder.
4. `:nums` is a LIST, so the `+` offers **Append literal** rather
   than a single value: append `1`, **Save**. Look at the edge now:
   one `:nums` label, and after its type chip the line fans out — a
   branch to the `1` and a branch to a fresh `+`, the list's next
   free slot (a plain argument splits off *before* its chip; a list
   element *after* it). Click that `+` and append a second `1`. The
   card now reads `1, 1` — the whole input of your function, as data
   on the card. (In `fns.edn` you'd write `:args {:nums [1 1]}`.)
5. Click the `⋯` button on the card's top row, then **▶ Run**, then
   **Run** in the Run pane that opens in the right panel: `2`.
   Nothing was built or deployed — the card IS the program, the pane
   is its output. Change a number and run again; the answer follows.

The value form takes JSON: a map, a bare number, or plain text. When
the slot's type is already known it renders one field per key instead,
and its `{} raw` button switches back to the raw editor.

## What we glossed over

- **What "slot" actually is** as a database entity — Lesson 04.
- **What "inheritance" really means in the data layer** — Lesson 03.
- **`:fn`-typed slots and higher-order functions** — Lesson 07.
- **Why `:parent` is singular here but the docs mention "multiple
  parents" (MI)** — Lesson 03.

Each of these is built on the same `fn-def` shape; we just keep
peeling layers.

## Next

[Lesson 02 — Reading a card](02-reading-a-card.md)
