// Graph canvas — keyboard navigation.
//
// The canvas was the last mouse-only surface: node cards are `div`s whose
// body carries a click handler, with no tabindex and no accessible name, so
// the graph — the thing the whole editor is for — could not be read or
// operated from the keyboard at all.
//
// Movement follows the EDGES rather than the screen. On this canvas an edge
// runs from a function to the argument that feeds it, laid out left to right,
// so:
//
//     ← / h   the consumer   (incoming edge — who uses this)
//     → / l   an argument    (outgoing edge — what this is built from)
//     ↑ / k   ↓ / j          siblings, ordered by vertical position
//
// That is a different thing from "the node visually left of here", and it is
// the right one: the graph's meaning is its wiring, and following wiring is
// how a blind user builds the same mental picture a sighted user gets from
// the layout.
//
// Focus and the viewport are coupled by hand. `scrollIntoView` cannot be used
// on the canvas: `#graph-layer` is positioned by a single CSS transform, so
// the browser has no scroll box to move — panning is a write to
// `viewport.pan`, and focusing an off-screen card has to pan there itself.

const CANVAS_STEP_PX = 90;      // pan per arrow press at the canvas level
const NODE_MARGIN_PX = 40;      // keep this much clearance when panning to a node

function canvasSurface() {
  return document.getElementById('graph-container');
}

function overlayFor(nodeId) {
  return document.querySelector('.node-overlay[data-node-id="' + nodeId + '"]');
}

function nodeIdOf(el) {
  const overlay = el?.closest?.('.node-overlay');
  return overlay ? overlay.dataset.nodeId : null;
}

/** Every node overlay currently on the canvas, in layout order. */
function canvasNodes() {
  return Array.from(document.querySelectorAll('.node-overlay'));
}

// ── Roving tabindex ─────────────────────────────────────────────────────────
//
// Overlays are rebuilt on every render, so — as in the Explorer tree — the
// current node is remembered by ID and re-applied afterwards.

let _currentNodeId = null;

function setCurrentNode(nodeId) {
  _currentNodeId = nodeId;
  for (const el of canvasNodes()) {
    el.setAttribute('tabindex', el.dataset.nodeId === nodeId ? '0' : '-1');
  }
}

/**
 * Re-place the single tab stop after a re-render. Falls back to the root
 * node so Tab always has somewhere to land.
 */
function refreshCanvasTabStop() {
  const nodes = canvasNodes();
  if (nodes.length === 0) return;
  const existing = _currentNodeId && overlayFor(_currentNodeId);
  if (existing) {
    setCurrentNode(_currentNodeId);
    return;
  }
  const root = nodes.find((el) => el.classList.contains('gd-node-active')) || nodes[0];
  setCurrentNode(root.dataset.nodeId);
}

// ── Viewport ────────────────────────────────────────────────────────────────

/**
 * Pan just enough to bring `nodeId` fully on screen.
 *
 * Only moves when the node is actually outside the visible area — otherwise
 * every arrow press would re-centre the graph and the user would lose the
 * spatial context they are navigating by.
 */
function ensureNodeVisible(nodeId) {
  const el = overlayFor(nodeId);
  const surface = canvasSurface();
  if (!el || !surface) return;

  const box = el.getBoundingClientRect();
  const view = surface.getBoundingClientRect();
  let dx = 0;
  let dy = 0;
  if (box.left < view.left + NODE_MARGIN_PX) dx = view.left + NODE_MARGIN_PX - box.left;
  else if (box.right > view.right - NODE_MARGIN_PX) dx = view.right - NODE_MARGIN_PX - box.right;
  if (box.top < view.top + NODE_MARGIN_PX) dy = view.top + NODE_MARGIN_PX - box.top;
  else if (box.bottom > view.bottom - NODE_MARGIN_PX) dy = view.bottom - NODE_MARGIN_PX - box.bottom;
  if (dx === 0 && dy === 0) return;

  setViewportPan(viewport.pan.x + dx, viewport.pan.y + dy);
  applyViewportTransform();
}

// ── Announcing ──────────────────────────────────────────────────────────────

function describeNode(nodeId) {
  const el = overlayFor(nodeId);
  if (!el) return '';
  const label = el.getAttribute('aria-label');
  if (label) return label;
  return (el.textContent || '').trim().split('\n')[0].slice(0, 60);
}

function announceNode(nodeId) {
  if (typeof window.gdAnnounce !== 'function') return;
  const node = gv.node(nodeId);
  let suffix = '';
  if (node) {
    const args = node.outgoingEdges().length;
    const users = node.incomingEdges().length;
    // Say what can be reached from here — on a canvas the edges ARE the
    // structure, and a screen reader gets no picture of them otherwise.
    const parts = [];
    if (args) parts.push(args + (args === 1 ? ' argument' : ' arguments'));
    if (users) parts.push(users === 1 ? '1 consumer' : users + ' consumers');
    if (parts.length) suffix = ', ' + parts.join(', ');
  }
  window.gdAnnounce(describeNode(nodeId) + suffix);
}

// ── Movement ────────────────────────────────────────────────────────────────

function focusNode(nodeId, {announce = true} = {}) {
  const el = overlayFor(nodeId);
  if (!el) return false;
  setCurrentNode(nodeId);
  ensureNodeVisible(nodeId);
  focusSafely(el);
  if (announce) announceNode(nodeId);
  return true;
}

/** Nodes one edge away, in the given direction. */
function neighbours(nodeId, direction) {
  const node = gv.node(nodeId);
  if (!node) return [];
  const edges = direction === 'out' ? node.outgoingEdges() : node.incomingEdges();
  return edges
    .map((e) => (direction === 'out' ? e.target() : e.source()))
    .filter(Boolean)
    .filter((n) => overlayFor(n.id()));
}

/** Of several candidates, the one closest to `from` vertically. */
function nearestVertically(from, candidates) {
  const y = from.position().y;
  return candidates.slice().sort(
    (a, b) => Math.abs(a.position().y - y) - Math.abs(b.position().y - y))[0];
}

function moveAlongEdge(nodeId, direction) {
  const node = gv.node(nodeId);
  if (!node) return false;
  const options = neighbours(nodeId, direction);
  if (options.length === 0) {
    if (typeof window.gdAnnounce === 'function') {
      window.gdAnnounce(direction === 'out' ? 'No arguments' : 'No consumers');
    }
    return false;
  }
  return focusNode(nearestVertically(node, options).id());
}

/**
 * Move to the node above or below among those sharing this column.
 *
 * Siblings are defined structurally — the other arguments of the same
 * consumer — falling back to raw geometry for a node with no consumer (the
 * root), so the key always does something sensible.
 */
function moveVertically(nodeId, dir) {
  const node = gv.node(nodeId);
  if (!node) return false;
  const consumers = neighbours(nodeId, 'in');
  let pool;
  if (consumers.length > 0) {
    pool = neighbours(consumers[0].id(), 'out');
  } else {
    const x = node.position().x;
    pool = canvasNodes()
      .map((el) => gv.node(el.dataset.nodeId))
      .filter(Boolean)
      .filter((n) => Math.abs(n.position().x - x) < 1);
  }
  const y = node.position().y;
  const candidates = pool
    .filter((n) => n.id() !== nodeId)
    .filter((n) => (dir > 0 ? n.position().y > y : n.position().y < y))
    .sort((a, b) => (dir > 0 ? a.position().y - b.position().y : b.position().y - a.position().y));
  if (candidates.length === 0) return false;
  return focusNode(candidates[0].id());
}

/** Open the focused node in the inspector — the keyboard twin of a click. */
function activateNode(nodeId) {
  const el = overlayFor(nodeId);
  if (!el) return;
  // Drive the card's own click handler so the two paths cannot drift.
  el.click();
}

// ── Key handling ────────────────────────────────────────────────────────────

function onCanvasKeydown(e) {
  if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey) return;
  const nodeId = nodeIdOf(e.target);
  if (!nodeId) return;
  // Let controls inside the card keep their own keys.
  if (e.target.closest('button, a[href], input, select, textarea, [contenteditable="true"]')) return;

  switch (e.key) {
    case 'ArrowRight': case 'l':
      if (moveAlongEdge(nodeId, 'out')) e.preventDefault();
      break;
    case 'ArrowLeft': case 'h':
      if (moveAlongEdge(nodeId, 'in')) e.preventDefault();
      break;
    case 'ArrowDown': case 'j':
      if (moveVertically(nodeId, 1)) e.preventDefault();
      break;
    case 'ArrowUp': case 'k':
      if (moveVertically(nodeId, -1)) e.preventDefault();
      break;
    case 'Enter':
      e.preventDefault();
      activateNode(nodeId);
      break;
    case 'Escape':
      // Step out to the canvas itself: further arrows pan instead of moving
      // between nodes.
      e.preventDefault();
      focusSafely(canvasSurface());
      break;
    default:
      break;
  }
}

/** Arrows at the canvas level (no node focused) pan the view. */
function onSurfaceKeydown(e) {
  if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey) return;
  if (e.target !== canvasSurface()) return;
  const step = e.shiftKey ? CANVAS_STEP_PX * 3 : CANVAS_STEP_PX;
  let dx = 0;
  let dy = 0;
  if (e.key === 'ArrowLeft') dx = step;
  else if (e.key === 'ArrowRight') dx = -step;
  else if (e.key === 'ArrowUp') dy = step;
  else if (e.key === 'ArrowDown') dy = -step;
  else if (e.key === 'Enter') {
    // Enter from the canvas drops into the graph at the current node.
    e.preventDefault();
    refreshCanvasTabStop();
    if (_currentNodeId) focusNode(_currentNodeId);
    return;
  } else return;
  e.preventDefault();
  setViewportPan(viewport.pan.x + dx, viewport.pan.y + dy);
  applyViewportTransform();
}

// ── Install ─────────────────────────────────────────────────────────────────

function installCanvasKeys() {
  const surface = canvasSurface();
  if (!surface) return;

  // Delegated — overlays are rebuilt on every render.
  surface.addEventListener('keydown', onCanvasKeydown);
  surface.addEventListener('keydown', onSurfaceKeydown);
  surface.addEventListener('focusin', (e) => {
    const id = nodeIdOf(e.target);
    if (id) setCurrentNode(id);
  });

  // Keep the tab stop alive across re-renders.
  const layer = document.getElementById('graph-layer') || surface;
  new MutationObserver(() => refreshCanvasTabStop())
    .observe(layer, {childList: true, subtree: false});

  if (typeof registerShortcut === 'function') {
    registerShortcut({
      id: 'canvas-focus', keys: 'g g', group: 'Graph',
      description: 'Move the keyboard into the graph',
      run: () => {
        refreshCanvasTabStop();
        if (_currentNodeId) focusNode(_currentNodeId);
        else focusSafely(canvasSurface());
      },
    });
  }

  refreshCanvasTabStop();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', installCanvasKeys);
} else {
  installCanvasKeys();
}

window.gdRefreshCanvasTabStop = refreshCanvasTabStop;
