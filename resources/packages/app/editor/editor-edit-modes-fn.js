// Editor Edit Modes (fn-level) — inline edit popovers that act on a
// WHOLE fn-def: extend (create child), rename, declared-effects
// contract (server-rendered form), return-type, namespace move, plus
// the shared patchFnFieldInState state-patcher. The popover skeleton,
// value / secret / rename / sequence modes and the network helpers
// live in editor-edit-modes.js.

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
function enterExpectsEffectsEditMode(fn, anchorEl) {
  if (!fn) return;
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Edit declared effects',
    makeControl(root) {
      // Server-rendered form (`/partials/expects-effects-form`) —
      // pre-filled from the fn row, with the checkbox roster sourced
      // from the canonical `known-effect-categories` set (the old
      // hand-built grid listed six categories and silently made
      // :process / :raw-sql undeclarable through the UI). JS owns
      // only the radio→enable/disable toggle and the save collect.
      const wrap = document.createElement('div');
      wrap.className = 'expects-effects-edit';
      wrap.textContent = '…';
      authFetch('/partials/expects-effects-form?fn-id='
                + encodeURIComponent(fn.id))
        .then((r) => (r.ok ? r.text() : null))
        .then((html) => {
          if (html == null) {
            wrap.textContent = 'Failed to load the effects form.';
            return;
          }
          const tpl = document.createElement('template');
          tpl.innerHTML = html;
          const form = tpl.content.firstElementChild;
          wrap.textContent = '';
          while (form?.firstChild) wrap.appendChild(form.firstChild);
          const refreshDisabled = () => {
            const contract = wrap.querySelector(
              'input[name="ee-mode"][value="contract"]');
            for (const cb of wrap.querySelectorAll(
              '.expects-effects-grid input[type="checkbox"]')) {
              cb.disabled = !contract?.checked;
            }
          };
          for (const radio of wrap.querySelectorAll('input[name="ee-mode"]')) {
            radio.addEventListener('change', refreshDisabled);
          }
          wrap._loaded = true;
        })
        .catch(() => { wrap.textContent = 'Failed to load the effects form.'; });
      // Expose collected state on the control element for doSave to
      // read — keeping doSave's signature single-argument matches the
      // shape openInlineEditPopover uses for every other edit-mode.
      wrap._collect = () => {
        if (!wrap._loaded) {
          // Belt-and-braces — doSave checks _loaded first and returns
          // a specific error; this throw guards any other caller.
          throw new Error('effects form not loaded');
        }
        const none = wrap.querySelector('input[name="ee-mode"][value="none"]');
        if (!none || none.checked) return null;
        return Array.from(wrap.querySelectorAll(
          '.expects-effects-grid input[type="checkbox"]'))
          .filter((cb) => cb.checked)
          .map((cb) => cb.value);
      };
      root.insertBefore(wrap, root.firstChild);
      return wrap;
    },
    async doSave(control) {
      if (!control._loaded) {
        // Form never arrived — refuse with a SPECIFIC reason instead
        // of the skeleton's generic "check that you're signed in".
        return { ok: false,
                 error: 'The effects form has not finished loading — wait a moment and try again.' };
      }
      try {
        const value = control._collect();
        // The form payload encodes the three states via a single
        // string field: "" (clear → nil), "[]" (explicit empty),
        // or a comma-separated list. parse-fn-from-form does the
        // round-trip.
        // `authMutate`'s field-map form strips empty-string values
        // (same gotcha the namespace-move clear hit), so the
        // clear-contract case must ship a PRE-ENCODED body — a
        // stripped-empty PUT 400s and the UI could never clear a
        // contract. "[]" (pinned purity) and the csv survive the map
        // form fine.
        const body =
          value == null       ? 'expects-effects=' :
          value.length === 0  ? { 'expects-effects': '[]' } :
                                { 'expects-effects': value.join(',') };
        const r = await authMutate('PUT',
          API.api_entities_type_id('fn', fn.id),
          body);
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
      // VALUE_KINDS is boot-fetched from /api/value-kinds — no
      // hand-copied fallback enum (it had already drifted).
      const kinds = (typeof VALUE_KINDS !== 'undefined' && VALUE_KINDS) || [];
      // First option is "(none)" so the user can clear return-type.
      const noneOpt = document.createElement('option');
      noneOpt.value = '';
      noneOpt.textContent = '(none)';
      select.appendChild(noneOpt);
      // Seed the CURRENT type first (same pattern as the arg-type
      // select) — if the boot fetch failed and `kinds` is empty,
      // open-then-Save stays a no-op instead of clearing the field.
      const cur = fn['return-type'];
      if (cur && !kinds.includes(cur)) {
        const curOpt = document.createElement('option');
        curOpt.value = cur;
        curOpt.textContent = cur;
        curOpt.selected = true;
        select.appendChild(curOpt);
      }
      kinds.forEach(k => {
        const o = document.createElement('option');
        o.value = k;
        o.textContent = k;
        if (cur === k) o.selected = true;
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
function patchFnFieldInState(fnId, field, value) {
  if (!graphData || !Array.isArray(graphData.fns)) return;
  for (const f of graphData.fns) {
    if (f && f.id === fnId) { f[field] = value; break; }
  }
  if (typeof buildLookups === 'function') lookups = buildLookups(graphData);
  if (typeof rebuildImplementationFnIds === 'function') rebuildImplementationFnIds();
}
