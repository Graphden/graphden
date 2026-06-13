// Editor Value-Form — generic hiccup renderer + form runtime for the
// type-aware value-edit popover.
//
// The backend (`POST /api/value-form`) resolves a slot's type and
// returns `{ok, form: <hiccup>, value: <current>}`. This module is a
// DUMB interpreter: it renders ANY hiccup tree to DOM, fills the
// current value into the `data-*`-marked controls, collects the value
// back out on save, and dispatches to the existing binding / sequence
// write helpers. It has zero type knowledge — that lives entirely on
// the backend, which is what lets user-defined / Tier-2 form-fns
// render through the identical path.
//
// `data-*` contract emitted by the backend form-fns:
//   data-form-root   — outer element; carries data-binding-id /
//                      data-fn-id / data-slot-id / data-item-id
//   data-form-field  — presence marks a value-bearing control
//   data-field-path  — dotted path into a composite value; ''/absent
//                      means the whole value IS this one field
//   data-field-kind  — text | number | bool | keyword | json | enum
//
// Globals consumed: validateLiteralAgainstType (editor-literal-types),
// writeBindingFields / putSequenceItemValue (editor-edit-modes — both
// hoisted globals, safe to call at runtime regardless of load order).

// ============================================================================
// HICCUP -> DOM
// ============================================================================

// Render a JSON-transited hiccup node to a DOM Node.
//   element  : ["tag", attrs?, ...children]   (tag is a string)
//   fragment : [[...], [...], ...]            (array whose head is an array)
//   text     : string | number
// Falsy children (null / undefined / false) are skipped — backend
// conditionals render falsy. Built only via createElement /
// createTextNode — never innerHTML, so server hiccup cannot inject
// script.
function renderHiccup(node) {
  if (Array.isArray(node)) {
    // Fragment — a bare list of elements (head is itself an array).
    if (node.length > 0 && Array.isArray(node[0])) {
      const frag = document.createDocumentFragment();
      for (const child of node) appendHiccupChild(frag, child);
      return frag;
    }
    const tag = node[0];
    if (typeof tag !== 'string') return null;
    const el = document.createElement(tag);
    let i = 1;
    const maybeAttrs = node[1];
    if (maybeAttrs && typeof maybeAttrs === 'object' && !Array.isArray(maybeAttrs)) {
      applyHiccupAttrs(el, maybeAttrs);
      i = 2;
    }
    for (; i < node.length; i++) appendHiccupChild(el, node[i]);
    return el;
  }
  if (node === null || node === undefined || node === false) return null;
  return document.createTextNode(String(node));
}

function appendHiccupChild(parent, child) {
  if (child === null || child === undefined || child === false) return;
  if (Array.isArray(child)) {
    // A nested seq of elements (e.g. a Clojure `for`) — flatten one level.
    if (child.length > 0 && Array.isArray(child[0])) {
      for (const c of child) appendHiccupChild(parent, c);
      return;
    }
    const rendered = renderHiccup(child);
    if (rendered) parent.appendChild(rendered);
    return;
  }
  parent.appendChild(document.createTextNode(String(child)));
}

function applyHiccupAttrs(el, attrs) {
  for (const k of Object.keys(attrs)) {
    const v = attrs[k];
    if (v === null || v === undefined) continue;
    if (k === 'class' || k === 'className') {
      el.className = v;
    } else if (k === 'style') {
      if (typeof v === 'string') {
        el.style.cssText = v;
      } else if (v && typeof v === 'object') {
        for (const sk of Object.keys(v)) el.style[sk] = v[sk];
      }
    } else if (k === 'for') {
      el.htmlFor = v;
    } else {
      el.setAttribute(k, v);
      // `value` / `checked` also need the live PROPERTY set, or an
      // already-created control won't actually show the attribute.
      if (k === 'value') el.value = v;
      else if (k === 'checked') el.checked = !!v;
    }
  }
}

// ============================================================================
// FORM RUNTIME — read / write / collect
// ============================================================================

// Coerce one control's raw value per its `data-field-kind`.
// Returns {value} or {value, error}.
function readFieldValue(el, kind) {
  if (kind === 'bool') return { value: !!el.checked };
  const raw = (el.value != null) ? el.value : '';
  const trimmed = raw.trim();
  if (kind === 'number') {
    if (trimmed === '') return { value: null };
    const n = Number(trimmed);
    return Number.isNaN(n) ? { value: raw, error: 'not a number' }
                           : { value: n };
  }
  if (kind === 'keyword') {
    if (trimmed === '') return { value: null };
    return { value: trimmed.charAt(0) === ':' ? trimmed : ':' + trimmed };
  }
  if (kind === 'json') {
    if (trimmed === '') return { value: null };
    try { return { value: JSON.parse(trimmed) }; }
    catch (_) { return { value: raw, error: 'invalid JSON' }; }
  }
  if (kind === 'any') {
    // Smart-parse: try JSON, fall back to the raw string. Never
    // errors — used by the legacy fallback control when /api/value-
    // form is unreachable.
    if (trimmed === '') return { value: null };
    try { return { value: JSON.parse(trimmed) }; }
    catch (_) { return { value: raw }; }
  }
  // text / enum / fallback
  return { value: raw };
}

// Write a value back into a control for its `data-field-kind`.
function writeFieldValue(el, kind, v) {
  if (kind === 'bool') { el.checked = !!v; return; }
  if (v === null || v === undefined) { el.value = ''; return; }
  if (kind === 'json') {
    el.value = (typeof v === 'string') ? v : JSON.stringify(v, null, 2);
  } else if (kind === 'keyword') {
    const s = String(v);
    el.value = (s.charAt(0) === ':') ? s : ':' + s;
  } else if (kind === 'number') {
    el.value = String(v);
  } else {
    el.value = (typeof v === 'string') ? v : JSON.stringify(v);
  }
}

function setFormPath(obj, segs, v) {
  let cur = obj;
  for (let i = 0; i < segs.length - 1; i++) {
    const s = segs[i];
    if (cur[s] == null || typeof cur[s] !== 'object') cur[s] = {};
    cur = cur[s];
  }
  cur[segs[segs.length - 1]] = v;
}

function getFormPath(obj, segs) {
  let cur = obj;
  for (const s of segs) {
    if (cur == null) return undefined;
    cur = cur[s];
  }
  return cur;
}

// Walk every `[data-form-field]` under `rootEl`, coerce each control,
// and assemble the value. A single empty-path field IS the scalar
// value; otherwise paths build a nested object (record / composite).
function collectFormValue(rootEl) {
  const fields = rootEl.querySelectorAll('[data-form-field]');
  const errors = [];
  const obj = {};
  let scalar;
  let hasScalar = false;
  for (const el of fields) {
    // Skip controls inside an inactive (hidden) union branch — only
    // the visible branch contributes to the value.
    if (el.closest('[hidden]')) continue;
    const path = el.getAttribute('data-field-path') || '';
    const kind = el.getAttribute('data-field-kind') || 'text';
    const res = readFieldValue(el, kind);
    if (res.error) errors.push((path || 'value') + ': ' + res.error);
    if (path === '') { scalar = res.value; hasScalar = true; }
    else setFormPath(obj, path.split('.'), res.value);
  }
  return { ok: errors.length === 0,
           value: hasScalar ? scalar : obj,
           errors };
}

// Inverse of collectFormValue — push the current value into the
// rendered controls. Inactive union branches (hidden) are skipped.
function fillFormValue(rootEl, value) {
  for (const el of rootEl.querySelectorAll('[data-form-field]')) {
    if (el.closest('[hidden]')) continue;
    const path = el.getAttribute('data-field-path') || '';
    const kind = el.getAttribute('data-field-kind') || 'text';
    const v = (path === '') ? value : getFormPath(value, path.split('.'));
    writeFieldValue(el, kind, v);
  }
}

// Wire each union branch-selector. The backend pre-sets `hidden` on
// inactive branches and `selected` on the active <option>; this just
// hooks the <select> so changing it swaps the visible branch.
function initUnions(rootEl) {
  for (const union of rootEl.querySelectorAll('[data-form-union]')) {
    const select = union.querySelector('[data-union-select]');
    if (!select) continue;
    const branches = union.querySelectorAll('[data-union-branch]');
    select.addEventListener('change', () => {
      for (const b of branches) {
        b.hidden = b.getAttribute('data-union-branch') !== select.value;
      }
    });
  }
}

// A multi-line <textarea> must keep Enter for newlines — stop it from
// reaching the popover skeleton's Enter-to-save listener. Ctrl/Cmd+
// Enter is left to bubble so power users can still save.
function installTextareaEnterGuard(rootEl) {
  for (const ta of rootEl.querySelectorAll('textarea')) {
    ta.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) e.stopPropagation();
    });
  }
}

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
    const r = await fetch('/api/value-form', {
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

// Tier 2 — mount custom widgets. Each `[data-form-widget]` node names
// a widget that a bundled JS module registered on
// `window.GraphdenFormWidgets`. The widget builds its own UI plus a
// hidden `[data-form-field]` input it keeps in sync, so collect/fill
// stay widget-agnostic. Unknown widget names render as an empty node.
function hydrateWidgets(rootEl, value) {
  const registry = window.GraphdenFormWidgets || {};
  for (const el of rootEl.querySelectorAll('[data-form-widget]')) {
    const name = el.getAttribute('data-form-widget');
    const widget = name ? registry[name] : null;
    if (!widget || typeof widget.mount !== 'function') continue;
    const path = el.getAttribute('data-field-path') || '';
    const v = (path === '') ? value : getFormPath(value, path.split('.'));
    try { widget.mount(el, v); } catch (_) {}
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
