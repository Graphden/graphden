// Editor State - Global variables, constants, and configuration
// BUILD_HASH is substituted at bundle-time (see :script in
// `app/editor/fns.edn`) — first 12 hex chars of the SHA-256 of the
// frontend source files at build time. Exposed on `window` so it can
// be read on demand (e.g. type `BUILD_HASH` in DevTools, or compare
// `window.BUILD_HASH` against `fetch('/version')`'s `frontend` field
// when diagnosing browser-cache vs server-deploy drift). No auto-log
// — the console stays clean unless you ask.
const BUILD_HASH = '__BUILD_HASH__';
window.BUILD_HASH = BUILD_HASH;

// ============================================================================
// GLOBAL STATE
// ============================================================================
//
// `let` (not `const`) on every binding below — these are MUTABLE
// globals reassigned from OTHER files in the bundle (editor-main,
// editor-render, editor-edit-modes, …). The editor ships as a
// single concatenated <script> rather than ES modules, so plain
// top-level `let` IS the cross-file mutable storage.
//
// Biome's per-file scope analysis can't see those reassignments and
// would flag every line below as `useConst`-eligible. `biome.json`'s
// override turns that rule off for THIS file (and `editor-ui.js`
// for the same reason); don't switch any of these to `const`
// without first confirming no other file rebinds it.

// biome-ignore lint/style/useConst: reassigned in editor-ui.js (one shared script scope)
let selectedFnId = null;          // Currently selected function ID
// biome-ignore lint/style/useConst: reassigned in editor-main.js (one shared script scope)
let graphData = null;             // Raw graph data from API
// biome-ignore lint/style/useConst: reassigned in editor-main.js, editor-edit-modes-fn.js, editor-tooltips.js (one shared script scope)
let lookups = null;               // Lookup maps (fnMap, argMap, argsByFn)
// biome-ignore lint/style/useConst: reassigned in editor-main.js (one shared script scope)
let richTypes = {};               // {fn-name → {return, args, effects, …}} from /api/types (lean bulk — finding K; on-demand detail comes from server partials, not per-fn backfills)
// biome-ignore lint/style/useConst: reassigned in editor-main.js (one shared script scope)
let VALUE_KINDS = [];             // value_kind schema enum, from /api/value-kinds

// Set of fn-ids reachable from `selectedFnId` via ref-id only — i.e.
// fns that show up in the layout WITHOUT requiring an expansion. The
// editor uses this to scope arg-level edits: anything in the
// "immediate implementation" should be editable, ancestor chains
// revealed by expansion stay read-only.
//
// Rebuilt by `rebuildImplementationFnIds()` on every graphData refresh
// (initGraph) and whenever lookups change.
let implementationFnIds = new Set();
function rebuildImplementationFnIds() {
  if (!selectedFnId 
      || !lookups?.bindingsByFn || !lookups.itemsByBinding) {
    implementationFnIds = new Set();
    return;
  }
  const seen = new Set([selectedFnId]);
  const stack = [selectedFnId];
  while (stack.length) {
    const cur = stack.pop();
    const bindings = lookups.bindingsByFn.get(cur) || [];
    for (const b of bindings) {
      const directRef = b['ref-fn-id'];
      if (directRef && !seen.has(directRef)) {
        seen.add(directRef);
        stack.push(directRef);
      }
      const items = lookups.itemsByBinding.get(b.id) || [];
      for (const it of items) {
        const itemRef = it['ref-fn-id'];
        if (itemRef && !seen.has(itemRef)) {
          seen.add(itemRef);
          stack.push(itemRef);
        }
      }
    }
  }
  implementationFnIds = seen;
}

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
const expansionState = new Map();  // nodeId -> {fullDepth, partialFns}
const previewState = new Map();    // nodeId -> {fullDepth, partialFns}
// biome-ignore lint/style/useConst: reassigned in editor-expansion.js, editor-overlay-fn-rows.js (one shared script scope)
let anchorNodeId = null;          // graph node id (full, incl. expansion prefix) that should stay stationary during layout

// UI state flags
// biome-ignore lint/style/useConst: reassigned in editor-render.js (one shared script scope)
let rebuildingOverlays = false;   // Prevents mouseleave during overlay rebuild
// biome-ignore lint/style/useConst: reassigned in editor-drag.js (one shared script scope)
let isGrabbing = false;           // True when any node is being dragged

// User-moved nodes (won't be auto-positioned by layout)
const userMovedNodes = new Set();

// Saved positions of user-moved nodes — preserved across preview renders
// so that when a preview removes a node and restoring brings it back, the
// user's manual position is retained. Cleared only on click (commit).
const savedUserPositions = new Map();  // nodeId -> {x, y}

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
const PLACEHOLDER_SIZE = 20;      // Click-target square for free-arg / empty-sequence-anchor


