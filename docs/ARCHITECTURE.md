# Graphden: Visual Functional Programming System

> **Last updated:** 2026-03-03
>
> This document describes the technical architecture of graphden.
> For implementation status and roadmap, see [ROADMAP.md](ROADMAP.md).

## Table of Contents

1. [Critical Model Analysis](#part-1-critical-model-analysis) - Design decisions and alternatives
2. [Constraints Protocol](#part-2-constraints-protocol-graphconstraints) - Graph integrity
3. [Recursion and Cycles](#part-3-recursion-and-cycles) - Handling recursive patterns
4. [Data Schema](#part-4-data-schema) - Entity definitions
5. [Execution Model](#part-5-execution-model) - Lazy evaluation with delays
6. [System Limitations](#part-6-system-limitations) - Known constraints and mitigations
7. [Distributed Execution](#part-7-distributed-execution-future) - Parallelization and distribution
8. [Appendices](#appendix-a-component-dependency-graph) - Reference material

---

## Part 1: Design Overview

### Simple Graph Model

Each function (`fn`) has argument bindings via `fn-arg` entities pointing to `arg-value` entities:

```
fn: create-user
  fn-schema: http-request
  fn-args: [
    {arg: url, value: "https://api.example.com"},
    {arg: method, value: "POST"},
    {arg: headers, value: {...}},
    {arg: body, value: {...}}
  ]
```

**Schema (normalized):**

```
fn:
  id: uuid (PK)
  name: text
  fn-schema-id: ref<fn-schema>
  owner-fn-id: ref<fn> (nullable) - for local scoping
  UNIQUE(owner-fn-id, name)

arg-value:
  id: uuid (PK)
  arg-schema-id: ref<arg-schema>
  value: JSONB (nullable) - literal value
  fn-usage-id: ref<fn-usage> (nullable) - reference to fn-usage
  XOR constraint: exactly one of (value, fn-usage-id) must be set
  UNIQUE(arg-schema-id, value) or UNIQUE(arg-schema-id, fn-usage-id) - deduplication

fn-arg:
  id: uuid (PK)
  fn-id: ref<fn>
  arg-schema-id: ref<arg-schema>
  arg-value-id: ref<arg-value>
  UNIQUE(fn-id, arg-schema-id)
```

**Benefits:**
- arg-value is pure (no owner) - enables deduplication
- fn-arg is the binding between fn and arg-value
- Local scoping via fn.owner-fn-id
- UI can offer "create based on" = copying with ability to change

**Constraints are extracted into an explicit protocol** - each storage MUST implement them.

---

## Part 2: Constraints Protocol (GraphConstraints)

### Protocol Definition

**File:** `src/graphden/storage/protocol/interface.clj`

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

### Implementation in Storage Backends

| Storage | Implementation | Optimization |
|---------|----------------|--------------|
| postgres | `storage/postgres/constraints.clj` | SQL queries |
| graph-storage-age | `storage/age/graph.clj` | Cypher queries via Apache AGE |

---

## Part 3: Recursion and Cycles

### Recursion

**Problem**: A function can reference itself.

```
fn: factorial
  fn-args: [
    {arg: n, value: <input argument>},
    {arg: recursive-call, value: ref<factorial>}  // Reference to itself
  ]
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
| Apache AGE | Cypher path queries |

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
| first-class: bool (default true) - visible in UI                 |
| UNIQUE(fn-schema-id, name)                                        |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
| fn (function instance)                                            |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| name: text                                                        |
| fn-schema-id: ref<fn-schema>                                      |
| owner-fn-id: ref<fn> (nullable) - for local scoping              |
| UNIQUE(owner-fn-id, name)                                         |
+------------------------------------------------------------------+
         |
         | 1:N (via fn-arg)
         v
+------------------------------------------------------------------+
| fn-arg (argument binding)                                         |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| fn-id: ref<fn>                                                    |
| arg-schema-id: ref<arg-schema>                                    |
| arg-value-id: ref<arg-value>                                      |
| UNIQUE(fn-id, arg-schema-id)                                      |
+------------------------------------------------------------------+
         |
         | N:1
         v
+------------------------------------------------------------------+
| arg-value (argument value - pure, no owner)                       |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| arg-schema-id: ref<arg-schema>                                    |
| value: JSONB (nullable) - literal value                           |
| fn-usage-id: ref<fn-usage> (nullable) - reference to fn-usage    |
| XOR: exactly one of (value, fn-usage-id) must be set             |
| UNIQUE(arg-schema-id, value/fn-usage-id) - enables deduplication  |
+------------------------------------------------------------------+

+------------------------------------------------------------------+
| fn-usage (function usage reference)                               |
+------------------------------------------------------------------+
| id: uuid (PK)                                                     |
| fn-id: ref<fn> - function to execute at this usage               |
| name: text - identifier for this fn-usage                        |
| owner-fn-id: ref<fn> (nullable) - owning fn for local scoping    |
| UNIQUE(owner-fn-id, name)                                         |
+------------------------------------------------------------------+
| PRIMARY PURPOSE: Distinguish same function at different usage    |
| points. Example: current-time before sleep vs after sleep.       |
|                                                                  |
| SECONDARY: Results are cached within execution (memoization).    |
+------------------------------------------------------------------+
```

### Constraints and Their Implementation

| # | Constraint | PostgreSQL | Apache AGE |
|---|------------|------------|------------|
| 1 | fn-schema.name is unique | UNIQUE constraint | UNIQUE constraint |
| 2 | fn.name is unique within scope | UNIQUE(owner-fn-id, name) | UNIQUE(owner-fn-id, name) |
| 3 | arg-schema is unique within fn-schema | UNIQUE(fn-schema-id, name) | UNIQUE(fn-schema-id, name) |
| 4 | arg-value deduplication | UNIQUE(arg-schema-id, value) | UNIQUE(arg-schema-id, value) |
| 5 | fn-arg is unique per fn | UNIQUE(fn-id, arg-schema-id) | UNIQUE(fn-id, arg-schema-id) |
| 6 | fn-arg.arg-schema-id matches fn.fn-schema-id | Clojure validation | Clojure validation |
| 7 | No cycles in fn graph through fn-arg | Recursive CTE | Cypher path query |

### Constraint #6 in Detail (Arg-Schema Belongs to Fn)

**Problem**: fn-arg binds arg-value to fn. The arg-schema-id must belong to the fn's fn-schema.

**Implementation**: All storage backends use shared Clojure validation via `validate-arg-schema-belongs-to-fn!`.

### Constraint #7 in Detail (No Dependency Cycles)

**When creating fn-arg with arg-value.value = ref<fn>:**

1. Get target-fn-id from arg-value.value
2. Recursively collect all fns that target-fn references through fn-arg → arg-value
3. If fn-id (the fn being bound) is in this set -> REJECT (cycle)

**Implementation**: `validate-no-dependency-cycle!` with backend-specific optimizations.

---

## Part 5: Execution Model

### Laziness via Clojure Delays

**File:** `src/graphden/executor/argument_resolution.clj`

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

;; fn-usage reference → delay with caching
(wrap-delay-with-context arg-name :fn-usage
  #(execute-fn-usage context uuid-value))
```

### Argument Resolution Priority

Arguments are resolved in this order (highest priority first):

1. **fn-arg bindings** — Stored bindings from database (fn-arg → arg-value, always takes precedence)
2. **provided-args** — Explicitly passed at execute time (from HOF callables, only for free args)
3. Required arg with no value → error
4. Optional arg with no value → delay returning nil

### Argument Types and Their Handling

**arg-value has two mutually exclusive fields:**
- `value` (JSONB) - literal value
- `fn-usage-id` (ref<fn-usage>) - reference to fn-usage

**Runtime behavior is controlled by `arg-schema.first-class` flag:**

| arg-value field | arg-schema.first-class | Delay Behavior |
|-----------------|------------------------|----------------|
| value (literal) | any | `@delay` → literal value |
| fn-usage-id | false | `@delay` → execute fn-usage, use result (cached) |
| fn-usage-id | true | `@delay` → get fn-id from fn-usage, pass directly (for HOF) |

**Key principle:** The FUNCTION (via arg-schema.first-class) decides how to handle the argument, NOT the caller.

### fn-usage: Call Site Identity

**Primary purpose:** Distinguish the same function called at different points in the graph.

```
;; Problem: How to get time BEFORE and AFTER sleep?
;; Both would reference the same fn: current-time

;; Solution: Two fn-usages pointing to the same fn
fn-usage: time-before  → fn: current-time
fn-usage: time-after   → fn: current-time

fn: my-program
  t1: ref<fn-usage:time-before>   ← first call site
  wait: ref<fn-usage:sleep-5s>
  t2: ref<fn-usage:time-after>    ← second call site (different!)
```

**Secondary purpose:** Results are cached within execution (memoization).

```
fn: report
  sales: ref<fn-usage:A>     ← A points to calculate-sales
  summary: ref<fn-usage:A>   ← Same A, result is cached

// calculate-sales executes ONCE, result shared between sales and summary
```

**Free arguments:** If the referenced fn has unbound arguments, pass them at runtime:

```clojure
;; fn-a has free argument arg-schema-a
;; fn-usage-a points to fn-a

(execute ctx root-fn-id
         {[fn-usage-a-id arg-schema-a-id] 42})
```

**How fn-usage works:**

All arg-value references use fn-usage-id (never direct fn-id). Behavior depends on arg-schema.first-class:

| arg-schema.first-class | Behavior |
|------------------------|----------|
| true | Get fn-id from fn-usage, pass directly (for HOF) |
| false | Execute fn-usage, cache result, support free args |

**Key insight:** The function (via arg-schema) decides how to handle the argument, NOT the caller.

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

**File:** `src/graphden/executor/context.clj`

```clojure
(defrecord ExecutionContext
  [storage           ; Storage instance for graph resolution
   execution-graph   ; Cached graph data (resolved once at top level)
   base-fns          ; Registry of base function implementations
   max-depth         ; Maximum recursion depth (default: 1000)
   timeout-ms        ; Maximum execution time (default: 30000ms)
   start-time        ; Execution start time
   depth             ; Current recursion depth
   current-fn-usage-id  ; Current fn-usage-id (nil for root function)
   result-cache      ; Atom: {fn-usage-id -> computed-result}
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
4. **`result-cache`** — Shared cache for `fn-usage` computations within execution
5. **`current-fn-usage-id`** — Tracks which fn-usage is being evaluated
6. **`clock`** — Injectable time source for deterministic timeout testing
7. **Forward compatibility** — `strict-type-validation?` + circuit breaker for schema migrations

### Limit Checking

```clojure
(defn- check-limits! [context]
  (check-depth-limit! context)   ; throws :execution-error/max-depth-exceeded
  (check-timeout-limit! context)) ; throws :execution-error/timeout
```

**Important**: Timeout is checked at the START of each function call, not during execution. A long-running base function will complete fully even if it exceeds the timeout. For precise timeout control, base functions should implement their own timeout logic.

### Lazy Sequence Protection

**File:** `src/graphden/executor/argument_resolution.clj`

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

### Local Argument Binding

**Problem**: How to provide different argument values for the same function at different call sites?

**Solution**: Create a local fn with `owner-fn-id` pointing to the parent function. This fn inherits the same fn-schema but can have different fn-arg bindings.

```
;; Example: Using add-fn twice with different arguments in a parent function

;; 1. Base add function (no owner, no bindings)
fn: add-fn
  fn-schema-id: add-schema
  ;; No fn-arg bindings - this is the "template"

;; 2. Local fn for first usage (owned by parent)
fn: add-10-20
  fn-schema-id: add-schema
  owner-fn-id: parent-fn-id  ;; Local to parent

;; fn-arg bindings for add-10-20:
fn-arg: {fn-id: add-10-20, arg-schema-id: a, arg-value-id: val-10}
fn-arg: {fn-id: add-10-20, arg-schema-id: b, arg-value-id: val-20}

;; arg-values (pure, reusable):
arg-value: val-10 {arg-schema-id: a, value: 10}
arg-value: val-20 {arg-schema-id: b, value: 20}

;; 3. Local fn for second usage (owned by same parent)
fn: add-30-40
  fn-schema-id: add-schema
  owner-fn-id: parent-fn-id  ;; Local to parent

;; fn-arg bindings for add-30-40:
fn-arg: {fn-id: add-30-40, arg-schema-id: a, arg-value-id: val-30}
fn-arg: {fn-id: add-30-40, arg-schema-id: b, arg-value-id: val-40}

;; 4. Parent function uses both via fn-usage
fn-usage: first-sum  → fn: add-10-20
fn-usage: second-sum → fn: add-30-40

fn: parent-fn
  fn-arg: {arg-schema-id: x, arg-value-id: ref<fn-usage:first-sum>}   ;; Result: 30
  fn-arg: {arg-schema-id: y, arg-value-id: ref<fn-usage:second-sum>}  ;; Result: 70
```

**Key insight**: All argument binding happens in the database via fn-arg (binding) + arg-value (pure values). No runtime argument injection is needed. The graph structure (fn with owner-fn-id + fn-arg bindings) is sufficient for all use cases.

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
;; In executor/base-fns, web/http-kit, web/reitit modules
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
;; In web/server/core.clj - no Clojure code, only data
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

Both syntaxes create `fn-usage` entities in storage. The difference is naming convention:

| Syntax | fn-usage name | Typical use case |
|--------|--------------|------------------|
| `:fn-name` | "fn-name-ref" | HOF arguments (arg-schema.first-class=true) |
| `:fn-name>` | "fn-name" or suffix | Computed values (arg-schema.first-class=false) |

**Runtime behavior is controlled by `arg-schema.first-class`, NOT by syntax:**
- `first-class=true` → get fn-id from fn-usage, pass directly (for HOF)
- `first-class=false` → execute fn-usage, use result value

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

The `>` suffix creates a **fn-usage** entity in storage, which means:
- Result is computed once and cached within execution
- Multiple references to same `:fn-name>` share the cached result
- Without `>`, you pass the fn-id for HOF to call multiple times

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
2. **Races during cycle detection** - if two processes create fn-arg bindings simultaneously
   - *Mitigation*: PostgreSQL uses transactions; Datomic is inherently serialized
3. **Performance on deep graphs** - many DB queries
   - *Mitigation*: Apache AGE optimized Cypher queries for graph traversal

### Mitigation Summary

1. Apache AGE graph queries for efficient traversal
2. Transactions for atomicity
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

| Module | Role in Distribution |
|--------|---------------------|
| `executor` | Base execution, will need parallel/distributed modes |
| `resolve-execution-graph` | Graph analysis for dependency detection |
| `storage` | Shared state, potential intermediate result store |

---

## Appendix A: Storage Backend Architecture

Two storage backends are available:

- `postgres-storage` — Pure PostgreSQL (SQL queries)
- `graph-storage-age` — PostgreSQL + Apache AGE (Cypher queries for graph traversal)

**Why AGE for graph operations?**

| Operation | PostgreSQL | Apache AGE |
|-----------|------------|------------|
| Dependency chain traversal | Recursive CTE | Single Cypher MATCH path |
| Cycle detection | WITH RECURSIVE + array | Path pattern matching |
| Graph visualization | N/A | Native graph model |

**When to add a new backend:**

1. Implement all protocols from `storage-protocol/interface.clj`
2. Run tests to verify compliance
3. Add backend-specific optimizations

---

## Appendix B: Module Dependency Graph

```
                    ┌─────────────────────┐
                    │   schema/fields     │
                    └──────────┬──────────┘
                               │
           ┌───────────────────┼───────────────────┐
           │                   │                   │
           v                   v                   v
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ schema/protocol  │ │ storage/protocol │ │ schema/malli     │
└────────┬─────────┘ └────────┬─────────┘ └────────┬─────────┘
         │                    │                    │
         └────────────────────┼────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    │                   │
                    v                   v
          ┌──────────────────┐ ┌──────────────────┐
          │ storage/postgres │ │ schema/graph     │
          └────────┬─────────┘ └────────┬─────────┘
                   │                    │
                   └─────────┬──────────┘
                             │
                             v
                   ┌──────────────────┐
                   │ storage/age      │
                   └────────┬─────────┘
                            │
                            v
                   ┌──────────────────┐
                   │   executor       │
                   └────────┬─────────┘
                            │
              ┌─────────────┼─────────────┐
              │             │             │
              v             v             v
     ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
     │ executor/    │ │ executor/    │ │ versioning/  │
     │ base-fns     │ │ registry     │ │ storage      │
     └──────────────┘ └──────────────┘ └──────────────┘
```

---

## Appendix C: Error Types

See [ERROR_CODES.md](ERROR_CODES.md) for the complete error types reference.
