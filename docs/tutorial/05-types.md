# Lesson 05 — Types: atomic, refinement, record, union, variant, list

**Goal**: by the end of this lesson you can declare each kind
of type-row graphden supports, distinguish "primitive type-row"
from "structural type", and reason about why a type-row IS just
a `:fn` entity (no impl, no parents).

**Concepts introduced**: `type-row`, `primitive type`,
`refinement`, `record`, `union`, `variant`, `list`, `type
alias`, `inline vs named`, `[:secret T]`.

One doctrine to carry through the lesson: when the checker finds
a subtype mismatch in something you save, that's a recorded
DIAGNOSTIC on the fn — not a save-blocker (the write lands; the
fn is flagged and refuses to execute until fixed) — with two
exceptions that still hard-reject the save: structural violations
(cycles, name collisions, MI arg-name clashes) and secret-flow
violations (`[:secret T]` into a plain slot).

## Types are fn-rows

The data layer doesn't have a separate "type" table. A type-row
IS a `:fn` row whose role is "type" — meaning:

- **Empty `:parent-ids`** — types don't inherit (they're
  primitive in the lattice sense).
- **No `:return-type-fn-id`** — types don't return anything (that
  field is the base-fn marker; type-rows never carry it).
- ONE of the type-distinguishing fields is set:
  - `:base-fn-id` + `:constraint` → REFINEMENT
  - `:element-fn-id` → LIST
  - fn-slot rows + nothing else → RECORD
  - `:constraint = [:union T1 T2 …]` → UNION
  - `:constraint = [:variant tag1 T1 …]` → VARIANT
  - All empty (just a name) → PRIMITIVE

This means slot types and fn refs use the SAME id space.
Writing `:type :port` in a slot decl points at the `:port`
fn-row (a refinement). Walking the parent-chain of `:my-fn`
and finding `:str-len` also points at a fn-row. One graph.

## Primitive types

14 baked-in primitives:

```
:null :uuid :text :int :bool :numeric
:timestamptz :jsonb :bytes :any :fn
:sequence :keyword :float
```

These are seeded at storage init (`composition/sync-primitives!`).
They have no constraint, no base, no element — just a name and
identity. Use them in slot decls directly:

```edn
{:name :greet
 :args {:name {:type :text}}     ; :text is a primitive type-row
 :return-type :text}
```

## Refinements — "an X where P holds"

A refinement narrows an existing type with a constraint:

```edn
{:name :port
 :refine {:base :int
          :constraint [:and [:>= 1] [:<= 65535]]}}
```

`:refine` writes a fn-row with:

- `:base-fn-id` = `:int`'s row id
- `:constraint` = the predicate vector

The predicate vocabulary is `[:= v]`, `[:not= v]`, `[:< v]`,
`[:> v]`, `[:<= v]`, `[:>= v]`, `[:and p1 p2 …]`,
`[:or p1 p2 …]`, `[:in [a b c]]` (membership in a finite vector),
`[:matches re]` (regex), plus a couple of others (see
`types.core/refinement-applies?`).

Refinements chain. `:non-empty-text` is `[:refine :text [:not= ""]]`
(and `:non-blank-text` is `[:refine :text [:matches "\\S"]]`).
You can refine again on top of that — the type-checker walks the
chain.

## Records — `{key type}` maps

```edn
{:name :user
 :type {:id :uuid
        :name :text
        :age :int}}
```

`:type` writes a record type-row + one `:slot` + one `:fn-slot`
junction PER field. Each slot's `type-fn-id` points at the
nested type. So `:user.:id` is a slot owned by `:user` whose
type is `:uuid`.

Records can nest:

```edn
{:name :user-with-address
 :type {:id :uuid
        :address {:street :text
                  :city   :text}}}
```

The inline `{:street ... :city ...}` writes a SECOND anonymous
record-type-row (deduped via `:anonymous-hash`) for the
address field's type. Two writes worth of storage.

When two unrelated fn-defs use the SAME inline record shape,
they share the anonymous row (the hash is canonical). So
`{:foo :int :bar :text}` written 30 times stores once.

## Lists — `[:list T]`

```edn
{:name :int-list
 :list :int}
```

`:list` writes a fn-row with `:element-fn-id = :int`. Slots
referencing this type see `[:list :int]` as the structural form.

Inline use:

```edn
{:name :sum
 :args {:nums {:type [:list :int]}}
 :return-type :int}
```

`[:list :int]` in a slot decl writes (and dedupes) an
anonymous list-type-row pointing at `:int`.

## Unions — "one of these"

```edn
{:name :json-value
 :union [:null :bool :int :numeric :text [:list :json-value] :jsonb]}
```

`:union` writes a fn-row with `:constraint = [:union T1 T2 …]`.
The type-checker accepts a value as a `:json-value` iff it
satisfies at least one branch.

Inline:

```edn
{:name :lookup
 :args {:key {:type [:union :keyword :int :text]}}}
```

`[:union :keyword :int :text]` — anonymous union row.

## Variants — tagged unions

A variant is a union where each branch is identified by a
keyword TAG:

```edn
{:name :result
 :variant [:ok  :int
           :err :text]}
```

A value typed `:result` is either `{:tag :ok :value 42}` or
`{:tag :err :value "failed"}`. The tag picks the branch; the
type-checker uses the tag to know which arm's payload-type to
enforce.

Variants are how graphden expresses sum types cleanly without
needing pattern-match syntax.

## Fn-types — `[:fn args ret]`

A slot can declare it wants a CALLABLE (see lesson 06 for what
that means at runtime):

```edn
{:name :map
 :args {:func {:type [:fn {:item a} b]}
        :coll {:type [:list a]}}
 :return-type [:list b]}
```

`[:fn {:item a} b]` — a structural fn-type with arg map `{:item
a}` and return type `b` (where `a` and `b` are TYPE VARIABLES).
The type-checker unifies `a` and `b` at the call site against
the actual `:coll` element type and the callback's return.

Fn-types CAN also be NAMED via `:fn-type`:

```edn
{:name :ring-handler
 :fn-type [{:request :ring-request-shape} :ring-response-shape]}
```

…and used as `:type :ring-handler` elsewhere.

## `[:secret T]` — the security marker

Lesson 07 covers this in depth. Type-level marker that taints
values flowing through the slot:

```edn
{:name :secret-leaf
 :args {:in {:type [:secret :text]}}
 :return-type [:secret :text]}
```

`[:secret :text]` is a structural type. A `:text` value AUTO-PROMOTES
into a `[:secret :text]` slot (subtyping is asymmetric — promotion
in, but no demotion out). Asymmetry is the security property:
secrets can't leak into plain `:text` sinks.

## Inline vs named

Two ways to use a structural type:

```edn
;; INLINE — anonymous structural shape
{:name :pluck
 :args {:rows {:type [:list {:id :uuid :name :text}]}}}

;; NAMED — declare once, use everywhere
{:name :row
 :type {:id :uuid :name :text}}

{:name :pluck
 :args {:rows {:type [:list :row]}}}
```

The named form gives the chip a real name in the editor's type
strip and lets future fns reuse it. The inline form keeps a
short one-off shape close to its use site. Both compile to the
same constraint on the slot.

## Try it

Create one of each kind:

```edn
;; refinement
{:name :tutorial-percentile
 :refine {:base :int :constraint [:and [:>= 0] [:<= 100]]}}

;; record
{:name :tutorial-cursor
 :type {:offset :int :limit :int}}

;; list
{:name :tutorial-tags
 :list :text}

;; union
{:name :tutorial-int-or-error
 :union [:int :text]}

;; variant
{:name :tutorial-event
 :variant [:click {:x :int :y :int}
           :keypress :text]}
```

Each writes a `:fn` row visible in the editor's namespace tree.
The arg-type chip for any slot referencing one of these resolves
to the chip's full name (e.g. `:tutorial-cursor`) instead of its
unfolded structural form.

Click the chip's `▸` to expand inline and see the structural form.
The provenance ↳ badge shows where the type came from.

## Type UX in the editor

Four affordances make the type system usable without ever
typing a structural form by hand:

**1. The compatible-type select.** Click an arg's type-chip on
an editable card and the select lists ONLY the types that can
legally narrow the slot — primitives, refinements, records,
unions — computed by one server-side, alias-aware `subtype?`
sweep over every named type in the graph. The current type is
seeded first; the rest of the options stream in when the server
answers. An fn-typed slot never offers `:int`; a `:numeric`
slot offers `:int`, `:positive-int`, `:port`, and friends.
(The inline `▸` panel's "narrow to…" select is the same list,
minus the bare primitives.)

**2. The "Type rule" popover.** When a fn-def's return type was
COMPUTED by an ancestor base-fn's type rule (`:assoc`, `:get`,
`:first`, arithmetic, …) rather than declared, the return-type
strip carries a `↳` badge. Clicking it opens a server-rendered
popover that names the rule-owning base-fn (clickable — jumps
to it), explains in one sentence what the rule did (e.g. for
`:assoc`: literal key + typed value add that field to the map's
record shape; a computed key widens to `:jsonb`), and lists the
Inputs the rule saw — which slots were bound by literal vs
fn-ref, and their effective types.

**3. Name autocomplete in the create-type form.** The name
fields in the `+ Type` form autocomplete from a server-fed
datalist of every named type-row, each labeled with its kind
(`refinement` / `record` / `union` / `variant` / `list`) plus
the primitives — so "base type" and "element type" inputs offer
real names instead of trusting your memory.

**4. Warnings on save, not blocked saves.** A write whose
aggregate type-check fails still lands; the failure is recorded
as a per-branch diagnostic. You see it as: the ⚠ badge on the
fn-card's root row, the per-branch **Type errors** section on the
**Organization** surface (open it from your avatar's menu) listing every
flagged fn (click-through to the fn), per-namespace
⚠ counts on the explorer tree rows, and a REFUSAL when you try to
execute the fn (clear message naming the fn and the first error).
Fixing the offending binding clears all of it. Structural and
secret-flow violations are the exception — those still reject the
save itself.

### Try it

1. On your `:tutorial-cursor` record from earlier, click an
   `:int`-typed arg's chip. The select offers `positive-int`,
   `port`, `non-negative-int`, … — and NOT `:text`.
2. Create a fn-def with `:parent :assoc` and bind `:key` to a
   literal `:total`. Its return-type strip grows a `↳` — click
   it and read which rule computed the record shape and from
   which inputs.

## What we glossed over

- **Type aliases at runtime** — `register-type-alias!` in
  `types.core` lets you create a shorthand for a structural
  form without writing a fn-row. Used by the system bootstrap
  to expose `:port`, `:positive-int`, etc.
- **Refinement constraint dialect** — the full predicate
  vocabulary is in `types.core/refinement-applies?`. New
  predicates are easy to add as long as their evaluator is
  deterministic.
- **Subtype/unify** — how the type-checker DECIDES that
  `[:list :positive-int]` is a subtype of `[:list :int]`. See
  [docs/TYPES.md](../TYPES.md).

## Next

Lesson 06 — Higher-order functions ([already written](06-higher-order-functions.md))
