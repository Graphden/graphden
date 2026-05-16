// Editor Overlays - HTML overlays for nodes (ancestor list, drag handles)
// Depends on: editor-state.js, editor-data.js, editor-tooltips.js,
//             editor-icons.js, editor-drag.js.
// Tooltip singletons, action-icon helpers, and the drag handle have
// moved into their own modules — see the corresponding `editor-*.js`
// files for the implementations referenced below.

// ============================================================================
// OVERLAY CREATION
// ============================================================================

// Wire the standard preview-trio handlers (mouseenter + mousemove fire
// `triggerPreview`, mouseleave clears + restores) onto a row element.
// Used by every overlay row that exposes hover-driven expansion.
function attachPreviewHandlers(el, triggerPreview, onPreviewLeave, restoreStyles) {
  el.addEventListener('mouseenter', triggerPreview);
  el.addEventListener('mousemove', triggerPreview);
  el.addEventListener('mouseleave', () => { onPreviewLeave(); restoreStyles(); });
}

// Walk a fn-card's incoming cytoscape edges and return the SINGLE
// editable arg if (a) there's exactly one such arg and (b) the
// owning fn is in the immediate implementation closure of the
// nav-root + the user is signed in. Returns null otherwise. Used to
// decide whether the value-fn card's depth-0 row should surface
// per-binding actions (× delete / ✎ change) — multi-incoming and
// uneditable cards skip the affordance to keep the row clean.
function _singleEditableIncomingArg(nodeId) {
  if (!cy || typeof argRowFromNode !== 'function') return null;
  const cyNode = cy.getElementById(nodeId);
  if (!cyNode?.length) return null;
  const editable = [];
  cyNode.incomers('edge').forEach((edge) => {
    const arg = argRowFromNode(edge.data());
    if (!arg) return;
    const inImpl = implementationFnIds?.has(arg['fn-id']);
    const signedIn = typeof isAuthenticated === 'function' && isAuthenticated();
    if (inImpl && signedIn) editable.push(arg);
  });
  return editable.length === 1 ? editable[0] : null;
}

// `+` Add-MI-parent button — pinned on the parent row's right edge
// when the parent set is editable. Two states:
//   - Active: tooltip "Add another parent (MI)" / click → fn-picker
//     filtered to candidates that share a base-fn AND set args the
//     existing parent set hasn't covered.
//   - Disabled: tooltip "<reason>" naming WHY no candidate is
//     available so the user understands the affordance isn't broken.
// `pinSlot` lets the caller stack this against neighbours
// (single-parent uses slot 3; MI uses an inline-flex sibling cell).
function makeAddMIParentButton(cardFnEntity, onEnter, pinSlot) {
  if (typeof createPinnedIconButton !== 'function') return null;
  if (typeof addMIParentInline !== 'function') return null;
  const currentParents = cardFnEntity['parent-ids'] || [];
  let candidateCount = 0;
  let firstReason = null;
  if (typeof compatibleMIParentInfo === 'function') {
    const info = compatibleMIParentInfo(cardFnEntity.id, currentParents);
    candidateCount = info.candidateIds.size;
    if (candidateCount === 0) {
      const allReasons = Object.values(info.rejected || {});
      const counts = {};
      for (const r of allReasons) counts[r] = (counts[r] || 0) + 1;
      let topReason = null, topCount = 0;
      for (const [r, c] of Object.entries(counts)) {
        if (c > topCount) { topReason = r; topCount = c; }
      }
      firstReason = topReason || 'no compatible MI parent in the registry';
    }
  }
  const btn = createPinnedIconButton({
    glyph: '+',
    title: candidateCount > 0
      ? 'Add another parent (multi-inheritance)'
      : ('No compatible MI parent — ' + firstReason),
    inline: true,
    onEnter,
    onClick: candidateCount > 0
      ? (anchor) => addMIParentInline(cardFnEntity, anchor)
      : null
  });
  if (!btn) return null;
  if (candidateCount === 0) {
    btn.disabled = true;
    btn.classList.add('pinned-icon-btn-disabled');
    btn.style.cursor = 'help';
  }
  return btn;
}

// Should this fn-row carry a left-pinned `ns` namespace badge?
// Anonymous / local fns have no addressable namespace, so the badge
// would always read "(root)" with no actionable click — skip them.
function rowWantsNamespaceBadge(rowFn) {
  return !!(rowFn?.name);
}

// Attach the left-pinned namespace badge to a fn-row. The badge is
// `attachNamespaceBadge` lived here in earlier iterations to pin a
// `ns` badge inside each fn-row. Removed when the row-actions popover
// (editor-row-actions.js) absorbed the ns badge alongside the other
// per-row affordances; row-bodies now show only the fn name.
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

// --- Paint state factory used by createFnOverlay ----------------------------

// Bundles the four constants, the row-bg helper, the highlight predicate,
// the MI-parent bg picker, and the three painters that the visibleLevels
// loop and the use-site header use to colour rows. The factory closes
// over the per-call `linesByDepth` Map (populated by the loop body) and
// the level / depth context, so callers destructure and use the returned
// helpers as if they were local declarations.
//
// Visual model (full rationale in createFnOverlay's body comment):
//   Nav-root / local-non-root: depth 0 is the "root header" (black bg,
//     white text). Non-clickable levels below it inherit that black-block
//     treatment until a clickable level breaks the chain.
//   Named-non-root: a separate empty "use-site" row IS the root header.
//   Within a group: same bg, no horizontal separator.
//   MI: each parent's cell is independently styled.
function buildFnPaintState({ linesByDepth, visibleLevels, fullDepth, partialFns }) {
  const ROOT_BG = 'var(--card-header-bg)';
  const ROOT_FG = 'var(--card-header-fg)';
  const HIGHLIGHT_BG = 'var(--card-row-highlight)';
  // Default non-root row bg — matches the card body so the overlay
  // container (header-coloured) can't bleed through sub-pixel gaps.
  const DEFAULT_BG = 'var(--card-bg)';

  // Sub-pixel-gap mitigation: a 1px box-shadow with the row's own colour
  // fills any rounding slack between this row and the next. When two
  // adjacent rows share the same colour the gap reads as that colour
  // (i.e. invisible). Without this, the black overlay container shows
  // through the gap between two white rows (and vice-versa).
  const setRowBg = (el, bg) => {
    el.style.background = bg;
    el.style.boxShadow = bg ? `0 1px 0 0 ${bg}` : '';
  };

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

  // Determine the visual bg for an MI parent given the current spec.
  const miFnBg = (miFn, miDepth, isRoot, sFull, sPartial) => {
    const fnInRootBlock = isRoot && !miFn.isClickable;
    const highlighted = fnIsHighlighted(miDepth, miFn.fnId, sFull, sPartial);
    if (fnInRootBlock) return { bg: ROOT_BG, fg: ROOT_FG };
    if (highlighted)   return { bg: HIGHLIGHT_BG, fg: '' };
    return { bg: DEFAULT_BG, fg: '' };
  };

  // Apply styles for a given spec. Handles both root-block styling and
  // expansion highlighting.
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

  return {
    ROOT_BG, ROOT_FG, HIGHLIGHT_BG, DEFAULT_BG,
    setRowBg, fnIsHighlighted,
    paintWithSpec, applyPreviewStyle, restoreStyles,
  };
}

// --- Per-row render helpers used by createFnOverlay's visibleLevels loop ---

// Use-site header for named non-root nodes. The use-site is the position
// this fn occupies in the parent's expansion — it has no name of its own,
// so we render an empty black row to mirror what local fns get for free
// (their depth-0 row is already empty because the fn itself is anonymous).
// Click collapses every expansion currently on this node; cursor reflects
// whether there's anything to collapse.
//
// Returns the created <div> (or null) so the caller can merge it with
// the depth-0 ancestor row when the header has nothing of its own to do
// — in that case the band acts as a multi-line top of the depth-0 row
// (single hover, single click target), per the "merge non-clickable rows"
// UI rule.
function appendUseSiteHeader(overlay, ctx) {
  const { nodeId, originalFnId, isNavRoot, isLocalFn,
          paint: { ROOT_BG, ROOT_FG, setRowBg, applyPreviewStyle, restoreStyles } } = ctx;
  if (isNavRoot || isLocalFn) return null;
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
    WebkitUserSelect: 'none',
    position: 'relative'
  });
  setRowBg(useSite, ROOT_BG);
  // The use-site header carries no buttons — it's a visual marker
  // that this card is being used as a value somewhere in the
  // nav-root's expansion (matched bg colour ties it to the parent's
  // root-block). Per-binding actions (× delete value / ✎ change
  // value) live on the depth-0 fn-name row inside `renderSingleFnRow`
  // so they sit next to the entity they affect, not on a separate
  // strip above it.
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
    applyHoverSpec(nodeId, 0, originalFnId, [originalFnId]);
  };
  attachPreviewHandlers(useSite, triggerUseSitePreview, onPreviewLeave, restoreStyles);
  overlay.appendChild(useSite);
  return useSite;
}

// Column-below-MI row — non-clickable level under MI parents, rendered
// as flex columns matching the MI parents above so the vertical
// borders continue downward and per-column bg inherits the MI parent's
// visual state. The fn name floats over the columns via an absolutely
// positioned text overlay. Populates linesByDepth with the column
// divs + text overlay for paintWithSpec.
function renderColumnBelowMiRow(line, levelInfo, miLevelAbove, ctx) {
  const { nodeId, isNavRoot, fullDepth, partialFns, linesByDepth,
          paint: { applyPreviewStyle, restoreStyles } } = ctx;
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
  const colShowOpen = !!colFn.name && !(isNavRoot && levelInfo.depth === 0);
  // Right inset reserves the single slot for the more-actions trigger
  // pinned at slot r-1. Per-row affordances (description, ns,
  // open-in-new-tab) live in the popover the trigger opens, so the
  // text only has to clear that one icon.
  const colRightInset = '24px';
  const colLeftInset = '8px';
  const textOverlay = document.createElement('span');
  textOverlay.textContent = displayLabel(colFn.name);
  Object.assign(textOverlay.style, {
    position: 'absolute',
    left: colLeftInset,
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
  const colFnEntity = lookups?.fnMap?.get(colFn.fnId) || null;
  // Per-row affordances (ns / i / ↗) move into the row-actions
  // popover anchored to the more-actions trigger. The trigger sits
  // on top of the column divs so the user can hit it across the full
  // row width.
  const buildColPopoverContent = (host) => {
    if (colFnEntity && rowWantsNamespaceBadge(colFn)
        && typeof createNamespaceBadge === 'function') {
      const nsPath = (typeof getFnNamespace === 'function')
                     ? getFnNamespace(colFnEntity) : null;
      const canEdit = typeof isAuthenticated === 'function' && isAuthenticated()
                   && typeof isFnEditable === 'function' && isFnEditable(colFn.fnId)
                   && typeof enterNamespaceMoveEditMode === 'function';
      const nsBadge = createNamespaceBadge(nsPath, {
        inline: true,
        onClick: canEdit
                 ? (anchor) => enterNamespaceMoveEditMode(colFnEntity, anchor)
                 : null
      });
      if (nsBadge) host.appendChild(nsBadge);
    }
    const descBadge = createDescriptionBadge(colFn.description, {
      name: colFn.name,
      namespace: colFnEntity ? (typeof getFnNamespace === 'function'
                                 ? getFnNamespace(colFnEntity) : null) : null,
      entityType: 'fn',
      entityId: colFn.fnId
    });
    if (descBadge) host.appendChild(descBadge);
    if (colShowOpen && colFnEntity) {
      const openBtn = createOpenInNewTabButton(colFnEntity, {});
      if (openBtn) host.appendChild(openBtn);
    }
  };
  if (typeof createMoreActionsTrigger === 'function') {
    const trigger = createMoreActionsTrigger({
      onEnter: colClearPreview,
      buildContent: buildColPopoverContent
    });
    trigger.style.zIndex = '2';
    line.appendChild(trigger);
  }
  bindFullNameHover(line, textOverlay, colFn.name);
  // Store column info for paintWithSpec
  linesByDepth.set(levelInfo.depth, { line, spansByFnId: null, levelInfo, colDivs, textOverlay });

  // Column-below-MI click/hover: when expanding, use the group's max
  // depth (cascade through MI + this level). When collapsing (already
  // expanded), collapse the WHOLE group by targeting the MI level's
  // depth — so toggle goes to miDepth - 1, removing MI too.
  // The chevron in slot l-1 is the click target; the row body is a
  // passive surface, so action icons can use the hover-to-show pattern
  // without competing with a row-wide expansion handler.
  const fnIdForLine = levelInfo.fns[0].fnId;
  const allFnsAtDepth = [fnIdForLine];
  const expandDepth = levelInfo.groupMaxDepth;
  const collapseDepth = miLevelAbove.depth;  // collapse whole group
  const getTargetDepth = () => expandDepth <= fullDepth ? collapseDepth : expandDepth;
  // Whole-line click cascades expansion to groupMaxDepth (so empty
  // grouped levels expand together); hover previews the same.
  line.style.cursor = 'pointer';
  const onMouseDown = (e) => {
    e.stopPropagation();
    e.preventDefault();
    const currentFull = expansionState.get(nodeId)?.fullDepth || 0;
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
  attachPreviewHandlers(line, triggerPreview, onPreviewLeave, restoreStyles);
}

// Multi-inheritance row — each parent becomes a flex `<span>` cell with
// per-fn click/hover. Returns the spansByFnId Map so the caller can
// hand it to `paintWithSpec` via linesByDepth.
function renderMiRow(line, levelInfo, idx, ctx) {
  const { nodeId, isNavRoot, fullDepth, partialFns, visibleLevels,
          paint: { ROOT_BG, ROOT_FG, HIGHLIGHT_BG, DEFAULT_BG,
                   setRowBg, fnIsHighlighted,
                   applyPreviewStyle, restoreStyles } } = ctx;
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

  const spansByFnId = new Map();
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
    span.textContent = displayLabel(f.name);
    const miShowOpen = !!f.name && !(isNavRoot && levelInfo.depth === 0);
    // Right inset reserves the single more-actions trigger slot;
    // per-cell affordances live in the popover it opens, not in the
    // cell. Left side is just the small breathing room around the
    // name.
    span.style.padding = '4px 24px 4px 8px';
    span.style.flex = '1 1 0';
    span.style.minWidth = '0';
    span.style.textAlign = 'left';
    span.style.whiteSpace = 'nowrap';
    span.style.overflow = 'hidden';
    span.style.textOverflow = 'ellipsis';
    span.style.position = 'relative';
    bindFullNameHover(span, span, f.name);
    const miClearPreview = () => { onPreviewLeave(); clearPreview(nodeId); restoreStyles(); };
    const cellFnEntity = lookups?.fnMap?.get(f.fnId) || null;
    const cardFnEntity = lookups?.fnMap?.get(ctx.originalFnId) || null;
    const miEditable = levelInfo.depth === 1
      && typeof isAuthenticated === 'function' && isAuthenticated()
      && implementationFnIds?.has(ctx.originalFnId);
    const buildCellPopoverContent = (host) => {
      // ns badge for THIS MI parent (each cell is its own fn).
      if (cellFnEntity && rowWantsNamespaceBadge(f)
          && typeof createNamespaceBadge === 'function') {
        const nsPath = (typeof getFnNamespace === 'function')
                       ? getFnNamespace(cellFnEntity) : null;
        const canEdit = typeof isAuthenticated === 'function' && isAuthenticated()
                     && typeof isFnEditable === 'function' && isFnEditable(f.fnId)
                     && typeof enterNamespaceMoveEditMode === 'function';
        const nsBadge = createNamespaceBadge(nsPath, {
          inline: true,
          onClick: canEdit
                   ? (anchor) => enterNamespaceMoveEditMode(cellFnEntity, anchor)
                   : null
        });
        if (nsBadge) host.appendChild(nsBadge);
      }
      const descBadge = createDescriptionBadge(f.description, {
        name: f.name,
        namespace: cellFnEntity ? (typeof getFnNamespace === 'function'
                                    ? getFnNamespace(cellFnEntity) : null) : null,
        entityType: 'fn',
        entityId: f.fnId
      });
      if (descBadge) host.appendChild(descBadge);
      if (miShowOpen && cellFnEntity) {
        const openBtn = createOpenInNewTabButton(cellFnEntity, {});
        if (openBtn) host.appendChild(openBtn);
      }
      // `×` remove-this-MI-parent + `+` add-MI-parent — both are
      // card-level edits (modify the cardFnEntity's parent set), so
      // they're available from any MI cell's popover.
      if (miEditable && cardFnEntity && typeof removeParentInline === 'function') {
        host.appendChild(createPinnedIconButton({
          glyph: '×',
          title: 'Remove this parent',
          inline: true,
          onClick: () => removeParentInline(cardFnEntity, f.fnId)
        }));
      }
      if (miEditable && cardFnEntity) {
        const addBtn = makeAddMIParentButton(cardFnEntity, null, 1);
        if (addBtn) host.appendChild(addBtn);
      }
    };
    if (typeof createMoreActionsTrigger === 'function') {
      const trigger = createMoreActionsTrigger({
        onEnter: miClearPreview,
        buildContent: buildCellPopoverContent
      });
      span.appendChild(trigger);
    }
    if (i < levelInfo.fns.length - 1) {
      span.style.borderRight = '1px solid var(--light-border)';
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
    span.style.cursor = 'pointer';
    // MI per-fn click — cascades through any non-clickable followers
    // (e.g. the column-below-MI text below this MI row).
    const onMouseDown = (e) => {
      e.stopPropagation();
      e.preventDefault();
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
      const hoverDepth = (preview && preview.fullDepth === miEffectiveDepth)
                       ? miEffectiveDepth : levelInfo.depth;
      applyHoverSpec(nodeId, hoverDepth, f.fnId, allFnsAtDepth);
    };
    attachPreviewHandlers(span, triggerSpanPreview, onPreviewLeave, restoreStyles);
    line.appendChild(span);
  });
  // Card-level `+` add-MI-parent moved into each MI cell's popover
  // above (it modifies the same cardFnEntity from any cell), so
  // there's no separate trailing cell on the row anymore — that
  // inline-flex column was the last bit of "actions inside the card"
  // and it has now followed the rest into the row-actions popover.
  return spansByFnId;
}

// Single-fn ancestor row — non-MI line whose whole rectangle is the
// click target. Cascades expansion to `groupMaxDepth` so empty grouped
// followers expand together.
function renderSingleFnRow(line, levelInfo, ctx) {
  const { nodeId, isNavRoot, fullDepth, partialFns,
          paint: { ROOT_BG, ROOT_FG, HIGHLIGHT_BG, DEFAULT_BG,
                   setRowBg, fnIsHighlighted,
                   applyPreviewStyle, restoreStyles } } = ctx;
  // Non-MI line: padding on the line itself.
  // Reserve symmetric horizontal room when right-pinned controls
  // are present, so wrapped names stay clear of them and the
  // visual centering point doesn't shift.
  const lineFn = levelInfo.fns[0];
  const lineShowOpen = !!lineFn.name && !(isNavRoot && levelInfo.depth === 0);
  // Root row pins TWO extra icons (Extend +, Delete 🗑) when the user
  // is signed in and the fn is editable, so it claims four slots
  // instead of two: i + ✎ + + + 🗑. Reserve enough right-padding for
  // four 18-px steps. Left-padding clears the namespace badge that
  // sits at icon-pin-r-1 on every named fn row.
  const lineIsRoot = isNavRoot && levelInfo.depth === 0;
  const lineSignedIn = typeof isAuthenticated === 'function' && isAuthenticated();
  const lineEditable = typeof isFnEditable === 'function' && isFnEditable(lineFn.fnId);
  // The "this fn is in use elsewhere, detach first" reason — shown on
  // click of a disabled action icon (✎ / + / ✕ / ns) so the user can
  // still see the affordances exist and discover WHY they're blocked.
  const lineEditBlockReason = (!lineEditable && typeof getFnEditBlockReason === 'function')
                              ? getFnEditBlockReason(lineFn.fnId) : null;
  const rootAffordancesVisible = lineIsRoot && lineSignedIn;
  // Depth-0 row of a non-nav-root card with a single editable
  // incoming binding pins per-binding action icons (× delete value
  // and ✎ change value) on the right edge — same number of slots as
  // the nav-root's own action row.
  const useSiteArg = (!lineIsRoot && levelInfo.depth === 0)
                     ? _singleEditableIncomingArg(nodeId) : null;
  // Parent-edit row — depth-1 of any editable card (nav-root OR a
  // value-fn card the user can reach via ref). The check is
  // intentionally looser than `isFnEditable`: re-parenting a fn
  // doesn't break references (the fn-id stays the same, only its
  // inheritance does), so requiring zero refs would lock parent
  // edits behind navigation just to add an MI parent.
  const parentEditAllowed = levelInfo.depth === 1
    && lineSignedIn
    && lookups?.fnMap
    && implementationFnIds?.has(ctx.originalFnId);
  // Right padding reserves the single slot for the more-actions
  // trigger (`⋯`) — every per-row affordance now lives in the popover
  // it opens, OUTSIDE the card silhouette. Left padding is just the
  // small breathing room around the name.
  const rightPad = 24;
  const leftPad = 8;
  line.style.padding = '4px ' + rightPad + 'px 4px ' + leftPad + 'px';
  line.style.textAlign = 'left';
  line.style.whiteSpace = 'nowrap';
  line.style.overflow = 'hidden';
  line.style.textOverflow = 'ellipsis';
  line.style.position = 'relative';
  // Whole-line click cascading to groupMaxDepth (so empty grouped
  // levels expand together).
  line.style.cursor = 'pointer';
  line.textContent = displayLabel(lineFn.name);
  const lineClearPreview = () => { onPreviewLeave(); clearPreview(nodeId); restoreStyles(); };
  const lineFnEntity = lookups?.fnMap?.get(lineFn.fnId) || null;
  const cardFnEntity = lookups?.fnMap?.get(ctx.originalFnId) || null;

  // All the per-row affordances now live in the row-actions popover
  // (see editor-row-actions.js), reachable via the `⋯` trigger pinned
  // at slot r-1. The card body stays minimal — fn name only, with
  // hover-driven expansion as before. `buildPopoverContent` defers
  // building the icons until the popover actually opens, so unhovered
  // rows pay no DOM cost.
  const buildPopoverContent = (host) => {
    // ns badge — first slot in the popover so it reads "this fn lives
    // in <ns>, here's everything you can do with it".
    const fnEntity = lineFnEntity;
    if (fnEntity && rowWantsNamespaceBadge(lineFn)
        && typeof createNamespaceBadge === 'function') {
      const nsPath = (typeof getFnNamespace === 'function')
                     ? getFnNamespace(fnEntity) : null;
      const signedIn = typeof isAuthenticated === 'function' && isAuthenticated();
      const editorReady = typeof enterNamespaceMoveEditMode === 'function';
      const nsBadge = createNamespaceBadge(nsPath, {
        inline: true,
        onClick: (signedIn && lineEditable && editorReady)
                 ? (anchor) => enterNamespaceMoveEditMode(fnEntity, anchor)
                 : null,
        // When the fn is in-use we still want the badge to feel like an
        // affordance (hover → ✎ swap) but click should reveal the reason
        // instead of opening the namespace picker.
        disabledReason: (signedIn && !lineEditable) ? lineEditBlockReason : null
      });
      if (nsBadge) host.appendChild(nsBadge);
    }
    // Description badge — always; the dedicated tooltip handles empty
    // description (turns into "click to add").
    const descBadge = createDescriptionBadge(lineFn.description, {
      name: lineFn.name,
      namespace: fnEntity ? (typeof getFnNamespace === 'function'
                              ? getFnNamespace(fnEntity) : null) : null,
      entityType: 'fn',
      entityId: lineFn.fnId
    });
    if (descBadge) host.appendChild(descBadge);
    // ↗ open-in-new-tab — when the row's fn has a globally-resolvable
    // name AND we're not already viewing it (root row).
    if (lineShowOpen && fnEntity) {
      const openBtn = createOpenInNewTabButton(fnEntity, {});
      if (openBtn) host.appendChild(openBtn);
    }
    // Branch-specific buttons. Same handlers as the inline version
    // used to wire — only the host changes.
    if (parentEditAllowed && cardFnEntity) {
      // `×` remove-this-parent.
      host.appendChild(createPinnedIconButton({
        glyph: '×',
        title: 'Remove this parent',
        inline: true,
        onClick: () => {
          if (typeof removeParentInline === 'function') {
            removeParentInline(cardFnEntity, lineFn.fnId);
          }
        }
      }));
      // `+` add MI parent.
      const addBtn = makeAddMIParentButton(cardFnEntity, null, 1);
      if (addBtn) host.appendChild(addBtn);
    } else if (useSiteArg) {
      // `×` remove this binding (slot reverts to free-arg).
      host.appendChild(createPinnedIconButton({
        glyph: '×',
        title: 'Remove this value (slot reverts to free-arg)',
        inline: true,
        onClick: () => {
          if (typeof deleteUseSiteBinding === 'function') {
            deleteUseSiteBinding(useSiteArg);
          }
        }
      }));
      // `✎` change value.
      if (typeof enterFreeArgBindEditMode === 'function') {
        host.appendChild(createPinnedIconButton({
          glyph: '✎',
          title: 'Change value (pick another fn / set a literal)',
          inline: true,
          onClick: (anchor) => enterFreeArgBindEditMode(useSiteArg, anchor)
        }));
      }
    } else if (rootAffordancesVisible && lineFnEntity) {
      // Root row's per-fn actions: rename, extend, delete. Always
      // rendered when the user is signed in — when the fn can't be
      // edited (it's referenced elsewhere), each icon goes into a
      // disabled-with-reason state so a click reveals WHY instead of
      // the user wondering whether the affordance just isn't there.
      const editBtn = createEditPencilButton({
        onClick: (anchor) => enterFnRenameEditMode(lineFnEntity, anchor),
        disabledReason: lineEditBlockReason
      });
      if (editBtn) host.appendChild(editBtn);
      if (typeof enterExtendEditMode === 'function') {
        host.appendChild(createPinnedIconButton({
          glyph: '+',
          title: 'Extend (create a child fn with this as :parent)',
          inline: true,
          onClick: (anchor) => enterExtendEditMode(lineFnEntity, anchor),
          disabledReason: lineEditBlockReason
        }));
      }
      host.appendChild(createPinnedIconButton({
        glyph: '✕',
        title: 'Delete this fn',
        inline: true,
        danger: true,
        disabledReason: lineEditBlockReason,
        onClick: async () => {
          const display = (typeof getQualifiedFnName === 'function')
                          ? getQualifiedFnName(lineFnEntity)
                          : (lineFnEntity.name || 'this fn');
          if (!confirm('Delete fn "' + display + '"? '
                       + 'Bindings that reference it will fail to load.')) return;
          const opKey = 'delete-fn:' + lineFnEntity.id;
          if (typeof isOpInflight === 'function' && isOpInflight(opKey)) return;
          const work = async () => {
            try {
              const r = await deleteEntity('fn', lineFnEntity.id);
              if (r && r.status >= 200 && r.status < 300) {
                try { window.location.hash = ''; } catch (_) {}
                if (typeof initGraph === 'function') await initGraph();
              } else {
                const text = r ? await r.text().catch(() => '') : '';
                alert('Delete failed (' + (r?.status) + '): '
                      + text.replace(/<[^>]+>/g, '').trim().slice(0, 200));
              }
            } catch (err) {
              alert('Network error: ' + err.message);
            }
          };
          if (typeof withBusy === 'function') {
            await withBusy(opKey, 'Deleting ' + display + '…', work);
          } else {
            await work();
          }
        }
      }));
    }
  };
  if (typeof createMoreActionsTrigger === 'function') {
    const trigger = createMoreActionsTrigger({
      onEnter: lineClearPreview,
      buildContent: buildPopoverContent
    });
    line.appendChild(trigger);
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
  attachPreviewHandlers(line, triggerLinePreview, onPreviewLeave, restoreStyles);
}

// --- Strip helpers used by createFnOverlay ----------------------------------

// Optional-but-unbound args (e.g. :get.default when no default was supplied)
// render as a thin, muted strip instead of their own placeholder nodes —
// they carry sane fallbacks so they're not part of the function's interface,
// just a nicety the caller may or may not care about.
function appendOptionalArgsStrip(overlay, optionalArgs) {
  if (!Array.isArray(optionalArgs) || !optionalArgs.length) return;
  const strip = document.createElement('div');
  Object.assign(strip.style, {
    padding: '2px 8px',
    color: 'var(--light-fg)',
    fontSize: '10px',
    fontStyle: 'italic',
    borderTop: '1px dashed var(--input-border)',
    background: 'var(--sidebar-bg)',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis'
  });
  strip.title = 'Optional args (unset, using defaults): ' + optionalArgs.join(', ');
  strip.textContent = optionalArgs.map(n => '?' + n).join(' ');
  overlay.appendChild(strip);
}

// Per-fn metadata strips at the bottom of the overlay: return-type,
// effects (with drift visualisation), edit-parents, namespace. All four
// share `cardFnEntity` and the `rtEditable` predicate, so they live in
// one helper instead of four call-site copies of the same gate.
function appendFnMetadataStrips(overlay, originalFnId, isNavRoot) {
  const cardFnEntity = lookups?.fnMap?.get(originalFnId);
  if (!cardFnEntity) return;
  const rt = cardFnEntity['return-type'];
  const rtEditable = isNavRoot
                  && (typeof isFnEditable === 'function' && isFnEditable(originalFnId))
                  && (typeof isAuthenticated === 'function' && isAuthenticated());

  // --- return-type strip ---
  // Two display modes:
  //   - Non-root cards (expanded ancestors): show only when a type is
  //     set, read-only — informational, doesn't add visual noise to
  //     fns the user can't edit from here anyway.
  //   - Root card: always show; clickable when fn is editable+authed
  //     so the user can SET a return-type even when the fn currently
  //     has none ("→ (none)" placeholder).
  // Pull the rich computed return-type from /api/types — for fn-defs
  // whose `:return-type` column is null, this is the only place the
  // computed shape lives client-side.
  let displayRich = null;
  let richReturn = null;
  if (cardFnEntity.name && typeof richTypes === 'object' && richTypes
      && typeof formatTypeHint === 'function') {
    const re = richTypes[cardFnEntity.name];
    if (re && re.return != null) {
      richReturn = re.return;
      displayRich = formatTypeHint(re.return);
    }
  }
  // Prefer the original NAMED return-type over the unfolded
  // structural form. For `web-server :return-type :http-server-handle`
  // the structural unfold reads `→ () → null` — readable as either
  // "this fn takes no args, returns null" (wrong) or "returns a
  // 0-arg callable returning null" (right but takes thought). The
  // alias name "http-server-handle" carries the same information
  // more compactly, so when the (possibly inherited)
  // `return-type-fn-id` resolves to a type-row with a real name we
  // use that instead. Falls back to the structural form when no
  // named alias exists (e.g. inline `[:fn …]` declarations).
  //
  // Walks the parent chain because composed fn-defs INHERIT
  // `:return-type` from their parent — `web-server`'s row carries
  // null, the value lives on `:http-server`'s row.
  if (lookups?.fnMap) {
    const PRIMITIVES = new Set(['null', 'uuid', 'text', 'int', 'bool',
                                 'numeric', 'timestamptz', 'jsonb',
                                 'bytes', 'any', 'fn', 'sequence',
                                 'keyword', 'float']);
    const visited = new Set();
    const queue = [cardFnEntity];
    let inheritedRtFnId = null;
    while (queue.length && !inheritedRtFnId) {
      const f = queue.shift();
      if (!f || visited.has(f.id)) continue;
      visited.add(f.id);
      if (f['return-type-fn-id']) {
        inheritedRtFnId = f['return-type-fn-id'];
        break;
      }
      for (const pid of (f['parent-ids'] || [])) {
        const pf = lookups.fnMap.get(pid);
        if (pf) queue.push(pf);
      }
    }
    if (inheritedRtFnId) {
      const rtFn = lookups.fnMap.get(inheritedRtFnId);
      if (rtFn?.name && typeof rtFn.name === 'string'
          && !PRIMITIVES.has(rtFn.name)) {
        displayRich = ':' + rtFn.name;
      }
    }
  }

  if (rt || rtEditable || displayRich) {
    const strip = document.createElement('div');
    strip.className = 'return-type-strip';
    const displayText = displayRich || rt;
    strip.textContent = displayText ? ('→ ' + displayText) : '→ (none)';
    strip.title = rt
      ? ('Return type: ' + rt + (displayRich && displayRich !== rt ? ' (computed: ' + displayRich + ')' : ''))
      : 'No return type set';
    if (rtEditable) {
      strip.classList.add('return-type-strip-editable');
      strip.title = (displayRich && displayRich !== rt
                     ? 'Computed: ' + displayRich + ' — click to change return type'
                     : 'Click to change return type');
      strip.addEventListener('click', (e) => {
        e.stopPropagation();
        enterFnReturnTypeEditMode(cardFnEntity, strip);
      });
    }
    overlay.appendChild(strip);
  }

  // --- effects strip ---
  // Small per-category badges (db / env / io / network / time /
  // effect). Reads richTypes[name].effects when available. Pure fns
  // get no row at all (no clutter for the 80% case). Each badge is
  // colour-coded and carries a hover-title with the full category name.
  //
  // When the fn-def also declares `:expects-effects`, the strip
  // shows declared/computed drift visually:
  //   - computed AND declared    → solid chip (normal)
  //   - computed NOT declared    → solid chip with red outline
  //                                (DRIFT — author should declare it)
  //   - declared NOT computed    → outlined ghost chip
  //                                (over-declared, harmless)
  if (cardFnEntity.name && typeof richTypes === 'object' && richTypes) {
    const re = richTypes[cardFnEntity.name];
    const computed = (re && Array.isArray(re.effects)) ? re.effects : [];
    // Prefer the live DB value (updated by UI edits) over the
    // richTypes snapshot which is rebuilt only at server start.
    const dbDeclared = Array.isArray(cardFnEntity['expects-effects'])
      ? cardFnEntity['expects-effects'] : null;
    const declared = dbDeclared
      || ((re && Array.isArray(re['expects-effects'])) ? re['expects-effects'] : null);
    const all = new Set([...computed, ...(declared || [])]);
    // The :expects-effects edit affordance is gated more loosely than
    // most footer-strip controls — the field is a documentation/drift
    // annotation, not a structural change, so we don't need to block
    // when the fn already has children (the `isFnEditable` gate is for
    // deletion). Auth + nav-root is enough.
    const effectsEditable = isNavRoot
                         && (typeof isAuthenticated === 'function' && isAuthenticated());
    if (all.size > 0 || (effectsEditable && computed.length === 0)) {
      const effRow = document.createElement('div');
      effRow.className = 'effects-strip';
      const titleParts = [];
      if (computed.length) titleParts.push('Effects: ' + computed.join(', '));
      if (declared)        titleParts.push('Declared: ' + declared.join(', '));
      const drift = computed.filter(e => declared && declared.indexOf(e) < 0);
      const overDeclared = (declared || []).filter(e => computed.indexOf(e) < 0);
      if (drift.length)         titleParts.push('Drift (undeclared): ' + drift.join(', '));
      if (overDeclared.length)  titleParts.push('Over-declared: '       + overDeclared.join(', '));
      effRow.title = titleParts.join('\n');
      Array.from(all).sort().forEach((eff) => {
        const isComputed = computed.indexOf(eff) >= 0;
        const isDeclared = declared && declared.indexOf(eff) >= 0;
        const chip = document.createElement('button');
        chip.type = 'button';
        let cls = 'effects-chip effects-chip-' + eff;
        if (!isComputed && isDeclared) cls += ' effects-chip-ghost';   // declared only
        if ( isComputed && declared && !isDeclared) cls += ' effects-chip-drift'; // unexpected
        chip.className = cls;
        chip.textContent = eff;
        chip.title = isComputed
          ? (isDeclared ? 'Effect: ' + eff + ' (declared & computed) — tap for details'
                        : 'Effect: ' + eff + ' (DRIFT — not in :expects-effects) — tap for details')
          : 'Effect: ' + eff + ' (declared but not computed) — tap for details';
        chip.setAttribute('aria-label', chip.title);
        chip.addEventListener('click', (e) => {
          e.stopPropagation();
          if (typeof showEffectExplainer === 'function') {
            showEffectExplainer({ effect: eff, anchorEl: chip });
          }
        });
        effRow.appendChild(chip);
      });
      // Inline "declare effects…" edit pencil — only for the
      // navigation-root card and only when the viewer can edit. On
      // pure fns with no contract yet, the strip otherwise wouldn't
      // exist; the all.size>0 gate above admits this case so the
      // pencil is always reachable.
      if (effectsEditable && typeof enterExpectsEffectsEditMode === 'function') {
        // Same `✎` glyph in both states (contract / no-contract) so
        // the affordance is one consistent thing the user can learn,
        // not two-text-vs-icon. The title carries the state-specific
        // hint for new users and screen readers.
        const editBtn = document.createElement('button');
        editBtn.type = 'button';
        editBtn.className = 'effects-strip-edit';
        editBtn.textContent = '✎';
        editBtn.title = declared
          ? 'Edit declared effect contract'
          : 'Declare an effect contract (drift checker compares declared vs computed)';
        editBtn.setAttribute('aria-label', editBtn.title);
        editBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          enterExpectsEffectsEditMode(cardFnEntity, editBtn, declared);
        });
        effRow.appendChild(editBtn);
      }
      overlay.appendChild(effRow);
    }
  }

  const appendClickStrip = (label, title, onClick) => {
    const strip = document.createElement('div');
    strip.className = 'reparent-strip';
    strip.textContent = label;
    strip.title = title;
    strip.addEventListener('click', (e) => {
      e.stopPropagation();
      onClick(strip);
    });
    overlay.appendChild(strip);
  };

  // --- edit-type-row strip ---
  // Type-rows have no parents and no callable signature — their value
  // lives in the structural fields (`base-fn-id`, `element-fn-id`,
  // `constraint`, or fn-slots for records). The fn-action toolbar at
  // the bottom of the card handles rename / delete / namespace, but
  // none of those touch the type's *definition*. Surface a single
  // strip whose click reopens the create-type form pre-populated with
  // the current values — submit goes through PUT.
  //
  // Only the kinds whose definition fits in one form (no compound
  // delta against existing slots) get an editable affordance: record
  // edit is read-only-prefilled and only rename works. fn-types are
  // anonymous structural fn-rows attached via `slot.type-fn-id`; the
  // arg-chip popover handles their rename instead.
  if (isNavRoot && rtEditable && typeof openTypeEditForm === 'function') {
    const editableRoles = new Set(['refinement', 'union', 'variant', 'list',
                                    'record',
                                    ':refinement', ':union', ':variant', ':list',
                                    ':record']);
    const role = cardFnEntity.role;
    if (editableRoles.has(role)) {
      const strip = document.createElement('div');
      strip.className = 'edit-type-strip';
      strip.tabIndex = 0;
      strip.setAttribute('role', 'button');
      strip.textContent = 'edit this type…';
      strip.title = 'Open the type-edit form to change this type-row\'s definition';
      const handler = (e) => {
        e.stopPropagation();
        openTypeEditForm(originalFnId, strip);
      };
      strip.addEventListener('click', handler);
      strip.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          handler(e);
        }
      });
      overlay.appendChild(strip);
    }
  }

  // --- set-parent strip (no-parents case only) ---
  // When the fn HAS a parent, the depth-1 ancestor row already shows
  // it AND carries an inline ✎ pencil — no separate strip needed.
  // When there's no parent, there's no row to attach the pencil to,
  // so we keep a minimal "set parent…" affordance here.
  if (rtEditable && typeof enterReparentEditMode === 'function') {
    const pids = cardFnEntity['parent-ids'] || [];
    if (pids.length === 0) {
      appendClickStrip(
        'set parent…',
        'Click to assign a parent (the rest of the chain follows)',
        (strip) => enterReparentEditMode(cardFnEntity, strip));
    }
  }

  // (Namespace surface lives as a left-pinned `ns` badge on the
  // fn-name row — see `createNamespaceBadge` in editor-icons.js.
  // Removed the dedicated bottom strip: same payload duplicated in
  // two places turned the card into a noisy stack of labels.)
}

// Hover handlers attached after every other strip — kept here, NOT
// in createFnOverlay's body, because they only need `overlay` and
// `nodeId`. Two concerns:
//
//   - Light up the entire outgoing edge bundle when the overlay itself
//     is hovered (not a specific edge label). Mouseenter / mouseleave
//     instead of pointerover so the highlight follows the rectangle
//     precisely without leaking through child overlays.
//   - Clear the layout preview state when the cursor leaves, unless
//     overlays are being rebuilt (rebuildingOverlays flag), the user
//     is dragging (isGrabbing), or the overlay was already detached.
function attachFnOverlayHoverHandlers(overlay, nodeId) {
  overlay.addEventListener('mouseenter', () => {
    if (!cy) return;
    const cyNode = cy.getElementById(nodeId);
    if (cyNode?.length) {
      cyNode.outgoers('edge').addClass('edge-hovered');
    }
  });
  overlay.addEventListener('mouseleave', () => {
    if (cy) cy.edges('.edge-hovered').removeClass('edge-hovered');
  });
  overlay.addEventListener('mouseleave', () => {
    if (!rebuildingOverlays && !isGrabbing && overlay.isConnected) {
      onPreviewLeave();
      clearPreview(nodeId);
    }
  });
}

// Phase 4: action toolbar for the selected (nav-root) fn.
//
// All mutating actions for a fn used to live behind hover-revealed
// icons scattered across the card (✎ rename pencil on the depth-0
// row, ✎ reparent pencil on the depth-1 ancestor row, ⓘ description
// badge with a sticky-on-click model, 🗑 delete buried in the
// sidebar). New users couldn't find them — especially on touch
// where hover doesn't fire. This toolbar surfaces every action in
// one persistent row, anchored to the bottom of the selected fn's
// overlay, with ≥44px touch targets.
//
// Visibility rules:
//   - Only on the nav-root card (the fn the user navigated to).
//   - Renders just the "Sign in to edit" CTA when the user isn't authed.
//     When authed, the bar drops entirely — every per-fn action now
//     lives next to its target (see comment block at the end of the
//     function). The old "Read-only — protected fn" banner is gone:
//     the ✎ / + / ✕ / ns icons themselves render in a disabled state
//     and reveal the in-use reason on click (see
//     `getFnEditBlockReason` + `applyIconDisabledReason`).
function appendFnActionToolbar(overlay, originalFnId, isNavRoot) {
  if (!isNavRoot) return;
  if (!lookups || !lookups.fnMap) return;
  const fn = lookups.fnMap.get(originalFnId);
  if (!fn) return;

  const authed = typeof isAuthenticated === 'function' && isAuthenticated();

  const bar = document.createElement('div');
  bar.className = 'fn-action-toolbar';
  bar.setAttribute('role', 'toolbar');
  bar.setAttribute('aria-label', 'Actions for ' + (fn.name || 'this fn'));

  function makeActionBtn({ label, glyph, onClick, danger }) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'fn-action-btn' + (danger ? ' fn-action-btn-danger' : '');
    btn.setAttribute('aria-label', label);
    btn.title = label;
    const g = document.createElement('span');
    g.className = 'fn-action-btn-glyph';
    g.setAttribute('aria-hidden', 'true');
    g.textContent = glyph;
    const t = document.createElement('span');
    t.className = 'fn-action-btn-label';
    t.textContent = label;
    btn.appendChild(g);
    btn.appendChild(t);
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      onClick(btn);
    });
    btn.addEventListener('mousedown', (e) => e.stopPropagation());
    btn.addEventListener('touchstart', (e) => e.stopPropagation(), { passive: true });
    return btn;
  }

  if (!authed) {
    // Read-only banner with a single CTA → opens the auth lock.
    const banner = document.createElement('span');
    banner.className = 'fn-action-toolbar-hint';
    banner.textContent = 'Sign in to edit';
    bar.appendChild(banner);
    const signIn = makeActionBtn({
      label: 'Sign in',
      glyph: '🔒',
      onClick: () => {
        const lock = document.getElementById('auth-lock-btn');
        if (lock) lock.click();
      },
    });
    bar.appendChild(signIn);
    overlay.appendChild(bar);
    return;
  }

  // Editable case — every per-fn action now lives next to the thing
  // it affects:
  //   - Rename / Extend / Delete: right-pinned icons on the root
  //     fn-name row (i / ✎ / + / ✕). See `renderSingleFnRow`.
  //   - Re-parent: ✎ pencil pinned to the depth-1 parent row.
  //   - Describe: ✎ Edit button inside the description tooltip
  //     (click the `i` badge to pin → Edit). Works uniformly across
  //     fn / arg / slot / binding / ns rows.
  //   - Namespace: left-pinned `ns` badge on the root fn-name row.
  // Nothing left for the bottom toolbar to carry once the user is
  // already authed and editable, so we drop the bar entirely.
}

// HOF-captured args (e.g. `:request` on a Ring-handler subtree) are free
// slots that the enclosing higher-order call site will fill at runtime —
// not interface args for the graph-level caller. Render as a compact
// strip prefixed with `λ` so the user can see the slot exists without
// needing to plan for supplying it themselves.
function appendHofCapturedArgsStrip(overlay, hofCapturedArgs) {
  if (!Array.isArray(hofCapturedArgs) || !hofCapturedArgs.length) return;
  const strip = document.createElement('div');
  Object.assign(strip.style, {
    padding: '2px 8px',
    color: 'var(--hof-fg)',
    fontSize: '10px',
    fontStyle: 'italic',
    borderTop: '1px dashed var(--hof-border)',
    background: 'var(--hof-bg)',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis'
  });
  strip.title = 'Args supplied by the enclosing HOF invocation: ' + hofCapturedArgs.join(', ');
  strip.textContent = hofCapturedArgs.map(n => 'λ' + n).join(' ');
  overlay.appendChild(strip);
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
  const isLocalFn = !(ownFn?.name);

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
  const overlay = createOverlay(nodeId, { background: 'var(--card-header-bg)' });
  overlay.dataset.originalFnId = originalFnId;
  overlay.dataset.nodeId = nodeId;
  overlay.style.cursor = 'default';

  // Paint state lives in buildFnPaintState() above; helpers receive it
  // via rowCtx. The body of this function only ever needs `restoreStyles`
  // for the final paint after the dispatch loop.
  const linesByDepth = new Map();   // depth -> { line, spansByFnId, levelInfo }
  const paint = buildFnPaintState({ linesByDepth, visibleLevels, fullDepth, partialFns });
  const { restoreStyles } = paint;
  // Bundle threaded into the per-row render helpers below so they don't
  // each take a 12-arg signature.
  const rowCtx = { nodeId, originalFnId, isNavRoot, isLocalFn,
                   fullDepth, partialFns, visibleLevels, linesByDepth, paint };

  const useSiteHeader = appendUseSiteHeader(overlay, rowCtx);

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
      ? 'none' : '1px solid var(--light-border)';
    Object.assign(line.style, {
      borderBottom: lineBorderBottom,
      touchAction: 'none',
      userSelect: 'none',
      WebkitUserSelect: 'none'
    });

    let spansByFnId = null;
    const miLevelAbove = levelInfo.followsMI >= 0 ? visibleLevels[levelInfo.followsMI] : null;

    if (miLevelAbove && !levelInfo.isMI) {
      renderColumnBelowMiRow(line, levelInfo, miLevelAbove, rowCtx);
    } else if (levelInfo.isMI) {
      spansByFnId = renderMiRow(line, levelInfo, idx, rowCtx);
    } else {
      renderSingleFnRow(line, levelInfo, rowCtx);
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

  // Merge the use-site header with the depth-0 ancestor row when both
  // share the same root-block bg AND the header has no expansion of its
  // own to collapse. The two divs already paint continuously (same bg,
  // no separator); forwarding mouse events makes them act as one — a
  // single multi-line click target / hover region. Matches the
  // "merge non-clickable rows" UI rule.
  if (useSiteHeader && !expansionState.has(nodeId)) {
    const depth0 = linesByDepth.get(0);
    if (depth0?.levelInfo.blockIsRoot && depth0.line) {
      const target = depth0.line;
      ['mousedown', 'touchend', 'mouseenter', 'mousemove', 'mouseleave'].forEach(type => {
        useSiteHeader.addEventListener(type, (e) => {
          // The original handler on useSiteHeader bails out early when
          // there is no expansion (that's exactly the case we're in),
          // so reusing the event on the depth-0 row never double-fires.
          target.dispatchEvent(new MouseEvent(type, {
            bubbles: false, cancelable: true,
            clientX: e.clientX, clientY: e.clientY,
            button: e.button
          }));
        });
      });
      useSiteHeader.style.cursor = target.style.cursor || 'pointer';
    }
  }

  if (ancestorLevels.length > MAX_VISIBLE_ANCESTORS + 1) {
    const more = document.createElement('div');
    Object.assign(more.style, { padding: '2px 8px', color: 'var(--light-fg)', fontSize: '10px' });
    more.textContent = '...';
    overlay.appendChild(more);
  }

  appendOptionalArgsStrip(overlay, node.data('optionalArgs'));

  appendFnMetadataStrips(overlay, originalFnId, isNavRoot);

  appendHofCapturedArgsStrip(overlay, node.data('hofCapturedArgs'));

  appendFnActionToolbar(overlay, originalFnId, isNavRoot);

  createDragHandle(overlay, node);

  attachFnOverlayHoverHandlers(overlay, nodeId);

  container.appendChild(overlay);
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
  });
}
