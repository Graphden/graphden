# Graphden Roadmap

> This document tracks implementation status and future plans.
> For technical architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| Slot/binding schema | Done | fn / slot / fn-slot / binding / binding-list-item |
| GraphConstraints Protocol | Done | Dependency-cycle validation |
| StorageCRUD Protocol | Done | + Batch operations |
| Executor (compile-at-startup) | Done | Lazy via Clojure delays; thunks compiled once at boot |
| Base Functions | Done | Arithmetic, logic, HOF, collections, strings, system; web (http, reitit, html, crud, ring-adapter) |
| PostgreSQL Storage | Done | Full protocol support; recursive-CTE cycle walks |
| Base Function impl-hash | Done | SHA-256 hash for version tracking |
| Integrant System | Done | Component lifecycle management |
| Logging | Done | Structured logging with MDC |
| Web Server | Done | http-kit + Reitit router |
| Versioning | Done | VersionedStorage decorator (fn / fn-slot / binding / binding-list-item) |
| Type System (refinements / records / lists / unions / variants) | Done | Save-time check; rich-type registry |
| Editor UI | Done | Cytoscape-based with server-computed layout, inline edit popovers |
| REST API | Done | `/api/graph/entities`, `/api/graph/layout`, `/api/entities/<entity>/*`, `/api/sequence/append/:fn-id`, `/api/sequence/item/:item-id` |
| Permissions | Planned | Future work |
| Distributed execution | Planned | See [Distributed Execution](#distributed-execution) below |

---

## Phase 0: Documentation [DONE]

- Main README.md
- ARCHITECTURE.md document
- CONSTRAINTS.md, ERROR_CODES.md, EXTENDING.md
- This ROADMAP.md document

---

## Phase 1: Data Schema and Constraints [DONE]

**1.1 graph-data-schema** — slot/binding model:
- `fn` — function or type-row; M:N inheritance via `parent-fn-ids`
- `slot` — atomic `(name, type-fn-id)`; immutable post-create
- `fn-slot` — junction `(fn-id, slot-id, position)`
- `binding` — per-`(fn, slot)` overlay (value, ref, rename, type-override, terminal, list flags)
- `binding-list-item` — sequence content under a list-typed binding

**1.2 GraphConstraints protocol** — runtime validator:
- No dependency cycles via `binding.ref-fn-id` / `binding-list-item.ref-fn-id`

**1.3 Schema-level uniqueness:**
- `UNIQUE(fn.name)` (NULL allowed for anonymous fns)
- `UNIQUE(fn-slot(fn-id, slot-id))`
- `UNIQUE(binding(fn-id, slot-id))`
- `UNIQUE(binding-list-item(binding-id, position))`

**1.4 Contract tests** — `graphden.storage.protocol.contract-tests`
covers cycle detection + concurrent CRUD against any storage backend.

**1.5 Storage implementations** — PostgreSQL.

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
Ref bindings are wrapped as thunks (`rt/thunk`); impls deref them via
`rt/resolve-arg` (handled transparently by the `defbase` macro so
bodies use bare symbols). `:fn`-typed refs bypass the thunk and go
through `hof-wrap` — see EXTENDING.md "Higher-Order Functions".

**3.3 Result Caching:**
- Per-invocation `*call-cache*` memoises `[ref-id, free-args]` pairs
- Shared subgraphs (e.g. `:router-result` pulled from multiple slots)
  run once per top-level invocation

**3.4 Executor:**
- `execute` — execute a fn by id with named free args
- `execute-by-name` — execute a fn looked up by name
- `make-single-arg-callable` — public HOF-wrap entry for raw fn-ids
- Inheritance resolution via parent-id chain
- Compile-at-startup: registry built once, closures cached in
  `ctx :compiled-registry` atom

**3.5 fn-registry + package loader:**
- `register-base-fn!` — register a single impl
- `register-base-fns!` — bulk registration
- `sync-defs-to-storage!` — sync fn-defs from `fns.edn` to storage
- Deterministic UUID generation for idempotent operations
- Orphan fn-def reaping: entries removed from `fns.edn` are deleted
  from storage on next sync

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
- `http-request` — HTTP client (http-kit)
- File operations

**4.5 I/O (Server) [DONE]**
- `http-server` — http-kit server wrapper (`web/http`)
- Reitit-based routing (`web/reitit`): `ring-router`,
  `ring-create-default-handler`, `ring-handler`, `middleware`
- Ring-adapter: request-field extractors, response envelope,
  bearer-token auth middleware (`web/ring-adapter`)

---

## Phase 5: UI/API [PARTIAL]

**5.1 REST API [PARTIAL]**
- CRUD endpoints for fn / namespace / slot / fn-slot / binding /
  binding-list-item [DONE — `web/crud`]
- GET `/api/graph/entities` [DONE]
- POST `/api/graph/layout` [DONE — `app/layout`]
- POST `/api/sequence/append/:fn-id` + DELETE
  `/api/sequence/item/:item-id` [DONE]
- Bearer-token auth middleware on mutating routes [DONE]
- POST `/execute` — run fn from UI [PLANNED]
- WebSocket for live updates [PLANNED]

**5.2 Web Interface [PARTIAL]**
- Namespace-grouped entity list in sidebar [DONE]
- Graph editor (Cytoscape-based, server-computed layout) [DONE]
- Entity create/edit/delete modals [DONE]
- Execute button with result display [PLANNED]

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

**Problem**: When executing a function with free arguments via UI,
users see slot's primary name (or its inherited form). For
domain-specific UI, we want a per-fn display alias.

**Solution (Phase 6+, current model)**: a per-fn rename creates a new
slot row owned by F whose `slot.source-slot-id` FK points at the
inherited ancestor slot. F's `fn-slot` junction exposes the new
slot under the alias name; bindings still resolve through the
source-slot id, so the rename is purely a display-side rewrite.

The retired `binding.rename-to` text column is no longer used —
Phase 6c migrated every callsite (CRUD, compile, editor) to the FK
link. `slot-by-fn-source-slot` (in
`executor/compile/lookups.clj`) is the O(1) index that lets renamers
be located without walking the inheritance chain.

**Lifecycle:**
- Created via the inline rename popover (edit the edge label) — the
  CRUD impl emits `slot.source-slot-id` + a new `fn-slot` junction.
- Cleared by saving an empty value through the same popover; the
  renamed-view slot row is deleted, exposing the source slot's
  original name again.
- Per-fn: a slot inherited at fn F may be renamed at F without
  affecting siblings (each rename owns its own slot row).

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

### Error Tolerance (type errors as visible diagnostics)

**Goal**: A graph with type errors can be saved and iterated on — sketch
the structure first, fix details later — without errors being silent.

**Current state**: type mismatch IS treated as an error (the type-rules
throw), but the sync-time check wraps every `check-fn-def!` in a
swallowing `try/catch` — a broken fn just drops out of the rich-types
registry with no surfaced diagnostic. The editor shows per-arg mismatch
rings, but there is no graph-level error status or branch-wide error
list.

**What's needed:**
- Type errors as DERIVED, non-blocking diagnostics — recorded per fn,
  always recomputed, never swallowed. Saving an invalid fn stays
  allowed; only the hard structural gates (cycles, name uniqueness,
  terminal/list-closed) reject a write.
- Graph-level "this fn has N errors" status + a "view all errors in a
  branch" surface.
- Branch policy gates: a protected branch may forbid invalid fns
  (block merge); execution of an invalid fn is refused with a clear
  "unresolved type errors" message rather than a runtime crash.
- NO `is_draft` flag — a branch IS the unit of work-in-progress. A WIP
  branch holds WIP fns; "draft-ness" is branch policy, not a per-fn
  column. Validity is a derived fact, not stored state.

**Depends on**: the branches/versions system (already present) plus the
user/role system below.

**Complexity**: Medium. Mostly diagnostics plumbing + editor surface;
no new entity kinds.

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
| `fn` | Yes | name, impl-hash, description, constraint, base-fn-id, element-fn-id, return-type-fn-id, anonymous-hash |
| `fn-slot` | Yes | fn-id, slot-id, position |
| `binding` | Yes | value, ref-fn-id, rename-to, type-override-fn-id, description, terminal, list-{append,closed} |
| `binding-list-item` | Yes | binding-id, position, value, ref-fn-id, literal |
| `slot` | No | immutable post-create — name + type are the slot's identity |
| `parent-fn-ids` | Junction (not versioned) | adding/removing parents creates new junction rows |

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

**Known issue — base-table `(binding_id, position)` unique constraint
vs versioning:**

`binding_list_item` (the stable identity table) carries a pre-versioning
`UNIQUE (binding_id, position)` index, but `position` is versioned data.
This conflicts with the two-table model in two ways:

1. **Soft-delete orphans a position.** `delete-entity` removes an item's
   *version* rows but keeps its base identity row (correct — the item may
   still live on another branch). The orphan base row keeps occupying
   `(binding_id, position)` in the unique index forever.
2. **Cross-branch divergence collides.** Two branches independently adding
   a different item at the same sequence position both need a base row at
   `(binding_id, position)` — the second insert is rejected.

`process-sequence-append` works around (1) by choosing a position that
clears the *base* table, not just the resolved view. The deeper fix is to
stop enforcing `position` uniqueness on the identity table — position is a
resolved-view property, so its uniqueness is an application-level
invariant, not a base-row one. Deferred until cross-branch sequence
editing is actually exercised.

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
