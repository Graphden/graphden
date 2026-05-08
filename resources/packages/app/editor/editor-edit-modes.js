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
    const ok = await opts.doSave(control);
    if (ok) {
      closeInlineEdit();
      if (typeof opts.onSaved === 'function') opts.onSaved();
    } else {
      save.disabled = false;
      cancel.disabled = false;
      errorEl.textContent = 'Save failed — check that you’re signed in.';
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
  const expected = expectedSlotType(arg);
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Edit arg value',
    makeControl(root) {
      // Hint line above the input — only when we know the slot's
      // expected type. Helps the user pick a value compatible with
      // the saved type-check; refinements show their constraint.
      if (expected) {
        const hint = document.createElement('div');
        hint.className = 'arg-value-edit-hint';
        hint.textContent = 'Expected: ' + formatTypeHint(expected);
        root.insertBefore(hint, root.firstChild);
      }
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      const v = arg.value;
      input.value = (typeof v === 'string') ? v
                  : (v === null || v === undefined) ? ''
                  : JSON.stringify(v);
      root.insertBefore(input, root.firstChild);

      // Live validation status — mirrors saveArgValue's smart-parse
      // (try JSON, fall back to raw string), then runs the same
      // literal-vs-type check the backend will run on save. Lets the
      // user fix typos before the round-trip.
      if (expected) {
        const status = document.createElement('div');
        status.className = 'arg-value-edit-status';
        const update = () => {
          const trimmed = (input.value || '').trim();
          if (trimmed === '') {
            status.textContent = '';
            status.classList.remove('ok', 'err');
            return;
          }
          let parsed;
          try { parsed = JSON.parse(trimmed); }
          catch (_) { parsed = input.value; }
          const r = validateLiteralAgainstType(parsed, expected);
          status.textContent = (r.ok ? '✓ ' : '✗ ') + (r.message || '');
          status.classList.toggle('ok',  r.ok);
          status.classList.toggle('err', !r.ok);
        };
        input.addEventListener('input', update);
        // Render once on open so the user sees status for the
        // pre-filled value.
        setTimeout(update, 0);
        root.insertBefore(status, input.nextSibling);
      }
      return input;
    },
    async doSave(input) { return saveArgValue(arg, input.value); },
    onSaved()           { if (typeof renderGraph === 'function') renderGraph(false); },
    // Delete drops the binding so the slot reverts to a free-arg
    // placeholder. From there the user can re-bind to a literal OR a
    // fn-ref via the placeholder's chooser — single inline path for
    // switching a bound literal to anything else.
    onDelete: arg['binding-id']
              ? () => { if (typeof deleteUseSiteBinding === 'function') deleteUseSiteBinding(arg); }
              : null
  });
}

// Slot/binding-aware writer: PUT /api/entities/binding/:id when the
// slot already has an own binding on this fn, POST a new binding
// otherwise. `arg` carries `fn-id` + `slot-id` (+ optional `binding-id`)
// — those come from the synth-arg adapter, populated server-side from
// the slot/binding rows. Editor JS no longer touches /api/entities/arg.
async function writeBindingFields(arg, fields) {
  if (!arg) return false;
  const fnId = arg['fn-id'];
  const slotId = arg['slot-id'];
  const bindingId = arg['binding-id'];
  if (!fnId || !slotId) return false;
  const body = Object.entries(fields)
    .map(([k, v]) => k + '=' + encodeURIComponent(v == null ? '' : v))
    .join('&');
  try {
    if (bindingId) {
      const r = await authMutate('PUT',
                                 '/api/entities/binding/' + encodeURIComponent(bindingId),
                                 body);
      return !!(r?.ok);
    }
    const r = await authMutate('POST', '/api/entities/binding',
                               'fn-id=' + encodeURIComponent(fnId) +
                               '&slot-id=' + encodeURIComponent(slotId) +
                               (body ? '&' + body : ''));
    return !!(r?.ok);
  } catch (_) {
    return false;
  }
}

async function saveArgValue(arg, rawInput) {
  // Smart parse: trim, try JSON, fall back to raw string. Empty input
  // is rejected here because the backend's permissive parse skips
  // blank `value=` (so a clear would be a no-op). Phase 2 type-change
  // will be the path to clear values.
  const trimmed = (rawInput || '').trim();
  if (trimmed === '') return false;
  let parsed;
  try { parsed = JSON.parse(trimmed); }
  catch (_) { parsed = rawInput; }
  const jsonStr = JSON.stringify(parsed);
  return await writeBindingFields(arg, { value: jsonStr });
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
                                   '/api/entities/fn/' + encodeURIComponent(fn.id),
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
                                   '/api/entities/fn/' + encodeURIComponent(fn.id),
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
  const results = await Promise.all(allNames.map(async name => {
    try {
      const r = await fetch('/api/types/compatible', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expected, candidate: name })
      }).then(r => r.json());
      return { name, ok: !!r.ok };
    } catch (_) { return { name, ok: false }; }
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
        const o = document.createElement('option');
        o.value = cur;
        o.textContent = cur + ' (current)';
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
      // the primitive type-row. Find that row by name in the loaded
      // graph snapshot. Empty string clears the override.
      const primitiveFnId = (() => {
        if (!newType || !graphData) return '';
        const fn = (graphData.fns || []).find(f =>
          f.name === newType
          && (!f['parent-ids'] || f['parent-ids'].length === 0)
          && !f['impl-hash']
          && !f['base-fn-id']
          && !f['element-fn-id']);
        return fn ? fn.id : '';
      })();
      if (!await writeBindingFields(arg, {
        'type-override-fn-id': primitiveFnId,
        value: '',
        'ref-fn-id': ''
      })) return false;
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
        const r = await authMutate('PUT',
                                   '/api/entities/fn/' + encodeURIComponent(fn.id),
                                   { 'namespace-id': picked.id || '' });
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

async function appendSequenceItem(fnId, anchorEl) {
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
        promptLiteralForAppend(fnId, anchorEl);
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

function promptLiteralForAppend(fnId, anchorEl) {
  openInlineEditPopover({
    anchorEl: anchorEl || document.body,
    ariaLabel: 'Enter literal value to append',
    makeControl(root) {
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.placeholder = 'JSON value (e.g. 42, "text", true)';
      root.insertBefore(input, root.firstChild);
      return input;
    },
    async doSave(input) {
      const trimmed = (input.value || '').trim();
      if (trimmed === '') return false;
      let parsed;
      try { parsed = JSON.parse(trimmed); }
      catch (_) { parsed = input.value; }
      return postSequenceAppend(fnId, { value: parsed });
    },
    onSaved() { if (typeof initGraph === 'function') initGraph(); }
  });
}

async function postSequenceAppend(fnId, body) {
  try {
    const r = await authFetch('/api/sequence/append/' + encodeURIComponent(fnId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (r?.ok) {
      if (typeof initGraph === 'function') initGraph();
      return true;
    }
  } catch (_) {}
  return false;
}

async function removeSequenceItem(itemId) {
  if (!itemId) return false;
  try {
    const r = await authMutate('DELETE',
                               '/api/sequence/item/' + encodeURIComponent(itemId));
    if (r?.ok) {
      if (typeof initGraph === 'function') initGraph();
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
                               '/api/entities/binding/' + encodeURIComponent(bindingId));
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
  if (await writeBindingFields(arg, { 'ref-fn-id': refFnId })) {
    if (typeof initGraph === 'function') initGraph();
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
