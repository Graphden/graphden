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
- **Primitives**: Nodes (fn, fn-schema, arg-schema, arg-value, call-site)
- **Combination**: Edges (references between nodes)
- **Abstraction**: Base functions (reusable implementations) and result caching (call-site)

**We resist adding new entity types or edge types.** Every addition increases cognitive load and implementation complexity.

#### 2.2 DRY (Don't Repeat Yourself)

Abstractions must minimize the need to define anything twice:
- call-site enables sharing computed results
- Base functions provide reusable implementations
- UI can offer "create based on" = copying with ability to change

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
| `fn` | Function instance (implements schema) |
| `arg-value` | Bound argument value (literal or reference) |
| `call-site` | Cached computation reference (memoization within execution) |

**Why these five?** They are the minimal set needed to express:
- Function definitions (fn-schema + arg-schema)
- Function instances with values (fn + arg-value)
- Result caching / sharing (call-site)

### Means of Combination

Two types of references in arg-value:

| Reference Type | Syntax (in fn-defs) | Behavior |
|---------------|---------------------|----------|
| `ref<fn>` | `:fn-name` | Pass fn-id as value (for HOF) |
| `ref<call-site>` | `:fn-name>` | Execute and use result |

**Why two types?** Higher-order functions (map, filter, reduce) need to receive functions as values, not their results. This is the minimum necessary distinction.

**We considered alternatives**:
- Inferring from arg-schema type — possible but makes behavior implicit
- Single reference type with explicit "execute" node — more entities

### Means of Abstraction

#### call-site (Named Intermediate Results)

```
fn: report
  sales:   ref<call-site:FRV-1>  ← FRV-1 points to calculate-sales
  summary: ref<call-site:FRV-1>  ← Same FRV-1, result computed once
```

This enables:
- Sharing expensive computations
- Consistent snapshots (same value for multiple consumers)
- Explicit caching (user controls what gets cached)

---

## Trade-offs and Constraints

### Accepted Complexity

#### Two Reference Types

We wanted a single edge type, but HOF require passing functions as values. The `>` suffix (call-site) vs plain reference (fn) is the minimum distinction needed.

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
| `call-site` | DRY, Performance | Share computed results; cache expensive operations |

### Storage Layer

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `storage-protocol` | Correctness, Minimal entities | Generic CRUD interface for all backends; schema-agnostic |
| `memory-storage` | Correctness (testing) | Fast tests enable comprehensive coverage |
| `postgres-storage` | Performance, Correctness | Production-grade ACID transactions |
| `datomic-storage` | Correctness, Dev tools | Immutable history enables versioning/audit |

### Graph Layer

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `graph-protocol` | Correctness, Modularity | Graph-specific protocols: GraphReader, GraphConstraints |
| `graph-data-schema` | Minimal entities | Core graph entities: fn, fn-schema, arg-schema, arg-value |
| `graph-storage-*` | Dev simplicity | Pre-configured storage + graph schema bundles |

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
| No dependency cycles | Correctness | Prevents infinite loops at write time |
| Arg-schema belongs to fn-schema | Correctness | Type safety for argument binding |

### Protocol Design Decisions

| Decision | Principles Served | Trade-off |
|----------|-------------------|-----------|
| Two reference types (`ref<fn>` vs `ref<call-site>`) | Expressiveness (HOF support) | +1 concept, but minimum for HOF |
| Union type for arg-value.value | Minimal entities | Single field instead of multiple tables |
| call-site as separate entity | DRY, Performance | +1 entity, but enables caching and sharing |

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

## Role Chain and Extensibility

### The Developer Chain

In traditional software, the chain from code to end user is short: a developer writes code, users use the product. But in practice, there are always intermediate layers with varying levels of technical expertise:

| Role | Technical Depth | Domain Knowledge | Examples |
|------|----------------|-------------------|----------|
| **Platform developer** | Deep (writes Clojure, base-fn) | Low | Core team |
| **System integrator** | Medium (composes graphs, configures) | Medium | DevOps, architects |
| **Domain builder** | Low (uses pre-built blocks) | High | Forum admin creating categories, CMS editor |
| **End user** | None | Full (their own domain) | Forum user, app consumer |

Even a classic forum has this: developers write the engine, admins create sections and configure rules, users post content. Modern no-code/low-code platforms extend this chain further.

**Graphden should support this entire chain.** Through access levels to functions and graph operations, each role sees only what they need:

- Platform developer: writes base-fn in Clojure, defines fn-schema
- System integrator: composes base-fn into graphs, configures storage and infrastructure
- Domain builder: creates domain-specific functions from existing compositions, configures routing
- End user: invokes functions through UI, provides runtime arguments

The boundaries between roles are enforced by the permission system, not by technical barriers. A domain builder doesn't need to know Clojure — they work with the same graph primitives, just at a higher level.

### Extension Modularity Problem

The core system is simple: base-fn implementations + graph composition (fn, arg-value, call-site) + executor. But production features — versioning, caching, logging, permissions, environment management, secret storage — all require modifications to either storage (new fields, tables, query logic) or executor (new resolution steps, middleware).

Hardwiring these features into the core has problems:
- Forces all users to use them, even when unnecessary
- Makes modifications difficult without deep knowledge of internals and Clojure
- Couples unrelated concerns

### Three Approaches to Extensions

| Approach | Description | When to Use |
|----------|-------------|-------------|
| **1. Hardwired** | Extension is part of core storage/executor code | Security-critical, performance-critical, or fundamentally inseparable from core |
| **2. Component** | Separate module wrapping core with additional behavior | Most production features (versioning, caching, permissions) |
| **3. Graph-native** | Extension described in the same graph language as user programs | Logging, monitoring, domain-specific middleware |

**Current assessment of planned features:**

| Feature | Recommended Approach | Reasoning |
|---------|---------------------|-----------|
| Core executor loop | 1 (hardwired) | Bootstrapping problem — cannot execute graph without executor |
| Core storage CRUD | 1 (hardwired) | Same — cannot store graph without storage |
| Versioning | 2 (component) | Storage wrapper: VersionedStorage(BaseStorage) |
| Caching | 2 (component) | Already implemented as CachedStorage wrapper |
| Permissions (enforcement) | 2 (component) | Security-critical — must not be bypassable by user graph |
| Permission policies | 2-3 (data/graph) | Policy rules can be declarative data or graph predicates |
| Secret storage | 2 (component) | Infrastructure concern, not expressible in pure graph |
| Environment management | 2 (component) | Branch-level metadata, storage concern |
| Logging | 3 (graph-native) | `with-logging` base-fn wrapper, user controls placement |
| Domain middleware | 3 (graph-native) | User-defined request/response processing |

### Storage Modularity Vision

Current storage implementations must satisfy a long protocol interface. The goal is to reduce this to a minimal set of generic operations:

```
Minimal Storage:
  put(entity-type, id, data)
  get(entity-type, id)
  query(entity-type, predicates)
  delete(entity-type, id)
  apply-migration(migration-spec)
```

Extensions declare their needs through migrations (new fields, indexes, tables) rather than requiring protocol changes. Each storage backend translates migration specs into its own DDL. This allows adding versioning, caching, or permissions without modifying the base storage interface.

**Independent composability requirement**: Each feature module (caching, versioning, permissions, etc.) must be an independent component implementing the storage decorator pattern. Any combination of decorators must work correctly:

```
BaseStorage (minimal CRUD)

Valid compositions (any combination):
  BaseStorage                                          — no extras
  CachedStorage(BaseStorage)                           — cache only
  VersionedStorage(BaseStorage)                        — versioning only
  CachedVersionedStorage(VersionedStorage(BaseStorage)) — both
  PermissionStorage(CachedStorage(BaseStorage))        — permissions + cache
  ... any other combination
```

The executor works with any storage through the unified `ExecutionGraph` protocol and does not need to know which decorators are active. Each decorator is transparent to layers above it.

When two features interact (e.g., cache invalidation on branch merge), a dedicated combining module handles the interaction rather than coupling the features directly.

### Three-Layer Architecture

The system separates concerns into three distinct layers:

```
┌─────────────────────────────────────────────────────────────────┐
│                         EXECUTOR                                 │
│  - Receives fn-id + call-site-args                              │
│  - Knows about laziness (delay, thunks)                         │
│  - Resolves base-fn implementations                             │
│  - Does NOT know about storage details                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ uses GraphReader protocol
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       GRAPH LAYER                                │
│  - Provides execution graph for a fn-id                         │
│  - Knows about graph entities (fn, fn-schema, arg-value, etc.)  │
│  - Middleware pattern: composable GraphReader implementations   │
│    • DirectGraphReader(storage) — direct queries                │
│    • CachedGraphReader(storage, cache) — DB-level caching       │
│    • VersionedGraphReader(storage) — branch version resolution  │
│  - Does NOT know about execution semantics                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ uses StorageCRUD protocol
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      STORAGE LAYER                               │
│  - Generic CRUD: create/read/update/delete/query                │
│  - Schema-agnostic (works with any entity types)                │
│  - Storage decorators modify CRUD behavior:                     │
│    • VersionedStorage — intercepts CRUD for version resolution  │
│    • PermissionStorage — enforces access control                │
│  - Does NOT know about graph semantics                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ uses DataSchema protocol
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     DATA SCHEMA LAYER                            │
│  - Defines entity types, fields, constraints                    │
│  - Schema extensions are composable:                            │
│    • graph-data-schema — fn, fn-schema, arg-value, etc.        │
│    • cache-data-schema — cached-fn, cached-merged-arg          │
│    • versioned-data-schema — branch, fn-version, etc.          │
│  - Pure data definitions, no behavior                           │
└─────────────────────────────────────────────────────────────────┘
```

**Key principle**: Each layer depends only on the layer below it. The executor doesn't know if storage is versioned or cached — it only sees `GraphReader`. Storage doesn't know about execution — it only sees CRUD operations.

**Graph-specific protocols in the right place**: The `GraphConstraints` and `ExecutionGraph` protocols belong in the Graph Layer, not Storage Layer:

| Protocol | Layer | Reason |
|----------|-------|--------|
| `StorageCRUD` | Storage | Generic CRUD, schema-agnostic |
| `GraphConstraints` | Graph | Knows about fn→arg-value→fn relationships |
| `ExecutionGraph` | Graph | Resolves graph structure for execution |
| `GraphReader` | Graph | Provides graph data to executor |

**GraphReader middleware pattern** enables composition:

```clojure
;; Direct graph reading (simplest)
(def reader (direct-graph-reader storage))

;; With DB-level caching
(def reader (cached-graph-reader storage cache-storage))

;; With versioning (branch resolution)
(def reader (versioned-graph-reader versioned-storage))

;; With both (cached + versioned)
(def reader (cached-versioned-graph-reader versioned-storage cache-storage))
```

The executor creates context with any GraphReader:
```clojure
(executor/create-context {:graph-reader reader
                          :base-fns (registry/get-base-fns)})
```

This design follows Django's "apps" pattern where each feature is an independent module that can:
1. Extend the schema (add entities/fields)
2. Modify CRUD behavior (storage decorators)
3. Modify graph reading behavior (GraphReader implementations)
4. Be combined with other features in any order

### Self-Describing System (Long-term Vision)

The ultimate goal: the system's own infrastructure is described in the same graph language it provides to users. Initialization graphs define storage setup, migrations, executor configuration. Extension graphs add fields, modify behavior. UI is a graph of components (e.g., HTMX + templating).

This is analogous to Lisp's self-hosting capability: the language is expressive enough to describe its own extensions. The practical limit is the bootstrapping boundary — a minimal Clojure core (executor + base CRUD) that cannot itself be a graph, because it's needed to execute graphs.

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

**Current answer**: call-site names results, but primarily for caching, not readability. Visual UI may need to support annotations or labels.

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
