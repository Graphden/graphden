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
| Integrant System | Done | Component lifecycle management |
| Logging | Done | Structured logging with MDC |
| Web Server | Done | http-kit + Reitit router |
| Versioning | Done | VersionedStorage + HTTP API + editor UI + per-branch executor routing (see [VERSIONING.md](VERSIONING.md)) |
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

- `fn` — function or type-row; M:N inheritance via `parent-ids`
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
- (the `binding-list-item(binding-id, position)` UNIQUE was retired — the
  base identity row is cross-branch, so position uniqueness is enforced
  per-branch by `VersionedStorage/check-list-item-position-collision!`)

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
- GET `/api/graph/entities` [DONE] — with `?scope=index` (sidebar only, 1.6 MB) and `?scope=subtree&root-id=X` (BFS closure, 1.5 KB - 50 KB typical) variants for per-fn bandwidth savings
- POST `/api/graph/layout` [DONE — `app/layout`]
- POST `/api/sequence/append/:fn-id` + DELETE
  `/api/sequence/item/:item-id` [DONE]
- Bearer-token auth middleware on mutating routes [DONE]
- POST `/api/execute` — run fn from UI [DONE — `docs/EXECUTION.md`,
  `crud.fn_execution` + sibling `/cancel`, `/api/executions`,
  `/api/execute/:id`]
- WebSocket for live updates [PLANNED]

**5.2 Web Interface [PARTIAL]**

- Namespace-grouped entity list in sidebar [DONE]
- Graph editor (Cytoscape-based, server-computed layout) [DONE]
- Entity create/edit/delete modals [DONE]
- Execute button with result display [DONE — `editor-execute.js`
  orchestrator, free-arg value-form, polling state machine, runtime
  effects strip, Repeat-from-history]

---

## Roadmap by Blocks (current plan)

This is the active forward plan, agreed with the author. See
[PHILOSOPHY § Positioning](PHILOSOPHY.md#positioning) and
[DISTRIBUTION § Author Horizon](DISTRIBUTION.md#author-horizon) for
context. The plan supersedes the per-item entries in § Future Work
below; those entries remain for historical context and as
deeper-design references.

**Path to MVP launch with external users**: Blocks 1 → 2 → 4 → 9-Launch
on the critical path, with Blocks 3 / 5 / 6 parallelizable. Estimated
total **~18-19 weeks**, calibrated against velocity in Jan-May 2026
(~150 commits/month sustained, e.g. full type-system overhaul in
~3 weeks, versioning + branches in ~3-4 weeks).

The AI-launch piece (Block 9.1–9.3) is on the critical path because
"co-edit your graph with the AI of your choice, review proposals as
graph-diffs not text-diffs" is part of the launch story; growth-piece
(Block 9.4–9.6) is deliberately deferred to post-MVP.

### Block 0 — Tutorial framework (continuous)

- Initial framework + first lessons in `docs/tutorial/` — text only
  for now; UI integration is a later decision
- One new lesson added per feature block as that block ships
- **Initial scope**: ~1 week; **ongoing**: ~0.5 week per block

### Block 1 — Foundation (start immediately)

Implements PHILOSOPHY § Self-Describing System "storage swap path"
(S1+S2+S3 in the protocols staging).

1. **Storage base-fns** — `:pg-query`, `:pg-execute`, `:pg-tx`, plus
   HoneySQL helpers — ~1.5 weeks
2. **API routes migrated** from direct Clojure storage to graph
   fn-defs — ~2 weeks
3. **Type-row `:Storage`** + `:postgres-storage-impl` + free-arg
   injection at the web-server level — ~3-4 days

Block total: **~4 weeks**

### Block 2 — Multi-user readiness

Enables external alpha users on either self-hosted or cloud-shared.

1. **Organizations + Users** entity + UI — ~1-1.5 weeks
2. **Postgres RLS** + policy-based isolation — ~1.5 weeks
3. **Permissions** (per-fn, per-namespace, per-branch) — ~2 weeks
4. **Package registry** (server + reference client, MVP — without
   full dep resolution) — ~2 weeks

Block total: **~6-7 weeks**

### Block 3 — Personal QoL (parallel to Block 2)

1. **Tests via `tests/` namespace** convention + UI filter in the
   sidebar — ~3-4 days
2. **Workspaces** (namespace M:N self-link + UI scoping) — ~1 week
3. **Error tolerance** (type mismatches as derived diagnostics, not
   silent swallow) — ~3-4 days. See § Future Work → Error Tolerance.
4. **Debug/observability** with the PHILOSOPHY § Debugging
   constraints (per-fn opt-in, sampling, `:secret` auto-skip,
   size/TTL limits) — ~1.5 weeks
5. **Free-arg aliases** — see § Future Work entry; status check,
   finish if not already shipped
6. **Routes API for frontend + static-lint against drift** — close
   the gap between declarative HTTP routes (`app/routes/fns.edn`
   `:get-route` / `:post-route` / `:delete` / `:put` fn-defs) and
   the editor JS which today hardcodes URL strings (`fetch('/api/
   secrets/' + id, …)`). When admin renames a path in `fns.edn`,
   JS continues hitting the old URL — bug surfaces only when a user
   clicks the button. ~3-4 days. Has 3 pieces:
   - **`GET /api/routes` endpoint** — graph fn-def chain over a new
     atomic `:list-routes` base-fn; returns `{action-name →
     {:path :method}}`. Single source of truth = the same fns.edn
     declarations that wire the actual handlers.
   - **`routeFor(action, params)` helper in `editor-state.js`** —
     reads `window.ROUTES` (loaded on bootstrap), substitutes `:param`
     placeholders. Replaces ~30 hardcoded `'/api/…'` literals
     across the editor JS modules.
   - **`bb check` lint** — sweeps editor JS for `routeFor('…')`
     calls, asserts every action-name appears in the routes-map
     computed from `fns.edn` at lint time. Catches typos and
     forgotten renames at CI instead of runtime. Closes the same
     drift gap that the `:tags` refactor closed for the
     admin-only-vault gate (declarative co-location of contract).

Block total: **~4-5 weeks**

### Block 4 — Ecosystem (after Block 1)

Implements the MVP launch bar from [DISTRIBUTION § MVP Launch Bar](DISTRIBUTION.md#mvp-launch-bar).

1. **Sidecar pattern** — `:python-call`, `:python-script`,
   `:go-call` base-fns for cross-language reach — ~1.5 weeks
2. **2-3 integration packages** — `telegram-bot`, `postgres-client`,
   `openai-client` (or `http-client` if `openai-client` deferred) —
   ~1 week each

Block total: **~4-5 weeks**

### Block 5 — Recursion (any window after Block 1)

- **`:fix` base-fn** (Approach A from
  [RECURSION.md](RECURSION.md)) — ~1 week

### Block 6 — UI Step 1 (any window)

- **Inline `:const` editor** for JS/CSS in the running editor +
  rebuild trigger — ~1.5 weeks. See
  [PHILOSOPHY § UI as Graph](PHILOSOPHY.md#ui-as-graph--two-step-roadmap).

### Block 7 — Horizontal scaling foundation

**Critical path for Cloud-Shared launch; deferrable if shipping
only Self-Hosted + Cloud-Dedicated.** Without this block running
more than one executor process is best-effort — fn-def writes on
one node don't invalidate compiled registries on others.

Sub-block A — **Multi-process executors over shared Postgres**:
each JVM instance serves requests independently; load balancer in
front (nginx / ALB / etc.); each executor builds its own
`:compiled-registry` from the shared storage at startup. The
self-hosted single-pod path is unchanged. Tested by spinning two
containers against one PG and round-tripping CRUD across them.

Sub-block B — **Cross-process fn-def invalidation via Postgres
LISTEN / NOTIFY**: every storage write that today calls
`invalidate-graph-cache!` also emits a `NOTIFY graphden_invalidate
<payload>` carrying the affected fn-ids. Each executor LISTENs on
the same channel and re-fires its local invalidate when it
receives a NOTIFY from a sibling. Per-branch routing already
in-process; the new layer is the cross-process pub/sub.

Estimated total: **~3-4 weeks** (A: ~1, B: ~2-3 incl. concurrency
testing across multiple containers).

Dependencies: Block 1 (storage-as-graph) + Block 2 (orgs/users for
tenant routing).

### Block 8 — Hot-reload of impls (optional)

**Dev-velocity feature, NOT required for Cloud-Shared launch.**
Today changing a `defbase` body requires `bb rebuild` (docker
image rebuild + restart). Hot-reload would let the author push a
new impl into a running executor without restart.

Approach sketch: nREPL channel into each executor; the package
loader re-syncs an individual `impls.clj` and the registry rebuild
picks up the new impl-fn map. Risk class: ClassLoader hygiene,
in-flight requests during swap, security (treats any nREPL caller
as trusted code-pusher — needs auth gating).

Estimated: **~2 weeks**.

Scheduled any time after Block 7 lands (sharing the cross-process
invalidation channel). Will not block any other block.

### Block 9 — AI Integration

**Launch piece (9.1–9.3) is on the MVP critical path; growth piece
(9.4–9.6) is post-MVP.** Implements the AI co-author surface so users
can drive graphden with the AI model of their choice (their own API
key) and so the editor presents AI proposals as graph-diffs rather
than text-diffs.

#### Motivation

1. **Author pain.** Reviewing AI-proposed changes in text-based
   codebases costs attention to whitespace, line-breaks, formatting
   noise rather than to behaviour. Graphden's entity model lets a
   proposal surface as "added 3 fn-defs, changed 2 bindings, deleted
   1 ref" against the current graph — the existing branch-diff UI
   already renders this shape.
2. **Launch-time differentiation.** "Co-edit your graph with the AI
   of your choice, review without text-diff noise" is part of the
   first-launch story for both users and investors.
3. **Hypothesis under test.** The small vocabulary (5 entity types,
   ~150 base-fns) should give an AI a tighter problem surface than
   an arbitrary 50 kLOC text codebase. Unproven — the MCP server +
   AI-context resource is the experiment that tests it.

#### Launch piece (~4 weeks, critical path)

1. **MCP server.** Graphden exposes its primitives (list / read /
   create / update / delete fn-def, execute, run tests, query
   branches, read history, get AI context) over the Model Context
   Protocol. Any MCP-capable client (Claude Code, Cursor, Claude
   Desktop, future clients) can drive graphden against the user's
   own model + API key. Open-source, ships as a base-fn-graph
   (`:mcp-server`) wired into the editor process. Per-user auth on
   every tool call. **~1.5 weeks.**
2. **Editor "Ask AI" flow.** Button in the editor that prompts for
   instructions + a target branch (defaults to a fresh `ai/<slug>`
   branch), spins up an AI session against the user-configured
   model, lets the AI execute graph mutations against that branch
   via the same MCP tools, and on completion routes the user to the
   existing branch-diff UI (`editor-branch-diff.js`) for accept /
   reject. **BYOM (bring-your-own-model)**: per-user model + API
   key config UI, encrypted at rest via the existing `:vault-*`
   surface (cloud-shared) or kept client-side (self-hosted).
   **~2 weeks.**
3. **AI-context resource.** Curated description of graphden's entity
   model, design principles, common patterns, naming conventions,
   and how to mutate the graph via MCP tools, served by the MCP
   server as a `get-ai-context` resource and as a downloadable
   `docs/AI_CONTEXT.md` for clients that don't auto-fetch resources.
   Distinct from `CLAUDE.md` (which is developer-side guidance for
   working on graphden) — this teaches an external AI how to write
   *user* fn-defs. **~3–4 days.**

#### Growth piece (~4–5 weeks, post-MVP)

1. **Managed-model gateway.** Control-plane proxy for users who
   don't want to manage their own API keys; per-token markup on top
   of upstream cost. Closed source, lives with the cloud control
   plane. Self-hosted users keep BYOM. **~1.5 weeks.**
2. **Dedicated proposal panel.** Richer per-fn-def diff cards with
   inline reject-with-feedback and conversational follow-up;
   successor to the branch-diff-modal reuse from 9.2. **~2 weeks.**
3. **Persistent AI sessions.** Store conversation transcripts +
   tool-call traces per branch, resume / share. **~1 week.**

#### Dependencies

- 9.1 needs Block 2 (auth — every tool call is per-user). Storage
  base-fns from Block 1 are not required but simplify implementing
  the tools as graph fn-defs rather than Clojure shims.
- 9.2 needs Block 2 (per-user secret storage for BYOM API keys).
  Block 6 (inline `:const` editor polish) is helpful but not
  required — the launch UX uses the existing branch-diff modal.
- 9.3 is standalone.
- 9.4 needs the cloud control plane (closed-source track, parallel
  to open-core blocks).
- 9.5–9.6 follow 9.2.

**Block total launch: ~4 weeks. Growth: ~4–5 weeks.**

### Deprioritized (do when there's a slot, no critical path)

- **Graph → Clojure export** — credibility / REPL escape hatch.
  Useful but not on the daily-use path.
- **Clojure → graph import** — needs a real migration target first.

### Not planned

- **Datomic** storage backend
- **Executor rewrite** in another host language (Go / Python). See
  PHILOSOPHY § Trade-off Sovereignty — would force two impls of
  every base-fn forever.
- **Multi-language fine-grained execution** — sidecar pattern
  (Block 4) covers the use case at coarse granularity, which is the
  only level where it's practical.
- **UI Step 2** (full graph-described UI structure) — far future.
- **Distributed execution** — kept on the existing § Future Work
  list as a separate research thread, not part of this block plan.
- **Popularity-based fn-set distribution** — the "smart" horizontal
  scaling where each executor holds only the hot subset of fn-defs
  and a routing layer dispatches by fn-id popularity. Requires
  millions-of-fns scale + heavy per-impl resource cost to pay back
  the orchestration complexity. We are not there for the next ~24
  months minimum. Block 7's "every executor holds everything"
  shape scales fine for thousands of fn-defs in modest-RAM JVMs.

---

## Future Work

### Graph-level Recursion

> Now scheduled in **Block 5**. The design details below remain
> authoritative; the block schedule sets the order.

**Status**: Approach A (`:fix` Y-combinator) is SHIPPED — `:fix` base-fn
in `core/recursion` (`resources/packages/core/recursion/`), depth-guarded,
used by `storage/branches` `:branch-chain`. See [RECURSION.md](RECURSION.md).
The per-binding cycle check + sync-time topological-sort still reject bare
self/mutual `ref-fn-id` patterns; `:fix` is the graph-native way to express
recursion without that escape hatch. Approach B (lazy ref resolution) is the
road not taken.

**Goal**: make recursion expressible in the fn-graph itself —
tree-walk (JSON / hiccup / AST visitor) is the bread-and-butter
practical case currently blocked.

**Two viable approaches**, both fully specified in
[RECURSION.md](RECURSION.md):

| | Approach A — `:fix` | Approach B — Lazy ref resolution |
|---|---|---|
| New entities | 1 base-fn | 0 (optional `:recursive?` flag on fn-row) |
| Cycle invariant | Preserved | Relaxed for opt-in fns |
| Mutual recursion | Tag-dispatch convention | Natural |
| Effort | ~3 hours | ~1-2 days |
| Risk | Minimal | Touches compile pipeline + delta-recompile |

**Recommended order**: ship A first (leverages already-shipped
closure-capture; covers ~80% of practical use cases). Revisit B
only if A's mutual-recursion ergonomics prove insufficient. After
A lands, `exec/execute-by-name` from inside an impl moves from
"escape hatch" to explicit anti-pattern.

**See**: [RECURSION.md](RECURSION.md) for the full design + impl
sketches + open questions, [ARCHITECTURE.md § Part 3](ARCHITECTURE.md#part-3-recursion-and-cycles)
for the current-state writeup.

---

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

> Now scheduled in **Block 3** (status check + finish if not done).
> The design below remains authoritative.

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

> Now scheduled in **Block 3**.

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
| `fn` | Yes | name, description, constraint, base-fn-id, element-fn-id, return-type-fn-id, anonymous-hash |
| `fn-slot` | Yes | fn-id, slot-id, position |
| `binding` | Yes | value, ref-fn-id, rename-to, type-override-fn-id, description, terminal, list-{append,closed} |
| `binding-list-item` | Yes | binding-id, position, value, ref-fn-id, literal |
| `slot` | No | immutable post-create — name + type are the slot's identity |
| `parent-ids` | Junction (not versioned) | adding/removing parents creates new junction rows |

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
- Implementation-only changes (bug fix, same signature): all users get the new code automatically (Clojure runtime is shared)

**Branch-aware request routing [DONE — `feat/versioning`]:**

- `:exec/branch-router` init-key holds an atom of
  `{branch-id → ExecutionContext}` — each non-default branch gets
  its OWN compiled registry because `(impl args ctx)` closes ctx in
  at compile time and branch bindings can diverge.
- Per-request branch selection via `X-Graphden-Branch` header or
  `?branch=<name>` query. Default = main.
- Lazy-build on first request, cached afterwards; invalidation
  piggybacks on the existing `invalidate-graph-cache!` (writes on
  branch X clear X's registry without touching main).
- Ring callable re-reads the registry atom on every invocation so
  the cached handler picks up post-write rebuilds without explicit
  reattach.
- Front of the wire: `:branch-routing-wrap` base-fn (in
  `web.branch-router`) wraps the compiled `:_app-ring-response` and
  delegates to the router. No active router (test paths / startup
  race) → call the wrapped base-handler directly.

**HTTP surface [DONE — see `docs/VERSIONING.md`]:**

| Verb   | Path                                     | Notes |
|--------|------------------------------------------|-------|
| GET    | `/api/branches`                          | List |
| GET    | `/api/branches/:ref`                     | One (UUID or name) |
| POST   | `/api/branches`                          | Create (forks from current) |
| DELETE | `/api/branches/:ref`                     | Rejects `main` and branches with children |
| GET    | `/api/branches/:ref/diff?against=…`      | Resolved-view diff |
| GET    | `/api/branches/:ref/conflicts?source=…`  | Preview conflicts before merge |
| POST   | `/api/branches/:ref/merge`               | Body `{source, conflict-resolutions?}` |
| GET    | `/api/fns/:fn-id/versions`               | History per fn |

**Editor UI [DONE]:** branch chip + popover in the menu-header
(`editor-branches.js`), `⌛` history popover on each fn-card
(`editor-fn-versions.js`), inline merge button + conflict-resolution
modal. `window.fetch` is wrapped at load so every `/api/*` call
picks up the current branch automatically.

**Demo seeder [DONE]:** `:exec/demo-branches` init-key ensures a
small set of pre-baked branches exists on every startup so the UI
has something to demo (diff, merge, switching). Idempotent — already-
existing branches are left untouched. Opt-in via
`GRAPHDEN_DEMO_BRANCHES_ENABLED=1`; off by default in prod.

**Known issue — base-table `(binding_id, position)` unique constraint [FIXED]:**

The pre-versioning `UNIQUE (binding_id, position)` index on
`binding_list_item` (the identity table) was retired in
`feat/versioning` — position is a resolved-view property, not a
cross-branch identity property. The invariant now lives at the
per-branch resolved view in
`VersionedStorage/check-list-item-position-collision!`. Existing
DBs get the legacy index dropped by `migration/drop-retired-indexes!`
on the next migration pass.

---

### User and Permission System

> Now scheduled in **Block 2** (Organizations + Users + RLS +
> per-fn / per-namespace / per-branch permissions). The
> permission-model sketch below is a starting point; details will
> be settled when implementing.

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
