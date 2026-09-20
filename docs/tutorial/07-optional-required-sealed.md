# Lesson 07 — Optional, required and sealed

**Goal**: by the end of this lesson you can tell, on any card, which
inputs a fn can run without, which it insists on, and which nobody
below it may touch — and you can set each of those on your own fns.

**Concepts introduced**: `optional slot` (`:required false`), the
`required` ratchet, `final value`, `sealed slot` (`:terminal`), the
lock badge `🔒` and its popover.

## Three decisions that live beside a value

A slot carries more than what is bound to it. Three author decisions
ride along, and each shows on the card:

| Decision | Where it is declared | On the card |
|---|---|---|
| **Optional** — the fn runs without it | `:required false` on the slot's declaration | the `+` is dimmed; the lock badge says *Optional* |
| **Required here** — a descendant insists on an inherited optional | `:required true` on that fn's binding | the `+` is solid again; the badge says *required since …* |
| **Sealed** — no descendant may bind this slot | `:terminal true` on a binding | a `🔒` on the label; descendants get a lock where the `+` would be |

And one that needs no flag at all: a **value**, once set, is final.

## Optional

A base function's argument declared `{:type :int :required false}`
may be left unbound: the implementation has a fallback. `subs` is one
— `end` defaults to the string's length:

```edn
{:name :subs
 :args {:string {:type :text}
        :start  {:type :int}
        :end    {:type :int :required false}}}
```

On a card the free `end` is drawn like any free slot, but its `+` is
**dimmed**, and the small lock after its type chip — `🔓`, faint until
you hover the label — reads *Optional — the fn runs without it*. The
Run form (lesson 15) lists it the same way. Bind it or don't; either
runs.

Optional is a property of the slot's **declaration**, so it is the
declaring fn's to change: on a slot you own (a renamed view from
`{:as …}`, lesson 05, or a field of your own type row) the popover
offers **Optional** as a checkbox. On a slot a base fn declares it is
just a fact you read.

## Required — a one-way ratchet

Your fn may need what its parent could live without. Tick **Require
it here** and every fn from yours downwards must supply `end`: the
`+` is solid, and a run without it fails with
`:execution-error/missing-required-arg`. In `fns.edn`:

```edn
{:name :cut-to
 :parent :subs
 :args {:end {:required true}}}
```

It only goes one way. Once an ancestor made a slot required, no
descendant can make it optional again — the popover shows *already
required above* and leaves the box disabled. Loosening would break
every caller that trusted the tighter contract; tightening breaks
nobody above you.

## Final values

Bind `string` to `"graphden"` on your fn and extend that fn: on the
child's card `string` is not there. Not hidden — **final**. A value an
ancestor set is the ancestor's decision; the editor offers no `+` for
it and the API refuses a binding on it (`value-override`). Want a
different string? Extend a different fn, or the base. This is the LEGO
rule from lesson 03: arguments are not overridden, a different
behaviour is a different fn-def — which is also why the standard
library composes by *parents* (`json-ok-response` is
`json-content-type` + `ok-response`) rather than by re-binding.

Lists are the exception that isn't: an inherited list stays open for
appending unless it is closed (lesson 06), because appending extends
the ancestor's decision instead of replacing it.

## Sealed — final before the value

A **template** slot — left free on purpose so callers fill it — can
still be one you do not want *descendants* to fill: the fn that
extends yours should pass it on, not decide it. Click the `🔓`, tick
**Seal against descendants**, **Save**. The badge is `🔒`; on every
child's card the slot shows a lock instead of a `+`, with the reason
on hover (*Sealed in cut — descendants cannot bind this slot*), and
the server refuses the write behind it (`terminal-seal`).

A seal is the explicit form of what a value does implicitly, and it
is yours to lift — untick it where you set it. The sealing fn may
still bind its own slot; a descendant can neither bind nor unseal it.
In `fns.edn`:

```edn
{:name :cut
 :parent :subs
 :args {:end {:terminal true}}}
```

## Reading the badge

One glyph, three facts, everywhere a slot is drawn:

- `🔓`, faint — nothing decided; on your own fn, click to decide.
- `🔒` — something is in force. Hover: *Sealed here* or *Sealed in
  cut*; *List closed in …*; *Optional*; *required since …*. The
  popover repeats the inherited ones read-only and lets you change
  the ones that are yours.
- A lock where a `+` should be — sealed, or a closed list, above you.
  Hover it for who, and where to lift it.

## Try it

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=07)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

Run a fn without its optional input, seal a slot, then require it:

1. Type `subs` in the Explorer filter and click the `subs` row
   (`core.strings`). Three free slots; `end`'s `+` is dimmed.
2. `⋯` → **Extend** → `tutorial-cut` → **Save**.
3. Hover the `end` label and its faint `🔓`: *Optional — the fn runs
   without it.*
4. `+` on `string` → **Bind literal** → `graphden` → **Save**; `+`
   on `start` → `5` → **Save**. Leave `end` alone.
5. `⋯` → **▶ Run** → **Run**: `"den"` — `end` defaulted to the end
   of the string.
6. Click the `🔓` after `end`, tick **Seal against descendants**,
   **Save**. The badge closes.
7. `⋯` → **Extend** → `tutorial-cut-more` → **Save**. On the child,
   `string` and `start` are gone — final — and `end` shows a lock
   where its `+` would be. Hover it.
8. Back on `tutorial-cut` (filter, click the row), open the `🔒`
   again: untick **Seal**, tick **Require it here**, **Save**.
9. Open `tutorial-cut-more` once more: the `+` on `end` is back, and
   no longer dimmed — from `tutorial-cut` down, `end` must be
   supplied.

## What we glossed over

- **The Run form** — how optional and required inputs are asked for
  at run time: lesson 15.
- **Closed lists** — the list slot's own seal: lesson 06.
- **Descriptions on slots** — the `i` beside the lock, lesson 02.
- **Types as constraints** — narrowing a slot's type in a descendant
  is the other ratchet; lesson 08.

## Next

[Lesson 08 — Types](08-types.md)
