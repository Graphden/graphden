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

  // Redesign 2026-08: reflect the selection in the right inspector.
  if (typeof gdInspectorRender === 'function') gdInspectorRender(fnId);

  // Scroll selected item into view
  const item = document.querySelector('[data-fn-id="' + fnId + '"]');
  if (item) item.scrollIntoView({ block: 'nearest' });

  if (fn && updateHistory) {
    window.history.pushState(null, '', '#' + getQualifiedFnName(fn));
  }

  // Selecting a fn rebuilds the canvas AND the inspector without moving
  // focus, so a screen reader gets no signal that the entire working context
  // just changed. Polite: it follows a deliberate user action, it is not an
  // interruption.
  if (fn && typeof window.gdAnnounce === 'function') {
    window.gdAnnounce(getQualifiedFnName(fn) + ' selected');
  }

  renderGraph(true);
}

/**
 * Select a function by name (simple `add` or qualified `core.arithmetic.add`).
 * Resolves against the loaded fn cache first, then — for a deep-link /
 * bookmark to a fn outside the loaded set — via the server (names are
 * globally unique). Async because that resolution may hit the network.
 */
// Select a fn the user JUST created. The create answered 2xx, so a resolve
// miss here is a stale read — not a wrong name: the org-scoped read path can
// trail its own write by a few hundred milliseconds, and a single attempt
// turned "created" into the toast “Function not found: <the name you just
// typed>”. Reproduced on a tenancy stack while walking tutorial lesson 26,
// where it also stalled the lesson: the tour was waiting for the fn to be
// selected. Retry briefly, then fall through to the normal select so a
// genuine miss still reports itself.
async function selectJustCreatedFn(name, tries = 12, gapMs = 250) {
  if (!name) return;
  for (let i = 0; i < tries; i++) {
    if (typeof resolveFnByName !== 'function') break;
    try {
      if (await resolveFnByName(name)) break;
    } catch (_) { /* keep trying — the write already succeeded */ }
    await new Promise((r) => setTimeout(r, gapMs));
  }
  if (typeof selectFnByName === 'function') await selectFnByName(name);
}
window.selectJustCreatedFn = selectJustCreatedFn;


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
  // Display-name fallback: the tree shows private names WITHOUT their
  // `_` prefix (displayLabel), so a name a user copied from the UI may
  // be the stripped form. Retry with `_` on the last segment before
  // declaring not-found — the canonical name stays authoritative.
  if (!fn && typeof name === 'string' && name
      && !name.split(/[./]/).pop().startsWith('_')) {
    const privatized = name.replace(/([^./]+)$/, '_$1');
    fn = (graphData.fns || []).find(f => f.name === privatized)
      || (lookups
          ? (graphData.fns || []).find(f => getQualifiedFnName(f) === privatized)
          : null);
    if (!fn && typeof resolveFnByName === 'function') {
      try { fn = await resolveFnByName(privatized); } catch (_) { /* below */ }
    }
  }
  if (!fn) {
    if (typeof gdToast === 'function') {
      // Silent-failure was worse than any message: the URL updated but
      // the canvas kept the previous fn, with no hint why.
      gdToast('Function not found: ' + name);
    }
    // A dead hash (fn lived only on a deleted branch, was renamed, …)
    // must not survive in the URL — a reload or share would repeat the
    // silent-empty canvas.
    try {
      const cur = decodeURIComponent((location.hash || '').replace(/^#/, ''));
      if (cur && cur === name) {
        history.replaceState(null, '', location.pathname + location.search);
      }
    } catch (_) { /* URL untouched */ }
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
// TOAST — minimal transient notice (bottom-center, auto-fades).
// ============================================================================

let _gdToastEl = null;
let _gdToastTimer = null;
// `kind` is 'error' for a notice the reader must not miss: it is styled as a
// failure and stays twice as long, because such a message names something
// that did NOT happen and usually what to do about it.
function gdToast(message, kind) {
  if (!_gdToastEl) {
    _gdToastEl = document.createElement('div');
    _gdToastEl.setAttribute('aria-live', 'polite');
    document.body.appendChild(_gdToastEl);
  }
  const failure = kind === 'error';
  _gdToastEl.className = 'gd-toast' + (failure ? ' gd-toast-error' : '');
  _gdToastEl.setAttribute('role', failure ? 'alert' : 'status');
  _gdToastEl.textContent = message;
  _gdToastEl.classList.add('gd-toast-visible');
  if (_gdToastTimer) clearTimeout(_gdToastTimer);
  _gdToastTimer = setTimeout(() => {
    _gdToastEl.classList.remove('gd-toast-visible');
  }, failure ? 8000 : 3500);
}
window.gdToast = gdToast;

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

/** Fit all nodes in the viewport (the ⊙ nav button). */
function navResetZoom() {
  if (!gv.ready() || gv.nodes().length === 0) return;
  fitInVisibleArea(50);
  applyViewportTransform();
  updateZoomSlider();
}

/**
 * Refit ONLY when part of the graph lies outside the visible viewport —
 * called after an expansion reveals new nodes, so they never land
 * off-screen, while a graph that already fits keeps the user's pan/zoom.
 */
function fitGraphIfOverflowing() {
  if (typeof gv === 'undefined' || !gv.ready() || gv.nodes().length === 0) return;
  const bb = (typeof graphBoundingBox === 'function') ? graphBoundingBox() : null;
  const surface = (typeof viewportContainer === 'function') ? viewportContainer() : null;
  if (!bb || !surface || bb.w <= 0) return;
  const z = viewport.zoom;
  const x1 = bb.x1 * z + viewport.pan.x;
  const y1 = bb.y1 * z + viewport.pan.y;
  const x2 = bb.x2 * z + viewport.pan.x;
  const y2 = bb.y2 * z + viewport.pan.y;
  const m = 8; // px tolerance — don't refit over a sliver
  const fits = x1 >= -m && y1 >= -m
            && x2 <= surface.clientWidth + m
            && y2 <= surface.clientHeight + m;
  if (!fits) navResetZoom();
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
