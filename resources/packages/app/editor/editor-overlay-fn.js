// Editor Overlay (fn) - fn-card overlay renderer: ancestor rows, MI
// cells, the paint state machine, and `createFnOverlay`.
// Depends on: editor-state.js, editor-data.js, editor-tooltips.js,
//             editor-icons.js, editor-drag.js, editor-row-actions.js.

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

// Walk a fn-card's incoming graph edges and return the SINGLE
// editable arg if (a) there's exactly one such arg and (b) the
// owning fn is in the immediate implementation closure of the
// nav-root + the user is signed in. Returns null otherwise. Used to
// decide whether the value-fn card's depth-0 row should surface
// per-binding actions (× delete / ✎ change) — multi-incoming and
// uneditable cards skip the affordance to keep the row clean.
function _singleEditableIncomingArg(nodeId) {
  if (!gv.ready() || typeof argRowFromNode !== 'function') return null;
  const graphNode = gv.node(nodeId);
  if (!graphNode) return null;
  const editable = [];
  graphNode.incomingEdges().forEach((edge) => {
    const arg = argRowFromNode(edge.data());
    if (!arg) return;
    const inImpl = implementationFnIds?.has(arg['fn-id']);
    const signedIn = typeof isAuthenticated === 'function' && isAuthenticated();
    if (inImpl && signedIn) editable.push(arg);
  });
  return editable.length === 1 ? editable[0] : null;
}

// `attachNamespaceBadge` lived here in earlier iterations to pin a
// `ns` badge inside each fn-row. Removed when the row-actions popover
// (editor-row-actions.js) absorbed the ns badge alongside the other
// per-row affordances; row-bodies now show only the fn name. The
// `+` Add-MI-parent factory also lived here until Phase A2/A4.5 of
// the HTMX migration moved its rendering to `:partial-row-actions`
// (cell context) — JS now just dispatches the `add-mi-parent` click
// through the row-actions dispatcher to `addMIParentInline`.

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
    if (gv.ready()) gv.highlightEdgesFrom(nodeId);
  });
  overlay.addEventListener('mouseleave', () => {
    if (gv.ready()) gv.clearEdgeHighlight();
  });
  overlay.addEventListener('mouseleave', () => {
    if (!rebuildingOverlays && !isGrabbing && overlay.isConnected) {
      onPreviewLeave();
      clearPreview(nodeId);
    }
  });
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
  // Compare-mode mark (diff v2): the whole card rings when this fn
  // differs vs the compared branch under the current type lens — so the
  // MAIN graph view reads as a diff, not just the selected fn's args.
  if (typeof gdDiffModeCardInfo === 'function') {
    const dm = gdDiffModeCardInfo(originalFnId);
    if (dm) {
      overlay.classList.add('fn-overlay-diff', 'fn-overlay-diff-' + dm.kind);
      overlay.title = dm.title;
    }
  }
  overlay.style.cursor = 'default';
  // The card's accessible name. Without it the group announces as unnamed and
  // the canvas reads as a pile of anonymous containers.
  const cardName = ownFn?.name
    ? (typeof getQualifiedFnName === 'function' ? getQualifiedFnName(ownFn) : ownFn.name)
    : 'anonymous function';
  overlay.setAttribute('aria-label', cardName);

  // Click the card BODY (not a button / inline editor) to inspect THIS node's
  // fn in the right panel — the standard "select a node → see its details"
  // loop, so you can read any node you see on the graph, not just the one you
  // opened. Buttons, links and inline editors inside the card own their clicks.
  overlay.addEventListener('click', (e) => {
    if (e.target.closest(
      'button, a, input, select, textarea, label, [contenteditable="true"], .placeholder-binder',
    )) return;
    if (typeof window.gdInspectorRender !== 'function') return;
    document.querySelectorAll('.node-overlay.gd-node-active')
      .forEach((n) => { n.classList.remove('gd-node-active'); });
    overlay.classList.add('gd-node-active');
    window.gdInspectorRender(originalFnId);
  });

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
    line.classList.add('fn-ancestor-line');   // static looks in editor-styles.css
    line.style.borderBottom = lineBorderBottom;

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
    more.classList.add('fn-ancestor-more');   // static looks in editor-styles.css
    more.textContent = '...';
    overlay.appendChild(more);
  }

  appendFnMetadataStrips(overlay, originalFnId, isNavRoot, {
    returnTypeAlias: node.data('returnTypeAlias') || null,
    ruleOwner: node.data('ruleOwner') || null,
    branchLocal: node.data('branchLocal') || null,
  });

  appendDeepFreeArgsStrip(overlay, node.data('deepFreeArgs'));

  appendFnActionToolbar(overlay, originalFnId, isNavRoot);

  createDragHandle(overlay, node);

  attachFnOverlayHoverHandlers(overlay, nodeId);

  container.appendChild(overlay);
}
