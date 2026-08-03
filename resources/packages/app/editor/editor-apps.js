// Editor — Apps sidebar section (Track C4b).
//
// Server-rendered via GET /partials/apps-panel: the current tenant org's
// NAMED APPS (`:app-route` rows — a subdomain label + the fn it serves).
// Graph-first — the markup (table + create form + per-row delete) is a fn-def
// returning hiccup; this module only builds the collapsible section shell and
// lazy-loads the panel via hx-get. Create / delete are real <form hx-post>s
// inside the partial that swap the refreshed panel back into [data-apps-panel]
// — no client JS here beyond the shell.
//
// Shown to authenticated users (the partial route is auth-required, org-scoped
// by current-org). Mirrors editor-errors.js / editor-packages.js; the caller
// (editor-sidebar.js mountAdminSection) runs htmx.process after appending, so
// the hx-get on a CONNECTED node fires.
//
// Globals consumed: isAuthenticated, htmx.

function buildAppsSection() {
  if (!isAuthenticated()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-apps';
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo">'
    +   '<span class="ns-label">Apps</span>'
    + '</div>'
    + '<div class="ns-children" hx-get="/partials/apps-panel" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  return wrap;
}
