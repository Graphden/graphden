// Editor — Platform-access delegation section (Platform surface).
//
// Server-rendered via GET /partials/platform-access: a table of accounts holding
// fine-grained platform rights, plus a grant form (email + capability) and a
// per-row revoke. Grant / revoke are pure HTMX declared in the partial's hiccup;
// this module only decides WHETHER to mount + lazy-loads via hx-get.
//
// Shown only to a holder of `manage-platform-access` (the meta-right to delegate
// platform access; platform-admin implies it). A plain user never sees it. Lives
// on the Platform surface (#gd-platform-panels) — cross-org, not org-scoped.
//
// Globals consumed: isAuthenticated, graphdenHasCap, htmx.

function buildPlatformAccessSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenHasCap === 'function'
      && !window.graphdenHasCap('manage-platform-access')) {
    return null;
  }
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-platform-access';
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo">'
    +   '<span class="ns-label">Platform access</span>'
    + '</div>'
    + '<div class="ns-children" hx-get="/partials/platform-access" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // Built imperatively; the caller (mountAdminSection) runs htmx.process after
  // appending to the connected DOM so hx-trigger="load" fires.
  return wrap;
}
