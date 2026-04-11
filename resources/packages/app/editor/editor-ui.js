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

// Expanded namespace state (persisted across updateEntityList calls).
// By default all namespaces are collapsed; only explicitly opened ones are expanded.
let expandedNamespaces = new Set();

/**
 * Build a tree from fns grouped by namespace path.
 * Returns: { children: Map<string, subtree>, fns: [fn, ...] }
 */
function buildNsTree(data) {
  const root = { children: new Map(), fns: [] };

  (data.fns || []).forEach(fn => {
    const qname = getQualifiedFnName(fn);
    const parts = qname.split('.');
    const fnName = parts.pop();
    let node = root;
    for (const part of parts) {
      if (!node.children.has(part)) {
        node.children.set(part, { children: new Map(), fns: [] });
      }
      node = node.children.get(part);
    }
    node.fns.push({ ...fn, displayName: fnName });
  });

  return root;
}

/**
 * Render a namespace tree node recursively into the container.
 */
function renderNsNode(container, name, node, path) {
  const nsPath = path ? path + '.' + name : name;
  const hasFns = node.fns.length > 0;
  const hasChildren = node.children.size > 0;
  const isCollapsed = !expandedNamespaces.has(nsPath);

  // Namespace header
  const header = document.createElement('div');
  header.className = 'ns-header';
  header.dataset.nsPath = nsPath;

  const arrow = document.createElement('span');
  arrow.className = 'ns-arrow' + (isCollapsed ? ' collapsed' : '');
  arrow.textContent = isCollapsed ? '\u25B6' : '\u25BC';
  header.appendChild(arrow);

  const label = document.createElement('span');
  label.className = 'ns-label';
  label.textContent = name;
  header.appendChild(label);

  header.onclick = (e) => {
    e.stopPropagation();
    if (isCollapsed) {
      expandedNamespaces.add(nsPath);
    } else {
      expandedNamespaces.delete(nsPath);
    }
    updateEntityList(graphData);
  };

  container.appendChild(header);

  if (isCollapsed) return;

  // Child namespaces (sorted)
  const childGroup = document.createElement('div');
  childGroup.className = 'ns-children';

  const sortedChildren = [...node.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [childName, childNode] of sortedChildren) {
    renderNsNode(childGroup, childName, childNode, nsPath);
  }

  // Fn items (sorted)
  const sortedFns = [...node.fns].sort((a, b) => a.displayName.localeCompare(b.displayName));
  for (const fn of sortedFns) {
    const item = document.createElement('div');
    item.className = 'entity-item';
    if (fn.id === selectedFnId) item.className += ' selected';
    item.dataset.fnId = fn.id;
    item.innerHTML = '<span class="name">' + fn.displayName + '</span>';
    item.onclick = () => selectFn(fn.id);
    childGroup.appendChild(item);
  }

  container.appendChild(childGroup);
}

/**
 * Update the entity list in sidebar as a namespace tree
 */
function updateEntityList(data) {
  const list = document.getElementById('entity-list');
  list.innerHTML = '';

  const tree = buildNsTree(data);

  // Render top-level namespaces (sorted)
  const sortedNs = [...tree.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [name, node] of sortedNs) {
    renderNsNode(list, name, node, '');
  }

  // Top-level fns without namespace
  const sortedFns = [...tree.fns].sort((a, b) => a.displayName.localeCompare(b.displayName));
  for (const fn of sortedFns) {
    const item = document.createElement('div');
    item.className = 'entity-item';
    if (fn.id === selectedFnId) item.className += ' selected';
    item.dataset.fnId = fn.id;
    item.innerHTML = '<span class="name">' + fn.displayName + '</span>';
    item.onclick = () => selectFn(fn.id);
    list.appendChild(item);
  }

  if (list.children.length === 0) {
    list.innerHTML = '<div class="loading">No functions found</div>';
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
