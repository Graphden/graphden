# Graphden

[![Clojure](https://img.shields.io/badge/Clojure-1.12-blue.svg)](https://clojure.org/)
[![Polylith](https://img.shields.io/badge/architecture-Polylith-purple.svg)](https://polylith.gitbook.io/)
[![Coverage](https://img.shields.io/badge/coverage-93%25-brightgreen.svg)](#testing)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

**Visual functional programming environment and distributed execution runtime** — a function graph stored in a database.

## Vision

Graphden is an experimental platform where:

- **Code = Graph in DB** — functions and their compositions are stored as structured data
- **Visual Editing** — graphical interface instead of text
- **Currying via Inheritance** — partial function application through parent chains
- **Lazy Execution** — only computes what's needed
- **Execution Graph Caching** — O(1) graph resolution instead of O(depth) recursive queries

**Goals**:
1. Test the hypothesis that graph-based visual programming can be simpler and more readable than text code for high-level logic
2. Leverage the graph structure for automatic parallelization — independent subgraphs can be computed concurrently on different executors

See [Architecture](docs/ARCHITECTURE.md) for detailed design decisions and technical documentation.

## Example: Building a Web Server from fn-defs

Graphden separates **base functions** (Clojure implementations) from **fn entities** (pure data compositions):

```clojure
;; fn-defs are pure data — no Clojure code
(def fn-defs
  [;; Create constant handler: (fn [_] response)
   {:name :hello-handler-fn
    :parent :const
    :args {:x {:status 200 :body "Hello from Graphden!"}}}

   ;; Build route map: {"handler" <fn>}
   ;; Note: :hello-handler-fn> means "execute and use result"
   {:name :hello-route-data-fn
    :parent :assoc
    :args {:m {}, :k "handler", :v :hello-handler-fn>}}

   ;; Create router from routes
   {:name :router-fn
    :parent :router
    :args {:routes [["/" {:get :hello-route-data-fn>}]]}}

   ;; Start HTTP server
   ;; :router-fn> executes router, passes resulting Ring handler
   {:name :web-server-fn
    :parent :http-server
    :args {:handler :router-fn>
           :port 8080}}])

;; Reference syntax:
;; :fn-name  = pass fn-id (for HOF like map/filter)
;; :fn-name> = execute fn and use result
```

The executor resolves this graph and starts a working HTTP server.

For a complete step-by-step example, see [ARCHITECTURE.md Part 5.5](docs/ARCHITECTURE.md#part-55-function-composition-fn-defs).

## Quick Start

### Requirements

- Java 21+
- Clojure 1.12+
- [Babashka](https://github.com/babashka/babashka)

### Commands

```bash
bb repl      # Start REPL
bb ci        # Full CI: linters + tests + coverage (parallel)
bb test      # Tests only
bb coverage  # Tests with coverage report
bb check     # Linters only (parallel)
bb fix       # Auto-fix formatting
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│              graph-with-base-fns-*                          │
│  (complete stack: storage + schema + executor + base-fns)   │
├─────────────────────────────────────────────────────────────┤
│                    graph-storage-*                          │
│  (memory, postgres, datomic) — storage + graph schema       │
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
│  (optional caching layer with invalidation)                │
└────────────────────────────────────────────────────────────┘
```

## Components

### Core Protocols

| Component | Description |
|-----------|-------------|
| [storage-protocol](components/storage-protocol/) | Storage, CRUD, GraphConstraints, ExecutionGraph protocols |
| [data-schema-protocol](components/data-schema-protocol/) | DataSchema protocol for entity definitions |
| [field-types](components/field-types/) | Supported data types (:int, :text, :bool, :jsonb, etc.) |
| [malli-data-schema](components/malli-data-schema/) | Malli-based schema builder |
| [graph-data-schema](components/graph-data-schema/) | Function graph entity schema (fn, fn-schema, arg-schema, arg-value) |

### Storage Backends

| Component | Description |
|-----------|-------------|
| [memory-storage](components/memory-storage/) | In-memory storage (tests, development) |
| [postgres-storage](components/postgres-storage/) | PostgreSQL storage (production) |
| [datomic-storage](components/datomic-storage/) | Datomic storage (immutable history, audit) |

### Execution

| Component | Description |
|-----------|-------------|
| [executor](components/executor/) | Graph executor with lazy evaluation, depth/timeout protection |
| [base-functions](components/base-functions/) | 50+ base functions (arithmetic, strings, collections, HOF) |
| [fn-registry](components/fn-registry/) | Base function registration and synchronization |

### Caching (Optional)

| Component | Description |
|-----------|-------------|
| [cache-protocol](components/cache-protocol/) | CacheStorage protocol for execution graph caching |
| [cache-memory](components/cache-memory/) | In-memory cache implementation |
| [cache-postgres](components/cache-postgres/) | PostgreSQL-backed cache |
| [cache-datomic](components/cache-datomic/) | Datomic-backed cache |
| [cached-storage](components/cached-storage/) | Decorator wrapping storage with caching + invalidation |
| [cache-data-schema](components/cache-data-schema/) | Schema for cache storage tables |

### Ready-to-use Bundles

| Component | Description |
|-----------|-------------|
| [graph-storage-memory](components/graph-storage-memory/) | Memory storage + graph schema |
| [graph-storage-postgres](components/graph-storage-postgres/) | PostgreSQL storage + graph schema |
| [graph-storage-datomic](components/graph-storage-datomic/) | Datomic storage + graph schema |
| [graph-with-base-fns-memory](components/graph-with-base-fns-memory/) | Complete stack with memory backend |
| [graph-with-base-fns-postgres](components/graph-with-base-fns-postgres/) | Complete stack with PostgreSQL backend |
| [graph-with-base-fns-datomic](components/graph-with-base-fns-datomic/) | Complete stack with Datomic backend |

## Documentation

| Document | Description |
|----------|-------------|
| [PHILOSOPHY.md](docs/PHILOSOPHY.md) | Core principles and design philosophy |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design decisions, data model, constraints, execution model |
| [ROADMAP.md](docs/ROADMAP.md) | Implementation status, phases, future plans |
| [CONSTRAINTS.md](docs/CONSTRAINTS.md) | Graph constraint specifications |
| [ERROR_CODES.md](docs/ERROR_CODES.md) | Error types reference |
| [EXTENDING.md](docs/EXTENDING.md) | Guide for adding new storage backends |

## Project Structure

```
graphden/
├── components/           # Polylith components (23 total)
│   ├── storage-protocol/ # Core protocols
│   ├── *-storage/        # Storage implementations
│   ├── cache-*/          # Caching layer
│   ├── graph-data-schema/
│   ├── executor/
│   ├── base-functions/
│   └── graph-with-base-fns-*/  # Complete bundles
├── docs/
│   ├── ARCHITECTURE.md   # Technical documentation
│   ├── ROADMAP.md        # Implementation status
│   └── ...
├── development/          # Development project
├── bb.edn               # Babashka tasks
└── deps.edn             # Dependencies
```

## Testing

```bash
bb test                    # Run all tests
bb coverage                # Tests with coverage report
open target/coverage/index.html
```

Current: **731 tests, 93% forms / 97% lines coverage**

## License

GNU Affero General Public License v3.0 (AGPL-3.0).
See [LICENSE](LICENSE).

For commercial licensing: licensing@graphden.dev
