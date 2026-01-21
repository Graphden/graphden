# Graph Constraints Reference

This document describes the integrity constraints enforced by the graphden storage layer. These constraints ensure the function graph remains consistent and executable.

## Overview

Graphden uses a graph of functions where:
- **fn-schema** defines a function's signature (arguments and return type)
- **fn** is an instance of a fn-schema with concrete argument values
- **arg-value** binds a value to an argument of a function
- Arguments can reference other functions, creating a dependency graph

## Constraint Protocol

All storage implementations must implement the `GraphConstraints` protocol:

```clojure
(defprotocol GraphConstraints
  (validate-arg-schema-belongs-to-fn! [this fn-id arg-schema-id])
  (validate-no-dependency-cycle! [this owner-fn-id value-fn-id]))
```

## Constraints

### 1. Arg-Schema Belongs to Fn

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

### 2. No Dependency Cycle

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
  (collect-dependency-chain [this fn-id]))
```

### Storage-Specific Implementations

| Storage | Location | Notes |
|---------|----------|-------|
| memory | `memory-storage/core.clj` | Direct atom access |
| postgres | `postgres-storage/constraints.clj` | SQL queries |
| datomic | `datomic-storage/constraints.clj` | Datalog queries |

### Performance Considerations

**Cycle Detection:**
- Uses iterative DFS with visited set
- Worst case O(V + E) where V = functions, E = references
- Early termination on cycle detection

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
- `arg-schema-belongs-to-fn-test`
- `no-dependency-cycle-test`
