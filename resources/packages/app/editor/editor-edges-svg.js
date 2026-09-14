// Editor Edges (SVG) — draws the taxi edges and answers hover hit-tests.
// Depends on: editor-layout.js (taxiBendX), editor-graph-view.js (gv),
// editor-overlay-manager.js (getGraphLayer, EDGE_LABEL_POST_BEND_GAP).
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

// ── Sequence groups ─────────────────────────────────────────────────────────
//
// The items of one list (edges sharing a layout-emitted `seqGroup`) are not N
// look-alike args: they draw as ONE TRUNK — source, bend, the group's single
// label — that FANS OUT after the label's type chip, one BRANCH per item, the
// last branch reaching the list's append tail (a free `+` placeholder). So a
// plain arg splits off at the bend before its chip and a list element splits
// off after it — the difference the eye is asked to read.
//
// The fan-out point is wherever the overlay manager placed the group's label;
// it reports back through `setSeqLabelAnchor` from the same geometry pass
// that positions the labels, and the trunk / branches are re-emitted right
// after. Before a label exists the branches bend at a fallback past the trunk
// bend, so the first paint is still a fan and not a pile of crossings.
const SEQ_TRUNK_PREFIX = 'seq:';
const SEQ_FAN_GAP = 14;             // label's right edge → the branches' bend
const SEQ_FALLBACK_LABEL_WIDTH = 48; // until the label has been measured
const _seqGroups = new Map();       // groupId → {sourceId, members: [edgeId…]}
const _seqLabelAnchors = new Map(); // groupId → {left, right, y}

function seqTrunkId(groupId) { return SEQ_TRUNK_PREFIX + groupId; }

function isSeqTrunkId(id) { return typeof id === 'string' && id.startsWith(SEQ_TRUNK_PREFIX); }

/** Called by the overlay manager once it has placed a group's label. */
function setSeqLabelAnchor(groupId, anchor) { _seqLabelAnchors.set(groupId, anchor); }

/** Vertical centre of a group's member targets — where its trunk ends and its label sits. */
function seqGroupCentreY(groupId) {
  const g = _seqGroups.get(groupId);
  if (!g) return null;
  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  for (const id of g.members) {
    const t = gv.edge(id)?.target();
    if (!t) continue;
    const y = t.position().y;
    if (y < min) min = y;
    if (y > max) max = y;
  }
  return min === Number.POSITIVE_INFINITY ? null : (min + max) / 2;
}

function seqAnchorFor(groupId, source) {
  const placed = _seqLabelAnchors.get(groupId);
  if (placed) return placed;
  const left = taxiBendX(source) + EDGE_LABEL_POST_BEND_GAP;
  return { left, right: left + SEQ_FALLBACK_LABEL_WIDTH,
           y: seqGroupCentreY(groupId) ?? source.position().y };
}

/** Source's right edge → the trunk bend → down to the label → its left edge. */
function seqTrunkPath(source, anchor) {
  const s = source.position();
  const srcRight = s.x + source.width() / 2;
  return 'M' + srcRight + ',' + s.y
       + 'H' + taxiBendX(source)
       + 'V' + anchor.y
       + 'H' + anchor.left;
}

/** Label's right edge → the fan bend → up/down to the item → its left edge. */
function seqBranchPath(target, anchor) {
  const t = target.position();
  const tgtLeft = t.x - target.width() / 2;
  // A label wider than the column gap reserved for it would put the fan
  // bend past the item; never bend backwards.
  const bendX = Math.min(anchor.right + SEQ_FAN_GAP, tgtLeft - 1);
  return 'M' + anchor.right + ',' + anchor.y
       + 'H' + bendX
       + 'V' + t.y
       + 'H' + tgtLeft;
}


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


/** One visible path + its fat hit twin, registered under `id`. */
function addEdgePaths(svg, id, d, className, markers, entry) {
  const hit = document.createElementNS(SVG_NS, 'path');
  hit.setAttribute('class', 'edge-hit');
  hit.setAttribute('d', d);
  hit.dataset.edgeId = id;
  svg.querySelector('#edge-hits').appendChild(hit);

  const line = document.createElementNS(SVG_NS, 'path');
  line.setAttribute('class', className);
  line.setAttribute('d', d);
  if (markers.start) line.setAttribute('marker-start', 'url(#gd-edge-source)');
  if (markers.end) line.setAttribute('marker-end', 'url(#gd-edge-target)');
  line.dataset.edgeId = id;
  svg.querySelector('#edge-lines').appendChild(line);

  _edgeGroupsByEdgeId.set(id, {hit, line, ...entry});
}


/** Rebuild the edge elements from the current graph. */
function renderEdges() {
  const svg = getEdgeLayer();
  if (!svg) return;
  svg.querySelector('#edge-hits').replaceChildren();
  svg.querySelector('#edge-lines').replaceChildren();
  _edgeGroupsByEdgeId.clear();
  _seqGroups.clear();
  _seqLabelAnchors.clear();

  for (const edge of gv.edges()) {
    const source = edge.source();
    const target = edge.target();
    if (!source || !target) continue;
    const id = edge.id();
    // Unified-arg-edges: every unset arg is an edge; PROVENANCE is a
    // style gradation, not a different UI. Flags come from the layout
    // emitter (add-unset-arg-node).
    const ed = edge.data();
    const flags = (ed.isUnset ? ' edge-unset' : '')
      + (ed.optionalArg ? ' edge-optional' : '')
      + (ed.lambdaArg ? ' edge-lambda' : '')
      + (ed.deepArg ? ' edge-deep' : '');

    const groupId = ed.seqGroup;
    if (groupId) {
      let g = _seqGroups.get(groupId);
      if (!g) {
        g = {sourceId: source.id(), members: []};
        _seqGroups.set(groupId, g);
        // The trunk carries the source dot; a solid line even when every
        // item is still unset — the list itself is bound, its slots are not.
        addEdgePaths(svg, seqTrunkId(groupId), seqTrunkPath(source, seqAnchorFor(groupId, source)),
                     'edge-line edge-seq-trunk', {start: true, end: false},
                     {sourceId: source.id(), groupId});
      }
      g.members.push(id);
      addEdgePaths(svg, id, seqBranchPath(target, seqAnchorFor(groupId, source)),
                   'edge-line edge-seq-branch' + flags, {start: false, end: true},
                   {sourceId: source.id(), groupId});
      continue;
    }

    addEdgePaths(svg, id, taxiPath(source, target), 'edge-line' + flags,
                 {start: true, end: true}, {sourceId: source.id()});
  }
  syncEdgeGeometry();
  applyEdgeStrokeWidths();
}


/** Re-emit every `d` after nodes moved (or labels were placed). O(edges). */
function syncEdgeGeometry() {
  for (const [edgeId, els] of _edgeGroupsByEdgeId) {
    let d;
    if (isSeqTrunkId(edgeId)) {
      const source = gv.node(els.sourceId);
      if (!source) continue;
      d = seqTrunkPath(source, seqAnchorFor(els.groupId, source));
    } else {
      const edge = gv.edge(edgeId);
      if (!edge) continue;
      const source = edge.source();
      const target = edge.target();
      if (!source || !target) continue;
      d = els.groupId
        ? seqBranchPath(target, seqAnchorFor(els.groupId, source))
        : taxiPath(source, target);
    }
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

/**
 * A list lights as a whole: hovering a branch lights its trunk, hovering the
 * trunk lights every branch — the fan IS one edge of the source fn.
 */
function withSeqGroupClosure(edgeIds) {
  const out = new Set(edgeIds);
  for (const id of edgeIds) {
    if (isSeqTrunkId(id)) {
      for (const m of _seqGroups.get(id.slice(SEQ_TRUNK_PREFIX.length))?.members || []) out.add(m);
    } else {
      const groupId = _edgeGroupsByEdgeId.get(id)?.groupId;
      if (groupId) out.add(seqTrunkId(groupId));
    }
  }
  return out;
}

function setEdgesHovered(edgeIds) {
  const lit = withSeqGroupClosure(edgeIds);
  for (const [id, els] of _edgeGroupsByEdgeId) {
    els.line.classList.toggle('edge-hovered', lit.has(id));
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
    // A trunk has no model edge of its own; its source is the group's.
    const source = gv.node(_edgeGroupsByEdgeId.get(id)?.sourceId);
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
