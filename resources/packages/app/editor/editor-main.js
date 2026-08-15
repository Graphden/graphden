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

// Resolve a fn by name to its row. Names are unique PER NAMESPACE
// (ADR-identity-model stage 5), so the input may be qualified —
// `ns.path/name` pins the namespace; a bare name resolves when it is
// unique. Fast path: the accumulating cache; slow path: an
// exact-match server search. A bare name matching fns in SEVERAL
// namespaces warns with the qualified candidates and returns the
// first match (deep-links predating duplication keep working; new
// links should qualify). Used by deep-link nav, type-override
// resolution, base-fn links, secret-leaf, etc.
async function resolveFnByName(name) {
  if (!name) return null;
  // slash >= 0: the backend's ROOT-namespace spelling is "/foo"
  // (empty ns before the slash) — `> 0` used to misread it as a
  // bare name "/foo" that matches nothing. wantNs === '' now
  // legitimately means "the root namespace".
  const slash = name.indexOf('/');
  const wantNs = slash >= 0 ? name.slice(0, slash) : null;
  const bare = slash >= 0 ? name.slice(slash + 1) : name;
  const nsPathOf = (f) => {
    // '' (not null) for a root-ns fn, so wantNs === '' can match it.
    if (f['namespace-id'] == null) return '';
    const p = lookups?.nsPathMap?.get(f['namespace-id']);
    return p || null;
  };
  const matches = (pool) => {
    const hits = [];
    for (const f of pool) {
      if (f.name !== bare) continue;
      if (wantNs !== null && nsPathOf(f) !== wantNs) continue;
      hits.push(f);
    }
    return hits;
  };
  const pick = (hits) => {
    if (hits.length === 0) return null;
    if (hits.length > 1) {
      const qual = hits.map(f => (nsPathOf(f) || '?') + '/' + f.name);
      const msg = '"' + bare + '" exists in several namespaces — picked '
                  + qual[0] + '. Qualify as ns.path/name to pin. '
                  + 'Candidates: ' + qual.join(', ');
      // eslint-disable-next-line no-console
      console.warn('resolveFnByName: ' + msg);
      if (typeof window.showTransientWarning === 'function') {
        window.showTransientWarning('Ambiguous name: ' + msg, 8000);
      }
    }
    return hits[0];
  };
  const cached = pick(matches(_knownFns.values()));
  if (cached) return cached;
  const { fns } = await searchFns(bare);
  const found = pick(matches(fns));
  if (found) return found;
  // Legacy dotted-qualified input ("a.b.foo" — the old hash form, or
  // a dotted candidate label): retry as qualified "a.b/foo", then as
  // the bare last segment.
  if (slash < 0 && name.includes('.')) {
    const cut = name.lastIndexOf('.');
    const q = await resolveFnByName(name.slice(0, cut) + '/' + name.slice(cut + 1));
    if (q) return q;
    return resolveFnByName(name.slice(cut + 1));
  }
  return null;
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
  // Tree first, alone: on an auth-gated deployment an anonymous boot
  // used to fire all four fetches and paint FOUR red 401s into the
  // console before the sign-in prompt. The tree's status answers the
  // auth question for everyone — only when it isn't 401 do the three
  // secondary fetches go out (in parallel; they start on the tree's
  // response headers, so the authed path pays ~one RTT).
  const entResp = await fetch(API.api_graph_entities + '?scope=tree');
  const [typeResp, vkResp] = (entResp.status === 401)
    ? [null, null]
    : await Promise.all([
        fetch(API.api_types).catch(() => null),
        fetch(API.api_value_kinds).catch(() => null),
        (typeof loadServicesEager === 'function')
          ? loadServicesEager().catch(() => null)
          : null,
      ]);
  // Auth wall (B3): the graph view is login-gated when auth is active, so an
  // unauthenticated (or stale-token) boot gets 401 here — send the user to
  // sign in instead of dying into the red fatal banner. Tenancy deployments
  // (the capability header on this very 401 set `gd-tenancy` via the fetch
  // wrap) have the full /login page; single-tenant has no such page, so open
  // the lock popover instead.
  if (entResp.status === 401) {
    clearAuthPassword(); // whatever we sent (or didn't) doesn't authenticate
    if (document.body.classList.contains('gd-tenancy')) {
      const next = location.pathname + location.search + location.hash;
      location.href = '/login?next=' + encodeURIComponent(next);
    } else if (typeof openAuthPopover === 'function') {
      void openAuthPopover('Sign in to use the editor'); // fire-and-forget; fields mount async
    }
    return;
  }
  graphData = graphShellFromTree(await entResp.json());
  lookups = buildLookups(graphData);
  if (typeResp?.ok) {
    try { richTypes = await typeResp.json(); } catch (err) {
      // eslint-disable-next-line no-console
      console.error(API.api_types + ' JSON parse failed — type tooltips will be empty', err);
      richTypes = {};
    }
    // Server-partial popovers key rich args by binding-id in
    // `_rowActionsUseSiteArgs`; entries staled by this refresh would
    // otherwise accumulate for the whole session.
    if (typeof _rowActionsUseSiteArgs !== 'undefined') _rowActionsUseSiteArgs.clear();
    // The type registry just (re)loaded, so any cached
    // `/api/types/compatible` verdicts may now be stale. `initGraph` is
    // also the fn-rename refresh path, where a retype changes the answers.
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
  // Proactive plan-usage badge — the fn count just (re)loaded, so refresh it
  // after every graph mutation (create / delete / rename all call initGraph).
  if (typeof refreshQuotaBadge === 'function') refreshQuotaBadge();
  // First-load hash navigation: `#fn-name` in the URL (bookmark,
  // shared link, page reload) must select the fn — `hashchange` /
  // `popstate` only fire on AFTER-load changes, not the initial
  // load. Without this, opening /#app.server.web-server (or any
  // hashed URL) leaves the canvas blank until the user clicks the
  // sidebar. Awaited so `await initGraph()` returns with the hashed
  // fn's subtree loaded (its slots/bindings/closure), not still in
  // flight — callers read `lookups.fnMap` right after.
  const hash = window.location.hash.slice(1);
  if (typeof gdRouteSurfaceHash === 'function' && gdRouteSurfaceHash(decodeURIComponent(hash))) {
    // `#@settings` / `#@organization` / … — a surface deep link, no fn to select.
  } else if (hash) {
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
    // Post-mutation refresh — prune the binding-id-keyed rich-arg
    // registry the row-actions popover fills; stale entries would
    // otherwise accumulate for the whole session.
    if (typeof _rowActionsUseSiteArgs !== 'undefined') _rowActionsUseSiteArgs.clear();
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
  if (hash && hash.charAt(0) !== '@' && typeof selectFnByName === 'function') {
    await selectFnByName(decodeURIComponent(hash), false);
  } else if (typeof renderGraph === 'function') {
    // `@`-surface hash (management screen up) or no hash — just repaint.
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
  // Surface deep links (`#@settings` …) route to the shell; a fn-name (or
  // empty) hash also pulls the shell back to Build when a surface was up.
  if (typeof gdRouteSurfaceHash === 'function' && gdRouteSurfaceHash(decodeURIComponent(hash))) return;
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

document.addEventListener('DOMContentLoaded', async () => {
  // Landing demo entry (?demo=1) — may store a fresh bearer and reload;
  // in that case skip the rest of the boot (the reload re-runs it authed).
  if (typeof maybeStartLandingDemo === 'function' && (await maybeStartLandingDemo())) return;
  initPrefsLate();
  initAuthLock();
  if (typeof initBranchSelector === 'function') initBranchSelector();
  if (typeof initOrgSwitcher === 'function') initOrgSwitcher();
  // Top-level catch so a failed initial /api/graph/entities load (network
  // outage, 5xx, branch-router error) shows a user-visible error in the
  // header instead of leaving the editor silently broken with just an
  // uncaught-promise message in DevTools.
  initGraph().catch((err) => {
    // eslint-disable-next-line no-console
    console.error('initGraph failed', err);
    const banner = document.createElement('div');
    banner.className = 'editor-fatal-banner';
    banner.textContent =
      'Editor failed to load graph data. Check network / server logs, then reload.';
    document.body.appendChild(banner);
  });
});
