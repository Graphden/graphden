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
 * Select a function by name (simple `add` or qualified `core.arithmetic.add`).
 * Resolves against the loaded fn cache first, then — for a deep-link /
 * bookmark to a fn outside the loaded set — via the server (names are
 * globally unique). Async because that resolution may hit the network.
 */
async function selectFnByName(name, updateHistory = true) {
  // Fast path: already-loaded fn (simple or qualified name).
  let fn = (graphData.fns || []).find(f => f.name === name);
  if (!fn && lookups) {
    fn = (graphData.fns || []).find(f => getQualifiedFnName(f) === name);
  }
  // Slow path: resolve by name via the server. The resolver itself
  // understands slash-qualified (ns.path/name, incl. the root "/foo"
  // spelling), legacy dotted, and bare forms — stripping to the last
  // segment here used to defeat disambiguation for duplicated names.
  if (!fn && typeof resolveFnByName === 'function') {
    try {
      const resolved = await resolveFnByName(name);
      if (resolved) fn = resolved;
    } catch (err) {
      // eslint-disable-next-line no-console
      console.error('selectFnByName resolution failed', err);
    }
  }
  if (fn) {
    selectFn(fn.id, updateHistory);
    // selectFn kicks renderGraph → ensureSubtreeFor but doesn't await it.
    // Await the subtree here (idempotent — cached by root) so callers that
    // `await selectFnByName(...)` — notably initGraph's hash nav — return
    // with the fn's slots/bindings + closure actually loaded, not still
    // in flight.
    if (typeof ensureSubtreeFor === 'function') {
      try { await ensureSubtreeFor(fn.id); }
      catch (err) {
        // eslint-disable-next-line no-console
        console.error('selectFnByName subtree load failed', err);
      }
    }
  }
}


// ============================================================================
// NAVIGATION CONTROLS
// ============================================================================

const ZOOM_STEP = 0.15;

/** Zoom in (dir=1) or out (dir=-1) relative to viewport center. */
function navZoom(dir) {
  if (!gv.ready()) return;
  const newZoom = Math.max(0.1, Math.min(3, gv.zoom() + dir * ZOOM_STEP));
  gv.setZoom(newZoom, viewportCentre());
  applyViewportTransform();
  updateZoomSlider();
}

/** Zoom to an absolute level (from the slider). */
function navZoomTo(level) {
  if (!gv.ready()) return;
  const clamped = Math.max(0.1, Math.min(3, level));
  gv.setZoom(clamped, viewportCentre());
  // Slider `oninput` fires per pixel of thumb travel — the overlays' graph
  // coordinates haven't changed, only the viewport, so this stays O(1).
  applyViewportTransform();
}

/** The point the zoom controls hold fixed: the middle of the drawing surface. */
function viewportCentre() {
  return { x: gv.width() / 2, y: gv.height() / 2 };
}

/** Sync the slider thumb with the current zoom level. */
function updateZoomSlider() {
  const slider = document.getElementById('zoom-slider');
  if (slider && gv.ready()) slider.value = Math.round(gv.zoom() * 100);
}

/** Reset zoom to fit all nodes in viewport. */
function navResetZoom() {
  if (!gv.ready() || gv.nodes().length === 0) return;
  fitInVisibleArea(50);
  applyViewportTransform();
  updateZoomSlider();
}

/** Pan to center the root node in the viewport. */
function navGoToRoot() {
  if (!gv.ready()) return;
  const nodes = gv.nodes();
  if (nodes.length === 0) return;
  // Root = node with selectedFnId as originalFnId
  const rootNode = nodes.find(n => n.data('isRoot')) || nodes[0];
  gv.centerOn(rootNode, 200);
  setTimeout(() => { applyViewportTransform(); updateZoomSlider(); }, 250);
}

/** Reset all user-moved nodes to their layout positions. */
function navResetPositions() {
  if (!gv.ready()) return;
  userMovedNodes.clear();
  savedUserPositions.clear();
  renderGraph(false);
}
