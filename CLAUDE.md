# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Design Principles (MUST READ)

**Every change must improve at least one principle without violating others.**

| # | Principle | Description |
|---|-----------|-------------|
| 1 | **Correctness first** | No feature justifies bugs. Comprehensive tests required. |
| 2 | **Minimal entities** | Resist adding new entity types, fields, or edge types. Each addition increases complexity everywhere. |
| 3 | **Explicit over implicit** | Behavior must be visible in graph structure. No magic, no context-dependent semantics. |
| 4 | **DRY** | Never define the same thing twice. Use base-functions and fn-usage for reuse. |
| 5 | **Expressiveness parity** | Can do everything classical languages can. No "sorry, you can't do that." |
| 6 | **No unnecessary expressiveness** | Don't add features just because we can. |
| 7 | **Locality of changes** | Changing one node shouldn't require changes elsewhere. |
| 8 | **Incrementality** | Adding features shouldn't require rewriting existing ones. |

**Before making changes, ask:**
- Which principle does this improve?
- Does it violate any other principle?
- Is there a simpler way?

See [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) for full rationale and module mapping.

## Project Overview

Graphden is a visual functional programming environment where functions and their compositions are stored as a graph in a database.

**Key concepts:**
- **Code = Graph in DB** — functions, schemas, and argument values stored as entities
- **Lazy Execution** — delay-based evaluation, only computes what's needed
- **Storage backend** — PostgreSQL with recursive CTE for graph traversal

**Core entities** (only 6 — kept minimal by design):
- `fn-schema` — function signature (name, return type, optional base-fn-name linking to Clojure impl)
- `arg-schema` — argument definition (belongs to fn-schema, includes first-class flag for HOF support)
- `fn` — function instance (references fn-schema, optional owner-fn-id for local scoping)
- `arg-value` — bound argument value (literal or reference to fn/fn-usage)
- `fn-usage` — function usage reference (distinguishes multiple uses of same function)
- `fn-arg` — binding that connects fn to arg-schema and arg-value

## Core Concept: fn-usage

**fn-usage is NOT primarily about caching. Its main purpose is structural:**

It distinguishes between the same function called at different points in the execution graph.

**Example:** Get current time, sleep 5 seconds, get current time again, print both.
```
fn: current-time (base function)
fn: sleep (base function)
fn: print-two-times (base function with args: t1, t2)

;; These are TWO DIFFERENT fn-usages pointing to the SAME fn
fn-usage: time-before  → fn: current-time
fn-usage: time-after   → fn: current-time

fn: my-program
  arg: t1 = ref<fn-usage:time-before>   ;; first call
  arg: wait = ref<fn-usage:sleep-5s>
  arg: t2 = ref<fn-usage:time-after>    ;; second call (different!)
```

Without fn-usage, we couldn't distinguish "time before sleep" from "time after sleep" — they'd be the same function reference.

**Local argument binding via fn with owner-fn-id:**
```clojure
;; To provide different argument values at different call sites,
;; create a local fn with owner-fn-id:

;; Local fn owned by parent function
(sp/create-entity storage :fn
  {:name "local-add"
   :fn-schema-id add-schema-id
   :owner-fn-id parent-fn-id})  ;; Makes this fn local to parent

;; Bind arguments for the local fn
(sp/create-entity storage :fn-arg
  {:fn-id local-fn-id
   :arg-schema-id a-schema-id
   :arg-value-id (create-literal-value 42)})
```

**Key insight:** All argument binding happens in the database via fn-arg entities. No runtime argument injection needed.

## Documentation Map

| Document | Purpose | When to read |
|----------|---------|--------------|
| [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) | Design principles, rationale, module mapping | Before making architectural decisions |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Technical details, execution model, examples | When implementing features |
| [docs/CONSTRAINTS.md](docs/CONSTRAINTS.md) | Graph constraint specifications | When working with GraphConstraints |
| [docs/ERROR_CODES.md](docs/ERROR_CODES.md) | Error types reference | When handling errors |
| [docs/EXTENDING.md](docs/EXTENDING.md) | Adding new storage backends | When implementing new backend |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Implementation status, future plans | For project planning |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Integrant config, Aero tags | When configuring the system |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Docker, uberjar, environment | When deploying to production |

## Common Commands

```bash
bb repl         # Start REPL with dev profile
bb ci           # Full CI: linters + tests + coverage (parallel, live progress)
bb test         # Run all tests
bb coverage     # Tests with coverage report (open target/coverage/index.html)
bb check        # Linters only (clj-kondo, splint, cljstyle in parallel)
bb fix          # Auto-fix formatting

# Build & Run
clojure -T:build uber    # Build uberjar (target/executor-server.jar)
clojure -M:run           # Run server
docker-compose up        # Run with PostgreSQL
```

### Running a Single Test

```bash
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test/execute-test
```

## Architecture Overview

Classical Clojure monorepo. Top namespace: `graphden`. Public API through `interface.clj` only.

### Three-Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      EXECUTOR LAYER                          │
│  executor + base-functions + fn-registry                    │
│  Uses ExecutionGraph protocol (sp/resolve-execution-graph)  │
├─────────────────────────────────────────────────────────────┤
│                      STORAGE LAYER                           │
│  storage-protocol: StorageCRUD, ExecutionGraph, Constraints │
│  Decorators: VersionedStorage (composable)                  │
│  Backends: postgres-storage                                 │
├─────────────────────────────────────────────────────────────┤
│                    DATA SCHEMA LAYER                         │
│  data-schema-protocol + malli-data-schema + field-types     │
│  Schema extensions: versioned-data-schema, graph-data-schema│
└─────────────────────────────────────────────────────────────┘
```

**Key principle:** Each layer depends only on the layer below it. Executor calls `sp/resolve-execution-graph` which returns `ExecutionGraphResult`. Storage implementations implement this protocol using recursive CTEs for optimal graph traversal.

See [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) for full architecture rationale.

## Quick Reference

### Fn-def Syntax

```clojure
{:name :my-fn           ; unique function name
 :parent :base-fn-name  ; base function to use
 :args {:arg1 value     ; literal value
        :arg2 :other-fn>}}  ; reference (> = execute)
```

### Reference Types

| Syntax | Meaning | Use case |
|--------|---------|----------|
| `:fn-name` | Pass fn-id without executing | HOF (map, filter, reduce) |
| `:fn-name>` | Execute fn and use result | When you need computed value |

### Base Function Arg Types

| Type | Behavior |
|------|----------|
| `:fn` | Expects fn-id, auto-wrapped for HOF |
| `:any` | No processing (use for already-executed Clojure fns) |
| Others (`:int`, `:text`, `:jsonb`) | Auto-deref from delay |

For complete examples, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) Part 5.5.

### Transducers and Lazy Sequences

HOFs like `map`, `filter` support two modes via optional `coll` argument:
- **With coll**: `(map f coll)` — returns lazy sequence of results
- **Without coll**: `(map f)` — returns transducer

**Key functions for composition:**
- `comp` — composes functions/transducers
- `transduce` — applies transducer with reducing function in single pass
- `call` — invokes function with argument

**Example pipeline:**
```clojure
;; Efficient single-pass transformation
(transduce (comp (filter pred) (map transform)) + 0 coll)
```

### fn vs Runtime Function (CRITICAL)

**Understand the two levels of "function" in graphden:**

| Level | What it is | Where it lives | Examples |
|-------|------------|----------------|----------|
| **fn (graph)** | Composition description | Database | fn-schema, arg bindings |
| **Runtime fn** | Actual Clojure object | Executor memory | transducers, composed fns |

**Key insight:** The graph in DB is just a *description* of how functions compose. Actual Clojure functions (like transducers, composed functions) exist only at runtime in executor memory.

**No special-casing:** The executor must remain generic. Never add special handling for specific function names (anti-pattern). All functions execute uniformly through the same code path.

**Multi-arity for behavior:** Use optional arguments to control behavior (e.g., `coll` in `map`/`filter`). Clojure's multi-arity functions handle this idiomatically.

**Runtime objects can't be stored:** Functions returned by `comp`, transducers, etc. are Clojure objects — they exist only during execution. The graph stores *how to create them*, not the objects themselves.

### Base Function Philosophy (CRITICAL)

**Base functions MUST be minimal primitives wrapping Clojure/Java/library capabilities.**

✅ **GOOD base-fns** (atomic, generic, small):
- `add`, `sub`, `mul` — wrap Clojure arithmetic
- `http-server` — wrap http-kit `run-server`
- `render-hiccup` — wrap hiccup2 `html`
- `router` — wrap reitit router creation
- `query-entities` — wrap storage protocol
- `env` — get environment variable

❌ **BAD base-fns** (anti-patterns to avoid):
- Hardcoded HTML/CSS/JS content
- Hardcoded routes or handlers
- Hardcoded business logic
- More than ~20 lines of code
- Anything that could be composed from other base-fns

**Rule:** If it contains hardcoded strings (except library defaults), HTML, routes, or logic — it should be a **fn-def**, not a base-fn.

**Example: Graph Editor should be fn-defs:**
```clojure
;; CORRECT approach
{:name :editor-styles, :parent :const, :args {:x "CSS here..."}}
{:name :editor-body, :parent :const, :args {:x [:div ...]}}
{:name :editor-page, :parent :html-page, :args {:title "Editor" :body :editor-body>}}
{:name :editor-router, :parent :router, :args {:routes [...]}}
{:name :web-server, :parent :http-server, :args {:handler :editor-router> :port 8080}}
```

See [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) "Base Functions Philosophy" for full details.

### Base Function impl-hash

Each base function has an `impl-hash` stored in `fn-schema` for version tracking:
- SHA-256 hash of canonical form (args, return-type, impl-source)
- Detects: body changes, arg changes, return-type changes
- Ignores: whitespace, comments, map key ordering

See [docs/EXTENDING.md](docs/EXTENDING.md) for details.

## Graph Constraints

Enforced at write time by `GraphConstraints` protocol (part of Graph Layer):

1. **No dependency cycles** — A→B→A forbidden (self-recursion allowed with depth limit)
2. **Arg-schema belongs to fn-schema** — type safety for argument binding

These constraints are implemented in `storage-protocol` and enforced by storage implementations.

See [docs/CONSTRAINTS.md](docs/CONSTRAINTS.md) for detailed specifications.

## Code Conventions

- Public API through `interface.clj` only
- Internal namespaces: `core.clj`, `util.clj`, `constraints.clj`, etc.
- Error types use canonical `:type` keywords (see [docs/ERROR_CODES.md](docs/ERROR_CODES.md))
- Dynamic vars for configuration: `*query-timeout-ms*`, `*max-graph-iterations*`

## File Locations

```
src/graphden/<module>/interface.clj    # Public API
test/graphden/<module>/                # Tests
docs/                                  # Documentation
```

### Namespace Structure

```
src/graphden/
├── library/            # Base function definitions (separated from executor)
│   ├── interface.clj   # Public API (get-all-defs)
│   ├── base_fns/
│   │   ├── core/       # Core primitives (arithmetic, logic, HOF, etc.)
│   │   └── web/        # Web-related base-fns (http, reitit, html)
│   └── fn_defs/
│       └── web/        # Web fn compositions (editor, etc.)
├── executor/           # Executor, registry, composition
│   ├── interface.clj
│   ├── registry/
│   └── composition/
├── schema/             # Protocol, malli, graph, versioned, traits, fields
│   ├── protocol/
│   ├── malli/
│   ├── graph/
│   ├── versioned/
│   ├── traits/
│   └── fields/
├── storage/            # Protocol, postgres
│   ├── protocol/
│   └── postgres/
├── versioning/         # Storage decorator, merge protection
│   ├── storage/
│   └── merge/
├── web/                # HTTP-kit, reitit, server, editor UI
│   ├── http_kit/       # HTTP server base functions
│   ├── reitit/         # Router base functions
│   ├── server/         # Server fn-defs
│   ├── editor/         # Graph editor UI
│   ├── graph/          # Graph API handlers
│   ├── crud/           # CRUD API handlers
│   └── html/           # HTML rendering utilities
├── logging/            # Structured logging with MDC
│   └── interface.clj
├── system/             # Integrant lifecycle management
│   ├── interface.clj   # start!, stop!, read-config
│   ├── config.clj      # Aero config loading
│   └── core.clj        # ig/init-key implementations
└── executor_runtime/   # Main entry point
    └── core.clj        # -main, shutdown hooks
```

## CI Workflow

**Run once, save output:**

```bash
bb ci 2>&1 | tee /tmp/ci-output.txt
```

Check final line for PASSED/FAILED. All information is in the single run — don't run multiple times.
