// Editor Create-Type — popover-driven flow for creating type-rows
// (refinement, record, union, variant, list) from the sidebar.
//
// Surface:
//   1. Per-namespace `+ Type ▾` button (wired from editor-sidebar.js)
//      opens a small kind-picker popover.
//   2. Kind-picker shows 5 options; clicking one swaps the popover
//      to the kind-specific form.
//   3. Form per kind:
//        refinement: name + base-type (text + datalist) + constraint
//                    DSL text (`(:> 0)` / `(:and (:>= 0) (:<= 100))`).
//        list:       name + element-type.
//        union:      name + comma-separated branch types.
//        variant:    name + lines of `tag: type`.
//        record:     name + lines of `field: type`.
//      The name (and description) is ONE field above the kind tabs; only what
//      sits below the strip is per kind.
//   4. Submit POSTs to the right endpoint:
//        refinement / union / variant → POST /api/entities/fn
//        list → POST /api/types/list
//        record → POST /api/types/record
//      On success: close popover, `initGraph()` to refresh, select
//      the new entity by name.
//
// Globals consumed: graphData, richTypes, authMutate, initGraph,
// selectFnByName, hideAllPopovers (optional).

let typeCreatePopoverEl = null;
let typeCreateContext = null; // {nsId, nsPath, anchorEl}

function hideTypeCreatePopover() {
  if (typeCreatePopoverEl) {
    typeCreatePopoverEl.style.display = 'none';
    typeCreatePopoverEl.textContent = '';
  }
  typeCreateContext = null;
}

// Outside-pointerdown / Esc dismissal — the anchor allowance lets the
// trigger re-open the popover without an intermediate close.
installPopoverDismiss({
  getEl: () => typeCreatePopoverEl,
  getAnchor: () => typeCreateContext?.anchorEl,
  isVisible: () => !!typeCreatePopoverEl && typeCreatePopoverEl.style.display !== 'none',
  onDismiss: hideTypeCreatePopover,
  // A create form: Tab belongs inside it, and Escape hands the keyboard
  // back to the `+` that opened it.
  trapFocus: true,
  getReturnFocus: () => typeCreateContext?.anchorEl,
});

function ensureTypeCreatePopover() {
  if (typeCreatePopoverEl) return typeCreatePopoverEl;
  const el = document.createElement('div');
  el.className = 'type-create-popover';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Create type');
  document.body.appendChild(el);
  typeCreatePopoverEl = el;
  return el;
}

// Entry point — called from the sidebar's `+ Type` button. Opens
// the unified create form at the refinement tab by default; the
// user switches via the tab strip at the top of the form, which
// saves a click compared to the separate kind-picker step we used
// before.
function openTypeCreatePicker(parentNsId, parentNsPath, anchorEl) {
  if (typeof ensureAuth === 'function' && !ensureAuth()) return;
  ensureTypeCreatePopover();
  typeCreateContext = { nsId: parentNsId, nsPath: parentNsPath, anchorEl };
  showTypeCreateForm('refinement');
}

// Server-rendered type-name datalist — names + kind labels come from
// `GET /partials/type-name-datalist` (named type-rows classified by
// the server's `compute-fn-role` + the canonical primitives set; the
// hand-copied primitive lists and the per-name fnMap kind scan are
// gone). Refetched every popover open so freshly created / deleted
// type-rows show up. Returns the id synchronously — an `<input
// list=…>` may reference the id before the node lands; the browser
// starts autocompleting once it mounts (~one round-trip later).
function buildTypeNameDatalist() {
  const id = 'type-create-typename-list';
  authFetch('/partials/type-name-datalist')
    .then((r) => (r.ok ? r.text() : null))
    .then((html) => {
      if (!html) return;
      const prev = document.getElementById(id);
      if (prev) prev.remove();
      const probe = document.createElement('div');
      probe.innerHTML = html;
      const dl = probe.querySelector('datalist');
      if (dl) document.body.appendChild(dl);
    })
    .catch(() => {});
  return id;
}

// The graph owns the shell, kind tabs and stable controls. Per-kind constraint
// builders are native typing-speed editors; the datalist is a server partial.
function showTypeCreateForm(kind) {
  const el = typeCreatePopoverEl;
  if (!el) return;
  el.textContent = '';
  const ctx = typeCreateContext;
  if (!ctx) return;
  const listId = buildTypeNameDatalist();
  const editing = !!ctx.editFnId;

  // The selected authoring graph owns the stable shell and tab presentation.
  // Native controls retain their live nodes until an explicit kind switch;
  // kind-specific editors remain local because they update at typing speed.
  const shell = window.gdAuthoringTypeShell(kind, editing, ctx.nsPath || '',
    ctx.prefill?.name || '', ctx.prefill?.description || '');
  el.replaceChildren(shell);
  const form = shell.querySelector('.type-create-form');
  const nameIn = form.querySelector('input.type-create-input');
  const descIn = form.querySelector('textarea.type-create-textarea');
  const errEl = form.querySelector('.type-create-error');
  const submit = form.querySelector('.type-create-submit');
  nameIn.required = true;
  nameIn.value = ctx.prefill?.name || '';
  descIn.value = ctx.prefill?.description || '';
  errEl.style.display = 'none';
  form.addEventListener('submit', (e) => e.preventDefault());
  for (const button of [shell.querySelector('.type-create-close'), form.querySelector('.type-create-back')]) {
    button.addEventListener('click', (e) => { e.stopPropagation(); hideTypeCreatePopover(); });
  }
  for (const tab of form.querySelectorAll('.type-create-tab')) {
    tab.addEventListener('click', (e) => {
      e.stopPropagation();
      if (tab.dataset.kind === kind) return;
      const savedPrefill = typeCreateContext.prefill;
      typeCreateContext.prefill = Object.assign({}, savedPrefill || {}, {
        name: nameIn.value || '', description: descIn.value || '',
      });
      showTypeCreateForm(tab.dataset.kind);
      typeCreateContext.prefill = savedPrefill;
    });
  }
  const extras = buildKindFields(kind, listId, ctx.prefill);
  form.querySelector('.type-create-extra-host').replaceWith(extras.el);

  submit.addEventListener('click', async (e) => {
    e.preventDefault();
    e.stopPropagation();
    errEl.style.display = 'none';
    submit.disabled = true;
    try {
      const name = nameIn.value.trim();
      if (!name) throw new Error('Name required');
      const payload = extras.collect(name);
      payload.description = descIn.value.trim();
      if (editing) {
        await putTypeEdit(kind, ctx.editFnId, name, payload);
      } else {
        await postTypeCreate(kind, ctx.nsId, name, payload);
      }
      hideTypeCreatePopover();
      if (typeof initGraph === 'function') await initGraph();
      if (typeof selectJustCreatedFn === 'function') await selectJustCreatedFn(name);
    } catch (err) {
      errEl.textContent = err?.message || String(err);
      errEl.style.display = 'block';
      submit.disabled = false;
    }
  });
  anchorBelowClamped(typeCreatePopoverEl, ctx.anchorEl);
  // Auto-focus first input.
  setTimeout(() => nameIn.focus(), 0);
}

// ---------- per-kind field builders -----------------------------------

function openTypeEditForm(fnId, anchorEl) {
  if (typeof ensureAuth === 'function' && !ensureAuth()) return;
  const fn = (typeof lookups === 'object' && lookups?.fnMap)
    ? lookups.fnMap.get(fnId) : null;
  if (!fn) return;
  const kind = roleToKind(fn.role);
  if (!kind) {
    alert('Editing this type-row kind is not supported yet.');
    return;
  }
  const prefill = buildPrefillFromFn(fn, kind);
  const el = ensureTypeCreatePopover();
  typeCreateContext = {
    nsId: fn['namespace-id'] || null,
    nsPath: null,
    anchorEl,
    editFnId: fnId,
    prefill,
  };
  el.textContent = '';
  showTypeCreateForm(kind);
}

function roleToKind(role) {
  switch (role) {
    case 'refinement': case ':refinement': return 'refinement';
    case 'union':      case ':union':      return 'union';
    case 'variant':    case ':variant':    return 'variant';
    case 'list':       case ':list':       return 'list';
    case 'record':     case ':record':     return 'record';
    default: return null;
  }
}

function fnIdToTypeName(fnId) {
  if (!fnId || !lookups?.fnMap) return null;
  const f = lookups.fnMap.get(fnId);
  return f?.name || null;
}

function constraintMemberToName(x) {
  // Constraints arrive from the backend keywordised — :null, :text,
  // refs etc. — but the form takes plain names. Strip the leading
  // ":" character that JSON encoding preserves.
  if (x == null) return '';
  if (typeof x === 'string') return x.replace(/^:/, '');
  return String(x);
}

function buildPrefillFromFn(fn, kind) {
  const base = { name: fn.name || '', description: fn.description || '' };
  if (kind === 'refinement') {
    return Object.assign(base, {
      base: fnIdToTypeName(fn['base-fn-id']) || '',
      // `constraint` round-trips through parse-fn-from-form's JSON
      // path: store as canonical JSON so the same code that handles
      // create handles edit.
      constraint: fn.constraint ? JSON.stringify(fn.constraint) : '',
    });
  }
  if (kind === 'list') {
    return Object.assign(base, {
      element: fnIdToTypeName(fn['element-fn-id']) || '',
    });
  }
  if (kind === 'union') {
    const c = fn.constraint || [];
    const branches = (Array.isArray(c) ? c.slice(1) : [])
      .map(constraintMemberToName).filter(Boolean);
    return Object.assign(base, { branches: branches.join(', ') });
  }
  if (kind === 'variant') {
    const c = fn.constraint || [];
    const xs = Array.isArray(c) ? c.slice(1) : [];
    const lines = [];
    for (let i = 0; i + 1 < xs.length; i += 2) {
      lines.push(constraintMemberToName(xs[i])
                 + ': ' + constraintMemberToName(xs[i + 1]));
    }
    return Object.assign(base, { branches: lines.join('\n') });
  }
  if (kind === 'record') {
    // Render fields from fn-slots in lookups so the user can see
    // structure + add / remove / rename / retype them. Submit goes
    // through /api/types/record (PUT) which diffs against current.
    const slots = [];
    if (lookups?.fnSlotsByFn && lookups?.slotMap) {
      const fss = lookups.fnSlotsByFn.get(fn.id) || [];
      for (const fs of fss) {
        const slot = lookups.slotMap.get(fs['slot-id']);
        if (!slot) continue;
        const tname = fnIdToTypeName(slot['type-fn-id']) || 'any';
        slots.push((slot.name || '?') + ': ' + tname);
      }
    }
    return Object.assign(base, { fields: slots.join('\n') });
  }
  return base;
}

async function putTypeEdit(_kind, fnId, name, payload) {
  // Records take a different path because slot add / remove / retype
  // is a multi-row delta — handled atomically by
  // PUT /api/types/record. Refinement / union / variant / list all
  // mutate a single `fn` row, so they go through generic PUT
  // /api/entities/fn/:id (parse-fn-from-form already accepts the
  // constraint / base-fn-id / element-fn-id keys from the create
  // path).
  // Always send description — empty string clears any prior value.
  const desc = payload.description || '';
  if (payload.kind === 'record') {
    const body = { id: fnId, name, description: desc, fields: payload.body.fields };
    const fetchFn = (typeof authFetch === 'function') ? authFetch : fetch;
    const r = await fetchFn(API.api_types_record_update, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await r.json().catch(() => ({ ok: false, error: 'HTTP ' + r.status }));
    if (!data.ok) throw new Error(data.error || ('HTTP ' + r.status));
    return r;
  }
  const form = Object.assign({ name, description: desc }, payload.body);
  // PUT /api/entities/fn/:id reads `element-fn-id` from the form (the
  // resolver under the hood accepts UUID-or-name). The create-side
  // payload uses `element-type` because POST /api/types/list takes a
  // distinct JSON-body key; rename so the same builder works for the
  // edit path.
  if (payload.kind === 'list' && form['element-type'] != null) {
    form['element-fn-id'] = form['element-type'];
    delete form['element-type'];
  }
  const r = await authMutate('PUT',
    API.api_entities_type_id('fn', fnId), form);
  if (!(r.status >= 200 && r.status < 300)) {
    const text = await r.text().catch(() => '');
    throw new Error((text || '').slice(0, 200) || ('HTTP ' + r.status));
  }
  return r;
}

async function postTypeCreate(_kind, parentNsId, name, payload) {
  const desc = payload.description || '';
  if (payload.kind === 'record') {
    return postRecordOrList(API.api_types_record, name, parentNsId,
                            Object.assign({ fields: payload.body.fields },
                                          desc ? { description: desc } : {}));
  }
  if (payload.kind === 'list') {
    return postRecordOrList(API.api_types_list, name, parentNsId,
                            Object.assign({ 'element-type': payload.body['element-type'] },
                                          desc ? { description: desc } : {}));
  }
  // refinement / union / variant — go through generic
  // POST /api/entities/fn with extended parse-fn-from-form support.
  const form = Object.assign({
    name,
    'namespace-id': parentNsId || '',
  }, payload.body);
  if (desc) form.description = desc;
  const r = await authMutate('POST', API.api_entities_type('fn'), form);
  if (!(r.status >= 200 && r.status < 300)) {
    const text = await r.text().catch(() => '');
    throw new Error((text || '').slice(0, 200) || ('HTTP ' + r.status));
  }
  return r;
}

async function postRecordOrList(url, name, parentNsId, extra) {
  const body = Object.assign({ name }, extra);
  if (parentNsId) body['namespace-id'] = parentNsId;
  const fetchFn = (typeof authFetch === 'function') ? authFetch : fetch;
  const r = await fetchFn(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const data = await r.json().catch(() => ({ ok: false, error: 'HTTP ' + r.status }));
  if (!data.ok) {
    throw new Error(data.error || ('HTTP ' + r.status));
  }
  return r;
}

window.openTypeCreatePicker = openTypeCreatePicker;
window.openTypeEditForm = openTypeEditForm;
