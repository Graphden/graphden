// Editor Graph View — the seam between the editor and whatever draws the graph.
// Depends on: editor-state.js (`cy`).
//
// Everything outside editor-cytoscape.js talks to the graph through `gv`. The
// calls that used to be spread across seven modules were all the same handful:
// read the viewport, read a node's position/size, walk a node's edges, toggle
// the edge-hover highlight. None of them needs cytoscape specifically —
// cytoscape draws no nodes here (they are invisible footprints under HTML
// overlays), runs no layout (positions come from the server, applied as
// `preset`), and owns neither selection nor dragging (both disabled).
//
// So this is a narrow contract, not a cytoscape wrapper: the backing store can
// be swapped without touching a consumer.
//
// The element shape (`id()` / `data()` / `position()` / `width()` / `height()`)
// deliberately mirrors the five methods the overlay builders already call, so
// they read a graph element without caring who produced it.
//
// `cy` is read lazily on every call — it is a `let` in editor-state.js that
// stays null until the first `createCytoscape`, and this module loads long
// before that.

/** Wrap one cytoscape node. Returns null for an empty collection. */
function _gvNode(el) {
  if (!el?.length) return null;
  return {
    id: () => el.id(),
    data: (key) => (key === undefined ? el.data() : el.data(key)),
    position: (p) => {
      if (p !== undefined) return el.position(p);
      // Cytoscape hands back a live reference; snapshot the primitives.
      const cur = el.position();
      return {x: cur.x, y: cur.y};
    },
    width: () => el.width(),
    height: () => el.height(),
    /** Record the card's real height, once the DOM has told us what it is. */
    setHeight: (h) => el.data('layoutHeight', h),
    incomingEdges: () => el.incomers('edge').map(_gvEdge),
    outgoingEdges: () => el.outgoers('edge').map(_gvEdge),
  };
}

/** Wrap one cytoscape edge. Returns null for an empty collection. */
function _gvEdge(el) {
  if (!el?.length) return null;
  return {
    id: () => el.id(),
    data: (key) => (key === undefined ? el.data() : el.data(key)),
    source: () => _gvNode(el.source()),
    target: () => _gvNode(el.target()),
  };
}

const gv = {
  /** Is there a graph to talk to yet? */
  ready() {
    return typeof cy !== 'undefined' && !!cy;
  },

  // ── Viewport ──────────────────────────────────────────────────────────────
  //
  // Owned by editor-viewport.js. Cytoscape's viewport is inert: it paints
  // nothing, so its pan and zoom are never read.

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
    return gv.ready() ? _gvNode(cy.getElementById(id)) : null;
  },

  edge(id) {
    return gv.ready() ? _gvEdge(cy.getElementById(id)) : null;
  },

  /** All nodes, or those matching a backend-specific selector. */
  nodes(selector) {
    if (!gv.ready()) return [];
    return (selector ? cy.nodes(selector) : cy.nodes()).map(_gvNode);
  },

  edges() {
    return gv.ready() ? cy.edges().map(_gvEdge) : [];
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
