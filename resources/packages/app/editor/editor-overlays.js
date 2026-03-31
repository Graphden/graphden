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

  dragHandle.addEventListener('mousedown', (e) => {
    e.stopPropagation();
    e.preventDefault();

    if (!cyNode.length) return;

    isGrabbing = true;
    dragHandle.style.cursor = 'grabbing';
    userMovedNodes.add(cyNode.id());

    let lastX = e.clientX;
    let lastY = e.clientY;

    const onMouseMove = (moveE) => {
      const dx = (moveE.clientX - lastX) / cy.zoom();
      const dy = (moveE.clientY - lastY) / cy.zoom();
      lastX = moveE.clientX;
      lastY = moveE.clientY;

      const pos = cyNode.position();
      cyNode.position({ x: pos.x + dx, y: pos.y + dy });
      updateOverlayPositions();
    };

    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      isGrabbing = false;
      dragHandle.style.cursor = 'grab';
    };

    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  });

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
    fontSize: options.fontSize || '11px'
  });
  return overlay;
}

/**
 * Create overlay for fn node with ancestor list
 */
function createFnOverlay(node, container) {
  const originalFnId = node.data('originalFnId');
  if (!originalFnId) return;

  const chain = getInheritanceChain(originalFnId);
  const items = buildAncestorItems(chain);
  const currentLevel = expansionLevel.get(originalFnId) || 0;
  const visibleItems = items.slice(0, MAX_VISIBLE_ANCESTORS + 1);

  const overlay = createOverlay(node.id());
  overlay.dataset.originalFnId = originalFnId;
  overlay.style.cursor = 'default';

  visibleItems.forEach((item, idx) => {
    const line = document.createElement('div');
    line.className = 'ancestor-line';
    line.dataset.level = item.groupLevel;
    Object.assign(line.style, {
      padding: '4px 8px',
      cursor: 'pointer',
      borderBottom: (visibleItems[idx + 1]?.groupLevel !== item.groupLevel) ? '1px solid #eee' : 'none'
    });
    line.textContent = item.name;

    if (item.groupLevel <= currentLevel) {
      line.style.fontWeight = 'bold';
      line.style.background = '#f0f0f0';
    }

    line.addEventListener('mouseenter', () => {
      if (!isGrabbing) setPreviewLevel(originalFnId, item.groupLevel);
    });
    line.addEventListener('click', (e) => {
      e.stopPropagation();
      setExpansionLevel(originalFnId, item.groupLevel);
    });

    overlay.appendChild(line);
  });

  if (items.length > MAX_VISIBLE_ANCESTORS + 1) {
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
      setPreviewLevel(originalFnId, null);
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
  if (previewLevel.size > 0) {
    const previewFnId = previewLevel.keys().next().value;
    preservedOverlayId = 'fn-' + previewFnId;
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
    const width = node.outerWidth();
    const height = node.outerHeight();

    const screenX = pos.x * zoom + pan.x;
    const screenY = pos.y * zoom + pan.y;

    overlay.style.left = (screenX - width / 2) + 'px';
    overlay.style.top = (screenY - height / 2) + 'px';
    overlay.style.width = width + 'px';
    overlay.style.minHeight = height + 'px';
    overlay.style.transform = 'scale(' + zoom + ')';
    overlay.style.transformOrigin = 'center center';
  });
}
