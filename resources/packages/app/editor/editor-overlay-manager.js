// Editor Overlay (manager) - base `createOverlay` factory, the
// placeholder-overlay binder, and the `createNodeOverlays` /
// `updateOverlayPositions` lifecycle.
// Depends on: editor-state.js, editor-data.js, editor-drag.js.

// ============================================================================
// OVERLAY REGISTRY
// ============================================================================
//
// Cytoscape fires `pan zoom` events at high rate (every wheel tick,
// every drag delta), and updateOverlayPositions also runs every frame
// inside the RAF loop in editor-cytoscape.js. `document.querySelectorAll(
// '.node-overlay')` on every tick scans the entire DOM and rebuilds a
// fresh NodeList — for a graph with 200 overlays this is ~12k DOM
// hits/sec just for the position update. Cache the elements in Maps
// keyed by their identity (cy node-id / edge-id) so position updates
// iterate a hashmap instead of touching the DOM.
//
// `registerNodeOverlay` / `registerEdgeOverlay` are called by every
// overlay-creation site (createOverlay factory + placeholder +
// edge-label). `unregisterOverlay` is called from removal sites in
// editor-cytoscape.js. `removeAllOverlays` skips the optional
// "preserved" id (the overlay the user is currently hovering, which
// we keep across rebuilds so mouseleave doesn't fire).
const _overlaysByNodeId = new Map();
const _edgeOverlaysByEdgeId = new Map();

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
    fontSize: options.fontSize || '11px',
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
  const inImpl = arg && implementationFnIds && implementationFnIds.has(arg['fn-id']);
  const editable = inImpl
                && (typeof isAuthenticated === 'function' && isAuthenticated());
  if (!editable) return;

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
  btn.className = 'placeholder-binder' + (isSeqAnchor ? ' is-seq-anchor' : '');
  btn.dataset.nodeId = node.id();
  btn.textContent = '+';
  btn.title = isSeqAnchor
              ? 'Add the first item'
              : 'Bind this slot (literal value or fn-ref)';
  btn.setAttribute('aria-label', btn.title);

  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (isSeqAnchor && typeof appendSequenceItem === 'function') {
      appendSequenceItem(seqFnId, btn, appendT);
    } else if (typeof enterFreeArgBindEditMode === 'function') {
      enterFreeArgBindEditMode(arg, btn);
    }
  });

  // Position the binder over the placeholder node's cytoscape footprint.
  // Reuses .node-overlay's pan/zoom positioning so the +.button tracks
  // the node through layout changes.
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

  if (!cy) return;

  const container = document.getElementById('cy');

  // Fn nodes (with ancestor list)
  cy.nodes('[type="fn"][!isPlaceholder]').forEach(node => {
    // Skip if overlay already exists (preserved)
    if (node.id() === preservedOverlayId && getNodeOverlay(node.id())) return;
    createFnOverlay(node, container);
  });

  // Arg value nodes
  cy.nodes('[type="arg"]').forEach(node => {
    createArgOverlay(node, container);
  });

  // Placeholder nodes (unset args)
  cy.nodes('[?isPlaceholder]').forEach(node => {
    createPlaceholderOverlay(node, container);
  });

  // Remove any stale edge label overlays then create fresh ones.
  _removeAllEdgeOverlays();
  cy.edges().forEach(edge => {
    if (edge.data('argName')) createEdgeLabelOverlay(edge, container);
  });

  updateOverlayPositions();
}

/**
 * Update overlay positions based on Cytoscape node positions
 */
function updateOverlayPositions() {
  if (!cy) return;

  const pan = cy.pan();
  const zoom = cy.zoom();

  for (const [nodeId, overlay] of _overlaysByNodeId) {
    const node = cy.getElementById(nodeId);
    if (!node.length) continue;

    const pos = node.position();
    // Use width()/height() (content size, no padding) to match calculateNodeSize
    const width = node.width();
    const height = node.height();

    // Position overlay's top-left at node's screen top-left
    // Using transformOrigin 'top left' so scale doesn't shift the overlay
    // (with 'center center', overlay content taller than node causes drift)
    const screenLeft = (pos.x - width / 2) * zoom + pan.x;
    const screenTop = (pos.y - height / 2) * zoom + pan.y;

    overlay.style.left = screenLeft + 'px';
    overlay.style.top = screenTop + 'px';
    overlay.style.width = width + 'px';
    overlay.style.minHeight = height + 'px';
    overlay.style.transform = 'scale(' + zoom + ')';
    overlay.style.transformOrigin = 'top left';
  }

  // Position edge label overlays. Anchor: visual right edge sits 6px to the
  // left of target's left edge, vertically centered on the target.
  // We measure the element's UNSCALED width/height (offsetWidth/Height ignore
  // transforms) and compute pixel positions so the visual top-left lands at
  // (screenRight - w*zoom, screenMid - h*zoom/2). Origin 'top left' means
  // scaling around the top-left corner — the corner stays at left/top.
  for (const [edgeId, overlay] of _edgeOverlaysByEdgeId) {
    const edge = cy.getElementById(edgeId);
    if (!edge.length) continue;
    const target = edge.target();
    const source = edge.source();
    if (!target.length) return;

    const tPos = target.position();
    const tWidth = target.width();
    const targetLeftPx = (tPos.x - tWidth / 2) * zoom + pan.x;
    const screenMid = tPos.y * zoom + pan.y;

    const w = overlay.offsetWidth;
    const h = overlay.offsetHeight;
    const wPx = w * zoom;

    // Anchor strategy. Taxi-style edges leave the source horizontally,
    // bend at a column boundary, then continue vertically + horizontally
    // toward the target. The visually "shared part" of an edge that
    // branches to multiple slots is the initial horizontal segment up
    // to that bend, plus the vertical descent — so we want the overlay
    // to sit AFTER the bend (in the source-side-of-target horizontal
    // segment), not on top of the shared part. The post-bend gap is
    // chosen so a visible stretch of horizontal edge remains BETWEEN
    // the bend and the label (instead of the label butting against
    // the vertical segment). Same gap is the floor on the fallback
    // path so a wide label never gets pushed back onto the vertical
    // edge segment — we'd rather clip the label's right side against
    // the target than smear it across the bend.
    let leftPx;
    if (source.length) {
      const sPos = source.position();
      const sWidth = source.width();
      const sourceRightPx = (sPos.x + sWidth / 2) * zoom + pan.x;
      const colRight = source.data('colRightX');
      // Matches the `taxi-turn` formula in editor-cytoscape.js:
      // bend = colRight + 20 (graph units) when colRightX is known;
      // otherwise fall back to source.right + 20.
      const bendXpx = (colRight !== undefined)
                      ? (colRight + 20) * zoom + pan.x
                      : sourceRightPx + 20 * zoom;
      // Post-bend gap scales with zoom so the visible stretch stays
      // proportional to the edge thickness at any zoom level.
      const postBendGap = 18 * zoom;
      const afterBend = bendXpx + postBendGap;
      const targetClampRight = targetLeftPx - 6;
      // Preferred: start just past the bend so the shared part of the
      // edge is fully visible. Fall back to "right-anchored to target"
      // when there isn't room after the bend (very short edges) — but
      // never let the label's LEFT slide past `afterBend`, so the
      // vertical edge segment stays clear regardless of label width.
      if (afterBend + wPx <= targetClampRight) {
        leftPx = afterBend;
      } else {
        leftPx = Math.max(afterBend, targetClampRight - wPx);
      }
    } else {
      leftPx = targetLeftPx - 6 - wPx;
    }

    overlay.style.left = leftPx + 'px';
    overlay.style.top = (screenMid - h * zoom / 2) + 'px';
    overlay.style.transform = 'scale(' + zoom + ')';
  }
}
