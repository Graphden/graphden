// Editor — Errors sidebar section (Phase C2 observability).
//
// Server-rendered via GET /partials/error-log: the current org's recent
// FAILED executions (newest first), error text/data already redacted +
// scrubbed at write time. Graph-first — the markup is a fn-def returning
// hiccup; this module only builds the collapsible section shell and
// lazy-loads the panel via hx-get. Fn names are plain #hash links, so
// navigation rides the editor's native hashchange handling — no JS here.
//
// Shown to authenticated users (the partial route is auth-required). NOT
// tenancy-gated — failures exist in single-tenant too. Mirrors
// editor-packages.js; the caller (editor-sidebar.js mountAdminSection) runs
// htmx.process after appending, so the hx-get on a CONNECTED node fires.
//
// Globals consumed: isAuthenticated, htmx.

function buildErrorsSection() {
  if (!isAuthenticated()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-errors';
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo">'
    +   '<span class="ns-label">Errors</span>'
    + '</div>'
    + '<div class="ns-children" hx-get="/partials/error-log" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  return wrap;
}
