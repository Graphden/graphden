// editor-assets.js — the Operate "Assets" section (UI Step 1): frontend
// bundle files with their override status, and an inline editor that
// saves per-branch `:resource-override` rows shadowing the shipped
// JS/CSS. Server-rendered partial (/partials/assets-panel); htmx owns
// the whole edit/save/revert flow — this module only mounts the shell.
//
// Globals consumed: isAuthenticated, graphdenTenancyActive.

function buildAssetsSection() {
  if (!isAuthenticated()) return null;
  // Cloud: `:resource-override` writes are system-only (tenant-forbidden
  // even for a platform-admin through the generic path — stored-XSS
  // surface), so a panel that can't save is noise. Self-host /
  // single-tenant only.
  if (typeof window.graphdenTenancyActive === 'function' && window.graphdenTenancyActive()) {
    return null;
  }
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-assets';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/assets-panel" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // Same lazy-load contract as the other admin sections: the CALLER runs
  // htmx.process after appending to the connected DOM (mountAdminSection).
  return wrap;
}
