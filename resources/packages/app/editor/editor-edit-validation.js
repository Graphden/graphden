// Editor Edit Validation - pre-flight checks for structural mutations
// (re-parent, MI add). The backend enforces these at write time
// (src/graphden/crud/validation.clj — parent-id cycles via
// cycle-check-rej, MI arg-name collisions via mi-collision-rej);
// these helpers mirror the same rules client-side so the user gets
// feedback BEFORE the re-parent cascade fires a doomed request.
//
// All helpers operate on `lookups` (see editor-data.js) and return a
// terse {ok: true} | {ok: false, reason: string} shape so callers
// can both gate the save and surface the message.

// Walks the parent-id closure of `candidateParentId`. If `fnId` shows
// up anywhere in that closure, naming `candidateParentId` as a parent
// would create a cycle.
function wouldCycle(fnId, candidateParentId, _lookups) {
  const lk = _lookups || (typeof lookups !== 'undefined' ? lookups : null);
  if (!lk?.fnMap) return { ok: false, reason: 'lookups unavailable' };
  if (fnId === candidateParentId) {
    return { ok: false, reason: 'a fn cannot be its own parent' };
  }
  const visited = new Set();
  const stack = [candidateParentId];
  while (stack.length) {
    const cur = stack.pop();
    if (cur === fnId) {
      return { ok: false,
               reason: 'cycle: candidate parent already inherits from this fn' };
    }
    if (visited.has(cur)) continue;
    visited.add(cur);
    const f = lk.fnMap.get(cur);
    const pids = (f?.['parent-ids']) || [];
    for (const p of pids) stack.push(p);
  }
  return { ok: true };
}

// MI arg-name collision check (used by Phase 4 — included here so the
// validation module is one place for all structural pre-checks).
// For each candidate parent, walk its full ancestor closure to gather
// the slot-ids it exposes (own + inherited) and their effective names.
// If two parents share a slot-id, that's the SAME slot — fine. If they
// share an arg-NAME but at different slot-ids, that's a collision (the
// merged interface would have a duplicate name and the executor
// wouldn't know which value to forward).
function miCollisionCheck(parentIds, _lookups) {
  const lk = _lookups || (typeof lookups !== 'undefined' ? lookups : null);
  if (!lk?.fnMap || !lk.fnSlotsByFn || !lk.slotMap) {
    return { ok: false, reason: 'lookups unavailable' };
  }
  if (!parentIds || parentIds.length < 2) return { ok: true };

  // For each parent fn, collect a Map<slotId, effectiveName> reflecting
  // every slot the fn exposes through its own fn-slots PLUS the closure
  // of inherited slots up the parent chain. The slot-id is the terminal
  // identity; sharing a slot-id across parents is fine, sharing a name
  // at distinct slot-ids is the collision.
  function visibleSlots(fnId) {
    const out = new Map();
    const visited = new Set();
    const walk = (id) => {
      if (!id || visited.has(id)) return;
      visited.add(id);
      const f = lk.fnMap.get(id);
      if (!f) return;
      const fss = lk.fnSlotsByFn.get(id) || [];
      for (const fs of fss) {
        const slotId = fs['slot-id'];
        if (!slotId || out.has(slotId)) continue;
        const slot = lk.slotMap.get(slotId);
        const effName = (typeof getEffectiveSlotName === 'function')
                        ? getEffectiveSlotName(fnId, slotId) : null;
        out.set(slotId, effName || (slot?.name) || '(unnamed)');
      }
      for (const p of (f['parent-ids'] || [])) walk(p);
    };
    walk(fnId);
    return out;
  }

  const perParent = parentIds.map(pid => visibleSlots(pid));
  // Group terminals by name across all parents.
  const byName = new Map();  // name -> Set<terminalId>
  perParent.forEach(m => {
    m.forEach((name, tid) => {
      if (!byName.has(name)) byName.set(name, new Set());
      byName.get(name).add(tid);
    });
  });
  for (const [name, tids] of byName) {
    if (tids.size > 1) {
      return { ok: false,
               reason: 'arg name collision: "' + name +
                       '" is defined by ' + tids.size +
                       ' distinct ancestor args across the parent set' };
    }
  }
  return { ok: true };
}

// Convenience: validate a candidate parent set against a target fn.
// Combines wouldCycle (per parent) + miCollisionCheck (across set).
function validateParentSet(fnId, parentIds, _lookups) {
  for (const pid of parentIds) {
    const c = wouldCycle(fnId, pid, _lookups);
    if (!c.ok) return c;
  }
  return miCollisionCheck(parentIds, _lookups);
}
