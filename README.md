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
- **Integrant System** — component lifecycle management with hot reload

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

## Modules

### Core Protocols (`src/graphden/`)

| Module | Description |
|--------|-------------|
| `storage/protocol/` | Storage, CRUD, ExecutionGraph protocols |
| `schema/protocol/` | DataSchema protocol for entity definitions |
| `schema/fields/` | Supported data types (:int, :text, :bool, :jsonb, etc.) |
| `schema/malli/` | Malli-based schema builder |
| `schema/graph/` | Function graph entity schema (fn, fn-schema, arg-schema, arg-value) |

### Storage

| Module | Description |
|--------|-------------|
| `storage/postgres/` | PostgreSQL storage backend |
| `storage/age/` | Apache AGE graph storage (execution graph) |

### Execution

| Module | Description |
|--------|-------------|
| `executor/` | Graph executor with lazy evaluation, depth/timeout protection |
| `executor/base-fns/` | 50+ base functions (arithmetic, strings, collections, HOF) |
| `executor/registry/` | Base function registration and synchronization |
| `executor/composition/` | Function composition utilities |

### Web

| Module | Description |
|--------|-------------|
| `web/http-kit/` | HTTP server base functions |
| `web/reitit/` | Reitit router base functions |
| `web/server/` | Web server utilities |

### System & Runtime

| Module | Description |
|--------|-------------|
| `system/` | Integrant lifecycle management, Aero config loading |
| `executor_runtime/` | Main entry point (-main), shutdown hooks |
| `logging/` | Structured logging with MDC context |

### Versioning (Optional)

| Module | Description |
|--------|-------------|
| `versioning/storage/` | Version tracking decorator |
| `schema/versioned/` | Versioning schema extensions |

## Documentation

| Document | Description |
|----------|-------------|
| [PHILOSOPHY.md](docs/PHILOSOPHY.md) | Core principles and design philosophy |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design decisions, data model, constraints, execution model |
| [ROADMAP.md](docs/ROADMAP.md) | Implementation status, phases, future plans |
| [CONSTRAINTS.md](docs/CONSTRAINTS.md) | Graph constraint specifications |
| [ERROR_CODES.md](docs/ERROR_CODES.md) | Error types reference |
| [EXTENDING.md](docs/EXTENDING.md) | Guide for adding new storage backends |
| [CONFIGURATION.md](docs/CONFIGURATION.md) | Integrant config and Aero tags |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Docker, uberjar, environment setup |

## Project Structure

```
graphden/
├── src/graphden/            # Source code
│   ├── executor/            # Executor, base-fns, registry, composition
│   ├── executor_runtime/    # Main entry point (-main, shutdown hooks)
│   ├── logging/             # Structured logging with MDC
│   ├── schema/              # Protocol, malli, graph, versioned, traits
│   ├── storage/             # Protocol, postgres, AGE
│   ├── system/              # Integrant lifecycle management
│   ├── versioning/          # Storage, merge protection
│   └── web/                 # HTTP-kit, reitit, server, editor, CRUD
├── test/graphden/           # Tests (mirrors src structure)
├── docs/                    # Documentation
├── resources/               # Config files (config.edn, logback.xml)
├── bb.edn                   # Babashka tasks
└── deps.edn                 # Dependencies
```

## Testing

```bash
bb test                    # Run all tests
bb coverage                # Tests with coverage report
open target/coverage/index.html
```

Current: **667 tests, 90% forms / 95% lines coverage**

## License

GNU Affero General Public License v3.0 (AGPL-3.0).
See [LICENSE](LICENSE).

For commercial licensing: licensing@graphden.dev
