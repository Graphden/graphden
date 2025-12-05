# Graphden

[![Clojure](https://img.shields.io/badge/Clojure-1.12-blue.svg)](https://clojure.org/)
[![Polylith](https://img.shields.io/badge/architecture-Polylith-purple.svg)](https://polylith.gitbook.io/)
[![Coverage](https://img.shields.io/badge/coverage-91%25-brightgreen.svg)](#testing)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

A functional, extensible low-code graph platform built with Clojure.

Graphden provides a graph-based data structure for modeling function composition with inheritance. Nodes can inherit arguments from parent nodes, and the system efficiently tracks relationships and derived data through an eager caching strategy.

## Features

- **Graph-based function composition** — Nodes represent functions with arguments that can inherit from parent nodes
- **Pluggable architecture** — Swap storage, schema validation, and caching implementations
- **Eager caching** — Derived queries (root ancestor, full args, children) are pre-computed on mutations
- **Schema validation** — Validate node data using Malli schemas
- **Built with Polylith** — Clean component boundaries and explicit dependencies

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         graph                                │
│  (orchestrates storage, schema, cache)                      │
├─────────────────┬─────────────────┬─────────────────────────┤
│     storage     │     schema      │         cache           │
│   (protocol)    │   (protocol)    │       (protocol)        │
├─────────────────┼─────────────────┼─────────────────────────┤
│ storage-memory  │  schema-malli   │      cache-eager        │
│ (in-memory impl)│  (Malli impl)   │   (eager update impl)   │
└─────────────────┴─────────────────┴─────────────────────────┘
```

### Components

| Component | Description |
|-----------|-------------|
| `graph` | Core graph operations, coordinates other components |
| `storage` | Storage protocol (CRUD operations) |
| `storage-memory` | In-memory storage using atoms |
| `schema` | Schema validation protocol |
| `schema-malli` | Malli-based schema implementation |
| `cache` | Caching strategy protocol |
| `cache-eager` | Eager caching — updates derived data on every mutation |

## Requirements

- Java 21+
- Clojure 1.12+
- [Babashka](https://github.com/babashka/babashka) (for task running)

### Optional (for faster linting)

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

## Development Workflow

### Available Tasks

```bash
bb tasks  # Show all available tasks
```

| Task | Description |
|------|-------------|
| `bb check` | Run everything: all linters + all tests (parallel) |
| `bb check --seq` | Same, but sequential |
| `bb lint` | Run all linters (parallel) |
| `bb test` | Run all tests |
| `bb coverage` | Run tests with coverage report |
| `bb repl` | Start nREPL |

### Individual Linters

```bash
bb kondo [path]     # Static analysis (clj-kondo)
bb splint [path]    # Style & idiom checks
bb cljstyle [path]  # Formatting check
bb fix [path]       # Auto-fix formatting
```

### Utilities

```bash
bb outdated   # Check for outdated dependencies
bb security   # Scan for CVE vulnerabilities
bb clean      # Clean generated files
bb info       # Show Polylith workspace info
bb deps       # Show component dependencies
```

## Usage Example

```clojure
(require '[graphden.graph.interface :as graph])

;; Create a graph (typically done via Integrant)
(def g (create-graph ...))

;; Add a base node
(graph/add-node g {:node-name :base
                   :args [{:arg-name :x :arg-val 1}
                          {:arg-name :y :arg-val 2}]})

;; Add a child that inherits from base
(graph/add-node g {:node-name :child
                   :parent-name :base
                   :args [{:arg-name :z :arg-val 3}]})

;; Query derived data
(graph/get-root-ancestor g :child)  ;=> :base
(graph/get-full-args g :child)      ;=> {:x ... :y ... :z ...}
(graph/get-children g :base)        ;=> #{:child}

;; Modify
(graph/set-arg-value g :child :z 10)
(graph/rename-node g :child :new-child)
(graph/delete-node g :new-child)
```

## Project Structure

```
graphden/
├── bb.edn                 # Babashka tasks
├── deps.edn               # Clojure dependencies
├── workspace.edn          # Polylith workspace config
├── components/
│   ├── graph/             # Core graph component
│   ├── storage/           # Storage protocol
│   ├── storage-memory/    # In-memory implementation
│   ├── schema/            # Schema protocol
│   ├── schema-malli/      # Malli implementation
│   ├── cache/             # Cache protocol
│   └── cache-eager/       # Eager cache implementation
├── bases/
│   └── dev/               # Development base
└── development/           # Development project
```

## Testing

```bash
# Run all tests
bb test

# Run with coverage (generates HTML report)
bb coverage
open target/coverage/index.html
```

Current coverage: **91% forms / 99% lines**

## Configuration

The system is configured via Integrant. Example configuration:

```clojure
{:graphden.schema-malli.core/provider
 {:schemas {:node [:map
                   [:node-name :keyword]
                   [:parent-name {:optional true} [:maybe :keyword]]
                   [:args [:vector [:map
                                    [:arg-name :keyword]
                                    [:arg-val :any]]]]]}}

 :graphden.storage-memory.core/storage
 {:initial-data {}}

 :graphden.cache-eager.core/cache
 {}

 :graphden.graph.core/graph
 {:schema-provider (ig/ref :graphden.schema-malli.core/provider)
  :storage (ig/ref :graphden.storage-memory.core/storage)
  :cache (ig/ref :graphden.cache-eager.core/cache)}}
```

## License

Graphden is available under the GNU Affero General Public License v3.0 (AGPL-3.0).  
See [LICENSE](LICENSE) for the full text.

In short, you are free to use, study, modify, and run Graphden, including in commercial environments, as long as:

- any modified version that you deploy to users over a network also makes its source code available to those users;
- your derivative works remain under AGPL-3.0.

If you want to use Graphden in a closed-source product or SaaS without the obligations of AGPL-3.0, commercial licenses are available.  
For commercial licensing, please contact: licensing@graphden.dev
