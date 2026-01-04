# Graph Constraints Reference

This document describes the integrity constraints enforced by the graphden storage layer. These constraints ensure the function graph remains consistent and executable.

## Overview

Graphden uses a graph of functions where:
- **fn-schema** defines a function's signature (arguments and return type)
- **fn** is an instance of a fn-schema with concrete argument values
- **arg-value** binds a value to an argument of a function
- Functions can inherit from parent functions via `parent-fn-id`
- Arguments can reference other functions, creating a dependency graph

## Constraint Protocol

All storage implementations must implement the `GraphConstraints` protocol:

```clojure
(defprotocol GraphConstraints
  (validate-parent-same-schema! [this fn-id parent-fn-id])
  (validate-no-arg-override! [this fn-id arg-schema-id])
  (validate-arg-schema-belongs-to-fn! [this fn-id arg-schema-id])
  (validate-no-inheritance-cycle! [this fn-id parent-fn-id])
  (validate-no-dependency-cycle! [this owner-fn-id value-fn-id]))
```

## Constraints

### 1. Parent Same Schema

**Rule:** A function's parent must have the same fn-schema-id.

**Rationale:** Inheritance in graphden means "use parent's arg-values as defaults". This only makes sense if parent and child have the same arguments, i.e., the same schema.

**Example:**
```
fn-schema: http-request
  args: [url, method, headers, body]

fn: base-api (schema: http-request)
  url: "https://api.example.com"
  method: "GET"

fn: get-users (schema: http-request, parent: base-api)  ✓
  headers: {"Authorization": "Bearer ..."}

fn: bad-fn (schema: some-other-schema, parent: base-api)  ✗
  // Error: parent-schema-mismatch
```

**Error:** `:constraint-violation/parent-schema-mismatch`

### 2. No Argument Override

**Rule:** An argument cannot be redefined if it's already set in an ancestor function.

**Rationale:** Prevents confusion about which value is used. The inheritance chain should only add new values, not override existing ones.

**Example:**
```
fn: base-api
  url: "https://api.example.com"

fn: v2-api (parent: base-api)
  method: "POST"  ✓

fn: broken (parent: base-api)
  url: "https://other.com"  ✗
  // Error: arg-already-defined
```

**Error:** `:constraint-violation/arg-already-defined`

**Note:** To change an inherited value, create a new function without the parent relationship.

### 3. Arg-Schema Belongs to Fn

**Rule:** An arg-value can only reference an arg-schema that belongs to the function's fn-schema.

**Rationale:** Ensures type safety. You can't set an argument that doesn't exist in the function's signature.

**Example:**
```
fn-schema: http-request
  args: [url, method, headers]

fn-schema: file-reader
  args: [path, encoding]

fn: api-call (schema: http-request)
  url: "..."      ✓ (url belongs to http-request)
  path: "..."     ✗ (path belongs to file-reader)
  // Error: arg-schema-mismatch
```

**Error:** `:constraint-violation/arg-schema-mismatch`

### 4. No Inheritance Cycle

**Rule:** The parent chain cannot form a cycle.

**Rationale:** Cycles in inheritance would cause infinite loops when resolving inherited values.

**Example:**
```
fn: A (parent: B)  ← Created first
fn: B (parent: A)  ✗ Error: inheritance-cycle

fn: X (parent: Y)
fn: Y (parent: Z)
fn: Z (parent: X)  ✗ Error: inheritance-cycle (X → Y → Z → X)
```

**Error:** `:constraint-violation/inheritance-cycle`

**Detection:** Uses depth-first search through the parent chain.

### 5. No Dependency Cycle

**Rule:** The dependency graph (via arg-value references to other functions) cannot form a cycle.

**Rationale:** Cycles would cause infinite recursion during execution.

**Example:**
```
fn: add-numbers
  a: ref<multiply-numbers>
  b: 10

fn: multiply-numbers
  x: ref<add-numbers>    ✗ Error: dependency-cycle
  y: 2

// add-numbers needs multiply-numbers
// multiply-numbers needs add-numbers
// → Deadlock during execution
```

**Error:** `:constraint-violation/dependency-cycle`

**Detection:** Uses depth-first search through all arg-value references.

**Note:** Self-reference (recursion) IS allowed because it's controlled by the executor's depth limit:

```
fn: factorial
  n: <input>
  recursive: ref<factorial>  ✓ (allowed - executor handles depth)
```

## Implementation Details

### Shared Validation Logic

The `storage-protocol` component provides shared validation functions that work with any storage implementing `ConstraintHelpers`:

```clojure
(defprotocol ConstraintHelpers
  (get-fn-schema-id-for-fn [this fn-id])
  (get-fn-schema-id-for-arg-schema [this arg-schema-id])
  (get-parent-fn-id [this fn-id])
  (collect-parent-chain [this fn-id])
  (has-arg-value-for-schema? [this fn-id arg-schema-id])
  (collect-fn-dependencies [this fn-id]))
```

### Storage-Specific Implementations

| Storage | Location | Notes |
|---------|----------|-------|
| memory | `memory-storage/core.clj` | Direct atom access |
| postgres | `postgres-storage/constraints.clj` | SQL queries with recursive CTEs |
| datomic | `datomic-storage/constraints.clj` | Datalog queries |

### Performance Considerations

**Cycle Detection:**
- Uses iterative DFS with visited set
- Worst case O(V + E) where V = functions, E = references
- Early termination on cycle detection

**Parent Chain Collection:**
- Iterative traversal (no recursion)
- Capped by function graph depth

**Caching:**
- Postgres: Metadata cached with lock-protected invalidation
- Datomic: Connection cached, queries use indices
- Memory: Direct access, no caching needed

## Testing

Contract tests in `storage-protocol/contract_tests.clj` verify all constraints work correctly for each storage implementation:

```bash
# Run all storage tests including constraints
bb test
```

Key test categories:
- `parent-same-schema-test`
- `no-arg-override-test`
- `arg-schema-belongs-to-fn-test`
- `no-inheritance-cycle-test`
- `no-dependency-cycle-test`
