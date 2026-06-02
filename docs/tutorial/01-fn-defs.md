# Lesson 01 — Anatomy of a fn-def

**Goal**: by the end of this lesson you can write a fn-def by hand
and explain each part of it.

**Concepts introduced**: `fn-def`, `:name`, `:parent`, `:args`,
`base function`, `fns.edn`.

## The smallest meaningful fn-def

```edn
{:name :hello-handler
 :parent :const
 :args  {:x {:status 200 :body "Hello from Graphden!"}}}
```

That's a complete fn-def. Three keys:

- **`:name`** — what you'll call this fn elsewhere. Names are
  globally unique inside a graphden instance.
- **`:parent`** — the fn this one inherits from. Here `:const`
  is a base function (a built-in primitive) that returns its
  `:x` argument unchanged when executed.
- **`:args`** — values that fill in the parent's slots. `:const`
  exposes one slot called `:x`; we bind it to a map.

Executing `:hello-handler` returns the map `{:status 200 :body "Hello from Graphden!"}`.

## Where fn-defs live

Two places:

1. **`resources/packages/<pkg>/<module>/fns.edn`** — a vector of
   fn-def maps, loaded at startup. This is the source-of-truth
   form. See [PACKAGES.md](../PACKAGES.md).
2. **Inside the running editor** — when you click `+` in a
   namespace, the editor creates a fn entity directly via
   `/api/fns`. Same shape, different entry point.

For this lesson assume you're typing into the editor. The
`fns.edn` form is what you'd write for code review.

## Two kinds of fn

Look at the `:parent` field. There are two kinds of fn you can
parent to:

| Kind | What it is | Example | How to spot one |
|---|---|---|---|
| **Base function** | A small Clojure impl wrapping one library call | `:const`, `:add`, `:render-hiccup`, `:pg-query` | Has `:impl-hash`, no `:parent-ids` |
| **fn-def** | A pure composition — no Clojure, just bindings | `:hello-handler`, `:web-server`, `:editor-page` | No `:impl-hash`, has at least one `:parent-ids` |

You can parent a new fn-def to either kind. Inheritance works
the same way for both.

## A fn-def that uses a fn-def

Now let's compose two:

```edn
{:name :hello-handler
 :parent :const
 :args  {:x {:status 200 :body "Hello!"}}}

{:name :hello-route
 :parent :assoc
 :args  {:m {} :k "handler" :v :hello-handler}}
```

`:hello-route` parents to `:assoc` — a base function that returns
`{k v}` merged into `m`. The interesting part: `:v` is bound to
`:hello-handler` (the keyword form of its name). When
`:hello-route` runs:

1. The executor sees the ref `:hello-handler` in slot `:v`.
2. It looks at `:assoc`'s slot type for `:v` — not `:fn`-typed.
3. So it **executes** `:hello-handler` (gets the response map)
   and uses that as the value of `:v`.

The same syntax in a `:fn`-typed slot would behave differently —
the fn-id would be passed unchanged for the parent to invoke. We'll
cover that in lesson 06 when we hit higher-order functions.

## Try it

In the running editor, in a namespace of your choice (or create
`tutorial/01-fn-defs`):

1. Click `+` to add a new fn. Name it `hello-handler`.
2. Set its parent to `:const`. The editor will show one free arg
   `:x`.
3. Click `:x` and bind a literal map: `{:status 200 :body "Hello!"}`.
4. Click the ▶ Run button — you should see the map come back.

## What we glossed over

- **What "slot" actually is** as a database entity — Lesson 03.
- **What "inheritance" really means in the data layer** — Lesson 02.
- **`:fn`-typed slots and higher-order functions** — Lesson 06.
- **Why `:parent` is singular here but the docs mention "multiple
  parents" (MI)** — Lesson 02.

Each of these is built on the same `fn-def` shape; we just keep
peeling layers.

## Next

[Lesson 02 — Parents and inheritance](02-parents.md) (planned)
