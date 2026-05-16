// Editor Popover Base — shared scaffolding for the editor's singleton
// "info / form" popovers (effect-explainer, mismatch-explainer,
// create-type picker). Each such popover still owns its own DOM
// element and content rendering; this module supplies only the two
// pieces that were copy-pasted verbatim across all of them:
//
//   - anchorBelowClamped: place a popover below an anchor element,
//     flipping above and clamping horizontally to stay on-screen.
//   - installPopoverDismiss: one document-level outside-pointerdown +
//     Esc handler that closes a popover.
//
// NOT used by editor-row-actions.js (re-anchors / scales with the
// cytoscape zoom) or editor-overlay-type-expand.js's inline hosts (a
// per-path multi-host system) — those have genuinely different
// positioning needs and are intentionally left alone.

// Place `el` below `anchorEl`, flipping above when there is no room
// and clamping horizontally into the viewport. `el` must already be
// in the DOM; it is rendered off-screen first so offsetWidth/Height
// can be measured before the final placement is written.
function anchorBelowClamped(el, anchorEl, opts) {
  const margin = opts?.margin || 8;
  el.style.display = 'block';
  el.style.left = '0px';
  el.style.top = '-9999px';
  const w = el.offsetWidth || opts?.fallbackW || 280;
  const h = el.offsetHeight || opts?.fallbackH || 120;
  const r = anchorEl.getBoundingClientRect();
  let left = r.left;
  let top = r.bottom + margin;
  if (top + h + margin > window.innerHeight) {
    top = Math.max(margin, r.top - h - margin);
  }
  if (left + w + margin > window.innerWidth) {
    left = Math.max(margin, window.innerWidth - w - margin);
  }
  if (left < margin) left = margin;
  el.style.left = left + 'px';
  el.style.top = top + 'px';
}

// Install a document-level dismiss handler for a singleton popover.
// `getEl` returns the popover root (or null if not built yet);
// `getAnchor` (optional) returns the current anchor — pointerdowns
// inside it are ignored so the trigger can re-open without an extra
// close; `isVisible` decides whether the popover currently counts as
// open; `onDismiss` closes it.
//
// Standardised on `pointerdown` (capture): `click` misses on iOS
// Safari for targets outside the popover, and `pointerdown` fires
// before focus changes. Call this exactly once per popover, at module
// load — the handlers are inert while the popover is hidden.
function installPopoverDismiss({ getEl, getAnchor, isVisible, onDismiss }) {
  document.addEventListener('pointerdown', (e) => {
    const el = getEl();
    if (!el || !isVisible()) return;
    const t = e.target;
    const anchor = getAnchor ? getAnchor() : null;
    if (t && (el.contains(t) || anchor?.contains(t))) return;
    onDismiss();
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && getEl() && isVisible()) onDismiss();
  });
}
