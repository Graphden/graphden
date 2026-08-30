// Editor — operator Orgs-admin sidebar section (org-RBAC).
//
// Server-rendered via GET /partials/orgs-admin (a table of every org: name |
// plan | a plan-change / ban control). This is the OPERATOR's outside-in org
// management — raise/lower a plan (limits) or set "suspended" (ban) — NOT a way
// into a tenant's users/grants (those are org-managed, gated on the org's own
// admins). Shown ONLY to a platform-admin (the `platform-admin` capability in
// the X-Graphden-Capabilities header); a plain tenant / org-admin never sees it.
//
// The plan change is pure HTMX in the partial's hiccup (hx-post /api/orgs/plan).
//
// Globals consumed: isAuthenticated, graphdenTenancyActive, graphdenHasCap, htmx.

function buildOrgsAdminSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenTenancyActive === 'function' && !window.graphdenTenancyActive()) {
    return null;
  }
  // Gated on the fine-grained `manage-orgs` platform right (platform-admin
  // implies it — the operator still sees it; a manage-orgs delegate does too).
  if (typeof window.graphdenHasCap === 'function' && !window.graphdenHasCap('manage-orgs')) {
    return null;
  }
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-orgs-admin';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/orgs-admin" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // The filter's behaviour. Markup comes from the server with the rest of
  // the panel; the listener lives on THIS wrapper because the panel body is
  // replaced by every HTMX swap (plan changes re-render it), and a listener
  // inside would be thrown away with it. Name matching only — the operator
  // is finding an org, not querying it.
  wrap.addEventListener('input', (e) => {
    const box = e.target.closest('[data-orgs-filter]');
    if (!box) return;
    const q = box.value.trim().toLowerCase();
    for (const row of wrap.querySelectorAll('tr[data-org]')) {
      row.hidden = q !== '' && !row.dataset.org.toLowerCase().includes(q);
    }
  });
  // Same lazy-load contract as the other admin sections: the CALLER runs
  // htmx.process after appending to the connected DOM (see mountAdminSection).
  return wrap;
}
