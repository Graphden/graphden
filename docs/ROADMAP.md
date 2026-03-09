# Graphden Roadmap

> **Last updated:** 2026-03-09
>
> This document tracks implementation status and future plans.
> For technical architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| GraphConstraints Protocol | Done | No-cycle validation |
| StorageCRUD Protocol | Done | + Batch operations |
| Executor with Delays | Done | Lazy evaluation via Clojure delays |
| Base Functions | Partial | 50+ functions done; I/O server done |
| PostgreSQL Storage | Done | Full protocol support |
| Apache AGE Storage | Done | Graph queries via Cypher |
| Base Function impl-hash | Done | SHA-256 hash for version tracking |
| Integrant System | Done | Component lifecycle management |
| Logging | Done | Structured logging with MDC |
| Web Server | Done | HTTP-kit + Reitit router |
| Versioning | Done | VersionedStorage decorator |
| 2-Entity Schema | Done | fn + arg model |
| REST API | Planned | Phase 5 |
| Web UI | Planned | Phase 5 |
| Type System | Planned | Future work |
| Permissions | Planned | Future work |

---

## Phase 0: Documentation [DONE]

- Main README.md
- ARCHITECTURE.md document
- CONSTRAINTS.md, ERROR_CODES.md, EXTENDING.md
- This ROADMAP.md document

---

## Phase 1: Data Schema and Constraints [DONE]

**1.1 graph-data-schema** - 2-entity model:
- `fn` — function entity (parent-id=nil for base-fn, parent-id set for composed)
- `arg` — argument entity with value/ref-id, source-id for inheritance, is-fn for HOF

**1.2 GraphConstraints protocol** - Validators:
- No dependency cycles via ref-id references
- Unique fn name (NULL allowed for local functions)
- Unique arg per fn + source-id combination
- Unique arg name within fn

**1.3 Contract tests** - Comprehensive test coverage

**1.4 Storage implementations** - PostgreSQL + Apache AGE

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

**3.2 Lazy Evaluation:**
Arguments are wrapped in Clojure `delay` objects:
- Literal values → immediate delay
- ref-id references → delay that executes function
- is-fn=true refs → delay returning fn-id (for HOF)

**3.3 Result Caching:**
- Results cached by ref-id within execution context
- Same ref-id in multiple args → function executes once

**3.4 Executor:**
- `execute` - Main entry point
- `execute-with-named-args` - Execute with named arguments
- `execute-by-name` - Execute by function name
- Depth/timeout protection
- Inheritance resolution via parent-id chain

**3.5 fn-registry component:**
- `register-base-fns!` - Register implementations
- `sync-defs-to-storage!` - Sync schemas to storage
- Deterministic UUID generation for idempotent operations

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

**Solution**: New entity `free-arg-alias` linking fn + arg to a display name.

**Schema:**
```
free-arg-alias:
  id: uuid (PK)
  fn-id: ref<fn>
  arg-id: ref<arg>
  alias: text (human-readable name)
  UNIQUE(fn-id, arg-id)
```

**Lifecycle:**
- Created manually via UI when naming free arguments
- **Auto-deleted** when the argument gets a value
- This ensures aliases only exist for truly "free" arguments

**UI Usage:**
```
Execute function: calculate-report
┌─────────────────────────────────────┐
│ Sales Region: [_______________]     │  ← alias for arg
│ Start Date:   [_______________]     │  ← alias for arg
│ Currency:     [USD v]               │  ← alias for arg
└─────────────────────────────────────┘
```

**Complexity**: Low-Medium.

---

### Type System (Type Algebra)

**Goal**: Static type checking, UI hints, automatic type inference.

**What's needed:**
- Types for functions (input types -> output type)
- Parametric polymorphism (List[T], Map[K,V])
- Type inference for compositions (Hindley-Milner or subset)
- Types for HOF: `map : (a -> b) -> List[a] -> List[b]`

**Complexity**: High. This is a separate large project.

---

### Base Function Version Tracking [DONE]

**Goal**: Detect when base function implementations change to enable safe upgrades.

**Implementation:**
- `impl-hash` field in `fn` entity (SHA-256 hash)
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

---

### Git-like Versioning [DONE]

**Goal**: Change history, rollback, branches, merge for function graphs.

**Design decisions:**

| Decision | Choice | Reasoning |
|----------|--------|-----------|
| Versioning pattern | Two-table: stable identity (id only) + version table (data + branch_id + created_at) | Clean separation of identity from mutable state |
| History model | Append-only version records | No data loss; current version = latest by created_at |
| Unique constraint | Non-unique (entity_id, branch_id) — multiple records per entity per branch | Enables full history without separate history tables |
| Branch model | Branch table with base_branch_id for inheritance chain | Resolution walks up the chain until version found |
| Merge mechanism | `branch_merge` table (no record duplication) | Single merge record makes source versions visible in target |
| Conflict detection | Git-style: entity modified in both branches after fork point | User resolves: take source / take target / custom |

**What is versioned (graph structure changes):**

| Entity | Versioned? | What changes |
|--------|-----------|--------------|
| `fn` | Yes (fn + fn_version) | name, parent-id, return-type, impl-hash |
| `arg` | Yes (arg + arg_version) | name, type, required, value, ref-id, is-fn, source-id |

**Branch operations:**

| Operation | Mechanism |
|-----------|-----------|
| Create branch | Insert into `branch` with `base_branch_id` |
| Edit on branch | Append new version record with branch_id |
| Read on branch | Walk branch chain upward; check branch_merge records for merged versions |
| Merge B into A | Insert `branch_merge(source=B, source_timestamp=now, target=A, target_timestamp=now)`. No records copied. Detect conflicts: entity modified in both B and A after fork point |
| Conflict resolution | User chooses: take source version / take target version / create custom version |
| Delete branch | Forbid if child branches exist; delete all version records and branch_merge records, then branch |

**Branch resolution at execution time:**
- Executor context carries `branch_id`
- `resolve-execution-graph` resolves each versioned entity by walking branch chain + branch_merge records
- No branch specified = default branch (base_branch_id = NULL)
- Branch is transparent to functions — they don't know which branch they're on

**Component architecture:**
- `versioned-storage` — independent component (storage decorator)
- Any combination works: base only, versioned only
- Executor works with any storage through unified `ExecutionGraph` protocol — no executor changes needed

**Base function update strategy:**
- Platform migrations update base-fns on a platform branch
- Users' branches don't see the change until they merge
- Compatible changes (new optional arg): single base fn name, Clojure code supports both old and new signatures
- Breaking changes (removed arg, changed type): register new fn name (e.g., `map-v2`), old code remains functional
- Implementation-only changes (bug fix, same signature): all users get the new code automatically (Clojure runtime is shared), `impl_hash` updated on platform branch

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
