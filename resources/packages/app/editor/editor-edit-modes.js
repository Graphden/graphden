// Editor Edit Modes — inline edit popovers for the graph editor.
//
// Inline edit flows share one singleton popover skeleton
// (`openInlineEditPopover`) and a small set of state-patching helpers
// (`patchFnFieldInState`) that don't escape this file.
//
// Globals consumed: authFetch/authMutate (editor-auth.js),
// fetchValueForm/renderValueForm/collectFormValue (editor-value-form.js
// + web/runtime), expectedSlotType/validateLiteralAgainstType
// (editor-literal-types.js), openFnPicker (editor-fn-picker.js),
// initGraph/renderGraph. fn-level and type-level modes live in
// editor-edit-modes-fn.js / editor-edit-modes-type.js.

// ============================================================================
// INLINE EDIT POPOVERS — arg-value, arg-rename, fn-rename, fn-return-type
// ============================================================================
//
// All four edits share the same singleton-popover skeleton: a small
// floating panel anchored below the click target with a single
// control (input or select) + Save/Cancel. Closed by Save (success),
// Cancel, Esc, or pointerdown outside.
//
// `openInlineEditPopover` builds the skeleton; the four `enter*EditMode`
// functions just feed it a control factory + save handler.

// graph-first-exception: inline edit popovers are keystroke-latency
// surfaces (live validation while typing, literal-vs-ref chooser) — the
// server sees the SUBMITTED value, not the editing state (secret mode
// must not stream keystrokes); server-owned forms mount via /api/value-form.
let inlineEditEl = null;
let inlineEditOutsideHandler = null;

function closeInlineEdit() {
  if (inlineEditEl) {
    inlineEditEl.remove();
    inlineEditEl = null;
  }
  if (inlineEditOutsideHandler) {
    document.removeEventListener('pointerdown', inlineEditOutsideHandler);
    inlineEditOutsideHandler = null;
  }
}

// `opts`: {anchorEl, makeControl(rootEl) → control, doSave(control) → bool|Promise<bool>, onSaved? → void}
// `makeControl` builds the focusable element (input/select) and appends
// it inside rootEl. It must return that element so the skeleton can
// focus it and listen for Enter/Escape.
function openInlineEditPopover(opts) {
  closeInlineEdit();

  const el = document.createElement('div');
  el.className = 'arg-value-edit-popover';
  // Screen-reader semantics — every popover is a small modal-like
  // dialog. Caller provides `ariaLabel` describing the action
  // ("Edit value", "Rename", "Change type", …); Esc + outside-
  // click + Cancel all dismiss.
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'false');
  el.setAttribute('aria-label', opts.ariaLabel || 'Edit');
  const rect = opts.anchorEl.getBoundingClientRect();
  // Position below the anchor by default; flip above + clamp inside
  // the viewport so iPad-portrait edits don't push the popover off
  // the bottom of the screen when the soft keyboard appears.
  el.style.top  = (rect.bottom + 6) + 'px';
  el.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - 280)) + 'px';

  const control = opts.makeControl(el);

  const errorEl = document.createElement('div');
  errorEl.className = 'arg-value-edit-error';
  errorEl.setAttribute('role', 'alert');
  errorEl.setAttribute('aria-live', 'polite');
  errorEl.style.display = 'none';

  const buttons = document.createElement('div');
  buttons.className = 'arg-value-edit-buttons';

  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
  cancel.textContent = 'Cancel';
  cancel.addEventListener('click', closeInlineEdit);

  const save = document.createElement('button');
  save.type = 'button';
  save.className = 'arg-value-edit-btn';
  save.textContent = 'Save';
  const doSave = async () => {
    save.disabled = true;
    cancel.disabled = true;
    errorEl.style.display = 'none';
    // `doSave` may return a bare boolean (legacy edit modes) or a
    // `{ok, error}` result — the latter lets a save surface the
    // server's rejection reason instead of the generic message.
    const res = await opts.doSave(control);
    const ok = (res === true) || !!res?.ok;
    if (ok) {
      closeInlineEdit();
      if (typeof opts.onSaved === 'function') opts.onSaved();
    } else {
      save.disabled = false;
      cancel.disabled = false;
      errorEl.textContent = res?.error
        || 'Save failed — check that you’re signed in.';
      errorEl.style.display = 'block';
    }
  };
  save.addEventListener('click', doSave);
  // Optional Delete button — for popovers that want to expose a
  // "drop the underlying entity" action alongside Save/Cancel.
  // Floated left (margin-right: auto on the row's first child) so
  // it visually separates from the commit/dismiss pair without
  // needing a new row. Caller's `onDelete(control)` runs after the
  // popover closes — typical use is "DELETE the binding so the slot
  // reverts to a free-arg".
  if (typeof opts.onDelete === 'function') {
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'arg-value-edit-btn arg-value-edit-btn-danger';
    del.textContent = 'Delete';
    del.addEventListener('click', () => {
      const ctl = control;
      closeInlineEdit();
      opts.onDelete(ctl);
    });
    buttons.appendChild(del);
  }
  buttons.appendChild(cancel);
  buttons.appendChild(save);

  el.appendChild(errorEl);
  el.appendChild(buttons);
  document.body.appendChild(el);
  inlineEditEl = el;

  setTimeout(() => {
    if (control && typeof control.focus === 'function') control.focus();
    if (control && typeof control.select === 'function') {
      try { control.select(); } catch (_) {}
    }
  }, 0);

  if (control) {
    control.addEventListener('keydown', (e) => {
      if (e.key === 'Enter')  { e.preventDefault(); doSave(); }
      if (e.key === 'Escape') { e.preventDefault(); closeInlineEdit(); }
    });
  }

  inlineEditOutsideHandler = (e) => {
    if (!el.contains(e.target)) closeInlineEdit();
  };
  setTimeout(() => document.addEventListener('pointerdown', inlineEditOutsideHandler), 0);
}

// --- arg-value edit (Phase 0) ---
//
// Type-validation helpers (expectedSlotType, classifyLiteralJS,
// refinementOK, primitiveSubtype, validateLiteralAgainstType,
// formatTypeHint, NUMERIC_SUPERS) live in editor-literal-types.js —
// loaded into the bundle before this file.

function enterArgValueEditMode(arg, anchorEl) {
  if (!arg) return;
  const expected = (typeof expectedSlotType === 'function')
                   ? expectedSlotType(arg) : null;
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Edit arg value',
    makeControl(root) {
      // Hint line above the form — shown when the slot's expected
      // type is known.
      if (expected && typeof formatTypeHint === 'function') {
        const hint = document.createElement('div');
        hint.className = 'arg-value-edit-hint';
        hint.textContent = 'Expected: ' + formatTypeHint(expected);
        root.appendChild(hint);
      }
      // The form is fetched async, but `makeControl` must return a
      // control synchronously — so it returns a host div (with a
      // spinner) and fills it once /api/value-form responds.
      const host = document.createElement('div');
      host.className = 'value-form-host';
      host.tabIndex = -1;
      const loading = document.createElement('div');
      loading.className = 'value-form-loading';
      loading.textContent = 'Loading…';
      host.appendChild(loading);
      root.appendChild(host);

      const status = document.createElement('div');
      status.className = 'arg-value-edit-status';
      root.appendChild(status);

      // The backend resolves the slot type and serves the matching
      // control as hiccup. If the endpoint is unreachable, fall back
      // to a legacy single-line input so value-editing never breaks.
      fetchValueForm(arg).then((payload) => {
        // The user may have dismissed the popover mid-fetch — `root`
        // is then detached from the document.
        if (!root.isConnected) return;
        if (!payload) {
          makeLegacyControl(host, arg, expected, status);
          return;
        }
        // Marker-typed slot (server-dispatched — the graph's
        // value-form registry mapped the slot's marker type to the
        // `secret-binding` widget; the editor knows no tag names):
        // creating a NEW binding routes to the path+value popover
        // that writes a resolver binding via POST /api/secrets/binding.
        // An EXISTING binding keeps the legacy control (same UX as
        // before server dispatch — inspect/replace the raw value).
        if (formWidgetName(payload.form) === 'secret-binding') {
          if (!arg['binding-id']) {
            enterSecretBindingEditMode(arg, anchorEl);
            return;
          }
          makeLegacyControl(host, arg, expected, status);
          return;
        }
        renderValueForm(host, payload, { expected, statusEl: status });
      });
      return host;
    },
    async doSave(control) {
      return saveFormValue(arg, control);
    },
    // Full refresh, not `renderGraph` — a value change alters binding/
    // item rows, leaving `lookups` stale. `loadGraphData` re-fetches the
    // index + subtree + rich-types (enough to reflect the value + any
    // inferred-type shift) WITHOUT `initGraph`'s value-kinds/services
    // re-fetch + graph re-render.
    onSaved() { if (typeof loadGraphData === 'function') loadGraphData(); },
    // Delete drops the binding so the slot reverts to a free-arg
    // placeholder — the single inline path for switching a bound
    // literal to anything else.
    onDelete: arg['binding-id']
              ? () => { if (typeof deleteUseSiteBinding === 'function') deleteUseSiteBinding(arg); }
              : null
  });
}

// First `data-form-widget` attribute in a JSON-hiccup tree — the
// server-side value-form dispatch names the widget; the client only
// routes on it (no type-tag knowledge here).
function formWidgetName(node) {
  if (!Array.isArray(node)) return null;
  const attrs = node[1];
  if (attrs && typeof attrs === 'object' && !Array.isArray(attrs)
      && attrs['data-form-widget']) {
    return attrs['data-form-widget'];
  }
  for (let i = 1; i < node.length; i += 1) {
    const found = formWidgetName(node[i]);
    if (found) return found;
  }
  return null;
}

// Inline `:secret-path` form for a `[:secret T]`-typed slot. Two
// fields: vault path + initial value. Submit posts to
// /api/secrets/binding, which atomically writes the value to vault and
// creates a `:secret-path`-kinded binding on (fn-id, slot-id). On
// success the graph reloads so the new binding appears immediately on
// the slot's edge.
function enterSecretBindingEditMode(arg, anchorEl) {
  if (!arg) return;
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Bind secret to slot',
    makeControl(root) {
      // Wrap in a div so `openInlineEditPopover`'s `control.focus()`,
      // `control.addEventListener('keydown', …)` calls work — they
      // expect an Element. The wrapper also lets `doSave` look up the
      // two inputs by `data-secret-field`.
      // graph-first-exception: the whole form is client-built by design —
      // the server must NOT see the vault path / secret value while the
      // user types (skill §6 security carve-out); its labels ride along
      // with the controls they caption.
      const wrap = document.createElement('div');
      wrap.className = 'arg-value-edit-secret-form';
      wrap.tabIndex = -1;
      const hint = document.createElement('div');
      hint.className = 'arg-value-edit-hint';
      hint.textContent = 'Secret-typed slot — value goes to OpenBao at the path below.';
      wrap.appendChild(hint);
      const pathLbl = document.createElement('label');
      pathLbl.className = 'arg-value-edit-hint';
      pathLbl.textContent = 'Vault path';
      wrap.appendChild(pathLbl);
      const pathInput = document.createElement('input');
      pathInput.type = 'text';
      pathInput.className = 'arg-value-edit-input';
      pathInput.placeholder = 'e.g. postgres/password';
      pathInput.dataset.secretField = 'path';
      wrap.appendChild(pathInput);
      const valLbl = document.createElement('label');
      valLbl.className = 'arg-value-edit-hint';
      valLbl.textContent = 'Initial value (never displayed again)';
      wrap.appendChild(valLbl);
      const valInput = document.createElement('input');
      valInput.type = 'password';
      valInput.className = 'arg-value-edit-input';
      valInput.dataset.secretField = 'value';
      wrap.appendChild(valInput);
      root.appendChild(wrap);
      setTimeout(() => { try { pathInput.focus(); } catch (_) {} }, 0);
      return wrap;
    },
    async doSave(wrap) {
      const pathInput = wrap.querySelector('[data-secret-field="path"]');
      const valInput = wrap.querySelector('[data-secret-field="value"]');
      const path = (pathInput?.value || '').trim();
      const value = valInput?.value || '';
      if (!path) return { ok: false, error: 'Vault path is required.' };
      if (!value) return { ok: false, error: 'Initial value is required.' };
      try {
        const r = await authFetch(API.api_secret_bindings, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            'fn-id': arg['fn-id'],
            'slot-id': arg['slot-id'],
            path,
            value,
          }),
        });
        if (r?.ok) {
          const body = await r.json().catch(() => null);
          if (body?.ok === false) return { ok: false, error: body.error || 'Save failed.' };
          return { ok: true };
        }
        return { ok: false, error: await responseError(r) };
      } catch (err) {
        // eslint-disable-next-line no-console
        console.error('value save fetch threw', err);
        return { ok: false, error: 'Save failed — network error.' };
      }
    },
    onSaved() { if (typeof initGraph === 'function') initGraph(); }
  });
}


// Legacy fallback control — a single text input, used ONLY when
// `/api/value-form` is unreachable. It carries the same `data-*`
// contract as a backend form-fn (`data-field-kind="any"` =
// smart-parse) so `saveFormValue` collects it through the identical
// path.
function makeLegacyControl(host, arg, expected, statusEl) {
  host.textContent = '';
  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'arg-value-edit-input';
  input.setAttribute('data-form-field', '');
  input.setAttribute('data-field-kind', 'any');
  const v = arg.value;
  input.value = (typeof v === 'string') ? v
              : (v === null || v === undefined) ? ''
              : JSON.stringify(v);
  host.appendChild(input);
  try { input.focus(); input.select(); } catch (_) {}
  if (statusEl && expected && typeof installFormLiveValidation === 'function') {
    installFormLiveValidation(host, expected, statusEl);
  }
}

// Wrap the shared `extractResponseError` (editor-auth.js) with this
// module's mutation-specific 401 message + offline-network fallback.
// The shared helper uses the DOM parser so HTML entities decode
// correctly (vs the old regex strip).
async function responseError(r) {
  if (!r) return 'Save failed — network error.';
  return extractResponseError(r, {
    authExpired: 'Sign in to save this value.',
    fallback: 'Save failed (HTTP ' + r.status + ').',
  });
}

// Slot/binding-aware writer: PUT /api/entities/binding/:id when the
// slot already has an own binding on this fn, POST a new binding
// otherwise. `arg` carries `fn-id` + `slot-id` (+ optional `binding-id`)
// — those come from the synth-arg adapter, populated server-side from
// the slot/binding rows. Returns `{ok, error?}` — `error` carries the
// server's rejection reason for the popover to display.
async function writeBindingFields(arg, fields) {
  if (!arg) return { ok: false, error: 'No target binding.' };
  const fnId = arg['fn-id'];
  const slotId = arg['slot-id'];
  const bindingId = arg['binding-id'];
  if (!fnId || !slotId) return { ok: false, error: 'No target binding.' };
  const body = Object.entries(fields)
    .map(([k, v]) => k + '=' + encodeURIComponent(v == null ? '' : v))
    .join('&');
  try {
    const r = bindingId
      ? await authMutate('PUT',
                          API.api_entities_type_id('binding', bindingId),
                          body)
      : await authMutate('POST', API.api_entities_type('binding'),
                          'fn-id=' + encodeURIComponent(fnId) +
                          '&slot-id=' + encodeURIComponent(slotId) +
                          (body ? '&' + body : ''));
    return r?.ok ? { ok: true } : { ok: false, error: await responseError(r) };
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('binding save fetch threw', err);
    return { ok: false, error: 'Save failed — network error.' };
  }
}

// PUT /api/sequence/item/:id with a JSON `{value}` body — the
// in-place edit counterpart of the append / remove helpers. Returns
// `{ok, error?}`.
async function putSequenceItemValue(itemId, value) {
  if (!itemId) return { ok: false, error: 'No sequence item.' };
  try {
    const r = await authFetch(API.api_sequence_item_item_id(itemId), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ value: value })
    });
    return r?.ok ? { ok: true } : { ok: false, error: await responseError(r) };
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('binding save fetch threw', err);
    return { ok: false, error: 'Save failed — network error.' };
  }
}

// --- arg rename (Phase 1) ---
//
// Edge-label click → rename the arg row whose `fn-id` is the root.
// The backend already enforces unique `(fn-id, name)` so a clash will
// fail server-side; we just propagate the failure as "Save failed".

// `displayLabel` is the name the user currently SEES on the edge —
// the slot's effective name (closest binding's `:rename-to` or the
// slot's own `:name`). Pre-filling with it is the least-surprising
// default; if the user just hits Save, the binding gets an explicit
// `:rename-to` equal to the inherited label (rename-forwarding
// becomes explicit).
function enterArgRenameEditMode(arg, anchorEl, displayLabel) {
  if (!arg) return;
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Rename arg',
    makeControl(root) {
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.value = arg.name || displayLabel || '';
      root.insertBefore(input, root.firstChild);
      return input;
    },
    async doSave(input) {
      const newName = (input.value || '').trim();
      if (!newName) return false;
      // Slot rename = binding's `:rename-to`. saveBindingFields
      // creates the binding when none exists yet (rename-only flag
      // on a fresh binding row).
      return await writeBindingFields(arg, { 'rename-to': newName });
    },
    onSaved() { if (typeof renderGraph === 'function') renderGraph(false); }
  });
}










// --- free-arg binding (Phase 4) ---
//
// Click on a placeholder for a root-fn free-arg → tiny chooser:
//   - "literal" → text input → PUT value=<json>
//   - "fn-ref"  → fn-picker → PUT ref-id=<fn-id>
//
// Effective type comes from the slot row (override-fn-id wins);
// for `:fn` the chooser short-circuits straight to the picker
// since a literal `fn-id` makes no sense.

// Shared two-button "literal vs fn-ref" chooser popover — the same
// skeleton serves the free-arg binder and the sequence-append flow
// (different labels + follow-ups). Both buttons close the popover and
// hand off; the skeleton's Save is inert.
function openLiteralVsRefChooser({ anchorEl, ariaLabel, litLabel, refLabel,
                                   onLiteral, onRef, extraButtons }) {
  openInlineEditPopover({
    anchorEl,
    ariaLabel,
    makeControl(root) {
      const wrap = document.createElement('div');
      wrap.className = 'free-arg-bind-chooser';
      const mk = (label, handler) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
        btn.textContent = label;
        btn.addEventListener('click', (e) => {
          e.preventDefault();
          closeInlineEdit();
          handler();
        });
        wrap.appendChild(btn);
        return btn;
      };
      const litBtn = mk(litLabel, onLiteral);
      mk(refLabel, onRef);
      for (const b of extraButtons || []) mk(b.label, b.handler);
      root.insertBefore(wrap, root.firstChild);
      return litBtn;  // initial focus target
    },
    async doSave() { return false; }
  });
}




// --- sequence add/remove (Phase 5) ---
//
// Thin wrappers over the existing /api/sequence/append/:fn-id and
// /api/sequence/item/:item-id endpoints. The `+` button on a chain
// tail kicks off a small chooser (literal vs fn-ref) so the new
// item's binding is set in the same operation.

// `expectedType` (optional) — the type the appended item must have,
// as resolved by `appendNavType` for a nav-typed sequence (e.g. an
// `:update-in` `:path`). When it's a closed enum the literal prompt
// renders a <select>; undefined means an unconstrained append.
// `opts.position` (optional) turns the append into an INSERT — the
// new item takes that position, later items shift +1 (the backend's
// optional `:position` body field).
// `opts.elemType` (optional) — the sequence's declared element type
// (`slotRichType`'s `[:list T]` elem). It types the "New from
// template…" picker so e.g. a hiccup :children chain offers the
// component library.
async function appendSequenceItem(fnId, anchorEl, expectedType, opts) {
  if (!fnId) return;
  const position = (opts && typeof opts.position === 'number') ? opts.position : null;
  const elemType = (opts && opts.elemType !== undefined) ? opts.elemType : null;
  const verb = position === null ? 'Append' : 'Insert';
  closeInlineEdit();
  // Two-step UX mirroring free-arg binding: pick "Literal" / "Fn-ref" /
  // "New from template…", then enter the value / pick the fn. The
  // endpoint accepts the chosen body in the same request, so we wait
  // for the user's pick.
  openLiteralVsRefChooser({
    anchorEl: anchorEl || document.getElementById('graph-surface') || document.body,
    ariaLabel: verb + ' sequence item',
    litLabel: verb + ' literal',
    refLabel: verb + ' fn-ref',
    onLiteral: () => promptLiteralForAppend(fnId, anchorEl, expectedType, position),
    onRef: () => {
      if (typeof openFnPicker === 'function') {
        openFnPicker({
          anchorEl: anchorEl || document.body,
          excludeIds: [fnId],
          onPick: async (fn) => {
            const body = { ref: fn.id };
            if (position !== null) body.position = position;
            await postSequenceAppend(fnId, body);
          }
        });
      }
    },
    extraButtons: [{
      label: 'New from template…',
      handler: () => promptTemplateInstanceInsert(fnId, anchorEl,
                                                  expectedType || elemType,
                                                  position)
    }]
  });
}

// "New from template…" — pick a type-compatible fn as the PARENT of a
// fresh named instance, create it, and append a ref to it. This is how
// a component drops into a page: pick :button from the (type-filtered)
// palette, name the instance, then bind its free args on the canvas.
function promptTemplateInstanceInsert(fnId, anchorEl, expectedType, position) {
  if (typeof openFnPicker !== 'function') return;
  openFnPicker({
    anchorEl: anchorEl || document.body,
    excludeIds: [fnId],
    expectedType: expectedType || undefined,
    onPick: (template) => {
      if (!template?.id) return;
      promptTemplateInstanceName(fnId, anchorEl, template, position);
    }
  });
}

function promptTemplateInstanceName(fnId, anchorEl, template, position) {
  const owner = lookups?.fnMap?.get(fnId);
  const suggested = (owner?.name ? '_' + owner.name + '-' : 'my-')
                  + (template.name || 'instance');
  openInlineEditPopover({
    anchorEl: anchorEl || document.body,
    ariaLabel: 'Name the new ' + (template.name || 'instance'),
    makeControl(root) {
      const hint = document.createElement('div');
      hint.className = 'arg-value-edit-hint';
      hint.textContent = 'New ' + (template.name || 'fn') + ' — instance name';
      root.insertBefore(hint, root.firstChild);
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.value = suggested;
      root.insertBefore(input, root.firstChild.nextSibling);
      return input;
    },
    async doSave(input) {
      const name = (input.value || '').trim();
      if (!name) return false;
      return await createTemplateInstanceAndAppend(fnId, template, name, position);
    }
  });
}

async function createTemplateInstanceAndAppend(fnId, template, name, position) {
  const owner = lookups?.fnMap?.get(fnId);
  try {
    const fields = { name: name,
                     'namespace-id': owner?.['namespace-id'] || '',
                     'parent-ids': template.id };
    const r = await authMutate('POST', API.api_entities_type('fn'),
                               new URLSearchParams(fields).toString());
    if (!r?.ok) return { ok: false, error: await responseError(r) };
    // The create response is a plain confirmation (no id) — resolve the
    // new row by (namespace-qualified) name, the same path deep links use.
    const nsPath = owner?.['namespace-id'] != null
                 ? lookups?.nsPathMap?.get(owner['namespace-id']) : null;
    const created = (typeof resolveFnByName === 'function')
                  ? await resolveFnByName(nsPath ? nsPath + '/' + name : name)
                  : null;
    if (!created?.id) return { ok: false, error: 'Created, but could not resolve the new fn.' };
    const body = { ref: created.id };
    if (typeof position === 'number') body.position = position;
    const appended = await postSequenceAppend(fnId, body);
    return appended ? { ok: true }
                    : { ok: false, error: 'Instance created, but appending the ref failed.' };
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('template-instance create threw', err);
    return { ok: false, error: 'Create failed — network error.' };
  }
}

function promptLiteralForAppend(fnId, anchorEl, expectedType, position) {
  // Closed-enum target → <select> of valid values; otherwise free text.
  const enumInfo = (expectedType != null && typeof closedEnumOf === 'function')
                   ? closedEnumOf(expectedType) : null;
  openInlineEditPopover({
    anchorEl: anchorEl || document.body,
    ariaLabel: 'Enter literal value to append',
    makeControl(root) {
      if (expectedType != null && typeof formatTypeHint === 'function') {
        const hint = document.createElement('div');
        hint.className = 'arg-value-edit-hint';
        hint.textContent = 'Expected: ' + formatTypeHint(expectedType);
        root.insertBefore(hint, root.firstChild);
      }
      if (enumInfo) {
        const select = document.createElement('select');
        select.className = 'arg-value-edit-input';
        for (const m of enumInfo.members) {
          const opt = document.createElement('option');
          opt.value = m.value;
          opt.textContent = m.label;
          select.appendChild(opt);
        }
        root.insertBefore(select, root.firstChild);
        return select;
      }
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.placeholder =
        (typeof isKeywordType === 'function' && isKeywordType(expectedType))
          ? ':key-name'
          : 'JSON value (e.g. 42, "text", true)';
      root.insertBefore(input, root.firstChild);
      return input;
    },
    async doSave(control) {
      const trimmed = (control.value || '').trim();
      if (trimmed === '') return false;
      let value;
      if (typeof isKeywordType === 'function' && isKeywordType(expectedType)) {
        // Keyword-typed segment — the input names a keyword. Store it
        // colon-prefixed so the backend keeps it AS a keyword; a bare
        // string would persist as plain text.
        value = (trimmed.charAt(0) === ':') ? trimmed : ':' + trimmed;
      } else {
        try { value = JSON.parse(trimmed); }
        catch (_) { value = control.value; }
      }
      const body = { value: value };
      if (typeof position === 'number') body.position = position;
      return postSequenceAppend(fnId, body);
    }
    // No `onSaved` refresh — `postSequenceAppend` already fires the
    // lighter `loadGraphData` (index + subtree + rich-types) on success.
    // A second `initGraph` here double-pulled the full index + types +
    // value-kinds + services and re-rendered the graph for nothing (the
    // ref-append path has never done it). See `postSequenceAppend`.
  });
}

async function postSequenceAppend(fnId, body) {
  try {
    const r = await authFetch(API.api_sequence_append_fn_id(fnId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (r?.ok) {
      // Sequence edits change binding-list-item rows, not fn structure/
      // value-kinds — the lighter `loadGraphData` (index + subtree +
      // rich-types) reflects them without the `initGraph` graph re-render.
      if (typeof loadGraphData === 'function') loadGraphData();
      return true;
    }
  } catch (_) {}
  return false;
}

// POST /api/sequence/move/:item-id with `{direction: "up"|"down"}` —
// swaps the item with its neighbour; an edge move is a server no-op.
async function moveSequenceItem(itemId, direction) {
  if (!itemId) return false;
  try {
    const r = await authFetch(API.api_sequence_move_item_id(itemId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ direction: direction })
    });
    if (r?.ok) {
      if (typeof loadGraphData === 'function') loadGraphData();
      return true;
    }
  } catch (_) {}
  return false;
}

async function removeSequenceItem(itemId) {
  if (!itemId) return false;
  try {
    const r = await authMutate('DELETE',
                               API.api_sequence_item_item_id(itemId));
    if (r?.ok) {
      if (typeof loadGraphData === 'function') loadGraphData();
      return true;
    }
  } catch (_) {}
  return false;
}

// Use-site delete — clears the value feeding a fn-card's grey
// header. Two paths depending on the row's storage:
//   - sequence item (item-id present)  → DELETE /api/sequence/item/:id
//     (one item drops out of the chain)
//   - plain binding (binding-id present) → DELETE /api/entities/binding/:id
//     (the override is removed; the slot reverts to whatever the
//     parent chain provides, falling back to free-arg)
// Confirmation prompt warns about the "becomes free-arg" outcome
// so the user knows the slot won't keep its current value.
async function deleteUseSiteBinding(arg) {
  if (!arg) return false;
  if (typeof isAuthenticated === 'function' && !isAuthenticated()) return false;
  const itemId = arg['item-id'];
  if (itemId) {
    if (!confirm('Remove this sequence item?')) return false;
    return await removeSequenceItem(itemId);
  }
  const bindingId = arg['binding-id'];
  if (!bindingId) return false;
  if (!confirm('Remove this value? The slot will revert to a free-arg '
               + '(default value, or pick a new one).')) return false;
  try {
    const r = await authMutate('DELETE',
                               API.api_entities_type_id('binding', bindingId));
    if (r?.ok) {
      if (typeof initGraph === 'function') initGraph();
      return true;
    }
  } catch (_) {}
  return false;
}

async function saveArgRef(arg, refFnId) {
  // Picker contract retained — but the body now writes binding's
  // `:ref-fn-id` directly via the slot/binding API.
  if (!arg) return false;
  if ((await writeBindingFields(arg, { 'ref-fn-id': refFnId })).ok) {
    // ref-fn-id change can shift the arg's inferred type — `loadGraphData`
    // refreshes rich-types + subtree, so the chip updates without initGraph.
    if (typeof loadGraphData === 'function') loadGraphData();
    return true;
  }
  return false;
}

