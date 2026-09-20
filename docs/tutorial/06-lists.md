# Lesson 06 — Lists: seed, append, close

**Goal**: by the end of this lesson you can read a list slot on the
card, seed it in one fn and extend it in a child, tell what
`list-append` stores, and close a list so that nothing below you can
add to it.

**Concepts introduced**: `list slot`, `item`, `append tail`, `seed`,
`list-append`, `inherited items`, `closed list` (`:list-closed`), the
lock badge `🔒` on an edge label.

## A list slot on the card

Most slots take one value — a literal or a fn. A **list slot** takes
several: its type is `[:list T]` (lesson 08 has the type story), and on
the card its edge is a **trunk** that fans out after the type chip:
one branch per item, and a last dashed branch to a `+` — the **append
tail**, the list's next free place.

```text
nums [any] ─┬─▶ 1
            ├─▶ 2
            └╌╌▶ +      ← the tail: append here
```

That is why the `+` on a list offers **Append literal / Append
fn-ref** where a scalar slot offers **Bind**: a container holds items,
not a single value. An item you own carries a `×` to remove it; the
order of the items is the order of the branches.

At the data level each item is a `binding-list-item` row under the
slot's binding (lesson 04 showed the dump):

```text
binding:           {fn-id: add-10, slot-id: nums, list-append: true}
binding-list-item: {binding-id: …, position: 0, value: 10}
```

## Seed in the parent, append in the child

A list you inherit is an ancestor's binding, so at level 0 your card
does not draw it (lesson 02: a card draws what the fn binds itself).
Unfold the ancestor's row and it appears — the items your ancestor
bound, each marked `↖` (hover it: *Inherited from …*), and after them
a tail `+` that is **yours**. Appending there does not touch the
parent: it creates a `list-append` binding on your fn whose items
follow the inherited ones. From then on your card shows the whole
list at level 0 — the parent's items and yours — because now the list
is something you bind. The parent keeps its list; you have the
parent's list plus yours.

```edn
{:name :base-sum  :parent :add  :args {:nums [1 2]}}
{:name :sum-more  :parent :base-sum
 :args {:nums {:append [3]}}}         ; runs add over [1 2 3]
```

`:nums [1 2]` and `:nums {:append [1 2]}` are the same thing — a
vector literal on a list slot IS an append binding, which is why the
dump above says `list-append: true` for a plain seed. The map form
exists for the flags below.

This is the same closest-wins walk as any binding (lesson 03), only a
list's "closest" binding *extends* the chain instead of replacing it.
A **scalar** value an ancestor set is final and stays off your card
entirely (lesson 07); a **list** an ancestor seeded stays open unless
someone closes it.

## Closing a list

Sometimes the seed is the whole point: a fixed set of middleware, the
columns of a report, the parents a type row accepts. **Close** the
list and no descendant can append to it: where their tail would be —
under the unfolded row, or on their own card once they have appended
— they see a lock `🔒`, and the server refuses the write behind it
(`list-closed`).

On the canvas the lock lives on the edge label, after the type chip:
click it on a list you own, tick **Close the list**, **Save**. The
label shows `🔒`; for every descendant the tail becomes a lock with
the reason on hover (*List closed in base-sum — descendants
cannot append*). Untick to reopen. Closing does not seal your own
tail — the list is yours to grow; it is closed *downwards*.

In `fns.edn`:

```edn
{:name :base-sum :parent :add
 :args {:nums {:append [1 2] :closed true}}}
```

Only the fn that owns the list can close it, and only when the list
has a binding to carry the flag — append an item first. A descendant
sees the lock, cannot lift it, and cannot close a list *above* itself
either; it can, of course, close its own additions for *its*
descendants.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=06)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

Seed a list, extend it from a child, then close it:

1. Type `add` in the Explorer filter and click the `add` row
   (`core.arithmetic`). Its `:nums` is a list slot — one `+`.
2. `⋯` on the row → **Extend**, name it `tutorial-base-sum`,
   **Save**. The editor opens the child.
3. Click the `+` on `:nums` → **Append literal** → `1` → **Save**.
   Then the tail `+` → `2` → **Save**. The trunk fans out to `1`,
   `2` and a new tail.
4. Each item carries its own small buttons on its branch: `↑` `↓`
   move it, `+` inserts before it, `×` removes it. Click `↑` on the
   `2` — the list reads `2, 1`. Then `+` on the `1` → **Insert
   literal** → `0` → **Save**: `2, 0, 1`. Order is the items'
   `position`, and it is yours to change; an append can be an
   insert, and the later items shift down.
5. `⋯` on `tutorial-base-sum` → **Extend** → `tutorial-sum-more` →
   **Save**. The new card shows no `:nums` yet — the list is the
   parent's. Click the `tutorial-base-sum` row on the card to unfold
   it: `2`, `0`, `1` appear, each with `↖`, and after them a tail
   `+` of your own.
6. Click that tail → **Append literal** → `3` → **Save**. Four
   items on the canvas, one binding on each fn — and the list now
   stays on your card folded or not.
7. `⋯` → **▶ Run** → **Run**: `6`. Run `tutorial-base-sum` and it
   still says `3` — you extended, you did not edit.
8. Back on `tutorial-base-sum` (filter, click its row), click the
   `🔒` after `:nums`' type chip, tick **Close the list**, **Save**.
   The badge is a closed lock now.
9. Open `tutorial-sum-more` again: its `3` is still there, but the
   tail is a lock — hover it. Nothing below `tutorial-base-sum` can
   append any more.

## What we glossed over

- **fn-ref items' order** — a fn bound as an item has no edge
  buttons; its `↑` `↓` and **+ Insert item before** sit in the item
  card's `⋯`. Same rows, same `position` chain.
- **fn-ref items** — **Append fn-ref** puts a fn's *result* in the
  list (a component in a page's `children`, lesson 10).
- **List types** — `[:list T]` and what the checker does with each
  item: lesson 08.
- **Closing an empty list** — needs a binding to carry the flag;
  in `fns.edn` `{:append [] :closed true}` does it.

## Next

[Lesson 07 — Optional, required and sealed](07-optional-required-sealed.md)
