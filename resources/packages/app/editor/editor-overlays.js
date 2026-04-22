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
  // Visual model:
  //   Depth 0 (root name): black bg, white text — ALWAYS.
  //   Non-clickable levels merge with the level above: same bg, no separator.
  //   If the non-clickable chain reaches depth 0, the level is ALSO black bg.
  //   Clickable levels: white bg normally, #f0f0f0 when expanded/previewed.
  //   MI: each parent's cell is independently styled.
  const ROOT_BG = '#000';
  const ROOT_FG = '#fff';
  const HIGHLIGHT_BG = '#f0f0f0';
  const linesByDepth = new Map();   // depth -> { line, spansByFnId, levelInfo }

  // Returns true if a particular fn at a given depth would be highlighted
  // under the given preview/committed spec.
  const fnIsHighlighted = (depth, fnId, sFull, sPartial) => {
    if (depth <= sFull) return true;
    if (depth === sFull + 1 && sPartial.has(fnId)) return true;
    return false;
  };

  // Apply styles for a given spec. Handles both root-block styling and
  // expansion highlighting.
  // Determine the visual bg for an MI parent given the current spec.
  const miFnBg = (miFn, miDepth, isRoot, sFull, sPartial) => {
    const fnInRootBlock = isRoot && !miFn.isClickable;
    const highlighted = fnIsHighlighted(miDepth, miFn.fnId, sFull, sPartial);
    if (fnInRootBlock) return { bg: ROOT_BG, fg: ROOT_FG };
    if (highlighted)   return { bg: HIGHLIGHT_BG, fg: '' };
    return { bg: '', fg: '' };
  };

  const paintWithSpec = (sFull, sPartial) => {
    linesByDepth.forEach(({ line, spansByFnId, levelInfo, colDivs, textOverlay }, depth) => {
      if (!levelInfo) return;
      const isRoot = levelInfo.blockIsRoot;

      if (colDivs) {
        // Column-below-MI: paint each column with its MI parent's bg
        const miLevel = visibleLevels[levelInfo.followsMI];
        const miIsRoot = miLevel ? miLevel.blockIsRoot : false;
        colDivs.forEach(({ col, miFn }) => {
          const { bg, fg } = miFnBg(miFn, miLevel.depth, miIsRoot, sFull, sPartial);
          col.style.background = bg;
        });
        // Text overlay: use the color from the first column (or black if mixed)
        const firstBg = miFnBg(colDivs[0].miFn, miLevel.depth, miIsRoot, sFull, sPartial);
        textOverlay.style.color = firstBg.bg === ROOT_BG ? ROOT_FG : '';
        textOverlay.style.fontWeight = isRoot ? 'bold' : 'normal';
      } else if (spansByFnId) {
        // MI line: per-fn styling
        line.style.fontWeight = 'normal';
        line.style.background = '';
        line.style.color = '';
        spansByFnId.forEach(({ span, fn }, fnId) => {
          const { bg, fg } = miFnBg(fn, depth, isRoot, sFull, sPartial);
          span.style.background = bg;
          span.style.color = fg;
          span.style.fontWeight = (bg === ROOT_BG || fnIsHighlighted(depth, fnId, sFull, sPartial))
            ? 'bold' : 'normal';
        });
      } else {
        // Single-fn line
        const fn = levelInfo.fns[0];
        const highlighted = fn && fnIsHighlighted(depth, fn.fnId, sFull, sPartial);
        if (isRoot) {
          line.style.background = ROOT_BG;
          line.style.color = ROOT_FG;
          line.style.fontWeight = 'bold';
        } else if (highlighted) {
          line.style.background = HIGHLIGHT_BG;
          line.style.color = '';
          line.style.fontWeight = 'bold';
        } else {
          line.style.background = '';
          line.style.color = '';
          line.style.fontWeight = 'normal';
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

    // No separator if next level is in the same group, OR if the next level
    // is a column-below-MI (the vertical borders continue, horizontal removed)
    const nextLevel = visibleLevels[idx + 1];
    const isLastInGroup = !nextLevel || nextLevel.groupId !== levelInfo.groupId;
    const isLast = idx === visibleLevels.length - 1;
    const nextIsColumnBelow = nextLevel && nextLevel.followsMI >= 0;
    const lineBorderBottom = (isLast || !isLastInGroup || nextIsColumnBelow)
      ? 'none' : '1px solid #eee';
    Object.assign(line.style, {
      borderBottom: lineBorderBottom,
      touchAction: 'none',
      userSelect: 'none',
      WebkitUserSelect: 'none'
    });

    let spansByFnId = null;
    const miLevelAbove = levelInfo.followsMI >= 0 ? visibleLevels[levelInfo.followsMI] : null;

    if (miLevelAbove && !levelInfo.isMI) {
      // Column-below-MI: non-clickable level below MI parents.
      // Render as flex columns matching the MI parents above, with vertical
      // border continuing down and per-column bg inheriting the MI parent's
      // visual state. The text is positioned absolutely over the columns.
      line.style.display = 'flex';
      line.style.padding = '0';
      line.style.position = 'relative';
      // The fn name floats over the column backgrounds
      const textOverlay = document.createElement('span');
      textOverlay.textContent = levelInfo.fns[0].name;
      Object.assign(textOverlay.style, {
        position: 'absolute', left: '0', right: '0', top: '4px',
        textAlign: 'center',
        pointerEvents: 'none', zIndex: '1'
      });
      // Create invisible column divs for bg + vertical border
      const colDivs = [];
      miLevelAbove.fns.forEach((miFn, i) => {
        const col = document.createElement('div');
        col.style.flex = '1 1 0';
        col.style.minWidth = '0';
        col.style.padding = '4px 8px';
        col.innerHTML = '&nbsp;';  // non-empty so it has height
        // NO visible border — columns are invisible, only for per-column
        // background behavior (left half follows left MI parent's state,
        // right half follows right MI parent's state).
        colDivs.push({ col, miFn });
        line.appendChild(col);
      });
      line.appendChild(textOverlay);
      // Store column info for paintWithSpec
      linesByDepth.set(levelInfo.depth, { line, spansByFnId: null, levelInfo, colDivs, textOverlay });

      // Column-below-MI click/hover: when expanding, use the group's max
      // depth (cascade through MI + this level). When collapsing (already
      // expanded), collapse the WHOLE group by targeting the MI level's
      // depth — so toggle goes to miDepth - 1, removing MI too.
      line.style.cursor = 'pointer';
      const fnIdForLine = levelInfo.fns[0].fnId;
      const allFnsAtDepth = [fnIdForLine];
      const expandDepth = levelInfo.groupMaxDepth;
      const collapseDepth = miLevelAbove.depth;  // collapse whole group
      const getTargetDepth = () => expandDepth <= fullDepth ? collapseDepth : expandDepth;
      const onMouseDown = (e) => {
        e.stopPropagation();
        e.preventDefault();
        const currentFull = (expansionState.get(nodeId) || {}).fullDepth || 0;
        const td = expandDepth <= currentFull ? collapseDepth : expandDepth;
        applyClickSpec(nodeId, td, fnIdForLine, allFnsAtDepth);
      };
      line.addEventListener('mousedown', onMouseDown);
      line.addEventListener('touchend', onMouseDown);
      const triggerPreview = () => {
        if (isGrabbing || shouldSuppressPreview()) return;
        const td = getTargetDepth();
        const preview = computeSpecAfterClick(
          { fullDepth, partialFns }, td, fnIdForLine, allFnsAtDepth);
        applyPreviewStyle(preview || { fullDepth: 0, partialFns: new Set() });
        applyHoverSpec(nodeId, td, fnIdForLine, allFnsAtDepth);
      };
      line.addEventListener('mouseenter', triggerPreview);
      line.addEventListener('mousemove', triggerPreview);
      line.addEventListener('mouseleave', () => { onPreviewLeave(); restoreStyles(); });
      line.addEventListener('mouseleave', () => { onPreviewLeave(); restoreStyles(); });
    } else if (levelInfo.isMI) {
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
      // Compute effective MI expand depth: MI depth + non-clickable followers.
      // When auto-promoting from all MI parents, cascade through them.
      let miEffectiveDepth = levelInfo.depth;
      for (let k = idx + 1; k < visibleLevels.length; k++) {
        if (!visibleLevels[k].anyClickable && !visibleLevels[k].isMI) {
          miEffectiveDepth = visibleLevels[k].depth;
        } else break;
      }
      levelInfo.fns.forEach((f, i) => {
        const span = document.createElement('span');
        span.textContent = f.name;
        span.style.cursor = 'pointer';
        span.style.padding = '4px 8px';
        span.style.flex = '1 1 0';
        span.style.minWidth = '0';
        span.style.textAlign = 'center';
        if (i < levelInfo.fns.length - 1) {
          span.style.borderRight = '1px solid #eee';
        }
        // Initial styling: root-block or highlighted
        const fnInRootBlock = levelInfo.blockIsRoot && !f.isClickable;
        if (fnInRootBlock) {
          span.style.background = ROOT_BG;
          span.style.color = ROOT_FG;
          span.style.fontWeight = 'bold';
        } else if (fnIsHighlighted(levelInfo.depth, f.fnId, fullDepth, partialFns)) {
          span.style.fontWeight = 'bold';
          span.style.background = HIGHLIGHT_BG;
        }
        spansByFnId.set(f.fnId, { span, fn: f });

        // Post-process: when auto-promote from MI fills the level,
        // cascade through non-clickable followers (e.g. ring-response).
        const cascadePromoted = (spec) => {
          if (!spec) return spec;
          if (spec.fullDepth === levelInfo.depth && spec.partialFns.size === 0
              && miEffectiveDepth > levelInfo.depth) {
            return { fullDepth: miEffectiveDepth, partialFns: new Set() };
          }
          return spec;
        };
        // MI per-fn click
        const onMouseDown = (e) => {
          e.stopPropagation();
          e.preventDefault();
          // Compute spec, cascade through followers if promoted
          const raw = computeSpecAfterClick(getSpec(nodeId), levelInfo.depth, f.fnId, allFnsAtDepth);
          const spec = cascadePromoted(raw);
          if (spec === null) { expansionState.delete(nodeId); }
          else { expansionState.set(nodeId, spec); }
          suppressPreviewOnClick();
          savedUserPositions.clear();
          previewState.delete(nodeId);
          anchorNodeId = nodeId;
          renderGraph(false);
          anchorNodeId = null;
        };
        span.addEventListener('mousedown', onMouseDown);
        span.addEventListener('touchend', onMouseDown);
        const triggerSpanPreview = () => {
          if (isGrabbing || shouldSuppressPreview()) return;
          const raw = computeSpecAfterClick(
            { fullDepth, partialFns }, levelInfo.depth, f.fnId, allFnsAtDepth);
          const preview = cascadePromoted(raw);
          applyPreviewStyle(preview || { fullDepth: 0, partialFns: new Set() });
          // Use effective depth so the backend render matches the cascaded spec
          const hoverDepth = (preview && preview.fullDepth === miEffectiveDepth) ? miEffectiveDepth : levelInfo.depth;
          applyHoverSpec(nodeId, hoverDepth, f.fnId, allFnsAtDepth);
        };
        span.addEventListener('mouseenter', triggerSpanPreview);
        span.addEventListener('mousemove', triggerSpanPreview);
        span.addEventListener('mouseleave', () => { onPreviewLeave(); restoreStyles(); });
        line.appendChild(span);
      });
    } else {
      // Non-MI line: padding on the line itself
      line.style.padding = '4px 8px';
      line.style.textAlign = 'center';
      // Single-fn level — whole-line click cascading to groupMaxDepth
      // (so empty grouped levels expand together).
      line.style.cursor = 'pointer';
      line.textContent = levelInfo.fns[0].name;
      const fnIdForLine = levelInfo.fns[0].fnId;
      const allFnsAtDepth = [fnIdForLine];
      const targetDepth = levelInfo.groupMaxDepth;
      // Initial styling: root-block or highlighted
      if (levelInfo.blockIsRoot) {
        line.style.background = ROOT_BG;
        line.style.color = ROOT_FG;
        line.style.fontWeight = 'bold';
      } else if (fnIsHighlighted(levelInfo.depth, fnIdForLine, fullDepth, partialFns)) {
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
      const triggerLinePreview = () => {
        if (isGrabbing || shouldSuppressPreview()) return;
        const preview = computeSpecAfterClick(
          { fullDepth, partialFns }, targetDepth, fnIdForLine, allFnsAtDepth);
        applyPreviewStyle(preview || { fullDepth: 0, partialFns: new Set() });
        applyHoverSpec(nodeId, targetDepth, fnIdForLine, allFnsAtDepth);
      };
      line.addEventListener('mouseenter', triggerLinePreview);
      line.addEventListener('mousemove', triggerLinePreview);
      line.addEventListener('mouseleave', () => { onPreviewLeave(); restoreStyles(); });
    }

    if (!linesByDepth.has(levelInfo.depth)) {
      linesByDepth.set(levelInfo.depth, { line, spansByFnId, levelInfo });
    }
    overlay.appendChild(line);
  });

  // Paint all lines/columns with the committed state. This is needed
  // because column-below-MI divs don't set their initial bg in the
  // constructor — it's computed dynamically from the MI parents' state.
  restoreStyles();

  if (ancestorLevels.length > MAX_VISIBLE_ANCESTORS + 1) {
    const more = document.createElement('div');
    Object.assign(more.style, { padding: '2px 8px', color: '#999', fontSize: '10px' });
    more.textContent = '...';
    overlay.appendChild(more);
  }

  // Optional-but-unbound args (e.g. :get.default when no default was supplied)
  // render as a thin, muted strip instead of their own placeholder nodes —
  // they carry sane fallbacks so they're not part of the function's interface,
  // just a nicety the caller may or may not care about.
  const optionalArgs = node.data('optionalArgs');
  if (Array.isArray(optionalArgs) && optionalArgs.length) {
    const strip = document.createElement('div');
    Object.assign(strip.style, {
      padding: '2px 8px',
      color: '#888',
      fontSize: '10px',
      fontStyle: 'italic',
      borderTop: '1px dashed #ccc',
      background: '#fafafa',
      whiteSpace: 'nowrap',
      overflow: 'hidden',
      textOverflow: 'ellipsis'
    });
    strip.title = 'Optional args (unset, using defaults): ' + optionalArgs.join(', ');
    strip.textContent = optionalArgs.map(n => '?' + n).join(' ');
    overlay.appendChild(strip);
  }

  // HOF-captured args (e.g. `:request` on a Ring-handler subtree) are free
  // slots that the enclosing higher-order call site will fill at runtime —
  // not interface args for the graph-level caller. Render as a compact
  // strip prefixed with `λ` so the user can see the slot exists without
  // needing to plan for supplying it themselves.
  const hofCapturedArgs = node.data('hofCapturedArgs');
  if (Array.isArray(hofCapturedArgs) && hofCapturedArgs.length) {
    const strip = document.createElement('div');
    Object.assign(strip.style, {
      padding: '2px 8px',
      color: '#557',
      fontSize: '10px',
      fontStyle: 'italic',
      borderTop: '1px dashed #b8c0e0',
      background: '#f4f6fc',
      whiteSpace: 'nowrap',
      overflow: 'hidden',
      textOverflow: 'ellipsis'
    });
    strip.title = 'Args supplied by the enclosing HOF invocation: ' + hofCapturedArgs.join(', ');
    strip.textContent = hofCapturedArgs.map(n => 'λ' + n).join(' ');
    overlay.appendChild(strip);
  }

  createDragHandle(overlay, node);

  // Hovering the FN overlay itself (not a specific outgoing edge) lights up
  // the whole outgoing bundle — the "start point before the args" view the
  // user asked for. Use mouseenter/leave so the highlight follows the
  // overlay rectangle precisely and does not leak into child elements.
  overlay.addEventListener('mouseenter', () => {
    if (!cy) return;
    const cyNode = cy.getElementById(nodeId);
    if (cyNode && cyNode.length) {
      cyNode.outgoers('edge').addClass('edge-hovered');
    }
  });
  overlay.addEventListener('mouseleave', () => {
    if (cy) cy.edges('.edge-hovered').removeClass('edge-hovered');
  });

  overlay.addEventListener('mouseleave', () => {
    // Don't clear preview if:
    // 1. Overlays are being rebuilt (rebuildingOverlays flag)
    // 2. User is dragging (isGrabbing flag)
    // 3. Overlay was removed from DOM (happens during rebuild, mouseleave fires async)
    if (!rebuildingOverlays && !isGrabbing && overlay.isConnected) {
      onPreviewLeave();
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
 * Create overlay for an edge label (multi-line aware).
 * Positioned just to the left of the target node, vertically centered
 * on the target. Uses pre-line white-space so \n in the label produces
 * actual line breaks.
 */
function createEdgeLabelOverlay(edge, container) {
  const label = edge.data('argName');
  if (!label) return;

  const overlay = document.createElement('div');
  overlay.className = 'edge-label-overlay';
  overlay.dataset.edgeId = edge.id();
  overlay.textContent = label;
  Object.assign(overlay.style, {
    position: 'absolute',
    pointerEvents: 'none',
    zIndex: '5',
    background: '#ffffff',
    color: '#666666',
    fontFamily: 'SF Mono, Monaco, monospace',
    fontSize: '10px',
    lineHeight: '1.2',
    padding: '2px 4px',
    whiteSpace: 'pre',
    textAlign: 'left',
    transformOrigin: 'top left',
    userSelect: 'none',
    WebkitUserSelect: 'none'
  });
  container.appendChild(overlay);
}

/**
 * Create overlay for placeholder node (unset arg)
 */
function createPlaceholderOverlay(node, container) {
  const overlay = createOverlay(node.id(), { border: '2px dashed black' });
  // The enclosing cy-node uses a shared minimum height larger than the
  // placeholder's natural content, so without a column flex the drag handle
  // would sit at its content offset and leave a blank strip between it and
  // the bottom border.
  overlay.style.display = 'flex';
  overlay.style.flexDirection = 'column';

  const content = document.createElement('div');
  content.style.padding = '4px 8px';
  content.style.flex = '1';
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

  // Remove any stale edge label overlays then create fresh ones
  document.querySelectorAll('.edge-label-overlay').forEach(el => el.remove());
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

  // Position edge label overlays. Anchor: visual right edge sits 6px to the
  // left of target's left edge, vertically centered on the target.
  // We measure the element's UNSCALED width/height (offsetWidth/Height ignore
  // transforms) and compute pixel positions so the visual top-left lands at
  // (screenRight - w*zoom, screenMid - h*zoom/2). Origin 'top left' means
  // scaling around the top-left corner — the corner stays at left/top.
  document.querySelectorAll('.edge-label-overlay').forEach(overlay => {
    const edgeId = overlay.dataset.edgeId;
    if (!edgeId) return;
    const edge = cy.getElementById(edgeId);
    if (!edge.length) return;
    const target = edge.target();
    if (!target.length) return;

    const tPos = target.position();
    const tWidth = target.width();
    const screenRight = (tPos.x - tWidth / 2) * zoom + pan.x - 6;
    const screenMid = tPos.y * zoom + pan.y;

    const w = overlay.offsetWidth;
    const h = overlay.offsetHeight;
    overlay.style.left = (screenRight - w * zoom) + 'px';
    overlay.style.top = (screenMid - h * zoom / 2) + 'px';
    overlay.style.transform = 'scale(' + zoom + ')';
  });
}
