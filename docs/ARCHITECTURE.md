# Graphden: Visual Functional Programming System

> **Last updated:** 2026-01-18
>
> This document describes the technical architecture of graphden.
> For implementation status and roadmap, see [ROADMAP.md](ROADMAP.md).

## Table of Contents

1. [Critical Model Analysis](#part-1-critical-model-analysis) - Design decisions and alternatives
2. [Constraints Protocol](#part-2-constraints-protocol-graphconstraints) - Graph integrity
3. [Recursion and Cycles](#part-3-recursion-and-cycles) - Handling recursive patterns
4. [Data Schema](#part-4-data-schema) - Entity definitions
5. [Execution Model](#part-5-execution-model) - Lazy evaluation with thunks
6. [Execution Graph Caching](#part-6-execution-graph-caching) - O(1) graph resolution
7. [System Limitations](#part-7-system-limitations) - Known constraints and mitigations
8. [Distributed Execution](#part-8-distributed-execution-future) - Parallelization and distribution
9. [Appendices](#appendix-a-component-dependency-graph) - Reference material

---

## Part 1: Design Overview

### Simple Graph Model

Each function (`fn`) directly defines its arguments through `arg-value` entities:

```
fn: create-user
  fn-schema: http-request
  arg-values: {
    url: "https://api.example.com",
    method: "POST",
    headers: {...},
    body: {...}
  }
```

**Schema:**

```
fn:
  id: uuid (PK)
  name: text (UNIQUE)
  fn-schema-id: ref<fn-schema>

arg-value:
  id: uuid (PK)
  owner-fn-id: ref<fn>
  arg-schema-id: ref<arg-schema>
  value: union<ref<fn> | literal-types...>
  UNIQUE(owner-fn-id, arg-schema-id)
```

**Benefits:**
- Each fn is self-contained
- Simple constraint: one arg-schema-id per fn = simple `UNIQUE(owner-fn-id, arg-schema-id)`
- No recursive checks needed
- UI can offer "create based on" = copying with ability to change

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

  (validate-arg-schema-belongs-to-fn!
    [this fn-id arg-schema-id]
    "Constraint: arg-schema must belong to this fn's fn-schema.
     Called when creating arg-value.
     Throws: :constraint-violation/arg-schema-mismatch")

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
  (get-fn-schema-id-for-fn [this fn-id])
  (get-fn-schema-id-for-arg-schema [this arg-schema-id])
  (collect-dependency-chain [this fn-id]))
```

**Benefits of this approach:**
- Code reuse across all storage backends
- Each backend optimizes its helpers (SQL queries, Datomic queries, in-memory traversal)
- Consistent error messages and behavior

### Implementation in Each Storage

| Storage | Implementation | Optimization |
|---------|----------------|--------------|
| memory | `memory-storage/core.clj` | In-memory maps, O(1) lookups |
| postgres | `postgres-storage/constraints.clj` | SQL queries |
| datomic | `datomic-storage/constraints.clj` | Datomic queries |

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
+------------------------------------------------------------------+
         |
         | 1:N
         v
+------------------------------------------------------------------+
| call-site (function call site reference)                    |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| fn-id: ref<fn> - function to execute at this call site           |
| name: text (UNIQUE) - identifier for this call site              |
+------------------------------------------------------------------+
| PRIMARY PURPOSE: Distinguish same function at different call     |
| sites. Example: current-time before sleep vs after sleep.        |
|                                                                  |
| SECONDARY: Results are cached within execution (memoization).    |
|                                                                  |
| FREE ARGUMENTS: If fn has unbound args, pass them at runtime     |
| via call-site-args: {[call-site-id arg-schema-id] value}   |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
| arg-value (argument value)                                        |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| owner-fn-id: ref<fn>                                              |
| arg-schema-id: ref<arg-schema>                                    |
| value: union<ref<fn> | ref<call-site> | literal-types...>   |
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
| 6 | No cycles in fn graph through arg-value | SQL query | Datalog query | DFS |

### Constraint #5 in Detail (Arg-Schema Belongs to Fn)

**Problem**: arg-value references arg-schema, which belongs to fn-schema. owner-fn also references fn-schema. They must match.

**Implementation**: All storage backends use shared Clojure validation via `validate-arg-schema-belongs-to-fn!`.

### Constraint #6 in Detail (No Dependency Cycles)

**When creating arg-value with value = ref<fn>:**

1. Get target-fn-id from value
2. Recursively collect all fn that target-fn references through arg-values
3. If owner-fn-id is in this set -> REJECT (cycle)

**Implementation**: `validate-no-dependency-cycle!` with backend-specific optimizations.

---

## Part 5: Execution Model

### Laziness via Clojure Delays

**File:** `components/executor/src/graphden/executor/argument_resolution.clj`

Arguments are wrapped in Clojure `delay` objects for lazy evaluation. This native approach:
- Leverages Clojure's built-in memoization (delays compute once, cache result)
- Enables lazy evaluation — values are only computed when dereferenced with `@`
- Provides simple, idiomatic error handling

```clojure
;; Literal value → immediate delay
(delay value)

;; fn reference (type = :fn) → delay returning UUID for HOF
(delay uuid-value)

;; fn reference (other type) → delay that executes function
(wrap-delay-with-context arg-name :fn-ref
  #(execute-internal context uuid-value nil))

;; call-site reference → delay with caching
(wrap-delay-with-context arg-name :call-site
  #(execute-call-site context uuid-value))
```

### Argument Resolution Priority

Arguments are resolved in this order (highest priority first):

1. **provided-args** — Explicitly passed at execute time (from HOF callables)
2. **call-site-args** — Runtime args from context for specific call sites
3. **arg-values** — Stored values from database (resolved-args in graph)
4. Required arg with no value → error
5. Optional arg with no value → delay returning nil

### Argument Types and Their Handling

| type in arg-schema | Value in arg-value | Delay Behavior |
|--------------------|-------------------|----------------|
| :int, :text, etc. | Literal | `@delay` → literal value |
| :int, :text, etc. | ref<fn> | `@delay` → executes fn each time forced |
| :int, :text, etc. | ref<call-site> | `@delay` → executes fn (cached in result-cache) |
| :fn | ref<fn> | `@delay` → fn-id UUID (for HOF) |

### call-site: Call Site Identity

**Primary purpose:** Distinguish the same function called at different points in the graph.

```
;; Problem: How to get time BEFORE and AFTER sleep?
;; Both would reference the same fn: current-time

;; Solution: Two call-sites pointing to the same fn
call-site: time-before  → fn: current-time
call-site: time-after   → fn: current-time

fn: my-program
  t1: ref<call-site:time-before>   ← first call site
  wait: ref<call-site:sleep-5s>
  t2: ref<call-site:time-after>    ← second call site (different!)
```

**Secondary purpose:** Results are cached within execution (memoization).

```
fn: report
  sales: ref<call-site:A>     ← A points to calculate-sales
  summary: ref<call-site:A>   ← Same A, result is cached

// calculate-sales executes ONCE, result shared between sales and summary
```

**Free arguments:** If the referenced fn has unbound arguments, pass them at runtime:

```clojure
;; fn-a has free argument arg-schema-a
;; call-site-a points to fn-a

(execute ctx root-fn-id
         {[call-site-a-id arg-schema-a-id] 42})
```

**Comparison with direct fn reference:**

| Reference type | Behavior |
|---------------|----------|
| `ref<fn>` with type=:fn | HOF: pass function as value, don't execute |
| `ref<fn>` with other type | Execute function each time arg is forced |
| `ref<call-site>` | Execute at this call site, cache result, support free args |

### Base Functions and Their Types

Base functions receive arguments as delays and use `@` (deref) to get values:

```clojure
;; Regular function - deref all arguments
;; Uses defbase macro from fn-registry for automatic delay handling
(defbase add
  {:args {:nums :jsonb}
   :return-type :numeric}
  (apply + nums))  ; defbase auto-derefs, so 'nums' is the value

;; Manual implementation shows the raw delay handling:
(def add-def
  {:args {:nums :jsonb}
   :return-type :numeric
   :impl (fn [delays _ctx]
           (apply + @(:nums delays)))})  ; must deref manually

;; Conditional - only deref the branch we need (true laziness)
(defbase if-fn
  {:args {:condition :bool, :then :any, :else :any}
   :lazy-args #{:then :else}  ; don't auto-deref these
   :return-type :any}
  (if condition
    @then    ; manually deref chosen branch
    @else))

;; HOF - f is passed as fn-id (type :fn), not executed
;; The target function must have exactly 1 required argument (any name)
(defbase map-fn
  {:args {:f :fn, :coll :jsonb}
   :return-type :jsonb}
  (let [callable (exec/make-single-arg-callable ctx f)]
    (mapv callable coll)))
```

### Execution Context

**File:** `components/executor/src/graphden/executor/context.clj`

```clojure
(defrecord ExecutionContext
  [storage           ; Storage instance for graph resolution
   execution-graph   ; Cached graph data (resolved once at top level)
   base-fns          ; Registry of base function implementations
   max-depth         ; Maximum recursion depth (default: 1000)
   timeout-ms        ; Maximum execution time (default: 30000ms)
   start-time        ; Execution start time
   depth             ; Current recursion depth
   call-site-args    ; Runtime args: {arg-schema-id -> value} for root,
                     ;               {[call-site-id arg-schema-id] -> value} for call sites
   current-call-site-id  ; Current call-site-id (nil for root function)
   result-cache      ; Atom: {call-site-id -> computed-result}
   strict-type-validation?  ; If true (default), throw on unknown types
   max-unknown-types ; Circuit breaker for forward compat mode (default: 10)
   unknown-type-counter     ; Atom: count of unknown types encountered
   clock             ; Function returning current time (for testing)
   cache-warning-threshold  ; Warn when cache reaches this size (default: 1000)
   cache-max-size])  ; Hard limit on cache size (default: 10000)
```

**Key design decisions:**
1. **`execution-graph` caching** — Graph resolved once at top level, reused for all nested calls
2. **`base-fns` registry** — Direct access to implementations without global state
3. **`storage` reference** — Enables `ExecutionGraph` protocol calls if needed
4. **`result-cache`** — Shared cache for `call-site` computations within execution
5. **`call-site-args`** — Runtime values for free arguments, keyed by arg-schema-id or [call-site-id arg-schema-id]
6. **`current-call-site-id`** — Tracks which call-site is being evaluated (for call-site-args lookup)
7. **`clock`** — Injectable time source for deterministic timeout testing
8. **Forward compatibility** — `strict-type-validation?` + circuit breaker for schema migrations

### Limit Checking

```clojure
(defn- check-limits! [context]
  (check-depth-limit! context)   ; throws :execution-error/max-depth-exceeded
  (check-timeout-limit! context)) ; throws :execution-error/timeout
```

**Important**: Timeout is checked at the START of each function call, not during execution. A long-running base function will complete fully even if it exceeds the timeout. For precise timeout control, base functions should implement their own timeout logic.

### Lazy Sequence Protection

**File:** `components/executor/src/graphden/executor/argument_resolution.clj`

Lazy sequences are a potential DoS vector — an attacker could pass `(range)` (infinite sequence) as an argument. The executor protects against this:

```clojure
;; Configuration (in storage-protocol/config.clj)
(def ^:dynamic *max-lazy-seq-size* 100000)        ; max elements
(def ^:dynamic *max-nested-collection-depth* 100) ; max nesting

;; Lazy sequences are realized with bounds when creating delays
(realize-lazy-value value)
;; - Lazy seqs → vectors (with size limit)
;; - Nested maps → recursively realized (with depth limit)
;; - Throws :execution-error/lazy-seq-too-large or :collection-too-deep
```

This ensures errors occur at argument evaluation time, not during consumption by base functions.

### Addressing Free Arguments (call-site-args)

**Problem**: A function may have "free" arguments — arguments without defined values in the database. These must be provided at runtime.

**Solution**: Use `call-site-args` in the execution context with fixed-length keys.

**Key format:**
- **For root function**: `{arg-schema-id -> value}` — direct arg-schema-id lookup
- **For nested functions via call-site (call site)**: `{[call-site-id arg-schema-id] -> value}`

```clojure
;; Example 1: Root function with free argument
;; fn A has free arg with schema-id x-schema-id
(create-context {:storage s
                 :call-site-args {x-schema-id 42}})
(execute ctx A-id {})

;; Example 2: Nested function via call-site (call site)
;; fn A uses fn B via call-site (cs-1)
;; fn B has free arg with schema-id y-schema-id
(create-context {:storage s
                 :call-site-args {[cs-1-id y-schema-id] 100}})
(execute ctx A-id {})

;; Example 3: Same function used twice with different values at different call sites
;; fn A references fn B via two call-sites: cs-1 and cs-2
;; B has a free arg with schema-id x-schema-id
(create-context {:storage s
                 :call-site-args {[cs-1-id x-schema-id] 100   ; x for first call site
                                  [cs-2-id x-schema-id] 200}}) ; x for second call site
```

**Important**: Direct fn refs (HOF with type=:fn) cannot receive call-site-args. They are "black boxes" controlled by map/reduce/filter. Only functions referenced via `call-site` (call sites) can have their free args set externally.

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

## Part 5.5: Function Composition (fn-defs)

### Two-Layer Architecture

Graphden separates function definitions into two layers:

**Layer 1: Base Functions** — Clojure implementations

```clojure
;; In base-functions, http-kit-fns, reitit-fns components
(defbase const
  "Returns a constant function that ignores input and returns x."
  {:args {:x :any}
   :return-type :fn}
  (fn [_] x))

(defbase assoc-fn
  {:args {:m :jsonb, :k :text, :v :any}
   :return-type :jsonb}
  (assoc m k v))
```

**Layer 2: Fn Entities (fn-defs)** — Pure data compositions

```clojure
;; In web-server/core.clj - no Clojure code, only data
[{:name :hello-handler-fn
  :parent :const
  :args {:x {:status 200 :body "Hello"}}}

 {:name :web-server-fn
  :parent :http-server
  :args {:handler :router-fn>
         :port 8080}}]
```

### Fn-def Syntax

Each fn-def is a map with:
- `:name` — unique keyword identifier
- `:parent` — base function or another fn-def to inherit from
- `:args` — argument values (literals or references)

### Reference Syntax: `:fn-name` vs `:fn-name>`

The `>` suffix determines whether to execute the referenced function:

| Syntax | Storage Entity | Execution Behavior |
|--------|---------------|-------------------|
| `:fn-name` | `ref<fn>` | Pass fn-id (UUID), don't execute |
| `:fn-name>` | `ref<call-site>` | Execute fn, use result value |

**When to use each:**

```clojure
;; WITHOUT > — Pass fn as value (for HOF)
{:name :double-all
 :parent :map-fn
 :args {:f :double-fn     ; map-fn will call double-fn for each element
        :coll [1 2 3]}}

;; WITH > — Execute and use result
{:name :web-server-fn
 :parent :http-server
 :args {:handler :router-fn>  ; router returns Clojure fn, pass that fn
        :port 8080}}
```

### defbase Arg Type `:fn` vs `:any`

The arg type in defbase controls special handling:

| Arg Type | What defbase does |
|----------|-------------------|
| `:fn` | Auto-wraps with `make-single-arg-callable` for HOF |
| `:any` | No special processing, receives value as-is |

**Critical distinction:**

```clojure
;; map-fn receives fn-id, needs to create callable from it
(defbase map-fn
  {:args {:f :fn, :coll :jsonb}  ; :fn → f will be wrapped in callable
   :return-type :jsonb}
  (let [callable (exec/make-single-arg-callable ctx f)]
    (mapv callable coll)))

;; http-server receives already-executed Clojure fn
(defbase http-server
  {:args {:handler :any, :port :int}  ; :any → handler is Clojure fn, not fn-id
   :return-type :any}
  (http-kit/run-server handler {:port port}))
```

### Complete Example: Building a Web Server

```clojure
;; === Base functions (Clojure implementations) ===
;; const: returns (fn [_] x) — a constant function
;; assoc: returns (assoc m k v)
;; conj: returns (conj coll x)
;; router: creates Ring router from routes, returns Ring handler fn
;; http-server: starts http-kit with handler fn

;; === Fn-defs (data composition) ===
[;; 1. Handler: const returns (fn [_] response)
 {:name :hello-handler-fn
  :parent :const
  :args {:x {:status 200 :body "Hello"}}}

 ;; 2. Route data: {"handler" <clojure-fn>}
 ;; Note: :hello-handler-fn> executes const, gets the fn it returns
 {:name :hello-handler-map-fn
  :parent :assoc
  :args {:m {}, :k "handler", :v :hello-handler-fn>}}

 ;; 3. Method map: {"get" {"handler" <fn>}}
 {:name :hello-method-map-fn
  :parent :assoc
  :args {:m {}, :k "get", :v :hello-handler-map-fn>}}

 ;; 4. Route tuple: ["/" {"get" {"handler" <fn>}}]
 {:name :hello-route-path-fn
  :parent :conj
  :args {:coll [], :x "/"}}

 {:name :hello-route-fn
  :parent :conj
  :args {:coll :hello-route-path-fn>, :x :hello-method-map-fn>}}

 ;; 5. Routes collection: [["/" {...}]]
 {:name :routes-fn
  :parent :conj
  :args {:coll [], :x :hello-route-fn>}}

 ;; 6. Router: creates Ring handler from routes
 {:name :router-fn
  :parent :router
  :args {:routes :routes-fn>}}

 ;; 7. Server: starts http-kit with router as handler
 ;; :router-fn> executes router and passes the Ring handler fn
 {:name :web-server-fn
  :parent :http-server
  :args {:handler :router-fn>
         :port 8080}}]
```

### Execution Flow

When executing `:web-server-fn`:

1. Resolve `:router-fn>` → execute `:router-fn`
2. `:router-fn` needs `:routes-fn>` → execute `:routes-fn`
3. Continue recursively until all `>` refs are resolved
4. `:const` returns `(fn [_] response)` — this Clojure fn propagates up
5. `:http-server` receives Clojure fn as `:handler`, starts server

### Key Insight

The `>` suffix creates a **call-site** entity in storage, which means:
- Result is computed once and cached within execution
- Multiple references to same `:fn-name>` share the cached result
- Without `>`, you pass the fn-id for HOF to call multiple times

---

## Part 6: Execution Graph Caching

### The Problem

Without caching, every function execution requires:
1. Resolve the execution graph (recursive queries to load fns, schemas, args)
2. Resolve all argument values and their references
3. Classify UUID references

For complex graphs, this means multiple database queries per execution.

### Solution: CacheStorage Protocol

The caching layer provides O(1) access to precomputed execution graphs:

```clojure
(defprotocol CacheStorage
  (get-cached-graph [this fn-id])      ; O(1) cache lookup
  (save-cache! [this fn-id graph deps]) ; Store with dependencies
  (delete-cache! [this fn-id])          ; Explicit invalidation
  (find-caches-by-fn-dep [this dep-fn-id])        ; Find affected caches
  (find-caches-by-fn-schema-dep [this dep-fn-schema-id])
  (find-caches-by-arg-schema-dep [this dep-arg-schema-id]))
```

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    CachedStorage                             │
│  (decorator wrapping base storage + cache)                  │
├─────────────────────────────────────────────────────────────┤
│  resolve-execution-graph:                                    │
│    1. Check cache → return if hit                           │
│    2. Call base storage → compute graph                     │
│    3. Save to cache with dependencies                       │
│    4. Return graph                                          │
├─────────────────────────────────────────────────────────────┤
│  CRUD operations:                                            │
│    1. Delegate to base storage                              │
│    2. Invalidate affected caches                            │
└─────────────────────────────────────────────────────────────┘
         │                              │
         v                              v
┌─────────────────┐          ┌─────────────────┐
│   Base Storage  │          │  Cache Storage  │
│ (postgres, etc) │          │ (cache-postgres)│
└─────────────────┘          └─────────────────┘
```

### Cache Invalidation Strategy

The cache tracks dependencies with ref-counts for proper invalidation:

```clojure
{:fn-ids {fn-id-1 -> 2, fn-id-2 -> 1}        ; fn referenced 2 times
 :fn-schema-ids {schema-id -> 1}              ; schema referenced 1 time
 :arg-schema-ids {arg-schema-id-1 -> 3}}      ; arg-schema referenced 3 times
```

**Invalidation triggers:**

| Entity | Operation | Action |
|--------|-----------|--------|
| fn | created | Create cache for new fn |
| fn | updated | Invalidate fn + all dependents |
| fn | deleted | Delete cache + invalidate dependents |
| arg-value | created/updated/deleted | Invalidate owner-fn + dependents |
| fn-schema | updated | Invalidate all caches using this schema |
| arg-schema | updated | Invalidate all caches using this arg-schema |

### Implementations

| Component | Backend | Use Case |
|-----------|---------|----------|
| cache-memory | In-memory maps | Tests, single-process apps |
| cache-postgres | PostgreSQL tables | Production, multi-process |
| cache-datomic | Datomic entities | Immutable history, audit |

### Usage

```clojure
(require '[graphden.postgres-storage.interface :as pg]
         '[graphden.cache-postgres.interface :as cache-pg]
         '[graphden.cached-storage.interface :as cached])

;; Create base storage and cache
(def storage (pg/create-storage config))
(def cache (cache-pg/create-cache config))

;; Wrap with caching
(def cached-storage (cached/wrap-with-cache storage cache))

;; Use normally - caching is transparent
(sp/resolve-execution-graph cached-storage fn-id)  ; O(1) after first call
```

### Performance Characteristics

| Operation | Without Cache | With Cache (hit) | With Cache (miss) |
|-----------|--------------|------------------|-------------------|
| resolve-execution-graph | O(depth) queries | O(1) lookup | O(depth) + O(1) save |
| create-entity :fn | O(1) | O(1) + cache build | - |
| update-entity :arg-value | O(1) | O(1) + invalidation cascade | - |

---

## Part 7: System Limitations

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

## Part 8: Distributed Execution (Future)

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

## Appendix A: Three-Tier Pattern Rationale

Nine components follow the `*-{memory|postgres|datomic}` naming pattern:
- `memory-storage`, `postgres-storage`, `datomic-storage`
- `cache-memory`, `cache-postgres`, `cache-datomic`
- `graph-storage-memory`, `graph-storage-postgres`, `graph-storage-datomic`

**Why not a single shared implementation?**

Each backend has fundamentally different constraints and optimal implementations:

| Operation | PostgreSQL | Datomic | Memory |
|-----------|------------|---------|--------|
| Parent chain traversal | Recursive CTE (single query) | Datalog recursive rule | In-memory loop |
| Dependency cycle detection | WITH RECURSIVE + array tracking | d/q with accumulator | BFS with visited set |
| Unique constraint | DB-level UNIQUE constraint | :db.unique/identity | In-memory index check |
| Transaction isolation | SERIALIZABLE isolation | Datomic ACID transactions | Clojure atoms |

**Trade-offs accepted:**

1. **Code duplication** (~30% similar code across 3 backends) in exchange for:
   - Optimal performance per backend
   - Backend-specific error handling
   - Simpler debugging (no abstraction layers)

2. **Maintenance cost** mitigated by:
   - Contract tests validating all backends identically (`contract_tests.clj`)
   - Protocol-first design ensuring consistent interfaces
   - Shared validation logic in `storage-protocol`

**When to add a new backend:**

1. Implement all protocols from `storage-protocol/interface.clj`
2. Use `backend_template.clj` as starting point
3. Run contract tests to verify compliance
4. Add backend-specific optimizations (e.g., recursive CTEs for SQL)

---

## Appendix B: Component Dependency Graph

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

## Appendix C: Error Types

See [ERROR_CODES.md](ERROR_CODES.md) for the complete error types reference.
