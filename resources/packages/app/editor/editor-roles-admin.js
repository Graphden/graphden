// Editor — Roles-admin sidebar section (org-RBAC).
//
// Server-rendered via GET /partials/roles-admin (a table of the org's roles:
// name | capabilities | members | delete; plus a create form). Shown only to a
// user who may MANAGE roles in their org (the `manage-roles` org-management
// capability, from the X-Graphden-Capabilities header) — so a plain member /
// the operator never sees it, and a single-tenant editor never renders it.
//
// Create + delete + set-members are pure HTMX in the partial's hiccup. The one
// bit of client JS: the create form's capabilities are chosen via checkboxes,
// but the form parser is single-value-per-key, so on submit we inject the
// checked capabilities into the request as a comma-joined `capabilities` param
// (htmx:configRequest, which runs after form serialization).
//
// Globals consumed: isAuthenticated, graphdenTenancyActive, graphdenHasCap, htmx.

function buildRolesAdminSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenTenancyActive === 'function' && !window.graphdenTenancyActive()) {
    return null;
  }
  if (typeof window.graphdenHasCap === 'function' && !window.graphdenHasCap('manage-roles')) {
    return null;
  }
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-roles-admin';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/roles-admin" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // Collect the checked capability checkboxes into the create POST's
  // `capabilities` param (comma-joined). Bubbles up from the lazy-loaded form.
  wrap.addEventListener('htmx:configRequest', (evt) => {
    const form = evt.detail?.elt?.closest?.('.role-create-form');
    if (!form || !evt.detail.parameters) return;
    const caps = [...form.querySelectorAll('.role-cap-cb:checked')].map((cb) => cb.value);
    evt.detail.parameters.capabilities = caps.join(',');
  });
  // Same lazy-load contract as the other admin sections: the CALLER runs
  // htmx.process after appending to the connected DOM (see mountAdminSection).
  return wrap;
}
