// Graphden popover primitives — platform-shared client helpers (bundled into
// BOTH the editor and the standalone /assets/graphden-runtime.js, so a
// graph-composed user page can build info/form popovers with the same two
// pieces the editor uses). Pure DOM — no editor state, no cytoscape, no
// graphData; safe to load anywhere.
//
//   - anchorBelowClamped: place a popover below an anchor element, flipping
//     above when there's no room and clamping horizontally to stay on-screen.
//   - installPopoverDismiss: one document-level outside-pointerdown + Esc
//     handler that closes a popover.

// Place `el` below `anchorEl`, flipping above when there is no room and
// clamping horizontally into the viewport. `el` must already be in the DOM; it
// is rendered off-screen first so offsetWidth/Height can be measured before the
// final placement is written.
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
// `getEl` returns the popover root (or null if not built yet); `getAnchor`
// (optional) returns the current anchor — pointerdowns inside it are ignored so
// the trigger can re-open without an extra close; `isVisible` decides whether
// the popover currently counts as open; `onDismiss` closes it.
//
// Standardised on `pointerdown` (capture): `click` misses on iOS Safari for
// targets outside the popover, and `pointerdown` fires before focus changes.
// Call this exactly once per popover, at module load — the handlers are inert
// while the popover is hidden.
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
    if (e.key !== 'Escape' || !getEl() || !isVisible()) return;
    onDismiss();
    // Escape has no default action — this MARKS the key as consumed. The
    // interactive tutorial ends on Escape, and "the reader dismissed the
    // popover the lesson told them to open" must not read as "the reader
    // quit". A selector list of every dismissible surface was the previous
    // answer and it went stale the moment a new popover shipped: closing the
    // Packages panel killed the tour mid-lesson.
    e.preventDefault();
  });
}
