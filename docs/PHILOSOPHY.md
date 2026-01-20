# Graphden Philosophy

> **Last updated:** 2026-01-20
>
> This document describes the core principles and philosophy behind graphden.
> For technical architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).
> For implementation status, see [ROADMAP.md](ROADMAP.md).

## Table of Contents

1. [The Problem](#the-problem)
2. [Why Graph?](#why-graph)
3. [Design Principles](#design-principles)
4. [Language Aspects (SICP)](#language-aspects-sicp)
5. [Trade-offs and Constraints](#trade-offs-and-constraints)
6. [Component-to-Principle Mapping](#component-to-principle-mapping)
7. [Open Questions](#open-questions)

---

## The Problem

### The Gap Between Intent and Instructions

A programming language is a **bridge** between:
- **Human intent** — fuzzy, incomplete, often unconscious desires ("I want this to work")
- **Machine instructions** — fully deterministic, precise, unambiguous

Humans rarely understand exactly what they want or how to achieve it. Computers can help, but they need precise instructions. The journey from human intent to machine execution requires multiple steps: understanding, translation, verification.

**Programming languages help traverse this path.** The question is: how optimally?

### The Text Problem

Modern programming languages use **text** as the primary representation:

1. **Machine side**: Text → AST → bytecode/machine code. This works well — it's fast, precise, and reliable. No complaints here.

2. **Human side**: Humans write and read text to express their thoughts. **This is where we see room for improvement.**

Problems with text-based code:
- **Reading is hard** — requires parsing symbols, understanding context, building mental model
- **Writing is hard** — requires remembering syntax, conventions, boilerplate
- **Editing is error-prone** — typos, missing brackets, wrong indentation
- **Duplication** — copy-paste is the primary reuse mechanism in practice

---

## Why Graph?

### Graphs Are Universal

Almost all code, regardless of language, is first converted to an **Abstract Syntax Tree (AST)** before execution. An AST is a graph. This suggests that graphs are a natural representation for computation.

### Graphs Have Simple Representations

| Aspect | Graph Representation |
|--------|---------------------|
| **Visual** | Nodes connected by edges — intuitive to read |
| **Editing** | Connect nodes, break connections — direct manipulation |
| **Storage** | Structured data (entities, relationships) — queryable, versionable |
| **Execution** | Traverse graph, evaluate nodes — natural for lazy evaluation |

### Graphs Enable Features Text Cannot

1. **Automatic parallelization** — independent subgraphs can be identified and executed concurrently without explicit annotations
2. **Dependency tracking** — relationships are explicit, not implicit in text position
3. **Refactoring safety** — renaming, moving nodes preserves connections
4. **Queryable code** — "find all functions that use X" is a database query, not grep

---

## Design Principles

### Principle 1: Execution Correctness and Performance

**Goal**: Match or exceed classical languages in execution speed, safety, and correctness.

**Strategies**:
- Multi-level caching (execution graph cache → O(1) resolution)
- Multiple storage backends for different deployment scenarios
- Distributed execution across multiple executors (planned)
- Comprehensive test coverage (target: 100%)
- Minimal, simple entity model to reduce logic errors

**Non-negotiable**: We will not sacrifice correctness for any other goal.

### Principle 2: Development Simplicity (Primary Priority)

**Goal**: Make development simpler than text-based programming.

This breaks down into:

#### 2.1 Minimal Primitives

Following SICP, a language has three aspects:
1. **Primitives** — basic building blocks
2. **Means of combination** — how to compose primitives
3. **Means of abstraction** — how to name and reuse compositions

In graphden:
- **Primitives**: Nodes (fn, fn-schema, arg-schema, arg-value, fn-result-value)
- **Combination**: Edges (references between nodes)
- **Abstraction**: Inheritance (fn → parent-fn-id) and result caching (fn-result-value)

**We resist adding new entity types or edge types.** Every addition increases cognitive load and implementation complexity.

#### 2.2 DRY (Don't Repeat Yourself)

Abstractions must minimize the need to define anything twice:
- Inheritance chains allow partial application without copying
- fn-result-value enables sharing computed results
- Base functions provide reusable implementations

#### 2.3 Expressiveness Parity

**Must have**: Everything expressible in classical languages should be expressible in graphden. No "sorry, you can't do that here."

**Must avoid**: Unnecessary expressiveness. Examples:
- Design patterns in Clojure are possible but unnecessary
- List comprehensions in Python make sense only in Python's ecosystem
- `let` bindings in Clojure are essential for Clojure, but graphs name things differently

#### 2.4 Development Tool Support

The system should support (or enable) development tools:

| Tool Category | Classical Languages | Graphden Approach |
|---------------|--------------------|--------------------|
| Version control | Git (text diffs) | Graph versioning (planned) — group changes, rollback, branch, merge |
| Testing | Unit test frameworks | Isolated node execution with mocked dependencies |
| Linting | Static analysis | Graph structure constraints (already enforced) |
| IDE features | Indexing, navigation | Database queries (inherent) |
| Debugging | Stack traces | Execution path traces (see [Open Questions](#debugging-and-observability)) |

---

## Language Aspects (SICP)

### Primitives

| Entity | Purpose |
|--------|---------|
| `fn-schema` | Function signature (args, return type, optional base-fn-name) |
| `arg-schema` | Argument definition (name, type, required) |
| `fn` | Function instance (implements schema, optional parent for inheritance) |
| `arg-value` | Bound argument value (literal or reference) |
| `fn-result-value` | Cached computation reference (memoization within execution) |

**Why these five?** They are the minimal set needed to express:
- Function definitions (fn-schema + arg-schema)
- Function instances with values (fn + arg-value)
- Partial application / inheritance (fn.parent-fn-id)
- Result caching / sharing (fn-result-value)

### Means of Combination

Two types of references in arg-value:

| Reference Type | Syntax (in fn-defs) | Behavior |
|---------------|---------------------|----------|
| `ref<fn>` | `:fn-name` | Pass fn-id as value (for HOF) |
| `ref<fn-result-value>` | `:fn-name>` | Execute and use result |

**Why two types?** Higher-order functions (map, filter, reduce) need to receive functions as values, not their results. This is the minimum necessary distinction.

**We considered alternatives**:
- Inferring from arg-schema type — possible but makes behavior implicit
- Single reference type with explicit "execute" node — more entities

### Means of Abstraction

#### Inheritance (Currying)

```
fn: A (parent: null)
  arg-values: {x: 1}

fn: B (parent: A)
  arg-values: {y: 2}  ← B inherits x from A, adds y
```

This enables:
- Partial application without copying values
- "Live" updates — changing A's values affects B
- Layered specialization — each fn adds its own values

#### fn-result-value (Named Intermediate Results)

```
fn: report
  sales:   ref<fn-result-value:FRV-1>  ← FRV-1 points to calculate-sales
  summary: ref<fn-result-value:FRV-1>  ← Same FRV-1, result computed once
```

This enables:
- Sharing expensive computations
- Consistent snapshots (same value for multiple consumers)
- Explicit caching (user controls what gets cached)

---

## Trade-offs and Constraints

### Accepted Complexity

#### Two Reference Types

We wanted a single edge type, but HOF require passing functions as values. The `>` suffix (fn-result-value) vs plain reference (fn) is the minimum distinction needed.

**Mitigation**: In visual UI, this can be shown as edge color or style, not requiring user to remember syntax.

#### Five Entity Types

More than ideal, but less than most languages. Each serves a distinct purpose:
- Removing any one would lose essential capability
- Adding more would need strong justification

### Performance Concerns

**Concern**: "Storing every function in a database will be slow."

**Response**:
1. Execution graph is loaded once, executed in memory
2. Caching provides O(1) graph resolution after first load
3. Database is storage, not runtime — similar to how IDEs index code

**Evidence**: Benchmarks needed, but architecture supports this claim.

### What We Don't Do

1. **Context-dependent semantics** — "if child has special type X and another child has field Y, then compute parent differently." This is complex and error-prone.

2. **Implicit behavior** — Everything should be explicit in the graph structure.

3. **Magic** — No hidden transformations or special cases.

---

## Component-to-Principle Mapping

This section maps each system component to the principles it serves. Use this to evaluate changes: modifying a component should improve its target principles without harming others.

### Core Entities (graph-data-schema)

| Entity | Principles Served | How |
|--------|-------------------|-----|
| `fn-schema` | Minimal entities, Expressiveness | Defines function signature — single concept for all functions |
| `arg-schema` | Minimal entities, Explicit | Arguments are explicit, typed, named |
| `fn` | DRY, Expressiveness | Inheritance enables reuse without duplication |
| `arg-value` | Explicit, Locality | Values are explicit; changing one doesn't affect siblings |
| `fn-result-value` | DRY, Performance | Share computed results; cache expensive operations |

### Storage Layer

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `storage-protocol` | Correctness, Minimal entities | Single interface for all backends; constraints enforced uniformly |
| `memory-storage` | Correctness (testing) | Fast tests enable comprehensive coverage |
| `postgres-storage` | Performance, Correctness | Production-grade ACID transactions |
| `datomic-storage` | Correctness, Dev tools | Immutable history enables versioning/audit |

### Caching Layer

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `cache-protocol` | Performance | O(1) graph resolution instead of O(depth) |
| `cache-memory` | Performance (dev) | Zero-latency cache for development |
| `cache-postgres` | Performance (prod) | Persistent cache survives restarts |
| `cache-datomic` | Performance + Dev tools | Cache with history for debugging |
| `cached-storage` | Performance, Correctness | Transparent caching; auto-invalidation prevents stale data |
| `cache-data-schema` | Minimal entities | Extends graph-data-schema without new concepts |

### Execution Layer

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `executor` | Correctness, Performance | Lazy evaluation; depth/timeout protection |
| `base-functions` | Expressiveness, Minimal entities | Rich library; no need for user to implement basics |
| `fn-registry` | DRY, Correctness | Single registration point; deterministic UUIDs |
| `fn-composition` | DRY, Explicit | Data-driven composition; no hidden behavior |

### Constraint System (GraphConstraints)

| Constraint | Principles Served | How |
|------------|-------------------|-----|
| No arg override | Correctness, Explicit | Prevents silent value shadowing |
| Same schema inheritance | Correctness | Type safety in inheritance chains |
| No dependency cycles | Correctness | Prevents infinite loops at write time |
| No inheritance cycles | Correctness | Prevents infinite parent traversal |
| Arg-schema belongs to fn-schema | Correctness | Type safety for argument binding |

### Protocol Design Decisions

| Decision | Principles Served | Trade-off |
|----------|-------------------|-----------|
| Two reference types (`ref<fn>` vs `ref<fn-result-value>`) | Expressiveness (HOF support) | +1 concept, but minimum for HOF |
| Union type for arg-value.value | Minimal entities | Single field instead of multiple tables |
| parent-fn-id (live inheritance) | DRY | Complexity in constraint validation |
| fn-result-value as separate entity | DRY, Performance | +1 entity, but enables caching and sharing |

### Bundles

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `graph-storage-*` | Dev simplicity | Pre-configured storage + schema |
| `graph-with-base-fns-*` | Dev simplicity | Complete stack in one call |

### Future Components (Planned)

| Component | Target Principles | Expected Trade-offs |
|-----------|-------------------|---------------------|
| Distributed execution | Performance | Complexity in coordination |
| Type system | Correctness, Dev tools | Complexity; may limit flexibility |
| Versioning | Dev tools, Correctness | Storage overhead; migration complexity |
| Permissions | Correctness | Query overhead; complexity |
| Visual UI | Dev simplicity | Large implementation effort |

### How to Use This Mapping

When proposing a change:

1. **Identify affected components** — which parts of the system change?
2. **Check target principles** — does the change improve them?
3. **Check other principles** — does it violate any?
4. **Evaluate trade-offs** — is the improvement worth the cost?

**Example evaluation**:

> **Proposal**: Add `priority` field to `fn` entity for execution ordering.
>
> - **Affects**: graph-data-schema, executor
> - **Improves**: Performance? (maybe, if we can optimize hot paths)
> - **Violates**: Minimal entities (+1 field), Explicit (implicit ordering)
> - **Verdict**: Reject — ordering should be explicit in graph structure, not metadata

---

## Open Questions

### Debugging and Observability

**Problem**: Classical languages have:
- Stack traces with line numbers
- Breakpoints
- Step-by-step debugging

**Question**: What's the graphden equivalent?

**Possible approaches**:
- Execution path (sequence of fn-ids traversed)
- Node highlighting in visual UI
- Time-travel debugging (replay execution with cached intermediate results)

### Intermediate Value Naming

**Problem**: In classical languages, `let` bindings name intermediate results for readability:

```clojure
(let [users (fetch-users)
      active (filter :active users)
      emails (map :email active)]
  (send-newsletter emails))
```

**Question**: How does graphden handle this?

**Current answer**: fn-result-value names results, but primarily for caching, not readability. Visual UI may need to support annotations or labels.

### Error Messages

**Problem**: Text-based errors reference line numbers.

**Question**: What do graphden errors reference?

**Current answer**: fn-id, arg-schema-id, execution path. Visual UI can highlight the relevant node. But textual representation needs thought.

### Schema Evolution

**Problem**: Changing fn-schema (adding/removing args) affects all fn instances.

**Question**: How to handle breaking changes?

**Possible approaches**:
- Versioning (planned)
- Migration tools
- Compatibility analysis before changes

---

## Summary of Principles

| # | Principle | Why |
|---|-----------|-----|
| 1 | **Correctness first** | No feature justifies bugs |
| 2 | **Minimal entities** | Each addition increases complexity everywhere |
| 3 | **Explicit over implicit** | Behavior should be visible in graph structure |
| 4 | **DRY** | Never define the same thing twice |
| 5 | **Expressiveness parity** | Can do everything classical languages can |
| 6 | **No unnecessary expressiveness** | Don't add features just because we can |
| 7 | **Locality of changes** | Changing one node shouldn't require changes elsewhere |
| 8 | **Incrementality** | Adding features shouldn't require rewriting existing ones |

### Evaluation Criterion

**Every change to the project must improve at least one principle without violating others.**

If a proposed change:
- Improves performance but adds entity types → reject or find alternative
- Improves expressiveness but makes behavior implicit → reject or find alternative
- Adds complexity without clear benefit → reject

This document serves as the philosophical foundation for design decisions.
