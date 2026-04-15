# Graphden Type System

> **Last updated:** 2026-04-15
>
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
10. [Type Narrowing Through Inheritance](#type-narrowing-through-inheritance)
11. [Typed and Untyped Boundary](#typed-and-untyped-boundary)
12. [Runtime Validation from Types](#runtime-validation-from-types)
13. [What the Type System Catches and What It Does Not](#what-the-type-system-catches-and-what-it-does-not)
14. [Comparison with Other Systems](#comparison-with-other-systems)
15. [Storage Schema](#storage-schema)
16. [Implementation Phases](#implementation-phases)

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

Each arg may have an optional `type-refinement` field. The effective type is computed by walking the `source-id` chain and merging refinements (each must be a subtype of the previous).

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

Two new fields on existing entities, no new entities:

### fn entity

```
fn:
  ...existing fields...
  computed-type: jsonb          -- Computed by system, always present
                                -- e.g. {:args {:a :int, :b :int}, :return {:int}}
  return-type-override: jsonb   -- Optional, user-declared
                                -- Must be subtype of computed return type
                                -- Used instead of computed type when present
```

### arg entity

```
arg:
  ...existing fields...
  type-refinement: jsonb        -- Optional, narrowing constraint
                                -- Must be subtype of inherited type
                                -- Merges with parent refinements via source-id chain
```

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
