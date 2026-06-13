// Editor Edit Re-parent — inline parent-set editing on the depth-1
// row of the nav-root card. Each parent cell carries a `×` remove
// button; the right edge of the row carries a `+` add-MI-parent
// button whose picker is filtered to compatible candidates.
//
// "Compatible" MI candidate = a fn that:
//   - shares a base-fn with at least one current parent (so the
//     merged interface inherits a coherent slot vocabulary),
//   - sets at least one own substantive binding (value/ref/list)
//     whose slot is NOT already substantively bound by a current
//     parent (otherwise MI just stacks redundant bindings),
//   - doesn't cycle and doesn't trip the existing arg-name
//     collision check (`miCollisionCheck`).
//
// 0-parent case: the "set parent…" strip on the root card opens the
// fn-picker directly (no chooser popover), and the chosen fn becomes
// the sole parent.
//
// Each operation runs through `performReparentCascade` so orphan
// bindings (those whose slot vanishes from the new parent closure)
// are deleted in the same network round-trip as the `parent-ids` PUT.
//
// Globals consumed: lookups, openFnPicker, openConfirmPopover,
// authMutate, initGraph, validateParentSet, miCollisionCheck,
// wouldCycle, getQualifiedFnName, withBusy, isOpInflight,
// isAuthenticated, isFnEditable.

// =============================================================================
// COMPATIBILITY FILTER
// =============================================================================

// Walk the parent-id closure of `fnId` and return the set of fn-ids
// that have NO parents themselves (= base-fns). For a base-fn, this
// is `{fnId}`; for a composed fn it's the deepest layer of the chain.
function _baseFnsOf(fnId) {
  const out = new Set();
  if (!lookups?.fnMap) return out;
  const visited = new Set();
  const stack = [fnId];
  while (stack.length) {
    const cur = stack.pop();
    if (!cur || visited.has(cur)) continue;
    visited.add(cur);
    const f = lookups.fnMap.get(cur);
    if (!f) continue;
    const pids = f['parent-ids'] || [];
    if (pids.length === 0) {
      out.add(cur);
    } else {
      for (const p of pids) stack.push(p);
    }
  }
  return out;
}

// Slot-ids that this fn has an OWN substantive binding for — i.e. a
// binding row that actually configures the slot (value, ref-fn-id, or
// list-append flag). Pure `:rename-to`-only bindings don't count: they
// just relabel an inherited slot, so a parent that ONLY renames isn't
// "substantively setting" args (per the user's "не те, которые с
// разными именами, а реально разные" rule).
function _substantiveBoundSlotIds(fnId) {
  const out = new Set();
  if (!lookups?.bindingsByFn) return out;
  const bindings = lookups.bindingsByFn.get(fnId) || [];
  for (const b of bindings) {
    if (!b['slot-id']) continue;
    const isSubstantive = b.value != null
                       || b['ref-fn-id']
                       || b['list-append']
                       || b.terminal;
    if (isSubstantive) out.add(b['slot-id']);
  }
  return out;
}

// Reason text — explanation surfaced in the disabled Add-MI button's
// `title` so the user understands why the affordance is greyed out.
function _miCandidateRejectionReason(targetFnId, candidateId,
                                     currentParentIds, existingBaseFns,
                                     existingSubstantiveSlots) {
  if (candidateId === targetFnId) return 'cycle: same fn';
  if (currentParentIds.includes(candidateId)) return 'already a parent';
  const cyc = (typeof wouldCycle === 'function')
              ? wouldCycle(targetFnId, candidateId) : { ok: true };
  if (!cyc.ok) return cyc.reason;
  const candidateBase = _baseFnsOf(candidateId);
  if (existingBaseFns.size > 0) {
    let shared = false;
    candidateBase.forEach(cb => { if (existingBaseFns.has(cb)) shared = true; });
    if (!shared) return 'different base-fn — MI requires a shared root';
  }
  const candidateSlots = _substantiveBoundSlotIds(candidateId);
  if (candidateSlots.size === 0) {
    return 'sets no substantive args of its own — MI would add nothing';
  }
  let allOverlap = true;
  candidateSlots.forEach(sid => {
    if (!existingSubstantiveSlots.has(sid)) allOverlap = false;
  });
  if (allOverlap) {
    return 'every arg this fn sets is already configured by an existing parent';
  }
  if (typeof miCollisionCheck === 'function') {
    const v = miCollisionCheck([...currentParentIds, candidateId]);
    if (!v.ok) return v.reason;
  }
  return null;
}

// Returns `{ candidateIds: Set<string>, rejected: { fnId: reason } }`
// for the picker's two states (allow-list + disabled-button reason).
function compatibleMIParentInfo(targetFnId, currentParentIds) {
  const candidates = new Set();
  const rejected = {};
  if (!lookups?.fnMap) return { candidateIds: candidates, rejected };
  const existingBaseFns = new Set();
  for (const pid of currentParentIds) {
    _baseFnsOf(pid).forEach(b => existingBaseFns.add(b));
  }
  const existingSubstantiveSlots = new Set();
  for (const pid of currentParentIds) {
    _substantiveBoundSlotIds(pid).forEach(s => existingSubstantiveSlots.add(s));
  }
  lookups.fnMap.forEach((_, fid) => {
    const r = _miCandidateRejectionReason(targetFnId, fid, currentParentIds,
                                          existingBaseFns,
                                          existingSubstantiveSlots);
    if (r === null) candidates.add(fid);
    else rejected[fid] = r;
  });
  return { candidateIds: candidates, rejected };
}

// =============================================================================
// CASCADE — orphan bindings + parent-ids PUT
// =============================================================================

async function performReparentCascade(fnId, newParentIds) {
  if (!lookups?.bindingsByFn) return false;
  // 1. Walk current bindings on this fn. A binding is orphaned when
  //    its slot lives on a fn no longer reachable through the new
  //    parent set — keeping such a binding would leave a dangling
  //    override on a slot the fn no longer knows about. The
  //    reachable-slot set is derived from each new parent's full
  //    inheritance closure.
  const reachableSlots = new Set();
  const visited = new Set();
  const collectSlotsFrom = (fid) => {
    if (visited.has(fid)) return;
    visited.add(fid);
    const fn = lookups.fnMap.get(fid);
    if (!fn) return;
    const fnSlots = (lookups.fnSlotsByFn?.get(fid)) || [];
    for (const fs of fnSlots) reachableSlots.add(fs['slot-id']);
    for (const p of (fn['parent-ids'] || [])) collectSlotsFrom(p);
  };
  for (const p of newParentIds) collectSlotsFrom(p);

  const currentBindings = lookups.bindingsByFn.get(fnId) || [];
  const orphans = currentBindings.filter(b => !reachableSlots.has(b['slot-id']));

  // 2. DELETE orphan bindings.
  for (const b of orphans) {
    try {
      const r = await authMutate('DELETE',
                                 '/api/entities/binding/' + encodeURIComponent(b.id));
      if (!r?.ok) return false;
    } catch (_) { return false; }
  }

  // 3. PUT new parent-ids on the fn itself. Empty list is encoded as
  //    the literal string `parent-ids=` so the backend resets the FK
  //    column to NULL, surfacing the fn back to the "set parent…"
  //    state. `authMutate`'s field-map form strips empty-string values,
  //    which would silently drop a clear-parents request — pass the
  //    pre-encoded body when the new list is empty.
  try {
    const body = newParentIds.length === 0
      ? 'parent-ids='
      : { 'parent-ids': newParentIds.join(',') };
    const r = await authMutate('PUT',
                               '/api/entities/fn/' + encodeURIComponent(fnId),
                               body);
    if (!r?.ok) return false;
  } catch (_) { return false; }

  return true;
}

async function _runCascadeWithBusy(fn, newParentIds, opLabel) {
  const opKey = 'reparent:' + fn.id;
  if (typeof isOpInflight === 'function' && isOpInflight(opKey)) return false;
  const display = (typeof getQualifiedFnName === 'function')
                  ? getQualifiedFnName(fn) : (fn.name || 'fn');
  const work = async () => {
    const ok = await performReparentCascade(fn.id, newParentIds);
    if (ok && typeof initGraph === 'function') await initGraph();
    return ok;
  };
  const ok = (typeof withBusy === 'function')
    ? await withBusy(opKey, opLabel + ' ' + display + '…', work)
    : await work();
  if (!ok) {
    alert('Re-parent failed — check the network log; some changes may '
          + 'be partial. Re-saving will retry idempotently.');
  }
  return ok;
}

// =============================================================================
// INLINE OPERATIONS
// =============================================================================

// Delete a single parent from the fn's parent set. Confirms before
// running because the cascade also drops orphan bindings, which can
// be a surprising side-effect for the user.
async function removeParentInline(fn, parentIdToRemove) {
  if (!fn || !parentIdToRemove) return;
  if (typeof isAuthenticated === 'function' && !isAuthenticated()) return;
  const current = fn['parent-ids'] || [];
  const next = current.filter(p => p !== parentIdToRemove);
  if (next.length === current.length) return;
  const removed = lookups?.fnMap?.get(parentIdToRemove);
  const removedName = removed
    ? ((typeof getQualifiedFnName === 'function')
       ? getQualifiedFnName(removed) : (removed.name || '(anonymous)'))
    : '(unknown)';
  const tail = next.length === 0
    ? '\n\nThis was the last parent — the fn will become a base-fn '
      + '(no inheritance).'
    : '';
  if (!confirm('Remove parent "' + removedName + '"?'
               + ' Bindings on slots no longer reachable will be deleted.'
               + tail)) return;
  await _runCascadeWithBusy(fn, next, 'Removing parent from');
}

// Add another MI parent — opens the fn-picker filtered to compatible
// candidates. Skips the picker entirely (and shows an alert) when no
// candidate exists, but `appendAddMIButton` already disables the
// button in that case so this is just a safety net.
function addMIParentInline(fn, anchorEl) {
  if (!fn) return;
  if (typeof isAuthenticated === 'function' && !isAuthenticated()) return;
  if (typeof openFnPicker !== 'function') return;
  const current = fn['parent-ids'] || [];
  const { candidateIds } = compatibleMIParentInfo(fn.id, current);
  if (candidateIds.size === 0) return;
  // The picker takes an excludeIds list; build it from "everything
  // NOT in candidateIds" so the picker only shows the allow-list.
  const exclude = [];
  if (lookups?.fnMap) {
    lookups.fnMap.forEach((_, fid) => {
      if (!candidateIds.has(fid)) exclude.push(fid);
    });
  }
  openFnPicker({
    anchorEl,
    excludeIds: exclude,
    fnNamespaceId: fn['namespace-id'],
    onPick: async (picked) => {
      if (!picked?.id) return;
      const next = [...current, picked.id];
      const v = (typeof validateParentSet === 'function')
                ? validateParentSet(fn.id, next) : { ok: true };
      if (!v.ok) {
        alert('Cannot add this parent: ' + v.reason);
        return;
      }
      await _runCascadeWithBusy(fn, next, 'Adding MI parent to');
    }
  });
}

// 0-parent path: "set parent…" strip click → direct fn-picker (no
// MI filter, just cycle / collision exclusion against the empty set).
function setInitialParentInline(fn, anchorEl) {
  if (!fn) return;
  if (typeof isAuthenticated === 'function' && !isAuthenticated()) return;
  if (typeof openFnPicker !== 'function') return;
  // Exclude self + descendants (cycle).
  const exclude = new Set([fn.id]);
  if (lookups?.fnMap) {
    lookups.fnMap.forEach((_, id) => {
      const cyc = (typeof wouldCycle === 'function')
                  ? wouldCycle(fn.id, id) : { ok: true };
      if (!cyc.ok) exclude.add(id);
    });
  }
  openFnPicker({
    anchorEl,
    excludeIds: Array.from(exclude),
    fnNamespaceId: fn['namespace-id'],
    onPick: async (picked) => {
      if (!picked?.id) return;
      await _runCascadeWithBusy(fn, [picked.id], 'Setting parent of');
    }
  });
}

// Backwards compatibility — the depth-1 row's existing `Change
// parent` ✎ pencil and the (now removed) bottom popover used to
// call `enterReparentEditMode`. Keep the symbol exported as a
// convenience so any lingering callsite jumps into the right path.
function enterReparentEditMode(fn, anchorEl) {
  const pids = fn?.['parent-ids'] || [];
  if (pids.length === 0) setInitialParentInline(fn, anchorEl);
  else addMIParentInline(fn, anchorEl);
}
