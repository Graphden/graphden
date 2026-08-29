// Editor Overlay (manager) - base `createOverlay` factory, the
// placeholder-overlay binder, and the `createNodeOverlays` /
// `updateOverlayPositions` lifecycle.
// Depends on: editor-state.js, editor-data.js, editor-drag.js,
// editor-graph-view.js (gv), editor-layout.js (taxiBendX, computeRowCenters).

// ============================================================================
// OVERLAY REGISTRY
// ============================================================================
//
// `syncOverlayGeometry` runs every frame of a node animation and on every
// pointer move of a drag. `document.querySelectorAll('.node-overlay')` on each
// of those scans the whole DOM and rebuilds a NodeList — for 200 overlays that
// is thousands of DOM hits a second, just to move things. Cache the elements in
// Maps keyed by node / edge id so the geometry pass walks a hashmap instead.
//
// `registerNodeOverlay` / `registerEdgeOverlay` are called by every
// overlay-creation site (createOverlay factory + placeholder +
// edge-label). `unregisterOverlay` is called from removal sites in
// editor-render.js. `removeAllOverlays` skips the optional
// "preserved" id (the overlay the user is currently hovering, which
// we keep across rebuilds so mouseleave doesn't fire).
const _overlaysByNodeId = new Map();
const _edgeOverlaysByEdgeId = new Map();

// ============================================================================
// THE GRAPH LAYER
// ============================================================================
//
// Every overlay lives inside ONE absolutely-positioned div that carries the
// viewport transform. Overlays are then laid out in GRAPH coordinates and
// never move again: panning and zooming rewrite a single `transform` on the
// layer instead of touching `left`/`top`/`width`/`transform` on all ~200
// overlays. `translate(pan) scale(zoom)` maps a point v to `pan + zoom*v`,
// which is exactly the projection the per-overlay code used to do by hand.
//
// The layer is zero-sized on purpose: its children are absolutely positioned
// and overflow it, so it never intercepts pointer events of its own.
const GRAPH_LAYER_ID = 'graph-layer';

function getGraphLayer() {
  let layer = document.getElementById(GRAPH_LAYER_ID);
  if (!layer) {
    const container = document.getElementById('graph-surface');
    if (!container) return null;
    layer = document.createElement('div');
    layer.id = GRAPH_LAYER_ID;
    container.appendChild(layer);
  }
  return layer;
}

function registerNodeOverlay(el) {
  const id = el.dataset.nodeId;
  if (id) _overlaysByNodeId.set(id, el);
}

function registerEdgeOverlay(el) {
  const id = el.dataset.edgeId;
  if (id) _edgeOverlaysByEdgeId.set(id, el);
}

function unregisterNodeOverlay(nodeId) {
  const el = _overlaysByNodeId.get(nodeId);
  if (el) {
    el.remove();
    _overlaysByNodeId.delete(nodeId);
  }
}

function getNodeOverlay(nodeId) {
  return _overlaysByNodeId.get(nodeId);
}

function _removeAllNodeOverlays(preservedNodeId) {
  for (const [nodeId, el] of _overlaysByNodeId) {
    if (nodeId === preservedNodeId) continue;
    el.remove();
    _overlaysByNodeId.delete(nodeId);
  }
}

function _removeAllEdgeOverlays() {
  for (const [, el] of _edgeOverlaysByEdgeId) el.remove();
  _edgeOverlaysByEdgeId.clear();
}

/**
 * Create overlay element with common styles
 */
function createOverlay(nodeId, options = {}) {
  const overlay = document.createElement('div');
  overlay.className = 'node-overlay';
  overlay.dataset.nodeId = nodeId;
  // Focusable, but not in the tab order: editor-canvas-keys.js runs a roving
  // tabindex over the canvas, so Tab enters the graph once and the arrow keys
  // move between nodes from there. A card is a group of controls, not a
  // control, hence role=group rather than button.
  overlay.setAttribute('tabindex', '-1');
  overlay.setAttribute('role', 'group');
  _overlaysByNodeId.set(nodeId, overlay);
  Object.assign(overlay.style, {
    position: 'absolute',
    pointerEvents: 'auto',
    zIndex: '10',
    background: options.background || 'var(--card-bg)',
    border: options.border || '2px solid var(--card-border)',
    borderRadius: options.borderRadius || '8px',
    overflow: 'hidden',
    fontFamily: 'SF Mono, Monaco, monospace',
    fontSize: options.fontSize || '0.6875rem',
    touchAction: 'none',         // Prevent browser gestures on overlay
    userSelect: 'none',
    WebkitUserSelect: 'none'
  });
  return overlay;
}

// createArgOverlay / resolveArgType / createTypeChip live in
// editor-overlay-arg.js, and createEdgeLabelOverlay in
// editor-overlay-edge-label.js — both are self-contained
// rendering concerns that don't share state with createFnOverlay
// or createPlaceholderOverlay.

/**
 * Create overlay for a placeholder node (unset arg, or empty sequence
 * anchor). The slot's expected type already reads off the incoming
 * edge's type-chip, so the placeholder itself collapses to a small
 * `+` click target — its job is only to host the binder action.
 *
 * Non-editable viewers see no overlay at all (the empty edge endpoint
 * is sufficient signal that the slot is unbound).
 */
function createPlaceholderOverlay(node, container) {
  const arg = (typeof argRowFromNode === 'function')
              ? argRowFromNode(node.data())
              : null;
  // λ-param of an enclosing HOF: the impl supplies it per call — the
  // reader must SEE it (that is how HOFs work) but must not bind it.
  // Informational badge for everyone, no `+`, regardless of auth.
  if (node.data('lambdaArg')) {
    const wrap = document.createElement('div');
    wrap.className = 'node-overlay placeholder-overlay';
    wrap.dataset.nodeId = node.id();
    Object.assign(wrap.style, {
      position: 'absolute', pointerEvents: 'none', zIndex: '10',
      background: 'transparent', border: 'none', overflow: 'visible'
    });
    const badge = document.createElement('span');
    badge.className = 'lambda-arg-badge';
    badge.textContent = 'λ';
    badge.title = 'Supplied per call by the enclosing higher-order fn — not bindable here';
    badge.style.pointerEvents = 'auto';
    wrap.appendChild(badge);
    registerNodeOverlay(wrap);
    container.appendChild(wrap);
    return;
  }
  const inImpl = arg && implementationFnIds?.has(arg['fn-id']);
  const editable = inImpl
                && (typeof isAuthenticated === 'function' && isAuthenticated());
  if (!editable) return;
  // A package-synced fn's bindings are re-synced on every boot and the API
  // refuses the write with a 403 — so don't offer a "+" that cannot land.
  // Extend it into a child and the placeholder appears there instead.
  if (typeof isPackageOwnedFn === 'function'
      && isPackageOwnedFn(arg['fn-id'])) return;

  const isSeqAnchor = !!node.data('isSequenceAnchor');
  const seqFnId = node.data('sequenceFnId') || arg['fn-id'];
  // Nav-typed sequence (`:update-in` `:path`) whose structure can't be
  // navigated further → no first item is valid, so skip the `+`.
  const appendT = (isSeqAnchor && typeof appendNavType === 'function')
                  ? appendNavType(seqFnId, arg['slot-id'])
                  : undefined;
  if (appendT === null) return;

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'placeholder-binder' + (isSeqAnchor ? ' is-seq-anchor' : '')
    + (node.data('optionalArg') ? ' is-optional' : '')
    + (node.data('deepArg') ? ' is-deep' : '');
  btn.dataset.nodeId = node.id();
  btn.textContent = '+';
  btn.title = (isSeqAnchor
              ? 'Add the first item'
              : 'Bind this slot (literal value or fn-ref)')
    + (node.data('optionalArg') ? ' — optional input, the fn runs without it' : '')
    + (node.data('deepArg') ? ' — propagated from inside the composition; the binding lands on this fn' : '');
  btn.setAttribute('aria-label', btn.title);

  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (isSeqAnchor && typeof appendSequenceItem === 'function') {
      appendSequenceItem(seqFnId, btn, appendT,
                         { elemType: (typeof seqElemType === 'function' ? seqElemType(arg) : null) });
    } else if (typeof enterFreeArgBindEditMode === 'function') {
      enterFreeArgBindEditMode(arg, btn);
    }
  });

  // Position the binder over the placeholder node's footprint. Reuses
  // .node-overlay's graph-coordinate positioning so the `+` button tracks the
  // node through layout changes.
  const wrap = document.createElement('div');
  wrap.className = 'node-overlay placeholder-overlay';
  wrap.dataset.nodeId = node.id();
  Object.assign(wrap.style, {
    position: 'absolute',
    pointerEvents: 'none',  // children opt back in
    zIndex: '10',
    background: 'transparent',
    border: 'none',
    overflow: 'visible'
  });
  btn.style.pointerEvents = 'auto';
  wrap.appendChild(btn);
  registerNodeOverlay(wrap);
  container.appendChild(wrap);
}

// ============================================================================
// OVERLAY MANAGEMENT
// ============================================================================

/**
 * Create all node overlays
 */
function createNodeOverlays() {
  // The execution-path view (editor-path-view.js) anchors badges +
  // classes on the CURRENT overlay set — a rebuild invalidates that
  // mapping, so the transient view clears rather than go stale.
  if (typeof clearExecutionPathView === 'function') clearExecutionPathView();

  // Find the node that has active preview - we should NOT remove its overlay
  // to prevent mouseleave events during rebuild
  let preservedOverlayId = null;
  if (previewState.size > 0) {
    // previewState keys are full node IDs (e.g., "fn-uuid")
    preservedOverlayId = previewState.keys().next().value;
  }

  // Remove overlays except the preserved one (registry-backed; replaces
  // a full-DOM querySelectorAll scan).
  _removeAllNodeOverlays(preservedOverlayId);

  if (!gv.ready()) return;

  const container = getGraphLayer();
  if (!container) return;

  // Edges first: they live in the same layer and must paint under the cards.
  renderEdges();

  // Fn nodes (with ancestor list)
  gv.fnNodes().forEach(node => {
    // Skip if overlay already exists (preserved)
    if (node.id() === preservedOverlayId && getNodeOverlay(node.id())) return;
    createFnOverlay(node, container);
  });

  // Arg value nodes
  gv.argNodes().forEach(node => {
    createArgOverlay(node, container);
  });

  // Placeholder nodes (unset args)
  gv.placeholderNodes().forEach(node => {
    createPlaceholderOverlay(node, container);
  });

  // Remove any stale edge label overlays then create fresh ones.
  _removeAllEdgeOverlays();
  gv.edges().forEach(edge => {
    if (edge.data('argName')) createEdgeLabelOverlay(edge, container);
  });

  updateOverlayPositions();
}

// Gap (graph units) between the taxi bend and the label's left edge, so a
// visible stretch of horizontal edge remains between them instead of the
// label butting against the vertical segment.
const EDGE_LABEL_POST_BEND_GAP = 18;
// Gap (graph units) between the label's right edge and the target's left edge.
const EDGE_LABEL_TARGET_GAP = 6;

/**
 * Move the whole overlay layer to match the viewport. O(1) — this is
 * the hot path (every wheel tick, every pan delta), so it must not touch
 * individual overlays.
 */
function applyViewportTransform() {
  if (!gv.ready()) return;
  const layer = getGraphLayer();
  if (!layer) return;
  const pan = gv.pan();
  const zoom = gv.zoom();
  layer.style.transform =
    'translate(' + pan.x + 'px,' + pan.y + 'px) scale(' + zoom + ')';
  // Two attribute writes, so the edges stay legible and grabbable at any zoom
  // without re-emitting a single path.
  applyEdgeStrokeWidths();
}

/**
 * Lay overlays out in GRAPH coordinates. O(n), but only needed when node
 * positions or overlay sizes actually change — a new layout, an animation
 * frame, a drag. Pan and zoom do NOT call this.
 */
function syncOverlayGeometry() {
  if (!gv.ready()) return;

  // Edge paths are graph-coordinate geometry too, so they move with the nodes.
  syncEdgeGeometry();

  for (const [nodeId, overlay] of _overlaysByNodeId) {
    const node = gv.node(nodeId);
    if (!node) continue;

    const pos = node.position();
    // Use width()/height() (content size, no padding) to match calculateNodeSize
    const width = node.width();
    const height = node.height();

    overlay.style.left = (pos.x - width / 2) + 'px';
    overlay.style.top = (pos.y - height / 2) + 'px';
    overlay.style.width = width + 'px';
    overlay.style.minHeight = height + 'px';
  }

  // Position edge label overlays. Anchor: right edge sits `TARGET_GAP` to the
  // left of the target's left edge, vertically centred on the target.
  //
  // `offsetWidth`/`offsetHeight` ignore transforms, so inside the scaled layer
  // they already report graph units — the whole projection collapses away.
  // Two passes so we never interleave a layout READ (offsetWidth/Height) with
  // a layout WRITE (style.left/top). Interleaved, each write dirties layout and
  // the next iteration's read forces a synchronous reflow — O(edges) reflows
  // per frame on drag/animate. Pass 1 reads every overlay's measured size and
  // resolves its geometry; pass 2 only writes.
  const edgeLabelWrites = [];
  for (const [edgeId, overlay] of _edgeOverlaysByEdgeId) {
    const edge = gv.edge(edgeId);
    if (!edge) continue;
    const target = edge.target();
    const source = edge.source();
    // Skip THIS edge only (a target can be missing mid edge-removal /
    // sync race); `return` here aborted the whole loop, freezing every
    // later edge-label.
    if (!target) continue;

    const tPos = target.position();
    const targetLeft = tPos.x - target.width() / 2;

    const w = overlay.offsetWidth;
    const h = overlay.offsetHeight;

    // Anchor strategy. Taxi-style edges leave the source horizontally,
    // bend at a column boundary, then continue vertically + horizontally
    // toward the target. The visually "shared part" of an edge that
    // branches to multiple slots is the initial horizontal segment up
    // to that bend, plus the vertical descent — so we want the overlay
    // to sit AFTER the bend, not on top of the shared part.
    let left;
    if (source) {
      // `taxiBendX` (editor-layout.js) is the SAME function the cytoscape
      // `taxi-turn` style and the edge-hover hit-test call, so the label can
      // never anchor on the wrong side of the line that actually gets drawn.
      const afterBend = taxiBendX(source) + EDGE_LABEL_POST_BEND_GAP;
      const targetClampRight = targetLeft - EDGE_LABEL_TARGET_GAP;
      // Preferred: start just past the bend so the shared part of the
      // edge is fully visible. Fall back to "right-anchored to target"
      // when there isn't room after the bend (very short edges) — but
      // never let the label's LEFT slide past `afterBend`, so the
      // vertical edge segment stays clear regardless of label width.
      // We'd rather clip the label's right side against the target than
      // smear it across the bend.
      left = (afterBend + w <= targetClampRight)
             ? afterBend
             : Math.max(afterBend, targetClampRight - w);
    } else {
      left = targetLeft - EDGE_LABEL_TARGET_GAP - w;
    }

    edgeLabelWrites.push({overlay, left, top: tPos.y - h / 2});
  }

  for (const {overlay, left, top} of edgeLabelWrites) {
    overlay.style.left = left + 'px';
    overlay.style.top = top + 'px';
  }
}

/**
 * Both halves. Call this after anything that MOVED a node or resized an
 * overlay. Pan/zoom handlers should call `applyViewportTransform` alone.
 */
function updateOverlayPositions() {
  syncOverlayGeometry();
  applyViewportTransform();
}


/**
 * Correct the layout against the cards' REAL heights.
 *
 * `calculateNodeSize` predicts a card's height by mirroring what the overlay
 * renderer will draw. That mirror cannot be right: the effects strip wraps to
 * as many chip rows as the card's width allows, which depends on font metrics,
 * and every strip the renderer gains has to be remembered here too — it had
 * already forgotten `branch-local`. On `web-server` the estimate was 90 graph
 * units against a real 167.
 *
 * Under-estimating pushes a card 77 units into the row below (the grid leaves
 * 40), and anchors its edges 38 units above its visual centre.
 *
 * So the estimate is demoted to a first guess that keeps the first paint from
 * flashing, and the DOM — which knows — supplies the answer. `offsetHeight`
 * ignores ancestor transforms, so inside the scaled layer it already reports
 * graph units.
 *
 * Mutates `layout` (node id → {x, y, …}) in place and returns whether anything
 * moved, so the caller can animate towards the corrected targets.
 */
function reflowFromMeasuredHeights(layout) {
  const gridPos = lastGridPos();
  if (!gv.ready() || !gridPos || !layout) return false;

  const measured = new Map();
  let changed = false;
  for (const [nodeId, overlay] of _overlaysByNodeId) {
    const node = gv.node(nodeId);
    // Placeholders are a fixed-size `+` target, not a card: their overlay is a
    // transparent wrapper whose height says nothing about the slot.
    if (!node || node.data('isPlaceholder')) continue;
    const h = overlay.offsetHeight;
    if (!h) continue;
    measured.set(nodeId, h);
    if (Math.abs(h - node.height()) > 0.5) changed = true;
  }
  if (!changed) return false;

  for (const [nodeId, h] of measured) {
    gv.node(nodeId)?.setHeight(h);
    const entry = layout.get(nodeId);
    if (entry) entry.height = h;
  }

  // Rows restack around the tallest card in each. Columns are untouched: an
  // overlay's width is set from the node, so it never overflows.
  const rowCenterY = computeRowCenters(
    gridPos, (id) => measured.get(id) ?? layout.get(id)?.height);
  for (const [nodeId, pos] of Object.entries(gridPos)) {
    const y = rowCenterY.get(pos.row);
    const entry = layout.get(nodeId);
    if (y !== undefined && entry) entry.y = y;
  }
  return true;
}
