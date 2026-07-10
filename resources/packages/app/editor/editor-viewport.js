// Editor Viewport — owns pan and zoom, and the gestures that change them.
// Depends on: editor-state.js. Consumed by editor-graph-view.js (`gv`).
//
// Cytoscape paints nothing any more: nodes are invisible footprints, edges are
// SVG. Its viewport was therefore doing one job — holding two numbers and
// firing an event when they changed — while its canvas ate the wheel and drag
// gestures that produced them. Both move here.
//
// Pan and zoom are applied by a single CSS transform on `#graph-layer`
// (`applyViewportTransform`). Nothing else re-projects.

const VIEWPORT_MIN_ZOOM = 0.1;
const VIEWPORT_MAX_ZOOM = 3;
// Wheel delta → zoom factor. One notch (~100px on a mouse) is ~10%, which is
// the same feel cytoscape's default `wheelSensitivity` gave.
const WHEEL_ZOOM_RATE = 0.001;

const viewport = {
  pan: {x: 0, y: 0},
  zoom: 1,
  // Suspended while another gesture owns the pointer — the overlay drag handle
  // moves a node, and without this a touch drag would pan the canvas too,
  // doubling the movement and pulling the node out from under the finger.
  userPanningEnabled: true,
};

const _viewportListeners = [];
let _viewportInputInstalled = false;

/** Register a handler for pan/zoom. Handlers must stay O(1) — these fire hot. */
function onViewportChanged(cb) {
  _viewportListeners.push(cb);
}

function _notifyViewportChanged() {
  for (const cb of _viewportListeners) cb();
}

function clampZoom(z) {
  return Math.min(Math.max(z, VIEWPORT_MIN_ZOOM), VIEWPORT_MAX_ZOOM);
}

/** Container-relative screen point → graph coordinates. */
function viewportScreenToGraph(sx, sy) {
  return {x: (sx - viewport.pan.x) / viewport.zoom,
          y: (sy - viewport.pan.y) / viewport.zoom};
}

function setViewportPan(x, y) {
  viewport.pan.x = x;
  viewport.pan.y = y;
  _notifyViewportChanged();
}

/** Set both at once, without an anchor point. Used by fit-to-content. */
function setViewportTransform(zoom, panX, panY) {
  viewport.zoom = clampZoom(zoom);
  viewport.pan.x = panX;
  viewport.pan.y = panY;
  _notifyViewportChanged();
}

/**
 * Did this gesture start on something the graph layer owns — a card, a drag
 * handle, an edge? Then it is not a background gesture and must not pan.
 */
function _startsOnGraphLayer(e) {
  return !!e.target?.closest?.('#graph-layer');
}

/**
 * Zoom to `level`, holding the graph point currently under `screenPoint`
 * (container-relative) fixed. That is what makes wheel-zoom feel anchored to
 * the cursor rather than to the corner.
 */
function setViewportZoom(level, screenPoint) {
  const next = clampZoom(level);
  const anchor = screenPoint || viewportCentreScreen();
  const g = viewportScreenToGraph(anchor.x, anchor.y);
  viewport.zoom = next;
  viewport.pan.x = anchor.x - g.x * next;
  viewport.pan.y = anchor.y - g.y * next;
  _notifyViewportChanged();
}

function viewportContainer() {
  return document.getElementById('cy');
}

function viewportWidth() {
  return viewportContainer()?.clientWidth || 0;
}

function viewportHeight() {
  return viewportContainer()?.clientHeight || 0;
}

function viewportCentreScreen() {
  return {x: viewportWidth() / 2, y: viewportHeight() / 2};
}

/** Pointer event → container-relative coordinates. */
function _localPoint(clientX, clientY) {
  const r = viewportContainer().getBoundingClientRect();
  return {x: clientX - r.left, y: clientY - r.top};
}

function _touchMidpoint(touches) {
  return _localPoint((touches[0].clientX + touches[1].clientX) / 2,
                     (touches[0].clientY + touches[1].clientY) / 2);
}

function _touchDistance(touches) {
  const dx = touches[0].clientX - touches[1].clientX;
  const dy = touches[0].clientY - touches[1].clientY;
  return Math.hypot(dx, dy);
}


/**
 * Wire the gestures. Listeners sit on the container, so anything that stops
 * propagation (the drag handle, a popover, an overlay button) keeps its event.
 */
function installViewportInput() {
  if (_viewportInputInstalled) return;
  const container = viewportContainer();
  if (!container) return;
  _viewportInputInstalled = true;

  // ── Wheel: zoom about the cursor ─────────────────────────────────────────
  container.addEventListener('wheel', (e) => {
    e.preventDefault();
    const p = _localPoint(e.clientX, e.clientY);
    setViewportZoom(viewport.zoom * Math.exp(-e.deltaY * WHEEL_ZOOM_RATE), p);
  }, {passive: false});

  // ── Mouse: drag the background to pan ────────────────────────────────────
  let panning = false;
  let lastX = 0;
  let lastY = 0;

  container.addEventListener('mousedown', (e) => {
    // Only the background pans. A press that lands on a card, a drag handle or
    // an edge belongs to that element — cytoscape drew those on its canvas, so
    // its own hit-test kept them apart; ours is the DOM tree.
    if (e.button !== 0 || !viewport.userPanningEnabled || _startsOnGraphLayer(e)) return;
    panning = true;
    lastX = e.clientX;
    lastY = e.clientY;
    container.style.cursor = 'grabbing';
  });

  const onMouseMove = (e) => {
    if (!panning) return;
    setViewportPan(viewport.pan.x + (e.clientX - lastX),
                   viewport.pan.y + (e.clientY - lastY));
    lastX = e.clientX;
    lastY = e.clientY;
  };
  const onMouseUp = () => {
    if (!panning) return;
    panning = false;
    container.style.cursor = '';
  };
  document.addEventListener('mousemove', onMouseMove);
  document.addEventListener('mouseup', onMouseUp);

  // ── Touch: one finger pans, two pinch ────────────────────────────────────
  let pinchDistance = 0;
  let pinchZoom = 1;

  container.addEventListener('touchstart', (e) => {
    if (!viewport.userPanningEnabled) return;
    // A pinch is always a viewport gesture, wherever it starts; a one-finger
    // drag on a card belongs to the card.
    if (e.touches.length === 1 && _startsOnGraphLayer(e)) return;
    if (e.touches.length === 1) {
      panning = true;
      lastX = e.touches[0].clientX;
      lastY = e.touches[0].clientY;
    } else if (e.touches.length === 2) {
      panning = false;
      pinchDistance = _touchDistance(e.touches);
      pinchZoom = viewport.zoom;
    }
  }, {passive: true});

  container.addEventListener('touchmove', (e) => {
    if (!viewport.userPanningEnabled) return;
    if (e.touches.length === 1 && panning) {
      if (e.cancelable) e.preventDefault();
      setViewportPan(viewport.pan.x + (e.touches[0].clientX - lastX),
                     viewport.pan.y + (e.touches[0].clientY - lastY));
      lastX = e.touches[0].clientX;
      lastY = e.touches[0].clientY;
    } else if (e.touches.length === 2 && pinchDistance > 0) {
      if (e.cancelable) e.preventDefault();
      const d = _touchDistance(e.touches);
      setViewportZoom(pinchZoom * (d / pinchDistance), _touchMidpoint(e.touches));
    }
  }, {passive: false});

  const endTouch = (e) => {
    if (e.touches.length < 2) pinchDistance = 0;
    if (e.touches.length === 0) panning = false;
  };
  container.addEventListener('touchend', endTouch);
  container.addEventListener('touchcancel', endTouch);
}


/**
 * Ease the pan so `graphPoint` lands at the centre of the visible area. Used by
 * "go to root"; cytoscape's `cy.animate({center})` did this.
 */
function animateViewportTo(graphPoint, durationMs) {
  const target = {
    x: viewportCentreScreen().x - graphPoint.x * viewport.zoom,
    y: viewportCentreScreen().y - graphPoint.y * viewport.zoom,
  };
  const from = {x: viewport.pan.x, y: viewport.pan.y};
  const start = performance.now();
  const step = (now) => {
    const t = Math.min(1, (now - start) / durationMs);
    // ease-out, matching the node animations
    const k = 1 - Math.pow(1 - t, 3);
    setViewportPan(from.x + (target.x - from.x) * k,
                   from.y + (target.y - from.y) * k);
    if (t < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}
