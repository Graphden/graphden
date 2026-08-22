// editor-tour-cleanup.js — undoing what a lesson made.
//
// graph-first-exception: no markup here at all. This is API orchestration
// over the same endpoints the editor's own buttons call, in an order the
// SERVER cannot choose for us (it depends on what this lesson created, in
// which order, on this branch).
//
// A step declares `:creates {:type … :name …}`; the engine records those and
// this file both REPORTS what still exists (the end-of-tour offer lists it)
// and removes it on request. Four kinds exist today — `fn`, `ns`, `branch`
// and `package-version` — and both halves must know all four: a type known to
// the deleter but not to the reporter is a row the reader is never told about
// (and, when it is a lesson's ONLY creation, never offered to delete).
//
// The e2e guards carry their own sweep (`hardCleanup` in
// tutorial-tour-helpers.js) — deliberately, as a belt for a run that crashes
// mid-lesson and never reaches this pass at all. It is allowed to be blunter
// (it deletes known tutorial names outright); what it must NOT be is smarter,
// so anything learned there about ORDER belongs here too.
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
      // Both resolve through the SERVER, like the delete pass: the client
      // holds only the selected subtree (and, right after a reload, nothing
      // at all), so a lexical miss means "not loaded here", not "gone" — and
      // a row missing from this report is never offered for deletion.
      case 'fn':
        if (_tourFindFn(c.name) || await _tourFnIdByName(c.name)) out.push(c);
        break;
      case 'ns':
        if (await _tourNsByName(c.name)) out.push(c);
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

// `authFetch` / `authMutate` RESOLVE on 4xx — they hand back the Response.
// A bare `try { await authMutate(…) } catch` therefore only ever sees a
// network error, and the refusals that actually happen here (409: the fn is
// still someone's parent; 409: the namespace is not empty) counted as
// success. Every delete goes through this, so a refusal is reported.
async function _tourDeleted(call) {
  try {
    const r = await call();
    // A Response-less resolve (a stub, a future change) counts as done.
    return r?.ok !== false;
  } catch (_) { return false; }
}

async function _tourDeleteCreatedBranches(created) {
  const failed = [];
  for (const c of (created || [])) {
    if (c.type !== 'branch') continue;
    const ok = await _tourDeleted(
      () => authFetch(API.api_branches_ref(c.name), { method: 'DELETE' }));
    if (!ok) failed.push(c);
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
    const ok = await _tourDeleted(
      () => authMutate('DELETE', API.api_entities_type_id('fn', id)));
    if (!ok) retry.push(c);
  }
  const failed = [];
  for (const c of retry) {
    const id = await _tourFnIdByName(c.name);
    if (!id) continue;
    const ok = await _tourDeleted(
      () => authMutate('DELETE', API.api_entities_type_id('fn', id)));
    if (!ok) failed.push(c);
  }
  return failed;
}

// A published version outlives the namespace it was cut from — and once that
// namespace is gone, installing the version answers 404. A lesson that
// publishes therefore withdraws its own release, or it leaves a broken row in
// the registry every time someone takes the tour.
//
// The PIN goes first. Installing materialises the package under
// `<ns>@<version>`, and the namespace pass below deletes that copy — a pin
// left pointing at gutted entities is the exact state that makes the next
// install answer 404 for a package the registry still lists as fine. Ending a
// lesson half-way (published and installed, but not yet uninstalled by hand)
// is the ordinary way to reach it.
async function _tourRemovePackages(created) {
  const failed = [];
  for (const c of created) {
    if (c.type !== 'package-version') continue;
    // Idempotent: answers `{removed: false}` when nothing was pinned.
    const unpinned = await _tourDeleted(
      () => authFetch(API.api_packages_uninstall
                      + '?name=' + encodeURIComponent(c.name), { method: 'DELETE' }));
    if (!unpinned) failed.push(c);
    for (const row of await _tourPublishedVersions(c.name)) {
      const gone = await _tourDeleted(
        () => authFetch(API.api_packages_withdraw
                        + '?name=' + encodeURIComponent(row.name)
                        + '&version=' + encodeURIComponent(row.version),
                        { method: 'DELETE' }));
      if (!gone) failed.push(c);
    }
  }
  return failed;
}

// Resolve a namespace the same way `_tourFnIdByName` resolves a fn: through
// the SERVER. The client's `graphData` is a view — it can be empty right after
// a reload, and a lesson that ran before it populated would read as "already
// gone" and leave the namespace behind for good.
async function _tourNsByName(name) {
  try {
    const r = await authFetch(API.api_graph_entities + '?scope=tree');
    const payload = await r.json();
    return (payload.namespaces || []).find((n) => n.name === name) || null;
  } catch (_) {
    return (typeof graphData !== 'undefined' && graphData
      && (graphData.namespaces || []).find((n) => n.name === name)) || null;
  }
}

async function _tourDeleteNamespaces(created) {
  const failed = [];
  for (const c of created) {
    if (c.type !== 'ns') continue;
    const ns = await _tourNsByName(c.name);
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
        // Best-effort: another row may still reference it, and the namespace
        // delete below is what reports the outcome either way.
        await _tourDeleted(() => authMutate('DELETE', API.api_entities_type_id('fn', f.id)));
      }
    } catch (_) { /* best-effort — the delete below reports the truth */ }
    const ok = await _tourDeleted(
      () => authMutate('DELETE', API.api_entities_type_id('ns', ns.id)));
    if (!ok) failed.push(c);
  }
  return failed;
}

// Delete everything the lesson created, in dependency order, and return what
// refused: `{failed: [{type, name}, …]}`. The caller decides what to say.
// `created` is passed IN, not read from `_tourState`: the dialog that calls
// this runs long after the tour stopped, and reading a state something else
// may have cleared turned "delete what the lesson made" into "delete nothing,
// report success".
async function _tourDeleteCreated(created) {
  const raw = [
    ...await _tourDeleteCreatedBranches(created),
    ...await _tourDeleteFns(created),      // fns first — a namespace deletes once empty
    ...await _tourRemovePackages(created),   // unpin, then withdraw
    ...await _tourDeleteNamespaces(created),
  ];
  // One row can refuse twice (an unpin AND a withdraw for the same package);
  // the reader should read its name once.
  const seen = new Set();
  const failed = raw.filter((c) => {
    const k = c.type + '\u0000' + c.name;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  if (typeof initGraph === 'function') { try { await initGraph(); } catch (_) {} }
  return { failed };
}
