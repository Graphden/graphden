# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Design Principles (MUST READ)

**Every change must improve at least one principle without violating others.**

| # | Principle | Description |
|---|-----------|-------------|
| 1 | **Correctness first** | No feature justifies bugs. Comprehensive tests required. |
| 2 | **Minimal entities** | Resist adding new entity types, fields, or edge types. Each addition increases complexity everywhere. |
| 3 | **Explicit over implicit** | Behavior must be visible in graph structure. No magic, no context-dependent semantics. |
| 4 | **DRY** | Never define the same thing twice. Use base-functions and call-site for reuse. |
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
- **Storage backend** — PostgreSQL with Apache AGE for graph queries

**Core entities** (only 5 — kept minimal by design):
- `fn-schema` — function signature (name, return type, optional base-fn-name linking to Clojure impl)
- `arg-schema` — argument definition (belongs to fn-schema)
- `fn` — function instance (references fn-schema, has bound arg-values)
- `arg-value` — bound argument value (literal or reference to fn/call-site)
- `call-site` — reference to a function call site (NOT cached result — see below)

## Core Concept: call-site

**call-site is NOT primarily about caching. Its main purpose is structural:**

It distinguishes between the same function called at different points in the execution graph.

**Example:** Get current time, sleep 5 seconds, get current time again, print both.
```
fn: current-time (base function)
fn: sleep (base function)
fn: print-two-times (base function with args: t1, t2)

;; These are TWO DIFFERENT call-sites pointing to the SAME fn
call-site: time-before  → fn: current-time
call-site: time-after   → fn: current-time

fn: my-program
  arg: t1 = ref<call-site:time-before>   ;; first call
  arg: wait = ref<call-site:sleep-5s>
  arg: t2 = ref<call-site:time-after>    ;; second call (different!)
```

Without call-site, we couldn't distinguish "time before sleep" from "time after sleep" — they'd be the same function reference.

**Free arguments are passed at execution time via call-site-args:**
```clojure
;; fn-a has free argument arg-schema-a (not bound in DB)
;; call-site-a references fn-a (this is a "call site")

;; At execution time, pass value for the free argument:
(create-context {:storage s
                 :call-site-args {[call-site-a-id arg-schema-a-id] 42}})

;; The executor resolves this when it reaches call-site-a
```

**Key insight:** No new schema fields needed. The graph structure (call-site pointing to fn) plus runtime call-site-args is sufficient.

## Documentation Map

| Document | Purpose | When to read |
|----------|---------|--------------|
| [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) | Design principles, rationale, module mapping | Before making architectural decisions |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Technical details, execution model, examples | When implementing features |
| [docs/CONSTRAINTS.md](docs/CONSTRAINTS.md) | Graph constraint specifications | When working with GraphConstraints |
| [docs/ERROR_CODES.md](docs/ERROR_CODES.md) | Error types reference | When handling errors |
| [docs/EXTENDING.md](docs/EXTENDING.md) | Adding new storage backends | When implementing new backend |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Implementation status, future plans | For project planning |

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
docker-compose up        # Run with Apache AGE
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
│  Backends: postgres-storage, graph-storage-age              │
├─────────────────────────────────────────────────────────────┤
│                    DATA SCHEMA LAYER                         │
│  data-schema-protocol + malli-data-schema + field-types     │
│  Schema extensions: versioned-data-schema, graph-data-schema│
└─────────────────────────────────────────────────────────────┘
```

**Key principle:** Each layer depends only on the layer below it. Executor calls `sp/resolve-execution-graph` which returns `ExecutionGraphResult`. Storage implementations (AGE, Postgres) each implement this protocol optimally.

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
├── executor/           # Executor, base-fns, registry, composition
│   ├── interface.clj
│   ├── base-fns/
│   ├── registry/
│   └── composition/
├── schema/             # Protocol, malli, graph, versioned, traits, fields
│   ├── protocol/
│   ├── malli/
│   ├── graph/
│   ├── versioned/
│   ├── traits/
│   └── fields/
├── storage/            # Protocol, postgres, AGE
│   ├── protocol/
│   ├── postgres/
│   └── age/
├── versioning/         # Storage, merge protection
│   ├── storage/
│   └── merge/
├── web/                # HTTP-kit, reitit, server
│   ├── http-kit/
│   ├── reitit/
│   └── server/
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
