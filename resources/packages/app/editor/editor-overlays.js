// Editor Overlays - HTML overlays for nodes (ancestor list, drag handles)
// Depends on: editor-state.js, editor-data.js

// ============================================================================
// DRAG HANDLE
// ============================================================================

/**
 * Create drag handle for any overlay
 */
function createDragHandle(overlay, cyNode) {
  const dragHandle = document.createElement('div');
  dragHandle.className = 'drag-handle';
  dragHandle.style.height = '12px';
  dragHandle.style.background = 'linear-gradient(to bottom, #f0f0f0, #ddd)';
  dragHandle.style.borderTop = '1px solid #ccc';
  dragHandle.style.cursor = 'grab';
  dragHandle.style.display = 'flex';
  dragHandle.style.alignItems = 'center';
  dragHandle.style.justifyContent = 'center';
  dragHandle.innerHTML = '<span style="color:#999;font-size:8px;">⋮⋮⋮</span>';

  // Shared drag logic for mouse and touch
  const startDrag = (startX, startY, moveEvent, endEvent, getXY) => {
    if (!cyNode.length) return;

    isGrabbing = true;
    dragHandle.style.cursor = 'grabbing';
    userMovedNodes.add(cyNode.id());

    let lastX = startX;
    let lastY = startY;

    const onMove = (moveE) => {
      const [mx, my] = getXY(moveE);
      const dx = (mx - lastX) / cy.zoom();
      const dy = (my - lastY) / cy.zoom();
      lastX = mx;
      lastY = my;

      const pos = cyNode.position();
      cyNode.position({ x: pos.x + dx, y: pos.y + dy });
      updateOverlayPositions();
    };

    const onEnd = () => {
      document.removeEventListener(moveEvent, onMove);
      document.removeEventListener(endEvent, onEnd);
      isGrabbing = false;
      dragHandle.style.cursor = 'grab';
    };

    document.addEventListener(moveEvent, onMove);
    document.addEventListener(endEvent, onEnd);
  };

  dragHandle.addEventListener('mousedown', (e) => {
    e.stopPropagation();
    e.preventDefault();
    startDrag(e.clientX, e.clientY, 'mousemove', 'mouseup', (e) => [e.clientX, e.clientY]);
  });

  dragHandle.addEventListener('touchstart', (e) => {
    e.stopPropagation();
    e.preventDefault();
    const touch = e.touches[0];
    startDrag(touch.clientX, touch.clientY, 'touchmove', 'touchend',
              (e) => [e.touches[0].clientX, e.touches[0].clientY]);
  }, { passive: false });

  overlay.appendChild(dragHandle);
}

// ============================================================================
// OVERLAY CREATION
// ============================================================================

/**
 * Create overlay element with common styles
 */
function createOverlay(nodeId, options = {}) {
  const overlay = document.createElement('div');
  overlay.className = 'node-overlay';
  overlay.dataset.nodeId = nodeId;
  Object.assign(overlay.style, {
    position: 'absolute',
    pointerEvents: 'auto',
    zIndex: '10',
    background: 'white',
    border: options.border || '2px solid black',
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

/**
 * Create overlay for fn node with ancestor list.
 * Each line is one BFS level — multiple parents at the same level are
 * joined on a single line (multiple inheritance).
 *
 * Levels are GROUPED: levels whose fns set no args are grouped with the
 * previous "real" level. Within a group there is no separator line and
 * hover/click on any line in the group acts on the whole group.
 *
 * Hover/click model: pointing at depth L means "expand exactly to L"
 * (everything at depths ≤ L expanded, deeper collapsed). Hover shows the
 * preview, click commits it.
 *
 * Click handlers fire on `mousedown` so the action is committed BEFORE any
 * pending hover-render can shift the layout under the cursor.
 */
function createFnOverlay(node, container) {
  const originalFnId = node.data('originalFnId');
  if (!originalFnId) return;

  const nodeId = node.id();  // Full node ID including expansion context
  const levels = getInheritanceLevels(originalFnId);
  const ancestorLevels = buildAncestorLevels(levels);
  const spec = expansionState.get(nodeId) || { fullDepth: 0, partialFns: new Set() };
  const fullDepth = spec.fullDepth;
  const partialFns = spec.partialFns;
  const visibleLevels = ancestorLevels.slice(0, MAX_VISIBLE_ANCESTORS + 1);

  const overlay = createOverlay(nodeId);
  overlay.dataset.originalFnId = originalFnId;
  overlay.dataset.nodeId = nodeId;
  overlay.style.cursor = 'default';

  // Preview/committed states share the SAME visual style so the click is
  // visually invisible — hovering shows a preview, clicking commits it but
  // the visual stays the same; only when the user leaves and re-enters
  // does the new state become visible.
  const HIGHLIGHT_BG = '#f0f0f0';
  // Each visible BFS level becomes one line; for MI levels each parent is
  // a span inside that line (separated by a vertical bar), individually
  // clickable. We need to repaint both the line backgrounds and the
  // individual span highlights when previewing/restoring.
  const linesByDepth = new Map();   // depth -> { line, spansByFnId }

  // Returns true if a particular fn at a given depth would be highlighted
  // under the given preview/committed spec.
  const fnIsHighlighted = (depth, fnId, sFull, sPartial) => {
    if (depth <= sFull) return true;
    if (depth === sFull + 1 && sPartial.has(fnId)) return true;
    return false;
  };

  const paintWithSpec = (sFull, sPartial) => {
    linesByDepth.forEach(({ line, spansByFnId }, depth) => {
      const lvl = ancestorLevels[depth];
      if (!lvl) return;
      if (spansByFnId) {
        // MI line: per-fn highlight (no whole-line background — only individual spans)
        line.style.fontWeight = 'normal';
        line.style.background = '';
        spansByFnId.forEach((span, fnId) => {
          if (fnIsHighlighted(depth, fnId, sFull, sPartial)) {
            span.style.fontWeight = 'bold';
            span.style.background = HIGHLIGHT_BG;
          } else {
            span.style.fontWeight = 'normal';
            span.style.background = '';
          }
        });
      } else {
        // Single-fn line: highlight the whole line
        const fn = lvl.fns[0];
        if (fn && fnIsHighlighted(depth, fn.fnId, sFull, sPartial)) {
          line.style.fontWeight = 'bold';
          line.style.background = HIGHLIGHT_BG;
        } else {
          line.style.fontWeight = 'normal';
          line.style.background = '';
        }
      }
    });
  };
  const applyPreviewStyle = (previewSpec) => {
    const sFull = previewSpec ? previewSpec.fullDepth : fullDepth;
    const sPartial = previewSpec ? previewSpec.partialFns : partialFns;
    paintWithSpec(sFull, sPartial);
  };
  const restoreStyles = () => paintWithSpec(fullDepth, partialFns);

  visibleLevels.forEach((levelInfo, idx) => {
    const line = document.createElement('div');
    line.className = 'ancestor-line';
    line.dataset.level = levelInfo.depth;
    line.dataset.groupId = levelInfo.groupId;

    // No separator if next level is in the same group
    const nextLevel = visibleLevels[idx + 1];
    const isLastInGroup = !nextLevel || nextLevel.groupId !== levelInfo.groupId;
    const isLast = idx === visibleLevels.length - 1;
    const lineBorderBottom = (isLast || !isLastInGroup) ? 'none' : '1px solid #eee';
    Object.assign(line.style, {
      borderBottom: lineBorderBottom,
      touchAction: 'none',
      userSelect: 'none',
      WebkitUserSelect: 'none'
    });

    let spansByFnId = null;
    if (levelInfo.isMI) {
      // Multi-fn level — each parent becomes a flex "cell" with its own
      // border-right (= vertical separator running from the top horizontal
      // line to the bottom one). Hovering fills the entire cell area, not
      // just the text. The line itself has no padding — padding lives on
      // the cells so the cell area covers the full row height.
      // Cells use flex:1 so they share the line width equally and there
      // is no white gap on the right when MI line is narrower than the
      // widest line of the overlay.
      line.style.display = 'flex';
      line.style.padding = '0';

      spansByFnId = new Map();
      const allFnsAtDepth = levelInfo.fns.map(f => f.fnId);
      levelInfo.fns.forEach((f, i) => {
        const span = document.createElement('span');
        span.textContent = f.name;
        span.style.cursor = 'pointer';
        span.style.padding = '4px 8px';
        span.style.flex = '1 1 0';
        span.style.minWidth = '0';
        if (i < levelInfo.fns.length - 1) {
          span.style.borderRight = '1px solid #eee';
        }
        // Initial highlight (committed state) — per-fn
        if (fnIsHighlighted(levelInfo.depth, f.fnId, fullDepth, partialFns)) {
          span.style.fontWeight = 'bold';
          span.style.background = HIGHLIGHT_BG;
        }
        spansByFnId.set(f.fnId, span);

        // MI per-fn click: cascade to depth-1 + partial = {this fn}
        const onMouseDown = (e) => {
          e.stopPropagation();
          e.preventDefault();
          applyClickSpec(nodeId, levelInfo.depth, f.fnId, allFnsAtDepth);
        };
        span.addEventListener('mousedown', onMouseDown);
        span.addEventListener('touchend', onMouseDown);
        span.addEventListener('mouseenter', (e) => {
          if (isGrabbing) return;
          if (!pointerMovedSinceLastPreview(e.clientX, e.clientY)) return;
          recordPreviewPointer(e.clientX, e.clientY);
          const preview = computeSpecAfterClick(
            { fullDepth, partialFns }, levelInfo.depth, f.fnId, allFnsAtDepth);
          applyPreviewStyle(preview);
          applyHoverSpec(nodeId, levelInfo.depth, f.fnId, allFnsAtDepth);
        });
        span.addEventListener('mouseleave', () => restoreStyles());
        line.appendChild(span);
      });
    } else {
      // Non-MI line: padding on the line itself
      line.style.padding = '4px 8px';
      // Single-fn level — whole-line click cascading to groupMaxDepth
      // (so empty grouped levels expand together).
      line.style.cursor = 'pointer';
      line.textContent = levelInfo.fns[0].name;
      const fnIdForLine = levelInfo.fns[0].fnId;
      const allFnsAtDepth = [fnIdForLine];
      const targetDepth = levelInfo.groupMaxDepth;
      // Initial highlight (committed state) for single-fn line
      if (fnIsHighlighted(levelInfo.depth, fnIdForLine, fullDepth, partialFns)) {
        line.style.fontWeight = 'bold';
        line.style.background = HIGHLIGHT_BG;
      }
      const onMouseDown = (e) => {
        e.stopPropagation();
        e.preventDefault();
        applyClickSpec(nodeId, targetDepth, fnIdForLine, allFnsAtDepth);
      };
      line.addEventListener('mousedown', onMouseDown);
      line.addEventListener('touchend', onMouseDown);
      line.addEventListener('mouseenter', (e) => {
        if (isGrabbing) return;
        if (!pointerMovedSinceLastPreview(e.clientX, e.clientY)) return;
        recordPreviewPointer(e.clientX, e.clientY);
        const preview = computeSpecAfterClick(
          { fullDepth, partialFns }, targetDepth, fnIdForLine, allFnsAtDepth);
        applyPreviewStyle(preview);
        applyHoverSpec(nodeId, targetDepth, fnIdForLine, allFnsAtDepth);
      });
      line.addEventListener('mouseleave', () => restoreStyles());
    }

    linesByDepth.set(levelInfo.depth, { line, spansByFnId });
    overlay.appendChild(line);
  });

  if (ancestorLevels.length > MAX_VISIBLE_ANCESTORS + 1) {
    const more = document.createElement('div');
    Object.assign(more.style, { padding: '2px 8px', color: '#999', fontSize: '10px' });
    more.textContent = '...';
    overlay.appendChild(more);
  }

  createDragHandle(overlay, node);

  overlay.addEventListener('mouseleave', () => {
    // Don't clear preview if:
    // 1. Overlays are being rebuilt (rebuildingOverlays flag)
    // 2. User is dragging (isGrabbing flag)
    // 3. Overlay was removed from DOM (happens during rebuild, mouseleave fires async)
    if (!rebuildingOverlays && !isGrabbing && overlay.isConnected) {
      clearPreview(nodeId);
    }
  });

  container.appendChild(overlay);
}

/**
 * Create overlay for arg value node
 */
function createArgOverlay(node, container) {
  const overlay = createOverlay(node.id(), { borderRadius: '4px', fontSize: '10px' });

  const content = document.createElement('div');
  content.style.padding = '4px 8px';
  content.textContent = truncateLabel(node.data('label') || '', 30);
  overlay.appendChild(content);

  createDragHandle(overlay, node);
  container.appendChild(overlay);
}

/**
 * Create overlay for placeholder node (unset arg)
 */
function createPlaceholderOverlay(node, container) {
  const overlay = createOverlay(node.id(), { border: '2px dashed black' });

  const content = document.createElement('div');
  content.style.padding = '4px 8px';
  content.textContent = node.data('label') || 'any';
  overlay.appendChild(content);

  createDragHandle(overlay, node);
  container.appendChild(overlay);
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

  // Remove overlays except the preserved one
  document.querySelectorAll('.node-overlay').forEach(el => {
    if (el.dataset.nodeId === preservedOverlayId) {
      // Keep this overlay - it's the one user is hovering over
      return;
    }
    el.remove();
  });

  if (!cy) return;

  const container = document.getElementById('cy');

  // Fn nodes (with ancestor list)
  cy.nodes('[type="fn"][!isPlaceholder]').forEach(node => {
    // Skip if overlay already exists (preserved)
    if (node.id() === preservedOverlayId) {
      const existingOverlay = document.querySelector(`.node-overlay[data-node-id="${node.id()}"]`);
      if (existingOverlay) return;
    }
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

  updateOverlayPositions();
}

/**
 * Update overlay positions based on Cytoscape node positions
 */
function updateOverlayPositions() {
  if (!cy) return;

  const pan = cy.pan();
  const zoom = cy.zoom();

  document.querySelectorAll('.node-overlay').forEach(overlay => {
    const nodeId = overlay.dataset.nodeId;
    if (!nodeId) return;

    const node = cy.getElementById(nodeId);
    if (!node.length) return;

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
  });
}
