// Editor Edit Modes — inline edit popovers for the graph editor.
//
// All inline edit flows used to live in editor-tooltips.js until the
// inline-editing work bloated the file past 1400 LOC. They share one
// singleton popover skeleton (`openInlineEditPopover`) and a small
// set of state-patching helpers (`patchFnFieldInState`) that don't
// escape this file.
//
// Globals consumed: lookups, graphData, authFetch, openFnPicker,
// openNamespacePicker, initGraph, renderGraph, buildLookups,
// rebuildImplementationFnIds, expectedSlotType, formatTypeHint,
// validateLiteralAgainstType, VALUE_KINDS.

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
  // Secret-typed slot (e.g. `:sql-exec/:password`): skip the generic
  // value-form and open a 2-field path+value popover that writes a
  // `:secret-path`-kinded binding via POST /api/secrets/binding. Only
  // for the create-new case — when a binding already exists, the user
  // must delete it first (the regular popover handles that).
  if (typeof isSecretType === 'function' && isSecretType(expected)
      && !arg['binding-id']) {
    enterSecretBindingEditMode(arg, anchorEl);
    return;
  }
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
    // re-fetch + cytoscape re-init.
    onSaved() { if (typeof loadGraphData === 'function') loadGraphData(); },
    // Delete drops the binding so the slot reverts to a free-arg
    // placeholder — the single inline path for switching a bound
    // literal to anything else.
    onDelete: arg['binding-id']
              ? () => { if (typeof deleteUseSiteBinding === 'function') deleteUseSiteBinding(arg); }
              : null
  });
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

// --- fn rename (Phase 1) ---
//
// Click ✎ pencil on the root fn name → rename popover. After save,
// the sidebar tree needs a refresh too, so we go through the heavy
// `initGraph()` path rather than patch-in-place.

// Extend = create a new composed fn with `fn` as parent, in the same
// namespace. Replaces the misguided "add a new arg to this fn"
// pattern that the storage layer now correctly rejects (the new fn
// is the legitimate place for new args / renames). After save, the
// editor navigates to the new fn so the user can immediately add
// `:as` renames + value bindings to extend its interface.
function enterExtendEditMode(fn, anchorEl) {
  if (!fn) return;
  let pendingName = '';
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Extend (create child fn)',
    makeControl(root) {
      const hint = document.createElement('div');
      hint.className = 'arg-value-edit-hint';
      hint.textContent = 'Creates a new fn with :parent '
        + (fn.name || '(this fn)') + '. Open it to add new bindings or renames.';
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.placeholder = 'New fn name';
      root.insertBefore(input, root.firstChild);
      root.insertBefore(hint, root.firstChild);
      return input;
    },
    async doSave(input) {
      const newName = (input.value || '').trim();
      if (!newName) return false;
      const opKey = 'extend:' + fn.id + ':' + newName;
      if (typeof isOpInflight === 'function' && isOpInflight(opKey)) return false;
      pendingName = newName;
      const fields = { name: newName, 'parent-ids': fn.id };
      if (fn['namespace-id']) fields['namespace-id'] = fn['namespace-id'];
      const work = async () => {
        try {
          const r = await postEntity('fn', fields);
          if (r && r.status >= 200 && r.status < 300) return true;
        } catch (_) {}
        return false;
      };
      return (typeof withBusy === 'function')
        ? await withBusy(opKey, 'Creating ' + newName + '…', work)
        : await work();
    },
    onSaved() {
      // Refetch + auto-select the new fn so the user sees its empty
      // body and can start adding bindings via the existing edit
      // affordances. The whole "init + select" sequence runs under
      // the same busy slot as the create, so the banner stays up
      // until the user-visible state matches the DB.
      const opKey = 'extend-finalise:' + fn.id;
      const finalise = async () => {
        if (typeof initGraph === 'function') await initGraph();
        if (pendingName && typeof selectFnByName === 'function') {
          selectFnByName(pendingName);
        }
      };
      if (typeof withBusy === 'function') {
        withBusy(opKey, 'Loading ' + (pendingName || 'new fn') + '…', finalise);
      } else {
        finalise();
      }
    }
  });
}


function enterFnRenameEditMode(fn, anchorEl) {
  if (!fn) return;
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Rename function',
    makeControl(root) {
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.value = fn.name || '';
      root.insertBefore(input, root.firstChild);
      return input;
    },
    async doSave(input) {
      const newName = (input.value || '').trim();
      if (!newName) return false;
      try {
        const r = await authMutate('PUT',
                                   API.api_entities_type_id('fn', fn.id),
                                   { name: newName });
        if (r?.ok) return true;
      } catch (_) {}
      return false;
    },
    onSaved() { if (typeof initGraph === 'function') initGraph(); }
  });
}

// --- fn return-type select (Phase 1) ---
//
// Click the `→ <type>` strip on the root fn card → small `<select>`
// dropdown of `value_kind` enum entries → save.

// --- :expects-effects contract edit ---
//
// Three states the backend recognises:
//   - null / undefined    "no contract"        no drift checking
//   - []                  "explicit purity"    drift = any effect at all
//   - ["db", "io", …]     "contract"           drift = effect ∉ this set
// The picker exposes a "no contract" radio + 6 effect checkboxes; if
// no effects are ticked but "explicit purity" is chosen, we POST `[]`.
function enterExpectsEffectsEditMode(fn, anchorEl, currentDeclared) {
  if (!fn) return;
  const cats = ['db', 'env', 'io', 'network', 'time', 'random'];
  const initial = Array.isArray(currentDeclared) ? currentDeclared : null;
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Edit declared effects',
    makeControl(root) {
      const wrap = document.createElement('div');
      wrap.className = 'expects-effects-edit';
      // Mode picker — choose between "no contract" and "explicit
      // contract" — the second mode unlocks the checkbox grid.
      const modeNone = document.createElement('label');
      const noneRadio = document.createElement('input');
      noneRadio.type = 'radio';
      noneRadio.name = 'ee-mode';
      noneRadio.value = 'none';
      noneRadio.checked = initial == null;
      modeNone.appendChild(noneRadio);
      modeNone.appendChild(document.createTextNode(' no contract'));
      modeNone.title = 'Drift checker is off for this fn (default).';

      const modeContract = document.createElement('label');
      const contractRadio = document.createElement('input');
      contractRadio.type = 'radio';
      contractRadio.name = 'ee-mode';
      contractRadio.value = 'contract';
      contractRadio.checked = initial != null;
      modeContract.appendChild(contractRadio);
      modeContract.appendChild(document.createTextNode(' explicit contract'));
      modeContract.title = 'Drift checker compares computed effects against the ticked set. Empty = pinned purity.';

      const modeRow = document.createElement('div');
      modeRow.className = 'expects-effects-mode-row';
      modeRow.appendChild(modeNone);
      modeRow.appendChild(modeContract);
      wrap.appendChild(modeRow);

      const grid = document.createElement('div');
      grid.className = 'expects-effects-grid';
      const boxes = {};
      cats.forEach((c) => {
        const lab = document.createElement('label');
        lab.className = 'expects-effects-checkbox';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.value = c;
        cb.checked = !!(initial && initial.indexOf(c) >= 0);
        cb.disabled = initial == null;
        boxes[c] = cb;
        lab.appendChild(cb);
        lab.appendChild(document.createTextNode(' ' + c));
        grid.appendChild(lab);
      });
      wrap.appendChild(grid);

      const refreshDisabled = () => {
        const inContract = contractRadio.checked;
        cats.forEach(c => { boxes[c].disabled = !inContract; });
      };
      noneRadio.addEventListener('change', refreshDisabled);
      contractRadio.addEventListener('change', refreshDisabled);

      root.insertBefore(wrap, root.firstChild);
      // Expose collected state on the control element for doSave to
      // read — keeping doSave's signature single-argument matches the
      // shape openInlineEditPopover uses for every other edit-mode.
      wrap._collect = () => {
        if (noneRadio.checked) return null;
        return cats.filter(c => boxes[c].checked);
      };
      return wrap;
    },
    async doSave(control) {
      const value = control._collect();
      // The form payload encodes the three states via a single
      // string field: "" (clear → nil), "[]" (explicit empty),
      // or a comma-separated list. parse-fn-from-form does the
      // round-trip.
      const wireValue =
        value == null       ? '' :
        value.length === 0  ? '[]' :
                              value.join(',');
      try {
        const r = await authMutate('PUT',
          API.api_entities_type_id('fn', fn.id),
          { 'expects-effects': wireValue });
        if (r?.ok) {
          patchFnFieldInState(fn.id, 'expects-effects', value);
          return true;
        }
      } catch (_) {}
      return false;
    },
    onSaved() { if (typeof renderGraph === 'function') renderGraph(false); }
  });
}


function enterFnReturnTypeEditMode(fn, anchorEl) {
  if (!fn) return;
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Change return type',
    makeControl(root) {
      const select = document.createElement('select');
      select.className = 'arg-value-edit-input';
      const kinds = (typeof VALUE_KINDS !== 'undefined') ? VALUE_KINDS
                  : ['null','uuid','text','int','bool','numeric','timestamptz','jsonb','bytes','any','fn','sequence'];
      // First option is "(none)" so the user can clear return-type.
      const noneOpt = document.createElement('option');
      noneOpt.value = '';
      noneOpt.textContent = '(none)';
      select.appendChild(noneOpt);
      kinds.forEach(k => {
        const o = document.createElement('option');
        o.value = k;
        o.textContent = k;
        if (fn['return-type'] === k) o.selected = true;
        select.appendChild(o);
      });
      root.insertBefore(select, root.firstChild);
      return select;
    },
    async doSave(select) {
      try {
        const r = await authMutate('PUT',
                                   API.api_entities_type_id('fn', fn.id),
                                   { 'return-type': select.value });
        if (r?.ok) {
          patchFnFieldInState(fn.id, 'return-type', select.value || null);
          return true;
        }
      } catch (_) {}
      return false;
    },
    onSaved() { if (typeof renderGraph === 'function') renderGraph(false); }
  });
}

// --- arg type flip (Phase 2) ---
//
// Type-chip click → small select of `value_kind` enum. On save we
// orchestrate two PUTs to /api/entities/arg/:id:
//
//   1. type=<new>&value=&ref-id=  — backend doesn't cascade type
//      changes, so we have to wipe both bindings explicitly. The
//      permissive parse honours empty form values now (Phase 2
//      backend change).
//   2. (only when new type is `:fn`) ref-id=<picked-fn-id> — opens
//      the fn-picker and writes the chosen ref-id, then refetches.
//
// Refetch is `initGraph()` because flipping type→fn restructures
// the canvas (arg-value node disappears, ref-edge appears).

// Populate `select` with every type-name T such that
// `T ⊆ expectedSlotType(arg)` per the server's alias-aware `subtype?`.
// Pre-fix the picker showed every primitive whether or not it matched
// the slot — turning `:handler` (an fn-type slot) into `:int` was a
// silent click; now the dropdown only lists types the slot can
// legally narrow to.
//
// Candidates: the 14 primitive value-kinds plus every named type-row
// in the rich-types snapshot (refinements, lists, unions, variants,
// fn-types, records). The filter runs once per popover open via
// parallel `/api/types/compatible` calls — typically <50 fetches,
// which finish in well under a second on the local dev box.
async function populateCompatibleTypes(arg, select, cur, loadingOpt) {
  if (typeof expectedSlotType !== 'function') return;
  const expected = expectedSlotType(arg);
  if (!expected) {
    if (loadingOpt) loadingOpt.remove();
    return;
  }
  const primitives = ['null', 'uuid', 'text', 'int', 'bool', 'numeric',
                      'timestamptz', 'jsonb', 'bytes', 'any', 'fn',
                      'sequence', 'keyword', 'float'];
  const richEntries = (typeof richTypes !== 'undefined' && richTypes)
                      ? richTypes : {};
  // Type-row entries are flagged with the keyword `:type-row?` on
  // the backend; cheshire serialises that as the string key
  // `"type-row?"`. Anonymous fn-shape rows have no name so they don't
  // appear here — only USER-FACING names land in the picker.
  const aliases = Object.keys(richEntries)
    .filter(k => richEntries[k] && richEntries[k]['type-row?'] === true);
  const allNames = Array.from(new Set([...primitives, ...aliases]));
  // typesCompatible (editor-literal-types.js) memoises the result per
  // (expected, candidate) pair across the session — repeat opens of
  // the type-select popover skip the backend entirely.
  const results = await Promise.all(allNames.map(async name => {
    const ok = await typesCompatible(expected, name);
    return { name, ok };
  }));
  const valid = results
    .filter(r => r.ok && r.name !== cur)
    .map(r => r.name)
    .sort();
  if (loadingOpt) loadingOpt.remove();
  for (const name of valid) {
    const o = document.createElement('option');
    o.value = name;
    o.textContent = name;
    select.appendChild(o);
  }
  if (!cur && valid.length === 0) {
    const empty = document.createElement('option');
    empty.value = '';
    empty.textContent = '(no compatible types)';
    empty.disabled = true;
    select.appendChild(empty);
  }
}


function enterArgTypeEditMode(arg, anchorEl) {
  if (!arg) return;
  // Capture pre-edit binding state so we can roll back if the user
  // changes type to `:fn`, then cancels the chained fn-picker. Without
  // this revert, the row is left as `:fn` with no ref — an orphaned
  // mismatch the user has to discover via the red ring. Reverting
  // makes "I changed my mind" a non-destructive operation.
  const preEdit = {
    typeOverrideFnId: arg['type-override-fn-id'] || '',
    valueJson: arg.value == null ? '' : JSON.stringify(arg.value),
    refFnId: arg['ref-id'] || arg['ref-fn-id'] || '',
    type: arg.type ? String(arg.type).replace(/^:/, '') : null,
  };
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Change arg type',
    makeControl(root) {
      const select = document.createElement('select');
      select.className = 'arg-value-edit-input';
      const cur = arg.type ? String(arg.type).replace(/^:/, '') : '';
      // Seed with the current type so the popover renders something
      // immediately; the rest of the compatible options stream in
      // asynchronously below (the subtype filter needs the server's
      // alias-aware `subtype?` predicate, no JS analogue handles
      // refinements / records / fn-types fully).
      if (cur) {
        // `selected=true` + the option being the very first item is
        // enough signal for the user; appending " (current)" was
        // redundant chrome the inline-expand panel already dropped.
        const o = document.createElement('option');
        o.value = cur;
        o.textContent = cur;
        o.selected = true;
        select.appendChild(o);
      }
      const loading = document.createElement('option');
      loading.value = '';
      loading.textContent = 'loading compatible types…';
      loading.disabled = true;
      select.appendChild(loading);
      root.insertBefore(select, root.firstChild);
      // Fire-and-forget async populate. Only narrows the picker — if
      // it fails, user keeps the current-only option (still valid).
      populateCompatibleTypes(arg, select, cur, loading);
      return select;
    },
    async doSave(select) {
      const newType = select.value;
      const curType = arg.type ? String(arg.type).replace(/^:/, '') : null;
      if (newType === curType) return true;  // no-op
      // Type override = binding's `:type-override-fn-id` pointing at
      // the type-row whose name matches the picker selection. The
      // picker offers PRIMITIVES (`:text`, `:int`, …) AND
      // REFINEMENTS (`:non-blank-text`, `:port`, …) — both are
      // valid override targets, the only constraint is
      // "parent-less + no impl" (rules out composed fn-defs that
      // happen to share a name). The earlier "primitive only"
      // filter (`!base-fn-id && !element-fn-id`) rejected
      // refinements which the picker happily listed, leaving the
      // user staring at a silent 400 from `writeBindingFields`.
      const overrideFnId = (() => {
        if (!newType || !graphData) return '';
        const fn = (graphData.fns || []).find(f =>
          f.name === newType
          && (!f['parent-ids'] || f['parent-ids'].length === 0)
          && !f['impl-hash']);
        return fn ? fn.id : '';
      })();
      if (!(await writeBindingFields(arg, {
        'type-override-fn-id': overrideFnId,
        value: '',
        'ref-fn-id': ''
      })).ok) return false;
      // Local arg-shape mirror — used by onSaved below to decide
      // whether to chain into the fn-picker. The next initGraph()
      // refetches authoritative state, so this only needs to live
      // until then.
      arg.type = newType;
      arg.value = null;
      arg['ref-id'] = null;
      return true;
    },
    onSaved() {
      const newType = arg.type ? String(arg.type).replace(/^:/, '') : null;
      if (newType === 'fn' && typeof openFnPicker === 'function') {
        // Allow self-reference but exclude only the fn the arg is
        // attached to to keep things sane. Re-parent (Phase 3) will
        // need the descendants exclusion.
        const fnId = arg['fn-id'];
        openFnPicker({
          anchorEl: document.getElementById('cy') || document.body,
          excludeIds: fnId ? [fnId] : [],
          expectedType: expectedSlotType(arg),
          onPick: async (fn) => { await saveArgRef(arg, fn.id); },
          onCancel: async () => {
            // Roll back the type change so the row doesn't end up
            // as `:fn` with no value/ref. We re-issue a write
            // restoring the pre-edit binding fields, then refresh.
            // If the rollback itself fails the row is left in the
            // pending state and the mismatch indicator + explainer
            // will surface it — better to fail loud than silently
            // half-revert.
            await writeBindingFields(arg, {
              'type-override-fn-id': preEdit.typeOverrideFnId,
              value: preEdit.valueJson,
              'ref-fn-id': preEdit.refFnId,
            });
            if (typeof initGraph === 'function') initGraph();
          }
        });
      } else if (typeof initGraph === 'function') {
        initGraph();
      }
    }
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

function enterFreeArgBindEditMode(arg, anchorEl) {
  if (!arg) return;
  closeInlineEdit();

  // Slot-level type drives the default action.
  const effType = arg.type ? String(arg.type).replace(/^:/, '') : null;

  // For `:fn` slots the only sensible binding is a fn-ref; jump straight
  // into the picker.
  if (effType === 'fn') {
    if (typeof openFnPicker === 'function') {
      openFnPicker({
        anchorEl,
        excludeIds: arg['fn-id'] ? [arg['fn-id']] : [],
        // The slot's structural type from /api/types — picker uses
        // it to highlight type-compatible fns.
        expectedType: expectedSlotType(arg),
        onPick: async (fn) => { await saveArgRef(arg, fn.id); }
      });
    }
    return;
  }

  // Otherwise show a literal-vs-ref chooser, then descend into the
  // appropriate input.
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Bind free arg',
    makeControl(root) {
      const wrap = document.createElement('div');
      wrap.className = 'free-arg-bind-chooser';
      const litBtn = document.createElement('button');
      litBtn.type = 'button';
      litBtn.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
      litBtn.textContent = 'Bind literal';
      const refBtn = document.createElement('button');
      refBtn.type = 'button';
      refBtn.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
      refBtn.textContent = 'Bind fn-ref';
      litBtn.addEventListener('click', (e) => {
        e.preventDefault();
        closeInlineEdit();
        // Fall through into the existing arg-value editor.
        enterArgValueEditMode(arg, anchorEl);
      });
      refBtn.addEventListener('click', (e) => {
        e.preventDefault();
        closeInlineEdit();
        if (typeof openFnPicker === 'function') {
          openFnPicker({
            anchorEl,
            excludeIds: arg['fn-id'] ? [arg['fn-id']] : [],
            expectedType: expectedSlotType(arg),
            onPick: async (fn) => { await saveArgRef(arg, fn.id); }
          });
        }
      });
      wrap.appendChild(litBtn);
      wrap.appendChild(refBtn);
      root.insertBefore(wrap, root.firstChild);
      return litBtn;  // initial focus target
    },
    // Save isn't used here — both buttons handle their own flow and
    // close the popover. The skeleton's Save still appears but does
    // nothing useful; users will pick a button instead.
    async doSave() { return false; }
  });
}

// --- namespace-move (Phase 5) ---
//
// Click on the namespace strip on the root card → namespace-picker.
// Pick → PUT namespace-id=<id>. After save, sidebar tree needs a
// rebuild, so we go through initGraph() rather than patch-in-place.

function enterNamespaceMoveEditMode(fn, anchorEl) {
  if (!fn) return;
  if (typeof openNamespacePicker !== 'function') return;
  openNamespacePicker({
    anchorEl,
    onPick: async (picked) => {
      try {
        // (root) sentinel uses `namespace-id=` literal so the backend
        // clears the FK column. `authMutate`'s field-map form strips
        // empty-string values, so pass the pre-encoded body when the
        // pick is the root namespace.
        const body = picked.id
          ? { 'namespace-id': picked.id }
          : 'namespace-id=';
        const r = await authMutate('PUT',
                                   API.api_entities_type_id('fn', fn.id),
                                   body);
        if (r?.ok && typeof initGraph === 'function') initGraph();
      } catch (_) {}
    }
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
async function appendSequenceItem(fnId, anchorEl, expectedType) {
  if (!fnId) return;
  closeInlineEdit();
  // Two-step UX mirroring free-arg binding: pick "Literal" or "Fn-ref",
  // then enter the value / pick the fn. The endpoint accepts the
  // chosen body in the same request, so we wait for the user's pick.
  openInlineEditPopover({
    anchorEl: anchorEl || document.getElementById('cy') || document.body,
    ariaLabel: 'Append sequence item',
    makeControl(root) {
      const wrap = document.createElement('div');
      wrap.className = 'free-arg-bind-chooser';
      const litBtn = document.createElement('button');
      litBtn.type = 'button';
      litBtn.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
      litBtn.textContent = 'Append literal';
      const refBtn = document.createElement('button');
      refBtn.type = 'button';
      refBtn.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
      refBtn.textContent = 'Append fn-ref';
      litBtn.addEventListener('click', (e) => {
        e.preventDefault();
        closeInlineEdit();
        promptLiteralForAppend(fnId, anchorEl, expectedType);
      });
      refBtn.addEventListener('click', (e) => {
        e.preventDefault();
        closeInlineEdit();
        if (typeof openFnPicker === 'function') {
          openFnPicker({
            anchorEl: anchorEl || document.body,
            excludeIds: [fnId],
            onPick: async (fn) => { await postSequenceAppend(fnId, { ref: fn.id }); }
          });
        }
      });
      wrap.appendChild(litBtn);
      wrap.appendChild(refBtn);
      root.insertBefore(wrap, root.firstChild);
      return litBtn;
    },
    async doSave() { return false; }
  });
}

function promptLiteralForAppend(fnId, anchorEl, expectedType) {
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
      return postSequenceAppend(fnId, { value: value });
    },
    onSaved() { if (typeof initGraph === 'function') initGraph(); }
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
      // rich-types) reflects them without the `initGraph` cytoscape re-init.
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

function patchFnFieldInState(fnId, field, value) {
  if (!graphData || !Array.isArray(graphData.fns)) return;
  for (const f of graphData.fns) {
    if (f && f.id === fnId) { f[field] = value; break; }
  }
  if (typeof buildLookups === 'function') lookups = buildLookups(graphData);
  if (typeof rebuildImplementationFnIds === 'function') rebuildImplementationFnIds();
}
