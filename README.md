# Graphden

[![Clojure](https://img.shields.io/badge/Clojure-1.12-blue.svg)](https://clojure.org/)
[![Coverage](https://img.shields.io/badge/coverage-93%25-brightgreen.svg)](#testing)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

**Visual functional programming environment and distributed execution runtime** — a function graph stored in a database.

## Vision

Graphden is an experimental platform where:

- **Code = Graph in DB** — functions and their compositions are stored as structured data
- **Visual Editing** — graphical interface instead of text
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
│                    EXECUTOR LAYER                           │
│  executor + base-functions + fn-registry + fn-composition   │
├─────────────────────────────────────────────────────────────┤
│                     GRAPH LAYER                             │
│  graph-data-schema: fn, fn-schema, arg-schema, arg-value    │
│  graph-storage-age: Apache AGE graph storage                │
├─────────────────────────────────────────────────────────────┤
│                    STORAGE LAYER                            │
│  storage-protocol: StorageCRUD, ExecutionGraph protocols    │
│  postgres-storage: PostgreSQL backend                       │
├─────────────────────────────────────────────────────────────┤
│                  DATA SCHEMA LAYER                          │
│  data-schema-protocol + malli-data-schema + field-types     │
└─────────────────────────────────────────────────────────────┘
```

## Components

### Core Protocols

| Component | Description |
|-----------|-------------|
| [storage-protocol](components/storage-protocol/) | Storage, CRUD, ExecutionGraph protocols |
| [data-schema-protocol](components/data-schema-protocol/) | DataSchema protocol for entity definitions |
| [field-types](components/field-types/) | Supported data types (:int, :text, :bool, :jsonb, etc.) |
| [malli-data-schema](components/malli-data-schema/) | Malli-based schema builder |
| [graph-data-schema](components/graph-data-schema/) | Function graph entity schema (fn, fn-schema, arg-schema, arg-value) |

### Storage

| Component | Description |
|-----------|-------------|
| [postgres-storage](components/postgres-storage/) | PostgreSQL storage backend |
| [graph-storage-age](components/graph-storage-age/) | Apache AGE graph storage (execution graph) |

### Execution

| Component | Description |
|-----------|-------------|
| [executor](components/executor/) | Graph executor with lazy evaluation, depth/timeout protection |
| [base-functions](components/base-functions/) | 50+ base functions (arithmetic, strings, collections, HOF) |
| [fn-registry](components/fn-registry/) | Base function registration and synchronization |
| [fn-composition](components/fn-composition/) | Function composition utilities |

### Web

| Component | Description |
|-----------|-------------|
| [http-kit-fns](components/http-kit-fns/) | HTTP server base functions |
| [reitit-fns](components/reitit-fns/) | Reitit router base functions |
| [web-server-fns](components/web-server-fns/) | Web server utilities |

### Versioning (Optional)

| Component | Description |
|-----------|-------------|
| [versioned-storage](components/versioned-storage/) | Version tracking decorator |
| [versioned-data-schema](components/versioned-data-schema/) | Versioning schema extensions |

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
├── components/           # Component-based modules
│   ├── storage-protocol/ # Core storage protocols
│   ├── postgres-storage/ # PostgreSQL backend
│   ├── graph-storage-age/# Apache AGE graph storage
│   ├── executor/         # Graph executor
│   ├── base-functions/   # Base function implementations
│   └── ...
├── bases/
│   └── executor-runtime/ # Executor server runtime
├── projects/
│   └── executor-server/  # Deployable executor server
├── docs/                 # Documentation
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
