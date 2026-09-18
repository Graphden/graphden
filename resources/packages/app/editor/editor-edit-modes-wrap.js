// Editor Edit Modes — WRAP in a new fn (graph refactoring): a new CALLER
// that receives this fn's result in a slot of a picked parent.
//
// The module passed the
// 800-line seam when Extend learned to run in place). Uses that file's
// `buildNsChooser`, `extendDefaultNsId` and `resolveJustCreatedFn`; loads
// right after it.

// --- Wrap in new fn (graph refactoring) ---
//
// "Add a step ABOVE the current fn" had no affordance: building
// g(f(...)) starting from f meant creating g by hand, finding f in a
// picker and binding it — five actions and a mental model inversion.
// Wrap does it in two: pick the wrapping parent (which fn should
// process this one's result), then name the wrapper — this fn lands as
// a ref in the chosen slot and the editor opens the new caller.

// Slot candidates for the wrapper = the picked parent's whole slot
// closure (own + inherited), from a scope=subtree fetch kept OFF the
// global graphData (ensureSubtreeFor would re-root the canvas view).
// Free slots (unbound anywhere in the closure) sort first — the seam a
// wrapper normally fills.
async function wrapSlotCandidates(parentFn, wrappedFn) {
  const r = await fetch(API.api_graph_entities
    + '?scope=subtree&root-id=' + encodeURIComponent(parentFn.id));
  if (!r.ok) throw new Error('subtree HTTP ' + r.status);
  const sub = await r.json();
  const fnById = new Map((sub.fns || []).map((f) => [f.id, f]));
  const closure = new Set();
  const queue = [parentFn.id];
  while (queue.length) {
    const id = queue.shift();
    if (closure.has(id)) continue;
    closure.add(id);
    for (const pid of (fnById.get(id)?.['parent-ids'] || [])) queue.push(pid);
  }
  const slotById = new Map((sub.slots || []).map((sl) => [sl.id, sl]));
  const bound = new Set((sub.bindings || [])
    .filter((b) => closure.has(b['fn-id']))
    .map((b) => b['slot-id']));
  const seen = new Set();
  const out = [];
  for (const fs of (sub['fn-slots'] || [])) {
    if (!closure.has(fs['fn-id'])) continue;
    const slot = slotById.get(fs['slot-id']);
    if (!slot || seen.has(slot.id)) continue;
    seen.add(slot.id);
    const typeName = slot['type-fn-id']
      ? fnById.get(slot['type-fn-id'])?.name : null;
    out.push({ id: slot.id, name: slot.name, free: !bound.has(slot.id),
               typeName, compatible: null });
  }
  // Which slots can legally TAKE the wrapped fn's result? Same checker
  // the picker's Compatible split uses — asked per slot, best-effort
  // (an unanswered check just leaves the slot unmarked).
  const ret = (typeof richTypes !== 'undefined')
    ? richTypes?.[wrappedFn?.name]?.return : null;
  if (ret && window.API?.api_types_compatible) {
    await Promise.all(out.map(async (sl) => {
      if (!sl.typeName) return;
      try {
        const cr = await fetch(API.api_types_compatible, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ expected: sl.typeName, candidate: ret }),
        });
        const cd = cr.ok ? await cr.json() : null;
        if (cd && typeof cd.ok === 'boolean') sl.compatible = cd.ok;
      } catch (_) { /* unmarked */ }
    }));
  }
  // Compatible free slots first, then free, then the rest.
  const rank = (sl) => (sl.free && sl.compatible ? 0 : sl.free ? 1 : 2);
  out.sort((a, b) => (rank(a) - rank(b))
    || String(a.name).localeCompare(String(b.name)));
  return out;
}

function enterWrapEditMode(fn, anchorEl) {
  if (!fn) return;
  if (typeof openFnPicker !== 'function') return;
  openFnPicker({
    anchorEl,
    excludeIds: [fn.id],
    onPick: (parent) => {
      if (parent?.id) promptWrapDetails(fn, parent, anchorEl);
    }
  });
}

function promptWrapDetails(fn, parent, anchorEl) {
  let pendingName = '';
  let nsChooser = null;
  let slotSelect = null;
  let slotsReady = null;
  let takeOverBox = null;
  let nameInput = null;
  openInlineEditPopover({
    anchorEl,
    ariaLabel: 'Wrap ' + (fn.name || 'this fn') + ' in a new fn',
    makeControl(root) {
      const hint = document.createElement('div');
      hint.className = 'arg-value-edit-hint';
      hint.textContent = 'Creates a new fn with :parent '
        + (parent.name || '(anonymous)') + ' that receives '
        + (fn.name || 'this fn') + ' in the chosen slot.';
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.placeholder = 'Wrapper fn name';
      nameInput = input;
      // "Take over the name": callers keep calling <name>; the old body
      // becomes _<name>-impl. Only offered on a NAMED fn the user owns —
      // the takeover renames it, which package fns refuse.
      const canTakeOver = !!fn.name
        && !(typeof isPackageOwnedFn === 'function' && isPackageOwnedFn(fn.id))
        && ((typeof graphdenIsFnOwned !== 'function') || graphdenIsFnOwned(fn));
      let takeRow = null;
      if (canTakeOver) {
        takeRow = document.createElement('label');
        takeRow.className = 'extend-ns-row';
        takeOverBox = document.createElement('input');
        takeOverBox.type = 'checkbox';
        takeOverBox.setAttribute('aria-label',
          'Take over the name ' + fn.name + ' — the current fn becomes _'
          + fn.name + '-impl');
        const takeCap = document.createElement('span');
        takeCap.className = 'extend-ns-cap';
        takeCap.textContent = 'take over the name (this fn → _' + fn.name + '-impl)';
        takeOverBox.addEventListener('change', () => {
          if (takeOverBox.checked) {
            input.value = fn.name;
            input.disabled = true;
          } else {
            input.disabled = false;
          }
        });
        takeRow.appendChild(takeOverBox);
        takeRow.appendChild(takeCap);
      }
      nsChooser = buildNsChooser(extendDefaultNsId(fn), 'Namespace for the wrapper fn');
      const nsRow = nsChooser.row;
      const slotRow = document.createElement('label');
      slotRow.className = 'extend-ns-row';
      const slotCap = document.createElement('span');
      slotCap.className = 'extend-ns-cap';
      slotCap.textContent = 'into';
      slotSelect = document.createElement('select');
      slotSelect.className = 'extend-ns-select';
      slotSelect.setAttribute('aria-label',
        'Slot of ' + (parent.name || 'the parent') + ' that receives '
        + (fn.name || 'this fn'));
      const loading = document.createElement('option');
      loading.value = '';
      loading.textContent = 'loading slots…';
      slotSelect.appendChild(loading);
      slotRow.appendChild(slotCap);
      slotRow.appendChild(slotSelect);
      slotsReady = wrapSlotCandidates(parent, fn).then((slots) => {
        slotSelect.replaceChildren();
        for (const sl of slots) {
          const opt = document.createElement('option');
          opt.value = sl.id;
          opt.textContent = ':' + sl.name
            + (sl.compatible === true ? ' ✓' : '')
            + (sl.compatible === false ? ' (type mismatch)' : '')
            + (sl.free ? '' : ' (bound — will override)');
          slotSelect.appendChild(opt);
        }
        if (!slots.length) {
          const none = document.createElement('option');
          none.value = '';
          none.textContent = 'no slots — pick another parent';
          slotSelect.appendChild(none);
        }
        return slots;
      }).catch(() => { slotSelect.replaceChildren(); return []; });
      root.insertBefore(slotRow, root.firstChild);
      root.insertBefore(nsRow, root.firstChild);
      nsRow.insertAdjacentElement('afterend', nsChooser.sub);
      if (takeRow) root.insertBefore(takeRow, root.firstChild);
      root.insertBefore(input, root.firstChild);
      root.insertBefore(hint, root.firstChild);
      return input;
    },
    async doSave(input) {
      const takeOver = !!takeOverBox?.checked;
      const newName = takeOver ? fn.name : (input.value || '').trim();
      if (!newName) return false;
      await slotsReady;
      const slotId = slotSelect ? slotSelect.value : '';
      if (!slotId) return { ok: false, error: 'The parent exposes no slot to receive this fn.' };
      const opKey = 'wrap:' + fn.id + ':' + newName;
      if (typeof isOpInflight === 'function' && isOpInflight(opKey)) return false;
      pendingName = newName;
      const nsPick = nsChooser ? await nsChooser.resolve() : { ok: true, nsId: '' };
      if (!nsPick.ok) return nsPick;
      const nsId = nsPick.nsId;
      const work = async () => {
        try {
          if (takeOver) {
            // Free the name FIRST: the current fn becomes _<name>-impl
            // (the fn-design idiom for a demoted body). Rolled back if
            // the wrapper's create then fails, so a half-takeover never
            // strands the graph nameless.
            const implName = '_' + fn.name + '-impl';
            const rn = await authMutate('PUT',
                                        API.api_entities_type_id('fn', fn.id),
                                        { name: implName });
            if (!rn?.ok) {
              return { ok: false,
                       error: 'Could not rename ' + fn.name + ' to ' + implName
                         + ' — is that name taken?' };
            }
          }
          const r = await postEntity('fn', { name: newName,
                                             'parent-ids': parent.id,
                                             'namespace-id': nsId });
          if (!(r && r.status >= 200 && r.status < 300)) {
            if (takeOver) {
              await authMutate('PUT', API.api_entities_type_id('fn', fn.id),
                               { name: fn.name }).catch(() => {});
            }
            return false;
          }
          // The create endpoint answers HTML, not the new id — resolve it
          // by (name, ns) through the search scope (`resolveJustCreatedFn`).
          const newId = (await resolveJustCreatedFn(newName, nsId))?.id || null;
          if (!newId) {
            if (takeOver) {
              await authMutate('PUT', API.api_entities_type_id('fn', fn.id),
                               { name: fn.name }).catch(() => {});
            }
            return { ok: false, error: 'Created ' + newName
              + ', but could not find it to bind — reload and bind by hand.' };
          }
          const b = await authMutate('POST', API.api_entities_type('binding'),
                                     { 'fn-id': newId, 'slot-id': slotId,
                                       'ref-fn-id': fn.id });
          if (b?.ok) {
            if (typeof gdRememberLastNs === 'function') gdRememberLastNs(nsId || null);
            // Undo = drop the wrapper; a take-over also gives the original
            // its name back once the wrapper (its only new referrer) is gone.
            if (typeof gdUndoRecord === 'function') {
              const originalName = fn.name;
              gdUndoRecord({
                label: 'Wrapped ' + originalName + ' in ' + newName,
                undo: async () => {
                  const id = await gdUndoFindFnId(newName, nsId || null);
                  if (!id) return { ok: false, error: 'the wrapper is already gone' };
                  const d = await authMutate('DELETE', API.api_entities_type_id('fn', id));
                  if (!(d && d.status >= 200 && d.status < 300)) {
                    let error = 'the server refused';
                    try { error = await extractResponseError(d); } catch (_) { /* generic */ }
                    return { ok: false, error };
                  }
                  if (takeOver) {
                    await authMutate('PUT', API.api_entities_type_id('fn', fn.id),
                                     { name: originalName }).catch(() => {});
                  }
                  const backTo = (typeof getQualifiedFnName === 'function')
                    ? getQualifiedFnName({ ...fn, name: originalName }) : originalName;
                  if (typeof gdUndoLeaveDeleted === 'function') await gdUndoLeaveDeleted(backTo);
                  else if (typeof initGraph === 'function') await initGraph();
                  return { ok: true };
                },
              });
            }
            return true;
          }
        } catch (_) {}
        return false;
      };
      return (typeof withBusy === 'function')
        ? await withBusy(opKey, 'Wrapping in ' + newName + '…', work)
        : await work();
    },
    onSaved() {
      const opKey = 'wrap-finalise:' + fn.id;
      const finalise = async () => {
        if (typeof initGraph === 'function') await initGraph();
        if (pendingName && typeof selectJustCreatedFn === 'function') {
          await selectJustCreatedFn(pendingName);
        }
      };
      if (typeof withBusy === 'function') {
        withBusy(opKey, 'Loading ' + (pendingName || 'wrapper') + '…', finalise);
      } else {
        finalise();
      }
    }
  });
}

