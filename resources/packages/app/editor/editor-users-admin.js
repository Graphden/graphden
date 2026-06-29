// Editor — Users-admin sidebar section (PLATFORM_PLAN §4.1).
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
// Globals consumed: isAuthenticated, graphdenTenancyActive, htmx.

function buildUsersAdminSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenTenancyActive === 'function' && !window.graphdenTenancyActive()) {
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
  // Built imperatively, so tell HTMX to bind the hx-get; hx-trigger="load"
  // then fires the fetch.
  if (window.htmx && typeof window.htmx.process === 'function') window.htmx.process(wrap);
  return wrap;
}
