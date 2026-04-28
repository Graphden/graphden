# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Design Principles (MUST READ)

**Every change must improve at least one principle without violating others.**

| # | Principle | Description |
|---|-----------|-------------|
| 1 | **Correctness first** | No feature justifies bugs. Comprehensive tests required. |
| 2 | **Minimal entities** | Resist adding new entity types, fields, or edge types. Each addition increases complexity everywhere. |
| 3 | **Explicit over implicit** | Behavior must be visible in graph structure. No magic, no context-dependent semantics. |
| 4 | **DRY** | Never define the same thing twice. Use inheritance (parent-id) and result caching for reuse. |
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
- **Code = Graph in DB** — functions and arguments stored as entities
- **Lazy Execution** — delay-based evaluation, only computes what's needed
- **Storage backend** — PostgreSQL with recursive CTE for graph traversal

**Core entities** (only 2 — minimal by design):
- `fn` — function (base or composed)
  - `parent-id=nil` → base-fn (has Clojure implementation)
  - `parent-id` set → composed fn (inherits from parent)
  - `name=nil` → local fn (scoped, not globally visible)
- `arg` — argument (primary or inherited)
  - `source-id=nil` → primary argument (defines interface)
  - `source-id` set → inherited/forwarded argument
  - `value` → literal JSONB value
  - `ref-id` → reference to fn (execute and use result)
  - `is-fn=true` → pass fn-id directly (for HOF)

## Core Concept: 2-Entity Inheritance Model

The system uses a unified inheritance model where functions inherit from other functions:

**Base function (parent-id=nil):**
```
fn: add (base-fn)
  parent-id: nil         ; marks this as base-fn
  return-type: :int
  impl-hash: "sha256..." ; links to Clojure implementation
  args:
    - {name: "a", type: :int, required: true}
    - {name: "b", type: :int, required: true}
```

**Composed function (parent-id set):**
```
fn: add-10
  parent-id: add         ; inherits from add
  args:
    - {source-id: add/a, value: 10}  ; binds parent's 'a' to 10
    ; 'b' not specified = exposed to callers
```

**Using the composed function:**
```clojure
;; Execute add-10 with b=5 → returns 15
(execute ctx add-10-id {:b 5})
```

**Key insight:** All argument binding happens in the database via arg entities. No separate fn-arg/arg-value entities needed. The arg entity combines schema, value, and inheritance in one place.

## Documentation Map

| Document | Purpose | When to read |
|----------|---------|--------------|
| [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) | Design principles, rationale, module mapping | Before making architectural decisions |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Technical details, execution model, examples | When implementing features |
| [docs/PACKAGES.md](docs/PACKAGES.md) | Package system, module structure, loading | When adding base-fns or fn-defs |
| [docs/TYPES.md](docs/TYPES.md) | Type system design & semantics | When working with arg types |
| [docs/LAYOUT.md](docs/LAYOUT.md) | Graph-editor layout pipeline (Stages 1–7) | When touching layout impl or editor frontend |
| [docs/CONSTRAINTS.md](docs/CONSTRAINTS.md) | Graph constraint specifications | When working with GraphConstraints |
| [docs/ERROR_CODES.md](docs/ERROR_CODES.md) | Error types reference | When handling errors |
| [docs/EXTENDING.md](docs/EXTENDING.md) | HOF semantics, custom storage, schema extensions, impl-hash | When extending below the package layer |
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

# Build & Deploy (Docker)
bb rebuild      # Rebuild jar + docker + restart (ALWAYS use this after code changes!)
bb deploy       # Full rebuild with DB truncate (for clean deployments)
```

**IMPORTANT:** After ANY backend code change (Clojure files, resources/packages/), ALWAYS run `bb rebuild` to apply changes. Never use raw docker commands.

### Running a Single Test

```bash
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test/execute-test
```

### Deploy verification — `bb verify`

Every uberjar carries a `graphden-build-hashes.json` resource with
three SHA-256 digests, written by `build.clj`'s
`compute-section-hashes` step:

| Section | Files |
|---------|-------|
| `frontend` | `.js` / `.css` / `.html` / `.svg` under `resources/packages/` |
| `packages` | `.edn` / `.clj` under `resources/packages/` |
| `backend`  | `src/**/*.clj` plus non-package resources |

Three consumers read those hashes:

- `GET /version` → `{"frontend": "<hex>", "packages": "<hex>", "backend": "<hex>"}`
- `window.BUILD_HASH` — first 12 chars of the `frontend` hash,
  substituted into the `__BUILD_HASH__` placeholder in
  `editor-state.js` at bundle time. Exposed on `window` for
  on-demand readout (type `BUILD_HASH` in DevTools, or read it
  programmatically from a test). No auto-log to console.
- `bb verify [<base-url>]` — recomputes the same three hashes from
  the local checkout, fetches `<base-url>/version`, and reports each
  section's match/mismatch independently.

Workflow:

```bash
bb rebuild           # rebuild JAR + docker image
bb verify            # per-section ✓/✗ — tells you WHICH part of the
                     # deploy didn't ship: e.g. frontend matches but
                     # backend differs → docker image rebuilt with a
                     # stale jar
bb verify https://prod.example.com   # any URL
```

Exit codes: 0 (every section matches), 1 (at least one mismatch),
2 (`/version` unreachable).

`window.BUILD_HASH` and the `frontend` field of `/version` always
agree because they come from the same baked-in resource. If
`bb verify` reports backend match but the user's browser still
behaves like old code, compare `window.BUILD_HASH` (DevTools console)
against `fetch('/version').then(r=>r.json())` — a divergence is a
browser-cache issue, not a deploy issue (offer the in-app reload
button or Ctrl+Shift+R).

The placeholder is `__BUILD_HASH__` — it lives only in
`editor-state.js`. Don't delete it; the substitution step would have
nothing to replace and `window.BUILD_HASH` would be the literal
string `"__BUILD_HASH__"`.

### Frontend Module Structure

The editor frontend is split into modules for better maintainability:

| File | Purpose | Dependencies |
|------|---------|--------------|
| `editor-state.js` | Global variables, constants, timestamp | - |
| `editor-data.js` | Data utilities, lookups, inheritance | state |
| `editor-layout.js` | Grid layout algorithm, positioning | state |
| `editor-tooltips.js` | Description-tooltip + full-name popover singletons | state |
| `editor-icons.js` | Right-edge action icons (`i`, `↗`) | state, data, tooltips |
| `editor-drag.js` | Drag handle for any overlay | state |
| `editor-overlays.js` | HTML overlays for cy nodes (rows, edges, placeholders, args) | state, data, tooltips, icons, drag |
| `editor-ui.js` | Sidebar, selection, expansion controls | state, data, icons, cytoscape |
| `editor-cytoscape.js` | Cytoscape initialization, rendering | state, layout, overlays |
| `editor-main.js` | Entry point, init | all |

**Load order** (in `app/editor/fns.edn` `_editor-script-paths`): state → data → layout → tooltips → icons → drag → overlays → ui → cytoscape → main

### Browser Test Tool

Automated browser testing with Playwright in `tools/browser-test/`:

```bash
cd tools/browser-test

# View a function's graph
node check-editor.js web-server

# Expand root node ancestors
node check-editor.js web-server root:1

# Expand multiple nodes
node check-editor.js web-server root:1 router-fn:1
```

**Output:**
- Screenshot: `/tmp/editor-screenshot.png`
- Console logs printed to terminal
- Build timestamp verification

**Expand spec format:** `node-name:level` (use `root` for selected function)

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
        :arg2 :other-fn}}  ; reference to fn
```

### Reference Types

All function references are stored in the `ref-id` field.

| Syntax | Storage | Notes |
|--------|---------|-------|
| `:fn-name` | `ref-id` set | Reference to another fn |

**Key principle:** The `is-fn` flag on the **parent arg** (inherited via `source-id`) determines whether to pass the fn-id directly (for HOF) or execute the function and use its result. This flag is set automatically when syncing base functions based on arg type (`:fn` type → `is-fn=true`).

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
| **fn (graph)** | Composition description | Database | fn entity, arg entities |
| **Runtime fn** | Actual Clojure object | Executor memory | transducers, composed fns |

**Key insight:** The graph in DB is just a *description* of how functions compose. Actual Clojure functions (like transducers, composed functions) exist only at runtime in executor memory.

**No special-casing:** The executor must remain generic. Never add special handling for specific function names (anti-pattern). All functions execute uniformly through the same code path.

**Multi-arity for behavior:** Use optional arguments to control behavior (e.g., `coll` in `map`/`filter`). Clojure's multi-arity functions handle this idiomatically.

**Runtime objects can't be stored:** Functions returned by `comp`, transducers, etc. are Clojure objects — they exist only during execution. The graph stores *how to create them*, not the objects themselves.

### Base Function Philosophy (CRITICAL)

**Base functions MUST be minimal primitives wrapping a single Clojure/Java/library call.**

A base-fn impl should ideally be **1-2 lines** of actual logic: call the library, return the result. Everything else — composition, defaults, transformation — belongs in fn-defs.

✅ **GOOD base-fns** (atomic, generic, small):
- `add`, `sub`, `mul` — wrap Clojure arithmetic (1 line)
- `render-hiccup` — wrap hiccup2 `html` (1 line)
- `query-entities` — wrap storage protocol (1 line)
- `env` — get environment variable (1 line)
- `wrap-element` — `[(keyword tag) content]` (1 line)

❌ **BAD base-fns** (anti-patterns to avoid):
- Hardcoded HTML/CSS/JS content → use `:parent :const` fn-def
- Hardcoded defaults → use arg `:value` in fns.edn
- Calling another base-fn → hidden composition, must be fn-def
- Multi-step processing (parse → transform → format) → decompose into separate base-fns composed via fn-defs
- More than ~5 lines of actual logic (excluding validation)

**Key rule: base-fn MUST NOT call another base-fn.** If impl A calls impl B, and both are registered base-fns, the composition A→B is hidden in code instead of being visible in the graph. Fix: either compose via fn-def, or make the shared logic a private helper (not a base-fn).

**Acceptable exceptions for longer impls:**
- Input validation / size limits (e.g., `range` validates step≠0 and max-size)
- Library adapter boilerplate (e.g., `http-server` builds Ring handler format)
- These are part of safely wrapping the library, not business logic

**Example: Graph Editor should be fn-defs:**
```clojure
;; CORRECT approach
{:name :editor-styles, :parent :const, :args {:x "CSS here..."}}
{:name :editor-body, :parent :const, :args {:x [:div ...]}}
{:name :editor-page, :parent :html-page, :args {:title "Editor" :body :editor-body}}
{:name :editor-router, :parent :router, :args {:routes [...]}}
{:name :web-server, :parent :http-server, :args {:handler :editor-router :port 8080}}
```

See [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) "Base Functions Philosophy" for full details.

### Base Function impl-hash

Each base function has an `impl-hash` stored in the `fn` entity for version tracking:
- SHA-256 hash of canonical form (args, return-type, impl-source)
- Detects: body changes, arg changes, return-type changes
- Ignores: whitespace, comments, map key ordering

See [docs/EXTENDING.md](docs/EXTENDING.md) for details.

## Graph Constraints

Enforced at write time by `GraphConstraints` protocol (part of Graph Layer):

1. **No dependency cycles** — A→B→A forbidden (self-recursion allowed with depth limit)
2. **Arg source-id references valid parent arg** — type safety for inheritance
3. **Unique arg per fn + source** — no duplicate inherited args
4. **Unique arg name within fn** — no duplicate arg names

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
├── packages/           # Package loader for resources/packages/
│   └── loader.clj      # load-packages, load-module-fns, load-module-impls
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
├── logging/            # Structured logging with MDC
│   └── interface.clj
├── system/             # Integrant lifecycle management
│   ├── interface.clj   # start!, stop!, read-config
│   ├── config.clj      # Aero config loading
│   └── core.clj        # ig/init-key implementations
└── executor_runtime/   # Main entry point
    └── core.clj        # -main, shutdown hooks

resources/packages/     # Package definitions (EDN + Clojure impls)
├── core/               # Core primitives (arithmetic, logic, HOF, etc.)
│   ├── package.edn     # Package metadata + dependencies
│   ├── arithmetic/     # {fns.edn, impls.clj}
│   ├── logic/
│   ├── hof/
│   ├── collections/
│   ├── strings/
│   └── system/
├── web/                # Web primitives (http, routing, html)
│   ├── package.edn
│   ├── http/
│   ├── reitit/
│   ├── html/
│   ├── crud/
│   └── graph/
└── app/                # Application server (editor, routes)
    ├── package.edn     # Has startup-fn: :web-server
    ├── common/         # Shared fn-defs (routes, responses)
    ├── editor/         # Editor UI fn-defs + impls
    └── server/         # Server composition fn-defs
```

## Packages System

Base functions and fn-defs live in `resources/packages/{pkg}/{module}/` as `fns.edn` (declarations) + `impls.clj` (Clojure impls). Dependencies in `package.edn` drive load order. See [docs/PACKAGES.md](docs/PACKAGES.md) for full format and workflow.

## Best Practices (CRITICAL)

The full rationale and worked examples live in [docs/PACKAGES.md § Composition Best Practices](docs/PACKAGES.md#composition-best-practices). The bullets below are the bare minimum for AI-assisted edits — read PACKAGES.md before larger structural changes.

### 1. DRY via inheritance
Extract a common parent when ≥ 2 fn-defs share an ancestor AND ≥ 1 bound arg with the same structure. Indicators: same parent, same bound args, repeated shape, can be named meaningfully. See [§ 1](docs/PACKAGES.md#1-use-inheritance-to-eliminate-duplication-dry).

### 2. Free-args propagation
Unbound args of a referenced fn-def surface as free args of the caller — that's how reusable templates (`:get-route`, `:json-ok-response`, …) work. Arg names propagate up; renames via `{:as :name}` swap the public name. See [§ 2](docs/PACKAGES.md#2-free-arguments-pattern-argument-propagation).

### 3. Named vs one-off
Name a fn-def when it's reused, has independent meaning, or represents a domain concept. Inline (no name) when used exactly once with no semantic identity. Heuristic: if you can't name it without describing wiring, it's one-off. See [§ 3](docs/PACKAGES.md#3-named-vs-anonymous-one-off-functions).

### 4. Hierarchy depth
2–3 levels is normal, 4–5 acceptable for route/response composition, 6+ needs justification. Each level should have a name, potential reuse, and a cohesive concept. See [§ 4](docs/PACKAGES.md#4-hierarchy-depth-guidelines).

### 5. Base-fn vs fn-def
Base-fn: has Clojure impl, wraps library, ≤ ~20 LOC body. Fn-def: pure composition, may carry hardcoded values, no impl. Base-fns MUST NOT call other base-fns — that's hidden composition. See [§ 5](docs/PACKAGES.md#5-base-function-vs-fn-def-decision-matrix) and [PHILOSOPHY § Base Functions](docs/PHILOSOPHY.md#base-functions-philosophy).

### 6. Naming (short names, context carries meaning)
Names add the **last bit of distinction** — namespace, parent, and arg names convey the rest. Drop affixes the context already says; keep affixes that disambiguate vs a sibling. Verb-at-end (`entity-create`) when prefix-form clashes with a base-fn. Extract a sub-NS when ≥ ~5 fn-defs share a long prefix. Names are validated for **global** uniqueness at sync time, **including locals** (`_*`).

Before renaming, grep:
```bash
grep -rE ":name :the-target-name\b|defbase the-target-name\b" resources/packages/
```

See [docs/PACKAGES.md § Naming Guidelines](docs/PACKAGES.md#naming-guidelines) for the full rationale, decision matrix, and worked examples.

## CI Workflow

**Run once, save output:**

```bash
bb ci 2>&1 | tee /tmp/ci-output.txt
```

Check final line for PASSED/FAILED. All information is in the single run — don't run multiple times.
