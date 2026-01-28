# Graphden Roadmap

> **Last updated:** 2026-01-10
>
> This document tracks implementation status and future plans.
> For technical architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| GraphConstraints Protocol | Done | All 5 validators |
| StorageCRUD Protocol | Done | + Batch operations |
| Executor with Thunks | Done | + Graph caching |
| Base Functions | Partial | 50+ functions done; I/O pending |
| Memory Storage | Done | Full protocol support |
| PostgreSQL Storage | Done | Full protocol support |
| Datomic Storage | Done | Full protocol support |
| Execution Graph Caching | Done | O(1) resolution, auto-invalidation |
| Base Function impl-hash | Done | SHA-256 hash for version tracking |
| REST API | Planned | Phase 5 |
| Web UI | Planned | Phase 5 |
| Type System | Planned | Future work |
| Versioning | Partial | impl-hash done; full system planned |
| Permissions | Planned | Future work |

---

## Phase 0: Documentation [DONE]

- Main README.md
- Component READMEs for all 23 components
- ARCHITECTURE.md document (with caching section)
- CONSTRAINTS.md, ERROR_CODES.md, EXTENDING.md
- This ROADMAP.md document

---

## Phase 1: Data Schema and Constraints [DONE]

**1.1 graph-data-schema** - All entities with all fields:
- `fn-schema` with `base-fn-name`
- `arg-schema` with `required`
- `fn` (function instance)
- `call-site` for cached computation results
- `arg-value` with union types (including refs to call-site)

**1.2 GraphConstraints protocol** - All 5 validators

**1.3 Contract tests** - Comprehensive test coverage

**1.4 Storage implementations** - All 3 backends

---

## Phase 2: CRUD Operations [DONE]

**2.1 StorageCRUD protocol:**
```clojure
(defprotocol StorageCRUD
  (create-entity [this entity-name data])
  (read-entity [this entity-name id])
  (update-entity [this entity-name id data])
  (delete-entity [this entity-name id])
  (query-entities [this entity-name where]))
```

**2.2 StorageBatchCRUD protocol (enhancement):**
```clojure
(defprotocol StorageBatchCRUD
  (create-entities [this entity-name data-seq])
  (read-entities [this entity-name ids])
  (delete-entities [this entity-name ids]))
```

---

## Phase 3: Executor [DONE]

**3.1 ExecutionGraph protocol:**
```clojure
(defprotocol ExecutionGraph
  (resolve-execution-graph [this root-fn-id]))
```

**3.2 Thunk types:**
- LiteralThunk - literal values
- FnRefThunk - direct function references (execute each time)
- FnResultValueThunk - cached computation references (execute once)
- LazyFnThunk - HOF function references (pass as value)

**3.3 Executor:**
- `execute` - Main entry point
- `execute-with-named-args` - Execute with named arguments
- `execute-by-name` - Execute by function name
- Depth/timeout protection
- `call-site-args` for runtime free argument values (keyed by arg-schema-id or [call-site-id arg-schema-id])

**3.4 fn-registry component:**
- `register-base-fns!` - Register implementations
- `sync-defs-to-storage!` - Sync schemas to storage
- Deterministic UUID generation for idempotent operations

---

## Phase 3.5: Execution Graph Caching [DONE]

**Goal**: O(1) graph resolution instead of O(depth) recursive queries.

**3.5.1 CacheStorage protocol:**
```clojure
(defprotocol CacheStorage
  (get-cached-graph [this fn-id])
  (save-cache! [this fn-id graph dependencies])
  (delete-cache! [this fn-id])
  (find-caches-by-fn-dep [this dep-fn-id])
  (find-caches-by-fn-schema-dep [this dep-fn-schema-id])
  (find-caches-by-arg-schema-dep [this dep-arg-schema-id]))
```

**3.5.2 Components:**
- `cache-protocol` - Protocol definition and utilities
- `cache-data-schema` - Schema for cache storage
- `cache-memory` - In-memory implementation
- `cache-postgres` - PostgreSQL implementation
- `cache-datomic` - Datomic implementation
- `cached-storage` - Decorator with automatic invalidation

**3.5.3 Features:**
- Dependency tracking with ref-counts
- Automatic invalidation on entity changes
- Extensible via multimethods

---

## Phase 4: Base Functions [PARTIAL]

**4.1 Arithmetic and Strings [DONE]**
- Arithmetic: `add`, `sub`, `mul`, `div`, `mod`, `neg`, `abs`
- Comparison: `eq`, `neq`, `lt`, `lte`, `gt`, `gte`
- Strings: `str`, `subs`, `str-len`, `str-upper`, `str-lower`, `str-trim`, `str-split`, `str-join`

**4.2 Collections [DONE]**
- Basic: `first`, `rest`, `cons`, `conj`, `get`, `assoc`, `dissoc`
- Advanced: `count`, `empty?`, `contains?`, `keys`, `vals`, `merge`, `into`
- Sequences: `range`, `repeat`, `take`, `drop`, `reverse`, `sort`, `concat`, `flatten`, `distinct`

**4.3 Conditionals and HOF [DONE]**
- Logic: `and`, `or`, `not` (with short-circuit for `and`/`or`)
- Conditionals: `if`, `cond`
- HOF: `map`, `filter`, `reduce`, `some`, `every?`, `find-first`, `group-by`, `sort-by`, `apply`
- Utilities: `identity`, `constantly`

**4.4 I/O (Client) [PLANNED]**
- `http-request` - HTTP client (http-kit)
- File operations

**4.5 I/O (Server) [PLANNED]**
- `http-server` - HTTP server (http-kit)
- Service Manager for long-lived processes

**Service Manager design:**
```clojure
(defprotocol ServiceManager
  (start-service [this service-fn-id])
  (stop-service [this instance-id])
  (list-services [this])
  (service-status [this instance-id]))
```

---

## Phase 5: UI/API [PLANNED]

**5.1 REST API**
- CRUD endpoints for all entities
- POST /execute - run function
- WebSocket for live updates

**5.2 Web Interface**
- Function list
- Graph editor (visual node-based)
- Execute button with results

---

## Future Work

### Distributed Execution

**Goal**: Automatic parallelization and distribution of computations across multiple executors.

**Why it's possible**: Graph structure explicitly represents dependencies. Independent subgraphs can be identified and computed in parallel without manual annotation.

**Phases:**

| Phase | Description | Complexity |
|-------|-------------|------------|
| 6.1 Local Parallelism | Execute independent args in parallel threads (same JVM) | Medium |
| 6.2 Worker Pool | Offload to worker processes on same machine | Medium |
| 6.3 Distributed Workers | Remote executors with network transport | High |
| 6.4 Smart Partitioning | Cost-based optimizer for graph partitioning | High |

**Key decisions needed:**
- Data transfer strategy between executors (direct, via coordinator, via storage)
- Granularity of distribution (coarse vs fine-grained)
- Handling side effects and ordering guarantees
- Failure handling and retry strategy

**See:** [ARCHITECTURE.md - Distributed Execution](ARCHITECTURE.md#part-8-distributed-execution-future)

---

### Free Argument Aliases (UI-friendly names)

**Goal**: Human-readable names for free arguments in execution forms.

**Problem**: When executing a function with free arguments via UI, users see technical identifiers (UUIDs). We need human-readable aliases.

**Solution**: New entity `free-arg-alias` linking `call-site` + `arg-schema` to a display name.

**Schema:**
```
free-arg-alias:
  id: uuid (PK)
  call-site-id: ref<call-site>
  arg-schema-id: ref<arg-schema>
  alias: text (human-readable name)
  UNIQUE(call-site-id, arg-schema-id)
```

**Lifecycle:**
- Created manually via UI when naming free arguments
- **Auto-deleted** when the argument gets a value (arg-value is created for the referenced function)
- This ensures aliases only exist for truly "free" arguments

**UI Usage:**
```
Execute function: calculate-report
┌─────────────────────────────────────┐
│ Sales Region: [_______________]     │  ← alias for cs-1 + region-arg
│ Start Date:   [_______________]     │  ← alias for cs-2 + date-arg
│ Currency:     [USD v]               │  ← alias for cs-1 + currency-arg
└─────────────────────────────────────┘
```

**Implementation notes:**
- Storage constraint: validate that arg is actually free (no arg-value exists for this fn + arg-schema)
- Cascade delete via trigger/transaction function when arg-value is created
- Root function free args don't need aliases — they already have human-readable names via `arg-schema.name`

**Complexity**: Low-Medium.

---

### Type System (Type Algebra)

**Goal**: Static type checking, UI hints, automatic type inference.

**What's needed:**
- Types for fn-schema (input types -> output type)
- Parametric polymorphism (List[T], Map[K,V])
- Type inference for compositions (Hindley-Milner or subset)
- Types for HOF: `map : (a -> b) -> List[a] -> List[b]`

**Complexity**: High. This is a separate large project.

---

### Base Function Version Tracking [DONE]

**Goal**: Detect when base function implementations change to enable safe upgrades.

**Implementation (Phase 1):**
- `impl-hash` field in `fn-schema` entity (SHA-256 hash)
- `impl-source` stored in `defbase` macro output
- Hash computed from: args, return-type, impl-source (body forms)
- Canonical form normalization (sorted maps, pr-str)

**What the hash detects:**
- Function body changes
- Argument type changes
- Argument additions/removals
- Return type changes

**What the hash ignores:**
- Whitespace/formatting changes
- Comments
- Map key ordering

**Files:**
- `graph-data-schema/interface.clj` - impl-hash field
- `fn-registry/core.clj` - compute-impl-hash function
- `fn-registry/macros.clj` - impl-source in defbase

---

### Git-like Versioning

**Goal**: Change history, rollback, branches, merge.

**Model:**
- Each fn/arg-value change is a commit
- Can rollback to any version
- Branches for experiments
- Merge to combine changes

**Implementation:**
- Either event sourcing (store all changes)
- Or snapshot + diff
- Integration with real git for export/import
- Uses `impl-hash` for detecting base function changes

---

### User and Permission System

**Goal**: Access control.

**Permission Model:**
```
User:
  id, name, email

Role:
  id, name

Permission:
  - view(fn-id)      - see function
  - edit(fn-id)      - edit
  - execute(fn-id)   - execute
  - admin(fn-id)     - manage permissions

UserRole:
  user-id, role-id

RolePermission:
  role-id, permission
```

**Application:**
- On CRUD operations - permission check
- On execution - execute permission check
- In UI - filter visible functions
