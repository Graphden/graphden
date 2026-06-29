// Editor — org-admin Grants sidebar section (PLATFORM_PLAN §6).
//
// Server-rendered via GET /partials/grants-admin (a table of every grant:
// subject | capability | namespace). Only shown to authenticated users when
// the tenancy addon is active — so a single-tenant editor never renders it.
//
// Create + delete are pure HTMX, declared in the partial's hiccup (hx-post /
// hx-delete / hx-swap) — there is NO client JS fetch here. The bearer rides on
// every HTMX request via the htmx:configRequest bridge in editor-auth.js. This
// module only decides WHETHER to mount the section (a client-side gate) and
// lazy-loads the panel via hx-get.
//
// Globals consumed: isAuthenticated, graphdenTenancyActive, htmx.

function buildGrantsAdminSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenTenancyActive === 'function' && !window.graphdenTenancyActive()) {
    return null;
  }
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-grants-admin';
  // The .ns-children hx-get lazy-loads the server-rendered panel on insert;
  // the panel's own hx-post/hx-delete then handle create/delete + swap.
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo">'
    +   '<span class="ns-label">Grants</span>'
    + '</div>'
    + '<div class="ns-children" hx-get="/partials/grants-admin" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // We built the markup imperatively, so tell HTMX to bind the hx-get (it only
  // auto-binds at page load); hx-trigger="load" then fires the fetch.
  if (window.htmx && typeof window.htmx.process === 'function') window.htmx.process(wrap);
  return wrap;
}
