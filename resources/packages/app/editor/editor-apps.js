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
// Shown to authenticated users ON A TENANCY deployment only: /partials/apps-panel
// lives in the addon-only tenancy-admin package, so on a single-tenant instance
// it 404s. We gate on window.API.api_orgs_apps (the /api/orgs/apps route, present
// only when tenancy-admin is loaded) — the same "is tenancy active" signal the
// org-switcher uses (api_memberships) — so a single-tenant editor never mounts
// the section and never logs the 404. Mirrors editor-errors.js; the caller
// (editor-sidebar.js mountAdminSection) runs htmx.process after appending, so
// the hx-get on a CONNECTED node fires.
//
// Globals consumed: isAuthenticated, window.API, htmx.

function buildAppsSection() {
  if (!isAuthenticated()) return null;
  // Tenancy-only: no addon → no /api/orgs/apps route → no Apps section (avoids a
  // console 404 for /partials/apps-panel on single-tenant instances).
  if (!window.API?.api_orgs_apps) return null;
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
