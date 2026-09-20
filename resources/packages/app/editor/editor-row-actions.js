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
//
// The `data-action` handler registrations are
// editor-row-actions-handlers.js, loaded right after this file. This file
// is the popover LIFECYCLE (show / pin / fade / re-anchor) and the loader.

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
    fontSize: '0.6875rem',
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
    if (pointerEventInTour(e)) return;
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
    e.preventDefault();   // consumed — see graphden-popover.js
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

// Edge flags for a sequence-item row — the partial renders ↑ / ↓
// disabled at the chain's ends (an edge move is a server no-op).
// Flags ride the partial URL, so they also key the HTML cache.
function _seqEdgeParams(arg) {
  const items = lookups?.itemsByBinding?.get(arg['binding-id']) || [];
  const idx = items.findIndex((i) => i.id === arg['item-id']);
  if (idx < 0) return '';
  return (idx === 0 ? '&seq-first=true' : '')
       + (idx === items.length - 1 ? '&seq-last=true' : '');
}

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
      // Resolve through the nearest ancestor carrying an id, the same way
      // the click path below does. `host.dataset.fnId` is only set on some
      // hosts, so the hover path could open a tooltip with a null id — and
      // an Edit→Save against that fires PUT /api/entities/fn/null, which
      // the server rejects with a 400 the UI then reported as "check that
      // you're signed in" on a perfectly good session.
      entityId: btn.dataset.fnId
                || btn.closest('[data-fn-id]')?.dataset.fnId
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


// Post-swap tenancy gating for ▣ Apps — the core partial renders the row
// unconditionally (it can't know whether the addon is loaded); the client
// hides it off the same window.API probe the sidebar apps lens uses. Runs on
// cached renders too (the cache stores HTML with the row present).
function _applyAppsAvailabilityState(host) {
  const appsBtn = host.querySelector('[data-action="apps"]');
  if (appsBtn) appsBtn.hidden = !window.API?.api_orgs_apps;
}


function _applyAddMICompatibilityState(host) {
  // Post-swap hint on the + Add-MI button. `compatibleMIParentInfo` walks
  // the fns `lookups` happens to hold (the open subtree + loaded Explorer
  // rows), while the picker it opens searches the whole graph — so this
  // can never say "no compatible parent exists"; it used to, and disabled
  // the button on every card whose compatible partner was not loaded (a
  // sibling axis such as `:json-content-type` next to `:ok-response`).
  // Now it only names what a compatible parent looks like.
  const addMiBtn = host.querySelector('[data-action="add-mi-parent"]');
  if (!addMiBtn) return;
  addMiBtn.title = 'Add another parent (multi-inheritance) — a fn sharing this '
                 + 'one\'s base that sets args of its own; the picker searches the whole graph';
  addMiBtn.setAttribute('aria-label', addMiBtn.title);
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
            + (opts.useSiteArg?.['item-id']
                ? '&seq-item=true' + _seqEdgeParams(opts.useSiteArg)
                : '')
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
    _applyAppsAvailabilityState(host);
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
      _applyAppsAvailabilityState(h);
    }
  });
}


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
