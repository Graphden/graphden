// Editor — Users-admin sidebar section (PLATFORM_PLAN §4.1).
//
// Server-rendered via GET /partials/users-admin (a table of every user:
// username | org — password hashes are stripped server-side). Only shown to
// authenticated users when the tenancy addon is active (a capability header
// has been seen) — so a single-tenant editor never renders it. The partial
// itself degrades to a "not active" notice if reached without the addon.
//
// Globals consumed: authFetch, isAuthenticated, graphdenTenancyActive.

// Synchronous wrapper: returns the section div (or null when it shouldn't
// show) + kicks off the async fetch that swaps the table in.
function buildUsersAdminSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenTenancyActive === 'function' && !window.graphdenTenancyActive()) {
    return null;
  }
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-users-admin';
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo">'
    +   '<span class="ns-label">Users</span>'
    + '</div>'
    + '<div class="ns-children"><div class="loading">Loading…</div></div>';
  // One delegated listener survives the .ns-children innerHTML swaps.
  wireUsersAdmin(wrap);
  refreshUsersAdmin(wrap);
  return wrap;
}

// Event-delegation for the [data-act] buttons the partial emits:
//   delete-user → DELETE /api/entities/user/:id (generic CRUD)
//   create-user → POST /api/users with the form-encoded inputs
function wireUsersAdmin(wrap) {
  wrap.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-act]');
    if (!btn || !wrap.contains(btn)) return;
    const act = btn.dataset.act;
    if (act === 'delete-user') {
      const id = btn.dataset.userId;
      if (id && window.confirm('Delete this user?')) {
        await authFetch('/api/entities/user/' + encodeURIComponent(id), { method: 'DELETE' });
        refreshUsersAdmin(wrap);
      }
    } else if (act === 'create-user') {
      const username = wrap.querySelector('[name="username"]')?.value.trim();
      const password = wrap.querySelector('[name="password"]')?.value;
      const org = wrap.querySelector('[name="org"]')?.value.trim();
      if (username && password && org) {
        const body = new URLSearchParams({ username, password, org }).toString();
        // api-url-drift-allow: /api/users is served by the tenancy-admin addon
        // (route-collection seam), not the core router the drift check scans.
        await authFetch('/api/users', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body,
        });
        refreshUsersAdmin(wrap);
      }
    }
  });
}

async function refreshUsersAdmin(wrap) {
  try {
    const r = await authFetch('/partials/users-admin');
    if (!r.ok) return; // leave the placeholder; 401 surfaces via the lock icon
    const html = await r.text();
    const child = wrap.querySelector('.ns-children');
    if (child) child.innerHTML = html;
  } catch (_) {
    /* leave the loading state — a transient fetch error shouldn't blank the UI */
  }
}
