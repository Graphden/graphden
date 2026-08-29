// Graphden popover primitives — platform-shared client helpers (bundled into
// BOTH the editor and the standalone /assets/graphden-runtime.js, so a
// graph-composed user page can build info/form popovers with the same two
// pieces the editor uses). Pure DOM — no editor state, no cytoscape, no
// graphData; safe to load anywhere.
//
//   - anchorBelowClamped: place a popover below an anchor element, flipping
//     above when there's no room and clamping horizontally to stay on-screen.
//   - installPopoverDismiss: one document-level outside-pointerdown + Esc
//     handler that closes a popover. Options `trapFocus` / `getReturnFocus`
//     add the keyboard half: Tab stays inside while it is open, and focus
//     goes back to the trigger when Escape closes it.
//   - focusIntoDialog / returnFocusTo / focusableWithin / focusSafely: the
//     focus moves a dialog has to make itself, at the points only it knows
//     about — it opens, and it closes through paths (× button, Cancel,
//     submit) that never reach installPopoverDismiss.

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
// Every installed popover, so a CONTEXT SWITCH can sweep them: surface
// navigation (Build -> Organization, hash deep-links) fires no
// outside-pointerdown, and a popover that survives it floats over the new
// surface and blocks it (tutorial finding 2026-08-26: the Run popover sat
// on top of the Organization panel).
const _popoverRegistry = [];

function dismissAllPopovers() {
  for (const p of _popoverRegistry) {
    try { if (p.isVisible()) p.onDismiss(); } catch (_) { /* keep sweeping */ }
  }
}

// A pointer event inside the interactive tutorial's popover never counts as
// "outside": mid-step the reader clicks its copy-chips to grab the value the
// open popover (Run, a picker) is waiting for, and dismissing that popover
// turns every copy into "reopen and start over". Pointer twin of the Escape
// rule below. Ad-hoc dismissers that don't go through installPopoverDismiss
// consult this too. On pages without the tour (the standalone runtime bundle)
// the id never matches and this is a cheap no-op.
function pointerEventInTour(e) {
  const t = e.target;
  return !!(t && typeof t.closest === 'function' && t.closest('#gd-tour-pop'));
}

function installPopoverDismiss({ getEl, getAnchor, isVisible, onDismiss, trapFocus, getReturnFocus }) {
  _popoverRegistry.push({ isVisible, onDismiss });
  document.addEventListener('pointerdown', (e) => {
    const el = getEl();
    if (!el || !isVisible()) return;
    if (pointerEventInTour(e)) return;
    const t = e.target;
    const anchor = getAnchor ? getAnchor() : null;
    if (t && (el.contains(t) || anchor?.contains(t))) return;
    onDismiss();
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || !getEl() || !isVisible()) return;
    // Read the return target BEFORE dismissing: most popovers null their
    // anchor reference as they close.
    const back = trapFocus || getReturnFocus ? returnTargetOf(getEl(), getReturnFocus) : null;
    onDismiss();
    if (back) focusSafely(back);
    // Escape has no default action — this MARKS the key as consumed. The
    // interactive tutorial ends on Escape, and "the reader dismissed the
    // popover the lesson told them to open" must not read as "the reader
    // quit". A selector list of every dismissible surface was the previous
    // answer and it went stale the moment a new popover shipped: closing the
    // Packages panel killed the tour mid-lesson.
    e.preventDefault();
  });
  if (trapFocus) installTabTrap({ getEl, isVisible });
}


// ── Focus management ────────────────────────────────────────────────────────
//
// Lives here rather than in an editor module because this file is the earliest
// one in the bundle and carries no editor dependencies — the standalone
// runtime gets the same helpers.

/**
 * Everything that can take keyboard focus. `:not([disabled])` and the tabindex
 * filter keep out controls that are present but unreachable.
 */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ');

/** Focusable descendants of `root`, in DOM order, skipping hidden subtrees. */
function focusableWithin(root) {
  if (!root) return [];
  return Array.from(root.querySelectorAll(FOCUSABLE_SELECTOR)).filter(
    // offsetParent is null inside a display:none subtree — and also for
    // position:fixed elements, hence the second arm.
    (el) => el.offsetParent !== null || getComputedStyle(el).position === 'fixed',
  );
}

/** `focus()` with the preventScroll option, falling back where unsupported. */
function focusSafely(el) {
  if (!el || typeof el.focus !== 'function') return false;
  try {
    el.focus({ preventScroll: true });
  } catch (_) {
    el.focus();
  }
  return document.activeElement === el;
}

// Where focus should land when a dialog closes. Only used when the caller
// asked for it: `getAnchor` is NOT a safe default — editor-secrets.js passes a
// neighbouring popover there (so its trigger can re-open cleanly), and sending
// focus into a different popover is worse than leaving it alone.
function returnTargetOf(el, getReturnFocus) {
  if (!getReturnFocus) return null;
  // Only pull focus back if it is currently inside the closing dialog;
  // otherwise the user has already moved on and we would yank them back.
  if (el && !el.contains(document.activeElement)) return null;
  try {
    return getReturnFocus() || null;
  } catch (_) {
    return null;
  }
}

/**
 * Move focus into a dialog. Call this when it opens; popovers that already
 * focus their own search/first field do not need it.
 */
function focusIntoDialog(el) {
  if (!el) return false;
  const first = focusableWithin(el)[0];
  if (focusSafely(first)) return true;
  // Nothing focusable inside — park focus on the container so the screen
  // reader reads the dialog rather than staying behind it.
  if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
  return focusSafely(el);
}

/**
 * Hand focus back to `el` (typically the trigger). Safe to call with null.
 */
function returnFocusTo(el) {
  return focusSafely(el);
}

/**
 * Hide everything except `dialogEl` from assistive tech and pointer input,
 * or put it back.
 *
 * Only for true modals — the ones that claim `aria-modal="true"`. That
 * attribute is a promise that the rest of the page is unreachable; without
 * this the promise is a lie, and a screen reader happily walks out of the
 * dialog into content the user cannot see past the overlay.
 *
 * Operates on body's element children so it does not need to know the app's
 * container id. `inert` carries pointer + focus semantics; `aria-hidden` is
 * the fallback for engines without it.
 */
function setSiblingsInert(dialogEl, on) {
  if (!dialogEl || !document.body) return;
  for (const el of Array.from(document.body.children)) {
    if (el === dialogEl || el.contains(dialogEl)) continue;
    if (on) {
      el.setAttribute('inert', '');
      el.setAttribute('aria-hidden', 'true');
    } else {
      el.removeAttribute('inert');
      el.removeAttribute('aria-hidden');
    }
  }
}

/**
 * Keep Tab inside the dialog while it is open.
 *
 * Document-level and driven by `isVisible()`, so it needs no open/close
 * notification: it is inert whenever the dialog is not showing.
 */
function installTabTrap({ getEl, isVisible }) {
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Tab') return;
    const el = getEl();
    if (!el || !isVisible() || !el.contains(document.activeElement)) return;
    const items = focusableWithin(el);
    if (items.length === 0) {
      // A dialog with nothing focusable must still not leak Tab into the page
      // behind it.
      e.preventDefault();
      focusSafely(el);
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      focusSafely(last);
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      focusSafely(first);
    }
  }, true);
}
