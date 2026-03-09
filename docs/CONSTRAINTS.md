# Graph Constraints Reference

This document describes the integrity constraints enforced by the graphden storage layer. These constraints ensure the function graph remains consistent and executable.

## Overview

Graphden uses a **2-entity graph model** where:
- **fn** — function entity (base-fn or composed via parent-id)
- **arg** — argument entity (primary or inherited via source-id)

Arguments can reference other functions via `ref-id`, creating a dependency graph.

## Constraint Protocol

All storage implementations must implement the `GraphConstraints` protocol:

```clojure
(defprotocol GraphConstraints
  (validate-no-dependency-cycle! [this owner-fn-id target-fn-id]))
```

## Constraints

### 1. No Dependency Cycle

**Rule:** The dependency graph (via arg.ref-id references to other functions) cannot form a cycle.

**Rationale:** Cycles would cause infinite recursion during execution.

**Example:**
```
fn: add-numbers
  arg: {ref-id: multiply-numbers}   ; references multiply-numbers
  arg: {value: 10}

fn: multiply-numbers
  arg: {ref-id: add-numbers}    ✗ Error: dependency-cycle
  arg: {value: 2}

// add-numbers needs multiply-numbers
// multiply-numbers needs add-numbers
// → Deadlock during execution
```

**Error:** `:constraint-violation/dependency-cycle`

**Detection:** Uses depth-first search through all arg.ref-id references.

**Note:** Self-reference (recursion) IS allowed because it's controlled by the executor's depth limit:

```
fn: factorial
  arg: {name: "n", ...}               ; input arg
  arg: {ref-id: factorial}            ✓ (allowed - executor handles depth)
```

### 2. Unique Fn Name

**Rule:** Function names must be unique (NULL is allowed for local functions).

**Constraint:** `UNIQUE(name)` on fn table.

**Example:**
```
fn: add (name: "add")       ✓
fn: add-v2 (name: "add")    ✗ Error: unique constraint violation
fn: local-fn (name: nil)    ✓ (local fn, no name collision)
```

### 3. Unique Arg per Fn + Source

**Rule:** Each fn can have at most one arg per source-id.

**Constraint:** `UNIQUE(fn-id, source-id)` on arg table.

**Rationale:** Prevents duplicate inherited arguments.

**Example:**
```
fn: parent
  arg: a {source-id: nil}    ; primary arg

fn: child (parent-id: parent)
  arg: {source-id: a, value: 10}    ✓
  arg: {source-id: a, value: 20}    ✗ Error: duplicate (fn-id, source-id)
```

### 4. Unique Arg Name within Fn

**Rule:** Arg names must be unique within a function.

**Constraint:** `UNIQUE(fn-id, name)` on arg table.

**Example:**
```
fn: my-fn
  arg: {name: "x", ...}    ✓
  arg: {name: "y", ...}    ✓
  arg: {name: "x", ...}    ✗ Error: duplicate arg name
```

### 5. Source-id References Valid Parent Arg (Application-Level)

**Rule:** When source-id is set, it must reference an arg that belongs to an ancestor function in the parent-id chain.

**Rationale:** Ensures inheritance makes sense — you can only inherit args from your parent (or parent's parent, etc.).

**Note:** This is validated at application level, not via DB constraint.

## Implementation Details

### Storage-Specific Implementations

| Storage | Location | Notes |
|---------|----------|-------|
| postgres | `storage/postgres/constraints.clj` | SQL queries + CTE for cycle detection |
| age | `storage/age/age.clj` | Graph queries via Cypher |

### Performance Considerations

**Cycle Detection:**
- Uses iterative DFS with visited set
- Worst case O(V + E) where V = functions, E = references
- Early termination on cycle detection

## Testing

Contract tests in `storage-protocol/contract_tests.clj` verify all constraints work correctly for each storage implementation:

```bash
# Run all storage tests including constraints
bb test
```

Key test categories:
- `no-dependency-cycle-test`
- `unique-fn-name-test`
- `unique-arg-source-test`
