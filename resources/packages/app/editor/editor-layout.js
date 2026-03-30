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
 * Sort children by priority: fn > fixed > free, then by name for stability
 */
function sortChildrenByPriority(childIds, nodeDataMap, sharedInfo, currentNodeId) {
  const typeOrder = { 'fn': 0, 'fixed': 1, 'free': 2 };

  return [...childIds].sort((a, b) => {
    // First by type
    const typeA = getNodeType(a, nodeDataMap);
    const typeB = getNodeType(b, nodeDataMap);
    const typeDiff = typeOrder[typeA] - typeOrder[typeB];
    if (typeDiff !== 0) return typeDiff;

    // Then by name for stability
    const nameA = nodeDataMap.get(a)?.label || a;
    const nameB = nodeDataMap.get(b)?.label || b;
    return nameA.localeCompare(nameB);
  });
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
 */
function findSplittingInfo(nodeId, childIds, sharedId, pathsToShared, pathLengths) {
  const leadingChildren = childIds.filter(childId => {
    const paths = pathsToShared.get(childId);
    return paths && paths.has(sharedId);
  });

  if (leadingChildren.length < 2) return null;

  // Sort by path length - shorter path becomes "lower" branch
  leadingChildren.sort((a, b) => {
    const distA = pathLengths.get(a + '->' + sharedId) || Infinity;
    const distB = pathLengths.get(b + '->' + sharedId) || Infinity;
    return distA - distB;
  });

  return {
    sharedId,
    lowerChild: leadingChildren[0],  // shortest path - goes horizontal
    upperChildren: leadingChildren.slice(1)  // longer paths - hang below
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
  matrix.vEdge[row][col] = true;
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
  reservedForLower  // Set of nodeIds reserved for lower branch
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

    // Sort children by priority
    let sortedChildren = sortChildrenByPriority(nodeChildren, nodeDataMap, sharedInfo, current);

    // Modify priority for shared argument handling
    if (branchContext && sharedInfo.sharedNodes.size > 0) {
      sortedChildren = adjustPriorityForShared(
        sortedChildren,
        sharedInfo,
        branchContext,
        placed
      );
    }

    // Take first unplaced child that isn't reserved (for upper branch)
    let nextChild = null;
    for (const c of sortedChildren) {
      if (placed.has(c)) continue;
      // Upper branch should not enter reserved shared nodes
      if (branchContext && branchContext.isUpperBranch &&
          reservedForLower && reservedForLower.has(c)) {
        continue;
      }
      nextChild = c;
      break;
    }
    if (!nextChild) break;

    // If next child is fn, continue branch; otherwise end here
    const nextType = getNodeType(nextChild, nodeDataMap);
    if (nextType !== 'fn') {
      branch.push(nextChild);
      break;
    }

    current = nextChild;
  }

  return branch;
}

/**
 * Adjust child priority based on shared argument handling
 */
function adjustPriorityForShared(sortedChildren, sharedInfo, branchContext, placed) {
  const { pathsToShared } = sharedInfo;
  const { isLowerBranch, targetSharedId, isUpperBranch } = branchContext;

  if (!targetSharedId) return sortedChildren;

  // Separate children that lead to target shared from others
  const leadsToShared = [];
  const others = [];

  sortedChildren.forEach(childId => {
    if (placed.has(childId)) {
      others.push(childId);
      return;
    }
    const paths = pathsToShared.get(childId);
    if (paths && paths.has(targetSharedId)) {
      leadsToShared.push(childId);
    } else {
      others.push(childId);
    }
  });

  if (isLowerBranch) {
    // Lower branch: path to shared gets HIGHEST priority (first = horizontal)
    return [...leadsToShared, ...others];
  } else if (isUpperBranch) {
    // Upper branch: path to shared gets LOWEST priority (last = hangs below)
    return [...others, ...leadsToShared];
  }

  return sortedChildren;
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

  // Pre-analyze: find all shared nodes and reserve them for lower branches
  // This ensures upper branches don't place shared nodes before lower branches get to them
  sharedInfo.sharedNodes.forEach(sharedId => {
    reservedForLower.add(sharedId);
  });

  /**
   * Place a horizontal branch and recursively place its sub-branches
   */
  function placeBranchAndChildren(startNodeId, startCol, minRow, branchContext) {
    // Build the horizontal branch
    const branch = buildHorizontalBranch(
      startNodeId, children, placed, nodeDataMap, sharedInfo, branchContext, reservedForLower
    );

    if (branch.length === 0) return minRow;

    // Filter out already placed nodes
    const newNodes = branch.filter(n => !placed.has(n));
    if (newNodes.length === 0) return minRow;

    // Find row for this branch
    const row = findRowForBranch(matrix, startCol, newNodes.length, minRow);

    // Place branch nodes
    for (let i = 0; i < newNodes.length; i++) {
      const nodeId = newNodes[i];
      const col = startCol + i;

      placeNode(matrix, nodeId, row, col);
      gridPos.set(nodeId, { row, col });
      placed.add(nodeId);

      // Place horizontal edge to next node
      if (i < newNodes.length - 1) {
        const nextId = newNodes[i + 1];
        const argName = edgeArgNames.get(nodeId + '->' + nextId) || '';
        placeHEdge(matrix, row, col, argName);
      }
    }

    // FIRST PASS: Detect all splitting in this branch (left to right)
    // This ensures we know which nodes are lower/upper branches before placing children
    for (let i = 0; i < newNodes.length; i++) {
      const nodeId = newNodes[i];
      const nodeChildren = children.get(nodeId) || [];
      if (nodeChildren.length === 0) continue;

      sharedInfo.sharedNodes.forEach(sharedId => {
        const info = findSplittingInfo(nodeId, nodeChildren, sharedId, sharedInfo.pathsToShared, sharedInfo.pathLengths);
        if (info && !splittingDecisions.has(info.sharedId)) {
          splittingDecisions.set(info.sharedId, info);
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
      let sortedChildren = sortChildrenByPriority(nodeChildren, nodeDataMap, sharedInfo, nodeId);

      // Adjust for shared arguments
      if (branchContext && sharedInfo.sharedNodes.size > 0) {
        sortedChildren = adjustPriorityForShared(sortedChildren, sharedInfo, branchContext, placed);
      }

      // Find which child was the branch continuation
      const branchContinuation = newNodes[i + 1];

      // Check for splitting at this node (already computed in first pass)
      const splittingInfos = [];
      sharedInfo.sharedNodes.forEach(sharedId => {
        const info = findSplittingInfo(nodeId, nodeChildren, sharedId, sharedInfo.pathsToShared, sharedInfo.pathLengths);
        if (info) splittingInfos.push(info);
      });

      // Process remaining children
      // IMPORTANT: Process upper branches FIRST (they go above), then lower branch LAST
      // This ensures upper branches are placed higher on the screen, so edges go DOWN to shared nodes
      let childRow = nodeRow + 1;

      // Reorder children: splitting nodes (upper then lower) go together, then others
      // This ensures splitting nodes are placed adjacent to each other
      let reorderedChildren = [...sortedChildren];
      if (splittingInfos.length > 0) {
        const lowerChildren = new Set();
        const upperChildren = new Set();
        splittingInfos.forEach(info => {
          lowerChildren.add(info.lowerChild);
          info.upperChildren.forEach(c => upperChildren.add(c));
        });

        // Sort: upper children first, then lower children (adjacent!), then others
        // This keeps splitting nodes together so vertical edges don't cross other branches
        reorderedChildren.sort((a, b) => {
          const aIsLower = lowerChildren.has(a);
          const bIsLower = lowerChildren.has(b);
          const aIsUpper = upperChildren.has(a);
          const bIsUpper = upperChildren.has(b);
          const aIsSplitting = aIsLower || aIsUpper;
          const bIsSplitting = bIsLower || bIsUpper;

          // Splitting nodes go first (together)
          if (aIsSplitting && !bIsSplitting) return -1;
          if (!aIsSplitting && bIsSplitting) return 1;

          // Among splitting nodes: upper first, then lower
          if (aIsSplitting && bIsSplitting) {
            if (aIsUpper && bIsLower) return -1;
            if (aIsLower && bIsUpper) return 1;
          }

          return 0;  // maintain original order otherwise
        });
      }

      for (const childId of reorderedChildren) {
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

        // Skip reserved nodes on upper branch - they will be placed by lower branch
        if (branchContext && branchContext.isUpperBranch && reservedForLower.has(childId)) {
          // Don't place this child now, but remember we need to draw edge later
          continue;
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

        // Special case: if child is a reserved shared node and we have no context yet,
        // check if current node is on the lower path to it (using splittingDecisions)
        if (!childBranchContext && reservedForLower.has(childId)) {
          const decision = splittingDecisions.get(childId);
          if (decision) {
            // Check if current node (nodeId) is the lowerChild or on its path
            const lowerChildPaths = sharedInfo.pathsToShared.get(decision.lowerChild);
            const isOnLowerPath = (nodeId === decision.lowerChild) ||
                                  (lowerChildPaths && lowerChildPaths.has(childId));
            if (isOnLowerPath) {
              // We're on the path from lowerChild to shared - this is lower branch
              childBranchContext = { isLowerBranch: true, targetSharedId: childId };
            }
          }
        }

        // Place vertical edge segments
        const childCol = nodeCol + 1;

        // Find row for this child's branch
        // If this is the lower branch leading to a shared node, it continues horizontally
        let childMinRow = childRow;
        if (childBranchContext && childBranchContext.isLowerBranch &&
            childBranchContext.targetSharedId === childId) {
          // This child IS the shared node that lower branch should place on same row
          childMinRow = nodeRow;
        }
        const childMaxRow = placeBranchAndChildren(childId, childCol, childMinRow, childBranchContext);

        // Get actual position of placed child
        const childPos = gridPos.get(childId);
        if (childPos) {
          if (childPos.row === nodeRow) {
            // Child on same row - place horizontal edge
            const argName = edgeArgNames.get(nodeId + '->' + childId) || '';
            for (let c = nodeCol; c < childPos.col; c++) {
              if (!hasHEdgeAt(matrix, nodeRow, c)) {
                placeHEdge(matrix, nodeRow, c, c === nodeCol ? argName : '');
              }
            }
          } else {
            // Place vertical edge from parent row to child row
            for (let r = nodeRow; r < childPos.row; r++) {
              placeVEdge(matrix, r, childCol);
            }
          }
          childRow = Math.max(childRow, childPos.row + 1);
          maxRowUsed = Math.max(maxRowUsed, childMaxRow);
        }
      }
    }

    return maxRowUsed;
  }

  /**
   * Draw edge to an already-placed node (shared argument)
   */
  function drawEdgeToPlaced(matrix, parentRow, parentCol, childRow, childCol, argName) {
    if (childRow === parentRow && childCol > parentCol) {
      // Horizontal edge
      for (let c = parentCol; c < childCol; c++) {
        if (!hasHEdgeAt(matrix, parentRow, c)) {
          placeHEdge(matrix, parentRow, c, c === parentCol ? argName : '');
        }
      }
    } else if (childRow > parentRow) {
      // Vertical edge going down
      for (let r = parentRow; r < childRow; r++) {
        placeVEdge(matrix, r, childCol);
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
    buildGridLayout
  };
}
