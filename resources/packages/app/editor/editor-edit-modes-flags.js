// Editor Edit Modes — fn-row FLAGS: the two card strips that write a
// single field of the fn ROW and nothing else.
//   λ  `:lambda-params`  — call-site parameters when the fn is handed
//                          over as a callable (the return-type strip's
//                          λ chip, editor-overlay-strips.js);
//   📍 `:branch-local?`  — "version rows never merge" (the branch-local
//                          strip).
// Both go through `openInlineEditPopover` (editor-edit-modes.js), PUT
// `/api/entities/fn/:id` with the form field `parse-fn-from-form`
// reads (web/crud-parse), patch the row in `graphData`, record their
// inverse with editor-undo.js and reload the graph (the strips read
// layout FACTS the server recomputes).
//
// Loads after editor-edit-modes-fn.js (`patchFnFieldInState`) and before
// the overlay modules that call these entry points.
//
// Two SURFACES open these popovers: the card strips (editor-overlay-
// strips.js) and the Inspector Overview's rows — "Returns", "Effects",
// "Call-site params", "Merge" (`gdBindInspectorFlagRows`, called by
// editor-inspector.js after the partial lands). Compact cards — the
// default — hide the return-type strip (with its λ chip and `↳`) and
// the effects strip (with its ✎ pencil and chips), so the Inspector is
// where every reader can reach all four; both surfaces gate on
// `gdFlagEditable`. The return-type and effects popovers themselves live
// in editor-edit-modes-fn.js.

// The fn-row FLAGS take the effects pencil's looser gate, not
// `isFnEditable`'s "no dependents": a fn is handed to a HOF or extended
// BECAUSE it is referenced, and that is exactly when its calling
// convention or merge policy needs saying. Ownership (tenancy) and
// package ownership still apply; a type-row has neither flag.
function gdFlagEditable(fn) {
  if (!fn) return false;
  const composed = Array.isArray(fn['parent-ids']) && fn['parent-ids'].length > 0;
  return composed
    && (typeof isAuthenticated === 'function' && isAuthenticated())
    && !(typeof isPackageOwnedFn === 'function' && isPackageOwnedFn(fn.id))
    && ((typeof graphdenIsFnOwned !== 'function') || graphdenIsFnOwned(fn));
}


// Wire the Inspector Overview's flag rows: on a fn the reader may edit, a
// row becomes a button that opens the same popover the card strip opens.
// Facts for the branch-local popover ride on the row (`data-state`,
// `data-seed`) — the server computed them, the client does not re-walk.
function gdBindInspectorFlagRows(host) {
  if (!host) return;
  // The fn row may not be in `lookups` yet when the Overview lands right
  // after an Extend (the inspector renders on selection, the graph
  // reload follows) — the server only prints these rows for a composed
  // fn, so a signed-in reader gets the affordance and the fn is resolved
  // again at click time.
  const fnOf = (id) => lookups?.fnMap?.get(id)
    || (typeof graphData !== 'undefined' && graphData?.fns || []).find((f) => f.id === id)
    || null;
  // Read-side disclosures every reader gets, owner or not — the same two
  // the card strips carry: an effect chip opens the effect explainer, the
  // `↳` opens the type-rule popover. Both stop the click so an editable
  // row underneath does not also open its form.
  for (const chip of host.querySelectorAll('.gd-insp-flag-row [data-effect]')) {
    const open = (e) => {
      e.stopPropagation();
      if (typeof showEffectExplainer === 'function') {
        showEffectExplainer({ effect: chip.dataset.effect, anchorEl: chip });
      }
    };
    chip.title = 'Effect: ' + chip.dataset.effect + ' — click for details';
    chip.addEventListener('click', open);
    chip.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(e); }
    });
  }
  for (const btn of host.querySelectorAll('.gd-insp-flag-row .gd-insp-rt-rule')) {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      const fn = fnOf(btn.closest('.gd-insp-flag-row')?.dataset.fnId);
      if (fn && typeof showReturnTypeRulePopover === 'function') {
        showReturnTypeRulePopover(fn.name, btn);
      }
    });
  }
  for (const row of host.querySelectorAll('.gd-insp-flag-row[data-action]')) {
    const fn0 = fnOf(row.dataset.fnId);
    const editable = fn0 ? gdFlagEditable(fn0)
                         : (typeof isAuthenticated === 'function' && isAuthenticated());
    if (!editable) continue;
    row.classList.add('gd-insp-editable');
    row.tabIndex = 0;
    row.setAttribute('role', 'button');
    row.title = (row.title ? row.title + ' — ' : '') + 'click to change';
    const open = () => {
      const fn = fnOf(row.dataset.fnId);
      if (!fn || !gdFlagEditable(fn)) return;
      if (row.dataset.action === 'lambda-params') {
        enterLambdaParamsEditMode(fn, row);
      } else if (row.dataset.action === 'return-type') {
        enterFnReturnTypeEditMode(fn, row);
      } else if (row.dataset.action === 'expects-effects') {
        enterExpectsEffectsEditMode(fn, row);
      } else if (row.dataset.action === 'branch-local') {
        const state = row.dataset.state;
        const facts = state === 'off' ? null : { own: state === 'own', seed: row.dataset.seed || null };
        enterBranchLocalEditMode(fn, row, facts);
      }
    };
    row.addEventListener('click', (e) => { e.stopPropagation(); open(); });
    row.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
    });
  }
}


// After a flag write the canvas reloads (the strips read layout facts) and
// the Inspector, if it shows this fn, re-renders its Overview rows.
function gdAfterFlagSaved(fn) {
  if (typeof loadGraphData === 'function') loadGraphData();
  if (typeof window.gdInspectorRender === 'function'
      && document.querySelector('.gd-insp-flag-row[data-fn-id="' + fn.id + '"]')) {
    window.gdInspectorRender(fn.id);
  }
}

// --- λ call-site parameters (`:lambda-params`) ---
//
// Click on the λ chip of the return-type strip (editor-overlay-strips.js).
// Three states, one field on the fn ROW:
//   derived  — nil: when a HOF / route calls this fn, the compile picks its
//              one unambiguous free arg and refuses (`:compile/ambiguous-
//              lambda-params`) when several qualify;
//   none     — `[]`: everything is captured from the graph, the callable
//              takes no per-call input (a handler chain);
//   named    — an ORDERED list of this fn's free-arg names filled per call.
// The wire is the same three-state CSV `:expects-effects` uses (blank →
// nil, "[]" → [], csv → vector) — `parse-fn-from-form`'s lambda-params
// fragment; `authMutate` strips empty strings, so the clear ships a
// pre-encoded body. The candidate list is the fn's public free args from
// the rich-types registry (`richTypeEntryOf(fn).args`); a name typed by
// hand is accepted too — the compile validates it against the frees.

function _lambdaCandidateNames(fn) {
  const entry = (typeof richTypeEntryOf === 'function') ? richTypeEntryOf(fn) : null;
  const args = entry?.args && typeof entry.args === 'object' ? Object.keys(entry.args) : [];
  return args.map((k) => String(k).replace(/^:/, ''));
}


function enterLambdaParamsEditMode(fn, anchorEl) {
  if (!fn) return;
  const declared = fn['lambda-params'];
  const mode0 = declared == null ? 'derived' : (declared.length === 0 ? 'none' : 'named');
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Call-site parameters',
    makeControl(root) {
      const wrap = document.createElement('div');
      wrap.className = 'lambda-params-edit';
      const head = document.createElement('div');
      head.className = 'lambda-params-edit-head';
      head.textContent = 'λ call-site parameters — what a HOF or a route fills per call when it hands this fn over as a callable';
      wrap.appendChild(head);
      const radios = [
        ['derived', 'Derived', 'the compile picks the one unambiguous free arg; refuses when several qualify'],
        ['none', 'None — []', 'everything is captured from the graph; the callable takes no per-call input (a handler chain)'],
        ['named', 'These, in order', 'the listed free args are filled per call; the rest is captured'],
      ];
      const list = document.createElement('div');
      list.className = 'lambda-params-edit-names';
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input lambda-params-edit-input';
      input.placeholder = 'request, item';
      input.value = Array.isArray(declared) ? declared.join(', ') : '';
      input.setAttribute('aria-label', 'Parameter names, in order');
      const current = () => input.value.split(',').map((s) => s.trim().replace(/^:/, '')).filter(Boolean);
      const setNames = (names) => {
        input.value = names.join(', ');
        for (const cb of list.querySelectorAll('input[type="checkbox"]')) cb.checked = names.includes(cb.value);
      };
      for (const name of _lambdaCandidateNames(fn)) {
        const lab = document.createElement('label');
        lab.className = 'lambda-params-edit-name';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.value = name;
        cb.dataset.lambdaName = name;
        cb.checked = Array.isArray(declared) && declared.includes(name);
        // Ticking APPENDS (order = the order you tick); unticking removes.
        cb.addEventListener('change', () => {
          const names = current().filter((n) => n !== name);
          setNames(cb.checked ? [...names, name] : names);
        });
        lab.appendChild(cb);
        lab.appendChild(document.createTextNode(' ' + name));
        list.appendChild(lab);
      }
      input.addEventListener('input', () => {
        const names = current();
        for (const cb of list.querySelectorAll('input[type="checkbox"]')) cb.checked = names.includes(cb.value);
      });
      const refresh = () => {
        const named = wrap.querySelector('input[name="lp-mode"][value="named"]')?.checked;
        input.disabled = !named;
        for (const cb of list.querySelectorAll('input[type="checkbox"]')) cb.disabled = !named;
        list.classList.toggle('lambda-params-edit-names-off', !named);
      };
      for (const [value, label, hint] of radios) {
        const row = document.createElement('label');
        row.className = 'lambda-params-edit-mode';
        const r = document.createElement('input');
        r.type = 'radio';
        r.name = 'lp-mode';
        r.value = value;
        r.checked = value === mode0;
        r.addEventListener('change', refresh);
        row.appendChild(r);
        const text = document.createElement('span');
        text.textContent = ' ' + label;
        row.appendChild(text);
        const h = document.createElement('span');
        h.className = 'lambda-params-edit-hint';
        h.textContent = ' — ' + hint;
        row.appendChild(h);
        wrap.appendChild(row);
        if (value === 'named') {
          wrap.appendChild(list);
          wrap.appendChild(input);
        }
      }
      refresh();
      wrap._collect = () => {
        const mode = wrap.querySelector('input[name="lp-mode"]:checked')?.value || 'derived';
        if (mode === 'derived') return null;
        if (mode === 'none') return [];
        return current();
      };
      root.insertBefore(wrap, root.firstChild);
      return wrap;
    },
    async doSave(control) {
      const value = control._collect();
      if (Array.isArray(value) && value.length === 0
          && control.querySelector('input[name="lp-mode"]:checked')?.value === 'named') {
        return { ok: false, error: 'Name at least one parameter, or pick "None — []".' };
      }
      const body = value == null ? 'lambda-params='
                 : value.length === 0 ? { 'lambda-params': '[]' }
                 : { 'lambda-params': value.join(',') };
      try {
        const r = await authMutate('PUT', API.api_entities_type_id('fn', fn.id), body);
        if (r?.ok) {
          const before = fn['lambda-params'] == null ? null : fn['lambda-params'].slice();
          patchFnFieldInState(fn.id, 'lambda-params', value);
          if (typeof gdUndoRecord === 'function') {
            gdUndoRecord({
              label: 'Set call-site parameters of ' + fn.name,
              undo: async () => {
                const back = before == null ? 'lambda-params='
                           : before.length === 0 ? { 'lambda-params': '[]' }
                           : { 'lambda-params': before.join(',') };
                const u = await authMutate('PUT', API.api_entities_type_id('fn', fn.id), back);
                if (!(u?.ok)) return { ok: false, error: 'the server refused' };
                patchFnFieldInState(fn.id, 'lambda-params', before);
                if (typeof loadGraphData === 'function') await loadGraphData();
                return { ok: true };
              },
            });
          }
          return true;
        }
        let error = 'Save failed.';
        try { error = (await extractResponseError(r)) || error; } catch (_) { /* generic */ }
        return { ok: false, error };
      } catch (_) {}
      return false;
    },
    onSaved() { gdAfterFlagSaved(fn); }
  });
}


// --- 📍 branch-local ---
//
// Click on the branch-local strip (editor-overlay-strips.js). One checkbox
// on the fn ROW's identity-level `:branch-local?`: "version rows of this fn
// never propagate on merge" — per-environment config (a port, a path, a
// schedule) that a merge must not carry. Monotonic-OR over parent-ids: a
// fn under a sticky-local ancestor is local whatever its own row says, so
// the box is read-only there and names the seed (`facts.seed`, from the
// layout's strip-facts); the server refuses the widening write as well
// (`crud.validation/branch-local-rej`).

function enterBranchLocalEditMode(fn, anchorEl, facts) {
  if (!fn) return;
  const inherited = !!(facts && !facts.own);
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Branch-local',
    makeControl(root) {
      const wrap = document.createElement('div');
      wrap.className = 'branch-local-edit';
      const row = document.createElement('label');
      row.className = 'branch-local-edit-row';
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.dataset.branchLocal = 'toggle';
      cb.checked = !!(facts) || fn['branch-local?'] === true;
      cb.disabled = inherited;
      row.appendChild(cb);
      row.appendChild(document.createTextNode(' Branch-local — version rows of this fn stay on the branch they were written on; a merge never carries them'));
      wrap.appendChild(row);
      const hint = document.createElement('div');
      hint.className = 'branch-local-edit-hint';
      hint.textContent = inherited
        ? ('Inherited from :' + (facts.seed || '<anon>') + ' — a sticky-local ancestor makes every descendant branch-local; re-parent off it to change that.')
        : 'For per-environment configuration: a web-server port, a secret path, a schedule. The flag is on the fn identity — every branch sees the same setting.';
      wrap.appendChild(hint);
      wrap._cb = cb;
      root.insertBefore(wrap, root.firstChild);
      return wrap;
    },
    async doSave(control) {
      if (inherited) return true;
      const next = !!control._cb.checked;
      const before = fn['branch-local?'] === true;
      if (next === before) return true;
      try {
        const r = await authMutate('PUT', API.api_entities_type_id('fn', fn.id),
                                   { 'branch-local': next ? 'true' : 'false' });
        if (r?.ok) {
          patchFnFieldInState(fn.id, 'branch-local?', next);
          if (typeof gdUndoRecord === 'function') {
            gdUndoRecord({
              label: (next ? 'Made ' : 'Unpinned ') + fn.name + (next ? ' branch-local' : ' from its branch'),
              undo: async () => {
                const u = await authMutate('PUT', API.api_entities_type_id('fn', fn.id),
                                           { 'branch-local': before ? 'true' : 'false' });
                if (!(u?.ok)) return { ok: false, error: 'the server refused' };
                patchFnFieldInState(fn.id, 'branch-local?', before);
                if (typeof loadGraphData === 'function') await loadGraphData();
                return { ok: true };
              },
            });
          }
          return true;
        }
        let error = 'Save failed.';
        try { error = (await extractResponseError(r)) || error; } catch (_) { /* generic */ }
        return { ok: false, error };
      } catch (_) {}
      return false;
    },
    // The strip reads a layout FACT (the server's parent-ids walk), so a
    // full graph reload is what redraws it.
    onSaved() { gdAfterFlagSaved(fn); }
  });
}
