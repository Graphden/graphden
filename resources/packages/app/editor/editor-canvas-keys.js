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
const NUDGE_STEP = 20;          // graph units per Shift+arrow

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



/**
 * Move a node by (dx, dy) graph units — the keyboard twin of a drag.
 *
 * Marks it user-moved exactly as editor-drag.js does, so a later relayout
 * leaves it where the user put it.
 */
function nudgeNode(nodeId, dx, dy) {
  const node = gv.node(nodeId);
  if (!node) return;
  userMovedNodes.add(nodeId);
  const pos = node.position();
  node.position({x: pos.x + dx, y: pos.y + dy});
  updateOverlayPositions();
  ensureNodeVisible(nodeId);
  if (typeof window.gdAnnounce === 'function') {
    const dir = dx < 0 ? 'left' : dx > 0 ? 'right' : dy < 0 ? 'up' : 'down';
    window.gdAnnounce('Moved ' + dir);
  }
}

// ── Rows inside a card ──────────────────────────────────────────────────────
//
// A card is not a single thing: it is the fn's own row plus one per ancestor
// or argument, each with its own actions. Arrow keys at the CARD level move
// between cards, so stepping INTO a card is a separate move — Enter — after
// which ↑↓ walk its rows and Escape comes back out. Same two-level shape the
// Explorer tree uses, for the same reason: a flat list of every row on the
// canvas would be unusable.

const ROW_SELECTOR = '.ancestor-line, .arg-overlay-row';

function rowsOf(card) {
  return Array.from(card.querySelectorAll(ROW_SELECTOR))
    .filter((el) => el.offsetParent !== null);
}

/** Enter the card: focus its first row, or fall back to the card itself. */
function enterCard(card) {
  const rows = rowsOf(card);
  if (rows.length === 0) return false;
  focusRow(rows[0], card);
  return true;
}

function focusRow(row, card) {
  for (const r of rowsOf(card)) r.setAttribute('tabindex', '-1');
  row.setAttribute('tabindex', '0');
  focusSafely(row);
  if (typeof window.gdAnnounce === 'function') {
    window.gdAnnounce((row.textContent || '').trim().slice(0, 80));
  }
}

function onRowKeydown(e) {
  const row = e.target.closest?.(ROW_SELECTOR);
  if (!row) return;
  const card = row.closest('.node-overlay');
  if (!card) return;
  // Only when the ROW ITSELF has focus — the same rule the card level
  // follows, and for the same reason: a row hosts controls and popovers
  // that own Escape and Enter, and consuming the key from anywhere in its
  // subtree steals it from them. (The canvas regression test fires Escape
  // at an element inside a card and requires it to arrive unconsumed; this
  // handler failed it the moment it was added.)
  if (e.target !== row) return;

  const rows = rowsOf(card);
  const idx = rows.indexOf(row);
  if (idx < 0) return;

  switch (e.key) {
    case 'ArrowDown': case 'j':
      e.preventDefault();
      focusRow(rows[Math.min(idx + 1, rows.length - 1)], card);
      break;
    case 'ArrowUp': case 'k':
      e.preventDefault();
      focusRow(rows[Math.max(idx - 1, 0)], card);
      break;
    case 'Escape':
      // Back out to the card; a second Escape leaves for the canvas.
      e.preventDefault();
      focusSafely(card);
      break;
    case 'Enter':
      e.preventDefault();
      row.click();
      break;
    case '.': case 'm': {
      // The row's own actions — the ⋯ trigger, which is what the mouse
      // reveals on hover.
      e.preventDefault();
      const trigger = row.querySelector('.more-actions-trigger')
        || card.querySelector('.more-actions-trigger');
      if (trigger) trigger.click();
      break;
    }
    default:
      break;
  }
}

// ── Key handling ────────────────────────────────────────────────────────────

function onCanvasKeydown(e) {
  if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey) return;
  const nodeId = nodeIdOf(e.target);
  if (!nodeId) return;
  // Only when the CARD ITSELF has focus, not something inside it. A card
  // hosts the ⋯ trigger, its description tooltip and the row-action popover,
  // and those own Escape and Enter for their own purposes — claiming the key
  // from anywhere in the subtree stole Escape from whatever was open inside.
  // (The landing gate caught this: the tour-history e2e closes a pinned
  // description tooltip with Escape and then could not reopen it.)
  if (!e.target.classList?.contains('node-overlay')) return;

  // Shift+arrows MOVE the node rather than moving between nodes — the
  // keyboard equivalent of dragging it, in grid-sized steps.
  if (e.shiftKey && e.key.startsWith('Arrow')) {
    const step = NUDGE_STEP;
    const d = {ArrowLeft: [-step, 0], ArrowRight: [step, 0],
               ArrowUp: [0, -step], ArrowDown: [0, step]}[e.key];
    if (d) {
      e.preventDefault();
      nudgeNode(nodeId, d[0], d[1]);
      return;
    }
  }

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
      // Select it in the inspector AND step into its rows: the two are one
      // gesture with a mouse (you click the card you want to read), so
      // making them one keystroke keeps the models matched.
      activateNode(nodeId);
      enterCard(e.target);
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
  surface.addEventListener('keydown', onRowKeydown);
  surface.addEventListener('keydown', onSurfaceKeydown);
  surface.addEventListener('focusin', (e) => {
    const id = nodeIdOf(e.target);
    if (id) setCurrentNode(id);
  });

  // Keep the tab stop alive across re-renders.
  //
  // Observe the SURFACE with subtree, not `#graph-layer` directly: the layer
  // is created on the first render, i.e. after this module installs, so a
  // reference taken here would be null and the overlays (its children) would
  // never be seen. Coalesced to one pass per frame — a render mutates the
  // layer many times and re-stamping tabindex on each is pointless work.
  let pending = false;
  new MutationObserver(() => {
    if (pending) return;
    pending = true;
    requestAnimationFrame(() => {
      pending = false;
      refreshCanvasTabStop();
    });
  }).observe(surface, {childList: true, subtree: true});

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
