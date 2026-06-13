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

// Metadata strips appended below ancestor rows by
// `appendFnMetadataStrips` (editor-overlays.js):
//   - return-type   ("→ <type>")     — render condition mirrored below
//   - effects       chips of categories
//   - "set parent…" (rtEditable AND fn has no parents)
// Each strip is ~17px tall. The use-site header for non-root non-local
// cards is one full ancestor-line worth (~21px). Without these
// additions row-height computation underestimates fn-card height and
// taller cards bleed into the row below.
//
// Namespace surface moved into a left-pinned `ns` badge on every
// fn-name row, so it no longer occupies a dedicated strip.
const METADATA_STRIP_HEIGHT = 17;
const USE_SITE_HEADER_HEIGHT = 21;

// Mirror appendFnMetadataStrips' decisions for the height-only side:
// return whether each strip will render. Conservative — when a check
// would need data not on `nodeData`, we assume the strip CAN render
// (over-reserves a few px rather than under-reserving and overlapping).
function metadataStripsHeight(nodeData) {
  const fnId = nodeData.originalFnId;
  if (!fnId) return 0;
  const fn = (typeof lookups !== 'undefined' && lookups && lookups.fnMap)
    ? lookups.fnMap.get(fnId) : null;
  if (!fn) return 0;
  let total = 0;
  // The return-type strip renders when EITHER the row is editable
  // (always shown to expose an "add return-type" affordance), OR when
  // there's an explicit `:return-type` on the entity, OR when
  // `richTypes` carries a computed entry for this name.
  const hasRtEntry = !!fn.name
    && typeof richTypes === 'object' 
    && richTypes?.[fn.name] && richTypes[fn.name].return != null;
  const isNavRoot = !nodeData.isPlaceholder && nodeData.isRoot;
  const rtEditable = isNavRoot
    && (typeof isFnEditable === 'function' && isFnEditable(fnId))
    && (typeof isAuthenticated === 'function' && isAuthenticated());
  if (fn['return-type'] || rtEditable || hasRtEntry) {
    total += METADATA_STRIP_HEIGHT;
  }
  // Effects strip — present iff the rich-type registry knows of either
  // computed or declared effects for this fn.
  if (fn.name && typeof richTypes === 'object' && richTypes) {
    const re = richTypes[fn.name];
    const eff = (re && Array.isArray(re.effects)) ? re.effects : [];
    const decl = (re && Array.isArray(re['expects-effects'])) ? re['expects-effects'] : [];
    if (eff.length || decl.length) total += METADATA_STRIP_HEIGHT;
  }
  // "set parent…" strip — only when the editable nav root has zero
  // parents (otherwise the depth-1 ancestor row already exposes a ✎).
  if (rtEditable) {
    const pids = fn['parent-ids'] || [];
    if (pids.length === 0) total += METADATA_STRIP_HEIGHT;
  }
  return total;
}

function calculateNodeSize(nodeData) {
  const label = nodeData.label || '';
  const type = nodeData.type;
  const isPlaceholder = nodeData.isPlaceholder;

  if (isPlaceholder) {
    // The type-chip on the inbound edge already shows what the slot
    // expects, so the placeholder itself collapses to a small click
    // target (binder for free args, `+` for empty sequence anchors).
    return { width: PLACEHOLDER_SIZE, height: PLACEHOLDER_SIZE };
  }

  if (type === 'arg') {
    const maxLen = 30;
    const effectiveLen = Math.min(label.length, maxLen);
    // arg-overlay is column-flex: row of (value text + type-chip +
    // inline-expand trigger + optional mismatch badge), then the
    // drag handle below. The horizontal budget only needs to cover
    // the inline row; the drag handle's height already lives in
    // DRAG_HANDLE_HEIGHT.
    const chipBudget = 38;     // `int` / `text` / `bool` etc.
    const triggerBudget = 14;  // `▸/▾` trigger
    return {
      width: Math.max(40, effectiveLen * 6 + 16 + chipBudget + triggerBudget),
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
    // Per-MI-cell floor so each cell still fits at least one icon-pair
    // worth of slack even when the names are very short. Without this,
    // a 3-cell MI row of `r404, r405, r500` collapses each cell to ~50px
    // and even the 4-character name truncates to "r…".
    const MIN_MI_CELL = 90;
    const widthFromMI = Math.max(...lines.map(l => {
      if (!l.includes(', ')) return 0;
      return l.split(', ').length * MIN_MI_CELL;
    }));
    // Per-row affordances now live in a popover OUTSIDE the card
    // (see editor-row-actions.js). The only chrome inside the card
    // is the `⋯` more-actions trigger pinned at slot r-1 (≈18 px) +
    // the row's symmetric breathing room (8 + 8 ≈ 16 px). 36 covers
    // it with a few pixels of slack so names hug the trigger.
    const iconBudget = 36;
    const width = Math.max(80, maxLen * 7 + iconBudget, widthFromOptional, widthFromMI);
    // `appendUseSiteHeader` prepends one extra row to non-nav-root
    // overlays whose fn has a global name (it skips local / anonymous
    // fns). Mirror the condition so row-height accounts for it.
    const ownFn = (typeof lookups !== 'undefined' && lookups
                   && lookups.fnMap && nodeData.originalFnId)
                  ? lookups.fnMap.get(nodeData.originalFnId) : null;
    const isLocalFn = !(ownFn?.name);
    const useSiteRow = (!nodeData.isRoot && !isLocalFn) ? USE_SITE_HEADER_HEIGHT : 0;
    const optionalExtra = optionalText ? OPTIONAL_STRIP_HEIGHT : 0;
    const stripsExtra = isPlaceholder ? 0 : metadataStripsHeight(nodeData);
    const height = Math.max(30 + DRAG_HANDLE_HEIGHT,
                            computeFnOverlayHeight(label, width)
                            + useSiteRow
                            + optionalExtra
                            + stripsExtra);
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
    // 1. Longest edge label crossing each column boundary, INCLUDING the
    //    type-chip / sequence buttons / description badge that the
    //    overlays render alongside the label text. Without this the
    //    column gap is sized for the bare argName and the chips spill
    //    into the next column.
    // 2. Width spread in the column (max - min): wide nodes extend past
    //    narrow ones, so edges from narrow nodes start further left,
    //    needing more gap for labels.
    const colGaps = new Map();
    const CHAR_WIDTH = 9;
    const LABEL_PADDING = 30;
    // Chip widths stay in lock-step with editor-styles.css. The chip
    // itself is always rendered (read-only or editable); its visible
    // text depends on the resolved type — short for primitives
    // (`int`, `text`), long for fn-types (`(request)→ring-res…`).
    // We compute the actual chip text via `compactTypeChipText` per
    // edge so columns hosting fn-typed args reserve enough room for
    // the chip to fit between source-card and target-card without
    // crossing either.
    const CHIP_CHAR_PX     = 6;   // SF Mono 9px ≈ 5.5–6 px per char
    const CHIP_CHROME_PX   = 25;  // padding 8 + border 2 + margin 4 + slack
    const TRIGGER_WIDTH    = 16;  // `▸ / ▾` inline-expand trigger
    const SEQ_BTN_WIDTH    = 18;  // × or +
    const DESC_BADGE_WIDTH = 19;  // 15 + 4 margin
    edges.forEach(e => {
      const srcPos = gridPos[e.data.source];
      const tgtPos = gridPos[e.data.target];
      if (srcPos && tgtPos && e.data.argName) {
        const labelCol = Math.min(srcPos.col, tgtPos.col);
        const widestLine = e.data.argName.split('\n').reduce(
          (max, line) => Math.max(max, line.length), 0);
        const editArg = (typeof argRowFromNode === 'function')
                        ? argRowFromNode(e.data) : null;
        const editable = editArg
                      && typeof implementationFnIds !== 'undefined'
                      && implementationFnIds.has(editArg['fn-id'])
                      && (typeof isAuthenticated === 'function'
                          ? isAuthenticated() : true);
        // Estimate the chip's visible text width — the chip's text is
        // produced by `compactTypeChipText(rich, flat)`, so reserve
        // the same width here. Fall back to a short default when the
        // helpers aren't loaded yet (e.g. first paint before
        // editor-overlay-arg.js is fully wired).
        const rich = (editArg && typeof expectedSlotType === 'function')
                     ? expectedSlotType(editArg) : null;
        const flat = editArg?.type
                     ? String(editArg.type).replace(/^:/, '')
                     : 'any';
        const chipText = (typeof compactTypeChipText === 'function')
                         ? compactTypeChipText(rich, flat)
                         : flat;
        const chipChars = (chipText || '').length;
        let chipOverhead = DESC_BADGE_WIDTH
                         + (chipChars * CHIP_CHAR_PX + CHIP_CHROME_PX);
        if (editable) {
          chipOverhead += TRIGGER_WIDTH;
          if (e.data.sourcePrevArgId) {
            chipOverhead += SEQ_BTN_WIDTH;                              // ×
            if (!e.data.sourceNextArgId) chipOverhead += SEQ_BTN_WIDTH; // tail +
          }
        }
        const labelWidth = widestLine * CHAR_WIDTH + LABEL_PADDING + chipOverhead;
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
