// Editor Value-Form — the editor-COUPLED half of the type-aware value-edit
// popover. The generic form runtime (renderHiccup + collect/fill/union/widget)
// lives in web/runtime/graphden-forms.js (loaded before this module and shared
// with user pages); this module is the editor glue: fetch /api/value-form,
// live-validate against the slot type, orchestrate render, and save through the
// binding / sequence write helpers.
//
// The backend (`POST /api/value-form`) resolves a slot's type and returns
// `{ok, form: <hiccup>, value: <current>}`; the shared runtime is a DUMB
// interpreter of that hiccup, which is what lets user-defined / Tier-2 form-fns
// render through the identical path.
//
// `data-*` contract emitted by the backend form-fns:
//   data-form-root   — outer element; carries data-binding-id /
//                      data-fn-id / data-slot-id / data-item-id
//   data-form-field  — presence marks a value-bearing control
//   data-field-path  — dotted path into a composite value; ''/absent
//                      means the whole value IS this one field
//   data-field-kind  — text | number | bool | keyword | json | edn | enum
//
// Globals consumed: validateLiteralAgainstType (editor-literal-types),
// writeBindingFields / putSequenceItemValue (editor-edit-modes — both
// hoisted globals, safe to call at runtime regardless of load order).


// Live ✓/✗ status — reuses the `.arg-value-edit-status` styling. Field
// errors (bad JSON / non-numeric) win; otherwise the assembled value
// is checked against the slot's expected type. Composite forms are
// validated field-level only — `validateLiteralAgainstType` is a
// scalar/literal mirror, the server stays authoritative on save.
function installFormLiveValidation(hostEl, expected, statusEl) {
  if (!statusEl) return;
  const update = () => {
    const root = hostEl.querySelector('[data-form-root]') || hostEl;
    const collected = collectFormValue(root);
    if (!collected.ok) {
      statusEl.textContent = '✗ ' + collected.errors[0];
      statusEl.classList.add('err');
      statusEl.classList.remove('ok');
      return;
    }
    if (expected && typeof validateLiteralAgainstType === 'function') {
      const r = validateLiteralAgainstType(collected.value, expected);
      statusEl.textContent = (r.ok ? '✓ ' : '✗ ') + (r.message || '');
      statusEl.classList.toggle('ok', !!r.ok);
      statusEl.classList.toggle('err', !r.ok);
    } else {
      statusEl.textContent = '';
      statusEl.classList.remove('ok', 'err');
    }
  };
  hostEl.addEventListener('input', update);
  hostEl.addEventListener('change', update);
  setTimeout(update, 0);
}

// ============================================================================
// FETCH + RENDER + SAVE
// ============================================================================

// POST the slot identifiers to /api/value-form. Read-only endpoint —
// plain fetch, no auth. Returns the `{ok, form, value}` payload, or
// null on any network / HTTP failure (caller falls back).
async function fetchValueForm(arg) {
  const body = {};
  if (arg['binding-id']) body['binding-id'] = arg['binding-id'];
  if (arg['fn-id'])      body['fn-id']      = arg['fn-id'];
  if (arg['slot-id'])    body['slot-id']    = arg['slot-id'];
  if (arg['item-id'])    body['item-id']    = arg['item-id'];
  try {
    const r = await fetch(API.api_value_form, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!r.ok) {
      // eslint-disable-next-line no-console
      console.error('value-form fetch HTTP', r.status, r.statusText);
      return null;
    }
    return await r.json();
  } catch (err) {
    // Silent on the wire was hiding real failures (network drops,
    // CORS mishaps, JSON parse errors) — caller renders a generic
    // "Could not load the form" but DevTools had nothing. Surface
    // so a triage session can see the real cause without re-running.
    // eslint-disable-next-line no-console
    console.error('value-form fetch threw', err);
    return null;
  }
}


// Render a fetched value-form payload into `hostEl` (replacing any
// spinner), fill the current value, mount custom widgets, and wire
// textarea-Enter + live validation. Returns true on success.
// `readOnly` disables every control — the read-only viewer reuses
// this exact path.
function renderValueForm(hostEl, payload, opts) {
  const options = opts || {};
  hostEl.textContent = '';
  if (!payload || payload.ok === false) {
    const err = document.createElement('div');
    err.className = 'value-form-error';
    err.textContent = payload?.error || 'Could not load the form.';
    hostEl.appendChild(err);
    return false;
  }
  const dom = renderHiccup(payload.form);
  if (!dom) {
    hostEl.appendChild(document.createTextNode('Empty form.'));
    return false;
  }
  hostEl.appendChild(dom);
  const root = hostEl.querySelector('[data-form-root]') || hostEl;
  initUnions(root);
  fillFormValue(root, payload.value);
  hydrateWidgets(root, payload.value);
  if (options.readOnly) {
    for (const el of root.querySelectorAll(
           '[data-form-field], [data-union-select], [data-form-widget] input,'
           + ' [data-form-widget] select, [data-form-widget] button')) {
      el.disabled = true;
    }
  } else {
    installTextareaEnterGuard(hostEl);
    installFormLiveValidation(hostEl, options.expected, options.statusEl);
    // Skip hidden inputs — a widget owns a hidden `[data-form-field]`
    // that is never a sensible focus target.
    const first = hostEl.querySelector('input:not([type=hidden]), textarea, select');
    if (first) { try { first.focus(); } catch (_) {} }
  }
  return true;
}

// Collect the form value and persist it through the EXISTING write
// helpers — composite literal → one `binding.value`, sequence item →
// its own endpoint. The value is already typed by `data-field-kind`,
// so it is NOT re-smart-parsed before the write.
async function saveFormValue(arg, hostEl) {
  const root = hostEl.querySelector('[data-form-root]') || hostEl;
  // Guard the gap between popover-open and the form arriving — a Save
  // click while only the spinner is mounted must not persist `{}`.
  if (root.querySelectorAll('[data-form-field]').length === 0) {
    return { ok: false, error: 'The form is still loading.' };
  }
  const collected = collectFormValue(root);
  if (!collected.ok) {
    return { ok: false, error: collected.errors[0] || 'Invalid value.' };
  }
  const value = collected.value;
  if (arg?.['item-id']) {
    if (typeof putSequenceItemValue === 'function') {
      return await putSequenceItemValue(arg['item-id'], value);
    }
    return { ok: false, error: 'Cannot save — editor not ready.' };
  }
  if (typeof writeBindingFields === 'function') {
    return await writeBindingFields(arg, { value: JSON.stringify(value) });
  }
  return { ok: false, error: 'Cannot save — editor not ready.' };
}

// ============================================================================
// READ-ONLY VIEWER
// ============================================================================
//
// A structurally read-only arg row (surfaced via an ancestor
// expansion) has no edit popover, and a long value is truncated on
// the canvas. `openValueViewer` shows the SAME type-aware form,
// fetched from /api/value-form, with every control disabled — a
// structured, full-value, read-only display. Singleton info-popover,
// same scaffolding as editor-mismatch-explainer.

let valueViewerEl = null;
let valueViewerAnchor = null;

function hideValueViewer() {
  if (!valueViewerEl) return;
  valueViewerEl.classList.remove('visible');
  valueViewerEl.style.display = 'none';
  valueViewerAnchor = null;
}

function ensureValueViewerEl() {
  if (valueViewerEl) return valueViewerEl;
  const el = document.createElement('div');
  el.className = 'value-viewer';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Value viewer');
  document.body.appendChild(el);
  valueViewerEl = el;
  return el;
}

async function openValueViewer(arg, anchorEl) {
  if (!arg || !anchorEl) return;
  const el = ensureValueViewerEl();
  el.textContent = '';

  const head = document.createElement('div');
  head.className = 'value-viewer-header';
  const title = document.createElement('span');
  title.className = 'value-viewer-title';
  title.textContent = arg.name ? arg.name + ' — value' : 'Value';
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'value-viewer-close';
  close.setAttribute('aria-label', 'Close value viewer');
  close.textContent = '×';
  close.addEventListener('click', (e) => { e.stopPropagation(); hideValueViewer(); });
  head.appendChild(title);
  head.appendChild(close);
  el.appendChild(head);

  const host = document.createElement('div');
  host.className = 'value-form-host';
  const loading = document.createElement('div');
  loading.className = 'value-form-loading';
  loading.textContent = 'Loading…';
  host.appendChild(loading);
  el.appendChild(host);

  el.classList.add('visible');
  if (typeof anchorBelowClamped === 'function') anchorBelowClamped(el, anchorEl);
  valueViewerAnchor = anchorEl;

  const payload = await fetchValueForm(arg);
  // The user may have dismissed the viewer mid-fetch.
  if (!valueViewerEl?.classList.contains('visible')) return;
  renderValueForm(host, payload, { readOnly: true });
  // The form changed the popover's height — re-clamp to the viewport.
  if (typeof anchorBelowClamped === 'function') anchorBelowClamped(el, anchorEl);
}

// Outside-pointerdown / Esc dismissal — same contract as the other
// singleton popovers. `getAnchor` lets a re-click on the same row
// reopen without an intervening close.
installPopoverDismiss({
  getEl: () => valueViewerEl,
  getAnchor: () => valueViewerAnchor,
  isVisible: () => !!valueViewerEl && valueViewerEl.classList.contains('visible'),
  onDismiss: hideValueViewer,
});
