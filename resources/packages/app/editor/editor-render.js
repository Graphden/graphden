// Editor Render — turns a backend layout into the graph on screen.
// Depends on: editor-graph-model.js, editor-graph-view.js, editor-viewport.js,
// editor-layout.js, editor-overlay-manager.js, editor-edges-svg.js.
//
// Nodes are HTML overlays, edges are SVG paths, the viewport is a CSS transform.
// Nothing here draws: this file decides what exists and where it goes, and the
// layers above paint it.

// The viewport's gesture handlers and change listeners are installed once, on
// the first graph.
let _viewportBound = false;


/**
 * The surface is not the visible area: its leftmost slice sits behind the
 * sidebar. So fit to the bounding box that fits in
 * (surfaceWidth − sidebarWidth − 2*padding), then pan so the box's centre lands
 * at the centre of what the user can actually see.
 */
function fitInVisibleArea(padding) {
  const bb = graphBoundingBox();
  if (!bb || bb.w <= 0 || bb.h <= 0) return;
  const surface = viewportContainer();
  if (!surface) return;

  const surfaceW = surface.clientWidth;
  const surfaceH = surface.clientHeight;
  const sidebar = document.getElementById('side-menu');
  const collapsed = document.body.classList.contains('sidebar-collapsed');
  // Redesign 2026-08: the sidebar is its own grid column, not an overlay over
  // the canvas, so `surface` already excludes it — don't compensate again or
  // the graph pans off-centre to the right.
  const redesign = document.getElementById('app')?.classList.contains('gd-redesign');
  const sidebarW = (!redesign && sidebar && !collapsed) ? sidebar.getBoundingClientRect().width : 0;
  const visibleW = Math.max(surfaceW - sidebarW, 100);

  const z = clampZoom(Math.min(
    (visibleW - 2 * padding) / bb.w,
    (surfaceH - 2 * padding) / bb.h
  ));
  const bbCx = (bb.x1 + bb.x2) / 2;
  const bbCy = (bb.y1 + bb.y2) / 2;
  setViewportTransform(z,
                       sidebarW + visibleW / 2 - bbCx * z,
                       surfaceH / 2 - bbCy * z);
}


/** Move nodes onto `layout`, then re-fit if asked. */
function settleNodes(layout) {
  for (const [nodeId, node] of graph.nodes) {
    if (userMovedNodes.has(nodeId)) continue;
    const pos = layout.get(nodeId);
    if (pos) {
      node.x = pos.x;
      node.y = pos.y;
    }
  }
}


/** First graph on the page. */
function createGraph(nodes, edges, layout, shouldFit) {
  for (const n of nodes) {
    graphAddNode({data: n.data, position: layout.get(n.data.id)});
  }
  for (const e of edges) graphAddEdge(e);

  if (!_viewportBound) {
    _viewportBound = true;
    installViewportInput();
    // Pan/zoom fires on every wheel tick and every drag delta, so these must
    // stay O(1). The overlays and the edge paths ride the layer's transform;
    // only the stroke widths need a nudge, and those are two attribute writes.
    onViewportChanged(applyViewportTransform);
    onViewportChanged(updateZoomSlider);
  }

  // Build the overlays, then correct the layout against their measured heights
  // and settle the nodes on it. Only then is there a bounding box worth fitting.
  createNodeOverlays();
  if (reflowFromMeasuredHeights(layout)) settleNodes(layout);

  if (shouldFit && graph.nodes.size > 0) fitInVisibleArea(50);
  updateOverlayPositions();
  updateZoomSlider();
}


// ============================================================================
// RENDER GRAPH
// ============================================================================

/**
 * Render or update the graph.
 * Fetches nodes, edges, and layout from the backend in a single request.
 */
async function renderGraph(shouldFit = true) {
  // `initGraph` only loaded the scope=tree sidebar payload (namespaces +
  // counts, no fn detail). Fetch the subtree for the selected fn so overlays /
  // edges read real slots / bindings / items out of `lookups`. ensureSubtreeFor
  // short-circuits when the cache already matches selectedFnId.
  if (selectedFnId && typeof ensureSubtreeFor === 'function') {
    try { await ensureSubtreeFor(selectedFnId); }
    catch (err) {
      console.error('renderGraph: subtree fetch failed', err);
      return;
    }
  }
  // `selectFn` called rebuildImplementationFnIds before this point, but on the
  // FIRST render after initGraph / a navigation switch lookups were partial
  // (pre-ensureSubtreeFor), so impl-fn-ids came back as ∅ and overlays would
  // gate as non-editable. Redo it now with the populated bindings/items index —
  // idempotent on subsequent renders of the same fn.
  if (selectedFnId && typeof rebuildImplementationFnIds === 'function') {
    rebuildImplementationFnIds();
  }

  // Capture the anchor BEFORE the await — `anchorNodeId` may be cleared by the
  // caller after an async yield. It holds the FULL node id (including the
  // `fn-{root}_{fn-id}` scoping prefix where applicable) so scoped leaves stay
  // stationary too.
  let capturedAnchorNodeId = anchorNodeId;
  let anchorOldPos = null;

  if (graph.nodes.size > 0) {
    // Fallback: if no explicit anchor was set (e.g. a render path that didn't go
    // through applyClickSpec/applyHoverSpec), anchor to a preview key so hover
    // re-renders don't drift the graph.
    if (!capturedAnchorNodeId && previewState.size > 0) {
      capturedAnchorNodeId = previewState.keys().next().value;
    }
    if (capturedAnchorNodeId) {
      const anchorNode = graph.nodes.get(capturedAnchorNodeId);
      if (anchorNode) anchorOldPos = {x: anchorNode.x, y: anchorNode.y};
    }
  }

  const result = await fetchBackendLayout();
  if (!result) {
    console.error('Failed to fetch layout from backend');
    return;
  }
  const {nodes, edges, layout} = result;

  // First render.
  if (graph.nodes.size === 0) {
    if (nodes.length > 0) createGraph(nodes, edges, layout, shouldFit);
    return;
  }

  graphStopAnimation();

  // Save positions of user-moved nodes BEFORE the render. During preview, nodes
  // may be removed and re-added; we restore their positions afterwards.
  for (const [nodeId, node] of graph.nodes) {
    if (userMovedNodes.has(nodeId)) {
      savedUserPositions.set(nodeId, {x: node.x, y: node.y});
    }
  }

  // Keep the anchor node stationary. SKIP when the anchor was user-moved —
  // otherwise the offset shifts every node to match the drag, visually undoing
  // it.
  if (capturedAnchorNodeId && anchorOldPos && !userMovedNodes.has(capturedAnchorNodeId)) {
    const anchorNewPos = layout.get(capturedAnchorNodeId);
    if (anchorNewPos) {
      const offsetX = anchorOldPos.x - anchorNewPos.x;
      const offsetY = anchorOldPos.y - anchorNewPos.y;
      if (offsetX !== 0 || offsetY !== 0) {
        layout.forEach((pos) => {
          pos.x += offsetX;
          pos.y += offsetY;
        });
      }
    }
  }

  const newNodeIds = new Set(nodes.map((n) => n.data.id));
  const newEdgeIds = new Set(edges.map((e) => e.data.id));
  const nodeIdsToRemove = graphNodeIds().filter((id) => !newNodeIds.has(id));
  const edgeIdsToRemove = [...graph.edges.keys()].filter((id) => !newEdgeIds.has(id));
  const nodesToAdd = nodes.filter((n) => !graph.nodes.has(n.data.id));
  const edgesToAdd = edges.filter((e) => !graph.edges.has(e.data.id));

  // Refresh the data of everything that survived. O(N) via pre-indexed maps —
  // a `.find()` per node was O(N²), visible as jitter on ~200-node graphs every
  // time the user expanded or collapsed.
  const nodesById = new Map(nodes.map((n) => [n.data.id, n]));
  const edgesById = new Map(edges.map((e) => [e.data.id, e]));
  for (const id of graph.nodes.keys()) {
    const fresh = nodesById.get(id);
    if (fresh) graphSetNodeData(id, fresh.data);
  }
  for (const id of graph.edges.keys()) {
    const fresh = edgesById.get(id);
    if (fresh) graphSetEdgeData(id, fresh.data);
  }

  function completeUpdate() {
    // During preview, keep userMovedNodes entries (positions are saved). On
    // commit, entries are cleared via savedUserPositions.clear().
    const isPreview = previewState.size > 0;
    for (const id of nodeIdsToRemove) {
      // Registry-backed lookup: the overlay's nodeId IS the node id, so look it
      // up directly rather than matching on data-original-fn-id, which is
      // ambiguous when several copies share an originalFnId.
      unregisterNodeOverlay(id);
      if (!isPreview) {
        userMovedNodes.delete(id);
        savedUserPositions.delete(id);
      }
      graphRemoveNode(id);
    }
    for (const id of edgeIdsToRemove) graphRemoveEdge(id);

    // Add new nodes at their layout position — or at the position the user
    // dragged them to, if they are coming back from a preview.
    for (const n of nodesToAdd) {
      const saved = savedUserPositions.get(n.data.id);
      const pos = (saved && userMovedNodes.has(n.data.id)) ? saved : layout.get(n.data.id);
      graphAddNode({data: n.data, position: pos});
    }
    for (const e of edgesToAdd) graphAddEdge(e);

    // Build the overlays BEFORE deciding where anything goes. A card's height is
    // content-driven — the effects strip wraps to as many chip rows as it needs
    // — so it can only be measured, never predicted. `reflowFromMeasuredHeights`
    // corrects `layout` in place, and the tween below drives towards those
    // corrected targets rather than snapping to them afterwards.
    rebuildingOverlays = true;
    createNodeOverlays();
    rebuildingOverlays = false;
    reflowFromMeasuredHeights(layout);

    // A node that has just appeared jumps to its place; one that was already on
    // screen eases there. Either way the target is the corrected one.
    const newIds = new Set(nodesToAdd.map((n) => n.data.id));
    const targets = new Map();
    for (const [nodeId, node] of graph.nodes) {
      if (userMovedNodes.has(nodeId)) continue;
      const pos = layout.get(nodeId);
      if (!pos) continue;
      if (newIds.has(nodeId)) {
        node.x = pos.x;
        node.y = pos.y;
      } else {
        targets.set(nodeId, {x: pos.x, y: pos.y});
      }
    }

    updateOverlayPositions();
    graphAnimateNodes(targets, ANIM_DURATION, updateOverlayPositions).then(() => {
      updateOverlayPositions();
      if (shouldFit && graph.nodes.size > 0) {
        fitInVisibleArea(50);
        updateZoomSlider();
      }
    });
  }

  // Nodes on their way out fly back to the parent they hung from, and dissolve.
  if (nodeIdsToRemove.length > 0) {
    const targets = new Map();
    for (const id of nodeIdsToRemove) {
      const parentEdgeId = [...(graph.incoming.get(id) || [])][0];
      const parentId = parentEdgeId && graph.edges.get(parentEdgeId)?.sourceId;
      const parent = parentId && graph.nodes.get(parentId);
      if (parent) targets.set(id, {x: parent.x, y: parent.y});
      fadeOutOverlay(getNodeOverlay(id), ANIM_DURATION);
    }
    if (targets.size > 0) {
      graphAnimateNodes(targets, ANIM_DURATION, updateOverlayPositions, easeInCubic)
        .then(completeUpdate);
      return;
    }
  }
  completeUpdate();
}
