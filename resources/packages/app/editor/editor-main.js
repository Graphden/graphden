// Editor Main - Entry point and initialization
// Depends on: editor-state.js, editor-data.js, editor-ui.js, editor-cytoscape.js

// ============================================================================
// SUBTREE-AWARE GRAPH LOADER
// ============================================================================

// `initGraph` fetches `?scope=index` — just fns + namespaces (sidebar
// payload, ~1.6 MB on a 3000-fn graph vs 4.5 MB full). Slots /
// bindings / fn-slots / list-items load PER-FN-VIEW via
// `?scope=subtree&root-id=X` (~1.5 KB - 50 KB typical, ~4.2 MB worst
// case at the app's root fn).
//
// `selectFn` triggers `renderGraph` which awaits `ensureSubtreeFor
// (selectedFnId)` — fetches the subtree for the newly-selected fn,
// MERGES it into `graphData` (sidebar fns + namespaces from index +
// subtree slots/bindings/items), rebuilds `lookups`.
//
// The cache key is the subtree's root fn-id. Navigating to a new fn
// triggers a fresh fetch + lookups rebuild. Mutations
// (`loadGraphData`) clear the cache so the next render re-fetches.
let _subtreeRootId = null;
let _subtreeFetchPromise = null;

async function ensureSubtreeFor(fnId) {
  if (!fnId) return;
  if (_subtreeRootId === fnId && Array.isArray(graphData?.bindings)) return;
  if (_subtreeFetchPromise) return _subtreeFetchPromise;
  _subtreeFetchPromise = (async () => {
    try {
      const r = await fetch(
        '/api/graph/entities?scope=subtree&root-id=' + encodeURIComponent(fnId));
      if (!r.ok) throw new Error('ensureSubtreeFor HTTP ' + r.status);
      const sub = await r.json();
      // Merge: keep the full sidebar fns + namespaces from initGraph's
      // scope=index, overlay the subtree's slots/bindings/items.
      graphData = {
        fns: graphData?.fns || sub.fns,
        namespaces: graphData?.namespaces || sub.namespaces,
        slots: sub.slots,
        'fn-slots': sub['fn-slots'],
        bindings: sub.bindings,
        'list-items': sub['list-items'],
      };
      _subtreeRootId = fnId;
      lookups = buildLookups(graphData);
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

// ============================================================================
// INITIALIZATION
// ============================================================================

/**
 * Initialize the graph editor
 */
async function initGraph() {
  // Load entities + the rich-type registry in parallel. Types feed
  // the in-place edit popovers' "Expected: <type>" hints, so they
  // need to be ready before the user opens any editor.
  //
  // /api/services is loaded eagerly too — auth-required, so anonymous
  // visitors see no service badges (loadServicesEager swallows the
  // 401 silently). Cheap (<30B per row) and primed before the first
  // overlay render so the badge has data on first paint.
  //
  // `?scope=index` — fns + namespaces only. The heavy slot /
  // binding / fn-slot / list-item rows fetch per-fn-view via
  // `ensureSubtreeFor()` from `renderGraph`.
  _subtreeRootId = null;
  _subtreeFetchPromise = null;
  const [entResp, typeResp, vkResp] = await Promise.all([
    fetch('/api/graph/entities?scope=index'),
    fetch('/api/types').catch(() => null),
    fetch('/api/value-kinds').catch(() => null),
    (typeof loadServicesEager === 'function')
      ? loadServicesEager().catch(() => null)
      : null,
  ]);
  graphData = await entResp.json();
  lookups = buildLookups(graphData);
  if (typeResp?.ok) {
    try { richTypes = await typeResp.json(); } catch (err) {
      // eslint-disable-next-line no-console
      console.error('/api/types JSON parse failed — type tooltips will be empty', err);
      richTypes = {};
    }
  }
  if (vkResp?.ok) {
    try { VALUE_KINDS = await vkResp.json(); } catch (err) {
      // eslint-disable-next-line no-console
      console.error('/api/value-kinds JSON parse failed — type-picker may be incomplete', err);
      VALUE_KINDS = [];
    }
  }
  updateEntityList(graphData);
  // First-load hash navigation: `#fn-name` in the URL (bookmark,
  // shared link, page reload) must select the fn — `hashchange` /
  // `popstate` only fire on AFTER-load changes, not the initial
  // load. Without this, opening /#app.server.web-server (or any
  // hashed URL) leaves the canvas blank until the user clicks the
  // sidebar. Mirrors the same hash-handling that `loadGraphData()`
  // does after a graph refresh.
  const hash = window.location.hash.slice(1);
  if (hash) {
    selectFnByName(decodeURIComponent(hash), false);
  }
}


// Re-fetch the graph state after a mutation (e.g. crud.secrets/create
// → new fn-def + binding appear in `/api/graph/entities`). Callable
// from editor-secrets.js etc. so the ns-tree / cytoscape pick up
// the new entries without a full page reload. Kept as a separate
// fn from `init()` so it doesn't also re-fire the auth / hash
// navigation work.
async function loadGraphData() {
  // Post-mutation refresh: re-fetch the sidebar index AND the
  // current subtree (if any) so both reflect the write. Splitting
  // the two fetches is still less data than the legacy 4.5 MB full
  // pull, except when the selected fn is the app root (rare).
  //
  // Invalidate the subtree cache so `renderGraph`'s next
  // `ensureSubtreeFor` re-fetches even when the selected fn hasn't
  // changed.
  const prevRoot = _subtreeRootId;
  _subtreeRootId = null;
  _subtreeFetchPromise = null;
  let r;
  try {
    r = await fetch('/api/graph/entities?scope=index');
  } catch (err) {
    // Surface network drops in DevTools — caller (post-mutation
    // refresh) silently leaves stale state on the screen otherwise.
    // eslint-disable-next-line no-console
    console.error('loadGraphData fetch threw', err);
    return;
  }
  if (!r.ok) {
    // eslint-disable-next-line no-console
    console.error('loadGraphData HTTP', r.status, r.statusText);
    return;
  }
  graphData = await r.json();
  lookups = buildLookups(graphData);
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
  if (typeof renderGraph === 'function') renderGraph(true);

  const hash = window.location.hash.slice(1);
  if (hash) {
    selectFnByName(decodeURIComponent(hash), false);
  } else {
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
