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

## Function Composition Model

### Two Layers: Base Functions vs Fn Entities

**Base functions** (`base-functions`, `http-kit-fns`, `reitit-fns`):
- Clojure implementations wrapping pure functions
- Defined with `defbase` macro
- Registered in storage as `fn-schema` with `base-fn-name`
- Examples: `add`, `const`, `assoc`, `conj`, `map-fn`, `router`, `http-server`

**Fn entities** (`fn-defs`):
- Compositions of base functions stored in DB
- Define concrete values and wiring between functions
- No Clojure code — pure data structures
- Example: `web-server/fn-defs` builds HTTP server from base-fns

### Fn-def Syntax

Fn-defs are vectors of maps describing function compositions:

```clojure
{:name :my-fn           ; unique function name (keyword)
 :parent :base-fn-name  ; base function or another fn to inherit from
 :args {:arg1 value     ; argument values
        :arg2 :other-fn>}}  ; reference with > suffix
```

### Argument Value Reference Syntax

When referencing other functions in args:

| Syntax | Meaning | When to use |
|--------|---------|-------------|
| `:fn-name` | Pass fn-id (UUID) without executing | HOF (map, filter, reduce) |
| `:fn-name>` | Execute fn and use result | When you need the computed value |

**Examples:**

```clojure
;; const returns a function, we need that function as value
{:name :handler-map-fn
 :parent :assoc
 :args {:m {}, :k "handler", :v :my-handler-fn>}}  ; > = execute const, get fn

;; router returns Ring handler fn, http-server needs that fn
{:name :web-server-fn
 :parent :http-server
 :args {:handler :router-fn>   ; > = execute router, get Ring handler
        :port 8080}}

;; map-fn needs fn-id to call for each element (HOF pattern)
{:name :double-all-fn
 :parent :map-fn
 :args {:f :double-fn    ; NO > = pass fn-id, don't execute
        :coll [1 2 3]}}
```

### Base Function Arg Types

In `defbase`, arg types control how values are processed:

| Arg Type | Behavior |
|----------|----------|
| `:int`, `:text`, etc. | Normal value, auto-deref from delay |
| `:fn` | Expects fn-id (UUID), auto-wrapped in `make-single-arg-callable` |
| `:any` | Accepts any value, no special processing |
| `:jsonb` | Map or vector |

**Important**: `:fn` type args are auto-wrapped by defbase macro. If your base-fn receives an already-executed Clojure fn (not a fn-id to execute), use `:any` type instead.

### Complete Example: Web Server

```clojure
;; Base functions used (from different components):
;; - const: (fn [x] (fn [_] x)) - returns constant function
;; - assoc: (fn [m k v] (assoc m k v)) - associates key in map
;; - conj: (fn [coll x] (conj coll x)) - adds to collection
;; - router: creates Ring router from routes vector
;; - http-server: starts http-kit server with handler

;; Fn-defs compose these into a web server:
[{:name :hello-handler-fn
  :parent :const
  :args {:x {:status 200 :body "Hello"}}}

 ;; Build route data: {"handler" <fn>}
 {:name :hello-handler-map-fn
  :parent :assoc
  :args {:m {}, :k "handler", :v :hello-handler-fn>}}  ; > executes const

 ;; Build method map: {"get" {"handler" <fn>}}
 {:name :hello-method-map-fn
  :parent :assoc
  :args {:m {}, :k "get", :v :hello-handler-map-fn>}}

 ;; Build route tuple: ["/" {"get" {"handler" <fn>}}]
 {:name :hello-route-path-fn
  :parent :conj
  :args {:coll [], :x "/"}}

 {:name :hello-route-fn
  :parent :conj
  :args {:coll :hello-route-path-fn>, :x :hello-method-map-fn>}}

 ;; Collect routes into vector
 {:name :routes-fn
  :parent :conj
  :args {:coll [], :x :hello-route-fn>}}

 ;; Create router from routes
 {:name :router-fn
  :parent :router
  :args {:routes :routes-fn>}}

 ;; Start server with router as handler
 {:name :web-server-fn
  :parent :http-server
  :args {:handler :router-fn>   ; router returns Clojure fn
         :port 8080}}]
```

## Executor Model

Arguments are wrapped in `delay` for lazy evaluation. Key patterns:
- Literal values → immediate
- `ref<fn>` with type `:fn` → pass fn-id to HOF (don't execute)
- `ref<fn>` with other type → execute and return result
- `ref<fn-result-value>` → execute once, cache result

HOF (map, filter, reduce) use single-argument model: the passed function must have exactly one required argument.

## Type System

Types are used for:
1. **Runtime validation** of user-provided arguments (not stored values)
2. **Storage mapping** (PostgreSQL JSONB, Datomic EDN string, etc.)
3. **defbase arg handling** (`:fn` type triggers HOF callable creation)

Supported types: `:int`, `:text`, `:bool`, `:numeric`, `:jsonb`, `:uuid`, `:fn`, `:ref`, `:any`, `:union`

**Storage of arg-value.value (union type):**

| Storage | Format |
|---------|--------|
| PostgreSQL | JSONB |
| Datomic | EDN string (pr-str/edn-read) |
| Memory | Clojure value as-is |

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
