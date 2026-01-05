# Graphden Roadmap

> **Last updated:** 2026-01-05
>
> This document tracks implementation status and future plans.
> For technical architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| GraphConstraints Protocol | Done | All 5 validators |
| StorageCRUD Protocol | Done | + Batch operations |
| Executor with Thunks | Done | + Graph caching |
| Base Functions | Partial | Arithmetic, logic, HOF done; I/O pending |
| Memory Storage | Done | Full protocol support |
| PostgreSQL Storage | Done | Full protocol support |
| Datomic Storage | Done | Full protocol support |
| REST API | Planned | Phase 5 |
| Web UI | Planned | Phase 5 |
| Type System | Planned | Future work |
| Versioning | Planned | Future work |
| Permissions | Planned | Future work |

---

## Phase 0: Documentation [DONE]

- Main README.md
- Component READMEs for all 16 components
- ARCHITECTURE.md document
- This ROADMAP.md document

---

## Phase 1: Data Schema and Constraints [DONE]

**1.1 graph-data-schema** - All entities with all fields:
- `fn-schema` with `base-fn-name`
- `arg-schema` with `required`
- `fn` with `parent-fn-id`
- `arg-value` with union types

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
- LiteralThunk
- FnRefThunk
- LazyFnThunk

**3.3 Executor:**
- `execute` - Main entry point
- `execute-with-named-args` - Execute with named arguments
- `execute-by-name` - Execute by function name
- Depth/timeout protection

**3.4 fn-registry component:**
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

### Type System (Type Algebra)

**Goal**: Static type checking, UI hints, automatic type inference.

**What's needed:**
- Types for fn-schema (input types -> output type)
- Parametric polymorphism (List[T], Map[K,V])
- Type inference for compositions (Hindley-Milner or subset)
- Types for HOF: `map : (a -> b) -> List[a] -> List[b]`

**Complexity**: High. This is a separate large project.

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
