// Editor Main - Entry point and initialization
// Depends on: editor-state.js, editor-data.js, editor-ui.js, editor-render.js

// ============================================================================
// LAZY, SUBTREE-AWARE GRAPH LOADER
// ============================================================================
//
// The editor no longer pulls every fn on load. The client holds only what
// it is showing, and asks the server scoped questions:
//
//   initGraph()     -> GET ?scope=tree   — namespaces + per-namespace
//                      named-fn counts. O(namespaces). The sidebar paints a
//                      collapsed tree from this; NO fn leaves are loaded yet.
//   expand a ns     -> loadNamespaceFns(nsId) -> ?scope=namespace — that
//                      namespace's light fn rows (the sidebar drives this).
//   filter box      -> searchFns(q) -> ?scope=search — capped name matches.
//   select a fn     -> ensureSubtreeFor(fnId) -> ?scope=subtree — the fn's
//                      slots/bindings/items + its transitive fn closure.
//   resolve a name  -> resolveFnByName(name) -> cache, else ?scope=search.
//
// `graphData.fns` is an ACCUMULATING cache (`_knownFns`), seeded lazily by
// all of the above — never the whole graph at once. BY-ID lookups
// (`lookups.fnMap`) stay correct for anything the user has actually loaded;
// a selected fn's subtree always includes every fn it references, so its
// view renders completely.
let _knownFns = new Map();            // fn-id -> fn row (light or full), merged
let _loadedNamespaceIds = new Set();  // namespaces whose fn leaves are loaded
let _subtreeRootId = null;
let _subtreeFetchPromise = null;

// Merge freshly-fetched fn rows into the accumulating cache. Full (subtree)
// rows and light (tree/namespace/search) rows coexist: a later light row
// spreads over an earlier full row, keeping the full row's extra columns and
// refreshing the shared ones (role, ref-counts, name, namespace-id).
function mergeKnownFns(rows) {
  for (const f of (rows || [])) {
    if (!f?.id) continue;
    const prev = _knownFns.get(f.id);
    _knownFns.set(f.id, prev ? { ...prev, ...f } : f);
  }
}

// Point graphData.fns at the current cache snapshot + rebuild lookups.
function syncKnownFnsIntoGraph() {
  if (!graphData) return;
  graphData.fns = Array.from(_knownFns.values());
  lookups = buildLookups(graphData);
}
window.syncKnownFnsIntoGraph = syncKnownFnsIntoGraph;

// True once a namespace's fn leaves have been fetched. The sidebar uses
// this to decide whether an expanded node can render leaves or must load.
function isNamespaceLoaded(nsId) {
  return _loadedNamespaceIds.has(nsId || '');
}
window.isNamespaceLoaded = isNamespaceLoaded;

// True while a namespace's fetch is in flight. The sidebar checks this so it
// triggers loadNamespaceFns (with its re-render `.then`) exactly ONCE per
// load — re-rendering while a fetch is pending (e.g. many namespaces
// expanded at once) must not keep attaching fresh `.then` callbacks, which
// would fan out into a re-render storm when the promise finally resolves.
function isNamespaceLoading(nsId) {
  return _nsFetchInFlight.has(nsId || '');
}
window.isNamespaceLoading = isNamespaceLoading;

// Fetch one namespace's fn leaves (light rows) and merge them. `nsId` may be
// null for the "(root)" bucket (namespace-less fns). Caches per-ns so a
// re-expand doesn't refetch; loadGraphData() clears the set on mutation. An
// in-flight map dedupes concurrent calls for the same namespace — the sidebar
// re-renders (and re-invokes this) many times while a fetch is pending, e.g.
// when several namespaces are expanded at once.
const _nsFetchInFlight = new Map();
async function loadNamespaceFns(nsId) {
  const key = nsId || '';
  if (_loadedNamespaceIds.has(key)) return;
  if (_nsFetchInFlight.has(key)) return _nsFetchInFlight.get(key);
  const p = (async () => {
    const url = API.api_graph_entities + '?scope=namespace'
      + (nsId ? '&namespace-id=' + encodeURIComponent(nsId) : '');
    const r = await fetch(url);
    if (!r.ok) throw new Error('loadNamespaceFns HTTP ' + r.status);
    const payload = await r.json();
    mergeKnownFns(payload.fns);
    _loadedNamespaceIds.add(key);
    syncKnownFnsIntoGraph();
  })();
  _nsFetchInFlight.set(key, p);
  try { await p; } finally { _nsFetchInFlight.delete(key); }
}
window.loadNamespaceFns = loadNamespaceFns;

// Server-side name search (filter box + pickers). Returns the matching light
// fn rows (also merged into the cache so BY-ID reads see them).
async function searchFns(q) {
  const needle = (q || '').trim();
  if (!needle) return { fns: [], truncated: false };
  const r = await fetch(API.api_graph_entities + '?scope=search&q=' + encodeURIComponent(needle));
  if (!r.ok) throw new Error('searchFns HTTP ' + r.status);
  const payload = await r.json();
  mergeKnownFns(payload.fns);
  syncKnownFnsIntoGraph();
  return { fns: payload.fns || [], truncated: !!payload['truncated?'] };
}
window.searchFns = searchFns;

// Resolve a fn by its (globally-unique) name to its row. Fast path: the
// accumulating cache; slow path: an exact-match server search. Used by
// deep-link nav, type-override resolution, base-fn links, secret-leaf, etc.
async function resolveFnByName(name) {
  if (!name) return null;
  for (const f of _knownFns.values()) {
    if (f.name === name) return f;
  }
  const { fns } = await searchFns(name);
  return fns.find(f => f.name === name) || null;
}
window.resolveFnByName = resolveFnByName;

async function ensureSubtreeFor(fnId) {
  if (!fnId) return;
  if (_subtreeRootId === fnId && Array.isArray(graphData?.bindings)) return;
  if (_subtreeFetchPromise) return _subtreeFetchPromise;
  _subtreeFetchPromise = (async () => {
    try {
      const r = await fetch(
        API.api_graph_entities + '?scope=subtree&root-id=' + encodeURIComponent(fnId));
      if (!r.ok) throw new Error('ensureSubtreeFor HTTP ' + r.status);
      const sub = await r.json();
      // Merge the subtree's fns (the selected fn + its full transitive
      // closure) into the cache, and overlay its heavy relational rows.
      // Namespaces / counts stay from the :tree load.
      mergeKnownFns(sub.fns);
      graphData.slots = sub.slots;
      graphData['fn-slots'] = sub['fn-slots'];
      graphData.bindings = sub.bindings;
      graphData['list-items'] = sub['list-items'];
      _subtreeRootId = fnId;
      syncKnownFnsIntoGraph();
    } catch (err) {
      _subtreeFetchPromise = null;
      throw err;
    } finally {
      _subtreeFetchPromise = null;
    }
  })();
  return _subtreeFetchPromise;
}
window.ensureSubtreeFor = ensureSubtreeFor;

// Fresh empty graph shell seeded from a `:tree` payload. Slots/bindings are
// filled per-fn by ensureSubtreeFor; fns accumulate via the cache.
function graphShellFromTree(tree) {
  return {
    fns: [],
    namespaces: tree.namespaces || [],
    counts: tree.counts || [],
    slots: [], 'fn-slots': [], bindings: [], 'list-items': [],
  };
}

// ============================================================================
// INITIALIZATION
// ============================================================================

/**
 * Initialize the graph editor
 */
async function initGraph() {
  // Load the namespace tree + the rich-type registry in parallel. Types feed
  // the in-place edit popovers' "Expected: <type>" hints, so they need to be
  // ready before the user opens any editor.
  //
  // /api/services is loaded eagerly too — auth-required, so anonymous
  // visitors see no service badges (loadServicesEager swallows the 401
  // silently). Cheap and primed before the first overlay render.
  //
  // `?scope=tree` — namespaces + counts only. Fn leaves load lazily per
  // expanded namespace; per-fn slots/bindings load via ensureSubtreeFor().
  _subtreeRootId = null;
  _subtreeFetchPromise = null;
  _knownFns = new Map();
  _loadedNamespaceIds = new Set();
  const [entResp, typeResp, vkResp] = await Promise.all([
    fetch(API.api_graph_entities + '?scope=tree'),
    fetch(API.api_types).catch(() => null),
    fetch(API.api_value_kinds).catch(() => null),
    (typeof loadServicesEager === 'function')
      ? loadServicesEager().catch(() => null)
      : null,
  ]);
  graphData = graphShellFromTree(await entResp.json());
  lookups = buildLookups(graphData);
  if (typeResp?.ok) {
    try { richTypes = await typeResp.json(); } catch (err) {
      // eslint-disable-next-line no-console
      console.error(API.api_types + ' JSON parse failed — type tooltips will be empty', err);
      richTypes = {};
    }
    // The type registry just (re)loaded, so any cached
    // `/api/types/compatible` verdicts may now be stale. `initGraph` is
    // also the fn-rename refresh path, where a retype changes the answers.
    if (typeof clearTypesCompatibleCache === 'function') clearTypesCompatibleCache();
  }
  if (vkResp?.ok) {
    try { VALUE_KINDS = await vkResp.json(); } catch (err) {
      // eslint-disable-next-line no-console
      console.error(API.api_value_kinds + ' JSON parse failed — type-picker may be incomplete', err);
      VALUE_KINDS = [];
    }
  }
  // Resolve the secret-leaf base-fn id once so isSecretFn() stays
  // synchronous without a full-fns mirror to scan.
  if (typeof primeSecretLeafId === 'function') primeSecretLeafId();
  updateEntityList(graphData);
  // First-load hash navigation: `#fn-name` in the URL (bookmark,
  // shared link, page reload) must select the fn — `hashchange` /
  // `popstate` only fire on AFTER-load changes, not the initial
  // load. Without this, opening /#app.server.web-server (or any
  // hashed URL) leaves the canvas blank until the user clicks the
  // sidebar. Awaited so `await initGraph()` returns with the hashed
  // fn's subtree loaded (its slots/bindings/closure), not still in
  // flight — callers read `lookups.fnMap` right after.
  const hash = window.location.hash.slice(1);
  if (hash) {
    await selectFnByName(decodeURIComponent(hash), false);
  }
}


// Re-fetch the graph state after a mutation (e.g. crud.secrets/create
// → new fn-def + binding appear in the graph). Callable from
// editor-secrets.js etc. so the ns-tree / graph pick up the new entries
// without a full page reload. Kept as a separate fn from `init()` so it
// doesn't also re-fire the auth / hash navigation work.
async function loadGraphData() {
  // Post-mutation refresh: re-fetch the namespace tree (counts can shift on
  // create/delete/rename) AND re-prime the current subtree. The accumulating
  // fn cache + per-ns load flags are reset so stale / renamed / deleted rows
  // don't linger; the sidebar re-fetches leaves for still-expanded
  // namespaces on its next render, and ensureSubtreeFor re-primes the
  // selected fn below.
  const prevRoot = _subtreeRootId;
  _subtreeRootId = null;
  _subtreeFetchPromise = null;
  _knownFns = new Map();
  _loadedNamespaceIds = new Set();
  let treeResp;
  let typeResp;
  try {
    // Refresh the rich-type registry alongside the tree: a mutation can
    // change a fn's INFERRED type (literal narrowing, type-override,
    // structural edits), and `richTypes` feeds every type-chip. value-kinds
    // / services are NOT re-fetched (they only change on type-create /
    // service edits, which keep using `initGraph`).
    [treeResp, typeResp] = await Promise.all([
      fetch(API.api_graph_entities + '?scope=tree'),
      fetch(API.api_types).catch(() => null),
    ]);
  } catch (err) {
    // Surface network drops in DevTools — caller (post-mutation
    // refresh) silently leaves stale state on the screen otherwise.
    // eslint-disable-next-line no-console
    console.error('loadGraphData fetch threw', err);
    return;
  }
  if (!treeResp.ok) {
    // eslint-disable-next-line no-console
    console.error('loadGraphData HTTP', treeResp.status, treeResp.statusText);
    return;
  }
  graphData = graphShellFromTree(await treeResp.json());
  lookups = buildLookups(graphData);
  if (typeResp?.ok) {
    try { richTypes = await typeResp.json(); }
    catch (_) { /* keep prior richTypes rather than blanking chips */ }
    // A mutation may have added / renamed / retyped a fn-def, changing the
    // type registry — drop the cached `/api/types/compatible` verdicts so the
    // next type-picker / mismatch check re-asks the server.
    if (typeof clearTypesCompatibleCache === 'function') clearTypesCompatibleCache();
  }
  if (typeof primeSecretLeafId === 'function') primeSecretLeafId();
  // Re-fetch subtree for the previously-rendered fn so overlays /
  // type-chips reflect the mutation. `renderGraph` would do this
  // anyway, but a fresh prime here keeps any synchronous reads of
  // `lookups` from racing on stale-empty state between this
  // function returning and the next paint.
  if (prevRoot) {
    try { await ensureSubtreeFor(prevRoot); }
    catch (err) {
      // eslint-disable-next-line no-console
      console.error('loadGraphData subtree refresh failed', err);
    }
  }
  updateEntityList(graphData);

  // Exactly one render: `selectFnByName` → `selectFn` → `renderGraph`
  // when a hash is present, else `renderGraph` directly. An earlier
  // unconditional `renderGraph` here fired a second, redundant layout +
  // subtree re-fetch on every mutation.
  const hash = window.location.hash.slice(1);
  if (hash && typeof selectFnByName === 'function') {
    await selectFnByName(decodeURIComponent(hash), false);
  } else if (typeof renderGraph === 'function') {
    renderGraph(true);
  }
}

// ============================================================================
// HISTORY NAVIGATION
// ============================================================================

// `popstate` covers browser back/forward. `hashchange` covers direct
// URL-bar edits and `location.hash = '…'` assignments — popstate does
// NOT fire for those, so without hashchange a bookmark / shared link
// pasted into the address bar after the editor is loaded would do
// nothing.
function _onHashNav() {
  const hash = window.location.hash.slice(1);
  if (hash && graphData) selectFnByName(decodeURIComponent(hash), false);
}
window.addEventListener('popstate', _onHashNav);
window.addEventListener('hashchange', _onHashNav);

// ============================================================================
// DOM READY
// ============================================================================

// Apply width / theme / collapsed state ASAP — body exists once
// `editor-prefs.js` is loaded (it's in the bundled `<script>` at
// end of body).
if (typeof initPrefsEarly === 'function') initPrefsEarly();

document.addEventListener('DOMContentLoaded', () => {
  initPrefsLate();
  initAuthLock();
  if (typeof initBranchSelector === 'function') initBranchSelector();
  // Top-level catch so a failed initial /api/graph/entities load (network
  // outage, 5xx, branch-router error) shows a user-visible error in the
  // header instead of leaving the editor silently broken with just an
  // uncaught-promise message in DevTools.
  initGraph().catch((err) => {
    // eslint-disable-next-line no-console
    console.error('initGraph failed', err);
    const banner = document.createElement('div');
    banner.style.cssText =
      'position:fixed;top:0;left:0;right:0;z-index:99999;' +
      'padding:8px 16px;background:#c0392b;color:#fff;font:14px sans-serif;';
    banner.textContent =
      'Editor failed to load graph data. Check network / server logs, then reload.';
    document.body.appendChild(banner);
  });
});
