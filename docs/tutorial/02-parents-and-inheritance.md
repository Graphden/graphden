# Lesson 02 — Parents and inheritance

**Goal**: by the end of this lesson you understand what `:parent`
actually does at the data level, when to use `:parents` (plural),
and why graphden's inheritance model is "BFS through `parent-ids`,
closer-wins on bindings."

**Concepts introduced**: `parent-ids`, `single inheritance`,
`multiple inheritance (MI)`, `BFS closure`, `closest-wins binding`,
`override`, `inheritance chain`.

## A `:parent` is a row pointer

When you write:

```edn
{:name :hello-handler
 :parent :const
 :args  {:x "Hello!"}}
```

…graphden stores `:hello-handler` as a `:fn` row whose
`:parent-ids` field contains exactly one entry: `:const`'s row id.
That's it. The field is called `parent-ids` (plural) even when
you wrote `:parent` (singular) — both forms write into the same
column. `:parent :const` is shorthand for `:parents [:const]`.

Two consequences:

1. A fn with `:parent-ids []` has NO ancestors. It's a base
   function or a primitive type-row.
2. A fn can have **more than one** parent (multiple inheritance).
   We'll see when you'd want that.

## Single inheritance — the common case

```edn
{:name :base
 :parent :const
 :args  {:x {:status 200 :body "ok"}}}

{:name :authed
 :parent :base}
```

`:authed` inherits from `:base`, which inherits from `:const`.
The **inheritance chain** of `:authed` is:

```
:authed → :base → :const
```

When the executor runs `:authed`, it walks this chain to find:

- Which **slots** are visible (the union of slots declared by
  every ancestor).
- Which **bindings** apply per slot (the CLOSEST binding to the
  current fn wins).

For `:authed.:x` — `:const` declares the slot, `:base` binds it
to a literal map, `:authed` adds nothing. The closest binding is
`:base`'s, so `:authed` returns the same map.

If `:authed` had its own binding, that would override:

```edn
{:name :authed-different
 :parent :base
 :args  {:x {:status 401 :body "denied"}}}
```

Now `:authed-different` returns the `401` map. Closer wins.

## Multiple inheritance (MI) — when you'd use it

Sometimes a fn cleanly belongs to two parents at once. Classic
example: a route handler that BOTH parents from a JSON-response
template AND from an auth-required mixin.

```edn
{:name :json-handler          ; template for JSON responses
 :parent :ring-handler
 :args  {:body {:value {}}}}

{:name :auth-required         ; mixin that wraps the handler in auth
 :parent :ring-handler
 :args  {:guard :require-bearer-token}}

{:name :secure-data-handler
 :parents [:json-handler :auth-required]
 :args  {:body :load-user-data}}
```

`:secure-data-handler` inherits slots from BOTH `:json-handler`
and `:auth-required`. The inheritance chain becomes a BFS closure:

```
:secure-data-handler
   ↓ ↓
:json-handler  :auth-required
       ↓ ↓
   :ring-handler
```

Slots from `:json-handler` AND `:auth-required` are both
exposed at `:secure-data-handler`. Bindings still resolve
closest-wins.

### MI restrictions

The type-checker rejects MI when:

- Two parents bind the SAME slot to incompatible values (silent
  conflict). One side has to back off.
- Two parents declare slots with the same name but incompatible
  types.

These are sync-time errors, so you'll find out before the row
lands in the DB.

## The fn-card as a chain visualizer

Open any composed fn in the editor. The card shows multiple
ROWS, each labeled with an ancestor's name. The TOP row is the
fn itself; the rows below are its ancestors in BFS order. Each
row carries:

- The ancestor's name (clickable — navigates to that fn).
- Any binding that ancestor contributed for the slot you're
  looking at.

Clicking an ancestor row OPENS that ancestor on a new card. So
you can walk the chain visually.

## Try it

In the editor:

1. Find `:add` (a base function). Its card shows ONE row —
   `:add` has no parents.
2. Find `:add-10` (a tutorial fn-def parented to `:add` with
   `:nums = [10]`). Its card shows TWO rows: `:add-10` (with
   the bound `:nums`) and `:add` below it.
3. Try writing a MI fn-def:

   ```edn
   {:name :tutorial-mi-example
    :parents [:add :str-concat]
    :args  {:nums [1 2] :separator ", "}}
   ```

   The card now shows THREE rows. The top is your fn,
   the next two are `:add` and `:str-concat` in BFS order.

## What we glossed over

- **Slots as DB entities** — when two parents declare a slot
  with the same name, how does the slot identity work? Lesson 03.
- **Free arguments** — what if a slot is declared but NEITHER
  ancestor binds it? Lesson 04.
- **HOF-typed slots** — `:fn`-typed slots behave differently
  during inheritance. Lesson 06.

## Next

Lesson 03 — Slots and bindings (planned — see [tutorial/README.md](README.md))
