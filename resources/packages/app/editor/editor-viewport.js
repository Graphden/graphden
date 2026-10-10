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
// the same feel cytoscape's default `wheelSensitivity` gave. The rate assumes
// PIXEL deltas; `_wheelDeltaPixels` normalizes line/page modes up front so
// Firefox mouse wheels (which report ~3 LINES per notch) aren't ~30× too slow.
const WHEEL_ZOOM_RATE = 0.001;
const WHEEL_LINE_HEIGHT = 16; // px per line, the usual approximation

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
let _viewportAnimation = null;

/** Register a handler for pan/zoom. Handlers must stay O(1) — these fire hot. */
function onViewportChanged(cb) {
  _viewportListeners.push(cb);
  return () => {
    const index = _viewportListeners.indexOf(cb);
    if (index >= 0) _viewportListeners.splice(index, 1);
  };
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

function _cancelViewportAnimation() {
  if (!_viewportAnimation) return;
  cancelAnimationFrame(_viewportAnimation.frame);
  _viewportAnimation = null;
}

function setViewportPan(x, y) {
  _cancelViewportAnimation();
  viewport.pan.x = x;
  viewport.pan.y = y;
  _notifyViewportChanged();
}

/** Set both at once, without an anchor point. Used by fit-to-content. */
function setViewportTransform(zoom, panX, panY) {
  _cancelViewportAnimation();
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
  _cancelViewportAnimation();
  const next = clampZoom(level);
  const anchor = screenPoint || viewportCentreScreen();
  const g = viewportScreenToGraph(anchor.x, anchor.y);
  viewport.zoom = next;
  viewport.pan.x = anchor.x - g.x * next;
  viewport.pan.y = anchor.y - g.y * next;
  _notifyViewportChanged();
}

function viewportContainer() {
  return document.getElementById('graph-surface');
}

// Surface-local area available to the graph. Desktop panels occupy grid
// columns; the narrow Inspector instead covers the canvas as a bottom sheet.
function visibleGraphRect() {
  const surface = viewportContainer();
  if (!surface) return null;
  const sidebar = document.getElementById('side-menu');
  const redesign = document.getElementById('app')?.classList.contains('gd-redesign');
  const sidebarWidth = !redesign && sidebar && !document.body.classList.contains('sidebar-collapsed')
    ? sidebar.getBoundingClientRect().width : 0;
  let bottom = surface.clientHeight;
  const inspector = document.getElementById('gd-inspector');
  if (inspector && document.body.classList.contains('gd-insp-open')
      && getComputedStyle(inspector).position === 'fixed') {
    // Its translateY entrance must not change the fitting rectangle: use the
    // final bottom-anchored height, independent of the current animation frame.
    const sheetTop = window.innerHeight - inspector.offsetHeight;
    bottom = Math.max(0, Math.min(bottom, sheetTop - surface.getBoundingClientRect().top));
  }
  return {left: sidebarWidth, top: 0, right: surface.clientWidth, bottom};
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

/**
 * Wheel `deltaY` in PIXELS, whatever the event's `deltaMode`. Chrome/Safari
 * and trackpad pinch report pixels (mode 0); Firefox mouse wheels report
 * lines (mode 1, ~3/notch); page mode (2) is rare. Without this, WHEEL_ZOOM_RATE
 * (tuned for pixels) makes a Firefox mouse-wheel notch move zoom ~0.3% instead
 * of ~10% — wheel-zoom looks dead.
 */
function _wheelDeltaPixels(e, container) {
  if (e.deltaMode === 1) return e.deltaY * WHEEL_LINE_HEIGHT;
  if (e.deltaMode === 2) return e.deltaY * (container ? container.clientHeight : 800);
  return e.deltaY;
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
    setViewportZoom(viewport.zoom * Math.exp(-_wheelDeltaPixels(e, container) * WHEEL_ZOOM_RATE), p);
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
    _cancelViewportAnimation();
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
      _cancelViewportAnimation();
      panning = true;
      lastX = e.touches[0].clientX;
      lastY = e.touches[0].clientY;
    } else if (e.touches.length === 2) {
      _cancelViewportAnimation();
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
  _cancelViewportAnimation();
  const centre = viewportCentreScreen();
  const target = {
    x: centre.x - graphPoint.x * viewport.zoom,
    y: centre.y - graphPoint.y * viewport.zoom,
  };
  const reducedMotion = typeof matchMedia === 'function'
    && matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reducedMotion || !Number.isFinite(durationMs) || durationMs <= 0) {
    setViewportPan(target.x, target.y);
    return;
  }
  const from = {x: viewport.pan.x, y: viewport.pan.y};
  const start = performance.now();
  const motion = {frame: null};
  _viewportAnimation = motion;
  const step = (now) => {
    if (_viewportAnimation !== motion) return;
    const t = Math.min(1, Math.max(0, (now - start) / durationMs));
    // Internal frame writes keep this motion alive; public setters cancel it.
    const k = 1 - (1 - t) ** 3;
    viewport.pan.x = from.x + (target.x - from.x) * k;
    viewport.pan.y = from.y + (target.y - from.y) * k;
    _notifyViewportChanged();
    // A listener may have started another motion or handled a user gesture.
    if (_viewportAnimation !== motion) return;
    if (t < 1) motion.frame = requestAnimationFrame(step);
    else _viewportAnimation = null;
  };
  motion.frame = requestAnimationFrame(step);
}
