// Layout Core - Grid-based layout with no edge crossings
// Algorithm:
// 1. Build horizontal branch (follow first children, prefer shared nodes)
// 2. Place branch below lowest existing nodes in its columns
// 3. Process branch right-to-left, placing remaining children below
// 4. For shared nodes: prioritize them to be placed first/higher

/**
 * Build adjacency map from edges
 */
function buildAdjacency(edges) {
  const children = new Map();
  const edgeArgNames = new Map();
  const edgeSet = new Set();

  edges.forEach(e => {
    const src = e.data.source;
    const tgt = e.data.target;
    const edgeKey = src + '->' + tgt;

    if (edgeSet.has(edgeKey)) return;
    edgeSet.add(edgeKey);

    if (!children.has(src)) children.set(src, []);
    children.get(src).push(tgt);

    if (e.data.argName) {
      edgeArgNames.set(edgeKey, e.data.argName);
    }
  });

  return { children, edgeArgNames };
}

/**
 * Find root node
 */
function findRootNode(nodes, edges) {
  const hasIncoming = new Set();
  edges.forEach(e => hasIncoming.add(e.data.target));
  const root = nodes.find(n => !hasIncoming.has(n.data.id));
  return root ? root.data.id : null;
}

/**
 * Create empty matrix state
 */
function createMatrixState() {
  return {
    nodeGrid: [],
    hEdge: [],
    vEdge: []
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

function placeNode(matrix, nodeId, row, col) {
  ensureMatrixSize(matrix, row, col);
  matrix.nodeGrid[row][col] = nodeId;
}

function placeHEdge(matrix, row, col, argName) {
  ensureMatrixSize(matrix, row, col + 1);
  matrix.hEdge[row][col] = argName || '';
}

function placeVEdge(matrix, row, col) {
  ensureMatrixSize(matrix, row + 1, col);
  matrix.vEdge[row][col] = true;
}

/**
 * Find lowest occupied row in columns [startCol, startCol + length)
 * Considers both nodes AND vertical edges
 */
function findLowestInColumns(matrix, startCol, length) {
  let lowest = -1;
  for (let c = startCol; c < startCol + length; c++) {
    for (let r = 0; r < matrix.nodeGrid.length; r++) {
      if (getNodeAt(matrix, r, c) !== null) {
        lowest = Math.max(lowest, r);
      }
    }
    for (let r = 0; r < matrix.vEdge.length; r++) {
      if (matrix.vEdge[r] && matrix.vEdge[r][c]) {
        lowest = Math.max(lowest, r + 1);
      }
    }
  }
  return lowest;
}

/**
 * Check if drawing a horizontal edge at given row from startCol to endCol would cross vertical edges
 */
function wouldCrossVerticalEdge(matrix, row, startCol, endCol) {
  for (let c = startCol + 1; c < endCol; c++) {
    // Check if there's a vertical edge passing through this row at column c
    // A vertical edge "passes through" row r if vEdge[r-1][c] && vEdge[r][c] are both true
    // Or if vEdge[r-1][c] is true and there's a node at (r, c)
    // Or if there's a node above at (r-1, c) with vEdge[r-1][c]
    if (row > 0 && matrix.vEdge[row - 1] && matrix.vEdge[row - 1][c]) {
      // Vertical edge comes from above - would cross
      return c;
    }
    if (matrix.vEdge[row] && matrix.vEdge[row][c]) {
      // Vertical edge goes down from this row - might cross depending on node
      // Actually if there's no node at (row, c), then drawing hEdge here would cross
      if (getNodeAt(matrix, row, c) === null) {
        return c;
      }
    }
  }
  return -1;
}

/**
 * Check if node or any of its descendants has an already-placed node
 */
function hasPlacedDescendant(nodeId, children, placed, visited) {
  if (visited.has(nodeId)) return false;
  visited.add(nodeId);

  if (placed.has(nodeId)) return true;

  const nodeChildren = children.get(nodeId) || [];
  for (const childId of nodeChildren) {
    if (hasPlacedDescendant(childId, children, placed, visited)) {
      return true;
    }
  }
  return false;
}

/**
 * Collect horizontal branch - follow first children
 * If a child is already placed (shared), stop there
 * Prioritize children that have already-placed descendants (to minimize vertical edges)
 */
function collectBranch(nodeId, children, placed, referencedBy) {
  const branch = [];
  let current = nodeId;

  while (current && !placed.has(current)) {
    branch.push(current);
    const nodeChildren = children.get(current) || [];
    if (nodeChildren.length === 0) break;

    // Sort children:
    // 1. Already-placed nodes first (we'll connect to them)
    // 2. Nodes with already-placed descendants (to keep them in horizontal branch)
    // 3. Shared nodes next
    // 4. Regular nodes last
    const sortedChildren = [...nodeChildren].sort((a, b) => {
      const aPlaced = placed.has(a);
      const bPlaced = placed.has(b);

      // Already placed nodes first (we'll connect to them)
      if (aPlaced && !bPlaced) return -1;
      if (bPlaced && !aPlaced) return 1;

      // Check if either has placed descendants
      const aHasPlacedDesc = hasPlacedDescendant(a, children, placed, new Set());
      const bHasPlacedDesc = hasPlacedDescendant(b, children, placed, new Set());

      // Nodes with placed descendants should go first (horizontal branch)
      if (aHasPlacedDesc && !bHasPlacedDesc) return -1;
      if (bHasPlacedDesc && !aHasPlacedDesc) return 1;

      // Shared nodes next
      const aShared = (referencedBy.get(a) || []).length > 1;
      const bShared = (referencedBy.get(b) || []).length > 1;
      if (aShared && !bShared) return -1;
      if (bShared && !aShared) return 1;

      return 0;
    });

    current = sortedChildren[0];
  }

  return branch;
}

/**
 * Shift a node and all its descendants to a new column
 * Also shifts any unrelated nodes that would collide
 */
function shiftNodeRight(matrix, gridPos, nodeId, newCol, children, shifted) {
  if (shifted.has(nodeId)) return;
  shifted.add(nodeId);

  const pos = gridPos.get(nodeId);
  if (!pos) return;

  const oldCol = pos.col;
  const colDelta = newCol - oldCol;
  if (colDelta <= 0) return;

  // Check if new position is occupied by a different node
  const occupant = getNodeAt(matrix, pos.row, newCol);
  if (occupant && occupant !== nodeId) {
    // Shift the occupant first (cascade)
    shiftNodeRight(matrix, gridPos, occupant, newCol + 1, children, shifted);
  }

  // Clear old position
  if (matrix.nodeGrid[pos.row] && matrix.nodeGrid[pos.row][oldCol] === nodeId) {
    matrix.nodeGrid[pos.row][oldCol] = null;
  }

  // Place at new position
  pos.col = newCol;
  placeNode(matrix, nodeId, pos.row, newCol);

  // Shift all children too
  const nodeChildren = children.get(nodeId) || [];
  for (const childId of nodeChildren) {
    const childPos = gridPos.get(childId);
    if (childPos && childPos.col > oldCol) {
      shiftNodeRight(matrix, gridPos, childId, childPos.col + colDelta, children, shifted);
    }
  }
}

/**
 * Main layout algorithm
 */
function buildMatrix(rootId, children, edgeArgNames) {
  const matrix = createMatrixState();
  const gridPos = new Map();
  const placed = new Set();

  // Track which nodes are referenced by multiple parents (shared nodes)
  const referencedBy = new Map();
  children.forEach((childList, parentId) => {
    childList.forEach(childId => {
      if (!referencedBy.has(childId)) referencedBy.set(childId, []);
      referencedBy.get(childId).push(parentId);
    });
  });

  /**
   * Sort children for processing order:
   * - Already placed (shared) nodes first - we just draw edge to them
   * - Shared nodes (referenced by multiple) next - they need to be placed higher
   * - Regular nodes last
   */
  function sortChildren(nodeChildren) {
    return [...nodeChildren].sort((a, b) => {
      const aPlaced = placed.has(a);
      const bPlaced = placed.has(b);
      const aShared = (referencedBy.get(a) || []).length > 1;
      const bShared = (referencedBy.get(b) || []).length > 1;

      if (aPlaced && !bPlaced) return -1;
      if (bPlaced && !aPlaced) return 1;
      if (aShared && !bShared) return -1;
      if (bShared && !aShared) return 1;
      return 0;
    });
  }

  /**
   * Place a branch starting at nodeId
   */
  function placeBranch(nodeId, startCol, minRow) {
    const branch = collectBranch(nodeId, children, placed, referencedBy);
    if (branch.length === 0) return minRow;

    // Find row: below lowest existing node/edge in our columns
    const lowest = findLowestInColumns(matrix, startCol, branch.length);
    let row = Math.max(minRow, lowest + 1);

    // Check if any node in branch will connect to a shared node via horizontal edge
    // that would cross existing vertical edges
    for (let i = 0; i < branch.length; i++) {
      const nid = branch[i];
      const col = startCol + i;
      const nodeChildren = children.get(nid) || [];

      for (const childId of nodeChildren) {
        if (placed.has(childId)) {
          const childPos = gridPos.get(childId);
          // If child is to the right and same row, horizontal edge would be drawn
          // Check if it would cross vertical edges
          if (childPos && childPos.col > col) {
            // Check if placing at current row would cause crossing
            const crossCol = wouldCrossVerticalEdge(matrix, row, col, childPos.col);
            if (crossCol !== -1) {
              // Find lowest vertical edge extent in the crossing column
              let lowestVEdge = row;
              for (let r = row; r < matrix.vEdge.length; r++) {
                if (matrix.vEdge[r] && matrix.vEdge[r][crossCol]) {
                  lowestVEdge = r + 1;
                } else {
                  break;
                }
              }
              row = Math.max(row, lowestVEdge + 1);
            }
          }
        }
      }
    }

    // Place all nodes in branch
    for (let i = 0; i < branch.length; i++) {
      const nid = branch[i];
      const col = startCol + i;
      placeNode(matrix, nid, row, col);
      gridPos.set(nid, { row, col });
      placed.add(nid);

      if (i < branch.length - 1) {
        const nextId = branch[i + 1];
        const argName = edgeArgNames.get(nid + '->' + nextId) || '';
        placeHEdge(matrix, row, col, argName);
      }
    }

    let maxRowUsed = row;

    // Process branch RIGHT TO LEFT
    for (let i = branch.length - 1; i >= 0; i--) {
      const nid = branch[i];
      const col = startCol + i;
      const nodeChildren = children.get(nid) || [];

      // Sort children, skip first (already in branch if not placed)
      const sortedChildren = sortChildren(nodeChildren);
      const firstInBranch = branch[i + 1]; // Next node in branch (if any)

      for (const childId of sortedChildren) {
        // Skip if this child is the next node in our branch
        if (childId === firstInBranch) continue;

        const argName = edgeArgNames.get(nid + '->' + childId) || '';

        if (placed.has(childId)) {
          // Shared node already placed - check if it's to the right of current parent
          const childPos = gridPos.get(childId);
          const requiredCol = col + 1;

          if (childPos.col < requiredCol) {
            // Child is not to the right - shift it and its subtree
            const shifted = new Set();
            shiftNodeRight(matrix, gridPos, childId, requiredCol, children, shifted);
          }

          // Now draw edge to the (possibly shifted) child
          const newChildPos = gridPos.get(childId);
          drawEdge(matrix, row, col, newChildPos.row, newChildPos.col, argName);
        } else {
          // Place child's branch
          const childCol = col + 1;
          const childMinRow = row + 1;

          const childMaxRow = placeBranch(childId, childCol, childMinRow);
          const childPos = gridPos.get(childId);

          if (childPos) {
            // Draw vertical edge from parent down to child
            for (let r = row; r < childPos.row; r++) {
              placeVEdge(matrix, r, childCol);
            }
          }

          maxRowUsed = Math.max(maxRowUsed, childMaxRow);
        }
      }
    }

    return maxRowUsed;
  }

  /**
   * Draw edge from parent to child (for already-placed shared nodes)
   */
  function drawEdge(matrix, parentRow, parentCol, childRow, childCol, argName) {
    if (childRow === parentRow && childCol > parentCol) {
      // Same row, child to the right - horizontal edge
      for (let c = parentCol; c < childCol; c++) {
        placeHEdge(matrix, parentRow, c, c === parentCol ? argName : '');
      }
    } else if (childRow > parentRow) {
      // Child below - vertical edge down in child's column
      for (let r = parentRow; r < childRow; r++) {
        placeVEdge(matrix, r, childCol);
      }
    } else if (childRow < parentRow) {
      // Child above parent - edge goes up
      // This is the problematic case - we need to route carefully
      // Draw vertical edge up in a column to the right of child
      const edgeCol = childCol + 1;
      for (let r = childRow; r < parentRow; r++) {
        placeVEdge(matrix, r, edgeCol);
      }
    }
  }

  if (rootId) {
    placeBranch(rootId, 0, 0);
  }

  return { matrix, gridPos, collisions: [] };
}

/**
 * Format matrix as ASCII art
 */
function formatMatrixASCII(matrix, gridPos) {
  const { nodeGrid, hEdge, vEdge } = matrix;
  const lines = [];

  const maxRow = nodeGrid.length;
  const maxCol = Math.max(...nodeGrid.map(r => r.length), 0);

  if (maxRow === 0 || maxCol === 0) {
    return '(empty matrix)';
  }

  const nodeNames = new Map();
  gridPos.forEach((pos, nodeId) => {
    const name = nodeId.length > 8 ? nodeId.substring(0, 7) + '…' : nodeId;
    nodeNames.set(pos.row + ',' + pos.col, name.padEnd(8));
  });

  for (let r = 0; r < maxRow; r++) {
    let nodeLine = '';
    for (let c = 0; c < maxCol; c++) {
      const key = r + ',' + c;
      const node = nodeNames.get(key) || '        ';
      nodeLine += '[' + node + ']';
      if (c < maxCol - 1) {
        const hasHEdge = hEdge[r] && hEdge[r][c] !== null && hEdge[r][c] !== undefined;
        nodeLine += hasHEdge ? '──' : '  ';
      }
    }
    lines.push(nodeLine);

    if (r < maxRow - 1) {
      let vLine = '';
      for (let c = 0; c < maxCol; c++) {
        const hasVEdge = vEdge[r] && vEdge[r][c];
        vLine += '    ' + (hasVEdge ? '│' : ' ') + '     ';
        if (c < maxCol - 1) {
          vLine += '  ';
        }
      }
      lines.push(vLine);
    }
  }

  return lines.join('\n');
}

/**
 * Check for edge crossings
 */
function detectCrossings(matrix, gridPos) {
  const crossings = [];
  const { hEdge, vEdge } = matrix;

  for (let r = 0; r < hEdge.length; r++) {
    for (let c = 0; c < (hEdge[r] || []).length; c++) {
      if (hEdge[r][c] !== null && hEdge[r][c] !== undefined) {
        const colToCheck = c + 1;
        if (r > 0 && vEdge[r-1] && vEdge[r-1][colToCheck] && vEdge[r] && vEdge[r][colToCheck]) {
          crossings.push({
            type: 'hv_cross',
            hEdge: { row: r, col: c },
            vEdge: { col: colToCheck, passingRow: r }
          });
        }
      }
    }
  }

  return crossings;
}

/**
 * Validate matrix
 */
function validateMatrix(matrix, gridPos) {
  const issues = [];
  const positions = new Map();

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

  const crossings = detectCrossings(matrix, gridPos);
  crossings.forEach(c => {
    issues.push({
      type: 'crossing',
      message: `Edge crossing at h(${c.hEdge.row},${c.hEdge.col}) x v(${c.vEdge.col},${c.vEdge.passingRow})`
    });
  });

  return { valid: issues.length === 0, issues };
}

/**
 * High-level function
 */
function layoutGraph(elements) {
  const { nodes, edges } = elements;

  if (nodes.length === 0) {
    return {
      matrix: createMatrixState(),
      gridPos: new Map(),
      collisions: [],
      validation: { valid: true, issues: [] },
      ascii: '(empty graph)'
    };
  }

  const { children, edgeArgNames } = buildAdjacency(edges);
  const rootId = findRootNode(nodes, edges);

  if (!rootId) {
    return {
      matrix: createMatrixState(),
      gridPos: new Map(),
      collisions: [],
      validation: { valid: false, issues: [{ type: 'no_root', message: 'No root node found' }] },
      ascii: '(no root node)'
    };
  }

  const { matrix, gridPos, collisions } = buildMatrix(rootId, children, edgeArgNames);
  const validation = validateMatrix(matrix, gridPos);
  const ascii = formatMatrixASCII(matrix, gridPos);

  return { matrix, gridPos, collisions, validation, ascii };
}

// Export for testing
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    buildAdjacency,
    findRootNode,
    createMatrixState,
    ensureMatrixSize,
    getNodeAt,
    findLowestInColumns,
    wouldCrossVerticalEdge,
    collectBranch,
    placeNode,
    placeHEdge,
    placeVEdge,
    buildMatrix,
    detectCrossings,
    validateMatrix,
    formatMatrixASCII,
    layoutGraph
  };
}
