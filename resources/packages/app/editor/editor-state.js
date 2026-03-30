// Editor State - Global variables, constants, and configuration
// Build timestamp (UTC+3) - update on each frontend change
const BUILD_TIMESTAMP = '2026-03-31 05:00';
console.log('%c[Graphden Editor] Build: ' + BUILD_TIMESTAMP, 'color: #0066cc; font-weight: bold');

// ============================================================================
// GLOBAL STATE
// ============================================================================

let cy = null;                    // Cytoscape instance
let selectedFnId = null;          // Currently selected function ID
let graphData = null;             // Raw graph data from API
let lookups = null;               // Lookup maps (fnMap, argMap, argsByFn)

// Expansion state
let expansionLevel = new Map();   // originalFnId -> number of ancestors to show
let previewLevel = new Map();     // originalFnId -> preview level (hover)

// UI state flags
let rebuildingOverlays = false;   // Prevents mouseleave during overlay rebuild
let isGrabbing = false;           // True when any node is being dragged
let suppressEdgeWarnings = false; // Suppresses Cytoscape edge warnings during drag

// User-moved nodes (won't be auto-positioned by layout)
let userMovedNodes = new Set();

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
    if (suppressEdgeWarnings && args[0] && typeof args[0] === 'string' &&
        args[0].includes('invalid endpoints')) {
      return; // Suppress Cytoscape edge warnings during drag
    }
    originalWarn.apply(console, args);
  };
})();
