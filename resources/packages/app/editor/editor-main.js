// Editor Main - Entry point and initialization
// Depends on: editor-state.js, editor-data.js, editor-ui.js, editor-cytoscape.js

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
  const [entResp, typeResp, vkResp] = await Promise.all([
    fetch('/api/graph/entities'),
    fetch('/api/types').catch(() => null),
    fetch('/api/value-kinds').catch(() => null),
    (typeof loadServicesEager === 'function')
      ? loadServicesEager().catch(() => null)
      : null,
  ]);
  graphData = await entResp.json();
  lookups = buildLookups(graphData);
  if (typeResp?.ok) {
    try { richTypes = await typeResp.json(); } catch (_) { richTypes = {}; }
  }
  if (vkResp?.ok) {
    try { VALUE_KINDS = await vkResp.json(); } catch (_) { VALUE_KINDS = []; }
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
  const r = await fetch('/api/graph/entities');
  if (!r.ok) return;
  graphData = await r.json();
  lookups = buildLookups(graphData);
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
  initGraph();
});
