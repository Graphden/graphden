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
 :args  {:value "Hello!"}}
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
 :args  {:value {:status 200 :body "ok"}}}

{:name :authed
 :parent :base}
```

`:authed` inherits from `:base`, which inherits from `:const`.
The **inheritance chain** of `:authed` is:

```text
:authed → :base → :const
```

When the executor runs `:authed`, it walks this chain to find:

- Which **slots** are visible (the union of slots declared by
  every ancestor).
- Which **bindings** apply per slot (the CLOSEST binding to the
  current fn wins).

For `:authed.:value` — `:const` declares the slot, `:base` binds it
to a literal map, `:authed` adds nothing. The closest binding is
`:base`'s, so `:authed` returns the same map.

If `:authed` had its own binding, that would override:

```edn
{:name :authed-different
 :parent :base
 :args  {:value {:status 401 :body "denied"}}}
```

Now `:authed-different` returns the `401` map. Closer wins.

## Multiple inheritance (MI) — when you'd use it

Sometimes a fn cleanly belongs to two parents at once — two
**orthogonal axes** you want to combine. This is exactly how
graphden builds its HTTP response matrix (see the real
`web.response` module). One axis sets the status code; the other
sets the content-type header; a concrete response is the two
combined:

```edn
{:name :ok-response           ; STATUS axis: binds :status
 :parent :ring-response
 :args  {:status 200}}

{:name :json-content-type     ; CONTENT-TYPE axis: binds :headers
 :parent :ring-response
 :args  {:headers {"Content-Type" "application/json"}}}

{:name :json-ok-response      ; combine both axes
 :parents [:json-content-type :ok-response]}
```

`:json-ok-response` inherits slots from BOTH `:json-content-type`
and `:ok-response`. The inheritance chain becomes a BFS closure:

```text
:json-ok-response
   ↓ ↓
:json-content-type  :ok-response
       ↓ ↓
    :ring-response
```

This is **diamond inheritance** — both parents themselves inherit
from `:ring-response`. That's fine here because each axis binds a
DIFFERENT arg (`:headers` vs `:status`), so there's no conflict;
the remaining `:body` slot stays unbound and propagates to the
child as a free argument. Bindings still resolve closest-wins.

### MI restrictions

Two failure modes guard an MI parent set:

- **Arg-name collision** — two parents expose DIFFERENT slots
  under the same user-visible name. This is a structural gate:
  the save itself is rejected, in the editor and at package sync
  alike. The name has to be disambiguated (rename one side)
  before the row lands.
- **Conflicting contracts on a SHARED slot** — both parents bind
  the same inherited slot to incompatible values, or pin it to
  incompatible types (neither a subtype of the other). This is a
  TYPE error: at package sync it fails the load; in the editor
  the fn still SAVES, and the conflict surfaces as a recorded
  diagnostic — ⚠ badge on the card, an entry in the diagnostics
  bar's "Type errors" tab (Lesson 03) — with
  execution refused until one
  side backs off.

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

In the editor, in a namespace of your choice:

1. Find `:add` (a base function). Its card shows ONE row —
   `:add` has no parents.
2. Create `:add-10`, a fn-def parented to `:add` that seeds the
   `:nums` list with `10`:

   ```edn
   {:name :add-10
    :parent :add
    :args  {:nums [10]}}
   ```

   Its card shows TWO rows: `:add-10` (with the bound `:nums`)
   and `:add` below it.

   Doing this through the editor's **⋯ → Extend** instead: the
   popover's **in** line picks the child's namespace. Extending
   your own fn defaults to the parent's namespace (the module
   stays together); extending a *platform* fn defaults to your
   last-used namespace — a child of `:add` belongs to your
   project, not to `core.arithmetic`. Change it right there if
   you want it elsewhere; a fn can also be moved later via
   **⋯ → Namespace → Move to another namespace…**, and renamed via
   **⋯ → ✎ Rename** (both are always safe, callers included —
   everyone references the fn by identity, not by its name or
   namespace).

   The mirror of Extend is **⋯ → ⬆ Wrap in new fn**: where Extend
   creates a child *under* the fn, Wrap builds a caller *above*
   it — pick the wrapping parent (which fn should process this
   one's result, say `:to-str`), name the wrapper, choose the slot
   that receives the fn, and the editor creates the new fn with
   the current one already bound in, then opens it. Compatible free
   slots sort first with a ✓; picking a slot marked "(bound — will
   override)" is legal too — the wrapper's own binding wins over the
   parent's (closest-fn-wins, Lesson 03). That is how
   you add a step on top of existing logic without re-assembling
   it by hand.
3. Try writing a multiple-inheritance fn-def over the two real
   response axes:

   ```edn
   {:name :tutorial-json-ok
    :parents [:json-content-type :ok-response]}
   ```

   The card now branches: your fn on top, the two axes
   (`:json-content-type` and `:ok-response`) below it, and their
   shared `:ring-response` ancestor beneath both — the diamond you
   saw above. Each axis row is clickable, so you can walk into
   either branch. `:tutorial-json-ok` leaves `:body` free; bind it
   (e.g. `"{}"`) and Run to get a `200 application/json` response.

## What we glossed over

- **Slots as DB entities** — when two parents declare a slot
  with the same name, how does the slot identity work? Lesson 03.
- **Free arguments** — what if a slot is declared but NEITHER
  ancestor binds it? Lesson 04.
- **HOF-typed slots** — `:fn`-typed slots behave differently
  during inheritance. Lesson 06.

## Next

Lesson 03 — Slots and bindings ([already written](03-slots-and-bindings.md))
