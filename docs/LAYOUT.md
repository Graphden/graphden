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

**Per-call-site model (load-bearing).** The build stage emits one node
per *call site*, not one node per fn identity. A root fn keys by
`fn-<id>`; every nested fn keys by `(caller-node-id, source-arg-id)`
(`add-fn-node`, `builder_helpers.clj`), so two uses of the same fn from
two different bindings are two distinct nodes. Consequently **the graph
handed to placement is a TREE — every node has exactly one parent, and
no node is shared between parents.** This is why placement is a plain
depth-first tree walk (below) and why there is no shared-node path
analysis or column-offset alignment: those concepts have no referent
when nothing is shared.

## Pipeline Stages

```
Request → Load Data → Build Graph Elements → Place Nodes → Validate → Response
```

`graphden.layout.graph` performs Load Data + Build Graph Elements
(it labels these "Stages 1–2" internally); `graphden.layout.core`
performs Place Nodes + Validate + Response and parses the HTTP request.

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

Unified arg edges (2026-08-26): EVERY unset arg — required, optional,
ref-propagated (deep), or a HOF lambda-param — is emitted as the same
placeholder node + edge shape by `add-unset-arg-node`, carrying
`:optionalArg` / `:deepArg` / `:lambdaArg` flags the client styles by
(lighter / sparser dashes / λ ghost). The former compact badge strips
(`:optionalArgs` / `:hofCapturedArgs`) are retired; `:deepFreeArgs`
(the informational ⇣-strip on expanded inner nodes) remains.
- Level N > 0: Show function with N ancestors expanded (inheritance chain visible)

**Inheritance chain:** `[fn-id, parent-id, grandparent-id, ...]`

When expanding to level N:

- Display function shows ancestors up to level N
- Args from ancestors become visible
- Bindings (arg values set by descendants) flow to ancestor arg positions

#### 2.2 Node ID Generation

Node IDs encode the *call site*, so the same fn used from two places
produces two nodes (`add-fn-node` in `builder_helpers.clj`):

| Context | Node ID Format | Purpose |
|---------|----------------|---------|
| Root node (or any node with no `source-arg-id`) | `fn-{original-fn-id}` | Canonical root id |
| Nested reference | `fn-{caller-tag}-{source-arg-id}` | Scoped to the (caller, arg) that referenced it |

`caller-tag` is the caller's node-id with its leading `fn-` stripped, so
prefixes don't double at each nesting level.

**Key insight:** because the id folds in the referencing `(caller,
source-arg-id)` pair, a fn referenced from N different bindings becomes N
distinct nodes — matching Clojure call-site semantics. There is **no**
shared "canonical" node reused across references; sharing does not exist
in this model.

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

- Bindings from an expansion context apply to the structural nodes emitted
  *inside* that expansion.
- Because each reference is its own call-site node (§2.2), a binding on
  one call site never leaks onto a different call site of the same fn —
  this is what prevents false edges like `favicon-route → metrics-handler`.

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

This rule applies in these places in the code:

| Location | What it collects | Effect of exclusion |
|----------|-----------------|---------------------|
| `collect-expanded-args` → `ref-fn-arg-ids` | Args from displayed ref-fns | Items not filtered as "belonging inside ref" |
| `process-fn` → `displayed-ref-arg-ids` | Args from displayed child ref-fns | Item bindings not filtered inside structural nodes |
| `child-covered-sources-for-fn` | Slot-ids exposed by child ref's closure | Slots not mistakenly "covered by coll child" when they live in the shared ancestor |

The deep ancestor-ref subtree that expansion previously re-walked per
binding is now resolved through the migration-target index in
`process-expanded-fn-impl` (`ancestor-ref-fn-id-index` plus the
`migration-via-*` branches in `graph.clj`): it answers "does this fid
appear inside an ancestor ref's chain?" in O(1) instead of walking the
subtree for every binding.

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

### Stage 3: Sort Children Lists

Placement walks each node's children in a fixed order. Under the
per-call-site model every child has exactly one parent — nothing is
shared — so ordering is a plain **stable sort by child type**, with
original insertion order preserved inside a type (`order-children` /
`get-child-type` in `core.clj`):

| Type | Rank | Detected from node `:data` |
|------|------|----------------------------|
| `:fn` | 0 (first) | `:type` = `"fn"` |
| `:fixed` | 1 | `:type` = `"arg"` |
| `:free` | 2 (last) | placeholder (`:isPlaceholder`), missing data, or anything else |

So a node's fn children come first (in their original order), then its
fixed args, then its free args. There is no divergence/upper-path/lower-path
ordering — those existed only to route edges into shared nodes, and there
are no shared nodes here.

### Stage 4: Place Nodes on the Grid

`layout-graph` (`core.clj`) does a depth-first placement of the tree.
Because the element graph is a tree, no two nodes ever contend for the
same cell by construction; the row search below only has to dodge cells
already filled by *earlier* branches.

#### 4.1 Definitions

- **SELECTED node** — the node currently being placed.
- **Horizontal branch** — the chain of first-children starting at SELECTED
  (`build-branch`). Node *i* in the branch is assigned column `start-col + i`.
- **Branch length** — simply the number of nodes in the branch (there are
  no column offsets; consecutive columns are used).

#### 4.2 Algorithm Steps (`place-subtree`)

**Step 1 — Build the horizontal branch.** From SELECTED, follow the first
sorted child repeatedly, assigning consecutive columns:

```
branch = [SELECTED@col]
current = SELECTED
while current has children:
    first = sorted_children[current][0]
    branch.append(first@(col+1))
    current = first
```

**Step 2 — Find the row (column-aware compaction).** Starting at `min-row`
(0 for the graph root, else parent-row + 1), scan downward for the first
row where **every column used by the branch is free** (`find-row-for-branch`
→ `branch-fits-at-row?`). Only the branch's own columns are checked, so a
short branch can share a row with a deeper subtree whose nodes sit in
columns the branch doesn't touch.

**Step 3 — Place the branch** at that row (`place-branch`), one node per
consecutive column.

**Step 4 — Reserve the vertical edge.** If the branch landed more than one
row below its parent, mark the intervening cells in the child's column as
`{:vertical-edge true}` (`reserve-vertical-edge`) so no later branch can
cross that descending edge. Adjacent rows need no reservation.

**Step 5 — Recurse into remaining children, RIGHT-TO-LEFT.** Walk the branch
in reverse; for each branch node place its non-first children (each as a
full subtree via `place-subtree`), starting the row search at
`branch-row + 1` and the column at `node-col + 1`.

**Key invariant:** a node's ENTIRE subtree is placed before its next
sibling is started.

#### 4.3 Column-aware compaction example

```
Graph:
  A
  ├── B
  │   ├── D
  │   └── E
  └── C
      └── F

Step 1: SELECTED = A, branch = [A, B, D] (first-children chain)
Step 3: place at row 0 → A(0,0) B(0,1) D(0,2)
Step 5: process children right-to-left:
  - D has no more children
  - B has child E → min_row = 1, branch = [E] at col 2 → E(1,2)
  - A has child C → min_row = 1, branch = [C, F] uses cols 1-2
      row 1: col 2 occupied by E → try row 2 → C(2,1) F(2,2)

Result:
  col:  0   1   2
  row 0: A   B   D
  row 1:         E
  row 2:     C   F
```

C shares no row with E because col 2 is taken at row 1; it drops to row 2.

#### 4.4 Vertical-edge reservation example

```
Graph:
  A
  ├── B → C → D   (horizontal branch at row 0, cols 1-3)
  │        ├── E  (child of C)
  │        └── F  (child of C, pushed to row 2)
  └── G → H

Processing:
1. Place branch A, B, C, D at row 0.
2. Right-to-left:
   - C's child E → row 1, (1,3)
   - C's child F → col 3 busy at row 1 → row 2, (2,3);
     reserve vertical edge at col 3, row 1 (C@row0 → F@row2)
   - A's child G → branch [G, H] uses cols 1-2; row 1 free there → G(1,1) H(1,2)

Result:
  col:  0   1   2   3
  row 0: A   B   C   D
  row 1:     G   H   E
  row 2:             F
```

G shares row 1 with E because their columns don't overlap (G/H in 1-2, E at 3),
and the reserved cell at (row 1, col 3) keeps any branch off the C→F edge.

### Stage 5: Validate and Build Response

`place-elements` (and the test entry `compute-layout-matrix`) attach the
grid positions and a validation record, returning the response shape:

```clojure
{:nodes [...]
 :edges [...]
 :grid-pos {node-id {:row r :col c} ...}
 :validation {:valid true/false :issues [...] :warnings [...]}}
```

`validate-layout` (`core.clj`) separates **fatal** structural problems
(any one makes `:valid` false, reported in `:issues`) from **advisory**
signals (reported in `:warnings`, `:valid` stays true):

| `:type` | Severity | Condition |
|---------|----------|-----------|
| `no_root` | fatal | Input nodes exist but no zero-in-edge root was found (empty graph or a pure cycle), so nothing could be placed. |
| `collision` | fatal | Two placed nodes share a `(row, col)` cell. |
| `orphan` | advisory | A root exists but some input node has no path from it, so the DFS never gave it a position. Dropping a node unreachable from the selected root is intended behaviour (a fn graph often has sibling nodes off the root's subtree), so this is surfaced for observability without invalidating the layout. The message lists the unplaced node-ids. |

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
   - Stage 3: Wrong child ordering?
   - Stage 4: Wrong placement?

3. **Check invariants:**
   - Is depth-first order maintained?
   - Are all children placed before moving to sibling?
   - Are edge reservations correct?

4. **Update this document** if algorithm needs changes

5. **Never fix code without understanding which algorithm step is wrong**

## Related Files

- `src/graphden/layout/` - The layout algorithm: Load Data + Build Elements in `data.clj` / `graph.clj` / `builder_helpers.clj` (`derive-fn-slot-views`, `process-fn`, `collect-expanded-args`, …); Place Nodes + Validate + request parsing in `core.clj` (`order-children`, `layout-graph`, `validate-layout`, `parse-layout-request`)
- `resources/packages/app/layout/impls.clj` - Thin `defbase` boundary (`_load-graph-cached`, `_layout-build-apply`, …) that calls into the algorithm above
- `test/graphden/layout/` - Layout tests
- `tools/browser-test/` - Visual browser tests
