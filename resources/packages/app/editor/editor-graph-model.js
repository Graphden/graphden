// Editor Graph Model — the nodes and edges, and the tween that moves them.
// Depends on: editor-state.js (ANIM_DURATION).
//
// This is what cytoscape had been reduced to. It drew nothing (nodes were
// invisible footprints under HTML overlays, edges became SVG), it ran no layout
// (positions come from the server), it handled no gesture (the wheel, the pan,
// the pinch and the node drag are all ours), and it owned no viewport. What
// remained was two maps and an animation, which is what lives here.
//
// A node's size is its layout size, stamped onto `data` by editor-layout.js.
// The fallbacks are the ones cytoscape's style functions used.

const DEFAULT_FN_SIZE = {width: 80, height: 30};
const DEFAULT_ARG_SIZE = {width: 40, height: 36};

const graph = {
  /** id → {id, data, x, y} */
  nodes: new Map(),
  /** id → {id, data, sourceId, targetId} */
  edges: new Map(),
  /** node id → Set of edge ids, for the two directions */
  outgoing: new Map(),
  incoming: new Map(),
  /** True while a position tween is in flight. */
  animating: false,
};

// Exposed for browser tests; the page holds exactly one graph. `nodeList()` and
// `edgeList()` hand back plain arrays so a test can read `.length` / `.map`, and
// `animating()` / `ready()` are the two states tests wait on — the same surface
// the old `window.cy` offered through `cy.nodes()` / `cy.animated()`.
window.graph = graph;
window.graphReady = graphReady;
window.graphView = {
  nodeList: () => [...graph.nodes.values()],
  edgeList: () => [...graph.edges.values()],
  animating: () => graph.animating,
  ready: () => graphReady(),
};

/** True once there is a graph to talk to. Tests wait on this. */
function graphReady() {
  return graph.nodes.size > 0;
}


function nodeDefaultSize(node) {
  return node.data?.type === 'arg' ? DEFAULT_ARG_SIZE : DEFAULT_FN_SIZE;
}

function nodeWidth(node) {
  return node.data?.layoutWidth || nodeDefaultSize(node).width;
}

function nodeHeight(node) {
  return node.data?.layoutHeight || nodeDefaultSize(node).height;
}


// ── Mutation ────────────────────────────────────────────────────────────────

function graphAddNode(spec) {
  const id = spec.data.id;
  graph.nodes.set(id, {
    id,
    data: {...spec.data},
    x: spec.position?.x ?? 0,
    y: spec.position?.y ?? 0,
  });
}

function graphAddEdge(spec) {
  const id = spec.data.id;
  const sourceId = spec.data.source;
  const targetId = spec.data.target;
  graph.edges.set(id, {id, data: {...spec.data}, sourceId, targetId});
  if (!graph.outgoing.has(sourceId)) graph.outgoing.set(sourceId, new Set());
  if (!graph.incoming.has(targetId)) graph.incoming.set(targetId, new Set());
  graph.outgoing.get(sourceId).add(id);
  graph.incoming.get(targetId).add(id);
}

function graphRemoveEdge(id) {
  const edge = graph.edges.get(id);
  if (!edge) return;
  graph.outgoing.get(edge.sourceId)?.delete(id);
  graph.incoming.get(edge.targetId)?.delete(id);
  graph.edges.delete(id);
}

function graphRemoveNode(id) {
  for (const edgeId of [...(graph.outgoing.get(id) || [])]) graphRemoveEdge(edgeId);
  for (const edgeId of [...(graph.incoming.get(id) || [])]) graphRemoveEdge(edgeId);
  graph.outgoing.delete(id);
  graph.incoming.delete(id);
  graph.nodes.delete(id);
}

/**
 * Replace a node's data outright.
 *
 * Cytoscape's `.data(obj)` MERGED, so a field the backend stopped sending stuck
 * around on the element — that is how a collapsed expansion kept drawing a
 * stale `typeChain` on its edge. A plain assignment has no such trap, and the
 * `replaceData` workaround it needed is gone.
 */
function graphSetNodeData(id, data) {
  const node = graph.nodes.get(id);
  if (node && data) node.data = {...data};
}

function graphSetEdgeData(id, data) {
  const edge = graph.edges.get(id);
  if (edge && data) edge.data = {...data};
}


// ── Reading ─────────────────────────────────────────────────────────────────

function graphNodeIds() {
  return [...graph.nodes.keys()];
}

/** Bounding box of every node, in graph coordinates. Empty graph → null. */
function graphBoundingBox() {
  if (graph.nodes.size === 0) return null;
  let x1 = Infinity;
  let y1 = Infinity;
  let x2 = -Infinity;
  let y2 = -Infinity;
  for (const node of graph.nodes.values()) {
    const w = nodeWidth(node);
    const h = nodeHeight(node);
    x1 = Math.min(x1, node.x - w / 2);
    y1 = Math.min(y1, node.y - h / 2);
    x2 = Math.max(x2, node.x + w / 2);
    y2 = Math.max(y2, node.y + h / 2);
  }
  return {x1, y1, x2, y2, w: x2 - x1, h: y2 - y1};
}


// ── Animation ───────────────────────────────────────────────────────────────

let _animToken = 0;

/** Cancel any tween in flight. The nodes stay wherever they got to. */
function graphStopAnimation() {
  _animToken++;
  graph.animating = false;
}

function easeOutCubic(t) {
  return 1 - (1 - t) ** 3;
}

// Nodes arriving decelerate into place; nodes leaving accelerate away.
function easeInCubic(t) {
  return t * t * t;
}

/**
 * Ease every node in `targets` (id → {x, y}) to its destination, calling
 * `onFrame` each frame so the overlays and edge paths can follow. Resolves when
 * the last one lands, or immediately if there is nothing to move.
 */
function graphAnimateNodes(targets, durationMs, onFrame, easing = easeOutCubic) {
  const moving = [];
  for (const [id, to] of targets) {
    const node = graph.nodes.get(id);
    if (!node) continue;
    if (Math.abs(node.x - to.x) <= 1 && Math.abs(node.y - to.y) <= 1) {
      node.x = to.x;
      node.y = to.y;
      continue;
    }
    moving.push({node, from: {x: node.x, y: node.y}, to});
  }
  if (moving.length === 0) {
    if (onFrame) onFrame();
    return Promise.resolve();
  }

  // Reduced motion: land every node on its destination in one frame.
  // The layout still changes — only the travel between the two states
  // is dropped, which is exactly what the preference asks for.
  if (window.prefersReducedMotion && window.prefersReducedMotion()) {
    for (const m of moving) {
      m.node.x = m.to.x;
      m.node.y = m.to.y;
    }
    if (onFrame) onFrame();
    return Promise.resolve();
  }

  const token = ++_animToken;
  graph.animating = true;
  const start = performance.now();
  return new Promise((resolve) => {
    const step = (now) => {
      // A newer animation (or a stop) took over — abandon this one silently.
      if (token !== _animToken) return resolve();
      const t = Math.min(1, (now - start) / durationMs);
      const k = easing(t);
      for (const m of moving) {
        m.node.x = m.from.x + (m.to.x - m.from.x) * k;
        m.node.y = m.from.y + (m.to.y - m.from.y) * k;
      }
      if (onFrame) onFrame();
      if (t < 1) {
        requestAnimationFrame(step);
      } else {
        graph.animating = false;
        resolve();
      }
    };
    requestAnimationFrame(step);
  });
}

/**
 * Fade an overlay out over `durationMs`. Removed nodes fly to their parent and
 * dissolve; cytoscape animated a `style.opacity` it was not drawing anyway, so
 * the fade always belonged to the overlay.
 */
function fadeOutOverlay(overlay, durationMs) {
  if (!overlay) return;
  overlay.style.transition = 'opacity ' + durationMs + 'ms ease-in';
  overlay.style.opacity = '0';
}
