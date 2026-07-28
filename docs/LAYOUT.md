# Graph Layout Algorithm

This document describes the complete algorithm for laying out
function graphs in the Graphden editor. Internal data shape:
`derive-fn-slot-views` flattens (fn × slot) views into "arg row"
records that feed every walker; lookups are slot-id-keyed
(`bindings`, `parent-bound-terminals`, `covered-slots`,
`child-covered-sources-for-fn`). See `src/graphden/layout/`
(`data.clj` / `graph.clj` / `builder_helpers.clj`) for the
implementation — `resources/packages/app/layout/impls.clj` is only the
thin `defbase` boundary that calls into it.

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

1. Load all `fn` / `slot` / `fn-slot` / `binding` /
   `binding-list-item` entities from storage.
2. `derive-fn-slot-views` flattens each fn's inheritance closure
   into anchor rows (one per `(fn, slot)` pair) plus item rows for
   list-bindings.
3. Build lookup maps:
   - `fn-map`: fn-id → fn entity
   - `arg-map`: synth-arg-id → derived (fn, slot) view
   - `args-by-fn`: fn-id → [view rows]
   - `slot-map`: slot-id → slot entity
   - `fn-slots-by-fn`: fn-id → [fn-slot junction rows]
   - `binding-by-fn-slot`: [fn-id slot-id] → binding row
   - `bindings-by-fn`: fn-id → [binding rows]
   - `items-by-binding`: binding-id → [list-item rows] sorted by position

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
| Inside expansion | `fn-{expansion-root}_{ancestor-fn-id}` | Scoped to expansion context (underscore separator) |
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

**Defines-own-ref rule (critical for coll chains):**
When a structural node has an arg that DEFINES its own ref (the source arg has no ref),
that arg's ref-id takes precedence over any ancestor binding with a different ref-id.

```
Example: editor-routes → list-11 → list-10 → list-10-9
         (where list-11.coll → list-10, list-10.coll → list-10-9)

When processing structural list-10:
- list-10.coll has ref-id = list-10-9 (defines own ref - source has no ref)
- Binding from list-11.coll has ref-id = list-10 (different from list-10-9)
- Because list-10 DEFINES its own coll ref, use list-10-9, NOT the binding's list-10
- This ensures the coll chain continues: list-10 → list-10-9 → list-10-8 → ...
```

Without this rule, the binding would incorrectly override the structural ref,
breaking the coll chain and hiding all nested items.

#### 2.4 Shared Ancestor Exclusion (critical for list/coll chains)

When determining which bindings "belong inside" a structural ref-fn vs should be shown
at the expansion root, the algorithm collects arg-ids from the ref-fn's inheritance chain.
If a binding's source chain leads to one of these arg-ids, it's considered "covered" by
the structural ref — shown there, not at the root.

**Problem:** When the expansion root and the ref-fn share a common ancestor (e.g., both
inherit from `conj-any`), the shared ancestor's args appear in BOTH chains. This causes
ALL bindings whose source chains pass through the shared ancestor to be incorrectly
filtered as "covered."

```
Example: editor-routes → list-11 → conj-any (expansion chain)
         list-10 → conj-any (ref-fn chain)

conj-any has args: coll (9bee1b2f), item (6a08596a)

Without exclusion:
  6a08596a is in ref-fn-arg-ids (from list-10's chain including conj-any)
  ALL editor-routes' items have source chains ending at 6a08596a
  → ALL items filtered as "covered" → items disappear from graph!
```

**Solution: Shared ancestor exclusion.** When collecting arg-ids from ref-fn chains,
EXCLUDE fns that also appear in the expansion root's full inheritance chain.

This rule applies in **four places** in the code:

| Location | What it collects | Effect of exclusion |
|----------|-----------------|---------------------|
| `collect-expanded-args` → `ref-fn-arg-ids` | Args from displayed ref-fns | Items not filtered as "belonging inside ref" |
| `process-expanded-fn` → `ancestor-ref-arg-sources` | Args from ancestor ref subtree | Level-0 items not filtered as "covered by ancestor" |
| `process-fn` → `displayed-ref-arg-ids` | Args from displayed child ref-fns | Item bindings not filtered inside structural nodes |
| `child-covered-sources-for-fn` | Slot-ids exposed by child ref's closure | Slots not mistakenly "covered by coll child" when they live in the shared ancestor |

**Recursive subtree walk for ancestor-ref-arg-sources:**
The ancestor ref may have a deep composition tree (e.g., list-10 → list-10-9 → ... → pair-1).
Bindings flow to ALL nodes in this tree, not just the direct ref. So `ancestor-ref-arg-sources`
must recursively walk the entire subtree (following ref-ids in args), collecting arg-ids from
all reachable nodes — while still applying the shared ancestor exclusion.

```
Example: editor-routes expanded to list-11 level.
  ancestor ref = list-10 (coll chain root)
  Recursive walk: list-10 → list-10-9 → ... → pair-1
  Each has item slot: item10, item9, ..., item1
  
  ancestor-ref-arg-sources includes ALL these item arg-ids
  → editor-routes' items 1-10 are "covered" (shown on coll nodes)
  → only item11 (list-11's own slot) shown at editor-routes level
```

#### 2.5 Output

```clojure
{:nodes [{:data {:id "fn-xxx" :label "name" :type "fn" ...}} ...]
 :edges [{:data {:id "e-xxx" :source "fn-a" :target "fn-b" :argName "handler"}} ...]}
```

#### 2.6 Inherited bindings are surfaced through ANCESTOR cards, not child arg nodes

A composed fn-def with no own bindings on a slot inherits its
parent's `value` / `:list-append` items. The layout intentionally
does NOT emit those values as arg-nodes under the CHILD's fn —
the child's `args-by-fn` slice is empty for inherited-only slots.

Instead, the editor's fn-overlay walks `parent-ids` at render
time and exposes the parent rows on the SAME card (the ancestor
expansion strip). Clicking an ancestor row navigates to that fn,
whose own layout DOES emit the bound values + sequence items.

What this means for layout-API consumers (UI tests, alternate
front-ends):

- A child's `/api/graph/layout?root=child` returns just the fn
  node + any own-binding placeholders. Inherited values are NOT
  in the response.
- To inspect the inherited values, request the parent's layout
  separately, or expand the child's ancestor row via the
  `:expansions` argument in the layout request.

This split is the load-bearing assumption pinned by the
`graphden.tools.browser-test/edit-inheritance-regression.test.js`
e2e test — parent layout exposes items, child layout doesn't.

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

#### 3.3 Node Classification by Relation to Shared Nodes

All paths in the graph start from a single root node. Paths to a shared node eventually
**diverge** — split among children of some common ancestor. This divergence can happen
at multiple levels. Based on their relation to paths leading to shared nodes, all nodes
fall into one of these categories:

**Category 1: Neutral nodes**
Nodes that do NOT lie on any path to any shared node.

```
Example: A has children B, C, D. C and D lead to shared F. B does not.
B is a neutral node.
```

**Category 2: Pre-divergence nodes**
Nodes that lie on ALL paths to a shared node, BEFORE the divergence point.
This always includes the graph root. There may be additional nodes between
root and the divergence point.

```
Example: A → X → {C, D} → F (shared)
A and X are pre-divergence nodes for F.
```

**Category 3: Divergence roots**
Siblings (children of the same parent) where the path to a shared node diverges.
These are the "roots" of upper and lower paths. Their relative position among
siblings determines which path becomes upper and which becomes lower.

**CRITICAL:** Divergence roots must be placed **adjacent to each other** in the
sorted children list. Neutral siblings must NOT be placed between them.
However, divergence roots do NOT necessarily move to the top or bottom of the
list — they stay at approximately their original position, just grouped together.

```
Example: A has children [B, C, D, E]. C and D both lead to shared F.
C and D are divergence roots. They must be adjacent: [B, C, D, E] or [B, D, C, E]
but NOT [B, C, E, D] (E between C and D is forbidden).
```

**Category 4: Path nodes (descendants of divergence roots)**
Nodes that lie on the path from a divergence root to the shared node, but are
NOT the divergence root itself. These have siblings that may be neutral.

Sorting rule for path nodes depends on whether they are on upper or lower path:

- **Upper path:** Path-to-shared children go LAST (pushed to higher row numbers)
- **Lower path:** Path-to-shared children go FIRST (stay at lower row numbers)

This ensures the lower path forms a horizontal branch leading directly to the
shared node, while upper paths curve down to meet it.

**Category 5: The shared node itself**
Removed from children lists of all parents EXCEPT the **shallowest** parent (min column depth;
when depths are equal, the later parent in edge order wins).
This ensures it is placed only once, by the parent closest to root.

**Category 6: Secondary divergence (special case)**
When a path that already diverged (upper or lower) diverges again for the SAME
shared node (shared node has 3+ parents):

- If secondary divergence happens on **upper path**: Both sub-paths become upper.
  Children are sorted by upper-path rules.
- If secondary divergence happens on **lower path**: One sub-path becomes upper,
  one remains lower (standard divergence rules apply).

**Rule:** There can be multiple upper paths, but only ONE lower path to each shared node.

#### 3.4 Compute Path Positions

For each node, determine if it's on "upper" or "lower" path:

- **Bottom parent:** Last parent in parent list of shared node
- **Lower path:** Bottom parent and ancestors leading to shared, **up to but NOT including
  the divergence point** (the node whose children split into upper/lower paths)
- **Upper path:** Non-bottom parents and their ancestors leading to shared, same stopping rule
- **Pre-divergence nodes** (divergence point and above): get NO path position (nil).
  Their children are sorted by default rules (fn > fixed > free), not by path rules.
- **Divergence roots** (the siblings where paths split): DO get path position.
  This determines how their own children are sorted.

**Propagation algorithm:** Starting from bottom parent (or upper parents), walk upward
through the `parents` map. At each step, filter eligible parents (leads to shared,
not a divergence point, not already marked), mark them, then continue with newly marked
nodes. Critical: filter BEFORE marking to avoid cutting off propagation when a parent
is marked and then immediately filtered out of the continuation set.

### Stage 4: Sort Children Lists

For each node, sort its children for optimal layout.

#### 4.1 Classification

Each child is classified as:

- **Neutral:** Does not lead to any shared node
- **Divergence root:** One of multiple siblings that lead to the same shared node
- **Path-to-shared:** Leads to shared node (not a divergence root)
- **Direct shared:** The child IS a shared node

#### 4.2 Sorting Rules

**Goal:** The lower path from divergence root to shared node should form a single
horizontal branch. All edges to the shared node should go DOWN or RIGHT, never up.

**Rules by parent category:**

**Rule 1: Pre-divergence and neutral parents**
Sort children by: type (fn > fixed-arg > free-arg), then by original index.
Divergence roots are grouped together at their original position but not moved.

**Rule 2: Divergence root parents (on lower path)**

- Path-to-shared children go **FIRST** (lower row numbers)
- Neutral children go **LAST**
- This makes the path-to-shared child the first child → horizontal branch → shared node at end

**Rule 3: Divergence root parents (on upper path)**

- Path-to-shared children go **LAST** (higher row numbers)
- Neutral children go **FIRST**
- This pushes the path-to-shared subtree down, making room for the lower path above

**Rule 4: Path node parents (on lower path)**
Same as Rule 2: path-to-shared children FIRST.

**Rule 5: Path node parents (on upper path)**
Same as Rule 3: path-to-shared children LAST.

**Rule 6: Divergence roots grouping**
When sorting children of a pre-divergence node, divergence roots targeting the same
shared node must be adjacent. The lower-path divergence root comes LAST in the group.

**Within any group:** Preserve original order (by original-idx), then sort by type.

### Stage 5: Compute Column Offsets

For shared nodes, direct parents at different column depths need alignment so that the
shared node (placed at keeper's col+1) is always to the RIGHT of all its parents,
eliminating backward edges.

**Problem:** If parent A is at column 3 and parent B is at column 5, an edge from B to
the shared node at column 4 (placed by A) would go LEFT — a backward edge.

**Solution:** Compute offsets using **column-depths** (BFS distance from root).
The deepest parent stays as-is; shallower parents shift right by the difference:

```clojure
;; For each shared node:
max-depth = max(column-depth(parent) for parent in parents)
parent-offsets[parent-id] = max-depth - column-depth(parent-id)
;; Multiple shared nodes: take max offset per parent
```

**Effect:** All direct parents end up at the same column. The shared node goes one
column to the right, guaranteeing no backward edges.

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
Graph (with branch-A expanded):
  root (col 0)
  ├── branch-A (col 1)
  │   ├── inner (col 2) → ... → leaf-X (col 3, row 1)
  │   └── child (col 2, row 2) → leaf-Y (col 3, row 2)
  └── branch-B (col 1)
      └── leaf-Z (col 2)

Processing:
1. Place horizontal branch: root, branch-A, inner, ...
2. Process branch-A subtree (fills cols 2-5, rows 0-2)
3. Process branch-B (sibling):
   - min_row = 1 (parent's row + 1)
   - Branch uses cols 1-2
   - Row 1: check cols 1-2 → both free! (leaf-X is at col 3)
   - Place at row 1, col 1

Result: branch-B can use row 1 because its columns (1-2) don't overlap
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

#### 6.5 Example Trace (Vertical Edge Reservation)

```
Graph (showing edge reservation preventing crossing):
  A
  ├── B → C → D (horizontal branch at row 0, cols 1-3)
  │        └── E (child of C at col 3)
  │        └── F (child of C at col 3, pushed to row 2)
  └── G (sibling of B)
      └── H (horizontal branch at row ?, cols 1-2)

Processing:
1. Place horizontal branch: A, B, C, D at row 0
2. Process children right-to-left:
   - C has child E → min_row = 1, place at (1,3)
   - C has child F → min_row = 1, col 3 occupied, place at (2,3)
     - Reserve vertical edge: col 3, rows 1-2 (from C at row 0 to F at row 2)
   - B has no second child
   - A has sibling G:
     - min_row = 1 (A's row + 1)
     - Branch = [G, H], uses cols 1-2
     - Check row 1, cols 1-2 → both free! (E is at col 3)
     - Place G(1,1) H(1,2)

Result:
  col:  0   1   2   3
  row 0: A   B   C   D
  row 1:     G   H   E
  row 2:             F

G can share row 1 with E because their columns don't overlap (G uses 1-2, E at 3).
The vertical edge reservation from C to F occupies row 1 col 3, preventing any
branch from using that cell.
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

## Frontend: Anchor Node Mechanism

When layout changes (expansion, preview), the **anchor node** stays stationary while
all other nodes move relative to it. This prevents disorienting jumps.

**Anchor selection priority:**

1. Explicit `anchorNodeId` — set by expansion click or preview hover/clear
2. Preview node — a key in the `previewState` map
3. None — graph fits to viewport

**Critical timing:** `anchorNodeId` and old position must be captured BEFORE the async
`fetchBackendLayout()` call (`editor-render.js` grabs `capturedAnchorNodeId`), because
callers clear `anchorNodeId` synchronously after calling `renderGraph()` (which is
async). After fetch completes, the saved anchor position is used to compute the offset
applied to all new positions.

**Preview anchor:** Both `applyHoverSpec` (hover) and `clearPreview` (mouseleave) set
`anchorNodeId` before calling `renderGraph`. Without this, clearing a preview would
cause the graph to jump back to unanchored position, then the node re-enters focus,
re-triggering preview — creating an infinite animation loop.

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

- `src/graphden/layout/` - The layout algorithm (`data.clj`, `graph.clj`, `builder_helpers.clj`, … — `derive-fn-slot-views`, `process-fn`, `collect-expanded-args`, etc.)
- `resources/packages/app/layout/impls.clj` - Thin `defbase` boundary (`_load-graph-cached`, `_layout-build-apply`, …) that calls into the algorithm above
- `test/graphden/layout/` - Layout tests
- `tools/browser-test/` - Visual browser tests
