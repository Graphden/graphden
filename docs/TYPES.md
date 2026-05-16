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

1. **Parametric polymorphism with inference** — type variables (`:a`, `:b`) on base-fns, automatically substituted when arguments are bound. Like Haskell's Hindley-Milner, but simpler because graphden fn-defs are not arbitrary programs.

2. **Structural type computation** — for operations like `assoc`, `get-field`, `dissoc`, `merge`, the system computes the output record type from concrete argument values. This is possible because in graphden, keys are typically literals stored in the DB, not runtime variables.

3. **Refinement subtypes** — named subtypes with constraints (e.g., `:positive-int`), expressed as regular fn-defs. The system enforces subtype relationships: where a supertype is expected, a subtype is accepted; where a subtype is expected, a supertype is rejected.

All three mechanisms use the same infrastructure — fn-defs, arg entities, `computed-type` field.

---

## Type Hierarchy

```
:any                           ← top type, accepts everything
├── :jsonb                     ← untyped structured data (escape hatch)
├── :int                       ← integer
│   └── :positive-int          ← refinement subtype (constraint: > 0)
├── :float                     ← floating point
├── :text                      ← string
├── :bool                      ← boolean
├── :keyword                   ← keyword
├── [:list :a]                 ← parameterized list
├── {:name :text, :age :int}   ← structural record type
└── {:fn [:a :b]}              ← function type (input → output)
```

Subtyping rules:

- Every type is a subtype of `:any`
- Primitive types (`:int`, `:text`, etc.) are subtypes of `:jsonb`
- Record types are subtypes of `:jsonb`
- A record with MORE fields is a subtype of a record with FEWER fields (`{:a :int, :b :text}` ⊂ `{:a :int}`)
- A refinement type is a subtype of its base type (`:positive-int` ⊂ `:int`)
- `:jsonb` is NOT a subtype of any concrete type (requires explicit conversion)

---

## When Types Are Checked

Type checking happens **at fn-def save time** — when the user creates or modifies a fn-def through API or UI. Not at runtime.

```
User creates/modifies fn-def
         │
         ▼
┌─────────────────┐
│ Resolve parents  │  Walk inheritance chain, find base-fn
└────────┬────────┘
         ▼
┌─────────────────┐
│ Type inference   │  Substitute type variables,
│                  │  apply type-rules for assoc/get-field,
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
 :args {:a {:type :int}, :b {:type :int}}
 :return-type :int}

{:name :map
 :args {:f {:type {:fn [:a :b]}}, :coll {:type [:list :a]}}
 :return-type [:list :b]}

{:name :http-get
 :args {:url {:type :text}}
 :return-type :jsonb}
```

**Users (fn-def creators)** never write types. Types are inferred:

```edn
{:name :add-10
 :parent :add
 :args {:a 10}}
;; System infers:
;;   a = 10, type :int, matches expected :int ✓
;;   b remains free, type :int
;;   return-type: :int
;; User wrote zero types.
```

**Exception:** at system boundaries (HTTP responses, external APIs), the user may optionally declare a return type override when the system cannot infer the structure:

```edn
{:name :get-user-api
 :parent :http-get
 :args {:url "https://api.example.com/user"}
 :return-type-override {:name :text, :age :int}}
```

---

## Type Inference

### Concrete value binding

When an arg is bound to a literal value, the system checks that the value's type matches the expected arg type:

```edn
{:name :add-10, :parent :add, :args {:a 10}}
;; 10 is :int, arg :a expects :int → ✓

{:name :add-broken, :parent :add, :args {:a "hello"}}
;; "hello" is :text, arg :a expects :int → ERROR
```

### Ref-id binding

When an arg is bound via `ref-id`, the system checks that the referenced fn's return type is a subtype of the expected arg type:

```edn
{:name :double-age, :parent :add, :args {:a :get-user-age, :b :get-user-age}}
;; get-user-age returns :int, add expects :int → ✓

{:name :broken, :parent :add, :args {:a :get-user-name}}
;; get-user-name returns :text, add expects :int → ERROR
```

### Parametric polymorphism (type variables)

Type variables on base-fns are unified when arguments are bound:

```edn
;; map :: {:f {:fn [:a :b]}, :coll [:list :a]} → [:list :b]

{:name :map-add-10, :parent :map, :args {:f :add-10}}
;; add-10 :: :int → :int (one free arg of type :int, returns :int)
;; Unification: :a = :int, :b = :int
;; Result: map-add-10 :: {:coll [:list :int]} → [:list :int]

{:name :map-upper, :parent :map, :args {:f :str-upper}}
;; str-upper :: :text → :text
;; Unification: :a = :text, :b = :text
;; Result: map-upper :: {:coll [:list :text]} → [:list :text]
```

Error example:

```edn
;; filter :: {:f {:fn [:a :bool]}, :coll [:list :a]} → [:list :a]

{:name :broken-filter, :parent :filter, :args {:f :add-10}}
;; add-10 returns :int, but filter expects :f to return :bool
;; :int ≠ :bool → ERROR
```

### Free argument type propagation

When a fn-def leaves arguments free, their types propagate to callers:

```edn
{:name :add-10, :parent :add, :args {:a 10}}
;; Free args: {:b :int}
;; Callers of add-10 must provide :b of type :int
```

---

## Structural Types (Records)

A record type is defined as a fn-def with parent `:record`:

```edn
{:name :message
 :parent :record
 :new-args {:from {:type :text}
            :text {:type :text}
            :timestamp {:type :int}}}

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

```
type-rule: if k is a literal, result = type(m) + {k: type(v)}
           if k is a ref-id, result = :jsonb (degradation)
```

Example:

```edn
{:name :with-name, :parent :assoc, :args {:m {}, :k "name", :v "Alice"}}
;; m = {} → type {}
;; k = "name" (literal, value known)
;; v = "Alice" → type :text
;; Result type: {:name :text}

{:name :with-name-and-age, :parent :assoc, :args {:m :with-name, :k "age", :v 30}}
;; m → :with-name → type {:name :text}
;; k = "age", v = 30 → :int
;; Result type: {:name :text, :age :int}
```

### get-field

```
type-rule: if field is a literal and obj has record type,
           check field exists, return field type.
           if field is a ref-id, result = :jsonb (degradation)
```

Example:

```edn
{:name :get-name, :parent :get-field, :args {:obj :with-name-and-age, :field "name"}}
;; obj type: {:name :text, :age :int}
;; field = "name" → exists, type :text ✓
;; Result type: :text

{:name :get-email, :parent :get-field, :args {:obj :with-name-and-age, :field "email"}}
;; field = "email" → NOT in {:name :text, :age :int}
;; ERROR: field "email" not found. Available: name, age
```

### dissoc

```
type-rule: if k is a literal, result = type(m) minus field k
```

### merge

```
type-rule: result = type(a) ∪ type(b)
```

### Degradation

When a key is a `ref-id` (computed value), the system knows the key's TYPE (`:text`) but not its VALUE. Type-rules that need the value degrade to `:jsonb` with a warning:

```edn
{:name :dynamic-get, :parent :get-field,
 :args {:obj :user, :field :compute-field-name}}
;; field is ref-id → value unknown → cannot check field existence
;; Result type: :jsonb (with WARNING)
```

---

## Refinement Types

A refinement type is a named subtype with constraints, expressed as a regular fn-def:

```edn
{:name :positive-int
 :parent :refine
 :args {:base-type :int
        :constraint [:> 0]}}
;; computed-type: {:subtype-of :int, :constraint [:> 0]}
```

`:refine` is a base-fn with a type-rule that establishes the subtype relationship.

### Usage

```edn
{:name :sqrt
 :args {:n :positive-int}
 :return-type :float}

;; Passing positive-int where int is expected — OK (subtype)
{:name :inc-positive, :parent :add, :args {:a :some-positive-value, :b 1}}
;; :positive-int ⊂ :int → ✓

;; Passing int where positive-int is expected — ERROR
{:name :sqrt-any, :parent :sqrt, :args {:n :some-int-value}}
;; :int ⊄ :positive-int → ERROR
;; User must insert explicit validation
```

### Explicit validation at boundaries

```edn
{:name :ensure-positive
 :parent :validate-refinement
 :args {:type :positive-int}}
;; Accepts :int, returns :positive-int
;; At runtime: if value <= 0 → error
;; For type system: narrows :int → :positive-int

{:name :sqrt-safe
 :parent :sqrt
 :args {:n :ensured-value}}

{:name :ensured-value
 :parent :ensure-positive
 :args {:value :some-int-value}}
;; some-int-value → :int
;; ensure-positive accepts :int, returns :positive-int
;; sqrt accepts :positive-int ✓
```

The system does NOT prove that a value is positive. It forces the programmer to **explicitly mark the point where the constraint is validated**. The validation itself happens at runtime. But forgetting it is impossible — the type system rejects the fn-def without it.

### Refinement constraints do NOT propagate through arithmetic

```edn
{:name :decrement, :parent :sub, :args {:a :some-positive, :b 1}}
;; Result type: :int (not :positive-int)
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

The `:if` base-fn's type-rule produces a union of its branches:

```edn
{:parent :if :args {:test :some-bool? :then 42 :else "fallback"}}
;; computed return-type: [:union :int :text]
```

`bearer-token` (which uses `:if` with a `nil :else`) ends up at
`[:union :null :text]` — the same shape `:nullable-text` declares.

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
{:name :variant-tag    :parent :get      :args {:key "tag"}}
{:name :variant-value  :parent :get      :args {:key "value"}}
{:name :variant-is?    :parent :equal?   :args {:a :variant-tag :b {:as :tag}}}
```

Authors then dispatch via the existing `:cond`:

```edn
{:parent :cond
 :args {:pairs [(:variant-is? :coll my-result :tag :ok)
                (:variant-value :coll my-result)
                :else
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
| `:positive-numeric`| `[:refine :numeric [:> 0]]`                                       | rates, weights                   |
| `:percent`         | `[:refine :numeric [:and [:>= 0] [:<= 100]]]`                     | percentages                      |
| `:probability`     | `[:refine :numeric [:and [:>= 0] [:<= 1]]]`                       | probabilities                    |
| `:port`            | `[:refine :int [:and [:>= 1] [:<= 65535]]]`                       | TCP/UDP ports                    |
| `:user-port`       | `[:refine :int [:and [:>= 1024] [:<= 65535]]]`                    | non-privileged ports             |
| `:http-status`     | `[:refine :int [:and [:>= 100] [:<= 599]]]`                       | HTTP status codes                |
| `:bit`             | `[:refine :int [:or [:= 0] [:= 1]]]`                              | binary flags                     |
| `:nullable-T`      | `[:union :null T]`  (T ∈ #{int text bool numeric uuid})           | optional values                  |
| `:result-T`        | tagged variant `{:ok T}|{:err :text}`                              | Result-style returns             |
| `:validation`      | tagged variant `{:valid :any}|{:invalid :text}`                    | parsed-vs-error                  |

Modules adding their own named type-rows just put the
`:refine` / `:list` / `:union` / `:variant` / `:record` entries
directly in their `fns.edn` alongside their fn-defs — the loader
recognises the shape and emits the right fn-row + slot structure.

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
| `:effect`  | legacy generic flag (`:effectful? true`)      | yes                              |

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

```
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
  bound". It's a *hard reject* at sync time, same channel as a
  return-type mismatch.

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
- **Required → optional**: forbidden. The sync-time check rejects
  any binding row carrying `:required false` with
  `:bindings/widening-required`. Optionality is declared once, on
  the slot itself.

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
| `check-required-widening!` | Pre-pass in `check-fn-def!` — rejects any binding map carrying `:required false`. Tagged `:bindings/widening-required`. Fires on package load AND on CRUD writes (CRUD funnels through `check-fn-def!` post-create). |
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

### What is stored in DB

A slot's effective type comes from `slot.type-fn-id` overlayed by
the closest `binding.type-override-fn-id` along the `parent-fn-ids`
inheritance closure. Refinement narrowing flows through the
type-row chain (`fn.base-fn-id` for `:refine`, `fn.element-fn-id`
for `:list`, `fn.constraint` for predicates).

---

## Typed and Untyped Boundary

```
Untyped world                    Typed world
(jsonb, any)                     (int, text, bool, records)
                                 
  HTTP responses ──┐                    │
  DB query results ┤  to-int       ┌───┤
  JSON parsing ────┘ ────────► :int│   ├── add, gt, mul, sub
                     to-text       │   ├── map, filter, comp
                    ────────►:text │   ├── if, and, or
                                   └───┘
                                       │    assoc, get-field
                    ◄──────────────────┘  ──────────►
                     to-jsonb              back to jsonb
```

Converter functions (`to-int`, `to-text`, `ensure-positive`) are **bridges**. They may fail at runtime (if the value is not of the expected format), but after them, type guarantees hold. The user chooses where to draw the boundary.

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
| Nonexistent record field | `get-field "nme"` on `{:name :text}` |
| Missing required conversion | `:int` passed where `:positive-int` expected |
| Type mismatch through chain | `get-field "name" → :text → add` (expects `:int`) |
| Incompatible type narrowing | Widening in inheritance chain |

### Does NOT catch

| Error | Why |
|-------|-----|
| Invalid JSON structure at runtime | Data arrives at runtime, types check composition |
| Business logic errors (amount < 0) | Requires SMT solver for arithmetic constraints |
| Dynamic field names | `get-field` with computed key — value unknown |
| Network/IO failures | Not a type system concern |
| Correct field name but wrong data from API | Type system trusts `return-type-override` |

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

---

## Storage Schema

Types live as fn-rows themselves (record / refinement / list /
union / variant) plus pointer fields on entities:

### fn entity (type-related fields)

```
fn:
  ...
  return-type-fn-id   ref<fn>  -- author-declared return type
  base-fn-id          ref<fn>  -- :refine — what we narrow
  constraint          jsonb    -- :refine predicate
  element-fn-id       ref<fn>  -- :list element type
  anonymous-hash      text     -- dedup key for inline composites
```

### slot entity (per-slot type)

```
slot:
  type-fn-id   ref<fn>  -- the slot's value type
  required     bool
```

### binding entity (per-fn override)

```
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
{
  "bearer-token": {
    "return": ["union", "null", "text"],
    "args":   { "coll": "jsonb", "default": "any" },
    "effects": ["env"],
    "source-file": "packages/web/ring-adapter/fns.edn",
    "source-line": 188
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

### Type-error messages with `file:line`

The package loader uses `clojure.tools.reader` (instead of
`clojure.edn/read`) to preserve source-position metadata on every
fn-def. The type-checker threads it via a dynamic var into every
error message, so authors see exactly which EDN entry to open:

```
  at packages/web/ring-adapter/fns.edn:188
Type-check failed in fn-def :token-valid?
  arg :a ← fn-ref → :auth-token-from-env
  parent :equal? expects: :int
  actual:                 [:union :null :text]
  hint: the ref's return type is the actual
```

### `bb effects`

Effects-breakdown report for a running instance:

```
$ bb effects
URL: http://localhost:9002/api/types
▶ effects breakdown  (effectful 94 of 452)
  db       ( 41)  :_all-entities-body, :_router, :all, …
  env      ( 31)  :_auth-required-body, :auth-token-from-env, …
  io       ( 34)  :_build-hashes-raw, :_editor-handler, …
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

- `record` base-fn with type-rule computing record type from args
- Type-rules for `assoc`, `get-field`, `dissoc`, `merge`
- Record subtyping (more fields ⊂ fewer fields)
- Degradation to `:jsonb` when keys are computed (ref-id, not literal)
- `return-type-override` field on fn for boundary declarations

**Catches:** typos in field names, wrong field types, missing fields.

### Phase 4: Refinement types and narrowing

**Goal:** enforce constraint validation at boundaries.

- `refine` base-fn for defining named subtypes
- Subtype checking: `:positive-int` ⊂ `:int`
- `validate-refinement` base-fn as explicit conversion point
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

- `:effects #{:io :db :env :time :network :random}` set on each base-fn
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
rejected at write time instead of silently breaking later.

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

**Save-time rejection on type-check failure.** CRUD's
`process-create-entity` validates ref-bindings (target's
`:return-type` ⊆ slot's expected type via `subtype?`) AND runs
full `check-fn-def!` on the owning fn-def for any binding /
binding-list-item mutation. On failure, the just-created entity
is deleted (best-effort rollback) and the response carries the
type-checker's diagnostic. `parse-fn-from-form` resolves
`return-type` form values via storage lookup with explicit error
on unknown names.

**Polymorphic `:invoke` via runtime detection.** `:invoke :func`
is typed `[:fn {:arg a} b]` — the type-checker unifies, runtime
disambiguates via `:produces-callable?` flag computed on each
ref-binding (`compile/bindings.clj` consults the bound fn's
`:return-type` from the rich-types registry — when it's itself
a fn-type, the fn-graph PRODUCES a callable so `make-ref-entry`
thunks instead of `hof-wrap`-ping). No `:hof-wrap` slot
annotation; the dispatch is type-derived.

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
;; map's :func slot — pure-only constraint
:func {:type [:fn {:item a} b #{}]}

;; an alternate hypothetical "give me an :env-or-pure callable" slot
:func {:type [:fn {:item a} b #{:env}]}
```

A 3-element form `[:fn args ret]` has *no* effect constraint —
"any effects allowed". A 4-element form `[:fn args ret eff-set]`
demands the bound callable's effects ⊆ `eff-set`.

**Subtype rule.** `(fn-subtype? sub sup)` requires
`sub-effects ⊆ sup-effects`, mirroring how arg variance and return
covariance already work. nil sub-effects means the callee is
**pure** (`#{}`): graphden computes effects totally — every fn-def
goes through `compute-effects`, which treats an absent `:effects`
as `#{}` — so a missing effect set is computed-pure, not "unknown".
Treating it otherwise ("assume impure") would make a `#{}` pure-only
slot unsatisfiable by any ordinary pure fn. nil sup-effects accepts
anything (no constraint declared).

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

```
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
the 4th element when present. `assemble-fn-type` keys off
`(contains? info :effects)` rather than `(seq eff)` so an explicit
`:effects #{}` rides through as a 4-element fn-type — but the
distinction is no longer load-bearing for the check outcome: both
an absent `:effects` and an explicit `#{}` are read as **pure** by
`effects-compatible?`, consistent with `compute-effects`.
