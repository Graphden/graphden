# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Graphden is a visual functional programming environment where functions and their compositions are stored as a graph in a database. Key concepts:

- **Code = Graph in DB** — functions, schemas, and argument values stored as entities
- **Currying via Inheritance** — partial application through parent-fn chains
- **Lazy Execution** — thunk-based evaluation, only computes what's needed
- **Three storage backends** — memory (tests), PostgreSQL (production), Datomic (immutable history)

## Common Commands

```bash
bb repl         # Start REPL with dev profile
bb ci           # Full CI: linters + tests + coverage (parallel, live progress)
bb test         # Run all tests
bb coverage     # Tests with coverage report (open target/coverage/index.html)
bb check        # Linters only (clj-kondo, splint, cljstyle in parallel)
bb fix          # Auto-fix formatting
bb kondo        # clj-kondo only
bb splint       # splint only
bb cljstyle     # cljstyle check only
```

### Running a Single Test

```bash
# Run specific test namespace
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test

# Run specific test var
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test/execute-test
```

### Polylith Commands

```bash
bb info                    # Show workspace info
bb deps                    # Show component dependencies
bb create-component NAME   # Create component and sync paths
bb sync                    # Sync paths to deps.edn
```

## Architecture

This is a Polylith monorepo. Top namespace: `graphden`. Each component has an `interface.clj` as its public API.

### Layer Structure (bottom to top)

1. **Protocols** — `storage-protocol`, `data-schema-protocol`, `field-types`
2. **Storage Implementations** — `memory-storage`, `postgres-storage`, `datomic-storage`
3. **Schema** — `malli-data-schema`, `graph-data-schema` (function graph entities)
4. **Execution** — `executor`, `base-functions`, `fn-registry`
5. **Bundles** — `graph-storage-*`, `graph-with-base-fns-*` (complete stacks)

### Key Protocols

**Storage** (`storage-protocol/interface.clj`):
- `Storage` — initialize, close
- `StorageCRUD` — create/read/update/delete entities
- `StorageBatchCRUD` — bulk operations
- `GraphConstraints` — function graph integrity (no cycles, valid inheritance)
- `ExecutionGraphReader` — resolve execution graph for a function

**DataSchema** (`data-schema-protocol/interface.clj`):
- Builder pattern for defining entities, enums, constraints

### Core Entities (graph-data-schema)

- `fn-schema` — function signature (args, return type, optional base-fn-name)
- `arg-schema` — argument definition (name, type, required)
- `fn` — function instance (implements a schema, optional parent for currying)
- `arg-value` — bound argument value (literal or reference to another fn)
- `fn-result-value` — cached computation reference (memoization within execution)

### Executor Model

Arguments are wrapped in `delay` for lazy evaluation. Key patterns:
- Literal values → immediate
- `ref<fn>` with type `:fn` → pass fn-id to HOF (don't execute)
- `ref<fn>` with other type → execute and return result
- `ref<fn-result-value>` → execute once, cache result

HOF (map, filter, reduce) use single-argument model: the passed function must have exactly one required argument.

## Testing Patterns

- Contract tests in `storage-protocol/contract_tests.clj` — run against all storage backends
- Test helpers in `storage-protocol/test_helpers.clj`:
  - `create-test-storage` — in-memory storage for tests
  - `with-test-storage` — macro with cleanup
- PostgreSQL tests use testcontainers (requires Docker)
- Datomic tests use datomic-local in-memory

## Code Conventions

- Public API through `interface.clj` only
- Internal namespaces: `core.clj`, `util.clj`, `constraints.clj`, etc.
- Error types use canonical `:type` keywords (e.g., `:constraint-violation/dependency-cycle`)
- Dynamic vars for configuration: `*query-timeout-ms*`, `*max-graph-iterations*`
- Sensitive data redaction via `redact-sensitive-deep` before logging

## Important Constraints

1. **No arg override** — cannot redefine argument already set in parent chain
2. **Same schema inheritance** — parent must have same fn-schema-id
3. **No dependency cycles** — A→B→A forbidden (but self-recursion A→A allowed with depth limit)
4. **No inheritance cycles** — parent chain must be acyclic

These are enforced by `GraphConstraints` protocol at write time.

## File Locations

- Component sources: `components/<name>/src/graphden/<name_snake>/`
- Component tests: `components/<name>/test/graphden/<name_snake>/`
- Development project: `development/`
- Architecture docs: `docs/ARCHITECTURE.md`

## CI Workflow

**IMPORTANT**: Run `bb ci` only ONCE and save output to a file for analysis:

```bash
bb ci 2>&1 | tee /tmp/ci-output.txt
```

Then analyze the saved output:
- Check final line for PASSED/FAILED status
- If failed: grep for specific errors, check which linter/test failed
- If passed: review coverage numbers

DO NOT run `bb ci` multiple times to check different parts of output. All information is in the single run.
