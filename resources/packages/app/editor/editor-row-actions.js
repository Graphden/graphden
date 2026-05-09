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
// The popover scales with the cytoscape zoom so it matches the
// scaled-up card chrome — the in-card icons all live inside an
// overlay that gets `transform: scale(zoom)` applied (see
// editor-overlays.js positionOverlays). Without this match the
// popover stays at base 15-px icons while the trigger doubles in
// size at 200 % zoom, which reads as broken.
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
  const zoom = (typeof cy !== 'undefined' && cy && typeof cy.zoom === 'function')
               ? cy.zoom() : 1;
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
  build(el);
  rowActionsPopoverAnchor = anchorEl;
  anchorEl.setAttribute('aria-expanded', 'true');
  // Fade-in: hide opacity through the position-measurement step
  // (which flips display to inline-block to read offsetWidth) and
  // then unfade in the next frame so the transition catches.
  el.style.opacity = '0';
  positionRowActionsPopover(el, anchorEl);
  requestAnimationFrame(() => { el.style.opacity = '1'; });
  ensureRowActionsCyHandlers();
  // Move focus into the popover when it opens via keyboard activation
  // (Enter/Space on the trigger leaves focus on the trigger itself).
  // Only auto-focus on sticky open — hover-show shouldn't grab focus
  // from whatever the user was looking at.
  if (rowActionsPopoverSticky) {
    const first = el.querySelector('button, a[href], [tabindex]:not([tabindex="-1"])');
    if (first) {
      try { first.focus({ preventScroll: true }); } catch (_) { first.focus(); }
    }
  }
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

// Cytoscape zoom/pan re-position — when the canvas zooms or pans
// while a popover is open, the anchor's bounding rect moves AND
// changes scale, so the popover would otherwise stick to its old
// (now-wrong) position and size. Re-run positionRowActionsPopover
// against the live anchor on every zoom/pan tick. Lazy-bound the
// first time a popover is shown, so the listener doesn't fire
// before there's anything to reposition.
let rowActionsCyHandlersBound = false;
function ensureRowActionsCyHandlers() {
  if (rowActionsCyHandlersBound) return;
  if (typeof cy === 'undefined' || !cy || typeof cy.on !== 'function') return;
  rowActionsCyHandlersBound = true;
  const reposition = () => {
    if (!rowActionsPopoverEl || rowActionsPopoverEl.style.display === 'none') return;
    if (!rowActionsPopoverAnchor || !document.contains(rowActionsPopoverAnchor)) return;
    positionRowActionsPopover(rowActionsPopoverEl, rowActionsPopoverAnchor);
  };
  cy.on('zoom pan', reposition);
}
