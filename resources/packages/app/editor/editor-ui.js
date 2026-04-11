// Editor UI - Sidebar, selection, expansion controls
// Depends on: editor-state.js, editor-data.js, editor-cytoscape.js

// ============================================================================
// DEBOUNCE
// ============================================================================

let previewDebounceTimer = null;
const PREVIEW_DEBOUNCE_MS = 100;

// ============================================================================
// SIDEBAR / ENTITY LIST
// ============================================================================

/**
 * Update the entity list in sidebar
 */
function updateEntityList(data) {
  const list = document.getElementById('entity-list');
  list.innerHTML = '';

  (data.fns || []).forEach(fn => {
    const li = document.createElement('li');
    li.className = 'entity-item';
    if (fn.id === selectedFnId) li.className += ' selected';
    li.dataset.fnId = fn.id;
    const qname = getQualifiedFnName(fn);
    li.innerHTML = '<div class="name">' + qname + '</div>';
    li.onclick = () => selectFn(fn.id);
    list.appendChild(li);
  });

  if (list.children.length === 0) {
    list.innerHTML = '<li class="loading">No functions found</li>';
  }
}

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

  document.querySelectorAll('.entity-item').forEach(el => el.classList.remove('selected'));
  const item = document.querySelector('[data-fn-id="' + fnId + '"]');
  if (item) item.classList.add('selected');

  const fn = lookups.fnMap.get(fnId);
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
// EXPANSION CONTROL
// ============================================================================

/**
 * Get the current spec for a node, defaulting to empty (no expansion).
 */
function getSpec(nodeId) {
  return expansionState.get(nodeId) || { fullDepth: 0, partialFns: new Set() };
}

/**
 * Apply a click/hover on an ancestor row.
 *
 * SINGLE-FN at depth L → spec = {fullDepth: L, partial: empty}
 *   "expand exactly to L" (always set, no toggle)
 *
 * MULTI-FN parent (MI) → toggle membership in partial:
 *   - Currently NOT in expansion: ADD this fn (cascade through shallower
 *     levels first if needed). If after adding, partial covers ALL MI fns
 *     at that depth, auto-promote to fullDepth = depth (clear partial).
 *   - Currently IS in expansion: REMOVE this fn. If was fully expanded,
 *     unpromote to {fullDepth: depth - 1, partial: (other MI fns)}.
 *     Also collapses anything deeper than this depth (since deeper required
 *     this fn as part of its cascade).
 *
 * This lets the user select ONE OR SEVERAL MI parents but not all.
 * Selecting a deeper non-MI level cascades (auto-includes all MI).
 *
 * Depth 0 → null (collapse all).
 */
function computeSpecAfterClick(currentSpec, depth, fnId, allFnsAtDepth) {
  if (depth <= 0) return null;
  const isMI = allFnsAtDepth && allFnsAtDepth.length > 1;
  const fullDepth = currentSpec.fullDepth || 0;

  if (!isMI) {
    // Toggle: if already expanded to this depth or deeper → collapse.
    // Otherwise → expand to this depth.
    if (depth <= fullDepth) {
      const newFull = depth - 1;
      if (newFull <= 0) return null;
      return { fullDepth: newFull, partialFns: new Set() };
    }
    return { fullDepth: depth, partialFns: new Set() };
  }

  const partial = new Set(currentSpec.partialFns || []);

  // Is this fn already part of the committed expansion?
  const fullyExpandedHere = depth <= fullDepth;
  const inPartialHere = depth === fullDepth + 1 && partial.has(fnId);
  const currentlyExpanded = fullyExpandedHere || inPartialHere;

  if (currentlyExpanded) {
    // TOGGLE OFF: remove this fn from the expansion.
    if (fullyExpandedHere) {
      // Was fully expanded at this depth — keep all OTHER MI fns at this
      // depth as partial; collapse anything deeper than this depth.
      const others = allFnsAtDepth.filter(f => f !== fnId);
      const newFull = depth - 1;
      if (newFull <= 0 && others.length === 0) return null;
      return { fullDepth: newFull, partialFns: new Set(others) };
    }
    // depth === fullDepth + 1 and fnId in partial: just remove from partial
    partial.delete(fnId);
    if (partial.size === 0 && fullDepth === 0) return null;
    return { fullDepth, partialFns: partial };
  }

  // TOGGLE ON: add this fn.
  if (depth > fullDepth + 1) {
    // Cascade: fully expand intermediate levels, then add this MI fn
    return { fullDepth: depth - 1, partialFns: new Set([fnId]) };
  }
  // depth === fullDepth + 1: add to existing partial
  partial.add(fnId);
  // Auto-promote when all MI fns at this depth are now selected
  if (allFnsAtDepth.every(f => partial.has(f))) {
    return { fullDepth: depth, partialFns: new Set() };
  }
  return { fullDepth, partialFns: partial };
}

/**
 * Apply spec change for click on a fn at a depth.
 */
function applyClickSpec(nodeId, depth, fnId, allFnsAtDepth) {
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }
  const parts = nodeId.replace('fn-', '').split('_');
  anchorFnId = parts[parts.length - 1];

  const newSpec = computeSpecAfterClick(getSpec(nodeId), depth, fnId, allFnsAtDepth);
  if (newSpec === null) {
    expansionState.delete(nodeId);
  } else {
    expansionState.set(nodeId, newSpec);
  }
  previewState.delete(nodeId);
  // Suppress preview until cursor leaves the element. This prevents the
  // "ghost preview" where committed state is immediately reversed.
  suppressPreviewOnClick();
  // Commit clears saved user positions — nodes that disappeared in the
  // committed state lose their manual position.
  savedUserPositions.clear();
  renderGraph(false);
  anchorFnId = null;
}

/**
 * Set preview spec (hover). Uses debouncing to avoid flicker.
 *
 * IMPORTANT: clicks are bound to `mousedown` (not `click`) so that the
 * click action fires BEFORE any pending hover render can shift the layout.
 * This keeps clicks reliable even with hover preview active.
 */
function applyHoverSpec(nodeId, depth, fnId, allFnsAtDepth) {
  const newSpec = computeSpecAfterClick(getSpec(nodeId), depth, fnId, allFnsAtDepth);
  const oldPreview = previewState.get(nodeId);
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }
  // Skip if preview unchanged
  if (oldPreview && newSpec
      && oldPreview.fullDepth === newSpec.fullDepth
      && oldPreview.partialFns.size === newSpec.partialFns.size
      && [...oldPreview.partialFns].every(f => newSpec.partialFns.has(f))) {
    return;
  }
  previewDebounceTimer = setTimeout(() => {
    const parts = nodeId.replace('fn-', '').split('_');
    anchorFnId = parts[parts.length - 1];
    // null spec means "collapse everything" — use {fullDepth:0} so the
    // preview render shows the collapsed graph (not the committed state).
    previewState.set(nodeId, newSpec || { fullDepth: 0, partialFns: new Set() });
    renderGraph(false);
    anchorFnId = null;
  }, PREVIEW_DEBOUNCE_MS);
}

function clearPreview(nodeId) {
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }
  if (!previewState.has(nodeId)) return;
  previewDebounceTimer = setTimeout(() => {
    const parts = nodeId.replace('fn-', '').split('_');
    anchorFnId = parts[parts.length - 1];
    previewState.delete(nodeId);
    renderGraph(false);
    anchorFnId = null;
  }, PREVIEW_DEBOUNCE_MS);
}

/**
 * Clear all preview state
 */
function clearPreviewState() {
  if (previewState.size > 0) {
    previewState.clear();
    renderGraph(false);
  }
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
  cy.fit(50);
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
  if (rootNode && rootNode.length) {
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
