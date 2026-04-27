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

// Current search/filter text (lowercase)
let searchFilter = '';

/**
 * Build a tree from fns grouped by namespace path.
 * Returns: { children: Map<string, subtree>, fns: [fn, ...] }
 */
function buildNsTree(data) {
  const root = { children: new Map(), fns: [], description: null, nsId: null };

  // Build {ns-path → ns-entity} from the namespace entities so the
  // rendered tree nodes can carry both their description tooltip AND
  // their ns-id (needed by the per-namespace `+` button to set
  // `parent-id` when creating sub-entities).
  const nsByPath = new Map();
  (data.namespaces || []).forEach(ns => {
    const path = (lookups.nsPathMap && lookups.nsPathMap.get(ns.id)) || ns.name;
    if (path) nsByPath.set(path, ns);
  });

  // Pre-create tree nodes for every declared namespace, even ones that
  // have no fns yet — newly-created empty namespaces should show up in
  // the sidebar immediately, not only after their first fn is added.
  nsByPath.forEach((ns, path) => {
    const parts = path.split('.');
    let node = root;
    let cumulativePath = '';
    for (const part of parts) {
      cumulativePath = cumulativePath ? cumulativePath + '.' + part : part;
      if (!node.children.has(part)) {
        const entry = nsByPath.get(cumulativePath);
        node.children.set(part, {
          children: new Map(),
          fns: [],
          description: entry ? entry.description : null,
          nsId: entry ? entry.id : null
        });
      }
      node = node.children.get(part);
    }
  });

  (data.fns || []).forEach(fn => {
    if (!fn.name) return; // skip anonymous/local fns
    const qname = getQualifiedFnName(fn);
    const parts = qname.split('.');
    const fnName = parts.pop();
    let node = root;
    let cumulativePath = '';
    for (const part of parts) {
      cumulativePath = cumulativePath ? cumulativePath + '.' + part : part;
      if (!node.children.has(part)) {
        const entry = nsByPath.get(cumulativePath);
        node.children.set(part, {
          children: new Map(),
          fns: [],
          description: entry ? entry.description : null,
          nsId: entry ? entry.id : null
        });
      }
      node = node.children.get(part);
    }
    node.fns.push({ ...fn, displayName: fnName });
  });

  return root;
}

/**
 * Filter a tree node, keeping only branches that contain matches.
 * A fn matches if its displayName contains the filter.
 * A ns matches if its name contains the filter OR any descendant matches.
 * When a ns name itself matches, all its descendants are included.
 * Returns null if nothing matches.
 */
function filterNsNode(node, filter, nsName) {
  const nsMatches = nsName && nsName.toLowerCase().includes(filter);

  // If the namespace name matches, include the entire subtree unfiltered
  if (nsMatches) return node;

  // Filter children recursively
  const filteredChildren = new Map();
  for (const [childName, childNode] of node.children) {
    const filtered = filterNsNode(childNode, filter, childName);
    if (filtered) filteredChildren.set(childName, filtered);
  }

  // Filter fns
  const filteredFns = node.fns.filter(fn =>
    fn.displayName.toLowerCase().includes(filter)
  );

  if (filteredChildren.size === 0 && filteredFns.length === 0) return null;

  return { children: filteredChildren, fns: filteredFns };
}

/**
 * Render a namespace tree node recursively into the container.
 */
function buildFnItem(fn) {
  const item = document.createElement('div');
  item.className = 'entity-item';
  if (fn.id === selectedFnId) item.className += ' selected';
  item.dataset.fnId = fn.id;

  const nameSpan = document.createElement('span');
  nameSpan.className = 'name';
  nameSpan.textContent = fn.displayName;
  item.appendChild(nameSpan);

  // Right-edge action group — same shape as `.ns-row-actions`. Order:
  // hover-only ✎ first, then always-visible `i`. fns don't have a `+`
  // button (you don't add children to a fn the same way you do to a
  // ns), only rename.
  const actions = document.createElement('span');
  actions.className = 'ns-row-actions';
  if (typeof buildFnRowButtons === 'function') {
    buildFnRowButtons(actions, fn.id, fn.displayName);
  }
  if (fn.description) {
    const desc = createDescriptionBadge(fn.description, {
      name: fn.displayName,
      namespace: getFnNamespace(lookups && lookups.fnMap && lookups.fnMap.get(fn.id))
    });
    if (desc) actions.appendChild(desc);
  }
  if (actions.children.length > 0) item.appendChild(actions);

  item.onclick = () => selectFn(fn.id);
  return item;
}


function renderNsNode(container, name, node, path) {
  const nsPath = path ? path + '.' + name : name;
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
  // All three right-edge icons live in one group. Order:
  //   ✎ (rename, hover-only)  +  + (create-child, hover-only)  +  i (description, always)
  // The always-visible `i` sits LAST so the empty slots left by the
  // hover-only buttons (when not hovered) collapse to nothing visible
  // — otherwise the row would look like there's a useless gap to the
  // left of the `i`.
  const actions = document.createElement('span');
  actions.className = 'ns-row-actions';
  if (node && node.nsId && typeof buildNsRowButtons === 'function') {
    buildNsRowButtons(actions, node.nsId, nsPath);
  }
  if (node && node.description) {
    const desc = createDescriptionBadge(node.description, { name: nsPath });
    if (desc) actions.appendChild(desc);
  }
  if (actions.children.length > 0) header.appendChild(actions);

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
    childGroup.appendChild(buildFnItem(fn));
  }

  // If the user has an active inline-create rooted at THIS namespace,
  // append the input row inside `childGroup` so it sits where the new
  // entity will appear once submitted.
  if (node && node.nsId && typeof buildActiveCreateRow === 'function') {
    const createRow = buildActiveCreateRow(node.nsId, 0);
    if (createRow) childGroup.appendChild(createRow);
  }

  container.appendChild(childGroup);
}

/**
 * Search input handler
 */
function onSearchInput(value) {
  searchFilter = value.trim().toLowerCase();
  updateEntityList(graphData);
}

function clearSearch() {
  searchFilter = '';
  const input = document.getElementById('search-input');
  if (input) input.value = '';
  updateEntityList(graphData);
}

/**
 * Update the entity list in sidebar as a namespace tree
 */
function updateEntityList(data) {
  const list = document.getElementById('entity-list');
  list.innerHTML = '';

  let tree = buildNsTree(data);

  // Apply search filter
  if (searchFilter) {
    tree = filterNsNode(tree, searchFilter, null) || { children: new Map(), fns: [] };
  }

  // Render top-level namespaces (sorted)
  const sortedNs = [...tree.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [name, node] of sortedNs) {
    renderNsNode(list, name, node, '');
  }

  // Top-level fns without namespace
  const sortedFns = [...tree.fns].sort((a, b) => a.displayName.localeCompare(b.displayName));
  for (const fn of sortedFns) {
    list.appendChild(buildFnItem(fn));
  }

  if (list.children.length === 0) {
    list.innerHTML = '<div class="loading">No matches</div>';
  }

  // Root-level inline-create input row, when the user clicked the
  // bottom "+ New namespace" button. Appears between the tree and the
  // bottom button so the new entry shows up in place.
  const rootCreateRow = (typeof buildRootCreateRow === 'function')
                        ? buildRootCreateRow() : null;
  if (rootCreateRow) {
    list.appendChild(rootCreateRow);
  } else if (typeof buildRootCreateButton === 'function') {
    // Always-visible "+ New namespace" full-width button at the bottom
    // of the sidebar. Skipped while a root-create row is already
    // active (no point in offering both at once).
    list.appendChild(buildRootCreateButton());
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
 *   "expand exactly to L" — pure SET, no toggle. Clicking a row already
 *   inside the expansion is a no-op; to collapse a level the user clicks
 *   a SHALLOWER row (which sets fullDepth to that shallower level, hiding
 *   anything deeper). The visual model treats a grouped block (e.g.
 *   merge-in + assoc-in joined into one cell) as a single unit — hovering
 *   inside an already-expanded block must NOT preview an asymmetric
 *   half-collapse like "merge-in stays, assoc-in disappears".
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
    // Pure SET: expand to exactly this depth. If already there or deeper,
    // the spec is unchanged (no asymmetric collapse). To shrink, the user
    // clicks a shallower row.
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
 * True when two specs (or absence-of-spec) describe the same expansion.
 * `null` and a missing entry both behave like {fullDepth: 0, partialFns: ∅}.
 */
function specsEqual(a, b) {
  const aFull = a ? a.fullDepth : 0;
  const bFull = b ? b.fullDepth : 0;
  if (aFull !== bFull) return false;
  const aPartial = (a && a.partialFns) || new Set();
  const bPartial = (b && b.partialFns) || new Set();
  if (aPartial.size !== bPartial.size) return false;
  for (const f of aPartial) if (!bPartial.has(f)) return false;
  return true;
}


/**
 * Apply spec change for click on a fn at a depth.
 */
function applyClickSpec(nodeId, depth, fnId, allFnsAtDepth) {
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }
  anchorNodeId = nodeId;

  const currentSpec = expansionState.get(nodeId);
  const newSpec = computeSpecAfterClick(getSpec(nodeId), depth, fnId, allFnsAtDepth);
  // No-op if the click would leave the expansion unchanged. Important now
  // that non-MI clicks are pure SET — re-clicking an already-expanded row
  // would otherwise clear savedUserPositions and trigger a needless re-render.
  if (specsEqual(currentSpec, newSpec)) {
    suppressPreviewOnClick();
    previewState.delete(nodeId);
    anchorNodeId = null;
    return;
  }
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
  anchorNodeId = null;
}

/**
 * Set preview spec (hover). Uses debouncing to avoid flicker.
 *
 * IMPORTANT: clicks are bound to `mousedown` (not `click`) so that the
 * click action fires BEFORE any pending hover render can shift the layout.
 * This keeps clicks reliable even with hover preview active.
 */
function applyHoverSpec(nodeId, depth, fnId, allFnsAtDepth) {
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }
  const newSpec = computeSpecAfterClick(getSpec(nodeId), depth, fnId, allFnsAtDepth);
  const committed = expansionState.get(nodeId);
  const oldPreview = previewState.get(nodeId);

  // What the layout actually needs to render under this hover. If hover
  // reproduces the committed state, no preview is needed — clear it (or
  // do nothing if there was none). This avoids hammering the backend
  // with no-op layouts for hovers that wouldn't change anything.
  const matchesCommitted = specsEqual(committed, newSpec);
  const effectiveSpec = matchesCommitted
    ? null
    : (newSpec || { fullDepth: 0, partialFns: new Set() });

  // Already in the desired preview state? Skip.
  if (effectiveSpec === null && !previewState.has(nodeId)) return;
  if (effectiveSpec && oldPreview && specsEqual(oldPreview, effectiveSpec)) return;

  previewDebounceTimer = setTimeout(() => {
    anchorNodeId = nodeId;
    if (effectiveSpec === null) {
      previewState.delete(nodeId);
    } else {
      previewState.set(nodeId, effectiveSpec);
    }
    renderGraph(false);
    anchorNodeId = null;
  }, PREVIEW_DEBOUNCE_MS);
}

function clearPreview(nodeId) {
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = null;
  }
  if (!previewState.has(nodeId)) return;
  previewDebounceTimer = setTimeout(() => {
    anchorNodeId = nodeId;
    previewState.delete(nodeId);
    renderGraph(false);
    anchorNodeId = null;
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
