// Editor Overlay (fn rows) — the four row renderers the fn-card
// dispatch loop draws with: the use-site header and the
// column-below-MI / MI / single-fn ancestor rows. The paint state
// machine, hover wiring, and `createFnOverlay` itself live in
// editor-overlay-fn.js. Loaded immediately BEFORE
// editor-overlay-fn.js in `_editor-script-paths`.

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
          paint: { ROOT_BG, setRowBg, applyPreviewStyle, restoreStyles } } = ctx;
  if (isNavRoot || isLocalFn) return null;
  const useSite = document.createElement('div');
  useSite.className = 'ancestor-line';
  useSite.dataset.useSite = 'true';
  const hasExpansion = expansionState.has(nodeId);
  useSite.classList.add('fn-use-site-header');   // static looks in editor-styles.css
  useSite.style.cursor = hasExpansion ? 'pointer' : 'default';
  // With no expansion the header has no text and no job of its own —
  // a full 21px empty colored band reads as a missing title. Collapse
  // it to a slim color-tie stripe (the marker semantics stay); the
  // full-height band returns when there IS an expansion to collapse,
  // because then it is a real click target. Mirrored in
  // editor-layout.js USE_SITE_HEADER_SLIM_HEIGHT.
  if (!hasExpansion) {
    useSite.classList.add('fn-use-site-header-slim');
    useSite.style.height = '6px';
    useSite.style.minHeight = '6px';
    useSite.style.padding = '0';
    useSite.style.overflow = 'hidden';
  }
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
  const textOverlay = document.createElement('span');
  textOverlay.className = 'mi-col-text';   // static looks (incl. the 8px/24px insets) in editor-styles.css
  textOverlay.textContent = displayLabel(colFn.name);
  // Create invisible column divs for bg + vertical border
  const colDivs = [];
  miLevelAbove.fns.forEach((miFn, _i) => {
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
  // Per-row affordances (ns / i / ↗) move into the row-actions
  // popover anchored to the more-actions trigger. The trigger sits
  // on top of the column divs so the user can hit it across the full
  // row width.
  // HTMX migration Phase A1: the col-header row-actions content
  // is now server-rendered via `/partials/row-actions`. JS keeps
  // the popover lifecycle (open / hover / dismiss / re-anchor on
  // viewport zoom-pan) + the post-swap `data-action` dispatcher; the
  // markup + per-fn conditionals (ns badge, i badge, ↗ link) live
  // in `:partial-row-actions :_partial-row-actions-col-header`.
  const buildColPopoverContent = (host) => {
    if (typeof loadRowActionsContent !== 'function') return;
    return loadRowActionsContent(host, colFn.fnId, 'col-header', {
      showOpen: colShowOpen
    });
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
    const cardFnEntity = lookups?.fnMap?.get(ctx.originalFnId) || null;
    const miEditable = levelInfo.depth === 1
      && typeof isAuthenticated === 'function' && isAuthenticated()
      && implementationFnIds?.has(ctx.originalFnId);
    // HTMX migration Phase A2: server-renders the MI cell's
    // toolbar (ns / i / ↗ shared with col-header + when editable
    // × Remove-MI / + Add-MI). Server-side gating on `editable=true`
    // mirrors the JS `miEditable` flag exactly. The post-swap
    // `bindRowActionsDispatch` re-applies the MI-add disabled-with-
    // reason check using `compatibleMIParentInfo` (client-cached).
    const buildCellPopoverContent = (host) => {
      if (typeof loadRowActionsContent !== 'function') return;
      return loadRowActionsContent(host, f.fnId, 'cell', {
        showOpen: !!miShowOpen,
        // Ownership (tenancy): the × Remove-MI / + Add-MI edits mutate the
        // CARD's parent-set, so they're offered only on a card the principal
        // OWNS. A public / other-org card is read-only.
        editable: !!miEditable && !!cardFnEntity
                  && ((typeof graphdenIsFnOwned !== 'function') || graphdenIsFnOwned(cardFnEntity)),
        cardFnId: cardFnEntity ? cardFnEntity.id : null
      });
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
  // Secret fns wear the same 🔒 the tree rows use — on canvas the only
  // tell used to be the parent name "secret-leaf". The marker also
  // explains the model: the graph stores the VAULT PATH, never the value.
  if (lineFnEntity && typeof isSecretFn === 'function' && isSecretFn(lineFnEntity)) {
    const lock = document.createElement('span');
    lock.className = 'fn-row-secret-mark';
    lock.textContent = '🔒 ';
    lock.title = 'Secret — the value lives in the vault; the graph stores only its path';
    lock.setAttribute('aria-label', 'Secret');
    line.prepend(lock);
  }
  const cardFnEntity = lookups?.fnMap?.get(ctx.originalFnId) || null;

  // All the per-row affordances now live in the row-actions popover
  // (see editor-row-actions.js), reachable via the `⋯` trigger pinned
  // at slot r-1. The card body stays minimal — fn name only, with
  // hover-driven expansion as before. `buildPopoverContent` defers
  // building the icons until the popover actually opens, so unhovered
  // rows pay no DOM cost.
  const buildPopoverContent = (host) => {
    // HTMX migration Phase A3: when the row is a use-site-arg
    // (signed-in user on an editable card with exactly one editable
    // incoming arg), the toolbar — ns/i/↗ shared + × Remove-binding
    // + ✎ Change-value — is server-rendered. JS keeps the popover
    // lifecycle + the `data-action` dispatcher (which looks up the
    // rich `useSiteArg` object by binding-id from the
    // `_rowActionsUseSiteArgs` registry the loader populated).
    //
    // `_singleEditableIncomingArg` already gates `useSiteArg` on
    // signed-in + edit-allowed, so passing `editable: true` here is
    // safe — the dispatcher does a second `isAuthenticated()`
    // check inside `deleteUseSiteBinding` as a defence in depth.
    if (useSiteArg) {
      if (typeof loadRowActionsContent !== 'function') return;
      return loadRowActionsContent(host, lineFn.fnId, 'use-site-arg', {
        showOpen: !!lineShowOpen,
        // Ownership (tenancy): × Remove-binding / ✎ Change-value mutate the
        // card's fn, so gate on owning the card. Read-only on a public/other-org
        // fn (server enforces too). Unknown card → fail-open.
        editable: (typeof graphdenIsFnOwned !== 'function')
                  || !cardFnEntity || graphdenIsFnOwned(cardFnEntity),
        useSiteArg: useSiteArg
      });
    }
    // HTMX migration Phase A4: root-row context (▶⌛⚙✎+✕ plus the
    // shared ns/i/↗ head). Client computes the edit-gating strings
    // the server uses to render disabled-with-reason states
    // (`editable` + `editBlockReason` for ✎/+/✕); the ⚙ service
    // block-reason is computed SERVER-side inside the partial from
    // `:service-blocking-free-args` — the same predicate the
    // create-service guard uses.
    if (rootAffordancesVisible && lineFnEntity) {
      if (typeof loadRowActionsContent !== 'function') return;
      return loadRowActionsContent(host, lineFn.fnId, 'root-row', {
        showOpen: !!lineShowOpen,
        editable: !!lineEditable,
        editBlockReason: lineEditBlockReason,
        // Ownership (tenancy): Rename / Delete render only on a fn the principal
        // OWNS. A public / other-org fn stays read-only (Run / Extend / view
        // remain). Single-tenant + platform-tier → always owned (unchanged).
        // A package-synced fn is not "owned" in the editing sense either: the
        // API refuses its rename and delete (403) and the next boot's sync
        // would revert them — so those two stay hidden there as well.
        owned: ((typeof graphdenIsFnOwned === 'function')
          ? graphdenIsFnOwned(lineFnEntity) : true)
          && !(typeof isPackageOwnedFn === 'function'
               && isPackageOwnedFn(lineFn.fnId))
      });
    }
    // Parent-edit row (depth-1 of an editable card) — same toolbar
    // shape as the MI cell context (ns/i/↗ + × Remove-parent + +
    // Add-MI). Reuses the `cell` partial directly: the dispatcher
    // cases for `remove-mi-parent` / `add-mi-parent` already operate
    // on `data-card-fn-id` (the card-owning fn) + `data-fn-id` (the
    // parent being acted on), which matches `removeParentInline
    // (cardFnEntity, lineFn.fnId)` 1:1.
    if (parentEditAllowed && cardFnEntity) {
      if (typeof loadRowActionsContent !== 'function') return;
      return loadRowActionsContent(host, lineFn.fnId, 'cell', {
        showOpen: !!lineShowOpen,
        // Ownership (tenancy): reparent / MI edits mutate the card's parent-set
        // → offered only on a card the principal owns. Read-only otherwise.
        editable: (typeof graphdenIsFnOwned !== 'function')
                  || !cardFnEntity || graphdenIsFnOwned(cardFnEntity),
        cardFnId: cardFnEntity.id
      });
    }
    // Fall-through: read-only viewers + non-root, non-parent-edit
    // lines. ns/i/↗ shared head only — reuses the `col-header`
    // partial (functionally identical 3-button shape; the
    // `data-context` value is debug-only).
    if (typeof loadRowActionsContent === 'function') {
      return loadRowActionsContent(host, lineFn.fnId, 'col-header', {
        showOpen: !!lineShowOpen
      });
    }
  };
  // Service badge — only on the root row of an fn-card the cache
  // knows about. Click opens the same service popover the ⚙ button
  // does; users see "this fn is running as a service" at-a-glance
  // without opening the actions popover.
  if (lineIsRoot && lineFnEntity
      && typeof getServiceForFnId === 'function'
      && typeof serviceBadgeState === 'function') {
    const svc = getServiceForFnId(lineFnEntity.id);
    const state = serviceBadgeState(svc);
    if (state) {
      const badge = document.createElement('span');
      badge.className = 'service-badge service-badge-' + state;
      badge.textContent = '●';
      const stateLabels = {
        running:  'Running as a service',
        failed:   'Service start failed — exhausted retries',
        disabled: 'Service declared but disabled',
        pending:  'Service enabled but not yet running — reconcile to start',
      };
      badge.title = stateLabels[state] + '. Click for settings.';
      badge.setAttribute('role', 'button');
      badge.setAttribute('tabindex', '0');
      badge.style.cursor = 'pointer';
      badge.addEventListener('click', (e) => {
        e.stopPropagation();
        if (typeof showServicePopover === 'function') {
          showServicePopover(lineFnEntity, badge);
        }
      });
      line.appendChild(badge);
    }
  }
  // Type-error badge (error-tolerance Phase 3) — root row of a card
  // whose fn currently fails the aggregate type-check. The count is
  // server-computed (`:type-error-count` on the subtree payload, from
  // the per-branch diagnostics store) and refreshes with the subtree
  // re-fetch after every mutation.
  if (lineIsRoot && lineFnEntity && (lineFnEntity['type-error-count'] || 0) > 0) {
    const n = lineFnEntity['type-error-count'];
    const warn = document.createElement('span');
    warn.className = 'type-error-badge';
    warn.textContent = '⚠';
    warn.title = n === 1
      ? '1 type error on this fn — see the Type errors panel'
      : n + ' type errors on this fn — see the Type errors panel';
    line.appendChild(warn);
  }
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
