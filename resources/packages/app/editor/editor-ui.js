// Editor UI - selection + navigation controls + the shared
// previewDebounceTimer used by the expansion machinery.
//
// Sidebar rendering and the spec→state→preview machine for ancestor
// row click/hover live in editor-sidebar.js and editor-expansion.js
// respectively.

// ============================================================================
// DEBOUNCE
// ============================================================================

// `let` because editor-expansion.js rebinds the timer; biome.json's
// override turns off `useConst` for this file so the rule doesn't
// fight the cross-file-mutated convention.
let previewDebounceTimer = null;
const PREVIEW_DEBOUNCE_MS = 100;

// ============================================================================
// SELECTION
// ============================================================================

/**
 * Select a function by ID
 */
function selectFn(fnId, updateHistory = true) {
  selectedFnId = fnId;
  expansionState.clear();
  previewState.clear();
  userMovedNodes.clear();
  // Recompute the editable scope so arg-overlays + edge-labels gate
  // on the new root's transitive ref closure.
  if (typeof rebuildImplementationFnIds === 'function') rebuildImplementationFnIds();

  // Ensure the fn's namespace is expanded in the sidebar tree
  const fn = lookups.fnMap.get(fnId);
  if (fn) {
    const qname = getQualifiedFnName(fn);
    const parts = qname.split('.');
    parts.pop(); // remove fn name
    let nsPath = '';
    for (const part of parts) {
      nsPath = nsPath ? nsPath + '.' + part : part;
      expandedNamespaces.add(nsPath);
    }
  }

  updateEntityList(graphData);

  // Scroll selected item into view
  const item = document.querySelector('[data-fn-id="' + fnId + '"]');
  if (item) item.scrollIntoView({ block: 'nearest' });

  if (fn && updateHistory) {
    window.history.pushState(null, '', '#' + getQualifiedFnName(fn));
  }

  renderGraph(true);
}

/**
 * Select a function by name
 */
function selectFnByName(name, updateHistory = true) {
  // Try exact simple name match first
  let fn = (graphData.fns || []).find(f => f.name === name);
  // Try qualified name match (e.g. "core.arithmetic.add")
  if (!fn && lookups) {
    fn = (graphData.fns || []).find(f => getQualifiedFnName(f) === name);
  }
  if (fn) selectFn(fn.id, updateHistory);
}


// ============================================================================
// NAVIGATION CONTROLS
// ============================================================================

const ZOOM_STEP = 0.15;

/** Zoom in (dir=1) or out (dir=-1) relative to viewport center. */
function navZoom(dir) {
  if (!cy) return;
  const newZoom = Math.max(0.1, Math.min(3, cy.zoom() + dir * ZOOM_STEP));
  cy.zoom({ level: newZoom, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  updateOverlayPositions();
  updateZoomSlider();
}

/** Zoom to an absolute level (from the slider). */
function navZoomTo(level) {
  if (!cy) return;
  const clamped = Math.max(0.1, Math.min(3, level));
  cy.zoom({ level: clamped, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  updateOverlayPositions();
}

/** Sync the slider thumb with the current zoom level. */
function updateZoomSlider() {
  const slider = document.getElementById('zoom-slider');
  if (slider && cy) slider.value = Math.round(cy.zoom() * 100);
}

/** Reset zoom to fit all nodes in viewport. */
function navResetZoom() {
  if (!cy || cy.nodes().length === 0) return;
  fitInVisibleArea(50);
  updateOverlayPositions();
  updateZoomSlider();
}

/** Pan to center the root node in the viewport. */
function navGoToRoot() {
  if (!cy) return;
  // Root = node with selectedFnId as originalFnId
  let rootNode = null;
  cy.nodes().forEach(n => {
    if (n.data('isRoot')) rootNode = n;
  });
  if (!rootNode) rootNode = cy.nodes().first();
  if (rootNode?.length) {
    cy.animate({ center: { eles: rootNode }, duration: 200 });
    setTimeout(() => { updateOverlayPositions(); updateZoomSlider(); }, 250);
  }
}

/** Reset all user-moved nodes to their layout positions. */
function navResetPositions() {
  if (!cy) return;
  userMovedNodes.clear();
  savedUserPositions.clear();
  renderGraph(false);
}
