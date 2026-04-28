// Editor Edit Validation - pre-flight checks for structural mutations
// (re-parent, MI add). Backend constraints are minimal in this area
// (gap G3: no parent-id-cycle check), so the frontend mirrors the
// rules to give the user feedback BEFORE the cascade kicks off.
//
// All helpers operate on `lookups` (see editor-data.js) and return a
// terse {ok: true} | {ok: false, reason: string} shape so callers
// can both gate the save and surface the message.

// Walks the parent-id closure of `candidateParentId`. If `fnId` shows
// up anywhere in that closure, naming `candidateParentId` as a parent
// would create a cycle.
function wouldCycle(fnId, candidateParentId, _lookups) {
  const lk = _lookups || (typeof lookups !== 'undefined' ? lookups : null);
  if (!lk || !lk.fnMap) return { ok: false, reason: 'lookups unavailable' };
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
    const pids = (f && f['parent-ids']) || [];
    for (const p of pids) stack.push(p);
  }
  return { ok: true };
}

// MI arg-name collision check (used by Phase 4 — included here so the
// validation module is one place for all structural pre-checks).
// For each candidate parent, walk its full ancestor closure to gather
// the terminal source-ids it inherits as primary args, plus the names
// at those terminals. If two parents share a terminal source-id, that
// is the SAME slot — fine. If they share an arg-NAME but at different
// terminals, that's a collision (the merged interface would have a
// duplicate name and the executor wouldn't know which value to forward).
function miCollisionCheck(parentIds, _lookups) {
  const lk = _lookups || (typeof lookups !== 'undefined' ? lookups : null);
  if (!lk || !lk.fnMap || !lk.argMap || !lk.argsByFn) {
    return { ok: false, reason: 'lookups unavailable' };
  }
  if (!parentIds || parentIds.length < 2) return { ok: true };

  // For each parent, collect a Map<terminalArgId, name>.
  function terminalArgs(fnId) {
    const out = new Map();
    const visited = new Set();
    const walk = (id) => {
      if (!id || visited.has(id)) return;
      visited.add(id);
      const f = lk.fnMap.get(id);
      if (!f) return;
      // Walk this fn's args, follow source-id to terminal.
      const args = lk.argsByFn.get(id) || [];
      for (const a of args) {
        let cur = a;
        while (cur && cur['source-id']) cur = lk.argMap.get(cur['source-id']);
        if (cur && !cur['source-id']) {
          out.set(cur.id, cur.name || '(unnamed)');
        }
      }
      for (const p of (f['parent-ids'] || [])) walk(p);
    };
    walk(fnId);
    return out;
  }

  const perParent = parentIds.map(pid => terminalArgs(pid));
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
