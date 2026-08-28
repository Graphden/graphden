// Editor — org-admin Executor sidebar section (Organization surface).
//
// Server-rendered via GET /partials/executor-admin (the tenancy addon):
// the org's execution mode (hosted / byo), whether a BYO executor is
// connected to this hub's SSE relay right now, the run snippet with the
// org prefilled, and where to mint the executor's token. The hosted↔byo
// FLIP stays operator-side (docs/BYO_RUNBOOK.md) — this panel informs,
// it does not flip.
//
// Same shape as the grants section: this module only decides WHETHER to
// mount (client gate) and lazy-loads the panel via hx-get; the server
// route enforces the real authorization.
//
// Globals consumed: isAuthenticated, graphdenTenancyActive,
// graphdenIsOrgOwner, graphdenHasCap.

function buildExecutorsAdminSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenTenancyActive === 'function' && !window.graphdenTenancyActive()) {
    return null;
  }
  // Org infrastructure — shown to the owner (owner-implies-all) or a
  // grants manager, mirroring the other org-admin sections.
  const owner = typeof window.graphdenIsOrgOwner === 'function' && window.graphdenIsOrgOwner();
  const manager = typeof window.graphdenHasCap === 'function' && window.graphdenHasCap('manage-grants');
  if (!owner && !manager) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-executors-admin';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/executor-admin" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // Markup is built imperatively; the CALLER runs htmx.process after
  // appending to the connected DOM (mountAdminSection).
  return wrap;
}
