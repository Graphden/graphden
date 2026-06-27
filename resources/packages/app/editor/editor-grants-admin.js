// Editor — org-admin Grants sidebar section (PLATFORM_PLAN §6).
//
// Server-rendered via GET /partials/grants-admin (a table of every grant:
// subject | capability | namespace). Only shown to authenticated users when
// the tenancy addon is active (a capability header has been seen) — so a
// single-tenant editor never renders it. The partial itself degrades to a
// "not active" notice if reached without the addon, so this is belt-and-
// suspenders.
//
// Globals consumed: authFetch, isAuthenticated, graphdenTenancyActive.

// Synchronous wrapper: returns the section div (or null when it shouldn't
// show) + kicks off the async fetch that swaps the table in.
function buildGrantsAdminSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenTenancyActive === 'function' && !window.graphdenTenancyActive()) {
    return null;
  }
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-grants-admin';
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo">'
    +   '<span class="ns-label">Grants</span>'
    + '</div>'
    + '<div class="ns-children"><div class="loading">Loading…</div></div>';
  refreshGrantsAdmin(wrap);
  return wrap;
}

async function refreshGrantsAdmin(wrap) {
  try {
    const r = await authFetch('/partials/grants-admin');
    if (!r.ok) return; // leave the placeholder; 401 surfaces via the lock icon
    const html = await r.text();
    const child = wrap.querySelector('.ns-children');
    if (child) child.innerHTML = html;
  } catch (_) {
    /* leave the loading state — a transient fetch error shouldn't blank the UI */
  }
}
