// Editor Row-Actions Popover - Singleton floating panel that hosts the
// per-row action icons (ns / i / ↗ / ✎ / × / + / ✕) OUTSIDE the card.
// Anchored to the right of the trigger button on the row.
//
// Why not in the row? Each fn-card's overlay has overflow: hidden so
// the row's contents stay clipped to the card silhouette. To show
// affordances "next to but outside" the card we need a separate
// floating element that lives at body-level and re-positions to the
// trigger's bounding rect.
//
// Lifecycle mirrors the description-tooltip:
//   - hover-show on the trigger's mouseenter
//   - hover-hide on the trigger's mouseleave (debounced — so the
//     cursor can travel from trigger → popover without the popover
//     closing under it)
//   - click-pin → the popover stays put even when hover ends; close
//     via document-level outside-click or the × on the popover
//   - touch users always get the click-pin path (no hover semantics)
//
// Depends on: editor-state.js (for shared singletons / dom utilities).

let rowActionsPopoverEl = null;
let rowActionsPopoverAnchor = null;
let rowActionsPopoverSticky = false;
let rowActionsPopoverHideTimer = null;
let rowActionsPopoverFadeTimer = null;

// Symmetric counterpart to the show-time fade-in: drive opacity to 0
// then flip display:none after the transition completes. Reopening
// during the fade cancels both timers so the user doesn't see a
// flash of disappearing chrome.
function fadeOutPopover() {
  if (!rowActionsPopoverEl) return;
  if (rowActionsPopoverEl.style.display === 'none') return;
  rowActionsPopoverEl.style.opacity = '0';
  if (rowActionsPopoverFadeTimer) clearTimeout(rowActionsPopoverFadeTimer);
  rowActionsPopoverFadeTimer = setTimeout(() => {
    if (rowActionsPopoverEl) rowActionsPopoverEl.style.display = 'none';
    rowActionsPopoverFadeTimer = null;
  }, 90);
}

function ensureRowActionsPopover() {
  if (rowActionsPopoverEl) return rowActionsPopoverEl;
  const el = document.createElement('div');
  el.className = 'row-actions-popover';
  // role="toolbar" announces the bag of action buttons as a group
  // without imposing focus-trap semantics (which `role="dialog"`
  // would). aria-label tells screen readers what the toolbar's for.
  el.setAttribute('role', 'toolbar');
  el.setAttribute('aria-label', 'Row actions');
  Object.assign(el.style, {
    position: 'fixed',
    zIndex: '9500',
    display: 'none',
    background: 'var(--card-bg)',
    border: '1px solid var(--card-border)',
    borderRadius: '4px',
    padding: '3px 6px',
    boxShadow: 'var(--shadow-md)',
    pointerEvents: 'auto',
    whiteSpace: 'nowrap',
    fontFamily: 'SF Mono, Monaco, monospace',
    fontSize: '11px',
    color: 'var(--card-fg)'
  });
  // Cursor leaving the popover itself dismisses it (unless pinned).
  el.addEventListener('mouseenter', () => {
    if (rowActionsPopoverHideTimer) {
      clearTimeout(rowActionsPopoverHideTimer);
      rowActionsPopoverHideTimer = null;
    }
  });
  el.addEventListener('mouseleave', () => {
    if (!rowActionsPopoverSticky) hideRowActionsPopover();
  });
  // Description-badge hover is DELEGATED (matches `[data-action=
  // "description"]` off e.target), so it survives every content swap
  // and is bound ONCE here — binding it per-swap would leak a fresh
  // listener pair onto this singleton element on every popover open.
  _bindDescriptionBadgeHover(el);
  // An action that opens its OWN standalone UI (Run popover, version
  // modal, rename form, …) dismisses this menu — otherwise the two
  // popovers stack. Delegated so it survives content swaps. `ns` and
  // `i` stay: their mini-popovers anchor to the button INSIDE this
  // menu, so it must remain visible under them.
  el.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-action]');
    if (!btn || btn.getAttribute('aria-disabled') === 'true') return;
    const keepOpen = ['description', 'namespace-move', 'add-mi-parent'];
    if (keepOpen.includes(btn.dataset.action)) return;
    rowActionsPopoverSticky = false;
    // Next tick — let the action's own handler read the anchor first.
    setTimeout(() => hideRowActionsPopover(), 0);
  });
  document.body.appendChild(el);
  rowActionsPopoverEl = el;
  return el;
}

// Anchor strategy: float to the RIGHT of the CARD by default — the
// popover lives "outside the node" no matter where in the row the
// trigger physically sits, so MI cells (multiple triggers per row)
// don't paint over their neighbour cells. Vertical center stays
// aligned with the actual trigger so the user can still tell which
// row's actions are showing.
//
// If there's not enough room on the right, fall back to LEFT-of-card.
//
// The popover follows the viewport zoom only LOOSELY (clamped): it
// used to match the card chrome 1:1 back when it was an icon row,
// but since the labeled-menu redesign it is a TEXT menu — text is
// read at UI scale, and a 1:1 match made it fill half the canvas at
// high zoom and become unreadable at low zoom. The residual clamp
// keeps it from visually detaching from very small / large cards.
function positionRowActionsPopover(el, anchor) {
  const ar = anchor.getBoundingClientRect();
  // Anchor X to the card's edge, not the trigger's — see comment above.
  // Falls back to the trigger if no card ancestor (defensive; the
  // overlay should always be there for in-card triggers).
  const card = anchor.closest('.node-overlay') || anchor;
  const cr = card.getBoundingClientRect();
  // If the anchor (or its card) is fully outside the viewport — pan
  // moved them off-screen — dismiss instead of leaving the popover
  // floating without a visible anchor. This also handles the case
  // where the user pans far while the popover is sticky.
  const offscreen = ar.right < 0 || ar.left > window.innerWidth
                 || ar.bottom < 0 || ar.top > window.innerHeight
                 || cr.right < 0 || cr.left > window.innerWidth;
  if (offscreen) {
    rowActionsPopoverSticky = false;
    if (rowActionsPopoverAnchor) {
      rowActionsPopoverAnchor.setAttribute('aria-expanded', 'false');
    }
    rowActionsPopoverAnchor = null;
    fadeOutPopover();
    return;
  }
  const rawZoom = (typeof gv !== 'undefined' && gv.ready()) ? gv.zoom() : 1;
  const zoom = Math.max(0.9, Math.min(1.1, rawZoom));
  // Reset transform so offsetWidth measures the un-scaled size.
  el.style.transform = '';
  el.style.transformOrigin = 'top left';
  el.style.display = 'inline-block';
  el.style.left = '-9999px';
  el.style.top = '-9999px';
  const baseW = el.offsetWidth || 200;
  const baseH = el.offsetHeight || 24;
  const pw = baseW * zoom;
  const ph = baseH * zoom;
  // Gap scales with zoom too — a flat 6px gap looks "tight" at zoom 2
  // and "huge" at zoom 0.5 because the trigger and popover both
  // resize but the gap doesn't. Scaling it keeps the visual ratio
  // constant.
  const margin = 6 * zoom;
  let left = cr.right + margin;
  if (left + pw > window.innerWidth - 8) {
    // Not enough room on the right of the card — open to the left.
    left = Math.max(8, cr.left - margin - pw);
  }
  // Vertical: align centre-to-centre with the (scaled) trigger so the
  // user sees the popover next to the row whose actions it shows.
  let top = ar.top + ar.height / 2 - ph / 2;
  top = Math.max(8, Math.min(top, window.innerHeight - ph - 8));
  el.style.left = left + 'px';
  el.style.top = top + 'px';
  el.style.transform = 'scale(' + zoom + ')';
}

// Public — show the popover, populate via the caller's `build(host)`.
// The host is empty on entry; `build` appends whatever icons it likes
// (and is free to use the same factories the inline rows used to use,
// just without the `pinRight: true` flag).
function showRowActionsPopover(anchorEl, build) {
  if (!anchorEl || typeof build !== 'function') return;
  if (rowActionsPopoverHideTimer) {
    clearTimeout(rowActionsPopoverHideTimer);
    rowActionsPopoverHideTimer = null;
  }
  // A re-show during fade-out cancels the pending display:none, so the
  // user doesn't see the popover disappear and pop back in.
  if (rowActionsPopoverFadeTimer) {
    clearTimeout(rowActionsPopoverFadeTimer);
    rowActionsPopoverFadeTimer = null;
  }
  const el = ensureRowActionsPopover();
  // If the same anchor's popover is already pinned, do nothing —
  // a second hover shouldn't rebuild over a sticky session.
  if (rowActionsPopoverSticky && rowActionsPopoverAnchor === anchorEl) return;
  // Flip the previous anchor's aria-expanded back to false, then
  // mark the new one open. Screen readers track "this trigger now
  // controls a visible popover".
  if (rowActionsPopoverAnchor && rowActionsPopoverAnchor !== anchorEl) {
    rowActionsPopoverAnchor.setAttribute('aria-expanded', 'false');
  }
  el.textContent = '';
  el.style.opacity = '0';
  rowActionsPopoverAnchor = anchorEl;
  anchorEl.setAttribute('aria-expanded', 'true');
  ensureRowActionsCyHandlers();
  // The popover body is built ASYNCHRONOUSLY (a server partial on first open,
  // then instant from cache). Keep the popover HIDDEN until it's populated,
  // then position + fade in — otherwise the user sees the loadPartial "…"
  // placeholder / an empty box flash before the menu ("intermediate dots").
  // `build` returns the load promise; a cache hit resolves on the next
  // microtask, so a warm popover still feels instant.
  const reveal = () => {
    if (rowActionsPopoverAnchor !== anchorEl) return; // hover moved on mid-load
    // Position (flips display to inline-block to read offsetWidth) then unfade
    // next frame so the transition catches.
    positionRowActionsPopover(el, anchorEl);
    requestAnimationFrame(() => { el.style.opacity = '1'; });
    // Move focus into the popover on keyboard/sticky open (hover-show must not
    // grab focus from whatever the user was looking at).
    if (rowActionsPopoverSticky) {
      const first = el.querySelector('button, a[href], [tabindex]:not([tabindex="-1"])');
      if (first) {
        try { first.focus({ preventScroll: true }); } catch (_) { first.focus(); }
      }
    }
  };
  const built = build(el);
  if (built && typeof built.then === 'function') built.then(reveal, reveal);
  else reveal();
}

// Public — schedule a hide (debounced so the cursor can cross the
// gap from trigger to popover). Cancelled by the popover's own
// mouseenter handler above.
function hideRowActionsPopover() {
  if (rowActionsPopoverSticky) return;
  if (rowActionsPopoverHideTimer) clearTimeout(rowActionsPopoverHideTimer);
  rowActionsPopoverHideTimer = setTimeout(() => {
    if (rowActionsPopoverAnchor) {
      rowActionsPopoverAnchor.setAttribute('aria-expanded', 'false');
    }
    rowActionsPopoverAnchor = null;
    rowActionsPopoverHideTimer = null;
    fadeOutPopover();
  }, 120);
}

// Public — pin the currently-shown popover open (toggled by the
// trigger's click handler; touch devices land here exclusively).
function toggleRowActionsPopoverSticky(anchorEl, build) {
  if (rowActionsPopoverSticky && rowActionsPopoverAnchor === anchorEl) {
    rowActionsPopoverSticky = false;
    hideRowActionsPopover();
    return;
  }
  rowActionsPopoverSticky = true;
  showRowActionsPopover(anchorEl, build);
}

// Document-level dismissal — clicking anywhere that isn't the popover
// or its anchor closes a pinned popover. Esc on a focused popover
// child does the same and additionally restores focus to the anchor
// trigger so keyboard users return to where they came from.
let rowActionsDocumentHandler = false;
function ensureRowActionsDismissHandler() {
  if (rowActionsDocumentHandler) return;
  rowActionsDocumentHandler = true;
  document.addEventListener('mousedown', (e) => {
    if (!rowActionsPopoverEl) return;
    if (rowActionsPopoverEl.style.display === 'none') return;
    if (rowActionsPopoverEl.contains(e.target)) return;
    if (rowActionsPopoverAnchor?.contains(e.target)) return;
    rowActionsPopoverSticky = false;
    if (rowActionsPopoverAnchor) {
      rowActionsPopoverAnchor.setAttribute('aria-expanded', 'false');
    }
    rowActionsPopoverAnchor = null;
    fadeOutPopover();
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!rowActionsPopoverEl || rowActionsPopoverEl.style.display === 'none') return;
    e.stopPropagation();
    rowActionsPopoverSticky = false;
    const anchor = rowActionsPopoverAnchor;
    rowActionsPopoverAnchor = null;
    if (anchor) {
      anchor.setAttribute('aria-expanded', 'false');
      try { anchor.focus({ preventScroll: true }); } catch (_) { anchor.focus(); }
    }
    fadeOutPopover();
  });
}
ensureRowActionsDismissHandler();

// ============================================================================
// ROW-ACTIONS PARTIAL — fetch + dispatcher registration
// ============================================================================
//
// The generic dispatch + partial-load primitives live in
// `graphden-runtime.js` (`loadPartial`, `bindActionDispatch`,
// `registerActionHandler`). This file does TWO things:
//
//   (1) `loadRowActionsContent(host, fnId, context, opts)` — a
//       thin wrapper over `loadPartial` that builds the
//       row-actions partial URL from the per-context opts and
//       registers the rich `useSiteArg` (when present) in the
//       binding-id-keyed map below before fetch.
//
//   (2) At file-load time, registers each of the 10 row-actions
//       `data-action` handlers via `registerActionHandler`. The
//       runtime's `bindActionDispatch` (auto-invoked by
//       `loadPartial`'s post-swap step) routes clicks to these.
//
// Description-badge hover + post-swap MI-add compatibility check
// are row-actions-specific concerns — they flow in via the
// `onSwap` callback we pass to `loadPartial`.

// Registry of rich `useSiteArg` objects keyed by binding-id. The
// server-rendered × / ✎ buttons carry only `data-binding-id` (a
// stable identifier); the dispatcher looks the full arg up here
// before invoking `deleteUseSiteBinding` / `enterFreeArgBindEditMode`
// which both need the arg's `:type` / `:item-id` / etc. fields.
const _rowActionsUseSiteArgs = new Map();

// Cache of rendered row-actions partial HTML, keyed by the full partial URL
// (which encodes fn-id + context + editable / owned / show-open). The popover
// is a FIXED set of actions for a given fn+context, so re-fetching it on every
// hover was pure waste — and it flashed loadPartial's "…" placeholder each
// time. First hover fetches; every hover after is instant from here. The URL
// key captures the auth-derived params (editable / owned), so a sign-in/out —
// which re-renders the graph with new params — naturally keys to fresh entries.
// Bounded FIFO so a long session can't grow it without bound.
const _rowActionsHtmlCache = new Map();
const _ROW_ACTIONS_CACHE_MAX = 300;


function _bindDescriptionBadgeHover(host) {
  host.addEventListener('mouseenter', (e) => {
    const btn = e.target.closest('[data-action="description"]');
    if (!btn) return;
    if (typeof hideFullNameTooltip === 'function') hideFullNameTooltip();
    if (typeof showDescriptionTooltip !== 'function') return;
    showDescriptionTooltip({
      name: null,
      namespace: null,
      description: btn.dataset.description || '',
      entityType: btn.dataset.entityType || null,
      entityId: btn.dataset.fnId
                || host.dataset.fnId
                || null
    }, e);
  }, true);
  host.addEventListener('mouseleave', (e) => {
    const btn = e.target.closest('[data-action="description"]');
    if (!btn) return;
    if (typeof hideDescriptionTooltip === 'function') hideDescriptionTooltip();
  }, true);
}


function _applyAddMICompatibilityState(host) {
  // Post-swap MI-add compatibility check — disable + tooltip when
  // no MI parent candidates exist. Mirrors the legacy
  // `makeAddMIParentButton`'s in-place gating; the button itself
  // is server-rendered, only the disabled-with-reason state lives
  // in JS because `compatibleMIParentInfo` walks client-cached
  // `lookups`.
  const addMiBtn = host.querySelector('[data-action="add-mi-parent"]');
  if (!addMiBtn || typeof compatibleMIParentInfo !== 'function') return;
  const cardFnId = host.dataset.cardFnId;
  const cardFnEntity = lookups?.fnMap?.get(cardFnId);
  if (!cardFnEntity) return;
  const info = compatibleMIParentInfo(cardFnEntity.id,
                                      cardFnEntity['parent-ids'] || []);
  if (info?.candidateIds.size !== 0) return;
  const reasons = Object.values(info.rejected || {});
  const counts = {};
  for (const r of reasons) counts[r] = (counts[r] || 0) + 1;
  let topReason = null, topCount = 0;
  for (const [r, c] of Object.entries(counts)) {
    if (c > topCount) { topReason = r; topCount = c; }
  }
  addMiBtn.disabled = true;
  addMiBtn.classList.add('action-icon-disabled');
  addMiBtn.style.cursor = 'help';
  addMiBtn.title = 'No compatible MI parent — '
                + (topReason || 'no compatible MI parent in the registry');
}


async function loadRowActionsContent(host, fnId, context, opts) {
  opts = opts || {};
  if (!host || !fnId) return;
  // Register the rich arg before fetch — the dispatcher binds
  // post-swap and reads by binding-id then.
  if (opts.useSiteArg?.['binding-id']) {
    _rowActionsUseSiteArgs.set(opts.useSiteArg['binding-id'], opts.useSiteArg);
  }
  // `/partials/*` paths are out of scope for `window.API` (only
  // `/api/*` flows through the validator + boot-cached constants);
  // the literal stays explicit — drift validator doesn't touch it.
  const useSiteBindingId = opts.useSiteArg
                         ? opts.useSiteArg['binding-id'] : null;
  const url = '/partials/row-actions'
            + '?fn-id=' + encodeURIComponent(fnId)
            + '&context=' + encodeURIComponent(context)
            + (opts.showOpen === false ? '&show-open=false' : '')
            + (opts.editable ? '&editable=true' : '')
            + (opts.owned === false ? '&owned=false' : '')
            + (opts.cardFnId
                ? '&card-fn-id=' + encodeURIComponent(opts.cardFnId)
                : '')
            + (useSiteBindingId
                ? '&binding-id=' + encodeURIComponent(useSiteBindingId)
                : '')
            + (opts.useSiteArg?.['item-id'] ? '&seq-item=true' : '')
            + (opts.editBlockReason
                ? '&edit-block-reason='
                  + encodeURIComponent(opts.editBlockReason)
                : '');
  // Cache hit → render synchronously, no fetch, no "…" flash. The add-MI
  // disabled-state is recomputed against the CURRENT lookups (not cached), so a
  // stale-graph case can't wrongly enable it.
  const cached = _rowActionsHtmlCache.get(url);
  if (cached != null) {
    host.innerHTML = cached;
    _applyAddMICompatibilityState(host);
    if (typeof bindActionDispatch === 'function') bindActionDispatch(host);
    return Promise.resolve();
  }
  return loadPartial(host, url, {
    loadingClass: 'row-actions-loading',
    // No visible loading text — the popover stays hidden until this resolves
    // (see showRowActionsPopover), so an intermediate "…" would only ever flash.
    loadingText: '',
    errorClass: 'row-actions-error',
    onSwap: (h) => {
      _rowActionsHtmlCache.set(url, h.innerHTML);
      if (_rowActionsHtmlCache.size > _ROW_ACTIONS_CACHE_MAX) {
        _rowActionsHtmlCache.delete(_rowActionsHtmlCache.keys().next().value);
      }
      _applyAddMICompatibilityState(h);
    }
  });
}


// =============================================================================
// HANDLER REGISTRATION
// =============================================================================
//
// Each `data-action="X"` handler registered here is invoked by
// the runtime's `bindActionDispatch` when the user clicks an
// enabled button. Handlers may use the second `event` arg (for
// preventDefault / stopPropagation) and the third `host` arg
// (for fall-through to `host.dataset.*`).

// The `ns` badge is primarily "WHERE does this fn live" — it opens a small
// popover showing the fn's namespace path + a "Reveal in Explorer" action to
// find it in the tree, and (only when the fn is editable) a "Move to another
// namespace…" action. So it's useful on ANY node — including a read-only /
// stdlib fn you just want to locate — instead of silently doing nothing.
registerActionHandler('namespace-move', (btn, e) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fn = lookups?.fnMap?.get(fnId);
  const signedIn = typeof isAuthenticated === 'function' && isAuthenticated();
  // Moving a fn to another namespace is an ownership edit — offer it only on a
  // fn the principal both structurally can edit AND owns (tenancy). Reveal +
  // the namespace path stay available on ANY fn (read-only locate).
  const owned = (typeof graphdenIsFnOwned !== 'function') || graphdenIsFnOwned(fn);
  const editable = (typeof isFnEditable === 'function' && isFnEditable(fnId)) && owned;
  const nsPath = (fn && lookups?.nsPathMap && fn['namespace-id'])
    ? (lookups.nsPathMap.get(fn['namespace-id']) || '(root)')
    : '(root)';

  const menu = document.createElement('div');
  menu.className = 'ns-menu';
  const label = document.createElement('div');
  label.className = 'ns-menu-label';
  label.textContent = 'Namespace';
  const path = document.createElement('div');
  path.className = 'ns-menu-path';
  path.textContent = nsPath;
  menu.append(label, path);

  const reveal = document.createElement('button');
  reveal.type = 'button';
  reveal.className = 'ns-menu-btn';
  reveal.textContent = 'Reveal in Explorer';
  reveal.addEventListener('click', () => {
    if (typeof hideIconReasonPopover === 'function') hideIconReasonPopover();
    if (typeof revealFnInTree === 'function') revealFnInTree(fnId);
  });
  menu.appendChild(reveal);

  if (signedIn && editable && fn && typeof enterNamespaceMoveEditMode === 'function') {
    const move = document.createElement('button');
    move.type = 'button';
    move.className = 'ns-menu-btn';
    move.textContent = 'Move to another namespace…';
    move.addEventListener('click', () => {
      if (typeof hideIconReasonPopover === 'function') hideIconReasonPopover();
      enterNamespaceMoveEditMode(fn, btn);
    });
    menu.appendChild(move);
  }

  if (typeof showIconReasonPopover === 'function') showIconReasonPopover(btn, menu);
});


registerActionHandler('description', (btn, e, host) => {
  // Click toggles sticky — match the legacy badge behaviour.
  e.preventDefault();
  e.stopPropagation();
  if (typeof descriptionTooltipSticky !== 'undefined') {
    descriptionTooltipSticky = !descriptionTooltipSticky;
  }
  if (typeof showDescriptionTooltip === 'function') {
    showDescriptionTooltip({
      name: null,
      namespace: null,
      description: btn.dataset.description || '',
      entityType: btn.dataset.entityType || null,
      entityId: btn.dataset.fnId
                || btn.closest('[data-fn-id]')?.dataset.fnId
                || null
    }, e);
  }
});


registerActionHandler('open', (btn, e) => {
  // Open THIS node's fn in a new tab. The server-rendered href is a fallback;
  // the editor navigates by the URL HASH (`#<qualified-name>`), not a `?fn=`
  // query (nothing reads that), so build the same hash the tree's ↗ uses from
  // the client's qualified name — robust against duplicate bare names.
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fn = (fnId && lookups?.fnMap) ? lookups.fnMap.get(fnId) : null;
  const name = (fn && typeof getQualifiedFnName === 'function') ? getQualifiedFnName(fn) : null;
  if (name && name !== '(anonymous)') {
    e.preventDefault();
    window.open('#' + encodeURIComponent(name), '_blank', 'noopener');
  }
  // else: fall through to the <a href> default (best-effort for an
  // unresolved / anonymous fn — the dispatcher never renders ↗ for those).
});


registerActionHandler('remove-mi-parent', (btn, e, host) => {
  // Remove THIS cell's fn from the CARD-owning fn's parent-set.
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const cardFnId = btn.dataset.cardFnId
                  || btn.closest('[data-card-fn-id]')?.dataset.cardFnId;
  const cardFnEntity = lookups?.fnMap?.get(cardFnId);
  if (cardFnEntity && typeof removeParentInline === 'function') {
    removeParentInline(cardFnEntity, fnId);
  }
});


registerActionHandler('add-mi-parent', (btn, e, host) => {
  // Open the MI picker for the CARD-owning fn. Compatibility
  // check (no candidates → disable + reason) stays in
  // `_applyAddMICompatibilityState` because `compatibleMIParentInfo`
  // reads client-cached `lookups`; server has no view of that map.
  e.preventDefault();
  e.stopPropagation();
  const cardFnId = btn.dataset.cardFnId
                  || btn.closest('[data-card-fn-id]')?.dataset.cardFnId;
  const cardFnEntity = lookups?.fnMap?.get(cardFnId);
  if (cardFnEntity && typeof addMIParentInline === 'function') {
    addMIParentInline(cardFnEntity, btn);
  }
});


registerActionHandler('remove-use-site-binding', (btn, e, host) => {
  // Look up the rich `useSiteArg` via the binding-id-keyed
  // registry the caller populated pre-fetch. The arg carries
  // `:type` / `:item-id` / etc. that `deleteUseSiteBinding`
  // needs to choose between sequence-item-removal and binding-
  // deletion code paths.
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg && typeof deleteUseSiteBinding === 'function') {
    deleteUseSiteBinding(arg);
  }
});


registerActionHandler('change-use-site-value', (btn, e, host) => {
  // `enterFreeArgBindEditMode` dispatches on the arg's effective
  // type (fn-picker for `:fn` slots, literal form for the rest) —
  // same registry lookup pattern as above.
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg && typeof enterFreeArgBindEditMode === 'function') {
    enterFreeArgBindEditMode(arg, btn);
  }
});


// --- sequence-item ordering (↑ / ↓ / + Insert-before) ---
// Same binding-id-keyed registry lookup as × / ✎; the rich arg
// carries the `item-id` and `position` the endpoints need.

registerActionHandler('seq-move-item-up', (btn, e, host) => {
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg?.['item-id'] && typeof moveSequenceItem === 'function') {
    moveSequenceItem(arg['item-id'], 'up');
  }
});


registerActionHandler('seq-move-item-down', (btn, e, host) => {
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg?.['item-id'] && typeof moveSequenceItem === 'function') {
    moveSequenceItem(arg['item-id'], 'down');
  }
});


registerActionHandler('seq-insert-before', (btn, e, host) => {
  // Reuses the append chooser (literal vs fn-ref) with the anchor
  // item's position — the backend shifts later items +1.
  e.preventDefault();
  e.stopPropagation();
  const bindingId = btn.closest('[data-binding-id]')?.dataset.bindingId;
  const arg = _rowActionsUseSiteArgs.get(bindingId);
  if (arg?.['fn-id'] && typeof arg.position === 'number'
      && typeof appendSequenceItem === 'function') {
    appendSequenceItem(arg['fn-id'], btn, undefined,
                       { position: arg.position,
                         elemType: (typeof seqElemType === 'function' ? seqElemType(arg) : null) });
  }
});


registerActionHandler('run-fn', (btn, e, host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof showExecutePopover === 'function') {
    showExecutePopover(fnEntity, btn);
  }
});


registerActionHandler('fn-versions', (btn, e, host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof showFnVersionsPopover === 'function') {
    showFnVersionsPopover(fnEntity, btn);
  }
});


registerActionHandler('service-settings', (btn, e, host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof showServicePopover === 'function') {
    showServicePopover(fnEntity, btn);
  }
});


registerActionHandler('rename-fn', (btn, e, host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof enterFnRenameEditMode === 'function') {
    enterFnRenameEditMode(fnEntity, btn);
  }
});


registerActionHandler('extend-fn', (btn, e, host) => {
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (fnEntity && typeof enterExtendEditMode === 'function') {
    enterExtendEditMode(fnEntity, btn);
  }
});


registerActionHandler('delete-fn', (btn, e, host) => {
  // Destructive — confirm + cascade + reload via initGraph,
  // mirroring the legacy in-card ✕ behaviour. `withBusy`
  // surfaces the deletion as a top-bar banner while it runs.
  e.preventDefault();
  e.stopPropagation();
  const fnId = btn.dataset.fnId || btn.closest('[data-fn-id]')?.dataset.fnId;
  const fnEntity = lookups?.fnMap?.get(fnId);
  if (!fnEntity) return;
  const display = (typeof getQualifiedFnName === 'function')
                ? getQualifiedFnName(fnEntity)
                : (fnEntity.name || 'this fn');
  if (!confirm('Delete fn "' + display + '"? '
               + 'Bindings that reference it will fail to load.')) return;
  const opKey = 'delete-fn:' + fnEntity.id;
  if (typeof isOpInflight === 'function' && isOpInflight(opKey)) return;
  const work = async () => {
    try {
      const r = await deleteEntity('fn', fnEntity.id);
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
    withBusy(opKey, 'Deleting ' + display + '…', work);
  } else {
    work();
  }
});


// Viewport zoom/pan re-position — when the graph zooms or pans
// while a popover is open, the anchor's bounding rect moves AND
// changes scale, so the popover would otherwise stick to its old
// (now-wrong) position and size. Re-run positionRowActionsPopover
// against the live anchor on every zoom/pan tick. Lazy-bound the
// first time a popover is shown, so the listener doesn't fire
// before there's anything to reposition.
let rowActionsCyHandlersBound = false;
function ensureRowActionsCyHandlers() {
  if (rowActionsCyHandlersBound) return;
  const reposition = () => {
    if (!rowActionsPopoverEl || rowActionsPopoverEl.style.display === 'none') return;
    if (!rowActionsPopoverAnchor || !document.contains(rowActionsPopoverAnchor)) return;
    positionRowActionsPopover(rowActionsPopoverEl, rowActionsPopoverAnchor);
  };
  // The popover is anchored to the document, not the graph layer, so it has to
  // re-anchor itself whenever the viewport moves under it.
  rowActionsCyHandlersBound = gv.onViewportChange(reposition);
}
