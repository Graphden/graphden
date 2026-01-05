# Graphden: Visual Functional Programming System

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

### New Protocol

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

### Contract Tests

Create a set of tests that EVERY storage must pass:

```clojure
(defn constraint-tests [create-storage-fn]
  (testing "parent-same-schema constraint"
    (let [storage (create-storage-fn)]
      ;; Setup: create fn-schema-1, fn-schema-2, fn-a (schema-1)
      ;; Test: create fn-b (schema-2) with parent = fn-a
      ;; Expected: throws :constraint-violation/parent-schema-mismatch
      ))

  (testing "no-arg-override constraint"
    (let [storage (create-storage-fn)]
      ;; Setup: fn-a with arg-value for :x, fn-b (parent: fn-a)
      ;; Test: create arg-value for :x on fn-b
      ;; Expected: throws :constraint-violation/arg-already-defined
      ))

  ;; ... remaining tests
  )
```

### Implementation in Each Storage

| Storage | Where implemented | How |
|---------|-------------------|-----|
| memory | On write to atom | Clojure code with state queries |
| postgres | TRIGGER + Clojure fallback | SQL trigger for performance, Clojure for complex cases |
| datomic | Transaction function | `:db/txFn` with Datomic queries |

### README for Each Storage

Each storage component will have a README.md describing:

```markdown
# memory-storage

## Implemented GraphConstraints

| Constraint | Implementation | File |
|------------|----------------|------|
| parent-same-schema | Check at `create-fn` | `core.clj:45` |
| no-arg-override | DFS through parent chain | `constraints.clj:12` |
| arg-schema-belongs-to-fn | Join check | `constraints.clj:28` |
| no-inheritance-cycle | DFS | `constraints.clj:35` |
| no-dependency-cycle | DFS through arg-values | `constraints.clj:52` |

## Tests

All contract tests pass: `bb test:memory-storage`
```

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

**Solutions:**
1. **Depth limit** - executor has max-depth (e.g., 1000)
2. **Timeout** - maximum execution time
3. **Runtime detection** - track call stack

**Recommendation**: All three. This is standard practice (JVM has StackOverflowError, browsers have timeouts).

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

**Solution**: Forbid cycles when creating arg-value.

| Storage | Implementation |
|---------|----------------|
| PostgreSQL | Trigger + recursive CTE for cycle detection |
| Datomic | Transaction function + query |
| Memory | DFS on write |

**This is complex**, but necessary. Without this, the system can hang.

**Alternative**: Runtime detection (during execution). Easier to implement, but error is discovered later.

**Recommendation**: Detection on write + runtime protection (in case of races or bugs).

### What Algorithms Are IMPOSSIBLE Without Recursion?

**Short answer**: Almost all non-trivial ones.

- Tree/graph traversal
- Sorting (quicksort, mergesort)
- Parsing recursive structures
- Many numerical methods

**Conclusion**: Recursion is MANDATORY. Need to allow it with protective mechanisms.

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

## Part 4: Final Data Schema

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
| 5 | arg-value.arg-schema-id matches owner-fn.fn-schema-id | TRIGGER or CHECK with subquery | :db.attr/preds | Validation on write |
| 6 | No cycles in fn graph through arg-value | TRIGGER + recursive CTE | Transaction function | DFS on write |

### Constraint #5 in Detail

**Problem**: arg-value references arg-schema, which belongs to fn-schema. owner-fn also references fn-schema. They must match.

```sql
-- PostgreSQL: CHECK constraint (slow, but declarative)
ALTER TABLE arg_value
ADD CONSTRAINT arg_value_schema_match CHECK (
  (SELECT fn_schema_id FROM arg_schema WHERE id = arg_schema_id) =
  (SELECT fn_schema_id FROM fn WHERE id = owner_fn_id)
);

-- Or TRIGGER (faster, but imperative)
CREATE FUNCTION check_arg_value_schema() RETURNS TRIGGER AS $$
BEGIN
  IF (SELECT fn_schema_id FROM arg_schema WHERE id = NEW.arg_schema_id) !=
     (SELECT fn_schema_id FROM fn WHERE id = NEW.owner_fn_id) THEN
    RAISE EXCEPTION 'arg-schema does not belong to fn schema';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

**Datomic**: `:db.attr/preds` with validation function.

**Memory**: Check in code on insert/update.

### Constraint #6 in Detail (Cycles)

**When creating arg-value with value = ref<fn>:**

1. Get target-fn-id from value
2. Recursively collect all fn that target-fn references through arg-values
3. If owner-fn-id is in this set -> REJECT (cycle)

```sql
-- PostgreSQL: recursive CTE
WITH RECURSIVE deps AS (
  -- Base case: target fn
  SELECT target_fn_id AS fn_id
  UNION
  -- Recursion: all fn referenced by arg-values
  SELECT (av.value->>'ref')::uuid
  FROM deps d
  JOIN arg_value av ON av.owner_fn_id = d.fn_id
  WHERE av.value->>'type' = 'ref'
)
SELECT EXISTS (SELECT 1 FROM deps WHERE fn_id = owner_fn_id);
```

---

## Part 5: Execution Model

### Laziness and Thunks

```clojure
(defprotocol IThunk
  (force-value [this context]))

(defrecord LiteralThunk [value]
  IThunk
  (force-value [_ _] value))

(defrecord FnRefThunk [fn-id provided-args]
  IThunk
  (force-value [_ context]
    (execute-fn fn-id provided-args context)))

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
;; Regular function - all arguments are evaluated
(defn base-add [{:keys [a b]}]
  (+ (force a) (force b)))

;; Conditional - lazy branches
(defn base-if [{:keys [condition then else]}]
  (if (force condition)
    (force then)
    (force else)))

;; HOF - f is passed as fn-id
(defn base-map [{:keys [f coll]} context]
  (let [coll-value (force coll)
        f-id (force f)]  ; This is fn-id, not the result!
    (mapv (fn [item]
            (execute-fn f-id {:item item} context))
          coll-value)))
```

### Execution Context

```clojure
(defrecord ExecutionContext
  [depth         ; Current depth (for infinite recursion protection)
   max-depth     ; Maximum depth
   start-time    ; Start time (for timeout)
   timeout-ms    ; Maximum time
   call-stack])  ; Call stack (for debugging)

(defn execute-fn [fn-id provided-args context]
  ;; Safety checks
  (when (> (:depth context) (:max-depth context))
    (throw (ex-info "Max recursion depth exceeded" {:depth (:depth context)})))
  (when (> (- (System/currentTimeMillis) (:start-time context)) (:timeout-ms context))
    (throw (ex-info "Execution timeout" {})))

  ;; Execution
  (let [graph (resolve-fn fn-id)
        thunks (build-thunks graph provided-args)
        new-context (update context :depth inc)]
    (call-base-fn (:base-fn-name graph) thunks new-context)))
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

**Implementation**:

When building thunks for A:
1. For arg1-of-A create FnRefThunk with fn-id=B and provided-args filtered by arg-value-1-id
2. For arg2-of-A create FnRefThunk with fn-id=B and provided-args filtered by arg-value-2-id

---

## Part 6: Implementation Plan

### Phase 0: Documentation

**0.1 Main Project README.md**

File: `README.md`

Contents:
- Project vision (visual functional programming)
- System architecture (function graph in DB)
- Key concepts (fn-schema, fn, arg-value, inheritance)
- Execution model (laziness, thunks)
- Links to component READMEs

**0.2 README for Each Component**

| Component | Description |
|-----------|-------------|
| `storage-protocol` | Storage, StorageCRUD, GraphConstraints protocols |
| `data-schema-protocol` | DataSchema protocol, field types |
| `field-types` | Supported data types |
| `malli-data-schema` | Malli schema implementation |
| `graph-data-schema` | Function graph schema (fn-schema, fn, arg-value) |
| `memory-storage` | In-memory implementation + constraints |
| `postgres-storage` | PostgreSQL implementation + constraints |
| `datomic-storage` | Datomic implementation + constraints |

Each README contains:
- Component purpose
- Dependencies
- Main functions/protocols
- Usage examples
- For storage: constraint implementation table

---

### Phase 1: Data Schema and Constraints

**1.1 Update graph-data-schema**

File: `components/graph-data-schema/src/graphden/graph_data_schema/interface.clj`

- Add `parent-fn-id` to `:fn`
- Add `required` to `:arg-schema`
- Add `base-fn-name` to `:fn-schema`

**1.2 GraphConstraints Protocol**

File: `components/storage-protocol/src/graphden/storage_protocol/interface.clj`

```clojure
(defprotocol GraphConstraints
  (validate-parent-same-schema! [this fn-id parent-fn-id])
  (validate-no-arg-override! [this fn-id arg-schema-id])
  (validate-arg-schema-belongs-to-fn! [this fn-id arg-schema-id])
  (validate-no-inheritance-cycle! [this fn-id parent-fn-id])
  (validate-no-dependency-cycle! [this owner-fn-id target-fn-id]))
```

**1.3 Contract Tests for GraphConstraints**

File: `components/storage-protocol/test/graphden/storage_protocol/constraint_contract_test.clj`

**1.4 Implement Constraints in Each Storage**

---

### Phase 2: CRUD Operations

**2.1 StorageCRUD Protocol**

File: `components/storage-protocol/src/graphden/storage_protocol/interface.clj`

```clojure
(defprotocol StorageCRUD
  (create [this entity-name data])      ; -> id
  (read-by-id [this entity-name id])    ; -> data | nil
  (update-by-id [this entity-name id data]) ; -> data
  (delete-by-id [this entity-name id])  ; -> boolean
  (query [this entity-name where]))     ; -> [data...]
```

**2.2 Implement CRUD in Each Storage**

| Storage | Files |
|---------|-------|
| memory | `components/memory-storage/src/graphden/memory_storage/crud.clj` |
| postgres | `components/postgres-storage/src/graphden/postgres_storage/crud.clj` |
| datomic | `components/datomic-storage/src/graphden/datomic_storage/crud.clj` |

### Phase 3: Executor

**3.1 graph-resolver** - new component

File: `components/graph-resolver/src/graphden/graph_resolver/interface.clj`

- `resolve-fn [storage fn-id]` -> collect graph with arg-values and parents

**3.2 thunk-builder**

File: `components/executor/src/graphden/executor/thunks.clj`

- Create LiteralThunk, FnRefThunk, LazyFnThunk

**3.3 executor**

File: `components/executor/src/graphden/executor/interface.clj`

- `execute [storage fn-id args context]` -> result
- Protection: max-depth, timeout

**3.4 base-functions** - registry of base functions

File: `components/base-functions/src/graphden/base_functions/interface.clj`

---

### Phase 4: Base Functions

**4.1 Arithmetic and Strings**
- +, -, *, /, mod
- str, subs, str/join, etc.

**4.2 Collections**
- first, rest, cons, conj
- get, assoc, dissoc

**4.3 Conditionals and HOF**
- if, cond
- map, filter, reduce

**4.4 I/O (Client)**
- http-request (http-kit client)
- file operations

**4.5 I/O (Server)**
- http-server (http-kit server)

**Running Long-lived Services:**

Problem: http-server must run continuously, not "compute and return result".

Solution: **Service Manager** - separate component for managing long-lived processes.

```clojure
(defprotocol ServiceManager
  (start-service [this service-fn-id])   ; -> service-instance-id
  (stop-service [this instance-id])      ; -> boolean
  (list-services [this])                 ; -> [{:id :fn-id :status :started-at}]
  (service-status [this instance-id]))   ; -> {:status :logs :metrics}
```

HTTP-server as a base function:
```clojure
;; base-fn-name: "graphden/http-server"
;; args: {:port int, :handler fn}
;;
;; This function does NOT return a result, but registers a service
(defn base-http-server [{:keys [port handler]} context]
  (let [server (http-kit/run-server
                 (fn [req] (execute-fn handler {:request req} context))
                 {:port (force port)})]
    ;; Return handle for stopping
    {:stop-fn server
     :type :http-server
     :port (force port)}))
```

Service Manager stores running services and provides API for management.

---

### Phase 5: UI/API

**5.1 REST API**
- CRUD endpoints for all entities
- POST /execute - run function

**5.2 Web Interface**
- Function list
- Graph editor
- Execute button

---

## Part 7: Future Plans

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

---

## Part 8: Honest System Limitations

### What CANNOT Be Done Elegantly

1. **Constraints #5 and #6** require triggers/code - no declarative way in SQL/Datomic
2. **Mutual recursion** - cannot distinguish "good" from "bad" statically
3. **Full type inference** - this is a separate large task

### What Can Break

1. **Infinite recursion** - protection via depth/timeout, but error at runtime
2. **Races during cycle detection** - if two processes create arg-values simultaneously
3. **Performance on deep graphs** - many DB queries

### Mitigation

1. Aggressive caching of resolved graphs
2. Transactions for atomicity
3. Monitoring and alerts for deep/long executions
