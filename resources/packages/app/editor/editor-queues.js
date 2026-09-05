// editor-queues.js — the Operate "Queues" section: every queue with its
// pending / dead counts and the dead letters with Requeue / Delete
// (docs/SERVICES.md § Queues). Server-rendered partial
// (/partials/queues-panel); the buttons are hx-posts that act and swap the
// refreshed panel in — this module only mounts the shell. Shown wherever
// the caller is signed in: reads are org-scoped on the cloud, so a tenant
// sees its own queues.
//
// Globals consumed: isAuthenticated.

function buildQueuesSection() {
  if (!isAuthenticated()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-queues';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/queues-panel" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // Same lazy-load contract as the other admin sections: the CALLER runs
  // htmx.process after appending to the connected DOM (mountAdminSection).
  return wrap;
}
