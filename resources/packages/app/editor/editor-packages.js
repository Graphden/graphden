// Editor — Packages sidebar section (PACKAGE_DISTRIBUTION §8, 3e).
//
// Server-rendered via GET /partials/packages-panel (a read-only table of the
// packages pinned on the current branch: Package | Version). Graph-first — the
// markup is a fn-def returning hiccup; this module only builds the collapsible
// section shell and lazy-loads the panel via hx-get. No client-side HTML.
//
// Shown to authenticated users (pins are per-branch; the /api/packages/installed
// endpoint the partial reads is auth-required). NOT tenancy-gated — packages
// exist in single-tenant too, unlike the Grants/Users admin sections.
//
// The `registry` package is OPTIONAL (it installs its routes via the route-
// collection seam). When a deployment omits it, `/partials/packages-panel` and
// the `/api/packages/*` routes 404 — so gate the whole section on a window.API
// probe (`api_packages_installed` is present only when registry contributed its
// routes). Mirrors how buildGrantsAdminSection gates on graphdenTenancyActive().
//
// Globals consumed: isAuthenticated, window.API, htmx. Mirrors editor-grants-admin.js.

// True iff the optional `registry` package is loaded — its `/api/packages/*`
// routes are in window.API only when its router was installed at boot.
function registryLoaded() {
  return typeof window.API === 'object' && window.API !== null
    && typeof window.API.api_packages_installed !== 'undefined';
}

function buildPackagesSection() {
  if (!isAuthenticated()) return null;
  if (!registryLoaded()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-packages';
  // .ns-children hx-get lazy-loads the server-rendered panel on insert.
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo">'
    +   '<span class="ns-label">Packages</span>'
    + '</div>'
    + '<div class="ns-children" hx-get="/partials/packages-panel" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // Markup is built imperatively; the CALLER runs htmx.process after appending
  // to the connected DOM. hx-trigger="load" only fires when process() runs on a
  // CONNECTED node — processing while detached marks it processed but never
  // fires load, so we must NOT process here (see mountAdminSection in sidebar).
  return wrap;
}
