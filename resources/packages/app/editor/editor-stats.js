// Editor — Stats sidebar section (Phase C observability, product side).
//
// Server-rendered via GET /partials/stats: the CURRENT org's 7-day run
// statistics (headline totals, a per-day trend table, a top-fns table),
// every number an org-scoped :usage-stat rollup — counts + durations
// only, never args/results/errors, so a tenant sees exactly their own
// workspace. Graph-first: the markup is a fn-def returning hiccup; this
// module only builds the collapsible section shell and lazy-loads the
// panel via hx-get. Fn names are plain #hash links, so navigation rides
// the editor's native hashchange handling — no JS here.
//
// Shown to authenticated users (the partial route is auth-required). NOT
// tenancy-gated — stats exist in single-tenant too. Mirrors
// editor-errors.js; the caller (editor-sidebar.js mountAdminSection) runs
// htmx.process after appending, so the hx-get on a CONNECTED node fires.
//
// Globals consumed: isAuthenticated, htmx.

function buildStatsSection() {
  if (!isAuthenticated()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-stats';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/stats" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  return wrap;
}
