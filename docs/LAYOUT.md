# Graph Layout Algorithm

This document describes the complete algorithm for laying out function graphs in the Graphden editor.

## Overview

The layout algorithm transforms a function graph into a 2D grid where:
- Each node occupies exactly one cell
- Edges go only RIGHT or DOWN (never left or up)
- No nodes or edges overlap
- Related nodes (parent-child) are visually connected

## Pipeline Stages

```
Request → Load Data → Build Graph → Compute Paths → Sort Children → Place Nodes → Response
```

### Stage 1: Load Data from Database

**Input:** `root-id` (UUID), `expansions` (map of fn-id → level)

**Process:**
1. Load all `fn` and `arg` entities from storage
2. Build lookup maps:
   - `fn-map`: fn-id → fn entity
   - `arg-map`: arg-id → arg entity
   - `args-by-fn`: fn-id → [arg entities]

### Stage 2: Build Graph Elements

Transform the database entities into graph nodes and edges based on expansions.

#### 2.1 Expansion Processing

For each function, determine its "display level":
- Level 0: Show function as single node with direct children (refs, values, unset args)
- Level N > 0: Show function with N ancestors expanded (inheritance chain visible)

**Inheritance chain:** `[fn-id, parent-id, grandparent-id, ...]`

When expanding to level N:
- Display function shows ancestors up to level N
- Args from ancestors become visible
- Bindings (arg values set by descendants) flow to ancestor arg positions

#### 2.2 Node ID Generation

Critical for correct sharing behavior:

| Context | Node ID Format | Purpose |
|---------|----------------|---------|
| Root node | `fn-{original-fn-id}` | Canonical ID |
| Expanded root | `fn-{original-fn-id}` | Same - expansion doesn't change ID |
| Inside expansion | `fn-{expansion-root}-{ancestor-fn-id}` | Scoped to expansion context |
| Canonical ref (level 0) | `fn-{ref-fn-id}` | Shared across all references |

**Key insight:** Nodes inside an expansion context get prefixed IDs. This ensures:
- Each expanded function has its own copy of ancestor structure (method-map, etc.)
- Canonical nodes (level-0 refs) are shared and displayed once

#### 2.3 Binding Resolution

Bindings are arg values that flow through inheritance:

```
add-10 (binds a=10)
  └── inherits from: add (has args: a, b)

When processing add-10:
  - bindings = {add/a-arg-id → {value: 10, arg-name: "a"}}
  - These bindings apply when showing add's arg "a"
```

**Binding propagation rules:**
- Bindings from expansion context apply to structural nodes (inside expansion)
- Bindings do NOT apply to canonical nodes (refs at level 0)
- This prevents false edges like `favicon-route → metrics-handler`

#### 2.4 Output

```clojure
{:nodes [{:data {:id "fn-xxx" :label "name" :type "fn" ...}} ...]
 :edges [{:data {:id "e-xxx" :source "fn-a" :target "fn-b" :argName "handler"}} ...]}
```

### Stage 3: Compute Paths to Shared Nodes

**Shared node:** A node with multiple parents (multiple incoming edges)

For layout, we need to know:
1. Which nodes are shared
2. Which nodes lead to shared nodes (path-to-shared)
3. Which path is "upper" vs "lower" for child ordering

#### 3.1 Find Shared Nodes

```clojure
shared-nodes = {node-id | count(parents[node-id]) > 1}
```

#### 3.2 Find Paths to Shared

For each node, compute which shared nodes are reachable:

```clojure
paths-to-shared[node-id] = set of shared-node-ids reachable from node-id
```

Uses memoized recursive traversal.

#### 3.3 Find Divergence Roots

**Divergence roots:** Siblings (same parent) that both lead to the same shared node.

Example:
```
editor-routes
  ├── entity-form-create-route ──→ entity-form-handler (shared)
  └── entity-form-edit-route ───→ entity-form-handler (shared)
```

Both `entity-form-create-route` and `entity-form-edit-route` are divergence roots.

**Purpose:** Divergence roots must be grouped together in child lists and maintain stable positions.

#### 3.4 Compute Path Positions

For each node, determine if it's on "upper" or "lower" path:

- **Bottom parent:** Last parent in parent list of shared node
- **Lower path:** Bottom parent and all its ancestors leading to shared
- **Upper path:** All other nodes leading to shared

**Purpose:** Child ordering differs based on path position:
- Lower path: path-to-shared children go FIRST (top)
- Upper path: path-to-shared children go LAST (bottom)

### Stage 4: Sort Children Lists

For each node, sort its children for optimal layout.

#### 4.1 Classification

Each child is classified as:
- **Direct shared:** The child IS a shared node
- **Divergence root:** Child leads to shared but is a divergence point
- **Path-to-shared:** Child leads to shared (not divergence root)
- **Regular:** Child doesn't lead to shared

#### 4.2 Sorting Rules

**Goal:** Shared nodes should end up at the BOTTOM so all edges go DOWN (or right) to reach them.

**Priority order:**
1. **Path position** (highest):
   - Lower path → path-to-shared children go LAST (larger rows, shared node at bottom)
   - Upper path → path-to-shared children go FIRST (smaller rows)
2. **Divergence grouping:** Divergence roots grouped together. Within a group on lower path, the lower-path member goes LAST.
3. **Type** (lowest): fn > fixed-arg > free-arg

**Within groups:** Preserve original order (by original-idx)

### Stage 5: Compute Column Offsets

For shared nodes, parents at different column depths need alignment.

**Problem:** If parent A is at column 2 and parent B is at column 4, their edges to the shared child would cross.

**Solution:** Compute offsets to shift parent A right so both align at column 4.

```clojure
parent-offsets[parent-id] = max-parent-depth - parent-depth
```

### Stage 6: Place Nodes (THE CRITICAL ALGORITHM)

This is the core placement algorithm. It must be followed exactly.

#### 6.1 Definitions

- **SELECTED node:** Current node being processed
- **Horizontal branch:** Chain of first children starting from SELECTED
- **Branch ROOT:** The SELECTED node that starts a horizontal branch
- **Branch length:** Count of nodes in branch + sum of all offsets

#### 6.2 Algorithm Steps

**Step 1: Initialize**
- SELECTED = graph root node
- Matrix = empty grid
- Edge reservations = empty (for collision detection)

**Step 2: Build Horizontal Branch**

Starting from SELECTED:
```
branch = [SELECTED]
current = SELECTED
while current has children:
    first_child = children[current][0]
    branch.append(first_child)
    current = first_child
```

Calculate branch length:
```
length = count(branch) + sum(offset[node] for node in branch)
```

**Step 3: Place Horizontal Branch**

Determine placement position:

**Column:**
- If SELECTED is graph root: column = 0
- Else: column = parent's column + 1 + SELECTED's offset (if any)

**Row (with column-aware compaction):**
- If SELECTED is graph root: row = 0
- Else: min_row = parent's row + 1

**Finding valid row (column-aware compaction):**
Starting from min_row, search downward for a row where:
1. All cells from (row, column) to (row, column + branch_length - 1) are FREE

**Key insight:** Only the branch's columns are checked. If a sibling's branch uses columns 1-2 and the subtree uses columns 3-5, the sibling can share rows with the subtree because their columns don't overlap.

**Column-aware compaction example:**
```
Graph (with expanded delete-entity-route):
  editor-routes (col 0)
  ├── delete-entity-route (col 1)
  │   ├── method-map (col 2) → ... → "delete" (col 3, row 1)
  │   └── pair (col 2, row 2) → path (col 3, row 2)
  └── entity-form-create-route (col 1)
      └── path (col 2)

Processing:
1. Place horizontal branch: editor-routes, delete-entity-route, method-map, ...
2. Process delete-entity-route subtree (fills cols 2-5, rows 0-2)
3. Process entity-form-create-route (sibling):
   - min_row = 1 (parent's row + 1)
   - Branch uses cols 1-2
   - Row 1: check cols 1-2 → both free! ("delete" is at col 3)
   - Place at row 1, col 1

Result: entity-form-create-route can use row 1 because its columns (1-2) don't overlap
with subtree nodes at row 1 (which are at col 3+).
```

**Place the branch:**
For each node in branch (left to right):
- Place node at (row, current_column)
- If node has offset, reserve edge cells before it
- current_column += 1 + node's offset

**Step 4: Process Children (DEPTH-FIRST)**

After placing horizontal branch, process remaining children of each node in branch.

**CRITICAL: Process in DEPTH-FIRST order, RIGHT to LEFT along the branch:**

```
for node in reverse(branch):
    children = sorted_children[node]
    for i in range(1, len(children)):  # Skip first child (already in branch)
        child = children[i]
        SELECTED = child
        goto Step 2  # Recursively place child's subtree COMPLETELY
```

**Key invariant:** A node's ENTIRE subtree must be placed before moving to the next sibling.

**Step 5: Backtrack**

When a branch has no more children to process:
1. Return to branch ROOT's parent
2. Find next unplaced sibling in parent's children list
3. If found: SELECTED = sibling, goto Step 2
4. If not found: continue backtracking to grandparent, etc.
5. If we reach graph root with no unplaced children: DONE

#### 6.3 Edge Reservations

To prevent edge crossings, reserve cells for edges:

**Horizontal edges (offsets):**
When placing a node with offset N at (row, col):
- Reserve cells (row, col-N) to (row, col-1) as "horizontal edge from parent to node"

**Vertical edges (row gaps):**
When placing a node at (row, col) but start_row was less than row:
- Reserve cells (start_row, col) to (row-1, col) as "vertical edge to node"

**Collision detection:**
Before placing a node or edge, check if cell is already occupied by:
- Another node
- Another edge reservation

If collision detected: ERROR in algorithm (should not happen with correct implementation)

#### 6.4 Example Trace (Column-Aware Compaction)

```
Graph:
  A
  ├── B
  │   ├── D
  │   └── E
  └── C
      └── F

Step 1: SELECTED = A
Step 2: Branch = [A, B, D] (first children chain)
Step 3: Place at row 0: A(0,0) B(0,1) D(0,2)
Step 4: Process children right-to-left:
  - D has no more children
  - B has child E (index 1)
    - SELECTED = E
    - min_row = 1 (B's row + 1)
    - Step 2: Branch = [E]
    - Step 3: Check row 1, col 2 → free, place E(1,2)
    - Step 4: E has no children, backtrack
  - A has child C (index 1)
    - SELECTED = C
    - min_row = 1 (A's row + 1)
    - Step 2: Branch = [C, F], length = 2, uses cols 1-2
    - Step 3: Check row 1, cols 1-2 → both free! (E is at col 2, but C's branch ends at col 2)
    - Actually col 2 is occupied by E at row 1 → try row 2
    - Place at row 2: C(2,1) F(2,2)
    - Step 4: No more children, backtrack
  - A has no more children
Step 5: Done

Result:
  col:  0   1   2
  row 0: A   B   D
  row 1:         E
  row 2:     C   F
```

**Note:** C tries row 1 but col 2 is occupied by E, so it moves to row 2.

#### 6.5 Example Trace (Successful Compaction)

```
Graph (showing compaction when columns don't overlap):
  A
  ├── B → C → D → E (horizontal branch at row 0, cols 1-4)
  │            └── F (child of D at row 1, col 3)
  └── G (sibling of B)
      └── H (horizontal branch at row ?, cols 1-2)

Processing:
1. Place horizontal branch: A, B, C, D, E at row 0
2. Process children right-to-left:
   - D has child F → place at row 1, col 3
   - A has sibling G:
     - min_row = 1 (A's row + 1)
     - Branch = [G, H], uses cols 1-2
     - Check row 1, cols 1-2 → both free! (F is at col 3)
     - Place G(1,1) H(1,2)

Result:
  col:  0   1   2   3   4
  row 0: A   B   C   D   E
  row 1:     G   H   F

G can share row 1 with F because their columns don't overlap (G uses 1-2, F at 3).
```

### Stage 7: Build Response

Convert matrix positions to response format:

```clojure
{:nodes [...]
 :edges [...]
 :grid-pos {node-id {:row r :col c} ...}
 :validation {:valid true/false :issues [...]}}
```

## Validation

After placement, verify:
1. All nodes have positions
2. No position collisions (multiple nodes at same cell)
3. No edge crossings (check reservations)

## Debugging Layout Issues

When layout problems occur:

1. **Identify the symptom:** Which nodes are misplaced? What's the expected vs actual position?

2. **Trace the algorithm:** Which step failed?
   - Stage 2: Wrong nodes/edges generated?
   - Stage 4: Wrong child ordering?
   - Stage 6: Wrong placement?

3. **Check invariants:**
   - Is depth-first order maintained?
   - Are all children placed before moving to sibling?
   - Are edge reservations correct?

4. **Update this document** if algorithm needs changes

5. **Never fix code without understanding which algorithm step is wrong**

## Related Files

- `resources/packages/app/layout/impls.clj` - Implementation
- `test/graphden/layout/` - Layout tests
- `tools/browser-test/` - Visual browser tests
