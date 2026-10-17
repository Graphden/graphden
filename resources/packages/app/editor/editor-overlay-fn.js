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

  // Sub-pixel-gap mitigation: a 1px box-shadow with the row's own colour
  // fills any rounding slack between this row and the next. When two
  // adjacent rows share the same colour the gap reads as that colour
  // (i.e. invisible). Without this, the black overlay container shows
  // through the gap between two white rows (and vice-versa).
  const setRowBg = (el, bg) => {
    el.style.background = bg;
    el.style.boxShadow = bg ? `0 1px 0 0 ${bg}` : '';
  };

  const paintWithSpec = (sFull, sPartial) => {
    const targets = [];
    const expanded = sFull > 0 || !!sPartial?.size;
    const add = (el, fn, depth, root, cell, apply) => {
      targets.push({el, apply, facts: {root, cell, clickable: !!fn?.isClickable,
        within: depth <= sFull, 'partial-next': depth === sFull + 1 && !!sPartial?.has(fn?.fnId), expanded}});
    };
    linesByDepth.forEach(({ line, spansByFnId, levelInfo, colDivs, textOverlay }, depth) => {
      if (!levelInfo) return;
      if (colDivs) {
        const miLevel = visibleLevels[levelInfo.followsMI];
        const first = targets.length;
        for (const {col, miFn} of colDivs) add(col, miFn, miLevel.depth, miLevel.blockIsRoot, true);
        add(textOverlay, null, depth, levelInfo.blockIsRoot, false, (value, results) => {
          textOverlay.style.color = results[first].fg;
          textOverlay.style.fontWeight = value.bold ? 'bold' : 'normal';
        });
        // Column text boldness follows its root block, independently of the
        // MI selection; the exact text owner stays native above the columns.
        targets[targets.length - 1].facts.expanded = false;
      } else if (spansByFnId) {
        add(line, null, depth, false, false);
        targets[targets.length - 1].facts.expanded = false;
        for (const {span, fn} of spansByFnId.values()) add(span, fn, depth, levelInfo.blockIsRoot, true);
      } else add(line, levelInfo.fns[0], depth, levelInfo.blockIsRoot, false);
    });
    const results = window.gdCanvasInheritanceGraph.paintBatch(targets.map(target => target.facts));
    // Commit only after the complete policy batch passed. A late personal
    // refusal reruns every receipt with the builtin before any owner changes.
    targets.forEach((target, index) => {
      const value = results[index];
      if (target.apply) target.apply(value, results);
      else {
        setRowBg(target.el, value.bg);
        target.el.style.color = value.fg;
        target.el.style.fontWeight = value.bold ? 'bold' : 'normal';
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
    ROOT_BG,
    setRowBg,
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
  const willPrependUseSite = !isNavRoot && !isLocalFn;
  const ancestorLevels = buildAncestorLevels(levels, willPrependUseSite);
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
  // The fn's NAME, so a tour step can ring one card among several by the
  // name its text uses (`.node-overlay[data-fn-name="str-len"] …`) — ids
  // are per-instance and unknown when the step is written.
  if (ownFn?.name) overlay.dataset.fnName = ownFn.name;
  // Compare-mode mark (diff v2): the whole card rings when this fn
  // differs vs the compared branch under the current type lens — so the
  // MAIN graph view reads as a diff, not just the selected fn's args.
  if (typeof gdDiffModeCardInfo === 'function') {
    const dm = gdDiffModeCardInfo(originalFnId);
    if (dm) {
      overlay.classList.add('fn-overlay-diff', 'fn-overlay-diff-' + dm.kind);
      overlay.title = dm.title;
      // "Changed inside" — the change lives in an ancestor / ref target;
      // the ∿ badge reveals it in this card (or opens it).
      if (dm.kind === 'inside' && typeof gdDiffInsideBadgeEl === 'function') {
        overlay.appendChild(gdDiffInsideBadgeEl(nodeId, originalFnId, dm));
      }
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
  const rowCtx = { overlay, nodeId, originalFnId, isNavRoot, isLocalFn,
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
  window.gdCanvasInheritanceGraph.bind(overlay, () => {
    // The policy validator preserves row/control ownership. Rechecking a new
    // runtime in place retains focused native controls and their exact nodes.
    const next = buildAncestorLevels(levels, willPrependUseSite);
    // Valid personal policies may split a passive group. Apply its declared
    // membership/endpoints to the retained row objects and event closures.
    for (let index = 0; index < visibleLevels.length; index++) {
      const current = visibleLevels[index];
      const receipt = next[index];
      Object.assign(current, {groupId: receipt.groupId, groupMaxDepth: receipt.groupMaxDepth,
        blockIsRoot: receipt.blockIsRoot, followsMI: receipt.followsMI});
      const line = linesByDepth.get(current.depth)?.line;
      if (line) line.dataset.groupId = current.groupId;
    }
    for (let index = 0; index < visibleLevels.length; index++) {
      const current = visibleLevels[index];
      const following = visibleLevels[index + 1];
      const line = linesByDepth.get(current.depth)?.line;
      if (line) line.style.borderBottom = !following || following.groupId === current.groupId
        || following.followsMI >= 0 ? 'none' : '1px solid var(--light-border)';
    }
    restoreStyles();
  });

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

  // Compare mode: the fn's OWN row change (rename / description) as a strip.
  if (typeof gdDiffAppendFnStrip === 'function') gdDiffAppendFnStrip(overlay, originalFnId);

  appendFnActionToolbar(overlay, originalFnId, isNavRoot);

  createDragHandle(overlay, node);

  attachFnOverlayHoverHandlers(overlay, nodeId);

  container.appendChild(overlay);
}
