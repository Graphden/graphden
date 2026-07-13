// Editor Graph View — the seam between the editor and the graph.
// Depends on: editor-graph-model.js (`graph`), editor-viewport.js (`viewport`).
//
// Every module outside the render pipeline talks to the graph through `gv`. The
// calls used to be spread across seven files and reached straight into
// cytoscape; they were all the same handful — read the viewport, read a node's
// position and size, walk a node's edges, light an edge bundle.
//
// The element shape (`id()` / `data()` / `position()` / `width()` / `height()`)
// is what the overlay builders read, and is deliberately a small, boring
// contract: it survived the backing store being swapped out from under it.

/** Wrap one model node. Returns null when the id is unknown. */
function _gvNode(node) {
  if (!node) return null;
  return {
    id: () => node.id,
    data: (key) => (key === undefined ? node.data : node.data[key]),
    position: (p) => {
      if (p !== undefined) {
        node.x = p.x;
        node.y = p.y;
        return p;
      }
      return {x: node.x, y: node.y};
    },
    width: () => nodeWidth(node),
    height: () => nodeHeight(node),
    /** Record the card's real height, once the DOM has told us what it is. */
    setHeight: (h) => { node.data.layoutHeight = h; },
    incomingEdges: () => _gvEdgesFrom(graph.incoming.get(node.id)),
    outgoingEdges: () => _gvEdgesFrom(graph.outgoing.get(node.id)),
  };
}

/** Wrap one model edge. Returns null when the id is unknown. */
function _gvEdge(edge) {
  if (!edge) return null;
  return {
    id: () => edge.id,
    data: (key) => (key === undefined ? edge.data : edge.data[key]),
    source: () => _gvNode(graph.nodes.get(edge.sourceId)),
    target: () => _gvNode(graph.nodes.get(edge.targetId)),
  };
}

function _gvEdgesFrom(edgeIds) {
  if (!edgeIds) return [];
  return [...edgeIds].map((id) => _gvEdge(graph.edges.get(id))).filter(Boolean);
}

const gv = {
  /** Is there a graph to talk to yet? */
  ready() {
    return graph.nodes.size > 0;
  },

  // ── Viewport ──────────────────────────────────────────────────────────────
  //
  // Owned by editor-viewport.js.

  /** Pan offset in screen pixels. A snapshot, never a live reference. */
  pan() {
    return {x: viewport.pan.x, y: viewport.pan.y};
  },

  zoom() {
    return viewport.zoom;
  },

  /** Zoom to `level`, holding the container-relative `screenPoint` fixed. */
  setZoom(level, screenPoint) {
    setViewportZoom(level, screenPoint);
  },

  /** Size of the drawing surface, in screen pixels. */
  width() {
    return viewportWidth();
  },

  height() {
    return viewportHeight();
  },

  /** Fires whenever pan or zoom changes. Handlers must stay O(1). */
  onViewportChange(cb) {
    onViewportChanged(cb);
    return true;
  },

  /** Suspend background panning while another gesture owns the pointer. */
  userPanningEnabled(enabled) {
    if (enabled === undefined) return viewport.userPanningEnabled;
    viewport.userPanningEnabled = enabled;
    return enabled;
  },

  /** Smoothly bring a node to the centre of the surface. */
  centerOn(node, durationMs) {
    animateViewportTo(node.position(), durationMs);
  },

  // ── Graph ─────────────────────────────────────────────────────────────────

  node(id) {
    return _gvNode(graph.nodes.get(id));
  },

  edge(id) {
    return _gvEdge(graph.edges.get(id));
  },

  nodes() {
    return [...graph.nodes.values()].map(_gvNode);
  },

  edges() {
    return [...graph.edges.values()].map(_gvEdge);
  },

  /** Fn cards — the ones that carry an ancestor list and metadata strips. */
  fnNodes() {
    return gv.nodes().filter((n) => n.data('type') === 'fn' && !n.data('isPlaceholder'));
  },

  /** Bound argument values, rendered as small cards. */
  argNodes() {
    return gv.nodes().filter((n) => n.data('type') === 'arg');
  },

  /** Unbound slots, rendered as a `+` binder for editable viewers. */
  placeholderNodes() {
    return gv.nodes().filter((n) => n.data('isPlaceholder'));
  },

  // ── Edge hover ────────────────────────────────────────────────────────────
  //
  // Hovering a fn card lights the whole bundle of edges leaving it. How that is
  // painted is the drawing layer's business (editor-edges-svg.js), so callers
  // name the intent and never touch a class.

  highlightEdgesFrom(nodeId) {
    highlightEdgesFromNode(nodeId);
  },

  clearEdgeHighlight() {
    clearEdgeHighlightSvg();
  },
};
