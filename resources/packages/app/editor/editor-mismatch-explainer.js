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
// when we need to explain a ref-binding).

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
  if (!implementationFnIds || !implementationFnIds.has(arg['fn-id'])) return false;
  return typeof isAuthenticated === 'function' && isAuthenticated();
}

// Pure render helper — takes already-resolved info and paints the
// popover. Split out from `showMismatchExplainer` so tests / probes
// can drive the visual without going through the live lookups
// chain. Real callers should prefer `showMismatchExplainer` so the
// arg-derived info stays consistent.
function renderMismatchExplainer(info, anchorEl) {
  if (!info || !anchorEl) return false;
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

function showMismatchExplainer(arg, anchorEl) {
  if (!arg || !anchorEl) return;
  if (typeof expectedSlotType !== 'function'
      || typeof validateLiteralAgainstType !== 'function'
      || typeof formatTypeHint !== 'function') return;
  const expected = expectedSlotType(arg);
  if (!expected) return;
  const v = arg.value;
  // Re-run the predicate the overlay uses so the message matches
  // the indicator. Doing it fresh (rather than caching) keeps the
  // explainer aligned even if the row's value has been edited live
  // since the indicator was painted.
  const result = validateLiteralAgainstType(v, expected);
  const editable = isEditableArg(arg)
                   && typeof enterArgValueEditMode === 'function';
  const info = {
    expected: formatTypeHint(expected),
    actual: formatActualLiteral(v),
    reason: result ? result.message : null,
    onEdit: editable ? () => enterArgValueEditMode(arg, anchorEl) : null,
    hint: editable ? null
                   : ((typeof isAuthenticated === 'function' && !isAuthenticated())
                      ? 'Sign in to edit values.'
                      : 'Open the owning fn to edit this value.'),
  };
  mismatchExplainerArg = arg;
  renderMismatchExplainer(info, anchorEl);
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
window.hideMismatchExplainer = hideMismatchExplainer;
window.renderMismatchExplainer = renderMismatchExplainer;
