// Editor Edges (SVG) — draws the taxi edges and answers hover hit-tests.
// Depends on: editor-layout.js (taxiBendX), editor-graph-view.js (gv),
// editor-overlay-manager.js (getGraphLayer).
//
// The edge layer lives INSIDE `#graph-layer`, so it inherits the viewport
// transform: paths are emitted in graph coordinates and never re-projected.
//
// Two things collapse by moving off the canvas.
//
// The path and the hit-zone are now the same geometry. Cytoscape drew the bend
// from a `taxi-turn` style function while a hand-written three-segment model
// re-derived it in JS for hover, because cytoscape's hit-test picks only one of
// several overlapping vertical runs. An SVG path is its own hit-zone: a fat
// transparent stroke over the same `d`, and `elementsFromPoint` returns every
// edge under the cursor, overlaps included.
//
// And the theme is just CSS. `stroke: var(--fg)` re-resolves itself when the
// body class flips; the canvas stylesheet had to be rebuilt by hand.

const EDGE_LAYER_ID = 'edge-layer';
const SVG_NS = 'http://www.w3.org/2000/svg';

// Cytoscape drew edges 2 units wide but never thinner than 0.75 screen px, or
// sub-pixel rendering makes them disappear at low zoom. Same formula, applied
// once to the group rather than per edge.
const BASE_EDGE_WIDTH = 2;
const MIN_EDGE_PIXELS = 0.75;
// The invisible hit stroke, in SCREEN pixels — a constant grab target at any
// zoom. Cytoscape's equivalent was a graph-unit tolerance, which made edges
// harder to hit the further you zoomed out.
const EDGE_HIT_PIXELS = 12;
// How close (graph units) the cursor must come to the source endpoint for the
// hover to mean "the whole bundle leaving this fn", not "this one edge".
const SOURCE_ENDPOINT_RADIUS = 14;

let _edgeLayer = null;
const _edgeGroupsByEdgeId = new Map();


/** The `<svg>` under `#graph-layer`, created on first use. */
function getEdgeLayer() {
  if (_edgeLayer?.isConnected) return _edgeLayer;
  const parent = getGraphLayer();
  if (!parent) return null;

  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.id = EDGE_LAYER_ID;
  // Hidden from the accessibility tree: these paths carry no text and no
  // name, so a screen reader would walk a pile of anonymous graphics.
  // The connections they draw are conveyed on the nodes themselves (the
  // fn cards name their arguments and their producer).
  svg.setAttribute('aria-hidden', 'true');
  // Zero-sized with `overflow: visible` (set in CSS): the layer has no extent
  // of its own, and graph coordinates run negative in both axes.
  svg.appendChild(buildEdgeMarkers());

  const hits = document.createElementNS(SVG_NS, 'g');
  hits.id = 'edge-hits';
  const lines = document.createElementNS(SVG_NS, 'g');
  lines.id = 'edge-lines';
  svg.append(hits, lines);

  // Insert before the overlays so edges paint underneath the cards.
  parent.insertBefore(svg, parent.firstChild);
  _edgeLayer = svg;
  installEdgeHoverHandlers(svg);
  return svg;
}


// Direction markers, one at each end, different shapes so an edge reads
// "arg-slot of the source fn ◯──▸ the fn that produces its value".
// `markerUnits="userSpaceOnUse"` keeps them sized in graph units, so they scale
// with the layer exactly as the canvas arrows scaled with zoom.
// `fill="context-stroke"` makes them follow the line's colour, which is how a
// hovered edge tints its arrowheads without a second rule.
function buildEdgeMarkers() {
  const defs = document.createElementNS(SVG_NS, 'defs');

  const source = document.createElementNS(SVG_NS, 'marker');
  source.setAttribute('id', 'gd-edge-source');
  source.setAttribute('markerUnits', 'userSpaceOnUse');
  source.setAttribute('markerWidth', '8');
  source.setAttribute('markerHeight', '8');
  source.setAttribute('refX', '4');
  source.setAttribute('refY', '4');
  source.setAttribute('orient', 'auto');
  const circle = document.createElementNS(SVG_NS, 'circle');
  circle.setAttribute('cx', '4');
  circle.setAttribute('cy', '4');
  circle.setAttribute('r', '3');
  circle.setAttribute('fill', 'context-stroke');
  source.appendChild(circle);

  const target = document.createElementNS(SVG_NS, 'marker');
  target.setAttribute('id', 'gd-edge-target');
  target.setAttribute('markerUnits', 'userSpaceOnUse');
  target.setAttribute('markerWidth', '9');
  target.setAttribute('markerHeight', '8');
  target.setAttribute('refX', '9');
  target.setAttribute('refY', '4');
  target.setAttribute('orient', 'auto');
  const tri = document.createElementNS(SVG_NS, 'path');
  tri.setAttribute('d', 'M0,0 L9,4 L0,8 Z');
  tri.setAttribute('fill', 'context-stroke');
  target.appendChild(tri);

  defs.append(source, target);
  return defs;
}


/**
 * The three segments of a taxi edge, in graph coordinates: out of the source's
 * right edge, down (or up) at the bend, then into the target's left edge.
 * `taxiBendX` is shared with the edge-label anchor, so a label can never land
 * on the wrong side of the line.
 */
function taxiPath(source, target) {
  const s = source.position();
  const t = target.position();
  const srcRight = s.x + source.width() / 2;
  const tgtLeft = t.x - target.width() / 2;
  const bendX = taxiBendX(source);
  return 'M' + srcRight + ',' + s.y
       + 'H' + bendX
       + 'V' + t.y
       + 'H' + tgtLeft;
}


/** Rebuild the edge elements from the current graph. */
function renderEdges() {
  const svg = getEdgeLayer();
  if (!svg) return;
  const hits = svg.querySelector('#edge-hits');
  const lines = svg.querySelector('#edge-lines');
  hits.replaceChildren();
  lines.replaceChildren();
  _edgeGroupsByEdgeId.clear();

  for (const edge of gv.edges()) {
    const source = edge.source();
    const target = edge.target();
    if (!source || !target) continue;
    const id = edge.id();
    const d = taxiPath(source, target);

    const hit = document.createElementNS(SVG_NS, 'path');
    hit.setAttribute('class', 'edge-hit');
    hit.setAttribute('d', d);
    hit.dataset.edgeId = id;
    hits.appendChild(hit);

    const line = document.createElementNS(SVG_NS, 'path');
    // Unified-arg-edges: every unset arg is an edge; PROVENANCE is a
    // style gradation, not a different UI. Flags come from the layout
    // emitter (add-unset-arg-node).
    const ed = edge.data();
    line.setAttribute('class', 'edge-line'
      + (ed.isUnset ? ' edge-unset' : '')
      + (ed.optionalArg ? ' edge-optional' : '')
      + (ed.lambdaArg ? ' edge-lambda' : '')
      + (ed.deepArg ? ' edge-deep' : ''));
    line.setAttribute('d', d);
    line.setAttribute('marker-start', 'url(#gd-edge-source)');
    line.setAttribute('marker-end', 'url(#gd-edge-target)');
    line.dataset.edgeId = id;
    lines.appendChild(line);

    _edgeGroupsByEdgeId.set(id, {hit, line, sourceId: source.id()});
  }
  syncEdgeGeometry();
  applyEdgeStrokeWidths();
}


/** Re-emit every `d` after nodes moved. O(edges); animation frames and drags. */
function syncEdgeGeometry() {
  for (const [edgeId, els] of _edgeGroupsByEdgeId) {
    const edge = gv.edge(edgeId);
    if (!edge) continue;
    const source = edge.source();
    const target = edge.target();
    if (!source || !target) continue;
    const d = taxiPath(source, target);
    els.hit.setAttribute('d', d);
    els.line.setAttribute('d', d);
  }
}


/**
 * Keep the visible line legible and the hit zone grabbable at any zoom. Two
 * attribute writes on two groups — the children inherit. O(1), so this can ride
 * the pan/zoom handler.
 */
function applyEdgeStrokeWidths() {
  const svg = getEdgeLayer();
  if (!svg || !gv.ready()) return;
  const zoom = gv.zoom();
  svg.querySelector('#edge-lines')
     .setAttribute('stroke-width', Math.max(BASE_EDGE_WIDTH, MIN_EDGE_PIXELS / zoom));
  svg.querySelector('#edge-hits')
     .setAttribute('stroke-width', EDGE_HIT_PIXELS / zoom);
}


// ── Hover ───────────────────────────────────────────────────────────────────

function setEdgesHovered(edgeIds) {
  for (const [id, els] of _edgeGroupsByEdgeId) {
    els.line.classList.toggle('edge-hovered', edgeIds.has(id));
  }
}

function clearEdgeHighlightSvg() {
  for (const els of _edgeGroupsByEdgeId.values()) {
    els.line.classList.remove('edge-hovered');
  }
}

/** Every edge leaving `nodeId` — what hovering the fn card itself lights up. */
function edgeIdsFromNode(nodeId) {
  const ids = new Set();
  for (const [id, els] of _edgeGroupsByEdgeId) {
    if (els.sourceId === nodeId) ids.add(id);
  }
  return ids;
}

function highlightEdgesFromNode(nodeId) {
  setEdgesHovered(edgeIdsFromNode(nodeId));
}

/** Screen point → graph coordinates, by inverting the layer transform. */
function screenToGraph(clientX, clientY) {
  const container = document.getElementById('graph-surface').getBoundingClientRect();
  const pan = gv.pan();
  const zoom = gv.zoom();
  return {
    x: (clientX - container.left - pan.x) / zoom,
    y: (clientY - container.top - pan.y) / zoom,
  };
}

/**
 * Which edges the pointer is over. `elementsFromPoint` hands back EVERY hit
 * path under the cursor, so the overlapping vertical runs that share a bend all
 * light together — the thing the old three-segment model existed to reproduce.
 *
 * Near a source endpoint the answer widens to that fn's whole outgoing bundle:
 * the circle marker reads as a pin on the fn, so hovering it should act at fn
 * level.
 */
function edgesUnderPointer(clientX, clientY) {
  const ids = new Set();
  for (const el of document.elementsFromPoint(clientX, clientY)) {
    const id = el.dataset?.edgeId;
    if (id && _edgeGroupsByEdgeId.has(id)) ids.add(id);
  }
  if (ids.size === 0) return ids;

  const p = screenToGraph(clientX, clientY);
  for (const id of ids) {
    const edge = gv.edge(id);
    const source = edge?.source();
    if (!source) continue;
    const s = source.position();
    const dx = p.x - (s.x + source.width() / 2);
    const dy = p.y - s.y;
    if (dx * dx + dy * dy <= SOURCE_ENDPOINT_RADIUS * SOURCE_ENDPOINT_RADIUS) {
      return edgeIdsFromNode(source.id());
    }
  }
  return ids;
}

function installEdgeHoverHandlers(svg) {
  const onPoint = (e) => {
    const pt = e.touches?.[0] || e;
    setEdgesHovered(edgesUnderPointer(pt.clientX, pt.clientY));
  };
  // Only the hit paths take pointer events, so these fire exactly on an edge.
  svg.addEventListener('mousemove', onPoint);
  svg.addEventListener('mouseover', onPoint);
  svg.addEventListener('mouseout', clearEdgeHighlightSvg);
  // A finger doesn't hover; tap-and-drag mirrors it, lifting clears.
  svg.addEventListener('touchstart', onPoint, {passive: true});
  svg.addEventListener('touchmove', onPoint, {passive: true});
  svg.addEventListener('touchend', clearEdgeHighlightSvg);
}
