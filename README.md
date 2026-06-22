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

   ;; Build route map: {"handler" <handler-result>}
   ;; :hello-handler-fn is EXECUTED here — assoc's :v slot is not :fn-typed,
   ;; so the executor evaluates the ref and uses its result.
   {:name :hello-route-data-fn
    :parent :assoc
    :args {:m {}, :k "handler", :v :hello-handler-fn}}

   ;; Create router from routes
   {:name :router-fn
    :parent :router
    :args {:routes [["/" {:get :hello-route-data-fn}]]}}

   ;; Start HTTP server
   ;; :router-fn is PASSED AS A FUNCTION here — http-server's :handler
   ;; slot is :fn-typed, so the executor hands the fn-id over instead
   ;; of executing it.
   {:name :web-server-fn
    :parent :http-server
    :args {:handler :router-fn
           :port 8080}}])

;; Reference syntax: :fn-name creates a ref to another fn.
;; Whether the executor executes the ref and uses its result, or
;; passes the fn-id directly (HOF callable), is determined by the
;; SLOT TYPE: `:fn`-typed slots receive the fn-id; everything else
;; gets the executed result. One concept — the type chip in the
;; editor — drives both the UI and the dispatch.
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

### Linter prerequisites

Most linters install themselves on first run (no host setup
required):

- **Clojure** — clj-kondo / splint / cljstyle: pulled by `setup-clojure`
  in CI; locally either install native binaries (`brew install
  clj-kondo cljstyle`, etc.) or let `bb check` fall through to the
  `clojure -M:kondo` / `-M:splint` / `-M:cljstyle` aliases.
- **JS + CSS + markdown** — biome / stylelint / markdownlint-cli2:
  npm devDeps in `package.json`; run `npm install` once after clone.
- **shell / Dockerfile / secrets** — shellcheck / hadolint / gitleaks:
  pulled as Docker images on first `bb shellcheck` / `bb hadolint` /
  `bb gitleaks`. Requires Docker (already needed for the executor
  and Postgres containers).
- **Spelling** — `typos`: `bb typos` downloads the binary into
  `.tools/typos` on first run (Linux x86_64 release tarball, pinned
  version). `.tools/` is gitignored.

For day-to-day work: `npm install`, then `bb check` runs every
linter. CI runs the same set on every PR.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    EXECUTOR LAYER                           │
│  executor + base-functions + fn-registry + fn-composition   │
├─────────────────────────────────────────────────────────────┤
│                     STORAGE LAYER                           │
│  storage-protocol: StorageCRUD, ExecutionGraph, Constraints │
│  postgres-storage + VersionedStorage decorator (optional)   │
├─────────────────────────────────────────────────────────────┤
│                  DATA SCHEMA LAYER                          │
│  schemas: malli-data-schema, graph-data-schema              │
│          (fn / slot / fn-slot / binding /                   │
│           binding-list-item),                               │
│          versioned-data-schema (branch + version tables)    │
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
| `schema/graph/` | Function graph entity schema (fn / slot / fn-slot / binding / binding-list-item) |

### Storage

| Module | Description |
|--------|-------------|
| `storage/postgres/` | PostgreSQL storage backend |

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
| [DISTRIBUTION.md](docs/DISTRIBUTION.md) | License layout, deployment shapes, packages model, competitors, feature acceptance rules |
| [tutorial/](docs/tutorial/) | Step-by-step lessons for new users — grows with every feature block |
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

Coverage is tracked per build — run `bb coverage` and open
`target/coverage/index.html` for the current breakdown. The badge at
the top of this file shows the latest CI value.

## License

GNU Affero General Public License v3.0 (AGPL-3.0).
See [LICENSE](LICENSE).

For commercial licensing: licensing@graphden.dev
