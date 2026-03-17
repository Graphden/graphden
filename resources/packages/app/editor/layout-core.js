// Layout Core - Grid-based layout with no edge crossings
// Algorithm:
// 1. Build horizontal branch (follow first children)
// 2. Place branch below lowest existing nodes in its columns
// 3. Process branch right-to-left, placing remaining children below
// 4. For shared nodes: detect merge point and place merging branches adjacent

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
 * Returns -1 if all columns are empty
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
    // Check vertical edges - if there's a vEdge at (r, c), something will cross row r+1
    for (let r = 0; r < matrix.vEdge.length; r++) {
      if (matrix.vEdge[r] && matrix.vEdge[r][c]) {
        // Vertical edge goes from row r to r+1, so row r+1 is "occupied" by this edge
        lowest = Math.max(lowest, r + 1);
      }
    }
  }
  return lowest;
}

/**
 * Collect horizontal branch - follow first children until no more
 */
function collectBranch(nodeId, children, placed) {
  const branch = [];
  let current = nodeId;
  while (current && !placed.has(current)) {
    branch.push(current);
    const nodeChildren = children.get(current) || [];
    // First child continues the branch
    current = nodeChildren.length > 0 ? nodeChildren[0] : null;
  }
  return branch;
}

/**
 * Main layout algorithm
 */
function buildMatrix(rootId, children, edgeArgNames) {
  const matrix = createMatrixState();
  const gridPos = new Map();
  const placed = new Set();

  // Track which nodes reference which (for detecting shared nodes)
  const referencedBy = new Map(); // nodeId -> [parentIds]
  children.forEach((childList, parentId) => {
    childList.forEach(childId => {
      if (!referencedBy.has(childId)) referencedBy.set(childId, []);
      referencedBy.get(childId).push(parentId);
    });
  });

  /**
   * Place a branch starting at nodeId
   * @param nodeId - starting node
   * @param startCol - column for first node
   * @param minRow - minimum row (must be >= this)
   * @returns {maxRow} - lowest row used by this branch and its subtrees
   */
  function placeBranch(nodeId, startCol, minRow) {
    // 1. Collect the horizontal branch
    const branch = collectBranch(nodeId, children, placed);
    if (branch.length === 0) return minRow;

    // 2. Find row: below lowest existing node/edge in exactly N columns
    // where N = branch length. Only check columns that this branch will occupy.
    const lowest = findLowestInColumns(matrix, startCol, branch.length);
    const row = Math.max(minRow, lowest + 1);

    // 3. Place all nodes in branch
    for (let i = 0; i < branch.length; i++) {
      const nid = branch[i];
      const col = startCol + i;
      placeNode(matrix, nid, row, col);
      gridPos.set(nid, { row, col });
      placed.add(nid);

      // Horizontal edge to next node in branch
      if (i < branch.length - 1) {
        const nextId = branch[i + 1];
        const argName = edgeArgNames.get(nid + '->' + nextId) || '';
        placeHEdge(matrix, row, col, argName);
      }
    }

    let maxRowUsed = row;

    // 4. Process branch RIGHT TO LEFT
    for (let i = branch.length - 1; i >= 0; i--) {
      const nid = branch[i];
      const col = startCol + i;
      const nodeChildren = children.get(nid) || [];

      // Skip first child (already in branch), process rest
      for (let j = 1; j < nodeChildren.length; j++) {
        const childId = nodeChildren[j];

        if (placed.has(childId)) {
          // Shared node - already placed, just draw edge
          const childPos = gridPos.get(childId);
          drawEdge(matrix, row, col, childPos.row, childPos.col, edgeArgNames.get(nid + '->' + childId) || '');
        } else {
          // Place child's branch
          // Child must be below parent (row + 1), but placeBranch will find
          // the actual row based on what's in its columns
          const childCol = col + 1;
          const childMinRow = row + 1;  // Just below parent, not below everything

          const childMaxRow = placeBranch(childId, childCol, childMinRow);
          const childPos = gridPos.get(childId);

          // Draw vertical edge from parent down to child
          if (childPos) {
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
   * Draw edge from (r1,c1) to (r2,c2)
   * For shared nodes that are already placed
   */
  function drawEdge(matrix, r1, c1, r2, c2, argName) {
    if (r1 === r2) {
      // Same row - horizontal
      const minC = Math.min(c1, c2);
      const maxC = Math.max(c1, c2);
      for (let c = minC; c < maxC; c++) {
        placeHEdge(matrix, r1, c, c === c1 ? argName : '');
      }
    } else if (r1 < r2) {
      // Parent above child - vertical edge down
      for (let r = r1; r < r2; r++) {
        placeVEdge(matrix, r, c2);
      }
    } else {
      // Parent below child - edge goes up (unusual but handle it)
      // Use column c2 for vertical
      for (let r = r2; r < r1; r++) {
        placeVEdge(matrix, r, c2);
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
 * A crossing happens when:
 * - Horizontal edge at row r goes from col c to c+1
 * - Vertical edge passes THROUGH row r at col c+1 (from above to below)
 */
function detectCrossings(matrix, gridPos) {
  const crossings = [];
  const { hEdge, vEdge } = matrix;

  for (let r = 0; r < hEdge.length; r++) {
    for (let c = 0; c < (hEdge[r] || []).length; c++) {
      if (hEdge[r][c] !== null && hEdge[r][c] !== undefined) {
        // Horizontal edge at row r from col c to c+1
        // Check if there's a vertical edge that PASSES THROUGH this row at col c+1
        // A vertical edge passes through if:
        // - vEdge[r-1][c+1] = true (edge comes FROM above into row r)
        // - AND vEdge[r][c+1] = true (edge continues below row r)
        // OR if there's a node at (r, c+1) with vEdge going down

        const colToCheck = c + 1;

        // Check if vertical edge passes through row r at col c+1
        // vEdge[row][col] means edge from row to row+1 at col
        // So for a vertical line passing through row r, we need:
        // vEdge[r-1][col] (entering row r from above) AND vEdge[r][col] (leaving row r going down)
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

  // Check for crossings
  const crossings = detectCrossings(matrix, gridPos);
  crossings.forEach(c => {
    issues.push({
      type: 'crossing',
      message: `Edge crossing at h(${c.hEdge.row},${c.hEdge.col}) x v(${c.vEdge.row},${c.vEdge.col})`
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
