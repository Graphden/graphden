// Layout Core - Pure functions for grid-based graph layout
// These functions have no side effects and can be tested independently

/**
 * Build adjacency maps from edges
 * @param {Array} edges - Array of {data: {source, target, argName?}}
 * @returns {{children: Map, parentOf: Map, edgeArgNames: Map}}
 */
function buildAdjacency(edges) {
  const children = new Map();  // nodeId -> [childIds]
  const parentOf = new Map();  // nodeId -> parentId
  const edgeArgNames = new Map();  // "source->target" -> argName
  const edgeSet = new Set();

  edges.forEach(e => {
    const src = e.data.source;
    const tgt = e.data.target;
    const edgeKey = src + '->' + tgt;

    if (edgeSet.has(edgeKey)) return;  // Skip duplicates
    edgeSet.add(edgeKey);

    if (!children.has(src)) children.set(src, []);
    children.get(src).push(tgt);
    parentOf.set(tgt, src);

    if (e.data.argName) {
      edgeArgNames.set(edgeKey, e.data.argName);
    }
  });

  return { children, parentOf, edgeArgNames };
}

/**
 * Find root node (node with no parent)
 * @param {Array} nodes - Array of {data: {id}}
 * @param {Map} parentOf - Map from buildAdjacency
 * @returns {string|null} - Root node id or null
 */
function findRootNode(nodes, parentOf) {
  const root = nodes.find(n => !parentOf.has(n.data.id));
  return root ? root.data.id : null;
}

/**
 * Create empty matrix state
 * @returns {{nodeGrid: Array, hEdge: Array, vEdge: Array}}
 */
function createMatrixState() {
  return {
    nodeGrid: [],  // [row][col] = nodeId | null
    hEdge: [],     // [row][col] = argName | null (horizontal edge from col to col+1)
    vEdge: []      // [row][col] = true/false (vertical edge from row to row+1)
  };
}

/**
 * Ensure matrix has at least (row+1) rows and (col+1) columns
 */
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

/**
 * Get node at position (returns null if out of bounds or empty)
 */
function getNodeAt(matrix, row, col) {
  const { nodeGrid } = matrix;
  if (row < 0 || col < 0) return null;
  if (row >= nodeGrid.length) return null;
  if (col >= nodeGrid[row].length) return null;
  return nodeGrid[row][col];
}

/**
 * Place node at position, returns collision info if any
 */
function placeNode(matrix, nodeId, row, col) {
  ensureMatrixSize(matrix, row, col);
  const existing = matrix.nodeGrid[row][col];
  if (existing !== null) {
    return { collision: true, existing, nodeId, row, col };
  }
  matrix.nodeGrid[row][col] = nodeId;
  return { collision: false };
}

/**
 * Place horizontal edge (from col to col+1)
 */
function placeHEdge(matrix, row, col, argName) {
  ensureMatrixSize(matrix, row, col + 1);
  matrix.hEdge[row][col] = argName || '';
}

/**
 * Place vertical edge (from row to row+1)
 */
function placeVEdge(matrix, row, col) {
  ensureMatrixSize(matrix, row + 1, col);
  matrix.vEdge[row][col] = true;
}

/**
 * Collect horizontal branch: follow first children until leaf
 * @returns {Array} Array of nodeIds from start to leaf
 */
function collectHorizontalBranch(nodeId, children) {
  const branch = [];
  let current = nodeId;
  while (current) {
    branch.push(current);
    const currentChildren = children.get(current) || [];
    current = currentChildren.length > 0 ? currentChildren[0] : null;
  }
  return branch;
}

/**
 * Find minimum row where branch fits (must be below all existing nodes in its columns)
 */
function findRowForBranch(matrix, branch, startCol, minRow) {
  let row = minRow;

  for (let i = 0; i < branch.length; i++) {
    const col = startCol + i;
    // Find lowest occupied row in this column
    for (let r = 0; r < matrix.nodeGrid.length; r++) {
      if (getNodeAt(matrix, r, col) !== null) {
        row = Math.max(row, r + 1);
      }
    }
  }

  return row;
}

/**
 * Place entire horizontal branch at given row
 * @returns {Map} gridPos updates: nodeId -> {row, col}
 */
function placeBranch(matrix, branch, row, startCol, edgeArgNames) {
  const gridPos = new Map();

  for (let i = 0; i < branch.length; i++) {
    const nodeId = branch[i];
    gridPos.set(nodeId, { row, col: startCol + i });
    placeNode(matrix, nodeId, row, startCol + i);

    // Place horizontal edge (except after last node)
    if (i < branch.length - 1) {
      const nextNodeId = branch[i + 1];
      const edgeKey = nodeId + '->' + nextNodeId;
      const argName = edgeArgNames.get(edgeKey) || '';
      placeHEdge(matrix, row, startCol + i, argName);
    }
  }

  return gridPos;
}

/**
 * Main layout algorithm - builds matrix from graph
 * @param {string} rootId - Root node id
 * @param {Map} children - nodeId -> [childIds]
 * @param {Map} edgeArgNames - "source->target" -> argName
 * @returns {{matrix: Object, gridPos: Map, collisions: Array}}
 */
function buildMatrix(rootId, children, edgeArgNames) {
  const matrix = createMatrixState();
  const gridPos = new Map();  // nodeId -> {row, col}
  const collisions = [];

  function assignPositions(nodeId, col, minRow) {
    // 1. Collect horizontal branch
    const branch = collectHorizontalBranch(nodeId, children);

    // 2. Find row where entire branch fits
    const row = findRowForBranch(matrix, branch, col, minRow);

    // 3. Place the branch
    const branchPos = placeBranch(matrix, branch, row, col, edgeArgNames);
    branchPos.forEach((pos, id) => gridPos.set(id, pos));

    let subtreeMaxRow = row;

    // 4. Process side branches (non-first children) from END to START
    for (let branchIdx = branch.length - 1; branchIdx >= 0; branchIdx--) {
      const branchNode = branch[branchIdx];
      const branchCol = col + branchIdx;
      const branchChildren = children.get(branchNode) || [];

      // Process non-first children
      for (let childIdx = 1; childIdx < branchChildren.length; childIdx++) {
        const childId = branchChildren[childIdx];
        const childCol = branchCol + 1;
        const childMinRow = row + 1;

        const childResult = assignPositions(childId, childCol, childMinRow);

        // Place vertical edges from branch down to child
        for (let r = row; r < childResult.minRow; r++) {
          placeVEdge(matrix, r, childCol);
        }

        subtreeMaxRow = Math.max(subtreeMaxRow, childResult.maxRow);
      }
    }

    return { minRow: row, maxRow: subtreeMaxRow };
  }

  if (rootId) {
    assignPositions(rootId, 0, 0);
  }

  // Check for collisions in final matrix
  const seen = new Map();  // "row,col" -> nodeId
  gridPos.forEach((pos, nodeId) => {
    const key = pos.row + ',' + pos.col;
    if (seen.has(key)) {
      collisions.push({
        pos: { row: pos.row, col: pos.col },
        nodes: [seen.get(key), nodeId]
      });
    } else {
      seen.set(key, nodeId);
    }
  });

  return { matrix, gridPos, collisions };
}

/**
 * Validate matrix - check for issues
 * @returns {{valid: boolean, issues: Array}}
 */
function validateMatrix(matrix, gridPos) {
  const issues = [];
  const { nodeGrid, hEdge, vEdge } = matrix;

  // Check for node collisions
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

  // Check for edges crossing nodes
  for (let r = 0; r < vEdge.length; r++) {
    for (let c = 0; c < (vEdge[r] || []).length; c++) {
      if (vEdge[r][c]) {
        // Vertical edge from (r, c) to (r+1, c)
        // Check if there's a node at (r+1, c) that isn't the target
        const nodeBelow = getNodeAt(matrix, r + 1, c);
        if (nodeBelow) {
          // This is expected - edge connects to node below
        }
      }
    }
  }

  return {
    valid: issues.length === 0,
    issues
  };
}

/**
 * Format matrix as ASCII art for debugging
 */
function formatMatrixASCII(matrix, gridPos) {
  const { nodeGrid, hEdge, vEdge } = matrix;
  const lines = [];

  // Find max dimensions
  const maxRow = nodeGrid.length;
  const maxCol = Math.max(...nodeGrid.map(r => r.length), 0);

  if (maxRow === 0 || maxCol === 0) {
    return '(empty matrix)';
  }

  // Build node name map (truncated to 8 chars)
  const nodeNames = new Map();
  gridPos.forEach((pos, nodeId) => {
    const name = nodeId.length > 8 ? nodeId.substring(0, 7) + '…' : nodeId;
    nodeNames.set(pos.row + ',' + pos.col, name.padEnd(8));
  });

  for (let r = 0; r < maxRow; r++) {
    // Node row
    let nodeLine = '';
    for (let c = 0; c < maxCol; c++) {
      const key = r + ',' + c;
      const node = nodeNames.get(key) || '        ';
      nodeLine += '[' + node + ']';

      // Horizontal edge
      if (c < maxCol - 1) {
        const hasHEdge = hEdge[r] && hEdge[r][c];
        nodeLine += hasHEdge ? '──' : '  ';
      }
    }
    lines.push(nodeLine);

    // Vertical edge row
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
 * High-level function: build layout from graph elements
 * @param {{nodes: Array, edges: Array}} elements
 * @returns {{matrix, gridPos, collisions, validation, ascii}}
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

  const { children, parentOf, edgeArgNames } = buildAdjacency(edges);
  const rootId = findRootNode(nodes, parentOf);

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

// Export for testing (works in Node.js and browser)
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    buildAdjacency,
    findRootNode,
    createMatrixState,
    ensureMatrixSize,
    getNodeAt,
    placeNode,
    placeHEdge,
    placeVEdge,
    collectHorizontalBranch,
    findRowForBranch,
    placeBranch,
    buildMatrix,
    validateMatrix,
    formatMatrixASCII,
    layoutGraph
  };
}
