// Editor Layout - Grid-based layout algorithm with no edge crossings
// Depends on: editor-state.js
//
// See docs/LAYOUT_ALGORITHM.md for full algorithm description
//
// Core principles:
// 1. Nodes placed in matrix - one node per cell maximum
// 2. Horizontal branches: first argument on same row as parent
// 3. Other arguments hang below, creating vertical edges
// 4. Shared arguments handled specially to avoid upward edges

// ============================================================================
// ADJACENCY AND ROOT DETECTION
// ============================================================================

/**
 * Build adjacency maps from edges
 * @returns {Object} { children, parents, edgeArgNames }
 */
function buildAdjacency(edges) {
  const children = new Map();   // parentId -> [childId, ...]
  const parents = new Map();    // childId -> [parentId, ...]
  const edgeArgNames = new Map(); // "parentId->childId" -> argName
  const edgeSet = new Set();

  edges.forEach(e => {
    const src = e.data.source;
    const tgt = e.data.target;
    const edgeKey = src + '->' + tgt;

    if (edgeSet.has(edgeKey)) return;
    edgeSet.add(edgeKey);

    if (!children.has(src)) children.set(src, []);
    children.get(src).push(tgt);

    if (!parents.has(tgt)) parents.set(tgt, []);
    parents.get(tgt).push(src);

    if (e.data.argName) {
      edgeArgNames.set(edgeKey, e.data.argName);
    }
  });

  return { children, parents, edgeArgNames };
}

/**
 * Find root node (node with no incoming edges)
 */
function findRootNode(nodes, edges) {
  const hasIncoming = new Set();
  edges.forEach(e => hasIncoming.add(e.data.target));
  const root = nodes.find(n => !hasIncoming.has(n.data.id));
  return root ? root.data.id : null;
}

// ============================================================================
// NODE TYPE DETECTION
// ============================================================================

/**
 * Get node type for sorting: 'fn' > 'fixed' > 'free'
 */
function getNodeType(nodeId, nodeDataMap) {
  const data = nodeDataMap.get(nodeId);
  if (!data) return 'free';

  // Check placeholder first - these are unset args displayed as "any"
  if (data.isPlaceholder) return 'free';

  if (data.type === 'fn') return 'fn';
  if (data.type === 'arg') {
    return 'fixed';  // arg with value
  }
  return 'free';
}

/**
 * Sort children by priority:
 * 1. Lower branch start items for shared args (handled by adjustPriorityForShared)
 * 2. fn type arguments (functions)
 * 3. fixed arguments (values)
 * 4. free arguments (unset/placeholders)
 * 5. Lower branch end items for shared args (handled by adjustPriorityForShared)
 */
function sortChildrenByPriority(childIds, nodeDataMap, sharedInfo, currentNodeId, edgeArgNames) {
  // Sort by: fn > fixed > free (preserve relative order within each group)
  const prioritized = childIds.map((childId, originalIndex) => {
    const type = getNodeType(childId, nodeDataMap);
    // Priority: fn=0, fixed=1, free=2
    let priority;
    if (type === 'fn') priority = 0;
    else if (type === 'fixed') priority = 1;
    else priority = 2;  // free
    return { childId, priority, originalIndex };
  });

  // Stable sort: sort by priority, preserve original order within same priority
  prioritized.sort((a, b) => {
    if (a.priority !== b.priority) return a.priority - b.priority;
    return a.originalIndex - b.originalIndex;
  });

  return prioritized.map(p => p.childId);
}

// ============================================================================
// SHARED ARGUMENT DETECTION
// ============================================================================

/**
 * Find all shared arguments and build path information
 * @returns {Object} {
 *   sharedNodes: Set of nodeIds with multiple parents,
 *   pathsToShared: Map nodeId -> Set of reachable shared nodeIds,
 *   pathLengths: Map "nodeId->sharedId" -> distance
 * }
 */
function analyzeSharedArguments(children, parents) {
  // Find shared nodes
  const sharedNodes = new Set();
  parents.forEach((parentList, nodeId) => {
    if (parentList.length > 1) {
      sharedNodes.add(nodeId);
    }
  });

  if (sharedNodes.size === 0) {
    return { sharedNodes, pathsToShared: new Map(), pathLengths: new Map() };
  }

  // Build paths to shared nodes using BFS from each shared node backwards
  const pathsToShared = new Map();  // nodeId -> Set of reachable shared nodeIds
  const pathLengths = new Map();    // "nodeId->sharedId" -> distance

  // For each shared node, trace back to find all ancestors
  sharedNodes.forEach(sharedId => {
    const visited = new Set();
    const queue = [{ nodeId: sharedId, dist: 0 }];

    while (queue.length > 0) {
      const { nodeId, dist } = queue.shift();

      if (visited.has(nodeId)) continue;
      visited.add(nodeId);

      // Record that this node leads to sharedId
      if (!pathsToShared.has(nodeId)) pathsToShared.set(nodeId, new Set());
      pathsToShared.get(nodeId).add(sharedId);
      pathLengths.set(nodeId + '->' + sharedId, dist);

      // Add all parents to queue
      const nodeParents = parents.get(nodeId) || [];
      nodeParents.forEach(parentId => {
        if (!visited.has(parentId)) {
          queue.push({ nodeId: parentId, dist: dist + 1 });
        }
      });
    }
  });

  return { sharedNodes, pathsToShared, pathLengths };
}

/**
 * Find splitting nodes for a shared argument
 * (siblings that both lead to the same shared node)
 *
 * Returns lowerChild (the one that will be on the lower row, where shared node goes)
 * and upperChildren (the ones above).
 *
 * The "lower" child is determined by:
 * 1. LONGER path length to shared node (more levels = more branching = lower on screen)
 * 2. If equal, LATER index in childIds (later in list = processed later = lower row)
 */
function findSplittingInfo(nodeId, childIds, sharedId, pathsToShared, pathLengths) {
  const leadingChildren = childIds.filter(childId => {
    const paths = pathsToShared.get(childId);
    return paths && paths.has(sharedId);
  });

  if (leadingChildren.length < 2) return null;

  // Sort by path length DESCENDING (longer path = lower branch)
  // When equal, preserve original order (stable sort) - later index = lower
  // We need the LAST one with max path length
  let maxPathLen = -1;
  let lowerChildIdx = -1;

  leadingChildren.forEach((childId, idx) => {
    const dist = pathLengths.get(childId + '->' + sharedId) || 0;
    // Use >= so that when path lengths are equal, the LAST one wins
    if (dist >= maxPathLen) {
      maxPathLen = dist;
      lowerChildIdx = idx;
    }
  });

  const lowerChild = leadingChildren[lowerChildIdx];
  const upperChildren = leadingChildren.filter((_, idx) => idx !== lowerChildIdx);

  return {
    sharedId,
    lowerChild,      // longest path (or last when equal) - goes horizontal, shared node on its row
    upperChildren    // shorter paths (or earlier when equal) - hang above
  };
}

// ============================================================================
// MATRIX STATE
// ============================================================================

function createMatrixState() {
  return {
    nodeGrid: [],   // nodeGrid[row][col] = nodeId or null
    hEdge: [],      // hEdge[row][col] = argName or null (horizontal edge segment)
    vEdge: []       // vEdge[row][col] = true/false (vertical edge passes through)
  };
}

function ensureMatrixSize(matrix, row, col) {
  const { nodeGrid, hEdge, vEdge } = matrix;
  while (nodeGrid.length <= row) nodeGrid.push([]);
  while (hEdge.length <= row) hEdge.push([]);
  while (vEdge.length <= row) vEdge.push([]);
  for (let r = 0; r <= row; r++) {
    while (nodeGrid[r].length <= col) nodeGrid[r].push(null);
    while (hEdge[r].length <= col) hEdge[r].push(null);
    while (vEdge[r].length <= col) vEdge[r].push(false);
  }
}

function getNodeAt(matrix, row, col) {
  if (row < 0 || col < 0) return null;
  if (row >= matrix.nodeGrid.length) return null;
  if (col >= matrix.nodeGrid[row].length) return null;
  return matrix.nodeGrid[row][col];
}

function hasVEdgeAt(matrix, row, col) {
  if (row < 0 || col < 0) return false;
  if (row >= matrix.vEdge.length) return false;
  if (col >= matrix.vEdge[row].length) return false;
  return matrix.vEdge[row][col];
}

function hasHEdgeAt(matrix, row, col) {
  if (row < 0 || col < 0) return false;
  if (row >= matrix.hEdge.length) return false;
  if (col >= matrix.hEdge[row].length) return false;
  return matrix.hEdge[row][col] !== null;
}

function isCellOccupied(matrix, row, col) {
  return getNodeAt(matrix, row, col) !== null ||
         hasVEdgeAt(matrix, row, col) ||
         hasHEdgeAt(matrix, row, col);
}

function placeNode(matrix, nodeId, row, col) {
  ensureMatrixSize(matrix, row, col);
  matrix.nodeGrid[row][col] = nodeId;
}

function placeHEdge(matrix, row, col, argName) {
  ensureMatrixSize(matrix, row, col);
  matrix.hEdge[row][col] = argName || '';
}

function placeVEdge(matrix, row, col) {
  ensureMatrixSize(matrix, row, col);
  // Don't place vEdge where a node already exists - would cause crossing
  if (matrix.nodeGrid[row][col] !== null) {
    return false; // Indicate failure
  }
  matrix.vEdge[row][col] = true;
  return true;
}

function removeVEdge(matrix, row, col) {
  if (row >= 0 && row < matrix.vEdge.length &&
      col >= 0 && col < matrix.vEdge[row].length) {
    matrix.vEdge[row][col] = false;
  }
}

// ============================================================================
// LAYOUT HELPERS
// ============================================================================

/**
 * Find the lowest occupied row in given column range
 * Considers both nodes and edges
 */
function findLowestInColumns(matrix, startCol, length) {
  let lowest = -1;
  for (let c = startCol; c < startCol + length; c++) {
    // Check nodes
    for (let r = 0; r < matrix.nodeGrid.length; r++) {
      if (getNodeAt(matrix, r, c) !== null) {
        lowest = Math.max(lowest, r);
      }
    }
    // Check vertical edges
    for (let r = 0; r < matrix.vEdge.length; r++) {
      if (hasVEdgeAt(matrix, r, c)) {
        lowest = Math.max(lowest, r);
      }
    }
    // Check horizontal edges
    for (let r = 0; r < matrix.hEdge.length; r++) {
      if (hasHEdgeAt(matrix, r, c)) {
        lowest = Math.max(lowest, r);
      }
    }
  }
  return lowest;
}

/**
 * Check if placing a horizontal branch at given row would cause collisions
 */
function checkBranchCollision(matrix, row, startCol, length) {
  for (let c = startCol; c < startCol + length; c++) {
    if (isCellOccupied(matrix, row, c)) {
      return true;
    }
  }
  return false;
}

/**
 * Find a row where a branch of given length can fit starting at startCol
 */
function findRowForBranch(matrix, startCol, length, minRow) {
  let row = minRow;
  while (checkBranchCollision(matrix, row, startCol, length)) {
    row++;
    if (row > 1000) {
      console.error('Layout error: could not find row for branch');
      break;
    }
  }
  return row;
}

// ============================================================================
// HORIZONTAL BRANCH BUILDING
// ============================================================================

/**
 * Build a horizontal branch starting from nodeId
 * Follows first children until reaching a leaf or already-placed node
 * @returns Array of nodeIds forming the branch
 */
function buildHorizontalBranch(
  startNodeId,
  children,
  placed,
  nodeDataMap,
  sharedInfo,
  branchContext,  // { isLowerBranch, targetSharedId, isUpperBranch }
  edgeArgNames,   // Map of "parentId->childId" -> argName
  reservedForLower,  // Set of nodeIds reserved for lower branch
  parents,         // Map of nodeId -> [parentIds]
  gridPos          // Map of nodeId -> { row, col }
) {
  const branch = [];
  let current = startNodeId;

  while (current && !placed.has(current)) {
    // If this node is reserved for lower branch, only lower branch targeting it can place it
    if (reservedForLower && reservedForLower.has(current)) {
      // Only lower branch targeting this exact shared node can proceed
      if (branchContext && branchContext.isLowerBranch && branchContext.targetSharedId === current) {
        // Lower branch reaching its target - unreserve it so it can be placed
        reservedForLower.delete(current);
      } else {
        // All other cases (upper branch, no context, or lower branch targeting different shared)
        // should stop here - the correct lower branch will place this node later
        break;
      }
    }

    branch.push(current);

    const nodeChildren = children.get(current) || [];
    if (nodeChildren.length === 0) break;

    // Sort children by priority (preserves original order from DB)
    let sortedChildren = sortChildrenByPriority(nodeChildren, nodeDataMap, sharedInfo, current, edgeArgNames);

    // Modify priority for shared argument handling
    // For lower parent of shared node: put shared node FIRST (goes horizontal)
    if (sharedInfo.sharedNodes.size > 0) {
      sortedChildren = adjustPriorityForShared(
        sortedChildren,
        sharedInfo,
        branchContext,
        placed,
        current,
        parents,
        gridPos,
        reservedForLower
      );
    }

    // Take first unplaced child
    // For shared nodes: lower parent places them horizontally, upper parent skips them
    let nextChild = null;

    for (const c of sortedChildren) {
      if (placed.has(c)) continue;

      // Check if this is a reserved shared node
      if (reservedForLower && reservedForLower.has(c)) {
        // Check if current node is the LOWER parent of this shared node
        const sharedParents = parents.get(c) || [];
        if (sharedParents.length >= 2) {
          let maxPathLen = -1;
          let lowerParent = null;
          for (const pid of sharedParents) {
            const pathKey = pid + '->' + c;
            const pathLen = sharedInfo.pathLengths.get(pathKey) || 1;
            if (pathLen >= maxPathLen) {
              maxPathLen = pathLen;
              lowerParent = pid;
            }
          }
          if (lowerParent === current) {
            // We ARE the lower parent - unreserve and include in horizontal branch
            reservedForLower.delete(c);
            nextChild = c;
            break;
          }
        }
        // Not the lower parent - skip this shared node
        continue;
      }

      nextChild = c;
      break;
    }
    if (!nextChild) break;

    // Check node type for branch continuation decision
    const nextType = getNodeType(nextChild, nodeDataMap);

    // If next child is NOT fn (placeholder/value), add it and stop branch
    // It will be placed on same row as parent (first child rule)
    if (nextType !== 'fn') {
      branch.push(nextChild);
      break;
    }

    // For fn nodes, continue the branch
    current = nextChild;
  }

  return branch;
}

/**
 * Adjust child priority based on shared argument handling.
 *
 * This function handles TWO cases:
 * 1. Direct: current node is a parent of a shared node → put shared node FIRST
 * 2. Splitting node: current node is where paths to shared node diverge →
 *    put the child leading to LOWER parent LAST (so it goes below)
 *
 * The goal is to ensure that branches between two parents of a shared node
 * don't interfere - the lower parent's branch should be below other branches.
 *
 * NOTE: This function is called DURING layout, before gridPos is fully populated,
 * so we cannot rely on row positions. We use path lengths and parent order instead.
 */
function adjustPriorityForShared(sortedChildren, sharedInfo, branchContext, placed, currentNodeId, parents, gridPos, reservedForLower) {
  if (sharedInfo.sharedNodes.size === 0) {
    return sortedChildren;
  }

  const result = [...sortedChildren];

  // Case 1: Direct - current node is a parent of a shared node
  // For LOWER parent: shared child goes FIRST (horizontal, on same row)
  // For UPPER parent: shared child goes LAST (hangs below)
  const sharedChildren = sortedChildren.filter(childId => sharedInfo.sharedNodes.has(childId));

  for (const sharedChildId of sharedChildren) {
    const childParents = parents.get(sharedChildId) || [];
    if (childParents.length < 2) continue;

    // Check if current node is a parent of this shared child
    if (!childParents.includes(currentNodeId)) continue;

    // Determine which parent is "lower" (longer path = processed later = lower row)
    // When path lengths are equal, later in parent list = lower
    let lowerParent = null;
    let maxPathLen = -1;
    for (const pid of childParents) {
      const pathLen = sharedInfo.pathLengths.get(pid + '->' + sharedChildId) || 1;
      if (pathLen >= maxPathLen) {
        maxPathLen = pathLen;
        lowerParent = pid;
      }
    }

    const idx = result.indexOf(sharedChildId);
    if (idx < 0) continue;

    if (currentNodeId === lowerParent) {
      // LOWER parent: shared child goes FIRST (horizontal)
      if (idx > 0) {
        result.splice(idx, 1);
        result.unshift(sharedChildId);
      }
    } else {
      // UPPER parent: shared child goes LAST (hangs below)
      if (idx < result.length - 1) {
        result.splice(idx, 1);
        result.push(sharedChildId);
      }
    }
  }

  // Case 2: Handle nodes on the path to lower parent
  // If current node has exactly ONE child leading to a shared node's lower parent,
  // that child should go FIRST (horizontal) to minimize distance between parents.
  //
  // Exception: at the SPLITTING NODE (where paths diverge), the child leading to
  // lower parent should go LAST so upper parent is placed first (higher row).

  sharedInfo.sharedNodes.forEach(sharedId => {
    const sharedParents = parents.get(sharedId) || [];
    if (sharedParents.length < 2) return;

    // Find children that lead to this shared node
    const childrenLeadingToShared = result.filter(childId => {
      const paths = sharedInfo.pathsToShared.get(childId);
      return paths && paths.has(sharedId);
    });

    if (childrenLeadingToShared.length === 0) return;

    // Find the lower parent
    let lowerParent = null;
    let maxPathLen = -1;

    for (const parentId of sharedParents) {
      const pathKey = parentId + '->' + sharedId;
      const pathLen = sharedInfo.pathLengths.get(pathKey) || 1;
      if (pathLen >= maxPathLen) {
        maxPathLen = pathLen;
        lowerParent = parentId;
      }
    }

    if (!lowerParent) return;

    // Find which child leads to the lower parent (has longest path to shared)
    let childToLower = null;
    let maxChildPath = -1;

    for (const childId of childrenLeadingToShared) {
      const pathLen = sharedInfo.pathLengths.get(childId + '->' + sharedId) || 0;
      if (pathLen > maxChildPath) {
        maxChildPath = pathLen;
        childToLower = childId;
      }
    }

    if (!childToLower) return;

    // Check if this is a SPLITTING NODE (2+ children lead to the same shared node)
    const isSplittingNode = childrenLeadingToShared.length >= 2;

    if (isSplittingNode) {
      // At splitting node: children are processed RIGHT-TO-LEFT when placing.
      // So higher index = processed first = placed at lower row number.

      // Find the child leading to upper parent (shorter path to shared)
      let childToUpper = null;
      let minChildPath = Infinity;
      for (const childId of childrenLeadingToShared) {
        if (childId === childToLower) continue;
        const pathLen = sharedInfo.pathLengths.get(childId + '->' + sharedId) || 0;
        if (pathLen < minChildPath) {
          minChildPath = pathLen;
          childToUpper = childId;
        }
      }

      if (childToUpper) {
        // Check if these children ARE the parents themselves (direct case)
        // vs children that LEAD TO the parents (indirect case)
        const sharedParents = parents.get(sharedId) || [];
        const isDirectCase = sharedParents.includes(childToUpper) && sharedParents.includes(childToLower);

        if (isDirectCase) {
          // Direct case: children ARE the parents of shared node
          // Move them to END of list so they're processed first (right-to-left)
          // and placed adjacent to each other
          const toRemove = [childToUpper, childToLower];
          const newResult = result.filter(id => !toRemove.includes(id));

          // Push in order: lower first (lower index), upper second (higher index)
          // Higher index = processed first = placed at lower row
          newResult.push(childToLower);  // processed second, higher row
          newResult.push(childToUpper);  // processed first, lower row

          result.length = 0;
          newResult.forEach(id => result.push(id));
        } else {
          // Indirect case: children LEAD TO the parents
          // childToLower should come right after childToUpper
          const upperIdx = result.indexOf(childToUpper);
          const lowerIdx = result.indexOf(childToLower);

          if (upperIdx >= 0 && lowerIdx >= 0 && lowerIdx !== upperIdx + 1) {
            result.splice(lowerIdx, 1);
            const newUpperIdx = result.indexOf(childToUpper);
            result.splice(newUpperIdx + 1, 0, childToLower);
          }
        }
      }
    } else {
      // Not a splitting node: only one child leads to shared node
      // Check if childToLower IS the lower parent itself, or is its DIRECT parent
      // Only in these cases do we move it to front
      const isDirectParentOfLower = childToLower === lowerParent ||
        (parents.get(lowerParent) || []).includes(childToLower);

      if (isDirectParentOfLower) {
        // This child is the lower parent or its direct parent
        // Move to FIRST (horizontal) to keep the path compact
        const idx = result.indexOf(childToLower);
        if (idx > 0) {
          result.splice(idx, 1);
          result.unshift(childToLower);
        }
      }
      // Otherwise, don't change order - we're too far from the lower parent
    }
  });

  return result;
}

// ============================================================================
// MAIN LAYOUT ALGORITHM
// ============================================================================

/**
 * Build the complete layout matrix
 */
function buildMatrix(rootId, children, parents, edgeArgNames, nodeDataMap) {
  const matrix = createMatrixState();
  const gridPos = new Map();  // nodeId -> { row, col }
  const placed = new Set();

  // Analyze shared arguments
  const sharedInfo = analyzeSharedArguments(children, parents);

  // Track splitting decisions
  const splittingDecisions = new Map();  // sharedId -> { lowerChild, upperChildren }

  // Reserved nodes - shared arguments that should only be placed by lower branch
  const reservedForLower = new Set();  // Set of shared nodeIds reserved for lower branch

  // Deferred children - children of upper parents that should be placed after shared node
  const deferredChildren = new Map();  // parentId -> [{ childId, nodeCol, nodeRow }]

  // Track how many parents of each shared node have been placed
  // Shared node is placed only when ALL parents are placed
  const sharedParentCount = new Map();  // sharedId -> total parent count
  const sharedParentsPlaced = new Map();  // sharedId -> Set of placed parent nodeIds

  // Pre-analyze: find all shared nodes and reserve them for lower branches
  // This ensures upper branches don't place shared nodes before lower branches get to them
  sharedInfo.sharedNodes.forEach(sharedId => {
    reservedForLower.add(sharedId);
    const parentIds = parents.get(sharedId) || [];
    sharedParentCount.set(sharedId, parentIds.length);
    sharedParentsPlaced.set(sharedId, new Set());
  });

  // Track target columns for parents of shared nodes
  // When a splitting node is detected, we calculate where all parents should be aligned
  // sharedParentTargetCol: Map<sharedId, { targetCol: number, splittingNodeId: nodeId, splittingCol: number }>
  const sharedParentTargetCol = new Map();

  // Map from parent nodeId to its target column (for shared node alignment)
  // parentTargetCol: Map<parentNodeId, targetCol>
  const parentTargetCol = new Map();

  // ============================================================================
  // GLOBAL PRE-ANALYSIS: Find all splitting nodes and calculate target columns
  // This must happen BEFORE any placement so we know where to place nodes
  // ============================================================================

  /**
   * Recursively analyze tree to find splitting nodes and calculate target columns
   * for parents of shared arguments.
   *
   * @param nodeId Current node being analyzed
   * @param currentCol Column where this node would be placed (estimated)
   * @param visited Set of already-visited nodes to prevent infinite loops
   */
  function preAnalyzeTree(nodeId, currentCol, visited) {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);

    const nodeChildren = children.get(nodeId) || [];
    if (nodeChildren.length === 0) return;

    // Check if this node is a splitting node for any shared argument
    sharedInfo.sharedNodes.forEach(sharedId => {
      const info = findSplittingInfo(nodeId, nodeChildren, sharedId, sharedInfo.pathsToShared, sharedInfo.pathLengths);
      if (info && !splittingDecisions.has(info.sharedId)) {
        splittingDecisions.set(info.sharedId, info);

        // Calculate target column for parents of this shared node
        const sharedParents = parents.get(sharedId) || [];
        let maxDistToParent = 0;

        for (const childId of nodeChildren) {
          const paths = sharedInfo.pathsToShared.get(childId);
          if (paths && paths.has(sharedId)) {
            const distToShared = sharedInfo.pathLengths.get(childId + '->' + sharedId) || 0;
            const distToParent = Math.max(0, distToShared - 1);
            maxDistToParent = Math.max(maxDistToParent, distToParent);
          }
        }

        // Target column: splitting node's column + 1 + max distance to parent
        const targetCol = currentCol + 1 + maxDistToParent;
        sharedParentTargetCol.set(sharedId, {
          targetCol,
          splittingNodeId: nodeId,
          splittingCol: currentCol
        });

        // Register each parent of this shared node to be placed at targetCol
        for (const parentId of sharedParents) {
          if (!parentTargetCol.has(parentId)) {
            parentTargetCol.set(parentId, targetCol);
          }
        }
      }
    });

    // Recursively analyze children
    // First child continues at currentCol + 1, others go below (same column)
    let childCol = currentCol + 1;
    for (const childId of nodeChildren) {
      // Check if this child has a target column (is parent of shared node)
      const targetCol = parentTargetCol.get(childId);
      const actualCol = (targetCol !== undefined && targetCol > childCol) ? targetCol : childCol;

      preAnalyzeTree(childId, actualCol, visited);

      // Only first child advances the column
      // (subsequent children are placed below, not to the right)
      // But we still need to track the rightmost column in the subtree
    }
  }

  // Run global pre-analysis starting from root
  if (rootId) {
    preAnalyzeTree(rootId, 0, new Set());
  }

  // ============================================================================
  // END OF GLOBAL PRE-ANALYSIS
  // ============================================================================

  /**
   * Try to place a shared node after a parent has been placed.
   * Only places the shared node when ALL its parents have been placed.
   * Places it at column = max(branch end columns) + 1.
   *
   * Row determination (to satisfy FIRST_CHILD_SAME_ROW rule):
   * - If shared node is the FIRST child of any parent, use that parent's row
   * - Otherwise, use the row of the lower parent (max row)
   *
   * @returns true if shared node was placed, false otherwise
   */
  function tryPlaceSharedNode(sharedId, parentNodeId, parentPos) {
    if (placed.has(sharedId)) return true;  // Already placed
    if (!reservedForLower.has(sharedId)) return false;  // Not a shared node we're tracking

    // Record this parent as placed
    const placedParents = sharedParentsPlaced.get(sharedId);
    placedParents.add(parentNodeId);

    // Check if all parents are placed
    const totalParents = sharedParentCount.get(sharedId);
    if (placedParents.size < totalParents) {
      return false;  // Still waiting for more parents
    }

    // All parents are placed! Now place the shared node.
    // Find max column among ALL nodes in branches leading to this shared node
    // (not just immediate parents, but their children too)
    let maxBranchCol = 0;

    // First, find the "lower parent" - the one that should have shared node on its row
    // Lower parent = the one with the HIGHEST row number (visually lowest on screen)
    // This must be determined by actual position since all parents are now placed.
    const parentIds = parents.get(sharedId) || [];
    let lowerParent = null;
    let maxParentRow = -1;

    placedParents.forEach(pid => {
      const pos = gridPos.get(pid);
      if (pos) {
        maxBranchCol = Math.max(maxBranchCol, pos.col);

        // Find parent with highest row number (visually lowest)
        if (pos.row > maxParentRow) {
          maxParentRow = pos.row;
          lowerParent = pid;
        }

        // Also check all children of this parent that are already placed
        // (these are siblings to the shared node and occupy columns)
        const parentChildren = children.get(pid) || [];
        parentChildren.forEach(childId => {
          if (childId !== sharedId && placed.has(childId)) {
            const childPos = gridPos.get(childId);
            if (childPos) {
              maxBranchCol = Math.max(maxBranchCol, childPos.col);
            }
          }
        });
      }
    });

    // Determine the correct row for the shared node:
    // Use the row of the lower parent (the one with highest row number)
    let sharedRow = maxParentRow;

    const sharedCol = maxBranchCol + 1;

    // Place shared node at the target row (lower parent's row)
    // If there are blocking vertical edges, REMOVE them - they can be rerouted later
    // This ensures shared nodes are placed at the correct position
    let actualRow = sharedRow;
    const sharedChildrenForRowCheck = children.get(sharedId) || [];
    const needsChildCol = sharedChildrenForRowCheck.length > 0 &&
                          !placed.has(sharedChildrenForRowCheck[0]);

    // Check if target cell has a NODE (not edge) - only then do we need to move
    const hasNodeAtTarget = getNodeAt(matrix, sharedRow, sharedCol) !== null;
    const hasNodeAtChildTarget = needsChildCol && getNodeAt(matrix, sharedRow, sharedCol + 1) !== null;

    if (hasNodeAtTarget || hasNodeAtChildTarget) {
      // There's an actual node blocking the target position
      // Find next free row
      while (true) {
        const sharedCellHasNode = getNodeAt(matrix, actualRow, sharedCol) !== null;
        const childCellHasNode = needsChildCol && getNodeAt(matrix, actualRow, sharedCol + 1) !== null;

        if (!sharedCellHasNode && !childCellHasNode) {
          break; // Both cells are free (no nodes)
        }
        actualRow++;
        if (actualRow > 1000) {
          console.error('Layout error: could not find row for shared node and child');
          break;
        }
      }
    }

    // Clear any vertical edges at the target position - they can be rerouted
    // This is safe because we'll redraw edges to the shared node properly
    if (hasVEdgeAt(matrix, actualRow, sharedCol)) {
      removeVEdge(matrix, actualRow, sharedCol);
    }
    if (needsChildCol && hasVEdgeAt(matrix, actualRow, sharedCol + 1)) {
      removeVEdge(matrix, actualRow, sharedCol + 1);
    }

    // Place the shared node
    placeNode(matrix, sharedId, actualRow, sharedCol);
    gridPos.set(sharedId, { row: actualRow, col: sharedCol });
    placed.add(sharedId);
    reservedForLower.delete(sharedId);

    // Now recursively place children of the shared node
    // IMPORTANT: First child goes on SAME row as shared node (horizontal)
    const sharedChildren = children.get(sharedId) || [];
    if (sharedChildren.length > 0) {
      let isFirstChild = true;
      let childRow = actualRow;  // Start on same row, not below!

      for (const childId of sharedChildren) {
        if (placed.has(childId)) continue;

        const childCol = sharedCol + 1;

        if (isFirstChild) {
          // First child: place on same row as shared node
          placeBranchAndChildren(childId, childCol, childRow, null);
          isFirstChild = false;
        } else {
          // Subsequent children: place below
          childRow = Math.max(childRow + 1, actualRow + 1);
          placeBranchAndChildren(childId, childCol, childRow, null);
        }

        const childPos = gridPos.get(childId);
        if (childPos) {
          // Draw edge to child
          const argName = edgeArgNames.get(sharedId + '->' + childId) || '';
          if (childPos.row === actualRow) {
            // Horizontal edge
            for (let c = sharedCol; c < childPos.col; c++) {
              if (!hasHEdgeAt(matrix, actualRow, c)) {
                placeHEdge(matrix, actualRow, c, c === sharedCol ? argName : '');
              }
            }
          } else {
            // Vertical edge (L-shaped)
            for (let r = actualRow + 1; r <= childPos.row; r++) {
              placeVEdge(matrix, r, sharedCol);
            }
            // Horizontal part at child's row
            for (let c = sharedCol; c < childPos.col; c++) {
              if (!hasHEdgeAt(matrix, childPos.row, c)) {
                placeHEdge(matrix, childPos.row, c, c === sharedCol ? argName : '');
              }
            }
          }
          childRow = childPos.row;
        }
      }
    }

    // Process deferred children of upper parents now that shared node is placed
    // These were skipped during upper parent processing to keep shared node row free
    const upperParentIds = parents.get(sharedId) || [];
    for (const upperParentId of upperParentIds) {
      if (upperParentId === lowerParent) continue;  // Skip lower parent

      const deferred = deferredChildren.get(upperParentId);
      if (!deferred || deferred.length === 0) continue;

      const upperPos = gridPos.get(upperParentId);
      if (!upperPos) continue;

      // Place deferred children below the shared node
      let deferredRow = actualRow + 1;
      for (const { childId, nodeCol } of deferred) {
        if (placed.has(childId)) continue;

        const childCol = nodeCol + 1;
        placeBranchAndChildren(childId, childCol, deferredRow, null);

        const childPos = gridPos.get(childId);
        if (childPos) {
          // Draw edge from upper parent to this child
          const argName = edgeArgNames.get(upperParentId + '->' + childId) || '';
          drawEdgeToPlaced(matrix, upperPos.row, upperPos.col, childPos.row, childPos.col, argName);
          deferredRow = childPos.row + 1;
        }
      }

      // Clear processed deferred children
      deferredChildren.delete(upperParentId);
    }

    return true;
  }

  /**
   * Place a horizontal branch and recursively place its sub-branches
   */
  function placeBranchAndChildren(startNodeId, startCol, minRow, branchContext) {
    // Build the horizontal branch
    const branch = buildHorizontalBranch(
      startNodeId, children, placed, nodeDataMap, sharedInfo, branchContext, edgeArgNames, reservedForLower, parents, gridPos
    );

    if (branch.length === 0) return minRow;

    // Filter out already placed nodes
    const newNodes = branch.filter(n => !placed.has(n));
    if (newNodes.length === 0) return minRow;

    // Check if any node in branch has a target column (is parent of shared node)
    // If so, we need to adjust placement to respect that target column
    let hasTargetCol = false;
    let maxTargetCol = startCol;
    for (const nodeId of newNodes) {
      const targetCol = parentTargetCol.get(nodeId);
      if (targetCol !== undefined) {
        hasTargetCol = true;
        maxTargetCol = Math.max(maxTargetCol, targetCol);
      }
    }

    // Calculate actual column for each node
    // If a node has a target column, place it there; otherwise place sequentially
    const nodeColumns = new Map();
    let currentCol = startCol;

    for (const nodeId of newNodes) {
      const targetCol = parentTargetCol.get(nodeId);
      if (targetCol !== undefined && targetCol > currentCol) {
        // This node is a parent of a shared node - place at target column
        nodeColumns.set(nodeId, targetCol);
        currentCol = targetCol + 1;
      } else {
        nodeColumns.set(nodeId, currentCol);
        currentCol++;
      }
    }

    // Determine actual branch length (may be longer due to gaps for target columns)
    const branchEndCol = currentCol;
    const branchLength = branchEndCol - startCol;

    // Find row for this branch
    const row = findRowForBranch(matrix, startCol, branchLength, minRow);

    // Place branch nodes
    for (let i = 0; i < newNodes.length; i++) {
      const nodeId = newNodes[i];
      const col = nodeColumns.get(nodeId);

      placeNode(matrix, nodeId, row, col);
      gridPos.set(nodeId, { row, col });
      placed.add(nodeId);

      // If this node was a shared node, process deferred children of its upper parents
      if (sharedInfo.sharedNodes.has(nodeId)) {
        const sharedParents = parents.get(nodeId) || [];

        // Find lower parent
        let lowerParent = null;
        let maxPathLen = -1;
        for (const pid of sharedParents) {
          const pathLen = sharedInfo.pathLengths.get(pid + '->' + nodeId) || 1;
          if (pathLen >= maxPathLen) {
            maxPathLen = pathLen;
            lowerParent = pid;
          }
        }

        // Process deferred children of upper parents
        for (const upperParentId of sharedParents) {
          if (upperParentId === lowerParent) continue;

          const deferred = deferredChildren.get(upperParentId);
          if (!deferred || deferred.length === 0) continue;

          const upperPos = gridPos.get(upperParentId);
          if (!upperPos) continue;

          // Place deferred children below the shared node
          let deferredRow = row + 1;
          for (const { childId, nodeCol: parentCol } of deferred) {
            if (placed.has(childId)) continue;

            const childCol = parentCol + 1;
            const childMaxRow = placeBranchAndChildren(childId, childCol, deferredRow, null);

            const childPos = gridPos.get(childId);
            if (childPos) {
              const argName = edgeArgNames.get(upperParentId + '->' + childId) || '';
              drawEdgeToPlaced(matrix, upperPos.row, upperPos.col, childPos.row, childPos.col, argName);
              deferredRow = childPos.row + 1;
            }
          }

          deferredChildren.delete(upperParentId);
        }
      }

      // Place horizontal edge to next node
      if (i < newNodes.length - 1) {
        const nextId = newNodes[i + 1];
        const nextCol = nodeColumns.get(nextId);
        const argName = edgeArgNames.get(nodeId + '->' + nextId) || '';
        // Fill horizontal edges from current node to next node
        for (let c = col; c < nextCol; c++) {
          placeHEdge(matrix, row, c, c === col ? argName : '');
        }
      }
    }

    // FIRST PASS: Detect all splitting in this branch (left to right)
    // This ensures we know which nodes are lower/upper branches before placing children
    // Also calculate target columns for shared node parents
    for (let i = 0; i < newNodes.length; i++) {
      const nodeId = newNodes[i];
      const nodeCol = nodeColumns.get(nodeId);
      const nodeChildren = children.get(nodeId) || [];
      if (nodeChildren.length === 0) continue;

      sharedInfo.sharedNodes.forEach(sharedId => {
        const info = findSplittingInfo(nodeId, nodeChildren, sharedId, sharedInfo.pathsToShared, sharedInfo.pathLengths);
        if (info && !splittingDecisions.has(info.sharedId)) {
          splittingDecisions.set(info.sharedId, info);

          // Calculate target column for parents of this shared node
          // All parents should be at: splittingCol + maxPathLength
          // where maxPathLength is the longest path from any child to the shared node's parents
          const sharedParents = parents.get(sharedId) || [];
          let maxDistToParent = 0;

          // For each child that leads to this shared node, find the path length
          // The path length to the shared node itself is stored, but we need the path to its PARENT
          // Path to parent = path to shared - 1 (since parent is 1 step before shared)
          for (const childId of nodeChildren) {
            const paths = sharedInfo.pathsToShared.get(childId);
            if (paths && paths.has(sharedId)) {
              // Distance from this child to the shared node
              const distToShared = sharedInfo.pathLengths.get(childId + '->' + sharedId) || 0;
              // Distance to the parent = distToShared - 1 (parent is one step before shared)
              // But we want from the splitting node's child, so it's distToShared - 1
              const distToParent = Math.max(0, distToShared - 1);
              maxDistToParent = Math.max(maxDistToParent, distToParent);
            }
          }

          // Target column for all parents of this shared node
          // +1 because children start at nodeCol + 1
          const targetCol = nodeCol + 1 + maxDistToParent;
          sharedParentTargetCol.set(sharedId, {
            targetCol,
            splittingNodeId: nodeId,
            splittingCol: nodeCol
          });

          // IMPORTANT: Also register each parent of this shared node to be placed at targetCol
          // This ensures all parents end up in the same column
          for (const parentId of sharedParents) {
            // Only set target if not already set (first shared node wins)
            if (!parentTargetCol.has(parentId)) {
              parentTargetCol.set(parentId, targetCol);
            }
          }
        }
      });
    }

    // Process children of branch nodes, right to left
    let maxRowUsed = row;

    for (let i = newNodes.length - 1; i >= 0; i--) {
      const nodeId = newNodes[i];
      const nodeCol = startCol + i;
      const nodeRow = row;

      const nodeChildren = children.get(nodeId) || [];
      if (nodeChildren.length === 0) continue;

      // Sort and get children to process (excluding the one that continued the branch)
      let sortedChildren = sortChildrenByPriority(nodeChildren, nodeDataMap, sharedInfo, nodeId, edgeArgNames);

      // Adjust for shared arguments
      if (sharedInfo.sharedNodes.size > 0) {
        sortedChildren = adjustPriorityForShared(sortedChildren, sharedInfo, branchContext, placed, nodeId, parents, gridPos, reservedForLower);
      }

      // Find which child was the branch continuation
      const branchContinuation = newNodes[i + 1];

      // Check for splitting at this node (already computed in first pass)
      const splittingInfos = [];
      sharedInfo.sharedNodes.forEach(sharedId => {
        const info = findSplittingInfo(nodeId, nodeChildren, sharedId, sharedInfo.pathsToShared, sharedInfo.pathLengths);
        if (info) splittingInfos.push(info);
      });

      // Process remaining children in original order from database
      // First child MUST go on same row as parent (horizontal)
      // Shared nodes are handled via reservedForLower and tryPlaceSharedNode
      let childRow = nodeRow + 1;

      // Check if this node is an UPPER parent of a shared node
      // If so, defer non-horizontal children until after shared node is placed
      let isUpperParentOfShared = false;
      let pendingSharedChild = null;
      for (const childId of sortedChildren) {
        if (reservedForLower.has(childId)) {
          // This node has a shared child that is reserved
          // Check if we are NOT the lower parent (i.e., we are upper)
          const sharedParents = parents.get(childId) || [];
          if (sharedParents.length >= 2) {
            let maxPathLen = -1;
            let lowerParent = null;
            for (const pid of sharedParents) {
              const pathLen = sharedInfo.pathLengths.get(pid + '->' + childId) || 1;
              if (pathLen >= maxPathLen) {
                maxPathLen = pathLen;
                lowerParent = pid;
              }
            }
            if (lowerParent !== nodeId) {
              // We are the upper parent
              isUpperParentOfShared = true;
              pendingSharedChild = childId;
              break;
            }
          }
        }
      }

      // Keep original order - no reordering based on splitting
      // This ensures first child from DB goes horizontal
      for (const childId of sortedChildren) {
        if (childId === branchContinuation) continue;
        if (placed.has(childId)) {
          // Child already placed (shared argument) - draw edge to it
          const childPos = gridPos.get(childId);
          if (childPos) {
            drawEdgeToPlaced(matrix, nodeRow, nodeCol, childPos.row, childPos.col,
                           edgeArgNames.get(nodeId + '->' + childId) || '');
          }
          continue;
        }

        // Handle shared nodes specially - they are placed only when ALL parents are placed
        if (reservedForLower.has(childId)) {
          // This is a shared node - register this parent and try to place
          const nodePos = gridPos.get(nodeId);
          if (nodePos) {
            const wasPlaced = tryPlaceSharedNode(childId, nodeId, nodePos);
            if (wasPlaced) {
              // Shared node is now placed - draw edge to it
              const childPos = gridPos.get(childId);
              if (childPos) {
                drawEdgeToPlaced(matrix, nodeRow, nodeCol, childPos.row, childPos.col,
                               edgeArgNames.get(nodeId + '->' + childId) || '');
              }
            }
            // Whether placed or not, we're done with this child for now
            continue;
          }
        }

        // If we are UPPER parent and shared child is not yet placed,
        // defer ALL non-horizontal children until after shared node is placed
        // The horizontal child was already processed via branchContinuation
        if (isUpperParentOfShared && pendingSharedChild && !placed.has(pendingSharedChild)) {
          // Shared child not yet placed - skip all children except the shared one itself
          if (childId !== pendingSharedChild) {
            // Store for later processing
            if (!deferredChildren.has(nodeId)) {
              deferredChildren.set(nodeId, []);
            }
            deferredChildren.get(nodeId).push({ childId, nodeCol, nodeRow });
            continue;
          }
        }

        // Determine branch context for this child
        // First check if current splitting creates a context
        let childBranchContext = null;
        for (const info of splittingInfos) {
          if (childId === info.lowerChild) {
            childBranchContext = { isLowerBranch: true, targetSharedId: info.sharedId };
            break;
          }
          if (info.upperChildren.includes(childId)) {
            childBranchContext = { isUpperBranch: true, targetSharedId: info.sharedId };
            break;
          }
        }

        // If no new splitting context, inherit parent's context
        // This propagates lower/upper branch info down the chain to the shared node
        if (!childBranchContext && branchContext) {
          childBranchContext = branchContext;
        }

        // Determine column for this child
        // Check if this child is a parent of a shared node with a target column
        let childCol = nodeCol + 1;

        // If this child is a direct parent of a shared node, use the target column
        const childChildren = children.get(childId) || [];
        for (const grandchildId of childChildren) {
          const targetInfo = sharedParentTargetCol.get(grandchildId);
          if (targetInfo && targetInfo.targetCol > childCol) {
            // This child is a parent of a shared node - align to target column
            childCol = targetInfo.targetCol;
            break;
          }
        }

        // Find row for this child's branch
        let childMinRow = childRow;
        const childMaxRow = placeBranchAndChildren(childId, childCol, childMinRow, childBranchContext);

        // Get actual position of placed child and draw edge using smart routing
        const childPos = gridPos.get(childId);
        if (childPos) {
          const argName = edgeArgNames.get(nodeId + '->' + childId) || '';
          drawEdgeToPlaced(matrix, nodeRow, nodeCol, childPos.row, childPos.col, argName);
          childRow = Math.max(childRow, childPos.row + 1);
          maxRowUsed = Math.max(maxRowUsed, childMaxRow);
        }
      }
    }

    return maxRowUsed;
  }

  /**
   * Draw edge to an already-placed node (shared argument)
   * Uses smart routing to avoid crossing any nodes
   */
  function drawEdgeToPlaced(matrix, parentRow, parentCol, childRow, childCol, argName) {
    if (childRow === parentRow && childCol > parentCol) {
      // Horizontal edge on same row
      for (let c = parentCol; c < childCol; c++) {
        if (!hasHEdgeAt(matrix, parentRow, c)) {
          placeHEdge(matrix, parentRow, c, c === parentCol ? argName : '');
        }
      }
    } else if (childRow > parentRow) {
      // Child is below parent - need to route around any nodes in the path
      // Strategy: find a clear column to use for vertical path

      // Check if parentCol is clear for vertical path
      let parentColClear = true;
      for (let r = parentRow + 1; r < childRow; r++) {
        if (getNodeAt(matrix, r, parentCol) !== null) {
          parentColClear = false;
          break;
        }
      }

      // Check if childCol is clear for vertical path
      let childColClear = true;
      for (let r = parentRow + 1; r < childRow; r++) {
        if (getNodeAt(matrix, r, childCol) !== null) {
          childColClear = false;
          break;
        }
      }

      // PREFER routing through childCol to avoid blocking parentCol for siblings
      // This is important for shared arguments where multiple parents need to be in same column
      if (childColClear) {
        // Route: right at parentRow, then down through childCol
        for (let c = parentCol; c < childCol; c++) {
          if (!hasHEdgeAt(matrix, parentRow, c)) {
            placeHEdge(matrix, parentRow, c, c === parentCol ? argName : '');
          }
        }
        for (let r = parentRow + 1; r < childRow; r++) {
          placeVEdge(matrix, r, childCol);
        }
      } else if (parentColClear) {
        // Fallback: route down through parentCol, then right at childRow
        for (let r = parentRow + 1; r <= childRow; r++) {
          placeVEdge(matrix, r, parentCol);
        }
        for (let c = parentCol; c < childCol; c++) {
          if (!hasHEdgeAt(matrix, childRow, c)) {
            placeHEdge(matrix, childRow, c, c === parentCol ? argName : '');
          }
        }
      } else {
        // Both parentCol and childCol are blocked
        // Try to find a clear column anywhere
        let clearCol = -1;

        // Helper to check if a column is clear for vertical path
        function isColClear(col) {
          for (let r = parentRow + 1; r < childRow; r++) {
            if (getNodeAt(matrix, r, col) !== null) {
              return false;
            }
          }
          return true;
        }

        // Try columns between parent and child first
        for (let tryCol = parentCol + 1; tryCol < childCol; tryCol++) {
          if (isColClear(tryCol)) {
            clearCol = tryCol;
            break;
          }
        }

        // If no clear column between, try columns beyond childCol (up to +20)
        if (clearCol < 0) {
          for (let tryCol = childCol + 1; tryCol <= childCol + 20; tryCol++) {
            if (isColClear(tryCol)) {
              clearCol = tryCol;
              break;
            }
          }
        }

        // If still no clear column, try columns BEFORE parentCol (down to 0)
        if (clearCol < 0) {
          for (let tryCol = parentCol - 1; tryCol >= 0; tryCol--) {
            if (isColClear(tryCol)) {
              clearCol = tryCol;
              break;
            }
          }
        }

        if (clearCol >= 0) {
          if (clearCol < parentCol) {
            // Clear col is BEFORE parentCol - route left, down, then right to child
            // Horizontal left at parent row
            for (let c = clearCol; c < parentCol; c++) {
              if (!hasHEdgeAt(matrix, parentRow, c)) {
                placeHEdge(matrix, parentRow, c, c === clearCol ? argName : '');
              }
            }
            // Vertical down
            for (let r = parentRow + 1; r <= childRow; r++) {
              placeVEdge(matrix, r, clearCol);
            }
            // Horizontal right at child row
            for (let c = clearCol; c < childCol; c++) {
              if (!hasHEdgeAt(matrix, childRow, c)) {
                placeHEdge(matrix, childRow, c, '');
              }
            }
          } else if (clearCol < childCol) {
            // Route: right to clearCol, down, then right to childCol
            for (let c = parentCol; c < clearCol; c++) {
              if (!hasHEdgeAt(matrix, parentRow, c)) {
                placeHEdge(matrix, parentRow, c, c === parentCol ? argName : '');
              }
            }
            for (let r = parentRow + 1; r <= childRow; r++) {
              placeVEdge(matrix, r, clearCol);
            }
            for (let c = clearCol; c < childCol; c++) {
              if (!hasHEdgeAt(matrix, childRow, c)) {
                placeHEdge(matrix, childRow, c, '');
              }
            }
          } else {
            // Clear col is beyond childCol - route right past child, down, then left
            for (let c = parentCol; c < clearCol; c++) {
              if (!hasHEdgeAt(matrix, parentRow, c)) {
                placeHEdge(matrix, parentRow, c, c === parentCol ? argName : '');
              }
            }
            for (let r = parentRow + 1; r <= childRow; r++) {
              placeVEdge(matrix, r, clearCol);
            }
            // Horizontal part at child row from clearCol back to childCol
            for (let c = childCol; c < clearCol; c++) {
              if (!hasHEdgeAt(matrix, childRow, c)) {
                placeHEdge(matrix, childRow, c, '');
              }
            }
          }
        } else {
          // No clear column found at all - use parentCol anyway (will cause crossing)
          console.warn('No clear path found for edge from (' + parentRow + ',' + parentCol +
                       ') to (' + childRow + ',' + childCol + ')');
          for (let r = parentRow + 1; r <= childRow; r++) {
            placeVEdge(matrix, r, parentCol);
          }
          for (let c = parentCol; c < childCol; c++) {
            if (!hasHEdgeAt(matrix, childRow, c)) {
              placeHEdge(matrix, childRow, c, c === parentCol ? argName : '');
            }
          }
        }
      }
    } else if (childRow < parentRow) {
      // Vertical edge going up - should not happen with proper ordering
      console.warn('Upward edge detected from row ' + parentRow + ' to row ' + childRow +
                   ' at col ' + childCol + ' - this may cause visual issues');
      for (let r = childRow; r < parentRow; r++) {
        placeVEdge(matrix, r, childCol);
      }
    }
  }

  // Start placement from root - place entire branch starting from root
  if (rootId) {
    placeBranchAndChildren(rootId, 0, 0, null);
  }

  return { matrix, gridPos };
}

// ============================================================================
// VALIDATION
// ============================================================================

function detectCrossings(matrix) {
  const crossings = [];
  const { hEdge, vEdge, nodeGrid } = matrix;

  for (let r = 0; r < hEdge.length; r++) {
    for (let c = 0; c < (hEdge[r] || []).length; c++) {
      if (hEdge[r][c] !== null) {
        // Check if horizontal edge crosses a vertical edge
        const checkCol = c + 1;
        // Vertical edge passes through if vEdge[r-1][c+1] and vEdge[r][c+1] are both true
        // and there's no node at [r][c+1]
        if (r > 0 &&
            hasVEdgeAt(matrix, r - 1, checkCol) &&
            hasVEdgeAt(matrix, r, checkCol) &&
            getNodeAt(matrix, r, checkCol) === null) {
          crossings.push({
            type: 'hv_cross',
            row: r,
            col: c,
            vEdgeCol: checkCol
          });
        }
      }
    }
  }

  return crossings;
}

function validateMatrix(matrix, gridPos) {
  const issues = [];
  const positions = new Map();

  // Check for node collisions
  gridPos.forEach((pos, nodeId) => {
    const key = pos.row + ',' + pos.col;
    if (positions.has(key)) {
      issues.push({
        type: 'collision',
        message: `Nodes ${positions.get(key)} and ${nodeId} both at (${pos.row}, ${pos.col})`
      });
    }
    positions.set(key, nodeId);
  });

  // Check for edge crossings
  const crossings = detectCrossings(matrix);
  crossings.forEach(c => {
    issues.push({
      type: 'crossing',
      message: `Edge crossing at row ${c.row}, cols ${c.col}-${c.vEdgeCol}`
    });
  });

  return { valid: issues.length === 0, issues };
}

// ============================================================================
// ASCII DEBUG OUTPUT
// ============================================================================

function formatMatrixASCII(matrix, gridPos) {
  const { nodeGrid, hEdge, vEdge } = matrix;
  const lines = [];

  const maxRow = Math.max(nodeGrid.length, 1);
  const maxCol = Math.max(...nodeGrid.map(r => r ? r.length : 0), 1);

  if (maxRow === 0 || maxCol === 0) {
    return '(empty matrix)';
  }

  // Build name map
  const nodeNames = new Map();
  gridPos.forEach((pos, nodeId) => {
    const name = nodeId.length > 8 ? nodeId.substring(0, 7) + '~' : nodeId;
    nodeNames.set(pos.row + ',' + pos.col, name.padEnd(8));
  });

  for (let r = 0; r < maxRow; r++) {
    let nodeLine = '';
    for (let c = 0; c < maxCol; c++) {
      const key = r + ',' + c;
      const node = nodeNames.get(key);
      if (node) {
        nodeLine += '[' + node + ']';
      } else if (hasHEdgeAt(matrix, r, c)) {
        nodeLine += '[---h---]';
      } else if (hasVEdgeAt(matrix, r, c)) {
        nodeLine += '[   |   ]';
      } else {
        nodeLine += '[       ]';
      }

      if (c < maxCol - 1) {
        const hasH = hEdge[r] && hEdge[r][c] !== null;
        nodeLine += hasH ? '--' : '  ';
      }
    }
    lines.push(nodeLine);

    if (r < maxRow - 1) {
      let vLine = '';
      for (let c = 0; c < maxCol; c++) {
        const hasV = vEdge[r] && vEdge[r][c];
        vLine += '    ' + (hasV ? '|' : ' ') + '    ';
        if (c < maxCol - 1) {
          vLine += '  ';
        }
      }
      lines.push(vLine);
    }
  }

  return lines.join('\n');
}

// ============================================================================
// HIGH-LEVEL API
// ============================================================================

/**
 * Build graph layout from elements
 */
function layoutGraph(elements) {
  const { nodes, edges } = elements;

  if (nodes.length === 0) {
    return {
      matrix: createMatrixState(),
      gridPos: new Map(),
      validation: { valid: true, issues: [] },
      ascii: '(empty graph)'
    };
  }

  const { children, parents, edgeArgNames } = buildAdjacency(edges);
  const rootId = findRootNode(nodes, edges);

  if (!rootId) {
    return {
      matrix: createMatrixState(),
      gridPos: new Map(),
      validation: { valid: false, issues: [{ type: 'no_root', message: 'No root node found' }] },
      ascii: '(no root node)'
    };
  }

  // Build node data map for type detection
  const nodeDataMap = new Map();
  nodes.forEach(n => {
    nodeDataMap.set(n.data.id, n.data);
  });

  const { matrix, gridPos } = buildMatrix(rootId, children, parents, edgeArgNames, nodeDataMap);
  const validation = validateMatrix(matrix, gridPos);
  const ascii = formatMatrixASCII(matrix, gridPos);

  if (!validation.valid) {
    console.warn('Layout validation issues:', validation.issues);
  }

  return { matrix, gridPos, validation, ascii };
}

// ============================================================================
// NODE SIZE CALCULATION
// ============================================================================

function calculateNodeSize(nodeData) {
  const label = nodeData.data.label || '';
  const type = nodeData.data.type;
  const isPlaceholder = nodeData.data.isPlaceholder;

  if (type === 'arg') {
    const maxLen = 30;
    const effectiveLen = Math.min(label.length, maxLen);
    return {
      width: Math.max(40, effectiveLen * 6 + 16),
      height: 28 + DRAG_HANDLE_HEIGHT
    };
  } else if (isPlaceholder) {
    return {
      width: Math.max(40, label.length * 6 + 16),
      height: 28 + DRAG_HANDLE_HEIGHT
    };
  } else {
    const lines = label.split('\n');
    const maxLineLen = 30;
    const maxLen = Math.max(...lines.map(l => {
      const cleanLen = l.replace(/[^\x20-\x7E]/g, '').length;
      return Math.min(cleanLen, maxLineLen);
    }));
    return {
      width: Math.max(80, maxLen * 7 + 24),
      height: Math.max(30, lines.length * 16 + 16) + DRAG_HANDLE_HEIGHT
    };
  }
}

/**
 * Build grid layout with pixel positions
 */
function buildGridLayout(elements) {
  const { nodes, edges } = elements;
  if (nodes.length === 0) return new Map();

  const result = layoutGraph(elements);
  const { matrix, gridPos, validation } = result;

  if (!validation.valid) {
    console.error('Layout validation issues:', validation.issues);
  }

  // Calculate sizes for all nodes
  const sizes = new Map();
  nodes.forEach(n => {
    sizes.set(n.data.id, calculateNodeSize(n));
  });

  // Calculate column widths
  const colWidths = new Map();
  gridPos.forEach((pos, nodeId) => {
    const size = sizes.get(nodeId);
    if (size) {
      const currentMax = colWidths.get(pos.col) || 0;
      colWidths.set(pos.col, Math.max(currentMax, size.width));
    }
  });

  // Calculate row heights
  const rowHeights = new Map();
  gridPos.forEach((pos, nodeId) => {
    const size = sizes.get(nodeId);
    if (size) {
      const currentMax = rowHeights.get(pos.row) || 0;
      rowHeights.set(pos.row, Math.max(currentMax, size.height));
    }
  });

  // Calculate extra gap for edge labels
  const { hEdge } = matrix;
  const colExtraGap = new Map();
  const maxColKey = Math.max(...Array.from(colWidths.keys()), 0);
  for (let c = 1; c <= maxColKey; c++) {
    let maxLabelLen = 0;
    for (let r = 0; r < hEdge.length; r++) {
      const argName = hEdge[r] && hEdge[r][c - 1];
      if (argName && typeof argName === 'string') {
        maxLabelLen = Math.max(maxLabelLen, argName.length);
      }
    }
    colExtraGap.set(c, maxLabelLen > 0 ? maxLabelLen * 7 : 0);
  }

  // Calculate X positions
  const colLeftX = new Map();
  let currentX = 0;
  for (let c = 0; c <= maxColKey; c++) {
    const extraGap = colExtraGap.get(c) || 0;
    currentX += extraGap;
    colLeftX.set(c, currentX);
    const width = colWidths.get(c) || 80;
    currentX += width + GRID_GAP_X;
  }

  // Calculate Y positions
  const rowCenterY = new Map();
  let currentY = 0;
  const maxRowKey = Math.max(...Array.from(rowHeights.keys()), 0);
  for (let r = 0; r <= maxRowKey; r++) {
    const height = rowHeights.get(r) || 30;
    rowCenterY.set(r, currentY + height / 2);
    currentY += height + GRID_GAP_Y;
  }

  // Build final layout
  const layout = new Map();
  gridPos.forEach((pos, nodeId) => {
    const size = sizes.get(nodeId);
    if (size) {
      const leftX = colLeftX.get(pos.col);
      layout.set(nodeId, {
        x: leftX + size.width / 2,
        y: rowCenterY.get(pos.row),
        width: size.width,
        height: size.height,
        row: pos.row,
        col: pos.col
      });
    }
  });

  return layout;
}

// ============================================================================
// BACKEND LAYOUT API
// ============================================================================

/**
 * Fetch layout from backend API
 * @param {Object} elements - { nodes: [...], edges: [...] }
 * @returns {Promise<Map>} Layout map: nodeId -> { x, y, width, height, row, col }
 */
async function fetchBackendLayout(elements) {
  try {
    const response = await fetch('/api/graph/layout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ elements })
    });

    if (!response.ok) {
      console.warn('Backend layout API failed, falling back to local layout');
      return null;
    }

    const data = await response.json();

    if (!data['grid-pos'] && !data.grid_pos && !data.gridPos) {
      console.warn('Invalid backend layout response');
      return null;
    }

    // Convert backend response to frontend layout format
    // Handle different naming conventions: 'grid-pos' (Clojure), 'grid_pos', 'gridPos'
    const gridPos = data['grid-pos'] || data.grid_pos || data.gridPos || {};
    const layout = new Map();

    // Calculate sizes for all nodes
    const sizes = new Map();
    elements.nodes.forEach(n => {
      sizes.set(n.data.id, calculateNodeSize(n));
    });

    // Calculate column widths
    const colWidths = new Map();
    Object.entries(gridPos).forEach(([nodeId, pos]) => {
      const size = sizes.get(nodeId);
      if (size) {
        const currentMax = colWidths.get(pos.col) || 0;
        colWidths.set(pos.col, Math.max(currentMax, size.width));
      }
    });

    // Calculate row heights
    const rowHeights = new Map();
    Object.entries(gridPos).forEach(([nodeId, pos]) => {
      const size = sizes.get(nodeId);
      if (size) {
        const currentMax = rowHeights.get(pos.row) || 0;
        rowHeights.set(pos.row, Math.max(currentMax, size.height));
      }
    });

    // Calculate X positions
    const colLeftX = new Map();
    const maxColKey = Math.max(...Array.from(colWidths.keys()), 0);
    let currentX = 0;
    for (let c = 0; c <= maxColKey; c++) {
      colLeftX.set(c, currentX);
      const width = colWidths.get(c) || 80;
      currentX += width + GRID_GAP_X;
    }

    // Calculate Y positions
    const rowCenterY = new Map();
    let currentY = 0;
    const maxRowKey = Math.max(...Array.from(rowHeights.keys()), 0);
    for (let r = 0; r <= maxRowKey; r++) {
      const height = rowHeights.get(r) || 30;
      rowCenterY.set(r, currentY + height / 2);
      currentY += height + GRID_GAP_Y;
    }

    // Build final layout
    Object.entries(gridPos).forEach(([nodeId, pos]) => {
      const size = sizes.get(nodeId);
      if (size) {
        const leftX = colLeftX.get(pos.col);
        layout.set(nodeId, {
          x: leftX + size.width / 2,
          y: rowCenterY.get(pos.row),
          width: size.width,
          height: size.height,
          row: pos.row,
          col: pos.col
        });
      }
    });

    cachedBackendLayout = layout;
    return layout;
  } catch (error) {
    console.warn('Backend layout fetch error:', error);
    return null;
  }
}

/**
 * Build grid layout - uses backend API if enabled, otherwise local calculation
 * Synchronous wrapper that returns cached layout or computes locally
 */
function buildGridLayoutSync(elements) {
  // Use cached backend layout if available and matches current elements
  if (USE_BACKEND_LAYOUT && cachedBackendLayout) {
    // Verify cache validity - all element ids must be in cache
    const allIds = new Set(elements.nodes.map(n => n.data.id));
    let cacheValid = true;
    allIds.forEach(id => {
      if (!cachedBackendLayout.has(id)) cacheValid = false;
    });
    if (cacheValid) {
      return cachedBackendLayout;
    }
  }

  // Fallback to local layout calculation
  return buildGridLayout(elements);
}

/**
 * Refresh layout from backend (async)
 * Call this when graph elements change
 */
async function refreshBackendLayout(elements) {
  if (!USE_BACKEND_LAYOUT) return;

  const layout = await fetchBackendLayout(elements);
  if (layout) {
    cachedBackendLayout = layout;
  }
}

// ============================================================================
// EXPORTS
// ============================================================================

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    buildAdjacency,
    findRootNode,
    analyzeSharedArguments,
    createMatrixState,
    buildMatrix,
    layoutGraph,
    validateMatrix,
    formatMatrixASCII,
    calculateNodeSize,
    buildGridLayout,
    fetchBackendLayout,
    buildGridLayoutSync,
    refreshBackendLayout
  };
}
