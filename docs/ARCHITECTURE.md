# Graphden: Visual Functional Programming System

> Technical architecture of graphden. For packages and module
> conventions, see [PACKAGES.md](PACKAGES.md). For implementation
> status, see [ROADMAP.md](ROADMAP.md). For design rationale, see
> [PHILOSOPHY.md](PHILOSOPHY.md).

## Table of Contents

1. [Design Overview](#part-1-design-overview)
2. [Constraints Protocol](#part-2-constraints-protocol)
3. [Recursion and Cycles](#part-3-recursion-and-cycles)
4. [Data Schema](#part-4-data-schema)
5. [Execution Model](#part-5-execution-model)
6. [Composition (fn-defs)](#part-6-composition-fn-defs)
7. [System Limitations](#part-7-system-limitations)
8. [Distributed Execution (Future)](#part-8-distributed-execution-future)
9. [Appendices](#appendix-a-storage-backend-architecture)

---

## Part 1: Design Overview

The graph is built from five entity types arranged in a small,
purposeful set:

- **fn** — function or type-row. Inheritance via `parent-ids`
  (many-to-many junction). A fn has different roles depending on
  which fields are set:
  - `parent-ids` empty + `return-type-fn-id` set → base-fn (Clojure impl)
  - `parent-ids` empty + no `return-type-fn-id` + slots/refine/list →
    type-row (record / refinement / list)
  - `parent-ids` non-empty → composed fn
  - `name=nil` → anonymous (deduped via `anonymous-hash`)
- **slot** — atomic `(name, type-fn-id)` pair, immutable
  post-create. The same slot may be exposed by many fns through
  `fn-slot` (sharing means MI inheritance picks up identity).
- **fn-slot** — junction `(fn-id, slot-id, position)`. "Which slots
  does this fn expose, in what order." Position drives the editor
  layout and the impl arg order for base-fns.
- **binding** — per-`(fn-id, slot-id)` overlay carrying any of:
  `value`, `ref-fn-id`, `rename-to`, `type-override-fn-id`,
  `terminal`, `list-append`, `list-closed`, `description`,
  `resolver-fn-id`. Closer-fn-wins via inheritance walk.
- **binding-list-item** — sequence content under a list-typed
  binding, ordered by `position`.

The shape lets composition happen entirely at the data layer: a
composed fn is "a `parent-ids` plus zero or more bindings on the
inherited slots." No runtime arg injection.

**Example: an `add-10` that seeds the number list with `10`:**

```
fn add  (base-fn, parent-ids: [], return-type-fn-id: numeric)
slot s-nums  (name: "nums", type-fn-id: [list numeric])
fn-slot {fn-id: add, slot-id: s-nums, position: 0}

fn add-10  (parent-ids: [add])
binding {fn-id: add-10, slot-id: s-nums, list-append: true}
binding-list-item {binding-id: <the add-10 binding>, position: 0, value: 10}
;; :nums is seeded with [10]; a caller may extend the chain or override
```

Calling `(execute ctx add-10-id {})` runs `add` with `nums = [10]` → `10`.
(`:add` takes a single `:nums` list, not two scalar args — arithmetic
base-fns are variadic over `:nums`.)

Constraints are extracted into an explicit protocol; each storage
implementation MUST enforce them at write time.

---

## Part 2: Constraints Protocol

### Protocol Definition

`src/graphden/storage/protocol/core.clj`:

```clojure
(defprotocol GraphConstraints
  (validate-no-dependency-cycle!
    [this owner-fn-id ref-fn-id]
    "Throws :constraint-violation/dependency-cycle when a binding's
     `:ref-fn-id` (or list-item's) would close a cycle through fn
     references."))
```

### Uniqueness constraints

Two are schema-level `UNIQUE` keys; two are per-branch **resolved-view**
checks enforced by `VersionedStorage` — the base identity row is
cross-branch and soft-deleted identities persist, so a raw `UNIQUE` on
them was retired:

| Entity | Unique key | Enforced by |
|---|---|---|
| `fn-slot` | `(fn-id, slot-id)` | schema `UNIQUE` |
| `binding` | `(fn-id, slot-id)` | schema `UNIQUE` |
| `fn` | `(namespace-id, name)` | resolved-view (`check-fn-name-collision!`) |
| `binding-list-item` | `(binding-id, position)` | resolved-view (`check-list-item-position-collision!`) |

### Implementation per backend

| Backend | Cycle detection |
|---|---|
| postgres | `WITH RECURSIVE` CTE walking `binding.ref-fn-id` and `binding-list-item.ref-fn-id` |

The protocol's shared validation logic
(`validate-no-dependency-cycle-impl`) runs against backend-specific
helpers (`ConstraintHelpers/collect-dependency-chain`), so behavior
is uniform across storages.

---

## Part 3: Recursion and Cycles

### Graph-level cycles are forbidden; recursion is via `:fix`

Two enforcement layers reject dependency **cycles** in the fn-def
graph at write/sync time, keeping the graph a DAG:

1. **Lower-level — `validate-no-dependency-cycle-impl`**
   (`storage/protocol/constraints.clj`). Per-binding write-time
   check. Walks `ref-fn-id` + `parent-ids` + `type-override-fn-id`
   - `binding-list-item.ref-fn-id` edges from the bound ref. Rejects
   when the chain closes back on the owner. Carves out
   `owner == ref` (the bare self-reference) as allowed — but the
   higher-level check catches this anyway.

2. **Higher-level — `topological-sort`**
   (`executor/composition/deps.clj`). Sync-time check across the
   whole fn-def set. Rejects ANY cycle, including bare self-refs.
   Required because the executor compiles closures in topological
   order and a cycle has no valid order.

```edn
;; Bare self-ref — rejected by topological-sort even though the
;; per-binding check carves it out.
{:name :fact :parent :if :args {:test :_zero? :then 1 :else :fact}}
;; → :fn-composition/circular-dependency on sync
```

So a fn-def can't refer back to itself through the graph. Recursion
instead goes through **`:fix`** (`core/recursion`), which introduces
NO graph cycle: the recursive call is a runtime `:self` callable the
step fn receives, synthesized by `:fix`'s impl at wrap time (a
Y-combinator over closure-capture — see
[docs/CLOSURE_CAPTURE.md](CLOSURE_CAPTURE.md)). The graph stays a DAG;
recursion lives at runtime, bounded by `*max-recursion-depth*`
(default 1000).

```edn
;; Factorial as a graph composition — no cycle, Code = Graph holds.
{:name :factorial
 :parent :fix
 :args {:step :_fact-step        ; (fn [{:input n :self recur}] …)
        :input {:as :n}}}
```

`:fix` is tested (`recursion_test` runs a real factorial + the depth
bound) and used in production: `storage/branches` `:branch-chain`
walks a branch's parent chain as a `:fix` composition over the
`:pg-query` / `:decode-row` primitives. Mutual recursion is expressed
by tag-dispatching inside one `:fix` step (the `:self` handles every
mutual arm).

### Anti-pattern: runtime re-entry from a base-fn impl

Before `:fix`, the only path was escaping into Clojure inside a
base-fn impl and calling back through `exec/execute-by-name`:

```clojure
(defbase fact-step [n ctx]        ; ANTI-PATTERN — use :fix instead
  (if (<= n 1) 1 (* n (exec/execute-by-name ctx :fact-step {:n (dec n)}))))
```

This **violates Code = Graph** — the recursive structure lives in
Clojure, invisible to the editor / type-checker / graph traversals.
`:fix` supersedes it; prefer a `:fix` composition for any new
recursion.

### The road not taken: Approach B (lazy ref resolution)

[RECURSION.md](RECURSION.md) also specifies Approach B — relax the
cycle check (gated by a `:recursive?` flag) so refs cycle freely and
the compiler emits lazy thunks at every ref site, giving natural
mutual recursion at the cost of touching the compile pipeline +
`delta-recompile!` + type-checker. It was NOT built; `:fix` (Approach
A) covers the practical cases without weakening the DAG invariant.
Revisit B only if `:fix`'s tag-dispatch mutual-recursion ergonomics
prove insufficient in practice.

### Runtime safety

The executor caps recursion through `:max-depth` and `:timeout-ms`
on the execution context (defaults 1000 and 30 s) — applies to BOTH
the legacy runtime re-entry pattern AND the `:fix`-based pattern
(`:fix` additionally enforces `*max-recursion-depth*` per call).
Storage-layer graph resolution caps walks via
`*max-graph-iterations*` (default 10000) when building closures.

---

## Part 4: Data Schema

```
+-----------------------------------------------------------+
| fn                                                         |
+-----------------------------------------------------------+
| id                  uuid PK                                |
| name                text NULL (NULL → anonymous)           |
| namespace-id        ref<ns> NULL                           |
| parent-ids       ref-many<fn>  -- inheritance closure   |
| base-fn-id          ref<fn> NULL -- :refine target         |
| element-fn-id       ref<fn> NULL -- :list element type     |
| return-type-fn-id   ref<fn> NULL -- base-fn marker         |
| anonymous-hash      text NULL    -- dedup key              |
| constraint          jsonb NULL   -- :refine predicate      |
| description         text NULL                              |
| (namespace-id, name) unique per branch — resolved-view     |
+-----------------------------------------------------------+
                  |
                  | many-to-many through fn-slot
                  v
+-----------------------------------------------------------+
| slot                                                       |
+-----------------------------------------------------------+
| id           uuid PK                                       |
| name         text                                          |
| type-fn-id   ref<fn>  -- the slot's value type             |
| required     bool NULL                                     |
| description  text NULL                                     |
+-----------------------------------------------------------+
                  ^
                  | reference
                  |
+-----------------------------------------------------------+
| fn-slot (junction)                                         |
+-----------------------------------------------------------+
| id        uuid PK                                          |
| fn-id     ref<fn>                                          |
| slot-id   ref<slot>                                        |
| position  int                                              |
| UNIQUE(fn-id, slot-id)                                     |
+-----------------------------------------------------------+

+-----------------------------------------------------------+
| binding (per-(fn, slot) overlay)                           |
+-----------------------------------------------------------+
| id                  uuid PK                                |
| fn-id               ref<fn>                                |
| slot-id             ref<slot>                              |
| value               jsonb NULL                             |
| ref-fn-id           ref<fn> NULL                           |
| rename-to           text NULL                              |
| type-override-fn-id ref<fn> NULL                           |
| description         text NULL                              |
| terminal            bool NULL  -- seal slot from descendants|
| list-append         bool NULL  -- extend parent's chain    |
| list-closed         bool NULL  -- forbid further append    |
| resolver-fn-id      uuid FK→fn NULL                        |
| UNIQUE(fn-id, slot-id)                                     |
+-----------------------------------------------------------+

+-----------------------------------------------------------+
| binding-list-item (sequence content)                       |
+-----------------------------------------------------------+
| id          uuid PK                                        |
| binding-id  ref<binding>                                   |
| position    int                                            |
| value       jsonb NULL                                     |
| ref-fn-id   ref<fn> NULL                                   |
| literal     bool NULL  -- :literal? for keyword items      |
| UNIQUE(binding-id, position)                               |
+-----------------------------------------------------------+
```

### Inheritance model

A composed fn `F` inherits its parents' slots through the
`parent-ids` BFS closure. Each slot in that closure is exposed
once at `F`; if multiple parents expose the same `slot-id`, that's
sharing (fine). If they expose different slot-ids with the same
`slot.name`, that's a name collision (rejected at write time by
the editor's MI validator).

A binding "applies" to the slot it names (`binding.slot-id`) at
its owning fn (`binding.fn-id`). When rendering / executing `F`,
walk `F`'s closure and overlay each slot with the closest
binding's value/ref/rename. Item chains resolve the same way:
`list-append: true` extends parent's items rather than replacing.

### Versioning

Versioned entities (per `graphden.versioning.storage.resolution/entity-config`):

| Entity | Version table | Versioned fields |
|---|---|---|
| `fn` | `fn-version` | `name`, `description`, `constraint`, `base-fn-id`, `element-fn-id`, `return-type-fn-id`, `anonymous-hash`, `expects-effects` |
| `fn-slot` | `fn-slot-version` | `fn-id`, `slot-id`, `position` |
| `binding` | `binding-version` | `fn-id`, `slot-id`, `value`, `value-present`, `ref-fn-id`, `resolver-fn-id`, `type-override-fn-id`, `description`, `terminal`, `required`, `list-append`, `list-closed` |
| `binding-list-item` | `binding-list-item-version` | `binding-id`, `position`, `value`, `ref-fn-id`, `literal` |

`slot` is intentionally not versioned — name+type is the slot's
identity, so changing either creates a new slot.

---

## Part 5: Execution Model

### Compile at startup

Every fn in the graph is compiled to a thunk at startup. Each call to
`execute-by-name` delegates to a precomputed callable; `/health`
~10 ms, `/api/graph/layout` ~130 ms on the current dev graph.

`graphden.executor.compile/compile-all` walks the graph and emits:

- per-base-fn thunks that wrap the Clojure impl with delay-resolved
  arg slots,
- per-composed-fn thunks that wire bindings (value / ref / list-items)
  into the parent's free-arg map,
- a memo cache keyed by `ref-fn-id` (shared across nested calls for
  the same execution).

The legacy queue-based executor was retired; `compile-runtime` is
the executor.

### Laziness via Clojure delays

Arguments arrive at base-fn impls as resolvable thunks (`rt/resolve-arg`
handles both new-style thunks and legacy `IDeref` delays). The
`defbase` macro injects symbol-name → `(rt/resolve-arg args :symbol)`
let-bindings so impls reference args by name without writing the
deref dance themselves.

The chosen-branch laziness comes from Clojure's native `if`: the
unchosen branch's symbol is never deref'd, so its thunk never fires.

```clojure
(defbase if-fn
  "Lazy if: only the chosen branch's ref-thunk is invoked."
  [test then else]
  (if test then else))
```

Structural benefits:

- branches not taken (e.g. `:if`'s unchosen side) never evaluate;
- the same `ref-fn-id` materialises at most once per execution
  through `result-cache`;
- HOF callable construction reuses the resolver layer — no ad-hoc
  memoisation.

### HOF single-arg model

Higher-order functions take a `:fn`-typed slot. The executor's
runtime layer (`graphden.executor.runtime/resolve-arg`) detects
the `:fn` type and wraps a raw fn-id via
`make-single-arg-callable` BEFORE the impl gets it — so the impl
receives an already-callable function. The callable threads its
input through the target's first free slot; reduce-shape HOFs
receive `[acc item]` as a single vector arg.

```clojure
(defbase map-fn [func coll]
  (if coll
    (map func coll)   ;; eager mode — caller supplied a coll
    (map func)))      ;; transducer mode — no coll
```

The HOF marker is the slot's effective type — `slot.type-fn-id`
overlaid by `binding.type-override-fn-id`. When the resolved type
is `:fn`, the executor passes the wrapped callable; otherwise it
executes the ref and uses its result.

### Argument resolution

Per call, the executor resolves a slot's value in this order:

1. `provided-args` map passed to `execute` (free-arg fill-in)
2. closest binding's `:value` or `:ref-fn-id` along the
   inheritance closure
3. closest list-binding's items (for sequence-typed slots), with
   `list-append` extending parent items
4. fail with `:execution-error/missing-required-arg` if the slot is
   required and unfilled

### Runtime fa (hybrid slot-id + name keys)

The runtime `fa` (free-args map) is hybrid-keyed past the public
boundary. Two key spaces live in the same map; readers prefer
slot-ids and fall back to names. The hybrid is the shipped
architecture (not a transitional bridge):

- **slot-id keys** — distinguish structural ambiguity at the public
  boundary. `execute-by-name` / `make-single-arg-callable` route
  caller args through `translate-named-args`, which walks the
  callee's surface (`deep-free-ext-entries`) and writes the
  caller's value under EVERY matching `(ext-name → slot-id)` pair.
  HOF wraps further propagate slot-ids past their boundary via
  `apply-hof-translation` so deep readers find their slot-id-keyed
  values without falling back to names.
- **name keys** — cover DYNAMIC writes: env-binding values
  (`compile-fn`'s `env-builder` loop), HOF lambda-args
  (`make-shape-callable` merges per-call values name-keyed), and
  `apply-rename-aliases` cross-fn rename copies. There's no
  structural ambiguity in these — a value flows under one name,
  set per call.

The `:free` arg-builder reads `fa[effective-reader-slot-id]` first
(rename-aware Phase 4 path), then falls back to `fa[ext-name]`.
The slot-id read distinguishes two inline-anon `:assoc` calls that
each have their own `{:as :X}` rename slot (different slot ids,
same chain-leaf); the name fallback covers dynamic per-call writes
that haven't reached the slot-id key space.

See [`docs/RUNTIME_SLOT_ID_REFACTOR.md`](RUNTIME_SLOT_ID_REFACTOR.md)
for the phase-by-phase landing history and the deferred Phase 5
extension that would unify the two key spaces.

### Limit checking

```clojure
(defn- check-limits! [context]
  (check-depth-limit! context)    ; :execution-error/max-depth-exceeded
  (check-timeout-limit! context)) ; :execution-error/timeout
```

Timeout fires at the START of each call, not mid-impl — a slow
base-fn finishes even past the budget. Lazy-seq guards
(`*max-lazy-seq-size*`, `*max-nested-collection-depth*`) realise
infinite/deep inputs at call time so failures surface before the
impl runs.

### Local argument binding

To call the same base-fn with different inputs at different sites,
create a composed fn per site:

```
fn add-10-20  (parent-ids: [add])
binding {slot s-nums, list-append: true}
binding-list-item {position 0, value 10}
binding-list-item {position 1, value 20}

fn add-30-40  (parent-ids: [add])
binding {slot s-nums, list-append: true}
binding-list-item {position 0, value 30}
binding-list-item {position 1, value 40}
```

All variation lives in the data — the executor stays generic.

---

## Part 6: Composition (fn-defs)

### Two layers

**Base-fns** carry Clojure impls (typically 1–5 lines wrapping a
library call). They're declared in `package/module/impls.clj` with
`defbase` and shape-described in `package/module/fns.edn`.

```clojure
(defbase const
  "Constant fn — returns :value unchanged."
  [value]
  value)

(defbase assoc-fn
  [map key value]
  (assoc (or map {}) key value))
```

The `defbase` arg-syms name the args declared in `fns.edn` (`:value`;
`:map`/`:key`/`:value`) and arrive already-resolved; types live in
`fns.edn`, not in the `defbase` form.

**Fn-defs** are pure data compositions in `fns.edn`:

```clojure
{:name :hello-handler
 :parent :const
 :args {:value {:status 200 :body "Hello"}}}

{:name :web-server
 :parent :http-server
 :args {:handler :hello-handler :port 8080}}
```

`:args` is sugar for binding rows — each entry becomes one or more
bindings on the named slot.

### Reference syntax

Bindings reference other fns via `binding.ref-fn-id`. EDN sugar:

```clojure
;; HOF: slot type is :fn → executor passes :double-fn's id
{:name :double-all :parent :map
 :args {:func :double-fn :coll [1 2 3]}}

;; Non-HOF: slot type isn't :fn → executor runs :router-fn,
;; uses its result
{:name :web-server :parent :http-server
 :args {:handler :router-fn :port 8080}}
```

### Free-arg propagation

Unbound slots of a referenced fn surface as free slots of the
caller — that's how reusable templates work. Renames (`:rename-to`)
swap the public name; type-overrides
(`:type-override-fn-id`) swap the effective type for that one
fn.

Worked example: `:get-route` exposes `:path` and `:handler`; a
caller binds `:path` and inherits the rest as their own free
arg.

See [PACKAGES.md § Composition Best Practices](PACKAGES.md#composition-best-practices).

---

## Part 7: System Limitations

### What can break

- **Infinite recursion** — bounded only by depth/timeout; throws at
  runtime.
- **Concurrent cycle detection** — relies on the storage's
  transactional guarantees. Postgres uses serializable transactions
  on the relevant write path.
- **Deep graph traversal** — many DB queries on the cold path.
  Mitigated by graph caching (`:graph-cache` on the executor
  context), invalidated explicitly by CRUD writes.

### Known gaps

- *(no current entries — the historical "per-fn optionality override"
  gap is now closed by binding-level `:required` narrowing; see
  [TYPES.md § Required Narrowing](TYPES.md#required-narrowing).)*

---

## Part 8: Distributed Execution (Future)

Graphden's graph-based representation enables automatic
parallelisation. Independent subgraphs (no mutual dependency
through `:ref-fn-id`) are eligible to run concurrently. The
executor today is single-threaded; the plan is:

1. **Local parallelism** — fork independent args onto a thread pool.
2. **Worker pool** — offload to processes on the same host.
3. **Distributed workers** — remote executors with network transport.
4. **Smart partitioning** — cost-based partitioner using the graph
   shape.

The architecture supports this naturally: pure subgraphs (no
declared `:effects`) can run anywhere, side-effecting subgraphs
need explicit ordering / idempotency. Effect declarations
(`:effects #{:io :db ...}`) on base-fns let the partitioner
distinguish.

---

## Appendix A: Storage Backend Architecture

Currently one backend ships:

- `storage/postgres` — pure PostgreSQL with recursive CTEs for
  graph traversal and cycle detection. Wrapped by
  `versioning/storage` (Git-like branching).

Backend interface contracts live in `storage/protocol/`:

| Protocol | Purpose |
|---|---|
| `StorageCRUD` | create / read / update / delete / query per entity |
| `StorageBatchCRUD` | batched variants for sync paths |
| `StorageIntrospection` | live schema metadata (entities, fields, enums) |
| `GraphConstraints` | the cycle check |
| `StorageValueCodec` | encode/decode JSONB / enum-tagged values |
| `StorageErrorClassifier` | wrap backend errors in storage-protocol error types |

To add a new backend: implement these protocols and run
`graphden.storage.protocol.contract-tests/run-graph-constraints-tests`

- `concurrent-read-write-test` to verify behavioural compliance.

## Appendix B: Module Dependency Graph

```
       schema/fields
            │
   ┌────────┼────────┐
   v        v        v
 schema/  storage/  schema/
 protocol protocol  malli
   │        │        │
   └────────┼────────┘
            v
     storage/postgres ─── schema/graph
            │
            v
       executor/* ────┬───── packages/loader
            │         │
            │         └───── executor/registry
            v
       resources/packages/
       (core, web, app, examples)
```

## Appendix C: Error Types

See [ERROR_CODES.md](ERROR_CODES.md).
