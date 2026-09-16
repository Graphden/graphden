// Editor Edit Modes (fn-level) — inline edit popovers that act on a
// WHOLE fn-def: extend (create child — in place at a use-site), rename,
// declared-effects contract (server-rendered form), return-type,
// namespace move, plus the shared patchFnFieldInState state-patcher and
// the `buildNsChooser` / `resolveJustCreatedFn` / `rebindUseSite` helpers
// Wrap shares. The popover skeleton, value / secret / rename / sequence
// modes and the network helpers live in editor-edit-modes.js; Wrap (a new
// CALLER of this fn) in editor-edit-modes-wrap.js, which loads right after.

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
//
// EXTEND IN PLACE (2026-09-16, from a reader walking lesson 15). At a
// USE-SITE — the ⋯ of a card that sits on the canvas because a slot of
// the fn being built binds it (`opts.useSiteArg`, the binding or list
// item that put it there) — Extend does one more thing and one less:
// the child TAKES THIS FN'S PLACE in that slot, and the editor stays on
// the canvas it is on. That is how a composition is built from the
// outside in: bind the base fn a slot needs (`map`), then extend it
// right there — the child appears where `map` was, with its own `+`s,
// and the next inner fn is made the same way from the child's card.
// Before, every inner fn meant Explorer → ⋯ → Extend (which opens the
// child) → back to the outer fn → find the child again by name.
// Which namespace should a fresh child land in? The PARENT's, always
// (decision 2026-09-15). The earlier rule — "a package parent defaults to
// your last-used namespace" — read as "the first namespace in the list"
// whenever the remembered one was stale, and a child that lands next to
// its parent is the one place the reader looks for it first. The popover
// shows the choice either way ("in <ns>"), with ↑ and + to leave it.
function extendDefaultNsId(fn) {
  return fn['namespace-id'] || null;
}

// The parent namespace of `nsId` (null for a root namespace / the root).
function nsParentIdOf(nsId) {
  if (!nsId || typeof lookups === 'undefined' || !lookups?.nsMap) return null;
  const ns = lookups.nsMap.get(nsId);
  return ns?.['parent-id'] || null;
}

// The "in <namespace>" chooser: the "(root)" + every-namespace select,
// sorted by dotted path, plus two ways OUT of the list — `↑` moves the
// choice one level up (core.arithmetic → core → (root)), `+` opens an
// inline row for a NEW sub-namespace under the current choice, typed as
// a single segment (the prefix is shown, never retyped). The sub-namespace
// is created on Save, right before the fn, by `chooser.resolve()`.
// Inline rather than a nested picker: a nested popover fights the
// inline-editor's outside-click dismissal.
function buildNsChooser(defaultNsId, ariaLabel) {
  const row = document.createElement('label');
  row.className = 'extend-ns-row';
  const cap = document.createElement('span');
  cap.className = 'extend-ns-cap';
  cap.textContent = 'in';
  const sel = document.createElement('select');
  sel.className = 'extend-ns-select';
  sel.setAttribute('aria-label', ariaLabel);
  const rootOpt = document.createElement('option');
  rootOpt.value = '';
  rootOpt.textContent = '(root)';
  sel.appendChild(rootOpt);
  const paths = [];
  if (typeof lookups !== 'undefined' && lookups?.nsPathMap) {
    for (const [id, path] of lookups.nsPathMap) paths.push([path, id]);
  }
  paths.sort((a, b) => a[0].localeCompare(b[0]));
  for (const [path, id] of paths) {
    const opt = document.createElement('option');
    opt.value = id;
    opt.textContent = path;
    sel.appendChild(opt);
  }
  sel.value = defaultNsId || '';
  if (sel.value !== (defaultNsId || '')) sel.value = '';

  const up = document.createElement('button');
  up.type = 'button';
  up.className = 'extend-ns-btn extend-ns-up';
  up.textContent = '↑';
  up.title = 'Up one level — the parent namespace';
  up.setAttribute('aria-label', 'Up one level — the parent namespace');
  const plus = document.createElement('button');
  plus.type = 'button';
  plus.className = 'extend-ns-btn extend-ns-plus';
  plus.textContent = '+';
  plus.title = 'New sub-namespace under this one';
  plus.setAttribute('aria-label', 'New sub-namespace under this one');

  // The inline "new sub-namespace" row: `<prefix>.` + one segment.
  const sub = document.createElement('div');
  sub.className = 'extend-ns-new';
  sub.hidden = true;
  const subCap = document.createElement('span');
  subCap.className = 'extend-ns-cap';
  subCap.textContent = 'new';
  const subPrefix = document.createElement('span');
  subPrefix.className = 'extend-ns-new-prefix';
  const subInput = document.createElement('input');
  subInput.type = 'text';
  subInput.className = 'extend-ns-new-input';
  subInput.placeholder = 'name';
  subInput.setAttribute('aria-label', 'New sub-namespace name');
  sub.appendChild(subCap);
  sub.appendChild(subPrefix);
  sub.appendChild(subInput);

  const pathOf = (id) => (id && typeof lookups !== 'undefined' && lookups?.nsPathMap)
    ? (lookups.nsPathMap.get(id) || '') : '';
  const refresh = () => {
    up.disabled = !sel.value;
    subPrefix.textContent = sel.value ? pathOf(sel.value) + '.' : '';
  };
  sel.addEventListener('change', refresh);
  up.addEventListener('click', () => {
    sel.value = nsParentIdOf(sel.value) || '';
    refresh();
  });
  plus.addEventListener('click', () => {
    sub.hidden = !sub.hidden;
    refresh();
    if (!sub.hidden) subInput.focus();
  });
  // Enter in the segment field must not submit the popover with a
  // half-typed name; it just returns to the fn-name field.
  subInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); }
  });
  refresh();

  row.appendChild(cap);
  row.appendChild(sel);
  row.appendChild(up);
  row.appendChild(plus);

  return {
    row, sub, select: sel,
    // The namespace the fn should land in, creating the pending
    // sub-namespace first: `{ok, nsId}` or `{ok:false, error}`.
    async resolve() {
      const seg = sub.hidden ? '' : (subInput.value || '').trim();
      if (!seg) return { ok: true, nsId: sel.value || '' };
      if (seg.includes('.') || /\s/.test(seg)) {
        return { ok: false, error: 'A sub-namespace name is one segment — no dots or spaces.' };
      }
      const parentId = sel.value || '';
      const r = await postEntity('ns', { name: seg, 'parent-id': parentId });
      if (!(r && r.status >= 200 && r.status < 300)) {
        let error = 'Could not create the namespace.';
        try {
          if (typeof extractResponseError === 'function') error = await extractResponseError(r);
        } catch (_) { /* generic */ }
        return { ok: false, error };
      }
      const nsId = (typeof gdUndoFindNsId === 'function')
        ? await gdUndoFindNsId(seg, parentId || null) : null;
      if (!nsId) return { ok: false, error: 'Created ' + seg + ', but could not find it — reload and retry.' };
      if (typeof gdUndoRecordCreatedNs === 'function') gdUndoRecordCreatedNs(seg, parentId || null);
      return { ok: true, nsId };
    },
  };
}

function enterExtendEditMode(fn, anchorEl, opts) {
  if (!fn) return;
  let pendingName = '';
  let pendingNsId = null;
  let nsChooser = null;
  // The binding / list item this card is on the canvas THROUGH — set
  // only at a use-site (see the header above). Its owner is the fn whose
  // slot the child will fill.
  const useSiteArg = opts?.useSiteArg || null;
  const siteOwner = useSiteArg ? lookups?.fnMap?.get(useSiteArg['fn-id']) : null;
  const siteSlot = useSiteArg?.name ? ':' + useSiteArg.name : 'the slot';
  openInlineEditPopover({
    anchorEl,
    ariaLabel: useSiteArg
      ? 'Extend ' + (fn.name || 'this fn') + ' in place — the child takes its place in ' + siteSlot
      : 'Extend (create child fn)',
    makeControl(root) {
      const hint = document.createElement('div');
      hint.className = 'arg-value-edit-hint';
      hint.textContent = useSiteArg
        ? ('Creates a new fn with :parent ' + (fn.name || '(this fn)')
           + ' and puts it in ' + siteSlot
           + (siteOwner?.name ? ' of ' + siteOwner.name : '')
           + ' in place of ' + (fn.name || 'this fn') + ' — you stay on this canvas.')
        : ('Creates a new fn with :parent '
           + (fn.name || '(this fn)') + '. Open it to add new bindings or renames.');
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.placeholder = 'New fn name';
      nsChooser = buildNsChooser(extendDefaultNsId(fn), 'Namespace for the new fn');
      root.insertBefore(nsChooser.sub, root.firstChild);
      root.insertBefore(nsChooser.row, root.firstChild);
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
      const work = async () => {
        const ns = nsChooser ? await nsChooser.resolve()
                             : { ok: true, nsId: fn['namespace-id'] || '' };
        if (!ns.ok) return ns;
        const nsId = ns.nsId;
        pendingNsId = nsId || null;
        const fields = { name: newName, 'parent-ids': fn.id, 'namespace-id': nsId };
        try {
          const r = await postEntity('fn', fields);
          if (!(r && r.status >= 200 && r.status < 300)) return false;
          if (typeof gdRememberLastNs === 'function') gdRememberLastNs(nsId || null);
          if (!useSiteArg) {
            if (typeof gdUndoRecordCreatedFn === 'function') {
              const backTo = (typeof getQualifiedFnName === 'function') ? getQualifiedFnName(fn) : fn.name;
              gdUndoRecordCreatedFn(newName, nsId || null, backTo);
            }
            return true;
          }
          // In place: the child takes this fn's place in the slot. The
          // create answered HTML, not an id — resolve the child by
          // (namespace-qualified) name, with retries for read-after-write
          // lag (the same reality selectJustCreatedFn handles).
          const child = await resolveJustCreatedFn(newName, nsId);
          if (!child?.id) {
            return { ok: false, error: 'Created ' + newName
              + ', but could not find it to bind — reload and bind by hand.' };
          }
          const rebound = await rebindUseSite(useSiteArg, child.id);
          if (!rebound.ok) {
            return { ok: false, error: 'Created ' + newName + ', but could not put it in '
              + siteSlot + ': ' + (rebound.error || 'the server refused') };
          }
          if (typeof gdUndoRecordExtendInPlace === 'function') {
            gdUndoRecordExtendInPlace(newName, nsId || null, useSiteArg, fn.id);
          }
          return true;
        } catch (_) {}
        return false;
      };
      return (typeof withBusy === 'function')
        ? await withBusy(opKey, 'Creating ' + newName + '…', work)
        : await work();
    },
    onSaved() {
      // In place: stay here. The slot now points at the child, so the
      // lighter `loadGraphData` redraws the canvas with the child's card
      // where this fn's was — then fit, so the new card (and its `+`s)
      // is on screen, not under the Inspector.
      if (useSiteArg) {
        const opKey = 'extend-in-place-finalise:' + fn.id;
        const redraw = async () => {
          if (typeof loadGraphData === 'function') await loadGraphData();
          if (typeof fitGraphIfOverflowing === 'function') {
            const wait = (typeof ANIM_DURATION === 'number' ? ANIM_DURATION : 300) + 30;
            setTimeout(fitGraphIfOverflowing, wait);
          }
        };
        if (typeof withBusy === 'function') withBusy(opKey, 'Loading ' + (pendingName || 'new fn') + '…', redraw);
        else redraw();
        return;
      }
      // Refetch + auto-select the new fn so the user sees its empty
      // body and can start adding bindings via the existing edit
      // affordances. The whole "init + select" sequence runs under
      // the same busy slot as the create, so the banner stays up
      // until the user-visible state matches the DB.
      const opKey = 'extend-finalise:' + fn.id;
      const finalise = async () => {
        if (typeof initGraph === 'function') await initGraph();
        if (pendingName && typeof selectJustCreatedFn === 'function') {
          await selectJustCreatedFn(pendingName);
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
// The row a create just wrote, by (name, namespace) through the search
// scope — retried for read-after-write lag. Shared by extend-in-place and
// wrap, which both need the id the HTML-answering create endpoint omits.
async function resolveJustCreatedFn(name, nsId, tries = 10, gapMs = 300) {
  for (let i = 0; i < tries; i++) {
    await new Promise((res) => setTimeout(res, gapMs));
    try {
      const sr = await fetch(API.api_graph_entities
        + '?scope=search&q=' + encodeURIComponent(name));
      const sd = sr.ok ? await sr.json() : null;
      const hit = (sd?.fns || []).find((f) => f.name === name
        && String(f['namespace-id'] || '') === String(nsId || ''));
      if (hit) return hit;
    } catch (_) { /* retry */ }
  }
  return null;
}

// Point the use-site at `newFnId`: a binding's `ref-fn-id`, or a list
// item's `ref` (PUT /api/sequence/item/:id, the same body append takes).
// No per-write undo entry — the caller records the combined inverse.
async function rebindUseSite(arg, newFnId) {
  if (arg?.['item-id']) {
    try {
      const r = await authFetch(API.api_sequence_item_item_id(arg['item-id']), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ref: newFnId })
      });
      return r?.ok ? { ok: true } : { ok: false, error: await responseError(r) };
    } catch (_) {
      return { ok: false, error: 'network error' };
    }
  }
  return await writeBindingFields(arg, { 'ref-fn-id': newFnId }, { undo: false });
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
        if (r?.ok) {
          if (newName !== fn.name && typeof gdUndoRecordRename === 'function') {
            gdUndoRecordRename(fn.id, fn.name, newName);
          }
          return true;
        }
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
    // Full refresh — these write the fn ROW, which `lookups.fnMap` and
    // the inspector's Overview (RETURNS / EFFECTS) read; `renderGraph`
    // alone left both stale until the next selection.
    onSaved() { if (typeof loadGraphData === 'function') loadGraphData(); }
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
    // Full refresh — these write the fn ROW, which `lookups.fnMap` and
    // the inspector's Overview (RETURNS / EFFECTS) read; `renderGraph`
    // alone left both stale until the next selection.
    onSaved() { if (typeof loadGraphData === 'function') loadGraphData(); }
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
        if (r?.ok) {
          if (typeof gdRememberLastNs === 'function') gdRememberLastNs(picked.id || null);
          if (typeof gdUndoRecordNsMove === 'function') {
            gdUndoRecordNsMove(fn.id, fn.name, fn['namespace-id'] || null, picked.id || null);
          }
          if (typeof initGraph === 'function') initGraph();
        }
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
