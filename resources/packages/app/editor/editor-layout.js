// Editor Layout - Grid-based layout algorithm with no edge crossings
// Depends on: editor-state.js
//
// Algorithm:
// 1. Build horizontal branch (follow first children, prefer shared nodes)
// 2. Place branch below lowest existing nodes in its columns
// 3. Process branch right-to-left, placing remaining children below
// 4. For shared nodes: prioritize them to be placed first/higher

// ============================================================================
// ADJACENCY AND ROOT DETECTION
// ============================================================================

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

function findRootNode(nodes, edges) {
  const hasIncoming = new Set();
  edges.forEach(e => hasIncoming.add(e.data.target));
  const root = nodes.find(n => !hasIncoming.has(n.data.id));
  return root ? root.data.id : null;
}

// ============================================================================
// MATRIX STATE
// ============================================================================

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

// ============================================================================
// LAYOUT HELPERS
// ============================================================================

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
    if (row > 0 && matrix.vEdge[row - 1] && matrix.vEdge[row - 1][c]) {
      return c;
    }
    if (matrix.vEdge[row] && matrix.vEdge[row][c]) {
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
 * Prioritize children that have already-placed descendants
 */
function collectBranch(nodeId, children, placed, referencedBy) {
  const branch = [];
  let current = nodeId;

  while (current && !placed.has(current)) {
    branch.push(current);
    const nodeChildren = children.get(current) || [];
    if (nodeChildren.length === 0) break;

    const sortedChildren = [...nodeChildren].sort((a, b) => {
      const aPlaced = placed.has(a);
      const bPlaced = placed.has(b);

      if (aPlaced && !bPlaced) return -1;
      if (bPlaced && !aPlaced) return 1;

      const aHasPlacedDesc = hasPlacedDescendant(a, children, placed, new Set());
      const bHasPlacedDesc = hasPlacedDescendant(b, children, placed, new Set());

      if (aHasPlacedDesc && !bHasPlacedDesc) return -1;
      if (bHasPlacedDesc && !aHasPlacedDesc) return 1;

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
 */
function shiftNodeRight(matrix, gridPos, nodeId, newCol, children, shifted) {
  if (shifted.has(nodeId)) return;
  shifted.add(nodeId);

  const pos = gridPos.get(nodeId);
  if (!pos) return;

  const oldCol = pos.col;
  const colDelta = newCol - oldCol;
  if (colDelta <= 0) return;

  const occupant = getNodeAt(matrix, pos.row, newCol);
  if (occupant && occupant !== nodeId) {
    shiftNodeRight(matrix, gridPos, occupant, newCol + 1, children, shifted);
  }

  if (matrix.nodeGrid[pos.row] && matrix.nodeGrid[pos.row][oldCol] === nodeId) {
    matrix.nodeGrid[pos.row][oldCol] = null;
  }

  pos.col = newCol;
  placeNode(matrix, nodeId, pos.row, newCol);

  const nodeChildren = children.get(nodeId) || [];
  for (const childId of nodeChildren) {
    const childPos = gridPos.get(childId);
    if (childPos && childPos.col > oldCol) {
      shiftNodeRight(matrix, gridPos, childId, childPos.col + colDelta, children, shifted);
    }
  }
}

// ============================================================================
// MAIN MATRIX BUILDING ALGORITHM
// ============================================================================

function buildMatrix(rootId, children, edgeArgNames) {
  const matrix = createMatrixState();
  const gridPos = new Map();
  const placed = new Set();

  const referencedBy = new Map();
  children.forEach((childList, parentId) => {
    childList.forEach(childId => {
      if (!referencedBy.has(childId)) referencedBy.set(childId, []);
      referencedBy.get(childId).push(parentId);
    });
  });

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

  function placeBranch(nodeId, startCol, minRow) {
    const branch = collectBranch(nodeId, children, placed, referencedBy);
    if (branch.length === 0) return minRow;

    const lowest = findLowestInColumns(matrix, startCol, branch.length);
    let row = Math.max(minRow, lowest + 1);

    for (let i = 0; i < branch.length; i++) {
      const nid = branch[i];
      const col = startCol + i;
      const nodeChildren = children.get(nid) || [];

      for (const childId of nodeChildren) {
        if (placed.has(childId)) {
          const childPos = gridPos.get(childId);
          if (childPos && childPos.col > col) {
            const crossCol = wouldCrossVerticalEdge(matrix, row, col, childPos.col);
            if (crossCol !== -1) {
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

    for (let i = branch.length - 1; i >= 0; i--) {
      const nid = branch[i];
      const col = startCol + i;
      const nodeChildren = children.get(nid) || [];
      const sortedChildren = sortChildren(nodeChildren);
      const firstInBranch = branch[i + 1];

      for (const childId of sortedChildren) {
        if (childId === firstInBranch) continue;

        const argName = edgeArgNames.get(nid + '->' + childId) || '';

        if (placed.has(childId)) {
          const childPos = gridPos.get(childId);
          const requiredCol = col + 1;

          if (childPos.col < requiredCol) {
            const shifted = new Set();
            shiftNodeRight(matrix, gridPos, childId, requiredCol, children, shifted);
          }

          const newChildPos = gridPos.get(childId);
          drawEdge(matrix, row, col, newChildPos.row, newChildPos.col, argName);
        } else {
          const childCol = col + 1;
          const childMinRow = row + 1;

          const childMaxRow = placeBranch(childId, childCol, childMinRow);
          const childPos = gridPos.get(childId);

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

  function drawEdge(matrix, parentRow, parentCol, childRow, childCol, argName) {
    if (childRow === parentRow && childCol > parentCol) {
      for (let c = parentCol; c < childCol; c++) {
        placeHEdge(matrix, parentRow, c, c === parentCol ? argName : '');
      }
    } else if (childRow > parentRow) {
      for (let r = parentRow; r < childRow; r++) {
        placeVEdge(matrix, r, childCol);
      }
    } else if (childRow < parentRow) {
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

// ============================================================================
// VALIDATION AND DEBUG
// ============================================================================

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

// ============================================================================
// HIGH-LEVEL LAYOUT API
// ============================================================================

/**
 * Build graph layout from elements
 * Returns: { matrix, gridPos, validation, ascii }
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

// ============================================================================
// NODE SIZE CALCULATION
// ============================================================================

/**
 * Calculate node dimensions based on label
 */
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
 * Returns: Map<nodeId, {row, col, width, height, x, y}>
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

// Export for Node.js testing
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
    layoutGraph,
    calculateNodeSize,
    buildGridLayout
  };
}
