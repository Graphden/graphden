// editor-tour-cleanup.js — undoing what a lesson made.
//
// A step declares `:creates {:type … :name …}`; the engine records those and
// this file both REPORTS what still exists (the end-of-tour offer lists it)
// and removes it on request. Four kinds exist today — `fn`, `ns`, `branch`
// and `package-version` — and both halves must know all four: a type known to
// the deleter but not to the reporter is a row the reader is never told about
// (and, when it is a lesson's ONLY creation, never offered to delete).
//
// Deletion reports what it could not do. Every call here is best-effort — a
// fn another row still references refuses, a registry can be unreachable —
// and an unconditional "deleted" toast over swallowed failures is a lie the
// reader can only discover by hand later.

// --- what still exists ------------------------------------------------------

async function _tourPublishedVersions(name) {
  try {
    const r = await authFetch(API.api_packages);
    const rows = await r.json();
    return (Array.isArray(rows) ? rows : (rows.packages || []))
      .filter((row) => row?.name === name);
  } catch (_) { return []; }
}

// `created` → the subset that is still there, in the same order. Async
// because a published version lives in the registry, not in `graphData`.
async function _tourSurvivors(created) {
  const out = [];
  for (const c of (created || [])) {
    switch (c.type) {
      case 'branch':
        // Not a graph row — a routing context. Offer it unconditionally;
        // the delete is idempotent.
        out.push(c);
        break;
      case 'fn':
        if (_tourFindFn(c.name)) out.push(c);
        break;
      case 'ns':
        if (typeof graphData !== 'undefined' && graphData
            && (graphData.namespaces || []).some((n) => n.name === c.name)) {
          out.push(c);
        }
        break;
      case 'package-version':
        if ((await _tourPublishedVersions(c.name)).length) out.push(c);
        break;
      default:
        // An unknown type must not vanish silently: offer it, let the delete
        // pass report what happened.
        out.push(c);
    }
  }
  return out;
}

// --- deletion ---------------------------------------------------------------

async function _tourDeleteCreatedBranches() {
  const created = (_tourState?.created) || [];
  const failed = [];
  for (const c of created) {
    if (c.type !== 'branch') continue;
    try {
      await authFetch(API.api_branches_ref(c.name), { method: 'DELETE' });
    } catch (_) { failed.push(c); }
  }
  return failed;
}

// Resolve through the SEARCH endpoint, not the lexical graph: the client only
// holds the SELECTED fn's subtree, so a fn the lesson created earlier can be
// absent from it by cleanup time — and an absent row reads as "already gone",
// which is how the first fn of every chain used to survive.
async function _tourFnIdByName(name) {
  try {
    const r = await authFetch(API.api_graph_entities
      + '?scope=search&q=' + encodeURIComponent(name));
    const payload = await r.json();
    return (payload.fns || []).find((f) => f.name === name)?.id || null;
  } catch (_) { return null; }
}

// NEWEST FIRST. A lesson that builds a chain creates the target before the fn
// that points at it (lesson 15: the cell, then the swap that writes to it),
// and the server refuses to delete a fn something still references — correctly.
// Creation order therefore left the FIRST fn of every chain behind. Whatever
// still refuses goes round once more, after the rest of the pass unblocked it.
async function _tourDeleteFns(created) {
  const fns = created.filter((c) => c.type === 'fn').reverse();
  const retry = [];
  for (const c of fns) {
    const id = await _tourFnIdByName(c.name);
    if (!id) continue;
    try { await authMutate('DELETE', API.api_entities_type_id('fn', id)); }
    catch (_) { retry.push(c); }
  }
  const failed = [];
  for (const c of retry) {
    const id = await _tourFnIdByName(c.name);
    if (!id) continue;
    try { await authMutate('DELETE', API.api_entities_type_id('fn', id)); }
    catch (_) { failed.push(c); }
  }
  return failed;
}

// A published version outlives the namespace it was cut from — and once that
// namespace is gone, installing the version answers 404. A lesson that
// publishes therefore withdraws its own release, or it leaves a broken row in
// the registry every time someone takes the tour.
async function _tourWithdrawVersions(created) {
  const failed = [];
  for (const c of created) {
    if (c.type !== 'package-version') continue;
    for (const row of await _tourPublishedVersions(c.name)) {
      try {
        const r = await authFetch(API.api_packages_withdraw
                                  + '?name=' + encodeURIComponent(row.name)
                                  + '&version=' + encodeURIComponent(row.version),
                                  { method: 'DELETE' });
        if (!r.ok) failed.push(c);
      } catch (_) { failed.push(c); }
    }
  }
  return failed;
}

async function _tourDeleteNamespaces(created) {
  const failed = [];
  for (const c of created) {
    if (c.type !== 'ns') continue;
    // The lesson created this namespace through the editor, so the client
    // already holds it — a full `initGraph()` just to learn one id costs
    // seconds on a large graph. Refresh only if it somehow isn't there.
    let ns = (typeof graphData !== 'undefined' && graphData
      && (graphData.namespaces || []).find((n) => n.name === c.name)) || null;
    if (!ns && typeof initGraph === 'function') {
      try { await initGraph(); } catch (_) { /* report via the delete below */ }
      ns = (typeof graphData !== 'undefined' && graphData
        && (graphData.namespaces || []).find((n) => n.name === c.name)) || null;
    }
    if (!ns) continue;
    // A namespace the lesson caused to exist can hold rows the lesson did not
    // create by hand — installing a package materialises its fns under
    // `<ns>@<version>`. Clear the contents first, or the delete 409s on a
    // non-empty namespace and the copy is left behind. `scope=namespace` is
    // the listing for a NAMESPACE; `subtree` takes a FN id and answers empty.
    try {
      const sub = await authFetch(API.api_graph_entities
                                  + '?scope=namespace&namespace-id=' + ns.id);
      const payload = await sub.json();
      for (const f of (payload.fns || [])) {
        if (f['namespace-id'] !== ns.id) continue;
        try { await authMutate('DELETE', API.api_entities_type_id('fn', f.id)); }
        catch (_) { /* another row may still reference it — the ns delete reports */ }
      }
    } catch (_) { /* best-effort — the delete below reports the truth */ }
    try { await authMutate('DELETE', API.api_entities_type_id('ns', ns.id)); }
    catch (_) { failed.push(c); }
  }
  return failed;
}

// Delete everything the lesson created, in dependency order, and return what
// refused: `{failed: [{type, name}, …]}`. The caller decides what to say.
async function _tourDeleteCreated() {
  const created = (_tourState?.created) || [];
  const failed = [
    ...await _tourDeleteCreatedBranches(),
    ...await _tourDeleteFns(created),      // fns first — a namespace deletes once empty
    ...await _tourWithdrawVersions(created),
    ...await _tourDeleteNamespaces(created),
  ];
  if (typeof initGraph === 'function') { try { await initGraph(); } catch (_) {} }
  return { failed };
}
