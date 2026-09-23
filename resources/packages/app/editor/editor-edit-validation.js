// Editor Edit Validation - the pre-flight cycle check for structural
// mutations (re-parent, MI add). The backend enforces the FULL rules at
// write time (src/graphden/crud/validation.clj — cycle-check-rej over
// parent-ids AND binding refs AND fk-refs AND constraint-refs; MI
// arg-name collisions via mi-collision-rej, which has no client copy:
// the old one miscounted renamed views, see editor-edit-reparent.js).
// This is a BEST-EFFORT pre-check over the data the lazy client actually holds
// (accumulated fns + the current subtree's bindings — there is
// deliberately no full-graph mirror), so the user gets feedback
// BEFORE the re-parent cascade fires a doomed request in the common
// case; an edge the client can't see still 409s server-side and the
// error surfaces through the normal save path.
//
// Operates on `lookups` (see editor-data.js) and returns a terse
// {ok: true} | {ok: false, reason: string} shape so callers can both
// gate the save and surface the message.

// Walks every dependency edge visible client-side out of
// `candidateParentId`: parent-ids, binding refs (subtree-scope),
// type FKs (base/element/return-type), and binding type-overrides.
// If `fnId` shows up anywhere in that closure, naming
// `candidateParentId` as a parent would create a cycle.
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
               reason: 'cycle: candidate parent already depends on this fn' };
    }
    if (visited.has(cur)) continue;
    visited.add(cur);
    const f = lk.fnMap.get(cur);
    for (const p of (f?.['parent-ids']) || []) stack.push(p);
    // Type FKs — refinement base, list element, declared return.
    for (const k of ['base-fn-id', 'element-fn-id', 'return-type-fn-id']) {
      if (f?.[k]) stack.push(f[k]);
    }
    // Binding edges — refs + type-overrides. Subtree-scope only (the
    // client holds no full binding mirror); the server covers the rest.
    for (const b of lk.bindingsByFn?.get(cur) || []) {
      if (b['ref-fn-id']) stack.push(b['ref-fn-id']);
      if (b['type-override-fn-id']) stack.push(b['type-override-fn-id']);
    }
  }
  return { ok: true };
}
