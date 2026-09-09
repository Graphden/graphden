# Lesson 03 — Slots and bindings at the data level

**Goal**: by the end of this lesson you can name the four entities
that make up graphden's slot/binding model and explain why
they're separate.

**Concepts introduced**: `slot`, `fn-slot`, `binding`,
`binding-list-item`, `slot identity`, rename-view slots
(`slot.source-slot-id`), `type-override`.

## Why four entities instead of one

If a "slot binding" were ONE row carrying `(fn-id, slot-name,
value)`, graphden couldn't:

- Let two fn-defs share a slot identity. Inheritance would have
  to walk by NAME, which breaks when ancestors rename.
- Express a value that lives in TWO places (parent binds it,
  child overrides it).
- Express sequence bindings like `:list-append` cleanly.

So the data model splits the concern across four entities (plus one
self-referencing field on `slot` for renames):

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
fn shows up under the Explorer's **⚠ type errors** lens (its row
marked `⚠1`, the message under the argument in the Inspector's
Bindings tab), and trying to EXECUTE it is refused until you fix
the type. (Structural
violations — cycles, name collisions — and secret-flow
violations still reject the save outright.)

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=03)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

One slot, many bindings — prove it with two children of one fn:

1. Type `str-upper` in the Explorer filter and click the
   `str-upper` row (`core.strings`). It declares one slot,
   `:string`; its type chip says `text`.
2. Click `⋯` on the `str-upper` row, choose **Extend**, name it
   `tutorial-a`, then **Save**. The `:string` row on the new card
   comes from `str-upper`: the slot is INHERITED, not copied.
3. Click the `+` on `:string`, choose **Bind literal**, type
   `alpha`, then **Save**.
4. Select `str-upper` again (filter for it). Its `:string` is still
   unbound — your binding lives on `tutorial-a`, not on the slot.
5. **⋯ → Extend** again, name this one `tutorial-b`, **Save**. Same
   slot identity as `tutorial-a`'s, and an empty `+` again, because
   `tutorial-b` has no binding of its own yet.
6. Click the `+` on `:string`, **Bind literal**, type `beta`,
   **Save**.

`tutorial-a` says `alpha`, `tutorial-b` says `beta`, `str-upper`
stays open — one slot identity, three independent binding states.
That separation is why inheritance never copies anything.

### Going further (fns.edn / MCP only)

A rename and a type override on the same slot, in one fn-def:

```edn
{:name :tutorial-renamed-input
 :parent :str-len
 :args  {:string {:as :input :type :non-blank-text}}}
```

Open its card: the arg row now says `:input` (renamed) and its
type chip says `non-blank-text` (narrowed). The provenance ↳ badge
shows where each came from — `:input` from
`:tutorial-renamed-input`'s slot, `:non-blank-text` from the
type-override binding. Clicking the type chip in the editor offers
only types that NARROW `:text` — `:any` isn't listed (the
compatible-type select is described in [Lesson 05](05-types.md)).

To see a type diagnostic land the way it actually happens — with a
value — extend `:http-server` as `:tutorial-bad-port` and bind its
`:port` (type `port`, a refined `:int`, 1..65535) to `-1`. The live
status flips to ✗, but Save still LANDS: the card root row gains a
⚠ badge, the Explorer's **⚠ type errors** lens counts it, the
Inspector's Bindings tab shows the diagnostic under `port`, and
**▶ Run** is REFUSED with "unresolved type errors". Bind `:port`
to `8081` and the fixing save clears all of it.

## What we glossed over

- **Free arguments** — when no ancestor binds a slot, it becomes
  a "free arg" the caller must supply. Lesson 04.
- **The `:fn` slot type** — slots can also expect callables, and
  those flow differently. Lesson 06.
- **Per-branch evolution of bindings** — how a binding's
  `value` lives on a `binding-version` row scoped to a branch.
  Lesson 20.

## Next

[Lesson 04 — Free arguments](04-free-arguments.md)
