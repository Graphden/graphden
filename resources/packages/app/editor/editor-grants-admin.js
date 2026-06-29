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
  // One delegated listener on the wrap survives the .ns-children innerHTML
  // swaps that refreshGrantsAdmin does, so wire it once here.
  wireGrantsAdmin(wrap);
  refreshGrantsAdmin(wrap);
  return wrap;
}

// Event-delegation for the [data-act] buttons the partial emits:
//   delete-grant → DELETE /api/entities/grant/:id (generic CRUD)
//   create-grant → POST /api/grants with the form-encoded inputs
function wireGrantsAdmin(wrap) {
  wrap.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-act]');
    if (!btn || !wrap.contains(btn)) return;
    const act = btn.dataset.act;
    if (act === 'delete-grant') {
      const id = btn.dataset.grantId;
      if (id && window.confirm('Delete this grant?')) {
        await authFetch('/api/entities/grant/' + encodeURIComponent(id), { method: 'DELETE' });
        refreshGrantsAdmin(wrap);
      }
    } else if (act === 'create-grant') {
      const subjectEl = wrap.querySelector('[name="subject"]');
      const capabilityEl = wrap.querySelector('[name="capability"]');
      const namespaceEl = wrap.querySelector('[name="namespace"]');
      const subject = subjectEl?.value.trim();
      const capability = capabilityEl?.value.trim();
      const namespace = namespaceEl?.value.trim();
      if (subject && capability && namespace) {
        const body = new URLSearchParams({ subject, capability, namespace }).toString();
        // api-url-drift-allow: /api/grants is served by the tenancy-admin addon
        // (route-collection seam), not the core router the drift check scans.
        await authFetch('/api/grants', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body,
        });
        refreshGrantsAdmin(wrap);
      }
    }
  });
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
