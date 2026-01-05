# Graphden: Visual Functional Programming System

> **Last updated:** 2026-01-05
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
7. [Appendices](#appendix-a-component-dependency-graph) - Reference material

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
| arg-value (argument value)                                        |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| owner-fn-id: ref<fn>                                              |
| arg-schema-id: ref<arg-schema>                                    |
| value: union<ref<fn> | literal-types...>                          |
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
| :int, :text, etc. | ref<fn> | FnRefThunk | force -> execute fn |
| :fn | ref<fn> | LazyFnThunk | force -> fn-id (for HOF) |

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
(def map-def
  {:args {:f :fn, :coll :jsonb}
   :return-type :jsonb
   :impl (fn [{:keys [f coll]} ctx]
           (mapv (fn [item]
                   (exec/execute-with-named-args ctx f {:item item}))
                 coll))})
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
   depth])          ; Current recursion depth
```

**Key design decisions:**
1. **`execution-graph` caching** - Graph resolved once at top level, reused for all nested calls
2. **`base-fns` registry** - Direct access to implementations without global state
3. **`storage` reference** - Enables `ExecutionGraph` protocol calls if needed

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

### Addressing Free Arguments

**Problem**: fn A uses fn B twice. B has a free argument x. How to pass different values of x?

**Solution**: Path through arg-value-id.

```clojure
;; In DB:
;; arg-value-1: {owner: A, arg-schema: arg1-of-A, value: ref<B>}
;; arg-value-2: {owner: A, arg-schema: arg2-of-A, value: ref<B>}
;;
;; B has a free arg-schema: x-of-B

;; Request to execute A:
{:fn-id A-id
 :args {[arg-value-1-id x-of-B-id] 100   ; x for first B
        [arg-value-2-id x-of-B-id] 200}} ; x for second B
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
