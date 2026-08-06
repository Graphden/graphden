// Editor — Users-admin sidebar section.
//
// Server-rendered via GET /partials/users-admin (a table of every user:
// username | org — password hashes are stripped server-side). Only shown to
// authenticated users when the tenancy addon is active — so a single-tenant
// editor never renders it.
//
// Create + delete are pure HTMX, declared in the partial's hiccup (hx-post /
// hx-delete / hx-swap) — there is NO client JS fetch here. The bearer rides on
// every HTMX request via the htmx:configRequest bridge in editor-auth.js. This
// module only decides WHETHER to mount the section and lazy-loads via hx-get.
//
// Shown only to a user who may MANAGE users in their org (org-RBAC: the owner
// or a holder of the `manage-users` capability — surfaced in the capabilities
// header). A plain member never sees an empty/denied panel, and the operator
// (public org, no org-management caps) never sees a cross-org user list.
//
// Globals consumed: isAuthenticated, graphdenTenancyActive, graphdenHasCap, htmx.

function buildUsersAdminSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenTenancyActive === 'function' && !window.graphdenTenancyActive()) {
    return null;
  }
  if (typeof window.graphdenHasCap === 'function' && !window.graphdenHasCap('manage-users')) {
    return null;
  }
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-users-admin';
  // The .ns-children hx-get lazy-loads the server-rendered panel on insert;
  // the panel's own hx-post/hx-delete then handle create/delete + swap.
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo">'
    +   '<span class="ns-label">Users</span>'
    + '</div>'
    + '<div class="ns-children" hx-get="/partials/users-admin" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // Markup is built imperatively; the CALLER runs htmx.process after appending
  // to the connected DOM. hx-trigger="load" only fires when process() runs on a
  // CONNECTED node — processing while detached marks it processed but never
  // fires load, so we must NOT process here (see mountAdminSection in sidebar).
  return wrap;
}
