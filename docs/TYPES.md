# Graphden Type System

> This document describes the type system design for graphden.
> For core architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).
> For design principles, see [PHILOSOPHY.md](PHILOSOPHY.md).

## Table of Contents

1. [Goals](#goals)
2. [Design Overview](#design-overview)
3. [Type Hierarchy](#type-hierarchy)
4. [When Types Are Checked](#when-types-are-checked)
5. [Who Writes Types](#who-writes-types)
6. [Type Inference](#type-inference)
7. [Structural Types (Records)](#structural-types-records)
8. [Type Rules for Data Structure Operations](#type-rules-for-data-structure-operations)
9. [Refinement Types](#refinement-types)
10. [Union Types](#union-types)
11. [Tagged Variants](#tagged-variants)
12. [Type Aliases](#type-aliases)
13. [Effect Categories](#effect-categories)
14. [Required Narrowing](#required-narrowing)
15. [Type Narrowing Through Inheritance](#type-narrowing-through-inheritance)
16. [Typed and Untyped Boundary](#typed-and-untyped-boundary)
17. [Runtime Validation from Types](#runtime-validation-from-types)
18. [What the Type System Catches and What It Does Not](#what-the-type-system-catches-and-what-it-does-not)
19. [Comparison with Other Systems](#comparison-with-other-systems)
20. [Storage Schema](#storage-schema)
21. [Tooling](#tooling)
22. [Implementation Phases](#implementation-phases)

---

## Goals

The type system must:

- **Catch composition errors before execution** — wrong types passed between functions
- **Require zero type annotations from the user** — types are inferred from the graph
- **Support gradual adoption** — `:jsonb` as escape hatch, type where you want
- **Not introduce new entities** — types are fn-defs, not a parallel system
- **Generate runtime validators automatically** — one definition, two uses

Non-goals:

- Full dependent types (Idris-level proof system)
- SMT-solver-based constraint propagation
- Automatic inference of refinement constraints through arithmetic

---

## Design Overview

The type system combines three mechanisms:

1. **Parametric polymorphism with inference** — type variables (`:a`, `:b`) on base-fns, automatically substituted (freshened per use-site) when arguments are bound. This is **rank-1 let-polymorphism only**: the variables live on base-fn signatures, users never write them, and there is no higher-rank or bounded quantification. Loosely Hindley-Milner-flavoured, but far narrower — graphden fn-defs are not arbitrary programs.

2. **Structural type computation** — for operations like `assoc`, `get`, `dissoc`, `merge`, the system computes the output record type from concrete argument values. This is possible because in graphden, keys are typically literals stored in the DB, not runtime variables.

3. **Refinement subtypes** — named subtypes with constraints (e.g., `:positive-int`), expressed as regular fn-defs. The system enforces subtype relationships: where a supertype is expected, a subtype is accepted; where a subtype is expected, a supertype is rejected.

All three mechanisms use the same infrastructure — fn-defs, arg entities, `computed-type` field.

---

## Type Hierarchy

```text
:any                           ← top type, accepts everything
├── :jsonb                     ← untyped structured data (escape hatch)
├── :numeric                   ← arbitrary-precision numeric supertype
│   ├── :int                   ← integer
│   │   └── :positive-int      ← refinement subtype (constraint: > 0)
│   ├── :float                 ← floating point
│   └── :decimal               ← arbitrary-precision rational (BigDecimal)
├── :text                      ← string
├── :bool                      ← boolean
├── :keyword                   ← keyword
├── :uuid / :timestamptz / :bytes
├── :input-stream              ← transient java.io.InputStream
│                                (type-system-only; storage-kind = :any)
├── :fn-ref                    ← a fn's IDENTITY: the slot receives the
│                                bound fn's id, never its value or a
│                                callable (ref-only; storage-kind = :uuid)
├── [:list :a]                 ← parameterized list (homogeneous, any length)
├── [:map :k :v]               ← homogeneous map (every key :k, every value :v)
├── [:tuple :a :b]             ← fixed-length heterogeneous tuple
├── {:name :text, :age :int}   ← structural record type (fixed named fields)
└── {:fn [:a :b]}              ← function type (input → output)

:never                         ← bottom type, subtype of everything
```

Storage-kind degradation (`type->storage-kind`): `:int`/`:float` stay
as themselves; `:decimal` degrades to `:numeric` (the value_kind enum
has no `:decimal` entry); `:input-stream` and `:never` degrade to
`:any` (transient / phantom — never persisted as data); `:fn-ref`
degrades to `:uuid` (though a `:fn-ref` slot is only ever bound by a
fn-ref, so no literal reaches the column).

**`:fn` vs `:fn-ref`.** A `:fn` slot means *give me something I can
call*: the executor hof-wraps the bound fn into a callable (or, when
the bound fn itself returns a callable, thunks it and hands over the
result — `:http-server`'s handle, `:_router`'s ring handler). A
`:fn-ref` slot means *give me WHO this is*: the impl receives the bound
fn's id, the fn is never evaluated, its free args never surface, its
effects are not the consumer's (`compute-effects` skips the edge, so
naming a `:process` listener leaves the consumer `:db`), and the edge
is not a dependency — so `:service-endpoint :service :web-server`
names the listener without starting it, and two services may name
each other. The checker accepts any fn-ref (whatever its
return type) on a `:fn-ref` slot and rejects a literal
(`:types/fn-ref-slot-needs-ref`); the editor's fn picker offers every
fn for such a slot (`/api/types/candidates` with `expected` `fn-ref`).

### When to use `:any` vs `:jsonb` vs a type variable

`:any` is the inference top — it's what the system falls back to when
no better information is available. **Avoid declaring `:any` on an
arg or return type** unless one of these holds:

| Situation                                | Right declaration |
|------------------------------------------|-------------------|
| Slot accepts untyped JSON-shaped data (HTTP body, env-var output, opaque map) | `:jsonb` |
| Slot accepts a polymorphic value the fn returns or passes through unchanged | type variable `a` |
| Slot's precise type depends on another slot's literal value (`:assoc` reading `:key`) | `:any` plus a `:return-type-rule` |
| Slot accepts an opaque library handle (compiled router, exception, …) | `:any` (no better shape exists) |

If you're tempted to write `:any` and none of the four cases match,
the right answer is usually a type variable. `:any` says "I have no
information here"; a type-variable says "the system should track
this slot's actual type across the composition" — strictly more
useful at every call site, identical at runtime.

A var-carrying declaration is not just documentation — it IS the
return-type rule. The checker's declared-signature fallback
(`signature-return` in `types/check`) fires wherever a root base-fn
has no hand `:return-type-rule`: it unifies each var-carrying
declared arg type against the actual type at the site and resolves
the declared return through the substitution (degrading to the
static return when nothing binds — the fallback never widens).
`:coll [:list a] → [:union :null a]` on `:first` is the whole
narrowing story; the elem-preserving stdlib family
(`:rest`/`:take`/`:sort`/…) carries no Clojure rules at all. Hand
`:return-type-rule` fns remain only where the result genuinely
depends on literal values, union fan-out, or registry callbacks
(`:get`, `:assoc`, `:merge`, `:if`/`:cond`, arithmetic int-join).
Marker-taint (`:secret` &c.) is orthogonal: a per-base-fn
`:taint-propagate? true` registry flag, applied centrally by the
checker on top of the structural result (see
[SECRETS.md](SECRETS.md) § Propagation).

`:jsonb` and `:any` are NOT interchangeable: `:jsonb` rejects
callables (they're not JSON-shaped data), `:any` accepts them. A
slot that genuinely takes "anything that fits into JSON" should be
`:jsonb`; a slot that accepts a callable too should be `:any`.

`:never` is the BOTTOM type — the dual of `:any` (top). It is the
type of a computation that never produces a value (`:throw`). It is
a subtype of every type, so a throwing branch is accepted in any
context, and `make-union` absorbs it (`[:union :never T]` = `T`) so
`(:if c (:throw …) x)` is typed exactly `x`. No literal value ever
has type `:never`; on the storage wire it degrades to `:any`.

Subtyping rules:

- Every type is a subtype of `:any`; `:never` is a subtype of every type
- Primitive types (`:int`, `:text`, etc.) are subtypes of `:jsonb`
- Record, list, map and tuple types are subtypes of `:jsonb`
- A record with MORE fields is a subtype of a record with FEWER fields (`{:a :int, :b :text}` ⊂ `{:a :int}`)
- `[:map K V]` is covariant in both key and value; a keyword-keyed record is a valid `[:map :keyword V]` value (`unify` bridges record ↔ `[:map …]`, mirroring `subtype?`)
- `[:tuple …]` is covariant per position and requires equal length
- A refinement type is a subtype of its base type (`:positive-int` ⊂ `:int`)
- `:jsonb` is NOT a subtype of any concrete type (requires explicit conversion)

---

## When Types Are Checked

Type checking happens **at fn-def save time** — when the user creates or modifies a fn-def through API or UI. Not at runtime.

```text
User creates/modifies fn-def
         │
         ▼
┌─────────────────┐
│ Resolve parents  │  Walk inheritance chain, find base-fn
└────────┬────────┘
         ▼
┌─────────────────┐
│ Type inference   │  Substitute type variables,
│                  │  apply type-rules for assoc/get,
│                  │  compute return-type
└────────┬────────┘
         ▼
┌─────────────────┐
│ Compatibility    │  For each arg with ref-id:
│ check            │  ref-fn return-type ⊂ expected arg type?
└────────┬────────┘
         │
    OK? ──┤
    │     └─ NO → error, fn-def NOT saved
    │           "arg :a expects :int, but :my-fn returns :text"
    ▼
┌─────────────────┐
│ Save             │  fn-def + computed-type stored in DB
└─────────────────┘
```

When a fn that others depend on is modified, dependent fn-defs are re-checked.

---

## Who Writes Types

**Base-fn authors** write types once:

```edn
{:name :add
 :args {:nums {:type [:list :numeric]}}
 :return-type :numeric}

{:name :map
 :args {:func {:type {:fn [:a :b]}}, :coll {:type [:list :a]}}
 :return-type [:list :b]}

{:name :http-get
 :args {:url {:type :text}}
 :return-type :jsonb}   ;; illustrative — the real :http-get returns a typed :http-response-shape
```

**Users (fn-def creators)** never write types. Types are inferred:

```edn
{:name :add-10
 :parent :add
 :args {:nums [10 {:as :b}]}}
;; System infers:
;;   nums[0] = 10, type :numeric, matches nums element :numeric ✓
;;   :b remains free, type :numeric
;;   return-type: :numeric
;; User wrote zero types.
```

**Exception:** at system boundaries (HTTP responses, external APIs), the user may optionally declare a return type override when the system cannot infer the structure:

```edn
{:name :get-user-api
 :parent :http-get
 :args {:url "https://api.example.com/user"}
 :return-type {:name :text, :age :int}}
```

---

## Type Inference

### Concrete value binding

When an arg is bound to a literal value, the system checks that the value's type matches the expected arg type:

```edn
{:name :add-10, :parent :add, :args {:nums [10]}}
;; 10 is :numeric, nums element expects :numeric → ✓

{:name :add-broken, :parent :add, :args {:nums ["hello"]}}
;; "hello" is :text, nums element expects :numeric → ERROR
```

### Ref-id binding

When an arg is bound via `ref-id`, the system checks that the referenced fn's return type is a subtype of the expected arg type:

```edn
{:name :double-age, :parent :add, :args {:nums [:get-user-age :get-user-age]}}
;; get-user-age returns :int ⊆ :numeric, nums element expects :numeric → ✓

{:name :broken, :parent :add, :args {:nums [:get-user-name]}}
;; get-user-name returns :text, nums element expects :numeric → ERROR
```

### Parametric polymorphism (type variables)

Type variables on base-fns are unified when arguments are bound:

```edn
;; map :: {:func {:fn [:a :b]}, :coll [:list :a]} → [:list :b]

{:name :map-add-10, :parent :map, :args {:func :add-10}}
;; add-10 :: :numeric → :numeric (one free arg of type :numeric, returns :numeric)
;; Unification: :a = :numeric, :b = :numeric
;; Result: map-add-10 :: {:coll [:list :numeric]} → [:list :numeric]

{:name :map-upper, :parent :map, :args {:func :str-upper}}
;; str-upper :: :text → :text
;; Unification: :a = :text, :b = :text
;; Result: map-upper :: {:coll [:list :text]} → [:list :text]
```

Error example:

```edn
;; filter :: {:pred {:fn [:a :bool]}, :coll [:list :a]} → [:list :a]

{:name :broken-filter, :parent :filter, :args {:pred :add-10}}
;; add-10 returns :numeric, but filter expects :pred to return :bool
;; :numeric ≠ :bool → ERROR
```

### Free argument type propagation

When a fn-def leaves arguments free, their types propagate to callers:

```edn
{:name :add-10, :parent :add, :args {:nums [10 {:as :b}]}}
;; Free args: {:b :numeric}
;; Callers of add-10 must provide :b of type :numeric
```

---

## Structural Types (Records)

A record type is defined as a fn-row with a `:type {field type …}` map:

```edn
{:name :message
 :type {:from :text
        :text :text
        :timestamp :int}}

;; System computes:
;;   return-type = {:from :text, :text :text, :timestamp :int}
;;   Free args: {:from :text, :text :text, :timestamp :int}
```

This serves as both:

- **Type definition** — `computed-type` describes the structure
- **Constructor** — binding all args creates a concrete value
- **Runtime schema source** — system can generate malli/JSON Schema for validation

No new entity needed. A type IS a fn.

---

## Type Rules for Data Structure Operations

Certain base-fns have **type-rules** — special logic for computing output types based on argument values. This is where graphden gets dependent-type-like behavior without actual dependent types.

### Key insight

In graphden, argument values are often **concrete literals stored in the DB**. When a user writes `{:k "name"}`, the system knows the key at save time. This is fundamentally different from traditional languages where values are only known at runtime.

### assoc

```text
type-rule: if key is a literal, result = type(map) + {key: type(value)}
           if key is a ref-id, result = :jsonb (degradation)
```

Example:

```edn
{:name :with-name, :parent :assoc, :args {:map {}, :key "name", :value "Alice"}}
;; map = {} → type {}
;; key = "name" (literal, value known)
;; value = "Alice" → type :text
;; Result type: {:name :text}

{:name :with-name-and-age, :parent :assoc, :args {:map :with-name, :key "age", :value 30}}
;; map → :with-name → type {:name :text}
;; key = "age", value = 30 → :int
;; Result type: {:name :text, :age :int}
```

### get

```text
type-rule: if key is a literal and coll has record type,
           check field exists, return field type.
           if key is a ref-id, result = :jsonb (degradation)
```

Example:

```edn
{:name :get-name, :parent :get, :args {:coll :with-name-and-age, :key "name"}}
;; coll type: {:name :text, :age :int}
;; key = "name" → exists, type :text ✓
;; Result type: :text

{:name :get-email, :parent :get, :args {:coll :with-name-and-age, :key "email"}}
;; key = "email" → NOT in {:name :text, :age :int}
;; ERROR: field "email" not found. Available: name, age
```

### dissoc

```text
slot:      :map  [:map a :any]   ; record ⊆ [:map :keyword :any] via subtype;
                                 ; [:list …] rejected at the type level.
type-rule: if k is a literal, result = type(m) minus field k
```

The slot uses `[:map a :any]` (not `[:map a b]`) so heterogeneous-
valued records still unify: a single `b` can't bind to both `:int`
and `:text` across `{:foo :int :bar :text}`'s fields. Fixing the
value side to `:any` keeps `record-to-map` unification through
`unify-map-record`; the rule recovers the precise record shape when
`:key` is a literal.

### merge

```text
type-rule: result = type(a) ∪ type(b)
```

### zipmap / pairs->map — static field reconstruction

```text
zipmap:     if every :keys item is a literal keyword/string,
            result = record {k1 T1 …} with Ti from :vals per-item types
pairs->map: if EVERY :entries item is a statically-known 2-element pair
            whose KEY half is a literal keyword/string,
            result = record {k1 T1 …} (later pair wins on a duplicate key)
```

`:pairs->map` recognises literal pairs (`{:value [k v]}` / raw `[k v]`
vectors) and fn-ref entries built via `:list` with a literal
`{:value <key>}` first item — the canonical case being
web/ring-adapter's `:internal-request`, whose five `[:field
<extractor>]` entries reconstruct exactly `:ring-request-shape`, so its
declared `:return-type` is proved by subtyping rather than asserted.
ANY entry that doesn't yield a literal key (a dynamic entries ref, an
append-form, a computed key) degrades the WHOLE result to the declared
`[:map :any :any]` — no partial records.

### Degradation

When a key is a `ref-id` (computed value), the system knows the key's TYPE (`:text`) but not its VALUE. Type-rules that need the value degrade to `:jsonb` with a warning:

```edn
{:name :dynamic-get, :parent :get,
 :args {:coll :user, :key :compute-field-name}}
;; key is ref-id → value unknown → cannot check field existence
;; Result type: :jsonb (with WARNING)
```

---

## Refinement Types

A refinement type is a named subtype with constraints, expressed as a regular fn-def:

```edn
{:name :positive-int
 :refine {:base :int
          :constraint [:> 0]}}
;; computed-type: {:subtype-of :int, :constraint [:> 0]}
```

`:refine` is not a base-fn — it's a top-level fn-row key (`:refine {:base T :constraint C}`) that the loader recognises and turns into a refinement type-row establishing the subtype relationship.

### Usage

```edn
{:name :sqrt
 :args {:n :positive-int}
 :return-type :float}

;; Passing positive-int where a numeric is expected — OK (subtype)
{:name :inc-positive, :parent :add, :args {:nums [:some-positive-value 1]}}
;; :positive-int ⊆ :int ⊆ :numeric → ✓

;; Passing int where positive-int is expected — ERROR
{:name :sqrt-any, :parent :sqrt, :args {:n :some-int-value}}
;; :int ⊄ :positive-int → ERROR
;; User must insert explicit validation
```

### Explicit validation at boundaries

The shipped refinement-narrowers live in `core/refinements` (parent
`:_refinement-narrow`), e.g. `:ensure-positive-int`. It accepts `:int` and
returns `:positive-int`: at runtime it throws `:refinement/violated` when
the value is `<= 0`; for the type system it narrows `:int → :positive-int`.

```edn
{:name :sqrt-safe
 :parent :sqrt
 :args {:n :ensured-value}}

{:name :ensured-value
 :parent :ensure-positive-int
 :args {:value :some-int-value}}
;; some-int-value → :int
;; :ensure-positive-int accepts :int, returns :positive-int
;; sqrt accepts :positive-int ✓
```

The system does NOT prove that a value is positive. It forces the programmer to **explicitly mark the point where the constraint is validated**. The validation itself happens at runtime. But forgetting it is impossible — the type system rejects the fn-def without it.

### Refinement constraints do NOT propagate through arithmetic

```edn
{:name :decrement, :parent :sub, :args {:nums [:some-positive 1]}}
;; Result type: :numeric (not :positive-int)
;; The system does not compute min(positive - 1) = could-be-zero
;; This would require an SMT solver — out of scope
```

### Compound constraints

Constraints support both atomic (`[op rhs]`) and compound (`[:and …]`,
`[:or …]`) shapes. The literal evaluator walks them with the obvious
short-circuit semantics; partially-decidable conjunctions defer to
`:unknown` (the runtime check still applies):

```edn
;; A percent: 0..100 inclusive
[:refine :numeric [:and [:>= 0] [:<= 100]]]

;; A bit: literally 0 or 1
[:refine :int [:or [:= 0] [:= 1]]]
```

Recognised atoms: `:>` `:>=` `:<` `:<=` `:=` `:not=` `:in` `:matches`.

---

## Union Types

Untagged sum types: `[:union T1 T2 …]`. A value of union type is one
of the branches — no constructor wrapper.

```clojure
(t/subtype? :int (t/make-union [:int :text]))     ; true
(t/subtype? (t/make-union [:int :null]) :any)     ; true
(t/subtype? (t/make-union [:int :null]) :int)     ; false
```

Subtyping rules:

- `T ⊆ [:union T1 T2 …]`  iff  `T ⊆ Tᵢ` for some `i`   (membership)
- `[:union T1 T2 …] ⊆ S`  iff  `Tᵢ ⊆ S` for every `i`  (disjunction)

`make-union` is the canonical constructor: it flattens nested unions,
deduplicates, collapses singletons, and absorbs `:any`. The sort order
is deterministic so equality between freshly built unions is reliable.

The `:if` base-fn types its branches as INDEPENDENT type variables
and returns their union: `{:then a, :else b}` → `[:union a b]`. The
branches need not agree — `make-union` collapses `[:union T T]` back
to `T` when they do, so a homogeneous `:if` still reads as a single
type:

```edn
{:parent :if :args {:test :some-bool? :then 42 :else "fallback"}}
;; computed return-type: [:union :int :text]

{:parent :if :args {:test :some-bool? :then 1 :else 2}}
;; computed return-type: :int   (union of :int with :int)
```

A shared type variable on `:then`/`:else` would instead FORCE the
branches to unify and reject every heterogeneous `:if` (e.g. an
error-record branch vs a success-record branch).

`(:if c (:throw …) x)` is typed exactly `x`: `:throw` returns
`:never` (the bottom type), and `[:union :never T]` = `T`.

### Unification & unions

The HM unifier doesn't pick a branch when given a union — it would
have to commit to a choice the user never made. Instead, unify defers
to `subtype?`: union ↔ X succeeds when the relation holds in either
direction, fails otherwise. Type variables never get bound to a
branch; declared union slots stay declared.

---

## Tagged Variants

Discriminated sum types — sugar for a union of tagged records. The
loader's `:variant [:tag1 T1 :tag2 T2 …]` shape on a `fns.edn` entry
desugars to:

```clojure
[:union {:tag [:refine :keyword [:= :tag1]] :value T1}
        {:tag [:refine :keyword [:= :tag2]] :value T2}
        …]
```

The `[:refine :keyword [:= :tag]]` pin on the tag slot is what makes
each branch discriminable. Built-in variant aliases:

```edn
;; resources/packages/core/refinements/fns.edn
{:name :result-text :variant [:ok :text :err :text]}
{:name :result-int  :variant [:ok :int  :err :text]}
{:name :validation  :variant [:valid :any :invalid :text]}
```

### Discrimination

No new pattern-matching base-fn. The `core.variants` module ships
three composition-only fn-defs over the existing `:get` / `:equal?`:

```edn
;; resources/packages/core/variants/fns.edn
{:name :variant-tag    :parent :get      :args {:key {:value :tag :type :keyword}}}
{:name :variant-value  :parent :get      :args {:key {:value :value :type :keyword}}}
{:name :variant-is?    :parent :equal?   :args {:a :variant-tag :b {:as :tag}}}
```

Authors then dispatch via the existing `:cond` — its `:clauses` is a
flat `[test result test result …]` sequence; a literal `true` test is
the else-branch:

```edn
{:parent :cond
 :args {:clauses [(:variant-is? :coll my-result :tag :ok)
                  (:variant-value :coll my-result)
                  true
                  (:variant-value :coll my-result)]}}
```

---

## Type Aliases

Named type-rows live alongside fn-defs in the same `fns.edn` files
— the loader inspects the entry shape (`:refine` / `:list` /
`:union` / `:variant` / `:record`) and emits a fn-row + slots in
the right shape. There is no separate `aliases.edn` file. Four
entry shapes:

| Shape       | Example                                                         | Meaning                                  |
|-------------|-----------------------------------------------------------------|------------------------------------------|
| Refinement  | `{:name :positive-int :refine {:base :int :constraint [:> 0]}}` | `[:refine :int [:> 0]]`                  |
| Record      | `{:name :user :record {:id :uuid :name :text}}`                 | `{:id :uuid :name :text}`                |
| Union       | `{:name :nullable-int :union [:null :int]}`                     | `[:union :null :int]`                    |
| Variant     | `{:name :result-int :variant [:ok :int :err :text]}`            | union of pinned-tag records (see above)  |

Type-rows declared in `core/refinements/fns.edn` cover everyday
needs out of the box:

| Alias              | Type                                                              | Used for                         |
|--------------------|-------------------------------------------------------------------|----------------------------------|
| `:positive-int`    | `[:refine :int [:> 0]]`                                           | counts, sizes                    |
| `:non-negative-int`| `[:refine :int [:>= 0]]`                                          | indexes, lengths                 |
| `:negative-int`    | `[:refine :int [:< 0]]`                                           | offsets                          |
| `:non-empty-text`  | `[:refine :text [:not= ""]]`                                      | required strings                 |
| `:non-blank-text`  | `[:refine :text [:matches "\\S"]]`                                | strings with at least one non-ws char |
| `:url`             | `[:refine :text [:matches "^https?://"]]`                         | HTTP / HTTPS URLs                |
| `:positive-numeric`| `[:refine :numeric [:> 0]]`                                       | rates, weights                   |
| `:percent`         | `[:refine :numeric [:and [:>= 0] [:<= 100]]]`                     | percentages                      |
| `:probability`     | `[:refine :numeric [:and [:>= 0] [:<= 1]]]`                       | probabilities                    |
| `:port`            | `[:refine :int [:and [:>= 1] [:<= 65535]]]`                       | TCP/UDP ports                    |
| `:user-port`       | `[:refine :int [:and [:>= 1024] [:<= 65535]]]`                    | non-privileged ports             |
| `:http-status`     | `[:refine :int [:and [:>= 100] [:<= 599]]]`                       | HTTP status codes                |
| `:bit`             | `[:refine :int [:or [:= 0] [:= 1]]]`                              | binary flags                     |
| `:nullable-text`   | `[:union :null :text]`                                            | optional text (env vars, headers); other primitives use inline `[:union :null T]` |
| `:result-T`        | tagged variant `{:ok T}` &#124; `{:err :text}`                     | Result-style returns             |
| `:validation`      | tagged variant `{:valid :any}` &#124; `{:invalid :text}`           | parsed-vs-error                  |

Modules adding their own named type-rows just put the
`:refine` / `:list` / `:union` / `:variant` / `:record` entries
directly in their `fns.edn` alongside their fn-defs — the loader
recognises the shape and emits the right fn-row + slot structure.

A refinement's constraint payload is **open**: shapes outside the
recognised vocabulary above are accepted and compared by structural
equality (both by `constraint-implies?` and the literal checks).
That makes an unrecognised constraint a sound *nominal
discriminator*: two refinements over the same base with distinct
opaque constraints are mutually incomparable, while each stays
`⊆ base`. `web.html` uses this for its page-asset tag types —
`:script-tag` / `:style-tag` are
`[:refine :hiccup-node [:elem 0 [:= "script"|"style"]]]`
(`[:elem N c]` reads "element at index N satisfies `c`"): both bind
anywhere a `:hiccup-node` is expected, but type-driven tooling (the
execute-result repr dispatch, app/reprs) can tell a page asset from
a visual component. Two *constraint-less* refinements over one base
would NOT discriminate — they are structurally identical and
mutually `subtype?` (which is why `:js-source` / `:css-source`
share one repr registry target).

### Per-namespace alias names

Type names follow the per-namespace rule fn names do
(ADR-identity-model.md): two modules may each declare `:shape`.
Every type-row registers its BARE name and a QUALIFIED
`:ns.path/name` alias (e.g. `:web.crud/shape`). While a bare name
has one owner it resolves as always; the moment a second type-row
claims it, the bare form throws `:types/ambiguous-alias` at
resolution — naming the qualified candidates — instead of silently
resolving to whichever module loaded last. Disambiguate by writing
the qualified keyword in the `:type` position:

```edn
:args {:row {:type :web.crud/shape}}
```

Version-materialized namespaces (`web.components@1-2-0`) register
bare-only — `@` is invalid in an EDN keyword namespace.

### Unknown aliases — migration note

The shipped `:nullable-*` shorthands are `:nullable-text`,
`:nullable-uuid`, `:nullable-jsonb`, `:nullable-int`, `:nullable-bool`,
`:nullable-keyword`, and `:nullable-keyword-map` (`core.refinements`).
Names NOT in that set — e.g. `:nullable-numeric` — do not resolve. If a
binding or return-type override in a long-lived DB references a name
that isn't a live alias, the next clean deploy will fail to resolve it.
Fix by replacing the reference with the inline form:

```edn
;; before
{:type :nullable-numeric}
;; after
{:type [:union :null :numeric]}
```

`register-type-aliases!` warns "body references an unknown type"
when this happens — search server logs for that string after a
deploy if anything pages.

---

## Effect Categories

Every fn-def carries an optional `:effects` set tagging the side
effects it performs. The set propagates along composition: a fn-def
inherits the union of every parent's and every ref-binding's
`:effects`. Pure code stays at `#{}` (no `:effects` key in the
registry entry).

### Categories

| Tag        | Meaning                                       | Cacheable within one top-level? |
|------------|-----------------------------------------------|----------------------------------|
| `:io`      | classpath / file reads                        | yes                              |
| `:db`      | storage CRUD                                  | yes (one txn)                    |
| `:env`     | env-var reads                                 | yes (stable per request)         |
| `:network` | HTTP socket lifecycle                         | yes (server handle is singleton) |
| `:time`    | wall-clock                                    | **no — always fresh**            |
| `:random`  | non-determinism                               | **no — always fresh**            |
| `:process` | spawns supervised background work (`:service` marker) | no                       |
| `:state`   | mutable in-process state (atoms / journals)   | no                               |
| `:raw-sql` | raw SQL bypassing the org-scoped storage protocol (cloud/tenant-blocked) | yes (one txn) |

### Caching policy

The compiled executor's call-cache memoizes ref invocations within
**one top-level call** so e.g. `:ring-body`'s single-use slurp is
shared across siblings. Effectful fns are still cacheable — env
values don't change mid-request, DB rows are consistent under one
txn, etc. Only `:time` and `:random` bypass the cache and fire fresh
every read; everything else benefits from sharing.

### `:expects-effects` policy declarations

Any fn-def can declare an `:expects-effects #{…}` set. At sync time,
the type-checker compares declared vs computed; categories that show
up in the actual graph but weren't in the declaration log a `WARN`:

```text
fn-def :health declared :expects-effects #{:time}
                but computed effects include #{:db}
                — drift in the call graph?
```

Useful for routes / module boundaries — declare what each layer is
ALLOWED to touch, and any accidental introduction of e.g. a DB read
into a supposedly pure `/health` endpoint shows up in `bb deploy`
logs at the moment of the regression. Default is no declaration → no
check (backward compat).

`:expects-effects` and **slot-level effect constraints** (Phase 8)
are complementary:

- `:expects-effects` is fn-def-wide policy — "this whole graph must
  not introduce :db reads beyond what I declared". It's a *warning*
  on drift.
- A slot-level `[:fn args ret eff-set]` constraint binds at the
  callsite: "any callable bound *here* must satisfy this effect
  bound". At package-sync time it's a *hard reject* (the sweep
  allowlist gate), same channel as a return-type mismatch; on a USER
  CRUD write it's a recorded diagnostic + `:type-warnings` on the
  kept row (error-tolerance Phase 2 — type errors no longer block
  user writes).

Use the slot constraint when the *contract of the HOF itself*
demands purity (`:filter`, `:map`, `:reduce` — order-independence,
caching, replayability). Use `:expects-effects` when the *role of
the fn-def* in the system demands a budget (e.g., `/health` is
allowed to read time but not the DB).

---

## Required Narrowing

Slots carry an optional `:required` flag (default `true`). A
descendant fn-def can narrow an inherited optional slot to required
via a binding — but never widen back. Same monotonicity rule as
type narrowing.

### Wire format

In `fns.edn`, on a composed fn-def's `:args`:

```edn
{:name :base-search
 :args {:query  {:type :text}
        :limit  {:type :int :required false}}    ; opt at the slot
 :return-type :jsonb}

{:name :paginated-search
 :parent :base-search
 :args {:limit {:required true}}}                ; narrow to required
```

`:limit` is now required for `:paginated-search` and every fn-def
inheriting from it. The free-arg propagation surfaces it to callers;
the executor's `effective-required?` ORs the slot's baseline with
every binding along the inheritance chain.

### Direction

- **Optional → required**: allowed (a binding with `:required true`).
  Once narrowed, *every* descendant inherits the required state.
- **Required → optional**: forbidden. The check flags any binding row
  carrying `:required false` with `:bindings/widening-required` —
  a hard reject at package-sync time; on a USER CRUD write the row is
  kept and the violation is recorded as a diagnostic +
  `:type-warnings` (error-tolerance Phase 2). Optionality is declared
  once, on the slot itself.

A binding may legitimately combine `:required true` with other
metadata (`:value`, `:as`, `:type`) — narrowing applies regardless
of whether the binding also carries a value. A `:required true` with
no value/ref leaves the slot logically free under the same external
name, just narrowed.

### Why this rule (and not the reverse)

Required-narrowing is monotonic for the same reason type-narrowing
is: a downstream caller of `:paginated-search` who relies on
`:limit` being required would break if some descendant could secretly
make it optional again. The user-facing contract is "if any node in
your inheritance chain says required, you must supply it" — a single
direction, no surprises.

### Implementation

| Layer | What it does |
|-------|-------------|
| `binding.required` (schema) | Optional bool. nil = no opinion at this binding. |
| `effective-required?` | Walks the inheritance chain, ORs slot's `:required` with every binding's `:required true`. Used by `classify-slot` to populate the `:free` entry's `:required` field. |
| `check-binding-monotonicity!` | Unified pre-pass in `check-fn-def!` — flags `:required false` bindings (tag `:bindings/widening-required`) AND `:type T` overrides where T ⊄ inherited slot type (tag `:bindings/widening-type`). Fires on package load (hard reject, sweep-gated) AND on CRUD writes (recorded diagnostic + `:type-warnings`, row kept — error-tolerance Phase 2). Replaces the older `check-required-widening!` which only covered the boolean half. |
| Loader (`map-arg-value->binding-fields`) | Recognises `:required` in `:args`-value maps and emits the corresponding binding-row column. |

---

## Type Narrowing Through Inheritance

When a fn-def inherits from a parent and the user wants to declare a more specific type for a free argument, they can narrow (but never widen) the type:

```edn
;; http-post has {:body :jsonb}

{:name :send-email
 :parent :http-post
 :args {:url "https://api.mail.com/send"}
 :refine {:body {:type {:to :text, :subject :text, :body :text}}}}

;; body narrowed: :jsonb → {:to :text, :subject :text, :body :text}
;; {:to :text, ...} ⊂ :jsonb → valid narrowing ✓
```

Further inheritance can narrow further:

```edn
{:name :send-welcome
 :parent :send-email
 :refine {:body {:type {:to :text, :subject [:= "Welcome!"], :body :text}}}}
;; subject narrowed to literal value
```

Widening is an error:

```edn
{:name :broken
 :parent :send-email
 :refine {:body {:type :jsonb}}}
;; :jsonb ⊄ {:to :text, ...} → ERROR: cannot widen type
```

### Unified monotonicity check

`check-binding-monotonicity!` is one pre-pass that enforces BOTH
narrowing channels — `:required` (boolean) and `:type` (structural
subtype) — under the same `:bindings/widening-{required,type}`
error category:

- `{:required false}` on any slot → `:bindings/widening-required`
- `{:type T}` where T ⊄ inherited slot type → `:bindings/widening-type`

Both fire BEFORE the regular per-binding type-check, so a widening
attempt fails with a clear "this is a widening, not a value
mismatch" diagnostic rather than the secondary "literal value
classifies as map, doesn't match :int" the value-binding path used
to emit for `{:type :any}` overrides.

### What is stored in DB

A slot's effective type comes from `slot.type-fn-id` overlayed by
the closest `binding.type-override-fn-id` along the `parent-ids`
inheritance closure. Refinement narrowing flows through the
type-row chain (`fn.base-fn-id` for `:refine`, `fn.element-fn-id`
for `:list`, `fn.constraint` for predicates).

---

## Typed and Untyped Boundary

```text
Untyped world                    Typed world
(jsonb, any)                     (int, text, bool, records)
                                 
  HTTP responses ──┐                    │
  DB query results ┤  to-int       ┌───┤
  JSON parsing ────┘ ────────► :int│   ├── add, gt, mul, sub
                     to-text       │   ├── map, filter, comp
                    ────────►:text │   ├── if, and, or
                                   └───┘
                                       │    assoc, get
                    ◄──────────────────┘  ──────────►
                     to-jsonb              back to jsonb
```

Converter functions (e.g. `:parse-int`, the `:ensure-*` refinement narrowers) are **bridges**. They may fail at runtime (if the value is not of the expected format), but after them, type guarantees hold. The user chooses where to draw the boundary.

---

## Runtime Validation from Types

A base-fn `type-schema` can introspect any fn's `computed-type` and generate a validation schema (malli-compatible):

```edn
{:name :message-schema
 :parent :type-schema
 :args {:entity :message}}
;; Introspects :message computed-type {:from :text, :text :text, :timestamp :int}
;; Returns malli schema: [:map [:from :string] [:text :string] [:timestamp :int]]
```

One type definition → static checking at save time + runtime validation at execution time. No duplication.

---

## What the Type System Catches and What It Does Not

### Catches (at save time)

| Error | Example |
|-------|---------|
| Wrong primitive type | `:text` passed where `:int` expected |
| Wrong HOF signature | `a → int` passed to filter (expects `a → bool`) |
| Nonexistent record field | `get "nme"` on `{:name :text}` |
| Missing required conversion | `:int` passed where `:positive-int` expected |
| Type mismatch through chain | `get "name" → :text → add` (expects `:numeric`) |
| Incompatible type narrowing | Widening in inheritance chain |

### Does NOT catch

| Error | Why |
|-------|-----|
| Invalid JSON structure at runtime | Data arrives at runtime, types check composition |
| Business logic errors (amount < 0) | Requires SMT solver for arithmetic constraints |
| Dynamic field names | `get` with computed key — value unknown |
| Network/IO failures | Not a type system concern |
| Correct field name but wrong data from API | Type system trusts the author's `:return-type` override |

---

## Comparison with Other Systems

| Aspect | Python | Malli | Java | **Graphden** | Haskell | Idris |
|--------|--------|-------|------|-------------|---------|-------|
| When errors found | Runtime | Runtime | Compile | **Save time** | Compile | Compile |
| User writes types | None | A lot | A lot | **None** (inferred) | Little | A lot |
| Gradual adoption | Yes | Yes | No | **Yes** (jsonb) | No | No |
| Structural records | No | Yes | No | **Yes** | Yes | Yes |
| Parametric polymorphism | Hints | No | Erased generics | **Yes** (inferred) | Yes | Yes |
| Refinement types | No | Runtime | No | **Yes** (save time) | Liquid Haskell | Yes (proofs) |
| Dependent-like types | No | No | No | **Partial** (literal keys) | No | Yes |
| Runtime validation | Manual | Schema | Manual | **Auto-generated** | No | No |

> **Soundness posture — read the table honestly.** Graphden's checker is
> **optional and erased, not sound** — closer to TypeScript / mypy than to
> Haskell or Idris. `:any` and `:jsonb` are unchecked escape hatches: a value
> flowing through them is accepted with **no static check and no inserted
> runtime cast**, so a type the graph "promises" can be violated at runtime
> with no blame error. The columns above describe *expressiveness* (what can be
> written and inferred), not a soundness guarantee. Where this doc says a
> property "holds", read it as *best-effort, enforced at save time where the
> checker can see it* — see [TYPE_SYSTEM_DECISIONS.md](TYPE_SYSTEM_DECISIONS.md)
> for the deliberate optimism and why it was chosen over a sound/gradual design.

---

## Storage Schema

Types live as fn-rows themselves (record / refinement / list /
union / variant) plus pointer fields on entities:

### fn entity (type-related fields)

```text
fn:
  ...
  return-type-fn-id   ref<fn>  -- author-declared return type
  base-fn-id          ref<fn>  -- :refine — what we narrow
  constraint          jsonb    -- :refine predicate
  element-fn-id       ref<fn>  -- :list element type
  anonymous-hash      text     -- dedup key for inline composites
```

### slot entity (per-slot type)

```text
slot:
  type-fn-id   ref<fn>  -- the slot's value type
  required     bool
```

### binding entity (per-fn override)

```text
binding:
  type-override-fn-id  ref<fn>  -- override slot's type at this fn
```

The effective type at `(fn, slot)` is
`(or binding.type-override-fn-id slot.type-fn-id)`. Refinement
walking follows the type-row chain (`base-fn-id` /
`element-fn-id`).

---

## Tooling

### `/api/types`

Snapshot of the in-memory rich-type registry as JSON. One entry per
fn (base-fn or fn-def) the type-checker has processed:

```jsonc
// Lean BULK shape — the heavy per-entry fields (:description,
// :source-file/-line, :tags, :arg-effects, :call-time-effects,
// :resolved-bindings, :primary-parent) are stripped from the wire;
// see docs/PERF_BUDGETS.md finding K. The full-field snapshot backs
// /api/types/candidates server-side only.
{
  "bearer-token": {
    "return": ["union", "null", "text"],
    "args":   { "coll": "jsonb", "default": "any" },
    "effects": ["env"]
  },
  "health": {
    "return": "fn",
    "args": { /* … */ },
    "effects": ["time"],
    "expects-effects": ["time"]
  }
}
```

The editor's fn-overlay reads this for: type-aware fn-picker
filtering, "Expected: <type>" hints, type-mismatch outlines, effect
badges, and declared-vs-computed drift markers.

### Editor surfacing of type narrowing

The editor exposes type provenance through **one canonical surface**
— the `↳` popover — plus two read-at-a-glance affordances on the
chip itself. The split is deliberate: the inline `▸/▾` panel stays
**structure-only** (what shape is this type?), the `↳` popover stays
**provenance-only** (where did this type come from?). One source of
truth for narrowing display, no duplicated "Resolved via" sections
across the UI.

**1. Inline `▸/▾` panel** (chip click) — structural reveal only:

- Refinement chain breadcrumb (`:user-port ⊂ :int ⊂ :numeric`,
  each link clickable to navigate to its type-row).
- Constituents: refine → base+constraint, list → element, map → key+value,
  tuple → #idx slots, record → field rows, union → branches,
  fn → args+ret+effect-row.
- Kind tag in header (Map / Record / Tuple / Refinement / Union /
  Function) so anonymous structural types are distinguishable.
- A small `↳ provenance` link in the header opens the popover below
  when the panel was opened on a slot-bound arg with non-null
  provenance — single click to switch from "what's the shape?" to
  "where did it come from?".

**2. Provenance popover** (↳ badge click, OR ↳ link from inline
panel): the canonical answer to "where did this type come from?".
Four stacked sections, SERVER-RENDERED (`GET /partials/provenance`
— JS only mounts + anchors; see docs/PARTIALS.md):

| Section           | When it appears                                                          | What it shows |
|-------------------|--------------------------------------------------------------------------|----------------|
| Inherited via     | ≥ 1 ancestor in the inheritance chain carries a `type-override` binding  | Closer-wins list: ancestor → override. When ≥ 2 candidates compete (MI / inherited overrides at different depths), the closer-fn-wins winner is marked `✓ (chosen)`; the shadowed candidates get `↳ (also by)` so the resolution decision is visible instead of silent. |
| Resolved via      | Always (4-tier priority chain)                                           | override → backward-unified → ref-return → slot-declaration; winning tier marked `✓`. Source fn-name on each tier is clickable — jumps to the fn that pinned the constraint. Type-row names in the type column are also clickable. |
| Allowed values    | Slot's effective type is `[:refine kw [:in […]]]`                        | Each member rendered as a chip. |
| Slot effect bound | Slot's effective type is `[:fn args ret eff]` with a concrete eff set    | `eff: pure` (empty set) or one chip per allowed category. |

The `↳` glyph on a fn-card's return-type strip opens the type-rule
variant (`GET /partials/return-type-rule?fn=<name>`, also fully
server-rendered): rule-owner attribution as a clickable link, a
static per-rule narrative from the graph-resident `:_rtr-narratives`
map — e.g. for `:assoc`: "Literal key + typed value add that field
to the map's record shape; a computed key widens the result to
:jsonb." — and an Inputs table over the registry's raw
`:resolved-bindings`.

**3. On-chip narrowing hints** — visible without any click:

- **Refinement stacking**: `:positive-int` chip renders the alias
  name on top and `(> 0)` constraint underneath. Hover the
  constraint span for the natural-language form
  (`constraintHuman` → "integer where >= 1024 and <= 65535").
- **`< :base` subtype line** on the chip when a binding-level
  `:type-override` (or an inherited override) narrowed the slot
  to a type whose declared base differs from the displayed text.
  Lets the reader see `:positive-int < :any` without opening
  any popover. Skipped for refinement-stacked chips (the chain
  breadcrumb already exposes the base).

**4. Mismatch explainer** (red `!` indicator click): the bind-time
"why is this rejected?" surface — expected / got / reason / leaf
disagreements — followed by the SAME provenance chain renderer as
the `↳` popover, so the source-fn names that pinned the offending
constraint are clickable from inside the mismatch too. One click to
trace upstream from the failure.

### Type-error messages with `file:line`

The package loader uses `clojure.tools.reader` (instead of
`clojure.edn/read`) to preserve source-position metadata on every
fn-def. The type-checker threads it via a dynamic var into every
error message, so authors see exactly which EDN entry to open:

```text
  at packages/web/ring-adapter/fns.edn:188
Type-check failed in fn-def :token-valid?
  arg :a ← fn-ref → :auth-token-from-env
  parent :equal? expects: :int
  actual:                 [:union :null :text]
  hint: the ref's return type is the actual
```

### `bb effects`

Effects-breakdown report for a running instance:

```text
$ bb effects
URL: http://localhost:9002/api/types
▶ effects breakdown  (effectful 94 of 452)
  db       ( 41)  :_all-entities-body, :_router, :all, …
  env      ( 31)  :_auth-required-body, :auth-token-from-env, …
  io       ( 33)  :_editor-handler, …
  network  (  3)  :http-server, :http-stop, :web-server
  time     ( 10)  :_health-handler, :current-time-ms, …

⚠ drift in 1 fn-def(s):
  metrics
    over-declared (declared but not computed): time
    declared: time  computed:
```

Exit code: `0` on no drift or over-declaration only (harmless); `1`
when at least one fn-def has REAL drift (a computed effect not in
its `:expects-effects`). Useful in CI as an audit gate without
blocking on the more lenient sync-time WARN.

---

## Implementation Phases

### Phase 1: Primitive type checking

**Goal:** catch basic type mismatches at save time.

- Add `computed-type` field to fn entity
- Define types for all existing base-fns (`:int`, `:text`, `:bool`, `:jsonb`, `:any`)
- On fn-def save: compute return type from parent chain
- On fn-def save: check ref-id return type compatibility with expected arg type
- Literal value type checking (10 is `:int`, "hello" is `:text`)
- `:jsonb` as universal compatible type (escape hatch)

**Catches:** `:text` passed where `:int` expected, wrong types through ref-id chains.

### Phase 2: Parametric polymorphism

**Goal:** infer types through HOFs (map, filter, comp).

- Type variables (`:a`, `:b`) on base-fn args and return types
- Unification algorithm: when args are bound, substitute type variables
- Infer return type of composed fns through map/filter/comp chains

**Catches:** predicate vs transformation confusion in HOFs, list element type tracking.

### Phase 3: Structural record types

**Goal:** type-check record field access.

- record type-rows (`:type {field type …}`) computing the record type from fields
- Type-rules for `assoc`, `get`, `dissoc`, `merge`
- Record subtyping (more fields ⊂ fewer fields)
- Degradation to `:jsonb` when keys are computed (ref-id, not literal)
- `:return-type` override on a fn for boundary declarations

**Catches:** typos in field names, wrong field types, missing fields.

### Phase 4: Refinement types and narrowing

**Goal:** enforce constraint validation at boundaries.

- `refine` base-fn for defining named subtypes
- Subtype checking: `:positive-int` ⊂ `:int`
- `:_refinement-narrow`-based narrowers (e.g. `:ensure-positive-int`) as explicit conversion point
- `type-refinement` field on arg for narrowing in inheritance
- Narrowing validation (only subtype allowed, not supertype)

**Catches:** missing boundary validation, invalid narrowing in inheritance.

### Phase 5: Runtime validation generation

**Goal:** one definition, static + runtime checking.

- `type-schema` base-fn: introspect `computed-type`, generate malli schema
- Integration with `json-handler` for automatic request body validation
- Validation error messages derived from type structure

### Phase 6: Effect categories ✅

**Goal:** taint-style tracking of side effects through composition.

- `:effects #{:io :db :env :time :network :random}` set on each base-fn (later phases added `:process`, `:state`, `:raw-sql` — the full nine are in the Categories table above)
- Loader normalises legacy `:effectful? true` to `:effects #{:effect}`
- Composition unions ref-binding effects into the fn-def's set
- Editor renders colour-coded chips per category
- `:expects-effects` declarations + sync-time WARN on drift
- `bb effects` CLI report
- Per-category caching policy (`:time` / `:random` always-fresh,
  others cacheable within one top-level invocation)

### Phase 7: Runtime type-registry mutation ✅

**Goal:** types created via API/editor become resolvable to the
type-checker without a server restart, and broken bindings get
caught at write time instead of silently breaking later. (The
"caught = rejected" half of this phase was later superseded by
error-tolerance Phase 2 — see the note below.)

**Single invalidation entry point.** `invalidate-graph-cache!` in
`executor/context.clj` clears `:graph-cache`, `:compiled-registry`,
AND lazily refreshes type-aliases from storage. Every CRUD mutation
goes through this — readers, the next `execute`, and the type-
checker all see the new state on the next request.

**DB-side type-alias registration.** `register-type-aliases-from-
db!` in `executor/compile-runtime.clj` walks fn-rows whose role
classifies as record / refinement / list / union (via
`type-row-role`'s constraint-tag detection) and registers each as
an alias. Idempotent. Iterates to a fixed point so inner-name
references resolve regardless of declaration order. Mirrors the
EDN-side `register-type-aliases!` which only sees package data —
runtime additions follow the same path.

**Save-time type-check → recorded diagnostics (updated by
error-tolerance Phase 2).** CRUD's create / update / tighten cores
run full `check-fn-def!` on the owning fn-def for any binding /
binding-list-item mutation. As originally shipped this phase
DELETED the just-created entity on failure (best-effort rollback)
and returned the diagnostic as a 400; error-tolerance Phase 2
flipped that: the row is KEPT, the failure is recorded in the
per-branch diagnostics store (`graphden.types.diagnostics`,
cleared when a later write fixes the fn), and the 200 response
carries `:type-warnings [{…diagnostic…}]` additively. Two classes
stay hard rejects on a user write: the structural gates (cycles,
name collisions, terminal / list-closed, MI, reparent-cross-branch)
AND secret-flow subtype violations — a diagnostic whose types carry
the `:secret` marker rolls the write back and returns the legacy
400 (`secret-diagnostic?` in `crud/type-check`; rationale in
SECRETS.md § Flow protection vs Error Tolerance). The package
corpus stays hard-gated at sync time by the sweep allowlist.
`parse-fn-from-form` resolves `return-type` form values via
storage lookup with explicit error on unknown names.

**Polymorphic `:invoke` via runtime detection.** `:invoke :func`
is typed `[:fn {:arg a} b]` — the type-checker unifies, runtime
disambiguates via `:produces-callable?` flag computed on each
ref-binding (`compile/bindings.clj` consults the bound fn's
`:return-type` from the rich-types registry — when it's itself
a fn-type, the fn-graph PRODUCES a callable so `make-ref-entry`
thunks instead of `hof-wrap`-ping). No `:hof-wrap` slot
annotation; the dispatch is type-derived. The registry's seed
records a composed fn-def without its own `:return-type` with its
PARENT's registered return (`record-rich-types!` `inherited-return`
— the module re-seed pass runs after every name of the module is
in, so order inside a file does not matter), and
`effective-return-type` walks the parent chain again at the
decision as a second line — an extension of `:ring-handler`
produces a callable whether or not the type-check sweep has run
(`router_without_sweep_test`, `record-rich-types-inherits-the-parents-return-test`).

**`record ↔ :jsonb` unification.** `subtype?` already accepted
records as ⊆ `:jsonb` (records are jsonb-shaped on the wire);
`unify` now does too. Lets a slot whose type-var narrowed to
`:jsonb` at one call-site unify against a more-precisely-typed
record at a later call-site — fixes the chain
`:_app-ring-response :func :_router` where `:invoke`'s `:arg`
type-var had been pinned to `:jsonb` by `:router-result :arg
:internal-request`.

### Phase 8: Slot-level effect constraint ✅

**Goal:** let a slot whose type is `:fn` declare *which effects the
callable is allowed to perform*. Effectful callbacks bound into a
pure slot get rejected at sync time, just like a return-type
mismatch — same channel, same diagnostic shape.

**Wire format.** The fn-type form gains an optional 4th element —
the effect constraint set on the *callable* side:

```edn
;; filter's :pred slot — pure-only constraint (map's :func carries NONE)
:pred {:type [:fn {:item a} :bool #{}]}

;; an alternate hypothetical "give me an :env-or-pure callable" slot
:func {:type [:fn {:item a} b #{:env}]}
```

Authors may still write a 3-element form `[:fn args ret]` in EDN —
`normalise` (the canonicalisation pass at every subtype/unify
boundary) rewrites it to the 4-element form with `:any` (the slot-
side "no constraint declared" sentinel). Internally every fn-type
is 4-element; the 3-element legacy form is read-time sugar only.

A 4-element form with `:any` in slot 3 demands no constraint
(matches a 3-element form's intent). A 4-element form with a
concrete set (`#{}` for pure, `#{:db}` for db-only, …) demands the
bound callable's effects ⊆ that set.

`make-fn-type` is the canonical constructor for new sites and
always produces 4-element output.

**Subtype rule.** `(fn-subtype? sub sup)` requires
`sub-effects ⊆ sup-effects`, mirroring how arg variance and return
covariance already work. `compute-effects` is total — every fn-def
has a known set, possibly `#{}` (pure) — and `record-rich-types!`
always stores it explicitly (`:effects #{}` for pure fns). nil
sub-effects only occurs for legacy fn-types missing the 4th element;
those normalise to `:any` (unconstrained), not pure. nil sup-effects
accepts anything (no constraint declared at the slot).

**Where it fires.** `check-fn-def!` walks each ref-binding,
computes the callable's effective fn-type via `assemble-fn-type`
(which surfaces the bound fn's `:effects` from the rich-types
registry as the 4th element), then `subtype?`s against the slot's
expected fn-type. A mismatch raises the standard `:types/check-
failed` exception with the same arg-name / parent / expected /
actual fields as a return-type mismatch.

**Coverage in core/hof.** HOF *predicate* slots carry the `#{}`
constraint; `:map`'s `:func` deliberately does NOT:

| HOF | Slot | Constraint | Why |
|-----|------|-----------|-----|
| `:map` | `:func` | *(none)* | mapping an effectful transform (`:read-resource` over paths) is normal; effects propagate via `compute-effects` and the result is tagged, not rejected. A `:map`-callback's effect only tags the elements it produces — the collection's shape stays deterministic |
| `:filter` | `:pred` | `#{}` | an effectful predicate changes WHICH elements survive — unsafe under lazy realisation; predicate must be idempotent / replayable |
| `:reduce` | `:func` | `#{}` | result must depend only on `(init, coll)` |
| `:some` | `:pred` | `#{}` | early-termination relies on determinism |
| `:every?` | `:pred` | `#{}` | same |
| `:find-first` | `:pred` | `#{}` | same |
| `:group-by` | `:key-fn` | `#{}` | same item must hash to the same bucket |
| `:sort-by` | `:key-fn` | `#{}` | comparator stability |
| `:transduce` | `:reducer` | `#{}` | same fold rationale as `:reduce` |

Binding e.g. `:env-flag-pred` (effects `#{:env}`) into `:filter
:pred` now fails sync with:

```text
parent :filter expects: [:fn {:item a} :bool #{}]
actual:                 [:fn {:item :any} :bool #{:env}]
```

**Why slot-level, not whole-function.** Haskell-style monadic
effect tracking would require *every* call site to thread
contexts. Graphden's value: catch the *specific* anti-pattern
where an effectful callback silently slips into a pure HOF — same
shape as catching a `:add-10` predicate (returns int, not bool).
Slot-level is the smallest mechanism that delivers that.

**Roundtrip preservation.** `resolve` (substitution),
`resolve-alias`, `unify-fn`, and `assemble-fn-type` all preserve
the 4th element. After the storage-unification cleanup the registry
always carries `:effects` — possibly `#{}` for pure fns — so
`assemble-fn-type` emits a canonical 4-element fn-type
unconditionally. The "absent key vs. empty set" ambiguity was
retired; `compute-effects`-computed pure and explicit `#{}` are
the same wire representation.

### Phase 9: Caller-context propagation (Phase α') ✅

Pass 1 of the type-check sweep checks each fn-def in ISOLATION —
its rule fires reading only the fn-def's own bindings, with free
args defaulting to their parent's slot type (which is often the
widest `:any`-like form). The "real" type of a free arg, at any
SPECIFIC call site, is determined by what the caller binds. Pass 1
can't see this.

For most patterns this is fine — `:_some-fn` whose free arg `:x` is
typed `:any` simply means "I work on any `:x`". But a chain like
`:_X-apply-entity-type-str = (:name (:get :parsed :entity-type
:default nil))` is BROKEN by isolation: its computed return is
`[:union :null :text]` (because `:parsed` is `:any` so
`:entity-type` lookup returns `:any`), but every actual caller
binds `:parsed` to a typed record where `:entity-type` is
`:keyword`. The narrowed answer (`:text`) is what the binding-check
needs but isolation never sees.

Pass 2 / Pass 3 close this gap:

**Pass 2** (`build-caller-narrowings`) walks every binder F. For
each ref-binding `:arg :ref-name` where `:arg` is a TRUE lifted
free arg (NOT a parent-contract slot), it propagates the ref's
recorded return type DOWN through F's transitive ref-tree
EXCLUDING `:ref-name` itself (the ref PRODUCES the value; the
consumers are the siblings). The propagation lands on every
fn-def that has a rename `{:as :arg}` in its OWN args — those are
the lexical leaves where the free-arg name was originally
introduced. Result: a `{rename-host-fn-name → {as-name →
narrowed-type}}` map.

**Pass 3** (`check-fn-def-with-narrowings!`) re-runs
`check-fn-def!` on every fn-def with `*caller-narrowings*` bound
to the per-callee entry. The rename branches in
`bindings-info-for-rule` + `collect-free-args` honour the
narrowed type. Outer consumers that don't introduce the rename
locally see the narrowing via `effective-ref-return`'s normal
rule re-firing reading the narrowed leaf's registry.

#### Per-use-site anon naming

The original `anon-fn-name` (parser pre-pass) dedup'd
identical-shape inline anons across the whole module — two
fn-defs with the same `(:get :coll {:as :parsed} :key :entity-type
:default nil)` shape collapsed to ONE synthetic `_anon-<hex>`
registry entry. Pass-3 narrowing would then conflict: caller A
narrows `:parsed := :_create-parsed-shape`, caller B narrows
`:parsed := :_seq-append-parsed-shape`, and the single anon's
registry entry can't reflect both.

The fix mixes the use-site host (`[parent-fn-def-name
parent-arg-name]`) into the hash. Two identical-shape anons at
different use-sites now get DISTINCT synthetic names — their
narrowings stay scoped per-consumer. Registry size grew modestly
(532 → 695 anons in the production package set); the dedup
optimisation traded for correctness under propagation.

#### Side fix: `effective-ref-return` merge order

`effective-ref-return` built `combined = merge ref-bindings
caller-bindings` — caller-wins. A deeper rename chain whose `:coll`
(or any other slot-named entry) carried a Pass-3 narrowed type
would override unrelated refs' own `:coll` binding when re-fired
via this path. Flipped to `(merge caller-bindings ref-bindings)`
— ref's own bindings shadow caller's; caller still contributes
free-arg context for keys the ref doesn't bind locally.

#### Outcome

The original 10 `:_X-apply-*` family failures closed. The
post-α'-precision-surfaced 11 nullability gaps closed via author
`:type T` annotations on binding forms (each guarded by an
upstream nil-check at runtime). Sweep at zero;
`allowed-type-check-failures` is `#{}` and the Phase E hard-gate
stays armed both directions. See
`docs/TYPE_SYSTEM_DECISIONS.md` for the rejected alternative
phases (β / γ / #170 v2) with rationale.

### Phase 10: Control-flow narrowing through `:if`/`:cond` guards (Phase #170 v1) ✅

A focused subset of flow-sensitive narrowing shipped:
`:if` / `:cond` whose `:test` ref (or clause-test ref) walks
to a root `:some?` / `:nil?` whose `:value` slot is bound to
a fn-name `:_T` get treated as control-flow guards. The
non-null narrowing flows into the taken branch's transitive
ref-tree.

Implementation (`src/graphden/types/check.clj`):

- `build-ref-return-overrides` — for each fn-def, collect any
  `:if` / `:cond` guards whose root predicate matches the
  recognised shapes (`:some?` / `:nil?` over a bare fn-ref).
- `if-branch-overrides`, `cond-branch-overrides` — per-branch
  narrowing maps for the `:then` / `:else` (`:if`) or per-
  clause result (`:cond`).
- `*ref-return-overrides*` dynvar — bound in Pass 3 alongside
  `*caller-narrowings*` (`check-fn-def-with-narrowings!`).
- `ref-return-narrowed` — every site that historically read
  `:return` straight off the registry while type-checking a
  binding now funnels through here:
  `bindings-info-for-rule`'s `{:ref ...}` branch,
  `ref-binding?` actual computation, sequence-item lookup,
  and the vector-binding `:fn-ref` / `:ref-map` shape readers.

Verified by removing the `:type :text` assertion on
`:_list-exec-limit-parsed-body` — sweep stays at 0, full test
suite green.

**Boundaries** — what v1 does NOT cover (composed guards,
record-field projections, `:and`/`:or` decomposition): each
remaining site uses an explicit `:type T` author-assertion
paired with a one-line `;; <invariant>` comment. The decision
to NOT extend recognition (v2) is recorded in
`docs/TYPE_SYSTEM_DECISIONS.md` — explicit assertion + comment
beats implicit inference per project principle #3.

### Author `:type T` assertions — the canonical pattern

The seven sites below use explicit `:type T` overrides on
binding forms to encode runtime invariants the type-checker
can't yet trace. Each is sound (the invariant holds because
of an upstream validation guard); each has a comment at the
binding site naming the guard.

| site | guard shape | reason inference can't cover |
|---|---|---|
| `:_bearer-token-raw` (`:ref :authorization-header :type :text`) | `:_has-bearer-prefix?` `:str-starts-with?` shim | non-`:some?`/`:nil?` predicate |
| `:_list-exec-limit-less-than-1?` (`:ref :_list-exec-limit-parsed :type :int`) | `:or` short-circuit `:_list-exec-limit-parsed :nil?` | `:or`-shaped guard not yet recognised |
| `:_list-exec-by-fn-version-id` (`:fn-id :type :uuid` via `:get :parsed`) | `:_list-exec-no-anchor? :and [...]` | `:and` of `:nil?` guards through field projection |
| `:_seq-remove-apply-do-delete` (`:id :type :uuid` via `:get :parsed`) | `:nil? (get parsed :item-id)` | per-use-site anon split |
| `:_create-branch-apply-row` (`:branch-name :type :text` via `:get :parsed`) | `:_blank?` (`:str-blank?` shim) | non-`:some?`/`:nil?` predicate |
| `:_inline-bind-target-fn-row` / `:_delete-secret-fn-row` (`:id :type :uuid`) | validation upstream | per-use-site anon split |
| `:_update-id-uuid` (`:ref :type :uuid` in `web/crud`) | parse-uuid + `:nil?` guard at caller | per-use-site anon split |

Adding a new site follows the same recipe: when the sweep
fails on a new fn-def because the checker can't trace a
runtime guard, add `:type T` to the binding form with a
one-line comment naming the guard. Phase E catches it loudly;
fix is a 30-second edit.
