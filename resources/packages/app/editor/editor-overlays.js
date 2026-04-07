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
 * Groups "empty" ancestors (no args set) with previous "real" ancestor:
 * - No separator line between items in same group
 * - Hover highlights entire group
 * - Click on any item in group activates the group's level
 */
function createFnOverlay(node, container) {
  const originalFnId = node.data('originalFnId');
  if (!originalFnId) return;

  const nodeId = node.id();  // Full node ID including expansion context
  const chain = getInheritanceChain(originalFnId);
  const items = buildAncestorItems(chain);
  const currentLevel = expansionLevel.get(nodeId) || 0;  // Use nodeId for expansion lookup
  const visibleItems = items.slice(0, MAX_VISIBLE_ANCESTORS + 1);

  const overlay = createOverlay(nodeId);
  overlay.dataset.originalFnId = originalFnId;
  overlay.dataset.nodeId = nodeId;  // Store nodeId for expansion
  overlay.style.cursor = 'default';

  // Group items by groupId for hover highlighting
  const linesByGroup = new Map();

  visibleItems.forEach((item, idx) => {
    const line = document.createElement('div');
    line.className = 'ancestor-line';
    line.dataset.level = item.level;
    line.dataset.groupId = item.groupId;
    line.dataset.groupLevel = item.groupLevel;

    // Check if next item is in different group (show separator)
    const nextItem = visibleItems[idx + 1];
    const showSeparator = nextItem && nextItem.groupId !== item.groupId;

    Object.assign(line.style, {
      padding: '4px 8px',
      cursor: 'pointer',
      borderBottom: showSeparator ? '1px solid #eee' : 'none'
    });
    line.textContent = item.name;

    // Current expansion level highlighting
    // All items in group up to and including current level are bold
    if (item.groupLevel <= currentLevel) {
      line.style.fontWeight = 'bold';
      line.style.background = '#f0f0f0';
    }

    // Track lines by group for hover highlighting
    if (!linesByGroup.has(item.groupId)) {
      linesByGroup.set(item.groupId, []);
    }
    linesByGroup.get(item.groupId).push(line);

    // Hover: highlight entire group AND all groups above (younger ancestors)
    // If already expanded to this level or beyond, don't trigger preview
    line.addEventListener('mouseenter', () => {
      if (isGrabbing) return;

      const hoverLevel = item.groupLevel;

      // Highlight this group and all groups with lower level (younger ancestors)
      linesByGroup.forEach((lines, groupId) => {
        const groupLevel = parseInt(lines[0].dataset.groupLevel);
        if (groupLevel <= hoverLevel) {
          lines.forEach(l => {
            l.style.background = '#e8e8e8';
          });
        }
      });

      // Only trigger preview if hovering on level > currentLevel
      // (i.e., expanding to a deeper ancestor)
      if (hoverLevel > currentLevel) {
        setPreviewLevel(nodeId, hoverLevel);
      }
    });

    line.addEventListener('mouseleave', () => {
      // Restore all lines to their proper state based on current expansion
      linesByGroup.forEach((lines, groupId) => {
        const groupLevel = parseInt(lines[0].dataset.groupLevel);
        lines.forEach(l => {
          if (groupLevel <= currentLevel) {
            l.style.background = '#f0f0f0';
          } else {
            l.style.background = '';
          }
        });
      });
    });

    // Click: toggle expansion
    // - If clicking on level > currentLevel: expand to that level
    // - If clicking on level <= currentLevel: collapse to that level
    //   (but clicking on level 0 when currentLevel > 0 collapses to 0)
    // Use nodeId (not originalFnId) so each expansion context is independent
    line.addEventListener('click', (e) => {
      e.stopPropagation();
      const clickLevel = item.groupLevel;

      if (clickLevel > currentLevel) {
        // Expanding to deeper ancestor
        setExpansionLevel(nodeId, clickLevel);
      } else if (clickLevel < currentLevel) {
        // Collapsing to younger ancestor
        setExpansionLevel(nodeId, clickLevel);
      } else {
        // Clicking on current level - collapse one step
        // If at level 0, stay at 0
        setExpansionLevel(nodeId, Math.max(0, clickLevel - 1));
      }
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
      setPreviewLevel(nodeId, null);
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
