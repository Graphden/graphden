// Editor Mismatch Explainer — click-driven popover that explains why
// a value or fn-ref doesn't fit a slot.
//
// Phase 2 closes the top UX hole identified in the audit: today the
// red `arg-overlay-mismatch` ring is the only feedback for a broken
// binding. New users see the ring but get no explanation of what's
// wrong or how to fix it. The native `title=` tooltip is hover-only
// and invisible on touch.
//
// This module renders a singleton popover that:
//   1. Opens on click (touch-friendly).
//   2. Persists until the user dismisses it (× or outside-click).
//   3. Surfaces the structured info a fix requires — expected type,
//      actual value (or fn-name + return type for refs), one-line
//      reason — plus a quick-action button to enter edit mode when
//      the binding is editable.
//
// Globals consumed: formatTypeHint, validateLiteralAgainstType,
// resolveArgType, enterArgValueEditMode, isAuthenticated,
// implementationFnIds, lookups, authFetch (for /api/types/compatible
// when we need to explain a ref-binding), slotTypeProvenance +
// appendResolutionSection (the shared "Resolved via" 4-tier chain,
// so a mismatch shows WHERE the expected type came from).

let mismatchExplainerEl = null;
let mismatchExplainerArg = null;
let mismatchExplainerAnchor = null;

function ensureMismatchExplainerEl() {
  if (mismatchExplainerEl) return mismatchExplainerEl;
  const el = document.createElement('div');
  el.className = 'mismatch-explainer';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Type mismatch explanation');
  document.body.appendChild(el);
  mismatchExplainerEl = el;
  return el;
}

// Post-swap binding for the three interactive surfaces the server
// hiccup emits — all rendering is server-side as of step 4, but
// click behaviour stays JS (it triggers other editor popovers /
// navigation, both of which are interactive components that don't
// fit a pure-render contract):
//
//   * `[data-explainer-close]` — close button (same convention the
//     effect-explainer partial uses).
//   * `a[data-fn-id]` — provenance source links → `selectFn(fnId)`
//     navigates and dismisses the popover. Same behaviour the
//     standalone provenance popover offers.
//   * `[data-edit-action]` — the Edit-value button → opens the inline
//     value-edit popover via `enterArgValueEditMode(arg, anchorEl)`.
//     The popover itself is JS-interactive (textarea + save / cancel),
//     not a pure render — keeping it here is correct.
function bindPostSwap(el, arg, anchorEl) {
  const close = el.querySelector('[data-explainer-close]');
  if (close) {
    close.addEventListener('click', (e) => {
      e.stopPropagation();
      hideMismatchExplainer();
    });
  }
  el.querySelectorAll('a[data-fn-id]').forEach((link) => {
    const fnId = link.getAttribute('data-fn-id');
    if (!fnId) return;
    link.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (typeof selectFn === 'function') {
        hideMismatchExplainer();
        selectFn(fnId);
      }
    });
  });
  const editBtn = el.querySelector('[data-edit-action]');
  if (editBtn && typeof enterArgValueEditMode === 'function') {
    editBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      hideMismatchExplainer();
      enterArgValueEditMode(arg, anchorEl);
    });
  }
}


// Fetch the server-rendered popover (header + Expected / Got /
// Reason rows + diff-leaves + provenance section + Edit-action
// button) and bind the three post-swap interactive surfaces (close,
// nav links, edit). Steps 1-4 complete — no rendering left JS-side,
// only click behaviour for editor-internal interactions.
async function showMismatchExplainer(arg, anchorEl) {
  if (!arg || !anchorEl) return;
  // Mutually exclusive with the inline-expand panel (same provenance
  // chain) — collapse it before swapping ours in.
  if (typeof hideAllInlineHosts === 'function') hideAllInlineHosts();
  // The partial requires a binding-id; for list-item rows we additionally
  // pass item-id so the server picks the per-position elem type. Skip
  // when neither is present (e.g. transient pre-binding overlays).
  const bindingId = arg['binding-id'];
  const itemId = arg['item-id'];
  if (!bindingId) return;
  const params = new URLSearchParams({ 'binding-id': bindingId });
  if (itemId) params.set('item-id', itemId);
  let html;
  try {
    const r = await authFetch('/partials/mismatch-explainer?' + params.toString());
    if (!r.ok) return;
    html = await r.text();
  } catch (_) {
    return;
  }
  const el = ensureMismatchExplainerEl();
  el.innerHTML = html;
  mismatchExplainerArg = arg;
  bindPostSwap(el, arg, anchorEl);
  el.classList.add('visible');
  el.style.display = '';
  anchorBelowClamped(el, anchorEl);
  mismatchExplainerAnchor = anchorEl;
}

function hideMismatchExplainer() {
  if (!mismatchExplainerEl) return;
  mismatchExplainerEl.classList.remove('visible');
  mismatchExplainerEl.style.display = 'none';
  mismatchExplainerArg = null;
  mismatchExplainerAnchor = null;
}

// Outside-pointerdown / Esc dismissal. The anchor allowance lets the
// user re-trigger the same explainer without an intermediate close.
installPopoverDismiss({
  getEl: () => mismatchExplainerEl,
  getAnchor: () => mismatchExplainerAnchor,
  isVisible: () => !!mismatchExplainerEl && mismatchExplainerEl.classList.contains('visible'),
  onDismiss: hideMismatchExplainer,
});

window.showMismatchExplainer = showMismatchExplainer;
