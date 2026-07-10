// Editor Cytoscape - Cytoscape.js initialization and rendering
// Depends on: editor-state.js, editor-layout.js, editor-overlays.js

// ============================================================================
// CYTOSCAPE STYLES
// ============================================================================

// Cytoscape renders to canvas — it doesn't honour `var(--*)` references the
// way HTML overlays do. We resolve the CSS vars at style-build time and
// re-apply on theme change (see `applyThemeToCytoscape`).
//
// Read from `document.body` because the dark-theme overrides are scoped
// there (`body.theme-dark { --bg: ...; }`); reading from documentElement
// would always return the `:root` defaults regardless of theme.
function cssVar(name) {
  return getComputedStyle(document.body).getPropertyValue(name).trim();
}

function buildCytoscapeStyles() {
  const cardBg = cssVar('--card-bg');
  const cardBorder = cssVar('--card-border');
  const cardFg = cssVar('--card-fg');
  return [
  // fn node (base dimensions, used for calculating size).
  // Width/height come from precomputed layoutWidth/layoutHeight on node.data
  // (computed in editor-layout.js so CY node size MATCHES the actual rendered
  // overlay height — important for MI rows where cells wrap to multiple lines).
  { selector: 'node[type="fn"]', style: {
    'label': 'data(label)',
    'text-valign': 'center',
    'text-halign': 'center',
    'text-wrap': 'wrap',
    'font-size': '11px',
    'font-family': 'SF Mono, Monaco, monospace',
    'shape': 'round-rectangle',
    'background-color': cardBg,
    'border-width': 2,
    'border-color': cardBorder,
    'color': cardFg,
    'padding': '0px',
    'width': function(node) {
      return node.data('layoutWidth') || 80;
    },
    'height': function(node) {
      return node.data('layoutHeight') || 30;
    }
  }},
  // Non-placeholder fn nodes - hide completely, overlay shows content
  { selector: 'node[type="fn"][!isPlaceholder]', style: {
    'label': '',
    'background-opacity': 0,
    'border-width': 0,
    'overlay-opacity': 0    // suppress cytoscape's default click/select highlight
  }},
  // Placeholder (unset arg) - hide; overlay shows a small `+` binder
  // for editable viewers, nothing for read-only viewers (the empty
  // edge endpoint is itself the "slot is unbound" signal).
  { selector: 'node[?isPlaceholder]', style: {
    'label': '',
    'background-opacity': 0,
    'border-width': 0,
    'padding': '0px',
    'overlay-opacity': 0
  }},
  // Arg value node - hide, overlay shows content with drag handle
  { selector: 'node[type="arg"]', style: {
    'label': '',
    'background-opacity': 0,
    'border-width': 0,
    'padding': '0px',
    'overlay-opacity': 0,
    'width': function(node) {
      return node.data('layoutWidth') || 40;
    },
    'height': function(node) {
      return node.data('layoutHeight') || 36;
    }
  }},
  // Edges are drawn as SVG paths in `#edge-layer` (editor-edges-svg.js), where
  // the path and its hit-zone are the same geometry and the theme is plain CSS.
  // Cytoscape still holds the edge topology — it is what `gv.edges()` walks —
  // but it must not paint anything, nor intercept a pointer over one.
  { selector: 'edge', style: {
    'opacity': 0,
    'events': 'no'
  }}
  ];
}

// Re-apply cytoscape style with current theme colors. Called by editor-prefs
// when the user toggles the theme — without this, edges and node borders
// keep their old (black/white) colors after the body class flips.
function applyThemeToCytoscape() {
  // window.cy starts undefined and only becomes a cytoscape instance after
  // createCytoscape runs. Theme can flip before that (the early-init pass
  // applies stored theme on every page load). Bail until the instance is
  // ready and offers the `.style()` API.
  const c = window.cy;
  if (!c || typeof c.style !== 'function') return;
  c.style().fromJson(buildCytoscapeStyles()).update();
}
window.applyThemeToCytoscape = applyThemeToCytoscape;

// ============================================================================
// CYTOSCAPE INITIALIZATION
// ============================================================================

/**
 * `cy.fit` thinks of the canvas as the full container, but with the
 * overlay layout the leftmost slice of the canvas sits behind the
 * sidebar and isn't actually visible. We fit-to-visible by computing
 * zoom and pan ourselves: zoom to the bounding box that fits in
 * (canvasWidth − sidebarWidth − 2*padding), then pan so the bbox
 * centre lands at the centre of the visible area (right of the
 * sidebar).
 */
function fitInVisibleArea(padding) {
  if (!cy || cy.nodes().length === 0) return;
  const cyContainer = document.getElementById('cy');
  if (!cyContainer) return;
  const canvasW = cyContainer.clientWidth;
  const canvasH = cyContainer.clientHeight;
  const sidebar = document.getElementById('side-menu');
  const collapsed = document.body.classList.contains('sidebar-collapsed');
  const sidebarW = (sidebar && !collapsed) ? sidebar.getBoundingClientRect().width : 0;
  const visibleW = Math.max(canvasW - sidebarW, 100);
  const visibleH = canvasH;

  const bb = cy.elements().boundingBox();
  if (bb.w <= 0 || bb.h <= 0) return;

  const zoom = Math.min(
    (visibleW - 2 * padding) / bb.w,
    (visibleH - 2 * padding) / bb.h
  );
  const visibleCenterX = sidebarW + visibleW / 2;
  const visibleCenterY = canvasH / 2;
  const bbCx = (bb.x1 + bb.x2) / 2;
  const bbCy = (bb.y1 + bb.y2) / 2;
  // `setViewportTransform` clamps the zoom to the viewport's own bounds.
  const z = clampZoom(zoom);
  setViewportTransform(z, visibleCenterX - bbCx * z, visibleCenterY - bbCy * z);
}

// The viewport's gesture handlers and change listeners are installed once, on
// the first graph. `createCytoscape` only runs when `cy` is null, but the guard
// keeps that an implementation detail rather than a precondition.
let _viewportBound = false;

/**
 * Create Cytoscape instance with initial elements and layout
 */
async function createCytoscape(nodes, edges, layout, shouldFit) {
  // Ensure cytoscape library is loaded
  if (typeof cytoscape === 'undefined') {
    console.error('Cytoscape library not loaded yet, retrying in 100ms...');
    await new Promise(resolve => setTimeout(resolve, 100));
    if (typeof cytoscape === 'undefined') {
      console.error('Cytoscape library still not loaded!');
      return;
    }
  }

  // Apply positions to nodes
  const nodesWithPos = nodes.map(n => {
    const pos = layout.get(n.data.id);
    if (pos) {
      return { ...n, position: { x: pos.x, y: pos.y } };
    }
    return n;
  });

  cy = cytoscape({
    container: document.getElementById('cy'),
    elements: { nodes: nodesWithPos, edges: edges },
    style: buildCytoscapeStyles(),
    layout: { name: 'preset' },
    // Cytoscape is now a topology and position store. It draws nothing, and it
    // handles no gestures: the wheel, the background drag and the pinch are
    // editor-viewport.js's, and node dragging was always the overlay handle's.
    userPanningEnabled: false,
    userZoomingEnabled: false,
    autoungrabify: true,
    autounselectify: true,
    boxSelectionEnabled: false
  });
  // Expose for browser-test debugging; harmless in production (single-graph page).
  window.cy = cy;

  // Pan/zoom fires on every wheel tick and every drag delta, so these must stay
  // O(1). The overlays and the edge paths both ride the layer's transform; only
  // the stroke widths need a nudge, and those are two attribute writes.
  if (!_viewportBound) {
    _viewportBound = true;
    installViewportInput();
    onViewportChanged(applyViewportTransform);
    onViewportChanged(updateZoomSlider);
  }

  // Edge hover lives in editor-edges-svg.js now. An SVG path is its own
  // hit-zone, and `elementsFromPoint` returns every edge under the cursor —
  // so the three-segment geometry that used to re-derive the taxi bend just to
  // find the overlapping vertical runs cytoscape's hit-test missed is gone,
  // along with the separate mouse/touch tolerances it needed.

  // Build the overlays, then correct the layout against their measured heights
  // and settle the nodes on it. Only then is there a bounding box worth fitting.
  createNodeOverlays();
  if (reflowFromMeasuredHeights(layout)) {
    cy.nodes().forEach((node) => {
      const pos = layout.get(node.id());
      if (pos) node.position({x: pos.x, y: pos.y});
    });
  }

  if (shouldFit && cy.nodes().length > 0) {
    fitInVisibleArea(50);
  }
  updateOverlayPositions();
  updateZoomSlider();
}

// ============================================================================
// RENDER GRAPH
// ============================================================================

/**
 * Render or update the graph
 * Fetches nodes, edges, and layout from backend in single request
 */
async function renderGraph(shouldFit = true) {
  // `initGraph` only loaded the scope=index sidebar payload. Fetch
  // the subtree for the selected fn so overlays / edges read real
  // slots / bindings / items out of `lookups`. ensureSubtreeFor
  // short-circuits when the cache already matches selectedFnId.
  if (selectedFnId && typeof ensureSubtreeFor === 'function') {
    try { await ensureSubtreeFor(selectedFnId); }
    catch (err) {
      // eslint-disable-next-line no-console
      console.error('renderGraph: subtree fetch failed', err);
      return;
    }
  }
  // `selectFn` called rebuildImplementationFnIds before this point,
  // but on the FIRST render after initGraph / a navigation switch
  // lookups were partial (pre-ensureSubtreeFor), so impl-fn-ids
  // came back as ∅ and overlays would gate as non-editable. Redo
  // it now with the populated bindings/items index — idempotent
  // on subsequent renders of the same fn.
  if (selectedFnId && typeof rebuildImplementationFnIds === 'function') {
    rebuildImplementationFnIds();
  }
  // Capture anchor BEFORE await — anchorNodeId may be cleared by caller after async yield.
  // The anchor holds the FULL cytoscape node id (including `fn-{root}_{fn-id}`
  // scoping prefix where applicable) so scoped leaves stay stationary too.
  let capturedAnchorNodeId = anchorNodeId;
  let anchorOldPos = null;

  if (cy) {
    // Fallback: if no explicit anchor was set (e.g. the user triggered a
    // render path that didn't go through applyClickSpec/applyHoverSpec),
    // anchor to a preview key so hover re-renders don't drift the graph.
    if (!capturedAnchorNodeId && previewState.size > 0) {
      capturedAnchorNodeId = previewState.keys().next().value;
    }

    // Save anchor position BEFORE fetch (before any async yield)
    if (capturedAnchorNodeId) {
      const anchorNode = cy.getElementById(capturedAnchorNodeId);
      if (anchorNode.length) {
        anchorOldPos = { ...anchorNode.position() };
      }
    }
  }

  // Fetch everything from backend
  const result = await fetchBackendLayout();
  if (!result) {
    console.error('Failed to fetch layout from backend');
    return;
  }

  const { nodes, edges, layout } = result;

  // First render - create cytoscape
  if (!cy) {
    if (nodes.length > 0) {
      await createCytoscape(nodes, edges, layout, shouldFit);
    }
    return;
  }

  // Stop any running animations
  cy.nodes().forEach(node => node.stop(true, true));

  // Save positions of user-moved nodes BEFORE render. During preview,
  // nodes may be removed and re-added; we restore their positions after.
  cy.nodes().forEach(node => {
    if (userMovedNodes.has(node.id())) {
      savedUserPositions.set(node.id(), { ...node.position() });
    }
  });

  // Calculate offset to keep anchor node stationary.
  // SKIP if the anchor is a user-moved node — otherwise the offset shifts
  // all nodes to match the user's drag, visually "undoing" the drag.
  let offsetX = 0;
  let offsetY = 0;
  if (capturedAnchorNodeId && anchorOldPos && !userMovedNodes.has(capturedAnchorNodeId)) {
    const anchorNewPos = layout.get(capturedAnchorNodeId);
    if (anchorNewPos) {
      offsetX = anchorOldPos.x - anchorNewPos.x;
      offsetY = anchorOldPos.y - anchorNewPos.y;
    }
  }

  // Apply offset to all layout positions
  if (offsetX !== 0 || offsetY !== 0) {
    layout.forEach((pos, nodeId) => {
      pos.x += offsetX;
      pos.y += offsetY;
    });
  }

  // Build maps for quick lookup
  const newNodeIds = new Set(nodes.map(n => n.data.id));
  const newEdgeIds = new Set(edges.map(e => e.data.id));

  // Find nodes/edges to remove and add
  const nodesToRemove = cy.nodes().filter(node => !newNodeIds.has(node.id()));
  const edgesToRemove = cy.edges().filter(edge => !newEdgeIds.has(edge.id()));
  const nodesToAdd = nodes.filter(n => !cy.getElementById(n.data.id).length);
  const edgesToAdd = edges.filter(e => !cy.getElementById(e.data.id).length);


  // Update existing node + edge data. Cytoscape's `.data(obj)` is a
  // MERGE — fields the backend stops sending stick around on the
  // element. That bit us on edges' `typeChain`: after the user
  // collapses an expansion the backend correctly returns an edge
  // without the chain, but the old chain lingered in cy data and
  // the renderer kept drawing the stale narrowing row. Replace by
  // clearing keys that don't appear in the new payload, then merging.
  const replaceData = (el, newData) => {
    if (!newData) return;
    const newKeys = new Set(Object.keys(newData));
    for (const k of Object.keys(el.data())) {
      if (!newKeys.has(k)) el.removeData(k);
    }
    el.data(newData);
  };
  // O(N) update via pre-indexed maps. The previous .find() per
  // cy node was O(N²) — visible jitter on graphs with ~200 nodes
  // every time the user expanded / collapsed.
  const nodesById = new Map(nodes.map(n => [n.data.id, n]));
  const edgesById = new Map(edges.map(e => [e.data.id, e]));
  cy.nodes().forEach(node => {
    const fresh = nodesById.get(node.id());
    if (fresh) replaceData(node, fresh.data);
  });
  cy.edges().forEach(edge => {
    const fresh = edgesById.get(edge.id());
    if (fresh) replaceData(edge, fresh.data);
  });

  function completeUpdate() {
    suppressEdgeWarnings = true;

    // Remove old elements.
    // During preview, keep userMovedNodes entries (positions are saved).
    // On commit, entries are cleared via savedUserPositions.clear().
    const isPreview = previewState.size > 0;
    nodesToRemove.forEach(node => {
      // Registry-backed lookup: avoids constructing a CSS selector +
      // scanning the DOM per removed node. The overlay's nodeId is
      // the cy node id, so look up directly rather than matching on
      // data-original-fn-id, which is ambiguous when multiple copies
      // share an originalFnId (it returns the first match).
      unregisterNodeOverlay(node.id());
      if (!isPreview) {
        userMovedNodes.delete(node.id());
        savedUserPositions.delete(node.id());
      }
    });
    cy.remove(nodesToRemove);
    cy.remove(edgesToRemove);

    // Add new nodes with initial position.
    // If a node was user-moved (has a saved position), use that instead of layout.
    if (nodesToAdd.length > 0) {
      nodesToAdd.forEach(n => {
        const saved = savedUserPositions.get(n.data.id);
        if (saved && userMovedNodes.has(n.data.id)) {
          n.position = { x: saved.x, y: saved.y };
        } else {
          const pos = layout.get(n.data.id);
          if (pos) {
            n.position = { x: pos.x, y: pos.y };
          }
        }
      });
      cy.add(nodesToAdd);
    }

    // Add edges after nodes are positioned. Their paths are (re)built from the
    // topology by `renderEdges()`, further down in `createNodeOverlays`; stroke
    // width is inherited from the group, so new edges need no per-edge refresh.
    if (edgesToAdd.length > 0) {
      cy.add(edgesToAdd);
    }

    // Build the overlays BEFORE deciding where anything goes. A card's height is
    // content-driven — the effects strip wraps to as many chip rows as it needs
    // — so it can only be measured, never predicted. `reflowFromMeasuredHeights`
    // corrects `layout` in place, and the animation below drives towards those
    // corrected targets rather than snapping to them afterwards.
    rebuildingOverlays = true;
    createNodeOverlays();
    rebuildingOverlays = false;
    reflowFromMeasuredHeights(layout);

    // Apply layout positions with animation
    const animPromises = [];

    cy.nodes().forEach(node => {
      const nodeId = node.id();

      if (userMovedNodes.has(nodeId)) return;

      const pos = layout.get(nodeId);
      if (!pos) return;

      const targetPos = { x: pos.x, y: pos.y };
      const currentPos = node.position();

      // A node that has just appeared jumps to its place; one that was already
      // on screen eases there. Either way the target is the corrected one.
      const isNewNode = nodesToAdd.some(n => n.data.id === nodeId);
      if (!isNewNode && (Math.abs(currentPos.x - targetPos.x) > 1 || Math.abs(currentPos.y - targetPos.y) > 1)) {
        const anim = node.animation({
          position: targetPos,
          duration: ANIM_DURATION,
          easing: 'ease-out'
        });
        animPromises.push(anim.play().promise());
      } else {
        node.position(targetPos);
      }
    });

    // Update overlays during animation
    let animating = true;
    function updateLoop() {
      if (animating) {
        updateOverlayPositions();
        requestAnimationFrame(updateLoop);
      }
    }

    updateOverlayPositions();
    requestAnimationFrame(updateLoop);

    Promise.all(animPromises).then(() => {
      animating = false;
      suppressEdgeWarnings = false;
      updateOverlayPositions();
      if (shouldFit && cy.nodes().length > 0) {
        fitInVisibleArea(50);
        updateZoomSlider();
      }
    });
  }

  // If nodes to remove, animate them first
  if (nodesToRemove.length > 0) {
    const removeAnims = [];
    nodesToRemove.forEach(node => {
      unregisterNodeOverlay(node.id());

      const parentEdge = cy.edges().filter(e => e.data('target') === node.id());
      if (parentEdge.length > 0) {
        const parentId = parentEdge[0].data('source');
        const parentNode = cy.getElementById(parentId);
        if (parentNode.length > 0) {
          const parentPos = parentNode.position();
          removeAnims.push(
            node.animation({
              position: { x: parentPos.x, y: parentPos.y },
              style: { opacity: 0 },
              duration: ANIM_DURATION,
              easing: 'ease-in'
            }).play().promise()
          );
        }
      }
    });

    if (removeAnims.length > 0) {
      suppressEdgeWarnings = true;
      Promise.all(removeAnims).then(() => {
        suppressEdgeWarnings = false;
        completeUpdate();
      });
    } else {
      completeUpdate();
    }
  } else {
    completeUpdate();
  }
}
