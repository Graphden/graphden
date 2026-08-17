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
| **fn-def** | A pure composition — no Clojure, just bindings | `:hello-handler`, `:web-server`, `:editor-page` | Has at least one `:parent-ids` |

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
cover that in lesson 06 when we hit higher-order functions.

## Try it

In the running editor, in a namespace of your choice (or create
`tutorial/01-fn-defs`):

1. Click `+` to add a new fn. Name it `hello-handler`.
2. Set its parent to `:const`. The editor will show one free arg
   `:value`.
3. Click `:value` and bind a literal map: `{:status 200 :body "Hello!"}`.
4. Open the row's `⋯` actions popover and click ▶ Run — you
   should see the map come back.

## What we glossed over

- **What "slot" actually is** as a database entity — Lesson 03.
- **What "inheritance" really means in the data layer** — Lesson 02.
- **`:fn`-typed slots and higher-order functions** — Lesson 06.
- **Why `:parent` is singular here but the docs mention "multiple
  parents" (MI)** — Lesson 02.

Each of these is built on the same `fn-def` shape; we just keep
peeling layers.

## Next

Lesson 02 — Parents and inheritance ([already written](02-parents-and-inheritance.md))
