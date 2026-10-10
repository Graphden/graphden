// editor-tour-cleanup.js — undoing what a lesson made.
//
// graph-first-exception: no markup here at all. This is API orchestration
// over the same endpoints the editor's own buttons call, in an order the
// SERVER cannot choose for us (it depends on what this lesson created, in
// which order, on this branch).
//
// A step declares `:creates {:type … :name …}`; the engine records those and
// this file both REPORTS what still exists (the end-of-tour offer lists it)
// and removes it on request. Graph rows, branches, publications, apps and
// tokens all need both reporting and removal: a type known to
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

async function _tourPublishedVersions(name, options) {
  const r = await authFetch(API.api_packages, options);
    if (!r.ok) throw new Error("Registry unavailable");
    const rows = await r.json();
    if (!Array.isArray(rows) && !Array.isArray(rows?.packages)) throw new Error("Invalid registry response");
    return (Array.isArray(rows) ? rows : rows.packages)
      .filter((row) => row?.name === name);
}

// `created` → the subset that is still there, in the same order. Async
// because a published version lives in the registry, not in `graphData`.
async function _tourSurvivors(created) {
  const out = [];
  for (const c of (created || [])) {
    if (c.receipt === 'removed') continue;
    if (c.receipt === 'pending') { out.push(c); continue; }
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
        try {
          if (c.id ? await _tourFnIdForCreation(c)
            : _tourFindFn(c.name) || await _tourFnIdByName(c.name)) out.push(c);
        } catch (_) { out.push(c); }
        break;
      case 'ns':
        try { if (c.id ? await _tourNsForCreation(c) : await _tourNsByName(c.name)) out.push(c); }
        catch (_) { out.push(c); }
        break;
      case 'package-version':
        try {
          if (!c.id || (await _tourPublishedVersions(c.name)).some(row => row.id === c.id)) out.push(c);
        } catch (_) { out.push(c); }
        break;
      case 'package-install':
        // Keep the receipt until cleanup confirms removal on its saved branch.
        out.push(c);
        break;
      case 'api-token':
        try { if (!c.id || (await _tourReadTokens()).some(row => row.id === c.id)) out.push(c); }
        catch (_) { out.push(c); }
        break;
      case 'app-route':
        try { if (await _tourAppForReceipt(c)) out.push(c); }
        catch (_) { out.push(c); }
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


async function _tourDeleteHttpPublications(created) {
  const failed = [];
  for (const row of (created || []).filter(item => item.type === 'http-publication')) {
    if (!row.id || !row.principal || !_tourPrincipalMatches({principal: row.principal})) {
      failed.push(row);
      continue;
    }
    try {
      const response = await authFetch(HTTP_HOST_API + '/' + encodeURIComponent(row.id), {method: 'DELETE'});
      const body = await response.json();
      if (!response.ok || body?.ok !== true) failed.push(row);
    } catch (_) { failed.push(row); }
  }
  return failed;
}

// NEWEST FIRST, like the fn pass: a lesson that forks a branch OFF another
// lesson branch (lesson 24: tutorial-feature off tutorial-release) creates the
// parent first, and the server refuses to delete a branch that still has
// children — correctly. One reversed pass clears the normal case; whatever
// still refuses goes round once more, after the rest unblocked it.
// NOTE the server answers these refusals as HTTP 200 with `{ok:false}`, so
// the body must be read — `Response.ok` alone counted them as deleted.
async function _tourDeleteBranch(name) {
  try {
    const r = await authFetch(API.api_branches_ref(name), { method: 'DELETE' });
    if (!r?.ok) return false;
    const body = await r.json();
    // A prior attempt may already have removed this child branch.
    return body?.ok === true || body?.reason === 'not-found';
  } catch (_) { return false; }
}

async function _tourDeleteCreatedBranches(created, blocked = new Set()) {
  const branches = (created || []).filter((c) => c.type === 'branch').reverse();
  const retry = [];
  for (const c of branches) {
    if (blocked.has(c.id) || !await _tourDeleteOwnedBranch(c)) retry.push(c);
  }
  const failed = [];
  for (const c of retry) {
    if (blocked.has(c.id) || !await _tourDeleteOwnedBranch(c)) failed.push(c);
  }
  return failed;
}

async function _tourDeleteOwnedBranch(created) {
  if (created.name === 'main') return false;
  if (created.receipt === 'pending' && (!created.id || !created['base-branch-id'])) return false;
  if (!created.id) return _tourDeleteBranch(created.name); // older lesson ledgers
  try {
    const response = await authFetch(API.api_branches);
    if (!response.ok) return false;
    const payload = await response.json();
    const rows = Array.isArray(payload) ? payload : payload?.branches;
    if (!Array.isArray(rows)) return false;
    const branch = rows.find((row) => row.id === created.id);
    if (!branch) return true;
    if (branch.name !== created.name || branch['base-branch-id'] !== created['base-branch-id']) return false;
    return _tourDeleteBranch(created.id);
  } catch (_) { return false; }
}

// Resolve through the SEARCH endpoint, not the lexical graph: the client only
// holds the SELECTED fn's subtree, so a fn the lesson created earlier can be
// absent from it by cleanup time — and an absent row reads as "already gone",
// which is how the first fn of every chain used to survive.
async function _tourFnIdByName(name, options) {
  const r = await authFetch(API.api_graph_entities
    + '?scope=search&q=' + encodeURIComponent(name), options);
  if (!r.ok) throw new Error('Function lookup failed');
  const payload = await r.json();
  if (!Array.isArray(payload.fns)) throw new Error('Invalid function lookup response');
  return payload.fns.find((f) => f.name === name)?.id || null;
}

async function _tourFnIdForCreation(created, options) {
  if (!created.id) return _tourFnIdByName(created.name, options);
  const response = await authFetch(API.api_entities_type_id('fn', created.id), {...options, cache: 'no-store'});
  if (response.status === 404) {
    const failure = await response.json().catch(() => null);
    if (failure?.error === 'function-not-found') return null;
    throw new Error('Function lookup failed');
  }
  if (!response.ok) throw new Error('Function lookup failed');
  const fn = await response.json();
  if (!fn || fn.id !== created.id) throw new Error('Invalid function identity response');
  if (fn.name !== created.name || fn['namespace-id'] !== created['namespace-id']) {
    throw new Error('Created function identity changed; keep it for manual review.');
  }
  return fn.id;
}

// A server preview can propose a fixed create-only manifest before apply.
// Only that exact staged operation can reconcile a lost reply by UUID; the
// ordinary name-only/pending create paths still cannot authorize cleanup.
function _tourCreateOnlyManifestReceipt(created) {
  return created.receipt === 'pending' && created.creation === 'create-only-manifest'
    && !!created.id && !!created['manifest-root-id'] && !!created['branch-id'] && !!created['branch-name']
    && (created.type === 'fn' ? Object.hasOwn(created, 'namespace-id')
      : created.type === 'ns' && Object.hasOwn(created, 'parent-id'));
}

// Clear only the selected identity whose DELETE actually succeeded. A failed
// lookup/delete or another surviving selection must keep its graph and context.
async function _tourDeleteFn(id, options) {
  try {
    const response = options
      ? await authFetch(API.api_entities_type_id('fn', id), {...options, method: 'DELETE'})
      : await authMutate('DELETE', API.api_entities_type_id('fn', id));
    if (response?.ok === false) return false;
    if (response?.ok === true && typeof selectedFnId !== 'undefined'
        && selectedFnId === id && typeof gdClearSelection === 'function') gdClearSelection();
    return true;
  } catch (_) { return false; }
}

// NEWEST FIRST. A lesson that builds a chain creates the target before the fn
// that points at it (lesson 12: the cell, then the swap that writes to it),
// and the server refuses to delete a fn something still references — correctly.
// Creation order therefore left the FIRST fn of every chain behind. Whatever
// still refuses is retried while other removals unblock it. The lesson can
// create a caller before the function it later references, so reverse creation
// order alone is insufficient. A stalled pass gets one retry, then reports it.
// Dependency data is only an ordering hint. Every deletion still performs
// the exact receipt's fresh identity lookup and the server's normal DELETE.
function _tourOrderFnDeletes(entries, graph) {
  if (!Array.isArray(graph?.fns) || !Array.isArray(graph.bindings)
      || !Array.isArray(graph['list-items'])) return entries;
  const byId = new Map(entries.map(row => [row.id, row]));
  const own = new Set(byId.keys());
  const edges = new Map(entries.map(row => [row.id, new Set()]));
  const add = (from, to) => { if (own.has(from) && own.has(to)) edges.get(from).add(to); };
  for (const fn of graph.fns) {
    for (const field of ['base-fn-id', 'return-type-fn-id', 'element-fn-id']) add(fn.id, fn[field]);
    for (const parent of fn['parent-ids'] || []) add(fn.id, parent);
  }
  for (const binding of graph.bindings) {
    for (const field of ['ref-fn-id', 'resolver-fn-id', 'type-override-fn-id']) add(binding['fn-id'], binding[field]);
  }
  const owners = new Map(graph.bindings.map(row => [row.id, row['fn-id']]));
  for (const item of graph['list-items']) add(owners.get(item['binding-id']), item['ref-fn-id']);
  const indegree = new Map(entries.map(row => [row.id, 0]));
  for (const dependencies of edges.values()) for (const id of dependencies) indegree.set(id, indegree.get(id) + 1);
  const queue = entries.filter(row => indegree.get(row.id) === 0);
  const ordered = [];
  for (let index = 0; index < queue.length; index++) {
    const row = queue[index];
    ordered.push(row);
    for (const id of edges.get(row.id)) {
      indegree.set(id, indegree.get(id) - 1);
      if (indegree.get(id) === 0) queue.push(byId.get(id));
    }
  }
  // Cyclic or missing edges retain ordinary retry/refusal behavior.
  const seen = new Set(ordered.map(row => row.id));
  return ordered.concat(entries.filter(row => !seen.has(row.id)));
}

async function _tourFnDeleteOrder(entries, options) {
  const roots = new Set(entries.map(row => row['cleanup-order-root-id']).filter(Boolean));
  if (roots.size !== 1) return entries;
  const root = [...roots][0];
  if (!entries.some(row => row.id === root && row.creation === 'create-only-manifest')) return entries;
  try {
    const response = await authFetch(API.api_graph_entities + '?scope=subtree&root-id=' + encodeURIComponent(root), options);
    return response.ok ? _tourOrderFnDeletes(entries, await response.json()) : entries;
  } catch (_) { return entries; }
}

async function _tourDeleteFns(created, options) {
  const entries = created.filter(c => c.type === 'fn' && c.receipt !== 'removed').reverse();
  const batch = await _tourDeleteManifestFns(entries, options);
  if (batch !== null) return batch;
  let pending = await _tourFnDeleteOrder(entries, options);
  for (let pass = 0; pending.length; pass++) {
    const failed = [];
    for (const c of pending) {
      if (c.receipt === 'pending' && !_tourCreateOnlyManifestReceipt(c)) { failed.push(c); continue; }
      try {
        const id = await _tourFnIdForCreation(c, options);
        if (!id) continue;
        if (!await _tourDeleteFn(id, options)) failed.push(c);
      } catch (_) { failed.push(c); }
    }
    if (pass > 0 && failed.length === pending.length) return failed;
    pending = failed;
  }
  return [];
}

// The server repeats identity, ACL and dependency checks for the whole set
// under one writer transaction. Only exact create-only receipts use this path;
// an uncertain response retains every receipt rather than trying single DELETEs.
async function _tourDeleteManifestFns(entries, options) {
  const uuid = value => typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
  if (!entries.length || entries.length > 1000 || typeof API.api_functions_delete_batch !== 'string') return null;
  const first = entries[0];
  if (!uuid(first['branch-id']) || !first['manifest-root-id']) return null;
  if (entries.some(row => row.creation !== 'create-only-manifest'
    || !['pending', 'created'].includes(row.receipt) || !uuid(row.id) || !uuid(row['namespace-id'])
    || typeof row.name !== 'string' || !row.name || row['manifest-root-id'] !== first['manifest-root-id']
    || row['branch-id'] !== first['branch-id'] || row['branch-name'] !== first['branch-name'])) return null;
  const ids = new Set(entries.map(row => row.id));
  if (ids.size !== entries.length) return entries;
  const routedBranch = options?.headers?.['X-Graphden-Branch'];
  // After an owned sandbox was removed, ordinary cleanup reconciles missing
  // rows through main. Never redirect that cleanup back to the removed branch.
  if (routedBranch && ![first['branch-id'], first['branch-name']].includes(routedBranch)) return null;
  try {
    const response = await authFetch(API.api_functions_delete_batch, {...options, method: 'POST',
      headers: {...options?.headers, 'Content-Type': 'application/json', 'X-Graphden-Branch': first['branch-id']},
      body: JSON.stringify({functions: entries.map(row => ({id: row.id, name: row.name, 'namespace-id': row['namespace-id']}))})});
    if (!response.ok) return entries;
    const result = await response.json();
    if (!Array.isArray(result?.deleted) || !Array.isArray(result['already-absent'])) return entries;
    const acknowledged = [...result.deleted, ...result['already-absent']];
    if (acknowledged.length !== ids.size || new Set(acknowledged).size !== ids.size
      || acknowledged.some(id => !ids.has(id))) return entries;
    for (const row of entries) row.receipt = 'removed';
    if (typeof _tourSaveState === 'function') _tourSaveState();
    const branch = typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : null;
    if ([first['branch-id'], first['branch-name']].includes(branch)
      && typeof selectedFnId !== 'undefined' && ids.has(selectedFnId)
      && typeof gdClearSelection === 'function') gdClearSelection();
    return [];
  } catch (_) { return entries; }
}

// Pins survive branch deletion in the schema. Remove only the receipt's exact
// pin on the saved owned branch BEFORE deleting that branch. A lost response
// retains a pending ledger item; it cannot authorize a name-only deletion.
async function _tourUnpinPackages(created) {
  const failed = [];
  for (const entry of created.filter(row => row.type === 'package-install')) {
    if (entry.receipt === 'removed') continue;
    if (!entry.id || !entry['branch-id'] || entry.receipt !== 'created') { failed.push(entry); continue; }
    try {
      const branchResponse = await authFetch(API.api_branches);
      if (!branchResponse.ok) throw new Error('Branches unavailable');
      const branchBody = await branchResponse.json();
      const branches = Array.isArray(branchBody) ? branchBody : branchBody?.branches;
      const branch = branches?.find(row => row.id === entry['branch-id']);
      if (!branch || !entry['branch-name'] || entry['branch-name'] === 'main'
          || branch.name !== entry['branch-name']) throw new Error('Owned branch unavailable');
      const headers = {'X-Graphden-Branch': entry['branch-id']};
      const response = await authFetch(API.api_packages_installed, {headers});
      if (!response.ok) throw new Error('Installed packages unavailable');
      const rows = await response.json();
      if (!Array.isArray(rows)) throw new Error('Invalid installed packages response');
      const pin = rows.find(row => row['package-name'] === entry.name);
      if (!pin) { entry.receipt = 'removed'; continue; }
      if (pin.id !== entry.id || pin['branch-id'] !== entry['branch-id'] || pin.version !== entry.version) {
        failed.push(entry); continue;
      }
      if (!await _tourDeleted(() => authFetch(API.api_packages_uninstall
        + '?name=' + encodeURIComponent(entry.name) + '&expected-id=' + encodeURIComponent(entry.id),
        {method: 'DELETE', headers}))) failed.push(entry);
      else entry.receipt = 'removed';
    } catch (_) { failed.push(entry); }
  }
  return failed;
}

async function _tourRemovePackages(created, options) {
  const failed = [];
  for (const entry of created.filter(row => row.type === 'package-version')) {
    if (!entry.id || !entry.version || !entry['content-hash'] || entry.receipt !== 'created') {
      failed.push(entry); continue;
    }
    try {
      const rows = await _tourPublishedVersions(entry.name, options);
      const row = rows.find(candidate => candidate.id === entry.id);
      if (!row) continue;
      if (row.name !== entry.name || row.version !== entry.version
          || row['content-hash'] !== entry['content-hash']) { failed.push(entry); continue; }
      if (!await _tourDeleted(() => authFetch(API.api_packages_withdraw
        + '?name=' + encodeURIComponent(entry.name) + '&version=' + encodeURIComponent(entry.version)
        + '&expected-id=' + encodeURIComponent(entry.id), {...options, method: 'DELETE'}))) failed.push(entry);
    } catch (_) { failed.push(entry); }
  }
  return failed;
}

// Resolve a namespace the same way `_tourFnIdByName` resolves a fn: through
// the SERVER. The client's `graphData` is a view — it can be empty right after
// a reload, and a lesson that ran before it populated would read as "already
// gone" and leave the namespace behind for good.
//
// ROOT namespaces only — `name` is a SEGMENT, not a path, and every lesson
// creates its namespace at the Explorer's root. Without that guard this
// resolver matches the first row carrying the segment ANYWHERE: once the
// platform shipped its own `core.tests` / `web.tests` self-tests, lesson 17's
// recorded `tests` resolved to one of those and the cleanup walked a platform
// namespace, deleting (403 / 409) its way through fns the lesson never made.
// The `ns-exists` CHECK has carried this rule since the cloud's
// `landing.tutorial` pages; the deleter has to agree with it.
async function _tourNsByName(name) {
  const rootNamed = (nss) => (nss || []).find((n) => n.name === name && !n['parent-id']) || null;
  try {
    const r = await authFetch(API.api_graph_entities + '?scope=tree');
    const payload = await r.json();
    return rootNamed(payload.namespaces);
  } catch (_) {
    return (typeof graphData !== 'undefined' && graphData
      && rootNamed(graphData.namespaces)) || null;
  }
}

async function _tourNsForCreation(created, options) {
  const response = await authFetch(API.api_graph_entities + '?scope=tree', options);
  if (!response.ok) throw new Error('Namespace lookup failed');
  const body = await response.json();
  if (!Array.isArray(body.namespaces)) throw new Error('Invalid namespace response');
  const namespace = body.namespaces.find(row => row.id === created.id);
  if (!namespace) return null;
  if (namespace.name !== created.name || (namespace['parent-id'] || null) !== (created['parent-id'] || null)) {
    throw new Error('Created namespace identity changed');
  }
  return namespace;
}

async function _tourDeleteNamespaces(created, options) {
  const failed = [];
  // Child namespaces first. A refused nonempty namespace is retained; never
  // clear arbitrary contents that another edit may have added since creation.
  for (const entry of created.filter(row => row.type === 'ns').reverse()) {
    if (!entry.id || (entry.receipt !== 'created' && !_tourCreateOnlyManifestReceipt(entry))) {
      failed.push(entry); continue;
    }
    try {
      if (!await _tourNsForCreation(entry, options)) continue;
      if (!await _tourDeleted(() => options
        ? authFetch(API.api_entities_type_id('ns', entry.id), {...options, method: 'DELETE'})
        : authMutate('DELETE', API.api_entities_type_id('ns', entry.id)))) failed.push(entry);
    } catch (_) { failed.push(entry); }
  }
  return failed;
}

// Delete everything the lesson created, in dependency order, and return what
// refused: `{failed: [{type, name}, …]}`. The caller decides what to say.
// `created` is passed IN, not read from `_tourState`: the dialog that calls
// this runs long after the tour stopped, and reading a state something else
// may have cleared turned "delete what the lesson made" into "delete nothing,
// report success".
async function _tourDeleteCreated(created, options) {
  const activeApps = created.some(row => row.type === 'app-route')
    ? await _tourDeleteAppRoutes(created) : [];
  if (activeApps.length) {
    // A public app must be removed before deleting its handler or branch.
    if (typeof _tourSaveState === 'function') _tourSaveState();
    return {failed: created.filter(row => row.receipt !== 'removed')};
  }
  const activeServices = typeof _tourCleanupServices === 'function' ? await _tourCleanupServices(created) : [];
  if (activeServices.length) {
    // Keep the whole lesson graph when a worker's stop or a publish outcome
    // cannot be proved. No branch/function deletion beneath a running worker.
    if (typeof _tourSaveState === 'function') _tourSaveState();
    return {failed: created.filter(row => row.receipt !== 'removed')};
  }
  const unpinned = await _tourUnpinPackages(created);
  if (typeof _tourSaveState === 'function') _tourSaveState();
  const blocked = new Set(unpinned.map(row => row['branch-id']));
  const raw = [
    ...await _tourDeleteHttpPublications(created),
    ...(created.some(row => row.type === 'api-token') ? await _tourDeleteTokens(created) : []),
    ...unpinned,
    ...await _tourDeleteCreatedBranches(created, blocked),
    ...await _tourDeleteFns(created, options),      // fns first — a namespace deletes once empty
    ...await _tourRemovePackages(created, options), // exact owned release, after unpin
    ...await _tourDeleteNamespaces(created, options),
  ];
  if (typeof window !== 'undefined' && typeof window.gdTourRestoreUIComponentPreferences === 'function') {
    try {
      raw.push(...await window.gdTourRestoreUIComponentPreferences(created, raw));
    } catch (_) {
      // Preference recovery is part of cleanup. Retain its ledger even when
      // all graph rows are gone, so returning through Lessons can retry.
      let marker = created.find(row => row.type === 'preference'
        && row.creation === 'component-preference-restoration');
      if (!marker) {
        marker = {type: 'preference', name: 'UI component preferences',
          creation: 'component-preference-restoration'};
        created.push(marker);
      }
      raw.push(marker);
    }
    if (typeof _tourSaveState === 'function') _tourSaveState();
  }
  // One row can refuse twice (an unpin AND a withdraw for the same package);
  // the reader should read its name once.
  const seen = new Set();
  const failed = raw.filter((c) => {
    const k = [c.type, c.name, c.id || c.version || '', c['branch-id'] || ''].join('\u0000');
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  if (typeof initGraph === 'function') { try { await initGraph(); } catch (_) {} }
  return { failed };
}
