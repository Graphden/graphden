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
  const [entResp, typeResp] = await Promise.all([
    fetch('/api/graph/entities'),
    fetch('/api/types').catch(() => null)
  ]);
  graphData = await entResp.json();
  lookups = buildLookups(graphData);
  if (typeResp?.ok) {
    try { richTypes = await typeResp.json(); } catch (_) { richTypes = {}; }
  }
  updateEntityList(graphData);

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

window.addEventListener('popstate', () => {
  const hash = window.location.hash.slice(1);
  if (hash && graphData) selectFnByName(decodeURIComponent(hash), false);
});

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
  initGraph();
});
