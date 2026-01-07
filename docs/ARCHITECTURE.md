# Graphden: Visual Functional Programming System

> **Last updated:** 2026-01-07
>
> This document describes the technical architecture of graphden.
> For implementation status and roadmap, see [ROADMAP.md](ROADMAP.md).

## Table of Contents

1. [Critical Model Analysis](#part-1-critical-model-analysis) - Design decisions and alternatives
2. [Constraints Protocol](#part-2-constraints-protocol-graphconstraints) - Graph integrity
3. [Recursion and Cycles](#part-3-recursion-and-cycles) - Handling recursive patterns
4. [Data Schema](#part-4-data-schema) - Entity definitions
5. [Execution Model](#part-5-execution-model) - Lazy evaluation with thunks
6. [System Limitations](#part-6-system-limitations) - Known constraints and mitigations
7. [Distributed Execution](#part-7-distributed-execution-future) - Parallelization and distribution
8. [Appendices](#appendix-a-component-dependency-graph) - Reference material

---

## Part 1: Critical Model Analysis

### The "Argument Override" Problem

**Current approach (inheritance via parent-fn-id):**

```
fn: A (parent: null)
  arg-values: {x: 1}

fn: B (parent: A)
  arg-values: {y: 2}  <- OK

fn: C (parent: B)
  arg-values: {x: 5}  <- FORBIDDEN: x is already defined in A
```

**How to enforce this?**

| Storage | Implementation | Issues |
|---------|----------------|--------|
| PostgreSQL | Trigger + recursive CTE | Complex, slow on deep chains |
| Datomic | Transaction function + query | Complex, requires additional query |
| Memory | Code at write time | Logic duplication |

**This is BAD** - the constraint is not declarative, requires code, easy to break.

---

### Alternative 1: Immutability + Copying

**Idea**: Abandon "live" inheritance. When creating fn, copy arg-values from the "base".

```
fn: A
  arg-values: {x: 1}

fn: B (based-on: A)
  // When created, copied x: 1 from A
  arg-values: {x: 1, y: 2}

fn: C (based-on: B)
  // When created, copied x: 1, y: 2 from B
  arg-values: {x: 1, y: 2, z: 3}
```

**Pros:**
- No parent-fn-id -> no recursive checks
- Each fn is self-contained
- Constraint "one arg-schema-id per fn" = simple unique(owner-fn-id, arg-schema-id)

**Cons:**
- No "live" updates: changed A -> B and C won't update
- Data duplication

**Mitigating cons:**
- "Live" updates are rarely needed in practice
- Can add "update from base" operation if needed
- Duplication is not a problem for small values

---

### Alternative 2: Versioning

**Idea**: Each fn is immutable, change = new version.

```
fn: base-api@v1 {url: "https://old.api"}
fn: base-api@v2 {url: "https://new.api"}

fn: create-user (based-on: base-api@v1)
  // Bound to a specific version
```

**Pros:**
- Complete predictability
- Change history
- Can rollback

**Cons:**
- More complex UX
- More data

---

### Alternative 3: Graph Without Inheritance

**Idea**: Abandon the "inheritance" concept. Each fn fully defines its arguments.

```
fn: create-user
  fn-schema: http-request
  arg-values: {
    url: "https://api.example.com",  // Can copy from another fn via UI
    method: "POST",
    headers: {...},
    body: {...},
    timeout: 30
  }
```

**Pros:**
- Maximum simplicity
- No override problem at all

**Cons:**
- Duplication when manually creating
- Loss of "partial application" concept

**However**: UI can offer "create based on" = copying with ability to change.

---

### Chosen Approach: Live Inheritance + Explicit Constraints

**Schema with inheritance:**

```
fn:
  id: uuid (PK)
  name: text (UNIQUE)
  fn-schema-id: ref<fn-schema>
  parent-fn-id: ref<fn> (nullable)  // Live inheritance

arg-value:
  id: uuid (PK)
  owner-fn-id: ref<fn>
  arg-schema-id: ref<arg-schema>
  value: union<ref<fn> | literal-types...>
  UNIQUE(owner-fn-id, arg-schema-id)
```

**Constraints are extracted into an explicit protocol** - each storage MUST implement them.

---

## Part 2: Constraints Protocol (GraphConstraints)

### Protocol Definition

**File:** `components/storage-protocol/src/graphden/storage_protocol/interface.clj`

```clojure
(defprotocol GraphConstraints
  "Function graph integrity constraints.
   Each storage MUST implement this protocol.
   Violation of any constraint = exception thrown."

  (validate-parent-same-schema!
    [this fn-id parent-fn-id]
    "Constraint: parent-fn must have the same fn-schema-id as fn.
     Called when creating/modifying fn with parent-fn-id.
     Throws: :constraint-violation/parent-schema-mismatch")

  (validate-no-arg-override!
    [this fn-id arg-schema-id]
    "Constraint: arg-schema-id must not already be defined in the parent chain.
     Called when creating arg-value.
     Throws: :constraint-violation/arg-already-defined")

  (validate-arg-schema-belongs-to-fn!
    [this fn-id arg-schema-id]
    "Constraint: arg-schema must belong to this fn's fn-schema.
     Called when creating arg-value.
     Throws: :constraint-violation/arg-schema-mismatch")

  (validate-no-inheritance-cycle!
    [this fn-id parent-fn-id]
    "Constraint: setting parent-fn-id must not create an inheritance cycle.
     Called when creating/modifying fn with parent-fn-id.
     Throws: :constraint-violation/inheritance-cycle")

  (validate-no-dependency-cycle!
    [this owner-fn-id target-fn-id]
    "Constraint: reference to target-fn must not create a dependency cycle.
     Called when creating arg-value with value = ref<fn>.
     Throws: :constraint-violation/dependency-cycle"))
```

### Implementation Architecture

The implementation uses a **shared validation logic** pattern with pluggable helpers:

```clojure
;; ConstraintHelpers protocol - each storage implements this
(defprotocol ConstraintHelpers
  (get-fn-schema-id [this fn-id])
  (get-parent-fn-id [this fn-id])
  (get-arg-schema-fn-schema-id [this arg-schema-id])
  (arg-defined-for-fn? [this fn-id arg-schema-id])
  (get-dependency-targets [this fn-id]))

;; Shared implementations use helpers
(defn validate-parent-same-schema-impl [storage fn-id parent-fn-id]
  (let [fn-schema (get-fn-schema-id storage fn-id)
        parent-schema (get-fn-schema-id storage parent-fn-id)]
    (when (not= fn-schema parent-schema)
      (throw ...))))
```

**Benefits of this approach:**
- Code reuse across all storage backends
- Each backend optimizes its helpers (SQL CTEs, Datomic queries, in-memory traversal)
- Consistent error messages and behavior

### Implementation in Each Storage

| Storage | Implementation | Optimization |
|---------|----------------|--------------|
| memory | `memory-storage/core.clj` | In-memory maps, O(1) lookups |
| postgres | `postgres-storage/constraints.clj` | SQL recursive CTEs |
| datomic | `datomic-storage/constraints.clj` | Datomic query batching |

---

## Part 3: Recursion and Cycles

### Recursion

**Problem**: A function can reference itself.

```
fn: factorial
  arg-values: {
    n: <input argument>,
    recursive-call: ref<factorial>  // Reference to itself
  }
```

**With lazy execution this works**, if there's a base case:

```clojure
;; Base factorial function
(defn base-factorial [{:keys [n recursive-call]}]
  (if (<= n 1)
    1
    (* n (execute-fn recursive-call {:n (dec n)}))))
```

**Danger**: Infinite recursion when there's no base case.

**Solutions (all implemented):**
1. **Depth limit** - executor has max-depth (default: 1000)
2. **Timeout** - maximum execution time (default: 30000ms)
3. **Runtime detection** - deferred (depth/timeout is sufficient)

### Cyclic Dependencies (Not Recursion)

**Problem:**

```
fn: A
  arg1: ref<B>

fn: B
  arg1: ref<A>
```

**When trying to compute A** -> need B -> need A -> infinity.

**Difference from recursion**: Recursion is one function calling itself (controlled). Cycle is two functions calling each other (uncontrolled).

**Solution**: Forbid cycles when creating arg-value via `validate-no-dependency-cycle!`.

| Storage | Implementation |
|---------|----------------|
| PostgreSQL | Recursive CTE for cycle detection |
| Datomic | Datalog query traversal |
| Memory | DFS on write |

### Mutual Recursion

```
fn: is-even (n) -> if n=0 then true else is-odd(n-1)
fn: is-odd (n) -> if n=0 then false else is-even(n-1)
```

Technically this is a cycle (A->B->A), but this is a VALID pattern.

**How to distinguish from a "bad" cycle?**
- Bad cycle: A needs result of B, B needs result of A (deadlock)
- Good cycle: A calls B with DIFFERENT arguments

**Solution**: Don't forbid at schema level. Protection only at runtime (depth, timeout).

---

## Part 4: Data Schema

### Entities

```
+------------------------------------------------------------------+
| fn-schema (function schema)                                       |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| name: text (UNIQUE)                                               |
| returned-type: enum<value-kind>                                   |
| base-fn-name: text (nullable) - Clojure function name            |
|                                 null = composite function         |
+------------------------------------------------------------------+
         |
         | 1:N
         v
+------------------------------------------------------------------+
| arg-schema (argument schema)                                      |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| fn-schema-id: ref<fn-schema>                                      |
| name: text                                                        |
| type: enum<value-kind>                                            |
| required: bool (default true)                                     |
| UNIQUE(fn-schema-id, name)                                        |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
| fn (function instance)                                            |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| name: text (UNIQUE)                                               |
| fn-schema-id: ref<fn-schema>                                      |
| parent-fn-id: ref<fn> (nullable) - live inheritance              |
+------------------------------------------------------------------+
         |
         | 1:N
         v
+------------------------------------------------------------------+
| fn-result-value (cached computation reference)                    |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| fn-id: ref<fn> - function to execute and cache                   |
+------------------------------------------------------------------+
| Multiple arg-values can reference the same fn-result-value       |
| to share the cached computation result.                          |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
| arg-value (argument value)                                        |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| owner-fn-id: ref<fn>                                              |
| arg-schema-id: ref<arg-schema>                                    |
| value: union<ref<fn> | ref<fn-result-value> | literal-types...>   |
| UNIQUE(owner-fn-id, arg-schema-id)                                |
+------------------------------------------------------------------+
```

### Constraints and Their Implementation

| # | Constraint | PostgreSQL | Datomic | Memory |
|---|------------|------------|---------|--------|
| 1 | fn-schema.name is unique | UNIQUE constraint | :db/unique :db.unique/identity | Set in index |
| 2 | fn.name is unique | UNIQUE constraint | :db/unique | Set in index |
| 3 | arg-schema is unique within fn-schema | UNIQUE(fn-schema-id, name) | Composite tuple + unique | Map<[fn-schema-id, name], id> |
| 4 | arg-value is unique within fn | UNIQUE(owner-fn-id, arg-schema-id) | Composite tuple + unique | Map<[fn-id, arg-schema-id], id> |
| 5 | arg-value.arg-schema-id matches owner-fn.fn-schema-id | Clojure validation | Clojure validation | Clojure validation |
| 6 | No cycles in fn graph through arg-value | Recursive CTE | Datalog query | DFS |

### Constraint #5 in Detail

**Problem**: arg-value references arg-schema, which belongs to fn-schema. owner-fn also references fn-schema. They must match.

**Implementation**: All storage backends use shared Clojure validation via `validate-arg-schema-belongs-to-fn!`.

### Constraint #6 in Detail (Cycles)

**When creating arg-value with value = ref<fn>:**

1. Get target-fn-id from value
2. Recursively collect all fn that target-fn references through arg-values
3. If owner-fn-id is in this set -> REJECT (cycle)

**Implementation**: `validate-no-dependency-cycle!` with backend-specific optimizations.

---

## Part 5: Execution Model

### Laziness and Thunks

**File:** `components/executor/src/graphden/executor/core.clj`

```clojure
(defprotocol IThunk
  (force-value [this context]))

(defrecord LiteralThunk [value]
  IThunk
  (force-value [_ _] value))

(defrecord FnRefThunk [fn-id provided-args]
  IThunk
  (force-value [_ context]
    (execute-internal context fn-id provided-args)))

(defrecord LazyFnThunk [fn-id]
  ;; For arguments of type :fn - don't evaluate, pass as-is
  IThunk
  (force-value [_ _] fn-id))  ; Return fn-id, not the result
```

### Argument Types and Their Handling

| type in arg-schema | Value in arg-value | Thunk | Behavior |
|--------------------|-------------------|-------|----------|
| :int, :text, etc. | Literal | LiteralThunk | force -> literal |
| :int, :text, etc. | ref<fn> | FnRefThunk | force -> execute fn (each time) |
| :int, :text, etc. | ref<fn-result-value> | FnResultValueThunk | force -> execute fn (cached) |
| :fn | ref<fn> | LazyFnThunk | force -> fn-id (for HOF) |

### fn-result-value: Cached Computation

The `fn-result-value` entity enables **caching of function results** within a single execution:

```
fn: report
  sales: ref<fn-result-value:A>     ← A points to calculate-sales
  summary: ref<fn-result-value:A>   ← Same A, result is cached

// calculate-sales executes ONCE, result shared between sales and summary
```

**Use cases:**
1. **Expensive computations** — compute once, reuse result
2. **Consistent snapshots** — same value for multiple consumers
3. **Explicit caching** — user controls what gets cached

**Comparison with direct fn reference:**

| Reference type | Behavior |
|---------------|----------|
| `ref<fn>` with type=:fn | HOF: pass function as value, don't execute |
| `ref<fn>` with other type | Execute function each time arg is forced |
| `ref<fn-result-value>` | Execute function once, cache and reuse result |

### Base Functions and Their Types

```clojure
;; Regular function - all arguments are evaluated before call
(def add-def
  {:args {:nums :jsonb}
   :return-type :numeric
   :impl (fn [{:keys [nums]} _ctx]
           (apply + nums))})

;; Conditional - lazy branches via :lazy-args
(def if-def
  {:args {:condition :bool, :then :any, :else :any}
   :lazy-args #{:then :else}
   :return-type :any
   :impl (fn [{:keys [condition then else]} ctx]
           (if condition
             (exec/force-value then ctx)
             (exec/force-value else ctx)))})

;; HOF - f is passed as fn-id (type :fn)
;; The target function must have exactly 1 required argument (any name)
(def map-def
  {:args {:f :fn, :coll :jsonb}
   :return-type :jsonb
   :impl (fn [{:keys [f coll]} ctx]
           (let [callable (exec/make-single-arg-callable ctx f)]
             (mapv callable coll)))})
```

### Execution Context

```clojure
(defrecord ExecutionContext
  [storage          ; Storage instance for graph resolution
   execution-graph  ; Cached graph data (performance optimization)
   base-fns         ; Registry of base function implementations
   max-depth        ; Maximum recursion depth
   timeout-ms       ; Maximum execution time
   start-time       ; Execution start time
   depth            ; Current recursion depth
   path-args        ; Runtime args: {arg-schema-id -> value} for root,
                    ;               {[fn-result-value-id arg-schema-id] -> value} for nested
   current-frv-id   ; Current fn-result-value-id (nil for root function)
   result-cache])   ; Atom: {fn-result-value-id -> computed-result}
```

**Key design decisions:**
1. **`execution-graph` caching** - Graph resolved once at top level, reused for all nested calls
2. **`base-fns` registry** - Direct access to implementations without global state
3. **`storage` reference** - Enables `ExecutionGraph` protocol calls if needed
4. **`result-cache`** - Shared cache for `fn-result-value` computations within execution
5. **`path-args`** - Runtime values for free arguments, keyed by arg-schema-id or [frv-id arg-schema-id]
6. **`current-frv-id`** - Tracks which fn-result-value is being evaluated (for path-args lookup)

### Limit Checking

```clojure
(defn- check-limits! [context]
  (when (> (:depth context) (:max-depth context))
    (throw (ex-info "Max recursion depth exceeded"
                    {:type :execution-error/max-depth-exceeded
                     :depth (:depth context)
                     :max-depth (:max-depth context)})))
  (let [elapsed (- (System/currentTimeMillis) (:start-time context))]
    (when (> elapsed (:timeout-ms context))
      (throw (ex-info "Execution timeout"
                      {:type :execution-error/timeout
                       :elapsed-ms elapsed
                       :timeout-ms (:timeout-ms context)})))))
```

### Addressing Free Arguments (path-args)

**Problem**: A function may have "free" arguments — arguments without defined values in the database. These must be provided at runtime.

**Solution**: Use `path-args` in the execution context with fixed-length keys.

**Key format:**
- **For root function**: `{arg-schema-id -> value}` — direct arg-schema-id lookup
- **For nested functions via fn-result-value**: `{[fn-result-value-id arg-schema-id] -> value}`

```clojure
;; Example 1: Root function with free argument
;; fn A has free arg with schema-id x-schema-id
(create-context {:storage s
                 :path-args {x-schema-id 42}})
(execute ctx A-id {})

;; Example 2: Nested function via fn-result-value
;; fn A uses fn B via fn-result-value (frv-1)
;; fn B has free arg with schema-id y-schema-id
(create-context {:storage s
                 :path-args {[frv-1-id y-schema-id] 100}})
(execute ctx A-id {})

;; Example 3: Same function used twice with different values
;; fn A references fn B via two fn-result-values: frv-1 and frv-2
;; B has a free arg with schema-id x-schema-id
(create-context {:storage s
                 :path-args {[frv-1-id x-schema-id] 100   ; x for first use of B
                             [frv-2-id x-schema-id] 200}}) ; x for second use of B
```

**Important**: Direct fn refs (HOF with type=:fn) cannot receive path-args. They are "black boxes" controlled by map/reduce/filter. Only functions referenced via `fn-result-value` can have their free args set externally.

### HOF Single-Argument Model

Higher-order functions (map, filter, reduce, etc.) use a **single-argument model** for the functions they invoke:

**Key principle**: Functions passed to HOF must have exactly **one required argument** (any name).

```clojure
;; Function for map/filter - one required arg
(defbase is-positive
  {:args {:n :int}   ; exactly 1 required arg
   :return-type :bool}
  (> n 0))

;; Function for reduce - one required arg receiving [acc item] vector
(defbase sum-pair
  {:args {:pair :jsonb}  ; exactly 1 required arg
   :return-type :int}
  (let [[acc item] pair]
    (+ acc item)))
```

**How HOF works internally:**

1. HOF receives `fn-id` (UUID) for the `:fn` type argument
2. HOF calls `exec/make-single-arg-callable` to create a callable
3. `make-single-arg-callable` finds the single required arg-schema and creates a function that passes values to it

```clojure
;; Inside map implementation
(defbase map-fn
  {:args {:f :fn, :coll :jsonb}
   :return-type :jsonb}
  (let [callable (exec/make-single-arg-callable ctx f)]
    (mapv callable coll)))

;; Inside reduce implementation
(defbase reduce-fn
  {:args {:f :fn, :init :any, :coll :jsonb}
   :return-type :any}
  (let [callable (exec/make-single-arg-callable ctx f)]
    (reduce (fn [acc item] (callable [acc item])) init coll)))
```

**Why single-arg model:**

1. **No naming convention** — user can name their argument anything (`n`, `x`, `value`, etc.)
2. **Simple API** — HOF doesn't need to know argument names
3. **Consistent behavior** — all HOF work the same way
4. **Explicit for reduce** — `[acc item]` vector makes it clear what the function receives

**Usage example in graph:**

```
;; 1. Create function for doubling
fn-schema: double-fn-schema
  arg-schema: x (type: :int, required: true)  ← exactly 1 required arg
  returned-type: :int

fn: double-fn
  fn-schema-id: double-fn-schema

;; 2. Use in map
fn: map-doubles
  fn-schema-id: map-schema
  arg-value: f -> ref<double-fn>      ← fn reference
  arg-value: coll -> [1, 2, 3]        ← literal

;; 3. Execute: map calls double-fn with each element
;; Result: [2, 4, 6]
```

---

## Part 6: System Limitations

### What CANNOT Be Done Elegantly

1. **Constraints #5 and #6** require code - no pure declarative way in SQL/Datomic
   - *Mitigation*: Shared validation logic reduces duplication
2. **Mutual recursion** - cannot distinguish "good" from "bad" statically
   - *Mitigation*: Runtime depth/timeout protection
3. **Full type inference** - this is a separate large task
   - *Mitigation*: Start with explicit types, add inference later

### What Can Break

1. **Infinite recursion** - protection via depth/timeout, but error at runtime
2. **Races during cycle detection** - if two processes create arg-values simultaneously
   - *Mitigation*: PostgreSQL uses transactions; Datomic is inherently serialized
3. **Performance on deep graphs** - many DB queries
   - *Mitigation*: Execution graph caching (implemented)

### Mitigation Summary

1. Aggressive caching of resolved graphs (implemented)
2. Transactions for atomicity (implemented)
3. Monitoring and alerts for deep/long executions (planned)

---

## Part 7: Distributed Execution (Future)

### Overview

Graphden's graph-based representation enables automatic parallelization and distribution of computations. Since functions are stored as a dependency graph rather than sequential text, the executor can:

1. **Identify independent subgraphs** — arguments without mutual dependencies can be computed in parallel
2. **Distribute computation** — large subgraphs can be offloaded to remote executors
3. **Optimize data transfer** — only transfer necessary intermediate results between executors

### Why Graph Structure Enables This

Traditional text-based code requires explicit parallelization (threads, async/await, futures). In graphden:

```
fn: report
  arg1: ref<calculate-sales>      ← Independent
  arg2: ref<calculate-expenses>   ← Independent
  arg3: ref<calculate-inventory>  ← Independent

  // All three can execute in parallel automatically
  // No explicit parallel constructs needed
```

The executor can analyze the graph and determine that `calculate-sales`, `calculate-expenses`, and `calculate-inventory` have no dependencies on each other — they can run concurrently.

### Distributed Execution Model (Planned)

```
┌─────────────────────────────────────────────────────────────┐
│                     Coordinator                              │
│  - Receives execution request                               │
│  - Analyzes graph for parallelization opportunities         │
│  - Partitions subgraphs across available executors          │
│  - Aggregates results                                       │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
         v                    v                    v
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│  Executor 1 │      │  Executor 2 │      │  Executor 3 │
│  (subgraph) │      │  (subgraph) │      │  (subgraph) │
└─────────────┘      └─────────────┘      └─────────────┘
```

### Key Design Questions

**1. Data Transfer Between Executors**

When a subgraph on Executor 2 depends on a result from Executor 1:

| Approach | Pros | Cons |
|----------|------|------|
| Direct transfer (E1 → E2) | Low latency | Complex networking, failure handling |
| Via Coordinator | Simple topology | Coordinator bottleneck |
| Via shared storage | Fault tolerant, resumable | Higher latency |
| Hybrid (small via coordinator, large via storage) | Balanced | Complexity |

**2. Granularity of Distribution**

- **Coarse-grained**: Distribute entire independent branches
- **Fine-grained**: Distribute individual function calls
- **Adaptive**: Start coarse, subdivide based on execution time

**3. State and Side Effects**

Pure functions (no I/O) can be distributed freely. Functions with side effects need:
- Explicit ordering guarantees
- Transaction boundaries
- Idempotency for retry safety

**4. Failure Handling**

- Retry failed subgraphs
- Checkpoint intermediate results
- Fallback to local execution

### Implementation Phases

1. **Local parallelism** — Execute independent args in parallel threads (same JVM)
2. **Worker pool** — Offload to worker processes on same machine
3. **Distributed workers** — Remote executors with network transport
4. **Smart partitioning** — Cost-based optimizer for graph partitioning

### Relevant Existing Components

| Component | Role in Distribution |
|-----------|---------------------|
| `executor` | Base execution, will need parallel/distributed modes |
| `resolve-execution-graph` | Graph analysis for dependency detection |
| `storage` | Shared state, potential intermediate result store |

---

## Appendix A: Component Dependency Graph

```
                    ┌─────────────────────┐
                    │   field-types       │
                    └──────────┬──────────┘
                               │
           ┌───────────────────┼───────────────────┐
           │                   │                   │
           v                   v                   v
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ data-schema-     │ │ storage-protocol │ │ malli-data-      │
│ protocol         │ │                  │ │ schema           │
└────────┬─────────┘ └────────┬─────────┘ └────────┬─────────┘
         │                    │                    │
         └────────────────────┼────────────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         │                    │                    │
         v                    v                    v
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ memory-storage   │ │ postgres-storage │ │ datomic-storage  │
└────────┬─────────┘ └────────┬─────────┘ └────────┬─────────┘
         │                    │                    │
         │        ┌───────────┴───────────┐        │
         │        │                       │        │
         │        v                       │        │
         │ ┌──────────────────┐           │        │
         │ │ graph-data-      │           │        │
         │ │ schema           │           │        │
         │ └────────┬─────────┘           │        │
         │          │                     │        │
         └──────────┼─────────────────────┼────────┘
                    │                     │
         ┌──────────┼─────────────────────┼──────────┐
         │          │                     │          │
         v          v                     v          v
┌────────────────────┐ ┌────────────────────┐ ┌────────────────────┐
│ graph-storage-     │ │ graph-storage-     │ │ graph-storage-     │
│ memory             │ │ postgres           │ │ datomic            │
└────────┬───────────┘ └────────┬───────────┘ └────────┬───────────┘
         │                      │                      │
         │           ┌──────────┴──────────┐           │
         │           │                     │           │
         │           v                     │           │
         │    ┌──────────────┐             │           │
         │    │   executor   │<────────────┼───────────┤
         │    └──────┬───────┘             │           │
         │           │                     │           │
         │           v                     │           │
         │    ┌──────────────┐             │           │
         │    │ base-        │             │           │
         │    │ functions    │             │           │
         │    └──────┬───────┘             │           │
         │           │                     │           │
         │           v                     │           │
         │    ┌──────────────┐             │           │
         │    │ fn-registry  │             │           │
         │    └──────┬───────┘             │           │
         │           │                     │           │
         └───────────┼─────────────────────┼───────────┘
                     │                     │
         ┌───────────┼─────────────────────┼───────────┐
         │           │                     │           │
         v           v                     v           v
┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐
│ graph-with-base-    │ │ graph-with-base-    │ │ graph-with-base-    │
│ fns-memory          │ │ fns-postgres        │ │ fns-datomic         │
└─────────────────────┘ └─────────────────────┘ └─────────────────────┘
```

---

## Appendix B: Error Types

All errors use canonical `:type` keys for programmatic handling:

| Error Type | Description |
|------------|-------------|
| `:constraint-violation/parent-schema-mismatch` | Parent fn has different schema |
| `:constraint-violation/arg-already-defined` | Arg already defined in parent chain |
| `:constraint-violation/arg-schema-mismatch` | Arg schema doesn't belong to fn schema |
| `:constraint-violation/inheritance-cycle` | Circular inheritance detected |
| `:constraint-violation/dependency-cycle` | Circular dependency detected |
| `:execution-error/max-depth-exceeded` | Recursion limit reached |
| `:execution-error/timeout` | Execution time limit reached |
| `:execution-error/invalid-args` | Invalid arguments to base function |
| `:execution-error/division-by-zero` | Division by zero |
| `:execution-error/index-out-of-bounds` | Index out of bounds |
| `:storage-error/*` | Storage-specific errors |
