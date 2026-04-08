// Editor Layout - Backend API client for graph layout
// Depends on: editor-state.js (GRID_GAP_X, GRID_GAP_Y, DRAG_HANDLE_HEIGHT, selectedFnId, expansionLevel, previewLevel)
//
// Layout is computed on the backend via POST /api/graph/layout
// Backend handles:
// 1. Fetching data from DB
// 2. Building graph elements with expansions
// 3. Computing grid layout
//
// This file handles:
// 1. Sending root-id + expansions to backend
// 2. Converting grid positions to pixel coordinates
// 3. Calculating node sizes for rendering

// ============================================================================
// NODE SIZE CALCULATION
// ============================================================================

function calculateNodeSize(nodeData) {
  const label = nodeData.label || '';
  const type = nodeData.type;
  const isPlaceholder = nodeData.isPlaceholder;

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

// ============================================================================
// BACKEND LAYOUT API
// ============================================================================

/**
 * Fetch layout from backend API
 * @returns {Promise<{nodes: Array, edges: Array, layout: Map}>}
 *   - nodes: Array of {data: {id, label, type, ...}}
 *   - edges: Array of {data: {id, source, target, argName, ...}}
 *   - layout: Map nodeId -> {x, y, width, height, row, col}
 */
async function fetchBackendLayout() {
  if (!selectedFnId) {
    return { nodes: [], edges: [], layout: new Map() };
  }

  try {
    // Build expansions map: merge expansionLevel and previewLevel
    // previewLevel takes priority
    const expansions = {};
    expansionLevel.forEach((level, fnId) => {
      expansions[fnId] = level;
    });
    previewLevel.forEach((level, fnId) => {
      expansions[fnId] = level;
    });

    const requestBody = {
      'root-id': selectedFnId,
      expansions: expansions
    };

    const response = await fetch('/api/graph/layout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody)
    });

    if (!response.ok) {
      console.error('Backend layout API failed:', response.status);
      return null;
    }

    const data = await response.json();

    // Handle different naming conventions: 'grid-pos' (Clojure kebab-case)
    const gridPos = data['grid-pos'] || data.gridPos || {};
    const nodes = data.nodes || [];
    const edges = data.edges || [];

    if (nodes.length === 0) {
      return { nodes: [], edges: [], layout: new Map() };
    }

    // Calculate sizes for all nodes
    const sizes = new Map();
    nodes.forEach(n => {
      sizes.set(n.data.id, calculateNodeSize(n.data));
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

    // Calculate per-column gap based on longest edge label crossing each column boundary
    // Edge labels (argName) sit between source and target nodes
    const colGaps = new Map(); // col -> extra gap needed for edge labels
    const CHAR_WIDTH = 9;
    const LABEL_PADDING = 30;
    edges.forEach(e => {
      const srcPos = gridPos[e.data.source];
      const tgtPos = gridPos[e.data.target];
      if (srcPos && tgtPos && e.data.argName) {
        // Edge goes from srcPos.col to tgtPos.col
        // Label sits after the source column
        const labelCol = Math.min(srcPos.col, tgtPos.col);
        const labelWidth = e.data.argName.length * CHAR_WIDTH + LABEL_PADDING;
        const currentGap = colGaps.get(labelCol) || GRID_GAP_X;
        colGaps.set(labelCol, Math.max(currentGap, labelWidth));
      }
    });

    // Calculate X positions with per-column gaps
    const colLeftX = new Map();
    const maxColKey = Math.max(...Array.from(colWidths.keys()), 0);
    let currentX = 0;
    for (let c = 0; c <= maxColKey; c++) {
      colLeftX.set(c, currentX);
      const width = colWidths.get(c) || 80;
      const gap = colGaps.get(c) || GRID_GAP_X;
      currentX += width + gap;
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

    return { nodes, edges, layout };
  } catch (error) {
    console.error('Backend layout fetch error:', error);
    return null;
  }
}

// ============================================================================
// EXPORTS (for Node.js testing)
// ============================================================================

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    calculateNodeSize,
    fetchBackendLayout
  };
}
