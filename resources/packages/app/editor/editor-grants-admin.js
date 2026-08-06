// Editor — org-admin Grants sidebar section.
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
// Shown only to a user who may hand out grants in their org (org-RBAC: the
// owner or a holder of `manage-grants`, from the capabilities header) — not
// every authenticated user, and not the operator (public org, no such cap).
//
// Globals consumed: isAuthenticated, graphdenTenancyActive, graphdenHasCap, htmx.

function buildGrantsAdminSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenTenancyActive === 'function' && !window.graphdenTenancyActive()) {
    return null;
  }
  if (typeof window.graphdenHasCap === 'function' && !window.graphdenHasCap('manage-grants')) {
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
  // Markup is built imperatively; the CALLER runs htmx.process after appending
  // to the connected DOM. hx-trigger="load" only fires when process() runs on a
  // CONNECTED node — processing while detached marks it processed but never
  // fires load, so we must NOT process here (see mountAdminSection in sidebar).
  return wrap;
}
