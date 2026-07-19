// Graphden form runtime — platform-shared client helpers (bundled into BOTH the
// editor and the standalone /assets/graphden-runtime.js). A graph-composed user
// page can render a server-sent hiccup form, collect/fill its value, wire union
// branch-switching, and mount custom widgets — WITHOUT the editor. Pure DOM +
// the `window.GraphdenFormWidgets` registry; no cy / graphData / editor-state
// and no /api/value-form coupling (that dispatch + the save path live in the
// editor's editor-value-form.js, which consumes these primitives).
//
// Exposed globals: renderHiccup, collectFormValue, fillFormValue, initUnions,
// installTextareaEnterGuard, hydrateWidgets (+ their helpers).
//
// SECURITY: renderHiccup builds only via createElement / createTextNode — never
// innerHTML — so server-sent hiccup cannot inject <script>. (Attrs
// ARE applied verbatim via setAttribute, incl. on* — server hiccup
// is the trust boundary, not this renderer.)

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
// <script>. (Attributes ARE applied verbatim via setAttribute, incl.
// on* handlers — the trusted-server hiccup is the trust boundary,
// not this renderer.)
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
  if (v === null || v === undefined) {
    // A <select> (enum) has no blank option, so `value = ''` sets
    // selectedIndex -1 → a blank dropdown that then fails validation.
    // Leave its natural default (the first, valid, member) instead.
    if (el.tagName !== 'SELECT') el.value = '';
    return;
  }
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
