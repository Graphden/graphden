# Graphden

[![Clojure](https://img.shields.io/badge/Clojure-1.12-blue.svg)](https://clojure.org/)
[![Polylith](https://img.shields.io/badge/architecture-Polylith-purple.svg)](https://polylith.gitbook.io/)
[![Coverage](https://img.shields.io/badge/coverage-96%25-brightgreen.svg)](#testing)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

**Visual functional programming environment** — a function graph stored in a database.

## Vision

Graphden is an experimental platform where:

1. **Code = Graph in DB** — functions and their compositions are stored as structured data
2. **Visual Editing** — graphical interface instead of text
3. **Currying via Inheritance** — partial function application through parent chains
4. **Lazy Execution** — only computes what's needed

**Goal**: Test the hypothesis that graph-based visual programming can be simpler and more readable than text code for high-level logic.

## Key Concepts

### Entities

| Entity | Description |
|--------|-------------|
| `fn-schema` | Function schema (name, argument types, return type) |
| `arg-schema` | Function argument schema |
| `fn` | Function instance (can inherit from a parent) |
| `arg-value` | Argument value (literal or reference to another fn) |

### Inheritance

```
fn-schema: http-request
  args: [url, method, headers, body, timeout]

fn: base-api (parent: null)
  arg-values: {url: "https://api.example.com", timeout: 30}

fn: auth-api (parent: base-api)
  arg-values: {headers: {"Authorization": "..."}}
  -> inherits: url, timeout

fn: create-user (parent: auth-api)
  arg-values: {method: "POST", body: {...}}
  -> inherits: url, timeout, headers
```

### Execution Model

- **Laziness** — arguments are wrapped in thunks, evaluated on demand
- **HOF Support** — functions like `map`, `filter` receive fn-id, not the result
- **Protection** — recursion depth limit and timeout

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    graph-storage-*                          │
│  (memory, postgres, datomic) — ready to use                │
├─────────────────────────────────────────────────────────────┤
│                   graph-data-schema                         │
│  (fn-schema, arg-schema, fn, arg-value)                    │
├─────────────────┬─────────────────┬─────────────────────────┤
│ storage-protocol│ data-schema-prot│     field-types        │
│ + *-storage     │ + malli-impl    │                        │
└─────────────────┴─────────────────┴─────────────────────────┘
```

## Components

### Protocols and Schemas

| Component | Description | README |
|-----------|-------------|--------|
| [storage-protocol](components/storage-protocol/) | Storage, StorageCRUD, GraphConstraints protocols | [->](components/storage-protocol/README.md) |
| [data-schema-protocol](components/data-schema-protocol/) | DataSchema protocol, field types | [->](components/data-schema-protocol/README.md) |
| [field-types](components/field-types/) | Supported data types | [->](components/field-types/README.md) |
| [malli-data-schema](components/malli-data-schema/) | Malli schema implementation | [->](components/malli-data-schema/README.md) |
| [graph-data-schema](components/graph-data-schema/) | Function graph schema | [->](components/graph-data-schema/README.md) |

### Storage Implementations

| Component | Description | README |
|-----------|-------------|--------|
| [memory-storage](components/memory-storage/) | In-memory storage | [->](components/memory-storage/README.md) |
| [postgres-storage](components/postgres-storage/) | PostgreSQL storage | [->](components/postgres-storage/README.md) |
| [datomic-storage](components/datomic-storage/) | Datomic storage | [->](components/datomic-storage/README.md) |

### Ready-to-use Combinations (storage + graph-data-schema)

| Component | Description | README |
|-----------|-------------|--------|
| [graph-storage-memory](components/graph-storage-memory/) | In-memory, ready to use | [->](components/graph-storage-memory/README.md) |
| [graph-storage-postgres](components/graph-storage-postgres/) | PostgreSQL, ready to use | [->](components/graph-storage-postgres/README.md) |
| [graph-storage-datomic](components/graph-storage-datomic/) | Datomic, ready to use | [->](components/graph-storage-datomic/README.md) |

### Execution

| Component | Description | README |
|-----------|-------------|--------|
| [executor](components/executor/) | Function graph executor (thunks, recursion, timeouts) | [->](components/executor/README.md) |
| [base-functions](components/base-functions/) | Base functions (arithmetic, strings, collections, HOF) | [->](components/base-functions/README.md) |

## Documentation

- **[Architecture](docs/ARCHITECTURE.md)** — detailed system description, decisions, and limitations

## Requirements

- Java 21+
- Clojure 1.12+
- [Babashka](https://github.com/babashka/babashka)

### Optional (for linters)

```bash
brew install clj-kondo cljstyle
```

## Quick Start

```bash
# Start REPL
bb repl

# Run all checks (linters + tests)
bb check

# Run tests only
bb test

# Run with coverage report
bb coverage
```

## Development

### Available Tasks

```bash
bb tasks  # Show all tasks
```

| Task | Description |
|------|-------------|
| `bb check` | All linters + all tests (parallel) |
| `bb lint` | Linters only |
| `bb test` | Tests only |
| `bb coverage` | Tests with coverage report |
| `bb repl` | Start nREPL |

### Linters

```bash
bb kondo [path]     # Static analysis (clj-kondo)
bb splint [path]    # Style and idioms
bb cljstyle [path]  # Formatting
bb fix [path]       # Auto-fix formatting
```

### Utilities

```bash
bb outdated   # Check outdated dependencies
bb security   # Scan for CVEs
bb clean      # Clean generated files
bb info       # Polylith workspace info
bb deps       # Component dependencies
```

## Testing

```bash
bb test
bb coverage
open target/coverage/index.html
```

Current coverage: **96% forms / 98% lines**

## Project Structure

```
graphden/
├── bb.edn                 # Babashka tasks
├── deps.edn               # Clojure dependencies
├── workspace.edn          # Polylith configuration
├── docs/
│   └── ARCHITECTURE.md    # Architecture documentation
├── components/
│   ├── storage-protocol/
│   ├── data-schema-protocol/
│   ├── field-types/
│   ├── malli-data-schema/
│   ├── graph-data-schema/
│   ├── memory-storage/
│   ├── postgres-storage/
│   ├── datomic-storage/
│   └── graph-storage-*/   # Ready-to-use combinations
└── development/           # Development project
```

## Development Status

### Implemented

- [x] Storage protocol (initialization, introspection)
- [x] DataSchema protocol (entities, fields, validation)
- [x] Malli schema implementation
- [x] Function graph schema (fn-schema, fn, arg-value)
- [x] Memory storage
- [x] PostgreSQL storage
- [x] Datomic storage
- [x] CRUD operations (StorageCRUD protocol)
- [x] GraphConstraints protocol (graph constraint validation)
- [x] Inheritance (parent-fn-id, parent chain)
- [x] Executor (with thunks, recursion protection, and timeouts)

### In Progress

- [ ] Base functions (add, if, map, filter, etc.)
- [ ] REST API
- [ ] Web interface

### Future Plans

- [ ] Type system (type algebra)
- [ ] Git-like versioning
- [ ] User and permission system

## License

GNU Affero General Public License v3.0 (AGPL-3.0).
See [LICENSE](LICENSE).

For commercial licensing: licensing@graphden.dev
