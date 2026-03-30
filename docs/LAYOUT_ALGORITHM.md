# Graph Layout Algorithm

## Overview

This document describes the grid-based layout algorithm for the Graphden visual editor. The algorithm places nodes in a rectangular matrix where each cell can contain at most one node, guaranteeing no node overlaps.

## Terminology

### Graph Elements

| Term | Description |
|------|-------------|
| **Root node** | The node from which the displayed graph starts; selected in the side menu and shown in URL |
| **Graph node** | Either a `fn` entity, an `arg` value, or an unset `arg` (free argument) |
| **Free argument** | An `arg` without `value` or `ref`; displayed with its type (any, jsonb, etc.) |
| **Fixed argument** | An `arg` with a concrete `value` (not `fn_ref`) |
| **Edge** | Connects parent node to argument node; represents an `arg` entity relationship |

### Relationships

| Term | Description |
|------|-------------|
| **Parent node** | The `fn` node that owns an argument |
| **Incoming edge** | Edge entering an argument node from its parent |
| **Outgoing edge** | Edge leaving a `fn` node to one of its arguments |
| **Shared argument** | A node with multiple incoming edges (multiple parents) |
| **Splitting nodes** | Nodes that have a shared argument as a direct child |

### Inheritance (not graph relationships)

| Term | Description |
|------|-------------|
| **Ancestor** | The `parent_id` chain of a `fn` entity (inheritance, not graph parent) |
| **Descendant** | Inverse of ancestor (inheritance) |
| **Expand** | Displaying an ancestor's implementation within the current graph |

### Layout Concepts

| Term | Description |
|------|-------------|
| **Matrix/Grid** | Rectangular placement grid; each cell holds at most one node |
| **Horizontal branch** | A chain of nodes where each is the first argument of the previous |
| **Optimal position** | A position that doesn't create crossings due to compact placement |
| **Step (bug)** | When a horizontal branch has a vertical offset - this is forbidden |

## Core Principles

1. **Grid-based placement**: Nodes are placed in a matrix; one node per cell maximum
2. **Left-to-right, top-to-bottom growth**: Graph starts at top-left corner
3. **Left alignment in columns, center alignment in rows**
4. **No steps in horizontal branches**: First argument is always on the same row as parent
5. **Horizontal branches are always straight**: If an argument needs to move down, its entire ancestor chain moves with it

## Argument Priority Order

Arguments are placed in this order (highest priority first):

1. `fn` arguments (function references)
2. Fixed value arguments
3. Free arguments

Within expanded ancestors, arguments follow the same priority, with newer descendants first.

**Exception**: Priority is modified for shared arguments to avoid crossings (see below).

## Edge Representation

Edges are visually either:
- Straight horizontal line (first argument on same row)
- Three-segment line: horizontal → vertical → horizontal (arguments below parent)

### Allowed Edge Merging

Edges from one parent to multiple children MAY share:
- Initial horizontal segments
- Vertical segments
- Final horizontal segment to a shared argument

Edges from DIFFERENT parents to different children MUST NOT cross.

## The Algorithm

### Phase 0: Pre-processing

1. Build adjacency maps (children, parents)
2. Find root node (node with no incoming edges)
3. **Detect shared arguments**: Find all nodes with multiple parents
4. **Build path maps**: For each node, store list of shared argument IDs reachable from it
5. **Identify splitting nodes**: Nodes whose children lead to the same shared argument

### Phase 1: Build Horizontal Branch

Starting from current node (root on first iteration):

1. Select first argument by priority (fn > fixed > free)
2. If it's a `fn`, select its first argument, and so on
3. Continue until reaching a node with only fixed/free arguments
4. The last node completes the branch

### Phase 2: Place Branch in Matrix

1. Branch "grows" right from its first node (which already has position)
2. Check for collisions with existing nodes/edges to the right
3. If collision detected: shift the branch DOWN along with:
   - The branch root
   - All sibling arguments below the root
4. This creates a vertical edge segment above the shifted nodes
5. Find the topmost row where the branch fits without collisions

### Phase 3: Process Remaining Arguments

Process the just-placed branch RIGHT TO LEFT:

1. Take the rightmost `fn` node
2. Place its next argument (by priority)
3. If only fixed/free arguments remain, place them (treat as single-node branches)
4. Move to the next node leftward
5. When all arguments of all nodes in branch are placed, return to parent branch
6. This is a depth-first traversal

### Phase 4: Repeat

Continue until all nodes are placed.

## Handling Shared Arguments

Shared arguments require special handling to avoid upward edges and crossings.

### Detection

Before placement:
1. Find all shared arguments (nodes with >1 parent)
2. For each node, compute which shared arguments are reachable from it
3. Store path lengths to shared arguments for optimization

### Splitting Arguments

When multiple siblings lead to the same shared argument:
- **Lower branch**: The path with shorter distance to shared argument
- **Upper branches**: All other paths to the same shared argument

### Placement Strategy

**For the lower branch:**
- Nodes on path to shared argument get HIGHEST priority
- This ensures the shared argument is ON the horizontal branch
- No upward edges needed

**For upper branches:**
- Nodes on path to shared argument get LOWEST priority
- They "hang" below, creating only downward vertical edges
- The vertical edge can reach the lower branch without crossing

### Aligning Parents

To connect upper branches to the shared argument:
1. Both immediate parents must be in the SAME column
2. If lower branch parent is to the left: extend the lower branch (simple, no sub-branches yet)
3. If upper branch parent is to the left: must shift the entire sub-tree

### Multiple Shared Arguments

When there are multiple shared arguments:
1. Process by "weight" (number of paths) - higher weight first
2. Conflicts may require heuristic resolution
3. Some crossings may be unavoidable - minimize them

## Matrix State

The matrix tracks:
- `nodeGrid[row][col]`: Node ID or null
- `hEdge[row][col]`: Horizontal edge segment info (argName) or null
- `vEdge[row][col]`: Boolean - vertical edge passes through this cell

### Edge Marking Rules

- Optimal positions don't need edge markers
- When node shifts DOWN from optimal: mark vertical edge above
- When node shifts RIGHT from optimal: mark horizontal edge to the left
- These marked cells cannot be occupied by other nodes or edges

## Validation

After placement, validate:
1. **No collisions**: Each grid cell has at most one node
2. **No crossings**: Horizontal edges don't cross vertical edges (except at their own endpoints)

## Performance Optimizations

1. **Column bounds cache**: Track lowest occupied row per column
2. **Subtree depth cache**: Pre-compute maximum depth of each subtree
3. **Deterministic ordering**: Use secondary sort by name for stability
4. **Incremental updates**: For expand, only recalculate affected regions

## Animation Guidelines

When expanding a node:
1. The expanded node stays in place - all movement is relative to it
2. New arguments "emerge" from their parent nodes cascading outward
3. Existing nodes that must move should animate to new positions
4. Avoid nodes swapping positions or re-appearing
