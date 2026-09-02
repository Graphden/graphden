# Lesson 03 — Slots and bindings at the data level

**Goal**: by the end of this lesson you can name the five entities
that make up graphden's slot/binding model and explain why
they're separate.

**Concepts introduced**: `slot`, `fn-slot`, `binding`,
`binding-list-item`, `slot identity`, rename-view slots
(`slot.source-slot-id`), `type-override`.

## Why three entities instead of one

If a "slot binding" were ONE row carrying `(fn-id, slot-name,
value)`, graphden couldn't:

- Let two fn-defs share a slot identity. Inheritance would have
  to walk by NAME, which breaks when ancestors rename.
- Express a value that lives in TWO places (parent binds it,
  child overrides it).
- Express sequence bindings like `:list-append` cleanly.

So the data model splits the concern across five entities:

| Entity | What it is | Lives across branches? |
|---|---|---|
| `slot` | An atomic `(name, type-fn-id)` pair. Immutable. | Identity row, shared across branches |
| `fn-slot` | Junction: "fn `F` exposes slot `S` at position `P`" | Per-branch version rows |
| `binding` | Per-`(fn, slot)` customization: `value`, `ref-fn-id`, type override, etc. | Per-branch version rows |
| `binding-list-item` | Sequence content under a list-typed binding | Per-branch version rows |
| `slot.source-slot-id` | When set, this slot is a RENAMED view of another | Identity (immutable) |

The `fn` row joins them all.

## Walking through one fn

```edn
{:name :add-10
 :parent :add
 :args  {:nums [10]}}
```

What's actually in the DB after sync?

```text
fn:           {id: add-10-id, name: "add-10", parent-ids: [add-id]}
slot:         {id: add.nums-slot-id, name: "nums", type-fn-id: sequence}
fn-slot:      {fn-id: add-id, slot-id: add.nums-slot-id, position: 0}
binding:      {fn-id: add-10-id, slot-id: add.nums-slot-id,
               value: nil, ref-fn-id: nil,
               list-append: true, list-closed: nil}
binding-list-item: {binding-id: ..., position: 0, value: 10}
```

Three load-bearing things here:

1. The **slot** is owned by `:add`. `:add-10` doesn't get its
   own slot row — it INHERITS via `:parent-ids` BFS.
2. The **binding** lives on `:add-10`, attaching to `:add`'s
   slot. The `list-append: true` flag says "extend the
   inherited sequence, don't replace it."
3. The **binding-list-item** carries the actual literal `10`.

## When does a fn own its OWN slot?

Only when it adds a NEW arg name not seen in its ancestors.
Base functions declare their slots — that's how they introduce
new vocabulary. Composed fn-defs that just bind their parent's
slots don't add new ones.

The exception is **renames** (next section).

## Renames — when the name has to change

Say you have:

```edn
{:name :str-len
 :args {:string {:type :text}}}
```

…and you want a descendant `:hello-len` that runs
`str-len` but calls its arg `:input` (because callers shouldn't
see the underlying name):

```edn
{:name :hello-len
 :parent :str-len
 :args  {:string {:as :input}}}
```

`{:as :input}` is a rename. It writes a NEW `slot` row owned by
`:hello-len`, whose `:source-slot-id` points back at
`:str-len.:string`'s slot id. Descendants of `:hello-len` see the
slot under the name `:input`; binding it goes to the new slot id,
but the resolver walks `source-slot-id` to find the original
type, default binding, etc.

This is why `slot` has an FK to itself — renames form a chain.

## Type overrides — narrowing without renaming

A binding can override the inherited type:

```edn
{:name :positive-len
 :parent :str-len
 :args  {:string {:type :non-blank-text}}}
```

`{:type :non-blank-text}` writes `:type-override-fn-id` on the
binding, pointing at `:non-blank-text`'s row. The slot itself
stays at `:text`, but THIS fn (and its descendants) sees the
narrower type. Useful for asserting a contract at a chain hop
without forcing a rename.

Constraint: descendant overrides can only NARROW (subtype of
the inherited type) — "you can't promise less than your parent
did." A widening is a type ERROR, but it does not block the
save: the write succeeds and the failure is recorded as a
per-branch type diagnostic. The fn's card gets a ⚠ badge, the
fn shows up in the **Type errors** tab of the diagnostics bar
(the collapsible strip under the canvas), and trying to
EXECUTE it is refused until you fix the type. (Structural
violations — cycles, name collisions — and secret-flow
violations still reject the save outright.)

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial={id})
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

1. Find `:str-len` in the editor. Click its `:string` slot
   chip. Note its declared type (`:text`).
2. Create a descendant:

   ```edn
   {:name :tutorial-renamed-input
    :parent :str-len
    :args  {:string {:as :input :type :non-blank-text}}}
   ```

3. Open `:tutorial-renamed-input`'s card. The arg row now says
   `:input` (renamed) and its type chip says `non-blank-text`
   (narrowed). The provenance ↳ badge shows where each came from
   — `:input` from `:tutorial-renamed-input`'s slot,
   `:non-blank-text` from the type-override binding.
4. Try widening: click the arg's type-chip. The compatible-type
   select offers only types that NARROW `:text` — `:any` isn't
   even listed. Widening is a type error, and while a type error
   would no longer block the save (it records a diagnostic —
   below), the select simply doesn't offer one.
5. So break a type the way it actually happens — with a value.
   Create `{:name :tutorial-bad-port :parent :http-server}` and
   click its `:port` arg (type `port`, a refined `:int` —
   1..65535). The number widget opens; type `-1`. The live
   status flips to ✗ (refinement violated) — but Save still
   LANDS. The fn is now flagged: its card root row gains a ⚠
   badge (hover: "1 type error on this fn — see the Type errors
   panel") and the diagnostics bar's **Type errors** tab (under
   the canvas) lists
   `:tutorial-bad-port` with the refinement diagnostic.
6. Press ▶, tick the side-effects acknowledgement, and hit Run
   (no need to fill the free args — the refusal fires before
   anything executes): execution is REFUSED — "unresolved type
   errors", naming the fn and the mismatch. The graph keeps
   your work-in-progress, but won't run it.
7. Edit `:port` again to `8081`. The fixing save clears the
   recorded diagnostic — the ⚠ badge and the panel entry
   disappear.

## What we glossed over

- **Free arguments** — when no ancestor binds a slot, it becomes
  a "free arg" the caller must supply. Lesson 04.
- **The `:fn` slot type** — slots can also expect callables, and
  those flow differently. Lesson 06.
- **Per-branch evolution of bindings** — how a binding's
  `value` lives on a `binding-version` row scoped to a branch.
  Lesson 20 (already written).

## Next

Lesson 04 — Free arguments ([already written](04-free-arguments.md))
