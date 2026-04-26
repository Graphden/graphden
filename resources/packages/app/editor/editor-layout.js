// Editor Layout - Backend API client for graph layout
// Depends on: editor-state.js (GRID_GAP_X, GRID_GAP_Y, DRAG_HANDLE_HEIGHT, selectedFnId, expansionState, previewState)
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

// Compute the height of a fn-overlay row given its width and content. MI rows
// (comma-separated names) split into N flex cells of width/N each; each cell
// wraps its name to multiple lines if the name is wider than the cell content
// area. The overlay height = sum of row heights + drag handle.
function computeFnOverlayHeight(label, _width) {
  // All ancestor rows render single-line (white-space:nowrap +
  // text-overflow:ellipsis); the full name is revealed on hover via
  // the floating full-name popover. Height is therefore
  // line-count × per-line-height, no per-cell wrap math.
  const LINE_H = 13;     // 11px font @ ~1.2 line-height
  const ROW_PAD = 8;     // 4px top + 4px bottom inside each ancestor-line
  const lines = label.split('\n');
  return lines.length * (LINE_H + ROW_PAD) + DRAG_HANDLE_HEIGHT;
}

// Optional-args strip rendered by editor-overlays.js right above the drag
// handle: 1px top border + italic text at 10px with 2px vertical padding.
const OPTIONAL_STRIP_HEIGHT = 17;

function calculateNodeSize(nodeData) {
  const label = nodeData.label || '';
  const type = nodeData.type;
  const isPlaceholder = nodeData.isPlaceholder;

  if (type === 'arg') {
    const maxLen = 30;
    const effectiveLen = Math.min(label.length, maxLen);
    return {
      width: Math.max(40, effectiveLen * 6 + 16),
      height: 22 + DRAG_HANDLE_HEIGHT  // content (padding 4+4 + line 14) + drag handle
    };
  } else {
    // Both fn and placeholder use fn-overlay rendering with potential MI rows.
    // Cap visible row width — names beyond this are truncated with an
    // ellipsis at render time and revealed in full via the hover popover.
    const lines = label.split('\n');
    const maxLineLen = 36;
    const maxLen = Math.max(...lines.map(l => {
      const cleanLen = l.replace(/[^\x20-\x7E]/g, '').length;
      return Math.min(cleanLen, maxLineLen);
    }));
    const optionalArgs = nodeData.optionalArgs;
    const optionalText = Array.isArray(optionalArgs) && optionalArgs.length
      ? optionalArgs.map(n => '?' + n).join(' ')
      : '';
    const widthFromOptional = optionalText ? optionalText.length * 6 + 24 : 0;
    // Slack budget = inner padding + room for the right-pinned action
    // icons (i + ↗ ≈ 42px) so the longest full name actually fits and
    // doesn't get prematurely ellipsised.
    const width = Math.max(80, maxLen * 7 + 60, widthFromOptional);
    const extra = optionalText ? OPTIONAL_STRIP_HEIGHT : 0;
    const height = Math.max(30 + DRAG_HANDLE_HEIGHT,
                            computeFnOverlayHeight(label, width) + extra);
    return { width, height };
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
    // Build expansions map: merge expansionState and previewState
    // previewState takes priority
    // Each entry is a spec: {full-depth: number, partial-fns: [fnId, ...]}
    const expansions = {};
    const specToWire = (spec) => ({
      'full-depth': spec.fullDepth,
      'partial-fns': Array.from(spec.partialFns || [])
    });
    expansionState.forEach((spec, nodeId) => {
      expansions[nodeId] = specToWire(spec);
    });
    previewState.forEach((spec, nodeId) => {
      expansions[nodeId] = specToWire(spec);
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

    // Calculate min node width per column (for width spread compensation)
    const colMinWidths = new Map();
    Object.entries(gridPos).forEach(([nodeId, pos]) => {
      const size = sizes.get(nodeId);
      if (size) {
        const currentMin = colMinWidths.get(pos.col);
        colMinWidths.set(pos.col, currentMin === undefined ? size.width : Math.min(currentMin, size.width));
      }
    });

    // Calculate per-column gap based on:
    // 1. Longest edge label crossing each column boundary
    // 2. Width spread in the column (max - min): wide nodes extend past narrow ones,
    //    so edges from narrow nodes start further left, needing more gap for labels
    const colGaps = new Map();
    const CHAR_WIDTH = 9;
    const LABEL_PADDING = 30;
    edges.forEach(e => {
      const srcPos = gridPos[e.data.source];
      const tgtPos = gridPos[e.data.target];
      if (srcPos && tgtPos && e.data.argName) {
        const labelCol = Math.min(srcPos.col, tgtPos.col);
        // Use widest line for multi-line labels (\n-separated)
        const widestLine = e.data.argName.split('\n').reduce(
          (max, line) => Math.max(max, line.length), 0);
        const labelWidth = widestLine * CHAR_WIDTH + LABEL_PADDING;
        const currentGap = colGaps.get(labelCol) || GRID_GAP_X;
        colGaps.set(labelCol, Math.max(currentGap, labelWidth));
      }
    });

    // Calculate X positions with per-column gaps + width spread compensation.
    // Skip empty columns entirely — the backend reserves some cells for edge
    // routing but those produce no nodes, so padding them with a default width
    // wastes horizontal space.
    const colLeftX = new Map();
    const usedCols = Array.from(colWidths.keys()).sort((a, b) => a - b);
    let currentX = 0;
    usedCols.forEach(c => {
      colLeftX.set(c, currentX);
      const maxWidth = colWidths.get(c);
      const minWidth = colMinWidths.get(c) || maxWidth;
      const widthSpread = Math.max(0, (maxWidth - minWidth) / 2);
      const gap = colGaps.get(c) || GRID_GAP_X;
      currentX += maxWidth + Math.max(gap, gap + widthSpread);
    });

    // Calculate Y positions. Skip empty rows — they happen when the matrix
    // solver leaves gaps between subtrees, and rendering them at the default
    // 30px + gap wastes vertical space (seen as a big void between top row
    // and the rest of the graph).
    const rowCenterY = new Map();
    let currentY = 0;
    const usedRows = Array.from(rowHeights.keys()).sort((a, b) => a - b);
    usedRows.forEach(r => {
      const height = rowHeights.get(r);
      rowCenterY.set(r, currentY + height / 2);
      currentY += height + GRID_GAP_Y;
    });

    // Build final layout
    // Left-align all nodes: x = leftX + nodeWidth/2
    // Overlay positions as: left = x*zoom + pan.x - nodeWidth/2*zoom = leftX*zoom + pan.x
    // This gives same left edge for all nodes in column regardless of width
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

    // Augment node data with colRightX (right edge of node's column).
    // Used by edge taxi-turn so the bend lands in the inter-column gap,
    // never inside a wider sibling node sharing the same column.
    // Also store computed layoutWidth/layoutHeight so CY node sizing matches
    // the actual overlay rendering (avoids overlay overflow into next row).
    nodes.forEach(n => {
      const pos = gridPos[n.data.id];
      if (pos) {
        n.data.colRightX = colLeftX.get(pos.col) + (colWidths.get(pos.col) || 0);
      }
      const size = sizes.get(n.data.id);
      if (size) {
        n.data.layoutWidth = size.width;
        n.data.layoutHeight = size.height;
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
