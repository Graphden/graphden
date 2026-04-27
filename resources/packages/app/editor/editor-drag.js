// Editor Drag - Drag handle for any overlay (allows manual node positioning).
// Depends on: editor-state.js (cy, isGrabbing, userMovedNodes,
// updateOverlayPositions).

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
  const startDrag = (startX, startY, moveEvent, endEvent, getXY, isTouch) => {
    if (!cyNode.length) return;

    isGrabbing = true;
    dragHandle.style.cursor = 'grabbing';
    userMovedNodes.add(cyNode.id());

    // Disable Cytoscape's own user-panning while we own the gesture.
    // Without this, on touch the finger drag also pans the viewport,
    // doubling the visual movement and pulling the node away from the
    // finger. Restored in onEnd.
    const prevUserPanning = cy.userPanningEnabled();
    cy.userPanningEnabled(false);

    let lastX = startX;
    let lastY = startY;

    const onMove = (moveE) => {
      // Touch: prevent browser scroll/zoom AND stop the move from reaching
      // Cytoscape's own touch handlers in case they listen on document.
      if (isTouch) {
        if (moveE.cancelable) moveE.preventDefault();
        moveE.stopPropagation();
      }
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
      document.removeEventListener(moveEvent, onMove, { capture: true });
      document.removeEventListener(endEvent, onEnd, { capture: true });
      cy.userPanningEnabled(prevUserPanning);
      isGrabbing = false;
      dragHandle.style.cursor = 'grab';
    };

    // Capture phase + non-passive touch listener so preventDefault works
    // and we win over any element-level listeners further down the path.
    document.addEventListener(moveEvent, onMove, { capture: true, passive: !isTouch });
    document.addEventListener(endEvent, onEnd, { capture: true });
  };

  dragHandle.addEventListener('mousedown', (e) => {
    e.stopPropagation();
    e.preventDefault();
    startDrag(e.clientX, e.clientY, 'mousemove', 'mouseup',
              (e) => [e.clientX, e.clientY], false);
  });

  dragHandle.addEventListener('touchstart', (e) => {
    e.stopPropagation();
    e.preventDefault();
    const touch = e.touches[0];
    startDrag(touch.clientX, touch.clientY, 'touchmove', 'touchend',
              (e) => [e.touches[0].clientX, e.touches[0].clientY], true);
  }, { passive: false });

  overlay.appendChild(dragHandle);
}
