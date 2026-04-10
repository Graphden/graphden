// Editor State - Global variables, constants, and configuration
// Build timestamp (UTC+3) - update on each frontend change
const BUILD_TIMESTAMP = '2026-04-11 15:25';
console.log('%c[Graphden Editor] Build: ' + BUILD_TIMESTAMP, 'color: #0066cc; font-weight: bold');

// ============================================================================
// GLOBAL STATE
// ============================================================================

let cy = null;                    // Cytoscape instance
let selectedFnId = null;          // Currently selected function ID
let graphData = null;             // Raw graph data from API
let lookups = null;               // Lookup maps (fnMap, argMap, argsByFn)

// Expansion state — per node, holds an expansion spec.
// spec: {fullDepth: number, partialFns: Set<fnId>}
//   fullDepth: BFS depths 1..fullDepth are FULLY expanded (cascade)
//   partialFns: at depth fullDepth+1, additional fns individually expanded
//
// Click semantics on an fn at depth L:
//   L <= fullDepth          → collapse: fullDepth = L - 1, partial empty
//   L === fullDepth + 1     → toggle this fn in partial
//                              (when partial covers all fns at L,
//                              promote to fullDepth = L, partial empty)
//   L > fullDepth + 1       → cascade: fullDepth = L - 1, partial = {fn}
let expansionState = new Map();  // nodeId -> {fullDepth, partialFns}
let previewState = new Map();    // nodeId -> {fullDepth, partialFns}
let anchorFnId = null;            // fnId that should stay stationary during layout

// UI state flags
let rebuildingOverlays = false;   // Prevents mouseleave during overlay rebuild
let isGrabbing = false;           // True when any node is being dragged
let suppressEdgeWarnings = false; // Suppresses Cytoscape edge warnings during drag

// User-moved nodes (won't be auto-positioned by layout)
let userMovedNodes = new Set();

// Saved positions of user-moved nodes — preserved across preview renders
// so that when a preview removes a node and restoring brings it back, the
// user's manual position is retained. Cleared only on click (commit).
let savedUserPositions = new Map();  // nodeId -> {x, y}

// Hover-preview suppression after click. After a click commits a state
// change, the overlay rebuilds and a synthetic mouseenter fires on the new
// line at the SAME pointer position. Without suppression, this immediately
// shows a "ghost" collapse-preview. We suppress preview for a brief window
// after click so the committed state is visible. After the window, hover
// preview works normally.
// After a click commits a state change, suppress hover-preview until the
// cursor LEAVES the element. This prevents the "ghost preview" where the
// committed expansion is immediately hidden by a reverse-preview.
// The flag is global (survives overlay rebuild) and cleared by mouseleave.
let suppressPreviewUntilLeave = false;

function shouldSuppressPreview() {
  return suppressPreviewUntilLeave;
}
function suppressPreviewOnClick() {
  suppressPreviewUntilLeave = true;
}
function onPreviewLeave() {
  suppressPreviewUntilLeave = false;
}

// ============================================================================
// CONSTANTS
// ============================================================================

const MAX_VISIBLE_ANCESTORS = 4;  // Max ancestors shown in node overlay
const ANIM_DURATION = 200;        // Animation duration in ms

// Grid layout constants
const GRID_GAP_X = 80;            // Horizontal gap between columns
const GRID_GAP_Y = 40;            // Vertical gap between rows
const DRAG_HANDLE_HEIGHT = 14;    // Height of drag handle at bottom of nodes


// ============================================================================
// CONSOLE WARNING SUPPRESSION
// ============================================================================

(function() {
  const originalWarn = console.warn;
  console.warn = function(...args) {
    if (args[0] && typeof args[0] === 'string' &&
        args[0].includes('invalid endpoints')) {
      return; // Suppress Cytoscape edge warnings (source/target overlap)
    }
    originalWarn.apply(console, args);
  };
})();
