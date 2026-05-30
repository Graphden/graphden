# Graphden Philosophy

> This document describes the core principles and philosophy behind graphden.
> For technical architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).
> For packages system, see [PACKAGES.md](PACKAGES.md).
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
- **Primitives**: Five entity types — fn, slot, fn-slot, binding, binding-list-item (see [§ Language Aspects](#language-aspects-sicp) for the full breakdown)
- **Combination**: Parent-fn-ids inheritance + binding overlays (`value` / `ref-fn-id`); `binding-list-item.position` for ordered chains
- **Abstraction**: Base functions (Clojure implementations) and composed functions (`parent-ids` inheritance + bindings)

**We resist adding new entity types or edge types.** Every addition increases cognitive load and implementation complexity.

#### 2.2 DRY (Don't Repeat Yourself)

Abstractions must minimize the need to define anything twice:
- Result caching (by `ref-fn-id` within execution) enables sharing computed results
- Composed functions (via `parent-ids` + bindings) enable reuse without duplication
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
| Version control | Git (text diffs) | Graph versioning via `VersionedStorage` decorator — branches, fork-point conflict detection, merge |
| Testing | Unit test frameworks | Isolated node execution with mocked dependencies |
| Linting | Static analysis | Graph structure constraints (already enforced) |
| IDE features | Indexing, navigation | Database queries (inherent) |
| Debugging | Stack traces | Execution path traces (see [Open Questions](#debugging-and-observability)) |

---

## Language Aspects (SICP)

### Primitives

Five entity types, with deliberate roles:

| Entity | Purpose |
|--------|---------|
| `fn` | Function entity OR type-row. Inheritance via `parent-ids` (M:N). |
| `slot` | Atomic `(name, type-fn-id)` pair. Immutable post-create; shared across fns. |
| `fn-slot` | Junction: which slots a fn exposes, with `position`. |
| `binding` | Per-`(fn, slot)` overlay (value, ref, rename, type-override, terminal, list flags). |
| `binding-list-item` | Sequence content under a list-typed binding. |

Slots are first-class because two fns can carry the **same slot
identity**, which is how multiple-inheritance recognises that "two
parents expose the same parameter" rather than colliding on names.

### Means of Combination

References point at fns via `binding.ref-fn-id` (or
`binding-list-item.ref-fn-id` for sequence items). The executor
dispatches based on the slot's effective type:

| Resolved slot type | Behavior |
|---|---|
| `:fn` | Pass `fn-id` directly — for HOF callables |
| anything else | Execute the ref, use its result (cached per `ref-fn-id`) |

The legacy `:is-fn` boolean flag on args was retired — `slot.type-fn-id`
(overlaid by `binding.type-override-fn-id`) IS the HOF marker. One
affordance, one concept.

### Base Functions Philosophy

**Base functions are minimal primitives that wrap Clojure/Java capabilities.**

They should be:
- **Atomic** — a single operation that cannot be expressed as composition of other base-fns
- **Generic** — no hardcoded business logic, values, or domain specifics
- **Small** — typically 1-10 lines of implementation code
- **Library wrappers** — thin layers over Clojure core, Java interop, or external libraries

**Examples of GOOD base functions:**

| Name | Purpose | Why it's a base-fn |
|------|---------|-------------------|
| `add` | `(apply + nums)` | Wraps Clojure `+` |
| `http-server` | Start http-kit server | Wraps http-kit library |
| `render-hiccup` | Convert hiccup to HTML | Wraps hiccup library |
| `router` | Create Ring router | Wraps reitit library |
| `query-entities` | Query storage | Access to storage protocol |
| `env` | Get environment variable | Access to system |

**Examples of BAD base functions (anti-patterns):**

| Name | Problem | What it should be |
|------|---------|-------------------|
| `graph-editor-server` | 250+ lines, hardcoded routes, HTML, CSS, JS | Multiple fn-defs composing `router`, `http-server`, `render-hiccup` |
| `user-login-handler` | Hardcoded auth logic | fn-def composing `query-entities`, `if`, `json-response` |
| `dashboard-page` | Hardcoded page structure | fn-def composing `html-page`, `with-htmx`, etc. |

**Rule of thumb:** If a base function contains:
- Hardcoded strings (except library defaults)
- Hardcoded HTML/CSS/JS
- Hardcoded routes
- Hardcoded business logic
- More than ~20 lines of code

...it should be a **fn-def** (graph composition), not a base-fn.

**The graph editor UI is built entirely as fn-defs in `resources/packages/app/`:**

```edn
;; In app/common/fns.edn — reusable route building blocks
{:name :json-ok-response
 :parent :ok-response
 :args {:headers {"Content-Type" "application/json"}}}

{:name :get-route
 :parent :route
 :args {:k "get"}}

;; In app/editor/fns.edn — editor UI composition
{:name :editor-page
 :parent :html-page
 :args {:title "Graphden - Graph Editor"
        :head :editor-head
        :body :editor-body
        :scripts :editor-scripts}}

;; In app/server/fns.edn — server composition
{:name :health-route
 :parent :get-route
 :args {:a "/health" :v :health-handler-fn}}

{:name :web-server
 :parent :http-server
 :args {:handler :router-fn
        :port 8080}}
```

This approach:
- Makes UI structure visible in the graph
- Allows modification without Clojure knowledge
- Follows DRY (shared components like `:json-ok-response` are reused)
- Enables visual editing of the UI structure
- Separates base primitives (packages/core, web) from application composition (packages/app)

### Means of Abstraction

#### Result Caching (via ref-fn-id)

```
fn report
  binding {slot s-sales,    ref-fn-id calculate-sales}  ← executes calculate-sales
  binding {slot s-snapshot, ref-fn-id calculate-sales}  ← same ref-fn-id, result cached
```

This enables:
- Sharing expensive computations (same `ref-fn-id` = computed once)
- Consistent snapshots (same value for multiple consumers)
- Automatic caching within execution context

---

## Trade-offs and Constraints

### Accepted Complexity

#### Slot type drives HOF dispatch

We wanted a single reference behavior, but HOFs need to receive
**functions as values**. Rather than a side-channel flag, a slot
typed `:fn` IS the HOF marker — the editor shows it through the
type chip, the executor dispatches on it. One affordance for one
concept.

#### Five entity types

The minimum needed for the model we want:
- `fn` carries inheritance + type-row metadata
- `slot` and `fn-slot` separate "what is this parameter" from
  "which fns expose it" — sharing slot identity is what makes MI
  inheritance picky about identity vs. name
- `binding` overlays per-fn customisation without forking the slot
- `binding-list-item` keeps sequence content indexable so
  reverse-queries ("which fns ref X via list?") stay cheap

Each entity earns its row. Removing any would either lose
expressiveness (no slot sharing → no MI), or push semantics into
ad-hoc fields on `fn` (binding overlays inside the fn entity →
versioning per-fn instead of per-binding, lost dedup).

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
| `fn` | Minimal entities, Expressiveness, DRY | Function or type-row; M:N inheritance via `parent-ids` |
| `slot` | Minimal entities, Explicit | Atomic identity for a parameter; sharing enables MI |
| `fn-slot` | Locality, Explicit | Which fn exposes which slot in what order |
| `binding` | Locality, Explicit | Per-`(fn, slot)` overlay; closer-fn-wins makes inheritance lookups O(chain length) |
| `binding-list-item` | Indexability | Sequence content as rows so reverse-queries stay cheap |

### Storage Layer

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `storage-protocol` | Correctness, Minimal entities | Generic CRUD interface for all backends; schema-agnostic |
| `postgres-storage` | Performance, Correctness | Production-grade ACID transactions; recursive CTE for cycle walks |

### Graph Layer

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `storage/protocol/graph` | Correctness, Modularity | `ExecutionGraphResult` record + accessor functions |
| `graph-data-schema` | Minimal entities | The five core entities (fn, slot, fn-slot, binding, binding-list-item) |
| `versioning/storage` | Dev simplicity | Versioned-decorator wrapping any base storage |

### Execution Layer

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `executor` | Correctness, Performance | Lazy evaluation; depth/timeout protection |
| `base-functions` | Expressiveness, Minimal entities | Rich library; no need for user to implement basics |
| `fn-registry` | DRY, Correctness | Single registration point; deterministic UUIDs |
| `fn-composition` | DRY, Explicit | Data-driven composition; no hidden behavior |

### Constraint System

| Constraint | Principles Served | How |
|------------|-------------------|-----|
| No dependency cycles | Correctness | Prevents infinite loops at write time (binding.ref-fn-id graph) |
| Schema `UNIQUE` keys | Correctness | fn.name, fn-slot, binding, binding-list-item identity |

### Protocol Design Decisions

| Decision | Principles Served | Trade-off |
|----------|-------------------|-----------|
| Slot type IS the HOF marker | Expressiveness, Explicit | One concept (`type=:fn`) instead of two (type + flag) |
| Union value/ref-fn-id on binding | Minimal entities | Mutual-exclusion checked at write |
| Result caching by ref-fn-id | DRY, Performance | Cache lives in executor, not schema |
| Slots shared across fns by id | DRY, MI correctness | Sharing is opt-in via fn-slot pointing at same slot-id |
| Bindings overlay per (fn, slot) | Locality | One row per customisation; no fork-on-rename |

### Bundles

| Component | Principles Served | How |
|-----------|-------------------|-----|
| `system/*` | Dev simplicity | Integrant lifecycle wires storage + executor + base-fn registry + http server |
| `packages/*` | Dev simplicity | One directory per package; loader auto-syncs at startup |

### Future Components

| Component | Status | Target Principles | Expected Trade-offs |
|-----------|--------|-------------------|---------------------|
| Distributed execution | Planned | Performance | Complexity in coordination |
| Type system | Done (refinements / records / lists / unions / variants) | Correctness, Dev tools | Save-time check + rich-type registry |
| Versioning | Done (`VersionedStorage` decorator) | Dev tools, Correctness | Storage overhead; migration complexity |
| Permissions | Planned | Correctness | Query overhead; complexity |
| Visual UI | Done (Cytoscape-based editor) | Dev simplicity | Large implementation effort |

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

- Platform developer: writes base-fn in Clojure, defines `fn` entity with `impl-hash`
- System integrator: composes base-fns into graphs (fn-defs + bindings), configures storage and infrastructure
- Domain builder: creates domain-specific fns from existing compositions, configures routing
- End user: invokes functions through UI, provides runtime arguments

The boundaries between roles are enforced by the permission system, not by technical barriers. A domain builder doesn't need to know Clojure — they work with the same graph primitives, just at a higher level.

### Extension Modularity Problem

The core system is simple: base-fn implementations + graph composition (fn, slot, fn-slot, binding, binding-list-item) + executor. But production features — versioning, caching, logging, permissions, environment management, secret storage — all require modifications to either storage (new fields, tables, query logic) or executor (new resolution steps, middleware).

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
│  - Receives fn-id, executes function graph                     │
│  - Knows about laziness (Clojure delays)                        │
│  - Resolves base-fn implementations                             │
│  - Does NOT know about storage details                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ uses ExecutionGraph + StorageCRUD protocols
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       GRAPH LAYER                                │
│  - Provides execution graph for a fn-id                         │
│  - Knows about graph entities (fn, slot, fn-slot, binding,      │
│    binding-list-item)                                           │
│  - Middleware pattern: composable storage decorators            │
│    • Base storage — direct queries                              │
│    • CachedStorage(base) — DB-level caching                     │
│    • VersionedStorage(base) — branch version resolution         │
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
│    • graph-data-schema — fn / slot / fn-slot / binding /        │
│      binding-list-item                                          │
│    • versioned-data-schema — branch + version table per         │
│      versioned entity                                           │
│  - Pure data definitions, no behavior                           │
└─────────────────────────────────────────────────────────────────┘
```

**Key principle**: Each layer depends only on the layer below it. The executor doesn't know if storage is versioned or cached — it only sees `ExecutionGraph` + `StorageCRUD`. Storage doesn't know about execution — it only sees CRUD operations.

**Graph-specific protocols in the right place**: The `GraphConstraints` and `ExecutionGraph` protocols belong in the Graph Layer, not Storage Layer:

| Protocol | Layer | Reason |
|----------|-------|--------|
| `StorageCRUD` | Storage | Generic CRUD, schema-agnostic |
| `GraphConstraints` | Graph | Knows about fn → binding.ref-fn-id → fn relationships |
| `ExecutionGraph` | Graph | Resolves graph structure for execution |

**Storage decorator pattern** enables composition:

```clojure
;; Direct storage (simplest)
(def storage (pg/create-storage cfg))

;; With versioning (branch resolution)
(def storage (vs/wrap-with-versioning (pg/create-storage cfg)))

;; Caching layer (planned)
(def storage (cs/wrap-with-cache (vs/wrap-with-versioning base) cache))
```

The executor takes any storage through the unified protocols:
```clojure
(executor/create-context {:storage storage
                          :base-fns (registry/get-base-fns)})
```

This design follows Django's "apps" pattern where each feature is an independent module that can:
1. Extend the schema (add entities/fields)
2. Modify CRUD behavior (storage decorators)
3. Modify graph-resolution behavior (`ExecutionGraph` implementations)
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

**Current answer**: Result caching by ref-id provides sharing, but primarily for efficiency, not readability. Visual UI may need to support annotations or labels.

### Error Messages

**Problem**: Text-based errors reference line numbers.

**Question**: What do graphden errors reference?

**Current answer**: fn-id, arg-id, execution path. Visual UI can highlight the relevant node. But textual representation needs thought.

### Schema Evolution

**Problem**: Changing base-fn args affects all composed fns that inherit from it.

**Question**: How to handle breaking changes?

**Possible approaches**:
- Versioning (implemented via `VersionedStorage` decorator + branch fork-point conflict detection)
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

---

## Base Function Refactoring Principles

A base-fn impl should ideally be **1-2 lines**: call the library, return the result.
Everything else — composition, defaults, multi-step processing — belongs in fn-defs.

**The fundamental rule: a base-fn MUST NOT call another base-fn.** If it does, the composition is hidden in code instead of being visible in the graph. Fix: compose via fn-def, or make shared logic a private helper (not a registered base-fn).

Acceptable exceptions for longer impls: input validation/size limits (safely wrapping the library) and library adapter boilerplate (e.g., Ring handler format).

### 1. Consolidate Duplicates

If two base-fns do the same thing with different type signatures, keep one with `:any` types.

**Examples:**
- `assoc` + `assoc-any` → single `:assoc` with `:any` args
- `conj` + `conj-any` → single `:conj`
- `identity` → fn-def of `:const` (same implementation)
- `wrap-style` + `wrap-script` → fn-defs of `:wrap-element` (parameterized by `:tag`)
- `with-htmx` + `with-cytoscape` → single `:with-cdn-script` (parameterized by `:url`)

### 2. Extract Reusable Primitives

When the same logic appears in multiple base-fns, extract it as a standalone base-fn.

**Examples:**
- `parse-query-string` — duplicated in 3 places in crud
- `parse-json` — inline in layout and crud handlers
- `stringify-map-keys` — inline closure in http-server
- `format-display-value` — nested cond in graph `arg->node`

### 3. No Hardcoded Defaults in Impls

Base-fns must not contain hardcoded configuration values. Defaults belong in fn-defs (via arg bindings), not in implementation code. The impl should use the arg directly without `(or arg default)`.

**Examples:**
- Security headers: from hardcoded map in `http-server` → fn-def arg `:default-headers`
- Error responses: from hardcoded maps in `router` → fn-defs via MI (`text-error-router`)
- Default styles: from `cytoscape-container` → explicit required arg `:style`
- Optional args with defaults: declared in fns.edn with default value, not `(or arg val)` in impl

### 4. Content → Fn-defs, Not Impls

HTML, SVG, CSS, hardcoded strings belong in fn-defs (`:parent :const`), not in implementation code. Impl stays a pure library wrapper.

**Examples:**
- `editor-body` → `:const` fn-def with hiccup structure
- `favicon-svg-body` → `:const` fn-def
- CDN URLs → fn-def args (not constants in impl)
- `dagre-script`, `cytoscape-dagre-script` → `:const` fn-defs

### 5. Private Fn-def Naming: `_` Prefix

Intermediate fn-defs that have no standalone semantic meaning use `_` prefix. This signals they exist only as wiring, not as reusable abstractions.

**Examples:**
- `health-handler-fn` → `_health-handler`
- `editor-response` → `_editor-response`
- `favicon-svg-body` → `_favicon-svg-body`

### 6. Rename Args for Domain Clarity

Generic arg names (`key`, `value`) should be renamed via `:as` to reflect domain semantics at the point of use.

**Examples:**
- `method-map.key` → `:method` (HTTP method)
- `method-map.value` → `:handler` (request handler)
- `assoc-status.value` → `:status` (status string)
- `assoc-timestamp.value` → `:timestamp` (timestamp value)
