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

function buildMismatchHeader(closeFn) {
  const head = document.createElement('div');
  head.className = 'mismatch-explainer-header';
  const title = document.createElement('span');
  title.className = 'mismatch-explainer-title';
  title.textContent = 'Type mismatch';
  head.appendChild(title);
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'mismatch-explainer-close';
  close.setAttribute('aria-label', 'Close mismatch explainer');
  close.textContent = '×';
  close.addEventListener('click', (e) => {
    e.stopPropagation();
    closeFn();
  });
  head.appendChild(close);
  return head;
}

function buildMismatchRow(label, valueText, opts) {
  const row = document.createElement('div');
  row.className = 'mismatch-explainer-row';
  if (opts?.cls) row.classList.add(opts.cls);
  const lbl = document.createElement('span');
  lbl.className = 'mismatch-explainer-label';
  lbl.textContent = label;
  const val = document.createElement('span');
  val.className = 'mismatch-explainer-value';
  val.textContent = valueText;
  row.appendChild(lbl);
  row.appendChild(val);
  return row;
}

// Format the actual value in a way the user can map back to what they
// see in the overlay. Strings get quoted, numbers stay bare, longer
// payloads get truncated. Mirrors what `arg-overlay`'s value text
// shows so the popover doesn't surprise the user with a different
// representation.
function formatActualLiteral(v) {
  if (v == null) return String(v);
  if (typeof v === 'string') {
    const s = v.length > 60 ? v.slice(0, 60) + '…' : v;
    return JSON.stringify(s);
  }
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  try {
    const s = JSON.stringify(v);
    return s.length > 60 ? s.slice(0, 60) + '…' : s;
  } catch (_) { return String(v); }
}

function isEditableArg(arg) {
  if (!arg) return false;
  if (!implementationFnIds?.has(arg['fn-id'])) return false;
  return typeof isAuthenticated === 'function' && isAuthenticated();
}

// Pure render helper — takes already-resolved info and paints the
// popover. Split out from `showMismatchExplainer` so tests / probes
// can drive the visual without going through the live lookups
// chain. Real callers should prefer `showMismatchExplainer` so the
// arg-derived info stays consistent.
function renderMismatchExplainer(info, anchorEl) {
  if (!info || !anchorEl) return false;
  // Mutually exclusive with the inline-expand panel — both render
  // the same "Resolved via" provenance chain. Collapse inline-expand
  // when the mismatch explainer takes over.
  if (typeof hideAllInlineHosts === 'function') hideAllInlineHosts();
  const el = ensureMismatchExplainerEl();
  el.textContent = '';
  el.appendChild(buildMismatchHeader(hideMismatchExplainer));
  if (info.expected != null) {
    el.appendChild(buildMismatchRow('Expected', info.expected,
                                    { cls: 'mismatch-row-expected' }));
  }
  if (info.actual != null) {
    el.appendChild(buildMismatchRow('Got', info.actual,
                                    { cls: 'mismatch-row-got' }));
  }
  if (info.reason) {
    el.appendChild(buildMismatchRow('Reason', info.reason,
                                    { cls: 'mismatch-row-reason' }));
  }
  // Leaf-level disagreement listing — points the user at the EXACT
  // failing field / element instead of leaving them to spot the diff
  // between two structural types by eye. Skipped when the diff has
  // ≤ 1 entry that's just the top-level (formatTypeHint already
  // renders that case).
  if (info.diffLeaves && info.diffLeaves.length > 0) {
    const leafSection = document.createElement('div');
    leafSection.className = 'mismatch-explainer-leaves';
    const head = document.createElement('div');
    head.className = 'mismatch-explainer-leaves-head';
    head.textContent = info.diffLeaves.length === 1 ? 'At' : 'Disagreements';
    leafSection.appendChild(head);
    for (const leaf of info.diffLeaves.slice(0, 6)) {
      const row = document.createElement('div');
      row.className = 'mismatch-explainer-leaf';
      const pathEl = document.createElement('span');
      pathEl.className = 'mismatch-explainer-leaf-path';
      pathEl.textContent = leaf.path || '(top)';
      const sep = document.createElement('span');
      sep.className = 'mismatch-explainer-leaf-sep';
      sep.textContent = ': expected ';
      const expEl = document.createElement('span');
      expEl.className = 'mismatch-explainer-leaf-expected';
      expEl.textContent = (typeof formatTypeHint === 'function')
                          ? formatTypeHint(leaf.expected)
                          : JSON.stringify(leaf.expected);
      const sep2 = document.createElement('span');
      sep2.className = 'mismatch-explainer-leaf-sep';
      sep2.textContent = ', got ';
      const actEl = document.createElement('span');
      actEl.className = 'mismatch-explainer-leaf-actual';
      actEl.textContent = typeof leaf.actual === 'string'
                          ? leaf.actual
                          : JSON.stringify(leaf.actual);
      row.appendChild(pathEl);
      row.appendChild(sep);
      row.appendChild(expEl);
      row.appendChild(sep2);
      row.appendChild(actEl);
      leafSection.appendChild(row);
    }
    if (info.diffLeaves.length > 6) {
      const more = document.createElement('div');
      more.className = 'mismatch-explainer-leaf-more';
      more.textContent = `… +${info.diffLeaves.length - 6} more`;
      leafSection.appendChild(more);
    }
    el.appendChild(leafSection);
  }
  // "Resolved via" — surface WHERE the expected type came from (slot
  // declaration / binding type-override / backward-unified return /
  // bound-fn return type). Answers "why is THIS the expected type?"
  // right at the mismatch, reusing the same 4-tier provenance the
  // inline type-expand panel renders. Skipped when provenance is
  // unavailable (list-items, slot-less args) or the renderer isn't
  // loaded.
  if (info.provenance && typeof appendResolutionSection === 'function') {
    // Pass onNavigate so the ancestor / source-fn rows in "Inherited
    // via" and "Resolved via" become clickable links — clicking jumps
    // to the fn that pinned the constraint and dismisses the mismatch
    // popover. Same behaviour the dedicated provenance popover offers,
    // so users trace upstream from a mismatch the same way they trace
    // upstream from the `↳` badge.
    appendResolutionSection(el, info.provenance, {
      onNavigate: (fnId) => {
        if (typeof selectFn === 'function' && fnId) {
          hideMismatchExplainer();
          selectFn(fnId);
        }
      },
    });
  }
  const actions = document.createElement('div');
  actions.className = 'mismatch-explainer-actions';
  if (typeof info.onEdit === 'function') {
    const editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'mismatch-explainer-btn';
    editBtn.textContent = info.editLabel || 'Edit value';
    editBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      hideMismatchExplainer();
      info.onEdit();
    });
    actions.appendChild(editBtn);
  } else if (info.hint) {
    const hint = document.createElement('span');
    hint.className = 'mismatch-explainer-hint';
    hint.textContent = info.hint;
    actions.appendChild(hint);
  }
  el.appendChild(actions);

  el.classList.add('visible');
  anchorBelowClamped(el, anchorEl);
  mismatchExplainerAnchor = anchorEl;
  return true;
}

// Append the JS-only Edit-action button (step 4 will migrate this
// too). Header / Expected / Got / Reason / diff-leaves / provenance
// are all server-rendered as of step 3.
//
// Also wires the nav links the provenance section emits — every `<a
// data-fn-id=…>` in the server hiccup gets a click handler that
// navigates to the source fn (same behaviour the standalone
// provenance popover offers, so users trace upstream from a mismatch
// the same way they trace upstream from the `↳` badge).
function appendJsOnlySections(el, arg, anchorEl) {
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
  const editable = isEditableArg(arg)
                   && typeof enterArgValueEditMode === 'function';
  const actions = document.createElement('div');
  actions.className = 'mismatch-explainer-actions';
  if (editable) {
    const editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'mismatch-explainer-btn';
    editBtn.textContent = 'Edit value';
    editBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      hideMismatchExplainer();
      enterArgValueEditMode(arg, anchorEl);
    });
    actions.appendChild(editBtn);
  } else {
    const hint = document.createElement('span');
    hint.className = 'mismatch-explainer-hint';
    hint.textContent = (typeof isAuthenticated === 'function' && !isAuthenticated())
                       ? 'Sign in to edit values.'
                       : 'Open the owning fn to edit this value.';
    actions.appendChild(hint);
  }
  el.appendChild(actions);
  // Server-emitted close button carries `data-explainer-close` —
  // bind dismissal the same way the effect-explainer partial does.
  const close = el.querySelector('[data-explainer-close]');
  if (close) {
    close.addEventListener('click', (e) => {
      e.stopPropagation();
      hideMismatchExplainer();
    });
  }
}


// Fetch the server-rendered shell (header + Expected / Got / Reason
// rows) and hydrate it with the JS-only sections (diff-leaves +
// provenance + Edit-action). Step 1 of the JS→graph migration; steps
// 2-4 push diff-leaves / provenance / edit-button server-side too.
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
  appendJsOnlySections(el, arg, anchorEl);
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
window.renderMismatchExplainer = renderMismatchExplainer;
