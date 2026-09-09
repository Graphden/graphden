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
  TYPE error: at package sync it fails the load; in the editor it
  is a recorded diagnostic — the fn saves, gets a ⚠ badge and shows
  under the Explorer's **⚠ type errors** lens, and refuses to
  execute until one side backs off (the doctrine is in
  [Lesson 05](05-types.md), the lens in
  [Lesson 16](16-errors-and-diagnostics.md)).

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

1. Type `add` in the Explorer filter and click the `add` row (in
   `core.arithmetic`). Its card shows ONE row — a base function has
   no parents.
2. Click `⋯` on the `add` row, choose **Extend**, type `add-10`,
   then **Save**. That creates a child whose `:parent` is `:add` —
   in `fns.edn` terms:

   ```edn
   {:name :add-10
    :parent :add
    :args  {:nums [10]}}
   ```

   The popover's **in** line picks the child's namespace. Extending
   your own fn defaults to the parent's namespace (the module
   stays together); extending a *platform* fn defaults to your
   last-used namespace — a child of `:add` belongs to your
   project, not to `core.arithmetic`. Change it right there if
   you want it elsewhere; a fn can also be moved later via
   **⋯ → Namespace → Move to another namespace…**, and renamed via
   **⋯ → ✎ Rename** (both are always safe, callers included —
   everyone references the fn by identity, not by its name or
   namespace).
3. The editor opens `add-10`: its card shows TWO rows, `add-10` on
   top and `add` below it. `add-10` inherited `add`'s `:nums` slot —
   a sequence. Click the `+` placeholder on `:nums`, choose
   **Append literal**, enter `10`, then **Save**. The seed becomes
   the list's first item.
4. Click `⋯` on the `add-10` row, then **▶ Run**, then **Run**. The
   bound seed makes the sum `10` — the child runs the parent's
   implementation with its own bindings.
5. Now go the other way. Extend went DOWN — a child under `add`.
   **⋯ → ⬆ Wrap in new fn** goes UP: a new fn that CALLS `add-10`
   and does something with its result. Click `⋯` on the `add-10`
   row, choose **⬆ Wrap in new fn**, type `to-str` in the picker
   and pick `core.strings.to-str` — the wrapper's parent. Name it
   `add-10-text`, leave the **into** slot on `:value`, then
   **Save**. The editor creates the wrapper with `add-10` already
   bound into it and opens it: your sum, stringified.

   Compatible free slots sort first with a ✓; picking a slot marked
   "(bound — will override)" is legal too — the wrapper's own
   binding wins over the parent's (closer wins, as above). That is
   how you add a step on top of existing logic without
   re-assembling it by hand.
6. Try writing a multiple-inheritance fn-def over the two real
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

[Lesson 03 — Slots and bindings](03-slots-and-bindings.md)
