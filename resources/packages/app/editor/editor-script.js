// Graph editor JavaScript - 2-entity schema visualization
// Grid-based layout with interactive expand/collapse

// Suppress Cytoscape edge overlap warnings only during user drag
let suppressEdgeWarnings = false;
(function() {
  const originalWarn = console.warn;
  console.warn = function(...args) {
    if (suppressEdgeWarnings && args[0] && typeof args[0] === 'string' && args[0].includes('invalid endpoints')) {
      return; // Suppress this specific warning during drag
    }
    originalWarn.apply(console, args);
  };
})();

let cy = null;
let selectedFnId = null;
let graphData = null;
let lookups = null;

// Map: originalFnId -> number of ancestors to show (0 = just self, 1 = self + parent, etc.)
let expansionLevel = new Map();

// For hover preview: originalFnId -> preview level (null if no preview)
let previewLevel = new Map();

// Track which node is being hovered
let hoveredNodeId = null;

// Flag to prevent mouseleave from triggering during overlay rebuild
let rebuildingOverlays = false;

// Flag to disable ancestor selection during drag
let isDragging = false;

// Flag to disable hover on all nodes when any node is being grabbed
let isGrabbing = false;

const MAX_VISIBLE_ANCESTORS = 4;

// User-moved nodes (won't be auto-positioned)
let userMovedNodes = new Set();

// Animation duration
const ANIM_DURATION = 200;

// Grid constants
const GRID_GAP_X = 80;  // Horizontal gap between columns (space for edge routing)
const GRID_GAP_Y = 30;  // Vertical gap between rows

// Build lookup maps
function buildLookups(data) {
  const fnMap = new Map();
  const argMap = new Map();
  const argsByFn = new Map();

  (data.fns || []).forEach(f => fnMap.set(f.id, f));
  (data.args || []).forEach(a => {
    argMap.set(a.id, a);
    const fnId = a['fn-id'];
    if (fnId) {
      if (!argsByFn.has(fnId)) argsByFn.set(fnId, []);
      argsByFn.get(fnId).push(a);
    }
  });

  return { fnMap, argMap, argsByFn };
}

// Get inheritance chain: [fnId, parentId, grandparentId, ...]
function getInheritanceChain(fnId) {
  const chain = [];
  let current = fnId;
  const visited = new Set();
  while (current && !visited.has(current)) {
    visited.add(current);
    chain.push(current);
    const fn = lookups.fnMap.get(current);
    current = fn ? fn['parent-id'] : null;
  }
  return chain;
}

// Resolve arg name
function resolveArgName(arg) {
  let current = arg;
  for (let i = 0; i < 100; i++) {
    if (current.name) return current.name;
    if (!current['source-id']) return null;
    current = lookups.argMap.get(current['source-id']);
    if (!current) return null;
  }
  return null;
}

// Check if a function sets any arguments
function fnSetsArgs(fnId) {
  const args = lookups.argsByFn.get(fnId) || [];
  return args.some(arg => {
    const hasValue = arg.value !== null && arg.value !== undefined;
    const hasRef = !!arg['ref-id'];
    return hasValue || hasRef;
  });
}

// Build grouped ancestor list for display
function buildAncestorItems(chain) {
  const items = [];
  let currentGroupLevel = 0;

  for (let i = 0; i < chain.length; i++) {
    const fnId = chain[i];
    const fn = lookups.fnMap.get(fnId);
    if (!fn) continue;

    const setsArgs = fnSetsArgs(fnId);

    if (setsArgs) {
      currentGroupLevel = i;
      items.push({
        idx: i,
        name: fn.name,
        fnId: fnId,
        groupLevel: i,
        isAlias: false
      });
    } else {
      items.push({
        idx: i,
        name: '(' + fn.name + ')',
        fnId: fnId,
        groupLevel: currentGroupLevel,
        isAlias: true
      });
    }
  }

  return items;
}

// Update sidebar
function updateEntityList(data) {
  const list = document.getElementById('entity-list');
  list.innerHTML = '';

  (data.fns || []).forEach(fn => {
    const li = document.createElement('li');
    li.className = 'entity-item';
    if (fn.id === selectedFnId) li.className += ' selected';
    li.dataset.fnId = fn.id;
    li.innerHTML = '<div class="name">' + fn.name + '</div>';
    li.onclick = () => selectFn(fn.id);
    list.appendChild(li);
  });

  if (list.children.length === 0) {
    list.innerHTML = '<li class="loading">No functions found</li>';
  }
}

// Select a function
function selectFn(fnId, updateHistory = true) {
  selectedFnId = fnId;
  expansionLevel.clear();
  previewLevel.clear();
  userMovedNodes.clear();

  document.querySelectorAll('.entity-item').forEach(el => el.classList.remove('selected'));
  const item = document.querySelector('[data-fn-id="' + fnId + '"]');
  if (item) item.classList.add('selected');

  const fn = lookups.fnMap.get(fnId);
  if (fn && updateHistory) {
    window.history.pushState(null, '', '#' + fn.name);
  }

  renderGraph(true);
}

// Set expansion level for a node
function setExpansionLevel(originalFnId, level) {
  if (level === 0) {
    expansionLevel.delete(originalFnId);
  } else {
    expansionLevel.set(originalFnId, level);
  }
  previewLevel.delete(originalFnId);
  renderGraph(false);
}

// Debounce timer for preview level changes
let previewDebounceTimer = null;
const PREVIEW_DEBOUNCE_MS = 100;

// Set preview level (hover)
function setPreviewLevel(originalFnId, level) {
  const oldLevel = previewLevel.get(originalFnId);

  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }

  if (level === null) {
    previewLevel.delete(originalFnId);
    hoveredNodeId = null;
    if (oldLevel !== level) {
      renderGraph(false);
    }
  } else {
    previewDebounceTimer = setTimeout(() => {
      previewLevel.set(originalFnId, level);
      hoveredNodeId = 'fn-' + originalFnId;
      if (oldLevel !== level) {
        renderGraph(false);
      }
    }, PREVIEW_DEBOUNCE_MS);
  }
}

// Clear all preview state
function clearPreviewState() {
  if (previewLevel.size > 0) {
    previewLevel.clear();
    hoveredNodeId = null;
    renderGraph(false);
  }
}

function selectFnByName(name, updateHistory = true) {
  const fn = (graphData.fns || []).find(f => f.name === name);
  if (fn) selectFn(fn.id, updateHistory);
}

async function initGraph() {
  const response = await fetch('/api/graph/entities');
  graphData = await response.json();
  lookups = buildLookups(graphData);
  updateEntityList(graphData);

  const hash = window.location.hash.slice(1);
  if (hash) {
    selectFnByName(decodeURIComponent(hash), false);
  } else {
    renderGraph(true);
  }
}

window.addEventListener('popstate', () => {
  const hash = window.location.hash.slice(1);
  if (hash && graphData) selectFnByName(decodeURIComponent(hash), false);
});

// ============================================================================
// GRID-BASED LAYOUT
// ============================================================================

// Calculate node dimensions based on label
function calculateNodeSize(nodeData) {
  const label = nodeData.data.label || '';
  const type = nodeData.data.type;

  if (type === 'arg') {
    const maxLen = 30;
    const effectiveLen = Math.min(label.length, maxLen);
    return {
      width: Math.max(40, effectiveLen * 6 + 16),
      height: 28
    };
  } else {
    // fn node
    const lines = label.split('\n');
    const maxLineLen = 30;
    const maxLen = Math.max(...lines.map(l => {
      const cleanLen = l.replace(/[^\x20-\x7E]/g, '').length;
      return Math.min(cleanLen, maxLineLen);
    }));
    return {
      width: Math.max(80, maxLen * 7 + 24),
      height: Math.max(30, lines.length * 16 + 16)
    };
  }
}

// Build grid layout from graph elements
// Returns: Map<nodeId, {row, col, width, height}>
function buildGridLayout(elements) {
  const { nodes, edges } = elements;
  if (nodes.length === 0) return new Map();

  // Build adjacency: parent -> [children] (no duplicates)
  const children = new Map();
  const parentOf = new Map();
  const edgeSet = new Set();  // Track unique edges
  edges.forEach(e => {
    const src = e.data.source;
    const tgt = e.data.target;
    const edgeKey = src + '->' + tgt;
    if (edgeSet.has(edgeKey)) return;  // Skip duplicate edges
    edgeSet.add(edgeKey);
    if (!children.has(src)) children.set(src, []);
    children.get(src).push(tgt);
    parentOf.set(tgt, src);
  });

  // Find root (node with no parent)
  const rootNode = nodes.find(n => !parentOf.has(n.data.id));
  if (!rootNode) return new Map();

  // Calculate sizes for all nodes
  const sizes = new Map();
  nodes.forEach(n => {
    sizes.set(n.data.id, calculateNodeSize(n));
  });

  // === MATRIX SYSTEM ===
  // nodeGrid[row][col] = nodeId | null
  // hEdge[row][col] = true means horizontal edge from (row,col) to (row,col+1)
  // vEdge[row][col] = true means vertical edge from (row,col) to (row+1,col)
  const nodeGrid = [];
  const hEdge = [];
  const vEdge = [];

  function ensureSize(row, col) {
    while (nodeGrid.length <= row) nodeGrid.push([]);
    while (hEdge.length <= row) hEdge.push([]);
    while (vEdge.length <= row) vEdge.push([]);
    for (let r = 0; r <= row; r++) {
      while (nodeGrid[r].length <= col) nodeGrid[r].push(null);
      while (hEdge[r].length <= col) hEdge[r].push(false);
      while (vEdge[r].length <= col) vEdge[r].push(false);
    }
  }

  function getNode(row, col) {
    if (row < 0 || col < 0) return null;
    if (row >= nodeGrid.length) return null;
    if (col >= nodeGrid[row].length) return null;
    return nodeGrid[row][col];
  }

  function hasHEdge(row, col) {
    if (row < 0 || col < 0) return false;
    if (row >= hEdge.length) return false;
    if (col >= hEdge[row].length) return false;
    return hEdge[row][col];
  }

  function hasVEdge(row, col) {
    if (row < 0 || col < 0) return false;
    if (row >= vEdge.length) return false;
    if (col >= vEdge[row].length) return false;
    return vEdge[row][col];
  }

  function placeNode(nodeId, row, col) {
    ensureSize(row, col);
    if (nodeGrid[row][col] !== null) {
      console.error('NODE COLLISION at', row, col, 'existing:', nodeGrid[row][col], 'new:', nodeId);
    }
    nodeGrid[row][col] = nodeId;
  }

  function placeHEdge(row, col) {
    ensureSize(row, col + 1);
    hEdge[row][col] = true;
  }

  function placeVEdge(row, col) {
    ensureSize(row + 1, col);
    vEdge[row][col] = true;
  }

  // Check if we can place horizontal edge from (row, fromCol) to (row, toCol)
  // Must not cross any vertical edges
  function canPlaceHEdgePath(row, fromCol, toCol) {
    const minCol = Math.min(fromCol, toCol);
    const maxCol = Math.max(fromCol, toCol);
    for (let c = minCol; c < maxCol; c++) {
      // Check if vertical edge crosses this horizontal segment
      // A vertical edge at (r, c) goes from row r to row r+1
      // It crosses horizontal edge at row if it spans that row
      // Check row-1 (edge coming down into this row) - would cross
      if (hasVEdge(row - 1, c + 1)) return false;
      // Also check if there's a node in the way (except endpoints)
      if (c > minCol && c < maxCol && getNode(row, c) !== null) return false;
    }
    return true;
  }

  // Check if we can place vertical edge from (fromRow, col) to (toRow, col)
  // Must not cross any horizontal edges
  function canPlaceVEdgePath(fromRow, toRow, col) {
    const minRow = Math.min(fromRow, toRow);
    const maxRow = Math.max(fromRow, toRow);
    for (let r = minRow; r < maxRow; r++) {
      // Check if horizontal edge crosses this vertical segment
      // H edge at (r, c) goes from col c to col c+1
      // Crosses vertical at col if col is between c and c+1
      if (hasHEdge(r, col - 1)) return false;
      if (hasHEdge(r, col)) return false;
      // Check if there's a node in the way (except endpoints)
      if (r > minRow && r < maxRow && getNode(r, col) !== null) return false;
    }
    return true;
  }

  // Shift all nodes and edges from startRow down by delta rows
  function shiftDown(startRow, delta) {
    if (delta <= 0) return;

    // Work from bottom up to avoid overwriting
    const maxRow = nodeGrid.length - 1;

    // Extend grids
    ensureSize(maxRow + delta, 0);

    for (let r = maxRow; r >= startRow; r--) {
      for (let c = 0; c < nodeGrid[r].length; c++) {
        // Move node
        if (nodeGrid[r][c] !== null) {
          ensureSize(r + delta, c);
          nodeGrid[r + delta][c] = nodeGrid[r][c];
          nodeGrid[r][c] = null;
        }
        // Move horizontal edge
        if (hEdge[r] && hEdge[r][c]) {
          ensureSize(r + delta, c);
          hEdge[r + delta][c] = true;
          hEdge[r][c] = false;
        }
        // Move vertical edge
        if (vEdge[r] && vEdge[r][c]) {
          ensureSize(r + delta, c);
          vEdge[r + delta][c] = true;
          vEdge[r][c] = false;
        }
      }
    }
  }

  // === LAYOUT ALGORITHM ===
  const gridPos = new Map();  // nodeId -> {row, col}

  // Collect horizontal branch: follow first children until leaf
  // Returns array of nodeIds from start to leaf
  function collectHorizontalBranch(nodeId) {
    const branch = [];
    let current = nodeId;
    while (current) {
      branch.push(current);
      const currentChildren = children.get(current) || [];
      current = currentChildren.length > 0 ? currentChildren[0] : null;
    }
    return branch;
  }

  // Check if row is free for entire branch starting at col
  function canPlaceBranch(branch, row, startCol) {
    for (let i = 0; i < branch.length; i++) {
      if (getNode(row, startCol + i) !== null) {
        return false;
      }
    }
    return true;
  }

  // Find minimum row where branch fits
  // Branch must be placed BELOW all existing nodes in its columns
  function findRowForBranch(branch, startCol, minRow) {
    let row = minRow;

    // For each column the branch will occupy, find the lowest existing node
    for (let i = 0; i < branch.length; i++) {
      const col = startCol + i;
      // Find the lowest occupied row in this column
      for (let r = 0; r < nodeGrid.length; r++) {
        if (getNode(r, col) !== null) {
          // Branch must be below this node
          row = Math.max(row, r + 1);
        }
      }
    }

    return row;
  }

  // Place entire horizontal branch at given row
  function placeBranch(branch, row, startCol) {
    for (let i = 0; i < branch.length; i++) {
      const nodeId = branch[i];
      gridPos.set(nodeId, { row, col: startCol + i });
      placeNode(nodeId, row, startCol + i);

      // Place horizontal edge (except after last node)
      if (i < branch.length - 1) {
        placeHEdge(row, startCol + i);
      }
    }
  }

  // Main recursive function
  // Places nodeId and all its descendants, returns { minRow, maxRow }
  function assignPositions(nodeId, col, minRow) {
    // 1. Collect horizontal branch
    const branch = collectHorizontalBranch(nodeId);

    // 2. Find row where entire branch fits
    const row = findRowForBranch(branch, col, minRow);

    // 3. Place the branch
    placeBranch(branch, row, col);

    let subtreeMaxRow = row;

    // 4. Process side branches (non-first children) from END to START
    //    This ensures deeper nodes are processed first
    for (let branchIdx = branch.length - 1; branchIdx >= 0; branchIdx--) {
      const branchNode = branch[branchIdx];
      const branchCol = col + branchIdx;
      const branchChildren = children.get(branchNode) || [];

      // Process non-first children (first child is already in the horizontal branch)
      for (let childIdx = 1; childIdx < branchChildren.length; childIdx++) {
        const childId = branchChildren[childIdx];
        const childCol = branchCol + 1;

        // Child must be below the branch row
        const childMinRow = row + 1;

        // Find where this child's subtree can fit
        const childResult = assignPositions(childId, childCol, childMinRow);

        // Place vertical edges from branch down to child
        for (let r = row; r < childResult.minRow; r++) {
          placeVEdge(r, childCol);
        }

        subtreeMaxRow = Math.max(subtreeMaxRow, childResult.maxRow);
      }
    }

    return { minRow: row, maxRow: subtreeMaxRow };
  }

  assignPositions(rootNode.data.id, 0, 0);

  // Build gridPos from nodeGrid for compatibility
  for (let r = 0; r < nodeGrid.length; r++) {
    for (let c = 0; c < nodeGrid[r].length; c++) {
      const nodeId = nodeGrid[r][c];
      if (nodeId) {
        gridPos.set(nodeId, { row: r, col: c });
      }
    }
  }

  // Calculate column widths (max width in each column)
  const colWidths = new Map();
  gridPos.forEach((pos, nodeId) => {
    const size = sizes.get(nodeId);
    const currentMax = colWidths.get(pos.col) || 0;
    colWidths.set(pos.col, Math.max(currentMax, size.width));
  });

  // Calculate row heights (max height in each row)
  const rowHeights = new Map();
  gridPos.forEach((pos, nodeId) => {
    const size = sizes.get(nodeId);
    const currentMax = rowHeights.get(pos.row) || 0;
    rowHeights.set(pos.row, Math.max(currentMax, size.height));
  });

  // Calculate X positions for left edge of each column
  // Nodes will be left-aligned within their column
  const colLeftX = new Map();
  let currentX = 0;
  const maxCol = Math.max(...Array.from(colWidths.keys()));
  for (let c = 0; c <= maxCol; c++) {
    colLeftX.set(c, currentX);
    const width = colWidths.get(c) || 80;
    currentX += width + GRID_GAP_X;
  }

  // Calculate Y positions for center of each row
  // Nodes will be vertically centered within their row (keeps horizontal edges straight)
  const rowCenterY = new Map();
  let currentY = 0;
  const maxRow = Math.max(...Array.from(rowHeights.keys()));
  for (let r = 0; r <= maxRow; r++) {
    const height = rowHeights.get(r) || 30;
    rowCenterY.set(r, currentY + height / 2);  // Center of row
    currentY += height + GRID_GAP_Y;
  }

  // Build final layout: nodeId -> {x, y, width, height}
  // X: left-aligned (left edge + half node width)
  // Y: vertically centered in row (row center)
  const layout = new Map();
  gridPos.forEach((pos, nodeId) => {
    const size = sizes.get(nodeId);
    const leftX = colLeftX.get(pos.col);
    layout.set(nodeId, {
      x: leftX + size.width / 2,  // Left-aligned: left edge + half width
      y: rowCenterY.get(pos.row), // Vertically centered in row
      width: size.width,
      height: size.height,
      row: pos.row,
      col: pos.col
    });
  });

  return layout;
}

// ============================================================================
// RENDER GRAPH
// ============================================================================

function renderGraph(shouldFit = true) {
  const elements = buildGraphElements();

  // First render - create cytoscape
  if (!cy) {
    createCytoscape(elements, shouldFit);
    return;
  }

  // Stop any running animations
  cy.nodes().forEach(node => node.stop(true, true));

  // Build layout
  const layout = buildGridLayout(elements);

  // Build maps for quick lookup
  const newNodeIds = new Set(elements.nodes.map(n => n.data.id));
  const newEdgeIds = new Set(elements.edges.map(e => e.data.id));

  // Find nodes/edges to remove and add
  const nodesToRemove = cy.nodes().filter(node => !newNodeIds.has(node.id()));
  const edgesToRemove = cy.edges().filter(edge => !newEdgeIds.has(edge.id()));
  const nodesToAdd = elements.nodes.filter(n => !cy.getElementById(n.data.id).length);
  const edgesToAdd = elements.edges.filter(e => !cy.getElementById(e.data.id).length);

  // Update existing node data
  cy.nodes().forEach(node => {
    const newData = elements.nodes.find(n => n.data.id === node.id());
    if (newData) {
      node.data(newData.data);
    }
  });

  function completeUpdate() {
    // Remove old elements
    nodesToRemove.forEach(node => {
      const overlay = document.querySelector('.node-overlay[data-original-fn-id="' + node.data('originalFnId') + '"]');
      if (overlay) overlay.remove();
      userMovedNodes.delete(node.id());
    });
    cy.remove(nodesToRemove);
    cy.remove(edgesToRemove);

    // Add new nodes with initial position (to avoid zero-length edges)
    if (nodesToAdd.length > 0) {
      nodesToAdd.forEach(n => {
        const pos = layout.get(n.data.id);
        if (pos) {
          n.position = { x: pos.x, y: pos.y };
        }
      });
      cy.add(nodesToAdd);
    }

    // Add edges after nodes are positioned
    if (edgesToAdd.length > 0) {
      cy.add(edgesToAdd);
    }

    // Apply layout positions with animation
    const animPromises = [];

    cy.nodes().forEach(node => {
      const nodeId = node.id();

      // Skip user-moved nodes
      if (userMovedNodes.has(nodeId)) return;

      const pos = layout.get(nodeId);
      if (!pos) return;

      const targetPos = { x: pos.x, y: pos.y };
      const currentPos = node.position();

      // New nodes are already at target position (set during add)
      // Only animate existing nodes that need to move
      const isNewNode = nodesToAdd.some(n => n.data.id === nodeId);
      if (!isNewNode && (Math.abs(currentPos.x - targetPos.x) > 1 || Math.abs(currentPos.y - targetPos.y) > 1)) {
        const anim = node.animation({
          position: targetPos,
          duration: ANIM_DURATION,
          easing: 'ease-out'
        });
        animPromises.push(anim.play().promise());
      } else if (!isNewNode) {
        node.position(targetPos);
      }
    });

    // Update overlays during animation
    let animating = true;
    function updateLoop() {
      if (animating) {
        updateOverlayPositions();
        requestAnimationFrame(updateLoop);
      }
    }

    rebuildingOverlays = true;
    createNodeOverlays();
    rebuildingOverlays = false;
    requestAnimationFrame(updateLoop);

    Promise.all(animPromises).then(() => {
      animating = false;
      updateOverlayPositions();
      if (shouldFit && cy.nodes().length > 0) {
        cy.fit(50);
      }
    });
  }

  // If nodes to remove, animate them first
  if (nodesToRemove.length > 0) {
    const removeAnims = [];
    nodesToRemove.forEach(node => {
      const overlay = document.querySelector('.node-overlay[data-original-fn-id="' + node.data('originalFnId') + '"]');
      if (overlay) overlay.remove();

      const parentEdge = cy.edges().filter(e => e.data('target') === node.id());
      if (parentEdge.length > 0) {
        const parentId = parentEdge[0].data('source');
        const parentNode = cy.getElementById(parentId);
        if (parentNode.length > 0) {
          const parentPos = parentNode.position();
          removeAnims.push(
            node.animation({
              position: { x: parentPos.x, y: parentPos.y },
              style: { opacity: 0 },
              duration: ANIM_DURATION,
              easing: 'ease-in'
            }).play().promise()
          );
        }
      }
    });

    if (removeAnims.length > 0) {
      // Suppress edge warnings during collapse animation
      suppressEdgeWarnings = true;
      Promise.all(removeAnims).then(() => {
        suppressEdgeWarnings = false;
        completeUpdate();
      });
    } else {
      completeUpdate();
    }
  } else {
    completeUpdate();
  }
}

// ============================================================================
// CYTOSCAPE SETUP
// ============================================================================

function createCytoscape(elements, shouldFit) {
  cy = cytoscape({
    container: document.getElementById('cy'),
    elements: elements,
    style: [
      // fn node
      { selector: 'node[type="fn"]', style: {
        'label': 'data(label)',
        'text-valign': 'center',
        'text-halign': 'center',
        'text-wrap': 'wrap',
        'font-size': '11px',
        'font-family': 'SF Mono, Monaco, monospace',
        'shape': 'round-rectangle',
        'background-color': '#ffffff',
        'border-width': 2,
        'border-color': '#000000',
        'color': '#000000',
        'padding': '10px',
        'width': function(node) {
          var label = node.data('label') || '';
          var lines = label.split('\n');
          var maxLineLen = 30;
          var maxLen = Math.max(...lines.map(function(l) {
            var cleanLen = l.replace(/[^\x20-\x7E]/g, '').length;
            return Math.min(cleanLen, maxLineLen);
          }));
          return Math.max(80, maxLen * 7 + 24);
        },
        'height': function(node) {
          var label = node.data('label') || '';
          var lines = label.split('\n').length;
          return Math.max(30, lines * 16 + 16);
        }
      }},
      // Root node
      { selector: 'node[?isRoot]', style: {
        'border-width': 4
      }},
      // Non-placeholder fn nodes - hide label for overlay
      { selector: 'node[type="fn"][!isPlaceholder]', style: {
        'label': ''
      }},
      // Placeholder (unset arg)
      { selector: 'node[?isPlaceholder]', style: {
        'border-style': 'dashed',
        'border-color': '#999999',
        'background-color': '#ffffff'
      }},
      // Arg value node
      { selector: 'node[type="arg"]', style: {
        'label': function(node) {
          var label = node.data('label') || '';
          var maxLen = 30;
          if (label.length > maxLen) {
            return label.substring(0, maxLen - 1) + '…';
          }
          return label;
        },
        'text-valign': 'center',
        'text-halign': 'center',
        'font-size': '10px',
        'font-family': 'SF Mono, Monaco, monospace',
        'shape': 'rectangle',
        'background-color': '#ffffff',
        'border-width': 2,
        'border-color': '#000000',
        'color': '#000000',
        'padding': '8px',
        'width': function(node) {
          var label = node.data('label') || '';
          var maxLen = 30;
          var effectiveLen = Math.min(label.length, maxLen);
          return Math.max(40, effectiveLen * 6 + 16);
        },
        'height': 28
      }},
      // Edge - taxi style
      { selector: 'edge', style: {
        'width': 2,
        'line-color': '#000000',
        'line-style': 'solid',
        'curve-style': 'taxi',
        'taxi-direction': 'rightward',
        'taxi-turn': '40px',
        'taxi-turn-min-distance': '10px',
        'source-endpoint': 'outside-to-node',
        'target-endpoint': 'outside-to-node'
      }},
      // Edge with label
      { selector: 'edge[argName]', style: {
        'target-label': function(edge) {
          var label = edge.data('argName') || '';
          var maxLen = 28;
          if (label.length > maxLen) {
            return label.substring(0, maxLen - 1) + '…';
          }
          return label;
        },
        'target-text-offset': function(edge) {
          var label = edge.data('argName') || '';
          return Math.max(30, label.length * 3 + 15);
        },
        'font-size': '10px',
        'font-family': 'SF Mono, Monaco, monospace',
        'color': '#666666',
        'text-background-color': '#ffffff',
        'text-background-opacity': 0.9,
        'text-background-padding': '2px'
      }},
      // Unset edge
      { selector: 'edge[?isUnset]', style: {
        'line-style': 'dashed',
        'line-color': '#999999'
      }}
    ],
    layout: { name: 'preset' },
    minZoom: 0.1,
    maxZoom: 3
  });

  // Apply initial layout
  const layout = buildGridLayout({ nodes: elements.nodes, edges: elements.edges });
  cy.nodes().forEach(node => {
    const pos = layout.get(node.id());
    if (pos) {
      node.position({ x: pos.x, y: pos.y });
    }
  });

  if (shouldFit && cy.nodes().length > 0) {
    cy.fit(50);
  }

  // Track user-moved nodes
  cy.on('dragfree', 'node', function(evt) {
    userMovedNodes.add(evt.target.id());
  });

  // Event handlers
  cy.on('grab', 'node', function() {
    isGrabbing = true;
    suppressEdgeWarnings = true;
    clearPreviewState();
  });

  cy.on('free', 'node', function() {
    isGrabbing = false;
    suppressEdgeWarnings = false;
  });

  cy.on('tap', 'node[type="fn"]', function(evt) {
    const node = evt.target;
    if (!node.data('isPlaceholder')) {
      const fnId = node.data('originalFnId');
      if (fnId) showFnDetails(fnId);
    }
  });

  cy.on('pan zoom', function() {
    updateOverlayPositions();
  });

  cy.on('drag', 'node', function() {
    updateOverlayPositions();
  });

  // Create overlays
  createNodeOverlays();
}

// ============================================================================
// NODE OVERLAYS (for ancestor list interaction)
// ============================================================================

function createNodeOverlays() {
  // Remove existing overlays
  document.querySelectorAll('.node-overlay').forEach(el => el.remove());

  if (!cy) return;

  const container = document.getElementById('cy');

  cy.nodes('[type="fn"][!isPlaceholder]').forEach(node => {
    const originalFnId = node.data('originalFnId');
    if (!originalFnId) return;

    const chain = getInheritanceChain(originalFnId);
    const items = buildAncestorItems(chain);

    const overlay = document.createElement('div');
    overlay.className = 'node-overlay';
    overlay.dataset.originalFnId = originalFnId;
    overlay.style.position = 'absolute';
    overlay.style.pointerEvents = 'auto';
    overlay.style.zIndex = '10';
    overlay.style.background = 'white';
    overlay.style.border = node.data('isRoot') ? '4px solid black' : '2px solid black';
    overlay.style.borderRadius = '8px';
    overlay.style.overflow = 'hidden';
    overlay.style.fontFamily = 'SF Mono, Monaco, monospace';
    overlay.style.fontSize = '11px';
    overlay.style.cursor = 'pointer';

    // Build content
    const currentLevel = expansionLevel.get(originalFnId) || 0;
    const visibleItems = items.slice(0, MAX_VISIBLE_ANCESTORS + 1);
    const hasMore = items.length > MAX_VISIBLE_ANCESTORS + 1;

    visibleItems.forEach((item, idx) => {
      const line = document.createElement('div');
      line.className = 'ancestor-line';
      line.dataset.level = item.groupLevel;
      line.style.padding = '4px 8px';
      line.style.borderBottom = idx < visibleItems.length - 1 ? '1px solid #eee' : 'none';
      line.textContent = item.name;

      if (item.groupLevel <= currentLevel) {
        line.style.fontWeight = 'bold';
        line.style.background = '#f0f0f0';
      }

      line.addEventListener('mouseenter', () => {
        if (!isGrabbing && !isDragging) {
          setPreviewLevel(originalFnId, item.groupLevel);
        }
      });

      line.addEventListener('click', (e) => {
        e.stopPropagation();
        setExpansionLevel(originalFnId, item.groupLevel);
      });

      overlay.appendChild(line);
    });

    if (hasMore) {
      const more = document.createElement('div');
      more.style.padding = '2px 8px';
      more.style.color = '#999';
      more.style.fontSize = '10px';
      more.textContent = '...';
      overlay.appendChild(more);
    }

    overlay.addEventListener('mouseleave', () => {
      if (!rebuildingOverlays && !isGrabbing) {
        setPreviewLevel(originalFnId, null);
      }
    });

    container.appendChild(overlay);
  });

  updateOverlayPositions();
}

function updateOverlayPositions() {
  if (!cy) return;

  const pan = cy.pan();
  const zoom = cy.zoom();

  document.querySelectorAll('.node-overlay').forEach(overlay => {
    const originalFnId = overlay.dataset.originalFnId;
    const node = cy.getElementById('fn-' + originalFnId);
    if (!node.length) return;

    const pos = node.position();
    const width = node.outerWidth();
    const height = node.outerHeight();

    const screenX = pos.x * zoom + pan.x;
    const screenY = pos.y * zoom + pan.y;

    // After scale(zoom), element will be width*zoom x height*zoom
    // We want to center it at (screenX, screenY)
    // With transform-origin: center, the scaled element centers at (left + width/2, top + height/2)
    // So: left + width/2 = screenX, meaning left = screenX - width/2
    overlay.style.left = (screenX - width / 2) + 'px';
    overlay.style.top = (screenY - height / 2) + 'px';
    overlay.style.width = width + 'px';
    overlay.style.minHeight = height + 'px';
    overlay.style.transform = 'scale(' + zoom + ')';
    overlay.style.transformOrigin = 'center center';
  });
}

// ============================================================================
// BUILD GRAPH ELEMENTS
// ============================================================================

function buildGraphElements() {
  const nodes = [];
  const edges = [];
  const addedNodeIds = new Set();

  if (!selectedFnId || !lookups.fnMap.has(selectedFnId)) {
    return { nodes: [], edges: [] };
  }

  function getEffectiveLevel(originalFnId) {
    if (previewLevel.has(originalFnId)) {
      return previewLevel.get(originalFnId);
    }
    return expansionLevel.get(originalFnId) || 0;
  }

  function getRootSourceId(arg) {
    let sourceId = arg.id;
    let cur = arg;
    while (cur['source-id']) {
      sourceId = cur['source-id'];
      cur = lookups.argMap.get(cur['source-id']);
      if (!cur) break;
    }
    return sourceId;
  }

  function collectSetArgs(originalFnId) {
    const chain = getInheritanceChain(originalFnId);
    const level = getEffectiveLevel(originalFnId);
    const activeFns = chain.slice(0, level + 1);

    const setArgs = new Map();
    // Collect args grouped by level, each level has: refArgs, valueArgs
    const argsByLevel = [];

    for (const fnId of activeFns) {
      const args = lookups.argsByFn.get(fnId) || [];
      const levelRefArgs = [];
      const levelValueArgs = [];

      args.forEach(arg => {
        const hasValue = arg.value !== null && arg.value !== undefined;
        const hasRef = !!arg['ref-id'];
        if (hasValue || hasRef) {
          const sourceId = getRootSourceId(arg);
          if (!setArgs.has(sourceId)) {
            const argInfo = {
              argName: resolveArgName(arg),
              value: arg.value,
              refId: arg['ref-id'],
              argId: arg.id,
              sourceId: sourceId
            };
            setArgs.set(sourceId, argInfo);
            if (hasRef) {
              levelRefArgs.push(argInfo);
            } else {
              levelValueArgs.push(argInfo);
            }
          }
        }
      });

      argsByLevel.push({ refArgs: levelRefArgs, valueArgs: levelValueArgs });
    }

    // Build ordered list: for each level, refs first, then values
    // Within refs, sort by children to group nodes with shared children together
    const orderedArgs = [];
    argsByLevel.forEach(level => {
      // Sort refArgs by their children (so nodes with same children are adjacent)
      const sortedRefArgs = level.refArgs.slice().sort((a, b) => {
        // Get children of each ref target
        const aChildren = (lookups.argsByFn.get(a.refId) || [])
          .filter(arg => arg['ref-id'] || arg.value !== null && arg.value !== undefined)
          .map(arg => arg['ref-id'] || 'val-' + arg.id)
          .sort()
          .join(',');
        const bChildren = (lookups.argsByFn.get(b.refId) || [])
          .filter(arg => arg['ref-id'] || arg.value !== null && arg.value !== undefined)
          .map(arg => arg['ref-id'] || 'val-' + arg.id)
          .sort()
          .join(',');
        return aChildren.localeCompare(bChildren);
      });
      sortedRefArgs.forEach(a => orderedArgs.push(a));
      level.valueArgs.forEach(a => orderedArgs.push(a));
    });

    return { setArgs, activeFns, orderedArgs };
  }

  function getUnsetArgs(originalFnId, setArgs) {
    const chain = getInheritanceChain(originalFnId);
    const level = getEffectiveLevel(originalFnId);
    const displayFnId = chain[Math.min(level, chain.length - 1)];

    const args = lookups.argsByFn.get(displayFnId) || [];
    return args.filter(arg => {
      const hasValue = arg.value !== null && arg.value !== undefined;
      const hasRef = !!arg['ref-id'];
      if (hasValue || hasRef) return false;
      const sourceId = getRootSourceId(arg);
      return !setArgs.has(sourceId);
    }).map(arg => ({
      ...arg,
      sourceId: getRootSourceId(arg)
    }));
  }

  function addFnNode(originalFnId, isRoot) {
    const nodeId = 'fn-' + originalFnId;
    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    const chain = getInheritanceChain(originalFnId);
    const level = getEffectiveLevel(originalFnId);

    const items = buildAncestorItems(chain);
    const visibleItems = items.slice(0, MAX_VISIBLE_ANCESTORS + 1);

    const labelLines = visibleItems.map(item => item.name);
    if (items.length > MAX_VISIBLE_ANCESTORS + 1) {
      labelLines.push('...');
    }

    const label = labelLines.join('\n');

    nodes.push({
      data: {
        id: nodeId,
        label: label,
        type: 'fn',
        isRoot: isRoot,
        originalFnId: originalFnId,
        ancestorList: chain,
        currentLevel: level
      }
    });

    return nodeId;
  }

  function addArgValueNode(argInfo, sourceNodeId) {
    const { argName, value, sourceId } = argInfo;
    const nodeId = 'arg-' + sourceId;

    if (addedNodeIds.has(nodeId)) return nodeId;
    addedNodeIds.add(nodeId);

    let displayValue = JSON.stringify(value);
    if (displayValue.length > 20) {
      displayValue = displayValue.substring(0, 17) + '...';
    }

    nodes.push({
      data: {
        id: nodeId,
        label: displayValue,
        type: 'arg'
      }
    });

    edges.push({
      data: {
        id: 'e-val-' + sourceId,
        source: sourceNodeId,
        target: nodeId,
        argName: argName
      }
    });

    return nodeId;
  }

  function addUnsetArg(arg, sourceNodeId) {
    const argName = resolveArgName(arg);
    const sourceId = arg.sourceId || arg.id;
    const nodeId = 'unset-' + sourceId;

    if (addedNodeIds.has(nodeId)) return;
    addedNodeIds.add(nodeId);

    nodes.push({
      data: {
        id: nodeId,
        label: arg.type || 'any',
        type: 'fn',
        isPlaceholder: true
      }
    });

    edges.push({
      data: {
        id: 'e-unset-' + sourceId,
        source: sourceNodeId,
        target: nodeId,
        argName: argName,
        isUnset: true
      }
    });
  }

  function processRefArg(argInfo, sourceNodeId) {
    const { argName, refId } = argInfo;

    const targetNodeId = addFnNode(refId, false);

    const edgeId = 'e-ref-' + sourceNodeId + '-' + refId;

    edges.push({
      data: {
        id: edgeId,
        source: sourceNodeId,
        target: targetNodeId,
        argName: argName
      }
    });

    const { setArgs, orderedArgs } = collectSetArgs(refId);

    // Process in order: refs first, then values (per level)
    orderedArgs.forEach((info) => {
      if (info.refId) {
        processRefArg(info, targetNodeId);
      } else if (info.value !== null && info.value !== undefined) {
        addArgValueNode(info, targetNodeId);
      }
    });

    const unsetArgs = getUnsetArgs(refId, setArgs);
    unsetArgs.forEach(arg => addUnsetArg(arg, targetNodeId));
  }

  // Main: process selected fn
  const rootNodeId = addFnNode(selectedFnId, true);
  const { setArgs, orderedArgs } = collectSetArgs(selectedFnId);

  // Process in order: refs first, then values (per level)
  orderedArgs.forEach((argInfo) => {
    if (argInfo.refId) {
      processRefArg(argInfo, rootNodeId);
    } else if (argInfo.value !== null && argInfo.value !== undefined) {
      addArgValueNode(argInfo, rootNodeId);
    }
  });

  const unsetArgs = getUnsetArgs(selectedFnId, setArgs);
  unsetArgs.forEach(arg => addUnsetArg(arg, rootNodeId));

  return { nodes, edges };
}

// ============================================================================
// UI FUNCTIONS
// ============================================================================

function showFnDetails(fnId) {
  const panel = document.getElementById('details-panel');
  panel.classList.remove('hidden');
  htmx.ajax('GET', '/partials/entity-details/fn/' + fnId, '#details-content');
}

function hideNodeDetails() {
  const panel = document.getElementById('details-panel');
  if (panel) panel.classList.add('hidden');
}

function closeDetailsPanel() {
  hideNodeDetails();
}

function collapseAll() {
  expansionLevel.clear();
  previewLevel.clear();
  renderGraph(false);
}

function resetLayout() {
  userMovedNodes.clear();
  renderGraph(false);
}

function showCreateModal(entityType) {
  document.getElementById('modal-overlay').classList.remove('hidden');
  htmx.ajax('GET', '/partials/entity-form/' + entityType, '#modal-content');
}

function hideModal() {
  document.getElementById('modal-overlay').classList.add('hidden');
}

async function refreshGraph() {
  const response = await fetch('/api/graph/entities');
  graphData = await response.json();
  lookups = buildLookups(graphData);
  updateEntityList(graphData);
  renderGraph(true);
}

function fitGraph() {
  if (cy) cy.fit(50);
}

document.addEventListener('DOMContentLoaded', initGraph);
document.body.addEventListener('entityCreated', refreshGraph);
document.body.addEventListener('entityUpdated', refreshGraph);
document.body.addEventListener('entityDeleted', refreshGraph);
