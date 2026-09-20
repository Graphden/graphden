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
    onSaved() { if (typeof loadGraphData === 'function') loadGraphData(); }
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
    onSaved() { if (typeof loadGraphData === 'function') loadGraphData(); }
  });
}
