// Editor Overlays - HTML overlays for nodes (ancestor list, drag handles)
// Depends on: editor-state.js, editor-data.js

// ============================================================================
// DESCRIPTION TOOLTIP
// ============================================================================
//
// Native `title` attribute is unreliable inside Cytoscape overlays —
// the cy canvas + parent mouseenter/leave handlers swallow the hover
// before the browser's tooltip delay fires. We render our own tooltip
// element on mouseenter/leave instead.

let descriptionTooltipEl = null;

function ensureDescriptionTooltip() {
  if (descriptionTooltipEl) return descriptionTooltipEl;
  const el = document.createElement('div');
  el.className = 'description-tooltip';
  Object.assign(el.style, {
    position: 'fixed',
    zIndex: '10000',
    background: 'rgba(0,0,0,0.88)',
    color: '#fff',
    fontFamily: 'system-ui, sans-serif',
    fontSize: '12px',
    lineHeight: '1.4',
    padding: '6px 10px',
    borderRadius: '4px',
    maxWidth: '360px',
    pointerEvents: 'none',
    boxShadow: '0 2px 8px rgba(0,0,0,0.25)',
    display: 'none',
    whiteSpace: 'pre-wrap'
  });
  document.body.appendChild(el);
  descriptionTooltipEl = el;
  return el;
}

function showDescriptionTooltip(content, evt) {
  const el = ensureDescriptionTooltip();
  el.textContent = '';
  // Accept either a plain string (legacy callers) or {namespace, description}.
  const ns = (content && typeof content === 'object') ? content.namespace : null;
  const text = (content && typeof content === 'object') ? content.description : content;
  if (ns) {
    const nsRow = document.createElement('div');
    nsRow.textContent = ns;
    nsRow.style.fontStyle = 'italic';
    nsRow.style.opacity = '0.7';
    nsRow.style.fontSize = '11px';
    nsRow.style.marginBottom = text ? '4px' : '0';
    el.appendChild(nsRow);
  }
  if (text) {
    const body = document.createElement('div');
    body.textContent = text;
    el.appendChild(body);
  }
  el.style.display = 'block';
  // Position next to the cursor; clamp to viewport.
  const margin = 12;
  const x = Math.min(evt.clientX + margin, window.innerWidth - el.offsetWidth - margin);
  const y = Math.min(evt.clientY + margin, window.innerHeight - el.offsetHeight - margin);
  el.style.left = x + 'px';
  el.style.top = y + 'px';
}

function hideDescriptionTooltip() {
  if (descriptionTooltipEl) descriptionTooltipEl.style.display = 'none';
}

// ============================================================================
// FULL-NAME POPOVER
// ============================================================================
//
// Ancestor rows truncate long names with an ellipsis. Hovering a row
// whose name doesn't fit the row width pops a small bubble above the
// node showing the full name. Singleton element, fixed position,
// fades in/out via opacity + translateY transition.

let fullNameTooltipEl = null;

function ensureFullNameTooltip() {
  if (fullNameTooltipEl) return fullNameTooltipEl;
  const el = document.createElement('div');
  el.className = 'full-name-tooltip';
  Object.assign(el.style, {
    position: 'fixed',
    zIndex: '9999',
    background: '#ffffff',
    color: '#000000',
    border: '1px solid #ccc',
    boxShadow: '0 4px 12px rgba(0,0,0,0.18)',
    padding: '4px 10px',
    borderRadius: '4px',
    fontFamily: 'inherit',
    fontSize: '12px',
    fontWeight: '600',
    whiteSpace: 'nowrap',
    pointerEvents: 'none',
    opacity: '0',
    transform: 'translateY(4px)',
    transition: 'opacity 110ms ease-out, transform 110ms ease-out',
    display: 'none'
  });
  document.body.appendChild(el);
  fullNameTooltipEl = el;
  return el;
}

function showFullNameTooltip(name, anchorEl) {
  const el = ensureFullNameTooltip();
  el.textContent = name;
  el.style.display = 'block';
  // Reset to entry state so re-show animates again even if previously
  // shown without leaving in between.
  el.style.opacity = '0';
  el.style.transform = 'translateY(4px)';
  // Force layout so offsetWidth/Height reflect the new text.
  void el.offsetWidth;
  const anchorRect = anchorEl.getBoundingClientRect();
  const gap = 6;
  let top = anchorRect.top - el.offsetHeight - gap;
  // Flip below when there's no room above.
  if (top < 8) top = anchorRect.bottom + gap;
  let left = anchorRect.left;
  if (left + el.offsetWidth > window.innerWidth - 8) {
    left = window.innerWidth - el.offsetWidth - 8;
  }
  if (left < 8) left = 8;
  el.style.left = left + 'px';
  el.style.top = top + 'px';
  requestAnimationFrame(() => {
    el.style.opacity = '1';
    el.style.transform = 'translateY(0)';
  });
}

function hideFullNameTooltip() {
  if (!fullNameTooltipEl) return;
  fullNameTooltipEl.style.opacity = '0';
  fullNameTooltipEl.style.transform = 'translateY(4px)';
  // Remove from layout once the fade has played out so it doesn't
  // catch hit-tests on stale geometry.
  setTimeout(() => {
    if (fullNameTooltipEl && fullNameTooltipEl.style.opacity === '0') {
      fullNameTooltipEl.style.display = 'none';
    }
  }, 130);
}

// Binds hover handlers that pop the full name when the visible text is
// truncated. `hoverEl` is the element whose mouseenter/leave we listen
// to; `measureEl` is where we measure scrollWidth vs clientWidth (often
// the same element, but for column-below-MI rows the measurement target
// is the floating textOverlay while the hover target is the line).
function bindFullNameHover(hoverEl, measureEl, fullName) {
  if (!fullName) return;
  hoverEl.addEventListener('mouseenter', () => {
    if (measureEl.scrollWidth > measureEl.clientWidth + 1) {
      showFullNameTooltip(fullName, measureEl);
    }
  });
  hoverEl.addEventListener('mouseleave', hideFullNameTooltip);
}

// Shared box styling for the right-edge action icons (description ⓘ
// and open-in-new-tab ↗). A 1-px border around each glyph turns them
// into clearly-clickable buttons instead of bare characters that the
// user can easily miss when aiming.
function applyActionIconBox(el) {
  el.style.display = 'inline-flex';
  el.style.alignItems = 'center';
  el.style.justifyContent = 'center';
  el.style.boxSizing = 'border-box';
  el.style.width = '15px';
  el.style.height = '15px';
  el.style.lineHeight = '1';
  el.style.border = '1px solid currentColor';
  el.style.borderRadius = '3px';
  el.style.fontSize = '10px';
}

function createDescriptionBadge(description, opts) {
  if (!description) return null;
  opts = opts || {};
  const badge = document.createElement('span');
  badge.className = 'description-badge';
  badge.textContent = 'i';
  // Inherit color from the parent row — default text is dark → black
  // badge, root-block rows that flip to ROOT_FG (white) get a white
  // badge automatically without per-site logic.
  badge.style.color = 'currentColor';
  badge.style.cursor = 'help';
  badge.style.fontWeight = 'normal';
  badge.style.fontStyle = 'italic';
  badge.style.opacity = '0.85';
  applyActionIconBox(badge);
  // Override pointer-events: some overlay containers opt out (e.g.
  // the floating column-below-MI text overlay) but we need the
  // hover events here.
  badge.style.pointerEvents = 'auto';
  if (opts.pinRight) {
    // Pinned to the right edge so the centered fn name stays centered
    // and the badge never overlaps the click-target text.
    // Parent must be position:relative.
    badge.style.position = 'absolute';
    badge.style.right = '6px';
    badge.style.top = '50%';
    badge.style.transform = 'translateY(-50%)';
  } else {
    badge.style.marginLeft = '4px';
    badge.style.verticalAlign = 'middle';
  }
  // Tooltip payload — namespace appears as a small italic header above
  // the description body when both are present.
  const tooltipContent = { namespace: opts.namespace || null, description };
  badge.addEventListener('mouseenter', (e) => {
    // Hover on the badge must NOT trigger a row-level expansion preview.
    // The parent's mouseenter still fires on first entry — the optional
    // onEnter callback is the row's preview-clear, undoing that preview.
    if (opts.onEnter) opts.onEnter();
    hideFullNameTooltip();
    showDescriptionTooltip(tooltipContent, e);
  });
  badge.addEventListener('mousemove', (e) => {
    // mousemove bubbles, so without stopPropagation the row's mousemove
    // would re-fire its preview while the cursor sits on the badge.
    e.stopPropagation();
    showDescriptionTooltip(tooltipContent, e);
  });
  badge.addEventListener('mouseleave', hideDescriptionTooltip);
  // Click on the badge must not commit an expansion either — swallow
  // mousedown/touchend so the row's click handler doesn't fire.
  badge.addEventListener('mousedown', (e) => e.stopPropagation());
  badge.addEventListener('touchend', (e) => e.stopPropagation());
  return badge;
}

// "Open in new tab" link for an ancestor row. Returns null when the fn
// has no globally-resolvable name (anonymous local fns can't be linked
// to via the URL hash). Pinned to the right edge alongside the
// description badge.
function createOpenInNewTabButton(fn, opts) {
  if (!fn) return null;
  const qualified = getQualifiedFnName(fn);
  if (!qualified || qualified === '(anonymous)') return null;
  opts = opts || {};
  const link = document.createElement('a');
  link.className = 'open-in-new-tab';
  link.href = '#' + encodeURIComponent(qualified);
  link.target = '_blank';
  link.rel = 'noopener';
  link.title = 'Open ' + qualified + ' in a new tab';
  link.textContent = '↗';
  link.style.color = 'currentColor';
  link.style.cursor = 'pointer';
  link.style.fontWeight = 'normal';
  link.style.textDecoration = 'none';
  link.style.opacity = '0.85';
  applyActionIconBox(link);
  link.style.fontSize = '11px';  // ↗ glyph reads better a hair larger
  link.style.pointerEvents = 'auto';
  if (opts.pinRight) {
    // Sits just left of the description badge (which is at right:6px).
    link.style.position = 'absolute';
    link.style.right = '24px';
    link.style.top = '50%';
    link.style.transform = 'translateY(-50%)';
  } else {
    link.style.marginLeft = '4px';
    link.style.verticalAlign = 'middle';
  }
  link.addEventListener('mouseenter', () => {
    if (opts.onEnter) opts.onEnter();
    hideFullNameTooltip();
  });
  link.addEventListener('mousemove', (e) => { e.stopPropagation(); });
  // Anchor handles navigation natively; just make sure mousedown/touchend
  // don't bubble into the row's expansion handler.
  link.addEventListener('mousedown', (e) => e.stopPropagation());
  link.addEventListener('touchend', (e) => e.stopPropagation());
  return link;
}

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
    background: options.background || 'white',
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
  // Navigation root vs expanded child: the root's nodeId is just `fn-{uuid}`
  // (single fn-id segment); expanded children carry the full ancestry path
  // (`fn-{root}-{intermediate}…-{leaf}`). Used below to decide whether to
  // prepend the "use-site" header row.
  const isNavRoot = nodeId === 'fn-' + originalFnId;
  const ownFn = lookups.fnMap.get(originalFnId);
  const isLocalFn = !(ownFn && ownFn.name);

  const levels = getInheritanceLevels(originalFnId);
  const ancestorLevels = buildAncestorLevels(levels);
  // When a use-site row will be prepended (named non-root), it takes over
  // the role of "root header". The ancestor levels then start outside the
  // root-block — a clickable depth-0 like `all-routes` reads as a normal
  // expandable row (white) rather than a passive black header. Non-clickable
  // depths still propagate the root-block treatment (so `list` below
  // `all-routes` stays black until a clickable level breaks the chain).
  const willPrependUseSite = !isNavRoot && !isLocalFn;
  if (willPrependUseSite) {
    let currentBlockIsRoot = true;
    ancestorLevels.forEach((lv) => {
      if (lv.anyClickable) currentBlockIsRoot = false;
      lv.blockIsRoot = currentBlockIsRoot;
    });
  }
  const spec = expansionState.get(nodeId) || { fullDepth: 0, partialFns: new Set() };
  const fullDepth = spec.fullDepth;
  const partialFns = spec.partialFns;
  const visibleLevels = ancestorLevels.slice(0, MAX_VISIBLE_ANCESTORS + 1);

  // Black container bg so adjacent black rows tile perfectly at sub-pixel
  // boundaries — any rounding slack shows the same colour, no white seam.
  // Non-black rows set their own bg explicitly (paintWithSpec / row
  // creation paths) so they don't bleed black through.
  const overlay = createOverlay(nodeId, { background: '#000' });
  overlay.dataset.originalFnId = originalFnId;
  overlay.dataset.nodeId = nodeId;
  overlay.style.cursor = 'default';

  // Preview/committed states share the SAME visual style so the click is
  // visually invisible — hovering shows a preview, clicking commits it but
  // the visual stays the same; only when the user leaves and re-enters
  // does the new state become visible.
  // Visual model:
  //   Nav-root / local-non-root: depth 0 is the "root header" (black bg,
  //     white text). Non-clickable levels below it inherit that black-block
  //     treatment until a clickable level breaks the chain.
  //   Named-non-root: a separate empty "use-site" row IS the root header.
  //     Ancestor levels start outside the root-block — depth 0 (the fn name)
  //     reads as a regular content row, white by default, #f0f0f0 only when
  //     it's actually inside the currently-applied expansion.
  //   Within a group: same bg, no horizontal separator.
  //   MI: each parent's cell is independently styled.
  const ROOT_BG = '#000';
  const ROOT_FG = '#fff';
  const HIGHLIGHT_BG = '#f0f0f0';
  // Default non-root row bg — explicit white so the black overlay container
  // can't bleed through sub-pixel gaps between adjacent rows.
  const DEFAULT_BG = '#fff';

  // Sub-pixel-gap mitigation: a 1px box-shadow with the row's own colour
  // fills any rounding slack between this row and the next. When two
  // adjacent rows share the same colour the gap reads as that colour
  // (i.e. invisible). Without this, the black overlay container shows
  // through the gap between two white rows (and vice-versa).
  const setRowBg = (el, bg) => {
    el.style.background = bg;
    el.style.boxShadow = bg ? `0 1px 0 0 ${bg}` : '';
  };
  const linesByDepth = new Map();   // depth -> { line, spansByFnId, levelInfo }

  // Returns true if a particular fn at a given depth would be highlighted
  // under the given preview/committed spec. With NO expansion at all
  // (sFull=0 and partial empty) NOTHING is highlighted — including depth 0.
  // Otherwise depth 0 would always read as gray "expanded", confusing the
  // signal that highlighting is meant to carry: "this level is part of
  // the currently-displayed expansion".
  const fnIsHighlighted = (depth, fnId, sFull, sPartial) => {
    if (sFull <= 0 && (!sPartial || sPartial.size === 0)) return false;
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
    return { bg: DEFAULT_BG, fg: '' };
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
          const { bg } = miFnBg(miFn, miLevel.depth, miIsRoot, sFull, sPartial);
          setRowBg(col, bg);
        });
        // Text overlay: use the color from the first column (or black if mixed)
        const firstBg = miFnBg(colDivs[0].miFn, miLevel.depth, miIsRoot, sFull, sPartial);
        textOverlay.style.color = firstBg.bg === ROOT_BG ? ROOT_FG : '';
        textOverlay.style.fontWeight = isRoot ? 'bold' : 'normal';
      } else if (spansByFnId) {
        // MI line: per-fn styling. Line itself stays the DEFAULT bg so any
        // sub-pixel slack between line and overlay container shows white.
        line.style.fontWeight = 'normal';
        setRowBg(line, DEFAULT_BG);
        line.style.color = '';
        spansByFnId.forEach(({ span, fn }, fnId) => {
          const { bg, fg } = miFnBg(fn, depth, isRoot, sFull, sPartial);
          setRowBg(span, bg);
          span.style.color = fg;
          span.style.fontWeight = (bg === ROOT_BG || fnIsHighlighted(depth, fnId, sFull, sPartial))
            ? 'bold' : 'normal';
        });
      } else {
        // Single-fn line
        const fn = levelInfo.fns[0];
        const highlighted = fn && fnIsHighlighted(depth, fn.fnId, sFull, sPartial);
        if (isRoot) {
          setRowBg(line, ROOT_BG);
          line.style.color = ROOT_FG;
          line.style.fontWeight = 'bold';
        } else if (highlighted) {
          setRowBg(line, HIGHLIGHT_BG);
          line.style.color = '';
          line.style.fontWeight = 'bold';
        } else {
          setRowBg(line, DEFAULT_BG);
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

  // Use-site header for named non-root nodes. The use-site is the position
  // this fn occupies in the parent's expansion — it has no name of its own,
  // so we render an empty black row to mirror what local fns get for free
  // (their depth-0 row is already empty because the fn itself is anonymous).
  // Click collapses every expansion currently on this node; cursor reflects
  // whether there's anything to collapse.
  if (!isNavRoot && !isLocalFn) {
    const useSite = document.createElement('div');
    useSite.className = 'ancestor-line';
    useSite.dataset.useSite = 'true';
    const hasExpansion = expansionState.has(nodeId);
    Object.assign(useSite.style, {
      color: ROOT_FG,
      borderBottom: 'none',
      cursor: hasExpansion ? 'pointer' : 'default',
      touchAction: 'none',
      userSelect: 'none',
      WebkitUserSelect: 'none'
    });
    setRowBg(useSite, ROOT_BG);
    const onUseSiteMouseDown = (e) => {
      e.stopPropagation();
      e.preventDefault();
      if (!expansionState.has(nodeId)) return;
      anchorNodeId = nodeId;
      expansionState.delete(nodeId);
      previewState.delete(nodeId);
      suppressPreviewOnClick();
      savedUserPositions.clear();
      renderGraph(false);
      anchorNodeId = null;
    };
    useSite.addEventListener('mousedown', onUseSiteMouseDown);
    useSite.addEventListener('touchend', onUseSiteMouseDown);
    // Hover preview: when there IS something to collapse, show what the
    // overlay (recoloring) and the graph (layout drop) would look like
    // post-click. No-op when nothing to collapse — the row is a passive
    // header in that state.
    const triggerUseSitePreview = () => {
      if (isGrabbing || shouldSuppressPreview()) return;
      if (!expansionState.has(nodeId)) return;
      const collapsedSpec = { fullDepth: 0, partialFns: new Set() };
      applyPreviewStyle(collapsedSpec);
      // depth=0 → computeSpecAfterClick returns null → applyHoverSpec
      // stores {fullDepth:0, empty} in previewState (the layout fallback).
      applyHoverSpec(nodeId, 0, originalFnId, [originalFnId]);
    };
    useSite.addEventListener('mouseenter', triggerUseSitePreview);
    useSite.addEventListener('mousemove', triggerUseSitePreview);
    useSite.addEventListener('mouseleave', () => { onPreviewLeave(); restoreStyles(); });
    overlay.appendChild(useSite);
  }

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
      // The fn name floats over the column backgrounds. When the row owns
      // a description badge or an open-in-new-tab link pinned to the
      // right we shrink the text area symmetrically so wrapped text
      // never spills under those controls — and the centering point
      // stays unchanged.
      const colFn = levelInfo.fns[0];
      const colHasDesc = !!colFn.description;
      const colShowOpen = !!colFn.name && !(isNavRoot && levelInfo.depth === 0);
      // Asymmetric: only the right side reserves room for the action
      // icons. Symmetric reservation eats too much width in narrow MI
      // cells and wraps the name behind the icons.
      const colRightInset = (colHasDesc && colShowOpen) ? '42px'
                          : (colHasDesc || colShowOpen) ? '24px'
                          : '0';
      const textOverlay = document.createElement('span');
      textOverlay.textContent = colFn.name;
      Object.assign(textOverlay.style, {
        position: 'absolute',
        left: '8px',
        right: colRightInset,
        top: '4px',
        textAlign: 'left',
        whiteSpace: 'nowrap',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
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
      const colClearPreview = () => { onPreviewLeave(); clearPreview(nodeId); restoreStyles(); };
      const colDescBadge = createDescriptionBadge(colFn.description, {
        pinRight: true,
        onEnter: colClearPreview,
        namespace: getFnNamespace(lookups.fnMap.get(colFn.fnId))
      });
      if (colDescBadge) {
        colDescBadge.style.zIndex = '2';
        line.appendChild(colDescBadge);
      }
      if (colShowOpen) {
        const colOpenBtn = createOpenInNewTabButton(lookups.fnMap.get(colFn.fnId), {
          pinRight: true,
          onEnter: colClearPreview
        });
        if (colOpenBtn) {
          colOpenBtn.style.zIndex = '2';
          line.appendChild(colOpenBtn);
        }
      }
      bindFullNameHover(line, textOverlay, colFn.name);
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
        const miShowOpen = !!f.name && !(isNavRoot && levelInfo.depth === 0);
        // Asymmetric right padding leaves the action icons their own
        // zone without halving the cell's text width on both sides.
        const miInset = (f.description && miShowOpen) ? '4px 42px 4px 8px'
                      : (f.description || miShowOpen) ? '4px 24px 4px 8px'
                      : '4px 8px';
        // Right padding reserves room for the action icons; text is
        // left-aligned and truncated with an ellipsis when the cell is
        // narrower than the name. Hover reveals the full name.
        span.style.padding = miInset;
        span.style.flex = '1 1 0';
        span.style.minWidth = '0';
        span.style.textAlign = 'left';
        span.style.whiteSpace = 'nowrap';
        span.style.overflow = 'hidden';
        span.style.textOverflow = 'ellipsis';
        span.style.position = 'relative';
        bindFullNameHover(span, span, f.name);
        const miClearPreview = () => { onPreviewLeave(); clearPreview(nodeId); restoreStyles(); };
        const miDescBadge = createDescriptionBadge(f.description, {
          pinRight: true,
          onEnter: miClearPreview,
          namespace: getFnNamespace(lookups.fnMap.get(f.fnId))
        });
        if (miDescBadge) span.appendChild(miDescBadge);
        if (miShowOpen) {
          const miOpenBtn = createOpenInNewTabButton(lookups.fnMap.get(f.fnId), {
            pinRight: true,
            onEnter: miClearPreview
          });
          if (miOpenBtn) span.appendChild(miOpenBtn);
        }
        if (i < levelInfo.fns.length - 1) {
          span.style.borderRight = '1px solid #eee';
        }
        // Initial styling: root-block or highlighted
        const fnInRootBlock = levelInfo.blockIsRoot && !f.isClickable;
        if (fnInRootBlock) {
          setRowBg(span, ROOT_BG);
          span.style.color = ROOT_FG;
          span.style.fontWeight = 'bold';
        } else if (fnIsHighlighted(levelInfo.depth, f.fnId, fullDepth, partialFns)) {
          span.style.fontWeight = 'bold';
          setRowBg(span, HIGHLIGHT_BG);
        } else {
          setRowBg(span, DEFAULT_BG);
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
      // Non-MI line: padding on the line itself.
      // Reserve symmetric horizontal room when right-pinned controls
      // are present, so wrapped names stay clear of them and the
      // visual centering point doesn't shift.
      const lineFn = levelInfo.fns[0];
      const lineHasDesc = !!lineFn.description;
      const lineShowOpen = !!lineFn.name && !(isNavRoot && levelInfo.depth === 0);
      line.style.padding = (lineHasDesc && lineShowOpen) ? '4px 42px 4px 8px'
                         : (lineHasDesc || lineShowOpen) ? '4px 24px 4px 8px'
                         : '4px 8px';
      line.style.textAlign = 'left';
      line.style.whiteSpace = 'nowrap';
      line.style.overflow = 'hidden';
      line.style.textOverflow = 'ellipsis';
      line.style.position = 'relative';
      // Single-fn level — whole-line click cascading to groupMaxDepth
      // (so empty grouped levels expand together).
      line.style.cursor = 'pointer';
      line.textContent = lineFn.name;
      const lineClearPreview = () => { onPreviewLeave(); clearPreview(nodeId); restoreStyles(); };
      const lineDescBadge = createDescriptionBadge(lineFn.description, {
        pinRight: true,
        onEnter: lineClearPreview,
        namespace: getFnNamespace(lookups.fnMap.get(lineFn.fnId))
      });
      if (lineDescBadge) line.appendChild(lineDescBadge);
      if (lineShowOpen) {
        const lineOpenBtn = createOpenInNewTabButton(lookups.fnMap.get(lineFn.fnId), {
          pinRight: true,
          onEnter: lineClearPreview
        });
        if (lineOpenBtn) line.appendChild(lineOpenBtn);
      }
      bindFullNameHover(line, line, lineFn.name);
      const fnIdForLine = levelInfo.fns[0].fnId;
      const allFnsAtDepth = [fnIdForLine];
      const targetDepth = levelInfo.groupMaxDepth;
      // Initial styling: root-block or highlighted
      if (levelInfo.blockIsRoot) {
        setRowBg(line, ROOT_BG);
        line.style.color = ROOT_FG;
        line.style.fontWeight = 'bold';
      } else if (fnIsHighlighted(levelInfo.depth, fnIdForLine, fullDepth, partialFns)) {
        line.style.fontWeight = 'bold';
        setRowBg(line, HIGHLIGHT_BG);
      } else {
        setRowBg(line, DEFAULT_BG);
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

  // Walk source-id chain from the edge's source-arg to the primary
  // (terminal source-id=nil) and pick the first :description we hit.
  // The primary owns the slot's canonical description; intermediate
  // renames may override it for clarity.
  let description = null;
  const sourceArgId = edge.data('sourceArgId');
  if (sourceArgId && lookups && lookups.argMap) {
    let cur = lookups.argMap.get(sourceArgId);
    description = cur && cur.description;
    while (!description && cur && cur['source-id']) {
      cur = lookups.argMap.get(cur['source-id']);
      description = cur && cur.description;
    }
  }

  const overlay = document.createElement('div');
  overlay.className = 'edge-label-overlay';
  overlay.dataset.edgeId = edge.id();
  Object.assign(overlay.style, {
    position: 'absolute',
    pointerEvents: 'auto',
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

  const labelSpan = document.createElement('span');
  labelSpan.textContent = label;
  overlay.appendChild(labelSpan);

  if (description) {
    const desc = createDescriptionBadge(description);
    if (desc) overlay.appendChild(desc);
  }

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
