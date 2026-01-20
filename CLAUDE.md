# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Design Principles (MUST READ)

**Every change must improve at least one principle without violating others.**

| # | Principle | Description |
|---|-----------|-------------|
| 1 | **Correctness first** | No feature justifies bugs. Comprehensive tests required. |
| 2 | **Minimal entities** | Resist adding new entity types, fields, or edge types. Each addition increases complexity everywhere. |
| 3 | **Explicit over implicit** | Behavior must be visible in graph structure. No magic, no context-dependent semantics. |
| 4 | **DRY** | Never define the same thing twice. Use inheritance and fn-result-value for reuse. |
| 5 | **Expressiveness parity** | Can do everything classical languages can. No "sorry, you can't do that." |
| 6 | **No unnecessary expressiveness** | Don't add features just because we can. |
| 7 | **Locality of changes** | Changing one node shouldn't require changes elsewhere. |
| 8 | **Incrementality** | Adding features shouldn't require rewriting existing ones. |

**Before making changes, ask:**
- Which principle does this improve?
- Does it violate any other principle?
- Is there a simpler way?

See [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) for full rationale and component-to-principle mapping.

## Project Overview

Graphden is a visual functional programming environment where functions and their compositions are stored as a graph in a database.

**Key concepts:**
- **Code = Graph in DB** — functions, schemas, and argument values stored as entities
- **Currying via Inheritance** — partial application through parent-fn chains
- **Lazy Execution** — delay-based evaluation, only computes what's needed
- **Three storage backends** — memory (tests), PostgreSQL (production), Datomic (immutable history)

**Core entities** (only 5 — kept minimal by design):
- `fn-schema` — function signature
- `arg-schema` — argument definition
- `fn` — function instance with optional parent (inheritance)
- `arg-value` — bound argument value
- `fn-result-value` — cached computation reference

## Documentation Map

| Document | Purpose | When to read |
|----------|---------|--------------|
| [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) | Design principles, rationale, component mapping | Before making architectural decisions |
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
```

### Running a Single Test

```bash
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test/execute-test
```

### Polylith Commands

```bash
bb info                    # Show workspace info
bb deps                    # Show component dependencies
bb create-component NAME   # Create component and sync paths
```

## Architecture Overview

Polylith monorepo. Top namespace: `graphden`. Public API through `interface.clj` only.

### Layer Structure

```
┌─────────────────────────────────────────────────────────────┐
│              graph-with-base-fns-*                          │
│  (complete stack: storage + schema + executor + base-fns)   │
├─────────────────────────────────────────────────────────────┤
│  executor  │  base-functions  │  fn-registry               │
├────────────┴─────────────────┬┴────────────────────────────┤
│         graph-data-schema    │     malli-data-schema       │
├──────────────────────────────┴─────────────────────────────┤
│ storage-protocol │ data-schema-protocol │ field-types      │
├──────────────────┴───────────┬─────────┴──────────────────┤
│     memory/postgres/datomic-storage                        │
├────────────────────────────────────────────────────────────┤
│          cached-storage + cache-*                          │
└────────────────────────────────────────────────────────────┘
```

See [README.md](README.md) for component list.

## Quick Reference

### Fn-def Syntax

```clojure
{:name :my-fn           ; unique function name
 :parent :base-fn-name  ; function to inherit from
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

## Graph Constraints

Enforced at write time by `GraphConstraints` protocol:

1. **No arg override** — cannot redefine argument from parent chain
2. **Same schema inheritance** — parent must have same fn-schema-id
3. **No dependency cycles** — A→B→A forbidden (self-recursion allowed with depth limit)
4. **No inheritance cycles** — parent chain must be acyclic
5. **Arg-schema belongs to fn-schema** — type safety for argument binding

See [docs/CONSTRAINTS.md](docs/CONSTRAINTS.md) for detailed specifications.

## Code Conventions

- Public API through `interface.clj` only
- Internal namespaces: `core.clj`, `util.clj`, `constraints.clj`, etc.
- Error types use canonical `:type` keywords (see [docs/ERROR_CODES.md](docs/ERROR_CODES.md))
- Dynamic vars for configuration: `*query-timeout-ms*`, `*max-graph-iterations*`

## File Locations

```
components/<name>/src/graphden/<name_snake>/interface.clj  # Public API
components/<name>/test/graphden/<name_snake>/              # Tests
docs/                                                       # Documentation
```

## CI Workflow

**Run once, save output:**

```bash
bb ci 2>&1 | tee /tmp/ci-output.txt
```

Check final line for PASSED/FAILED. All information is in the single run — don't run multiple times.
