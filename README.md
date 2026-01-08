# Graphden

[![Clojure](https://img.shields.io/badge/Clojure-1.12-blue.svg)](https://clojure.org/)
[![Polylith](https://img.shields.io/badge/architecture-Polylith-purple.svg)](https://polylith.gitbook.io/)
[![Coverage](https://img.shields.io/badge/coverage-95%25-brightgreen.svg)](#testing)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

**Visual functional programming environment and distributed execution runtime** — a function graph stored in a database.

## Vision

Graphden is an experimental platform where:

- **Code = Graph in DB** — functions and their compositions are stored as structured data
- **Visual Editing** — graphical interface instead of text
- **Currying via Inheritance** — partial function application through parent chains
- **Lazy Execution** — only computes what's needed
- **Distributed Execution** — automatic parallelization and distribution of computations across multiple executors

**Goals**:
1. Test the hypothesis that graph-based visual programming can be simpler and more readable than text code for high-level logic
2. Leverage the graph structure for automatic parallelization — independent subgraphs can be computed concurrently on different executors

See [Architecture](docs/ARCHITECTURE.md) for detailed design decisions and technical documentation.

## Quick Start

### Requirements

- Java 21+
- Clojure 1.12+
- [Babashka](https://github.com/babashka/babashka)

### Commands

```bash
bb repl      # Start REPL
bb check     # All linters + tests (parallel)
bb test      # Tests only
bb coverage  # Tests with coverage report
bb tasks     # Show all tasks
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│              graph-with-base-fns-*                          │
│  (complete stack: storage + schema + executor + base-fns)  │
├─────────────────────────────────────────────────────────────┤
│                    graph-storage-*                          │
│  (memory, postgres, datomic) — storage + graph schema      │
├─────────────────────────────────────────────────────────────┤
│  executor  │  base-functions  │  fn-registry              │
├────────────┴─────────────────┬┴───────────────────────────┤
│         graph-data-schema    │     malli-data-schema      │
├──────────────────────────────┴────────────────────────────┤
│ storage-protocol │ data-schema-protocol │ field-types     │
├──────────────────┴───────────┬──────────┴─────────────────┤
│     memory/postgres/datomic-storage                       │
└──────────────────────────────────────────────────────────┘
```

## Components

### Core

| Component | Description |
|-----------|-------------|
| [storage-protocol](components/storage-protocol/) | Storage, CRUD, GraphConstraints protocols |
| [data-schema-protocol](components/data-schema-protocol/) | DataSchema protocol |
| [field-types](components/field-types/) | Supported data types |
| [malli-data-schema](components/malli-data-schema/) | Malli schema builder |
| [graph-data-schema](components/graph-data-schema/) | Function graph schema |

### Storage Backends

| Component | Description |
|-----------|-------------|
| [memory-storage](components/memory-storage/) | In-memory (tests, development) |
| [postgres-storage](components/postgres-storage/) | PostgreSQL (production) |
| [datomic-storage](components/datomic-storage/) | Datomic (immutable history) |

### Execution

| Component | Description |
|-----------|-------------|
| [executor](components/executor/) | Graph executor with thunks and protection |
| [base-functions](components/base-functions/) | 55+ base functions (arithmetic, collections, HOF) |
| [fn-registry](components/fn-registry/) | Base function registration and sync |

### Ready-to-use Bundles

| Component | Description |
|-----------|-------------|
| [graph-storage-*](components/graph-storage-memory/) | Storage + graph schema |
| [graph-with-base-fns-*](components/graph-with-base-fns-memory/) | Complete stack |

## Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design decisions, data model, constraints, execution model |
| [ROADMAP.md](docs/ROADMAP.md) | Implementation status, phases, future plans |
| Component READMEs | Each component has its own README with API docs |

## Project Structure

```
graphden/
├── components/           # Polylith components
│   ├── storage-protocol/
│   ├── *-storage/        # Storage implementations
│   ├── graph-data-schema/
│   ├── executor/
│   └── base-functions/
├── docs/
│   └── ARCHITECTURE.md   # Technical documentation
├── development/          # Development project
├── bb.edn               # Babashka tasks
└── deps.edn             # Dependencies
```

## Testing

```bash
bb test
bb coverage
open target/coverage/index.html
```

Current coverage: **95% forms / 98% lines**

## License

GNU Affero General Public License v3.0 (AGPL-3.0).
See [LICENSE](LICENSE).

For commercial licensing: licensing@graphden.dev
