// Editor — Apps sidebar section (Track C4b).
//
// Server-rendered via GET /partials/apps-panel: the current tenant org's
// NAMED APPS (`:app-route` rows — a subdomain label + the fn it serves).
// Graph-first — the markup (table + create form + per-row delete) is a fn-def
// returning hiccup; this module only builds the collapsible section shell and
// lazy-loads the panel via hx-get. Create / delete are real <form hx-post>s
// inside the partial that swap the refreshed panel back into [data-apps-panel]
// — no client JS here beyond the shell.
//
// Shown to authenticated users ON A TENANCY deployment only: /partials/apps-panel
// lives in the addon-only tenancy-admin package, so on a single-tenant instance
// it 404s. We gate on window.API.api_orgs_apps (the /api/orgs/apps route, present
// only when tenancy-admin is loaded) — the same "is tenancy active" signal the
// org-switcher uses (api_memberships) — so a single-tenant editor never mounts
// the section and never logs the 404. Mirrors editor-errors.js; the caller
// (editor-sidebar.js mountAdminSection) runs htmx.process after appending, so
// the hx-get on a CONNECTED node fires.
//
// Globals consumed: isAuthenticated, window.API, htmx, authFetch.

// === App-routes cache =======================================================
// The org's :app-route rows (GET /api/orgs/apps), cached for the SYNC reads
// the sidebar tree makes hot: the ▣ kind-marker + host tooltip on a handler
// fn's row, the apps lens filter, and the apps chip count. Mirrors
// servicesCache (editor-service-popover.js) — primed once per graph load via
// primeAppsCacheOnce (editor-sidebar.js), refreshed on demand.
let appRoutesCache = null;

async function refreshAppRoutesCache() {
  // Address the route by its window.API key ONLY — the boot-time URL-drift
  // guard scans editor JS for /api/* literals against the LIVE router, and
  // this addon-only route doesn't exist on a single-tenant instance.
  const listUrl = window.API?.api_orgs_apps;
  if (!listUrl) { appRoutesCache = []; return appRoutesCache; }
  try {
    const r = await authFetch(listUrl, { method: 'GET' });
    if (!r.ok) {
      if (r.status !== 401) {
        // eslint-disable-next-line no-console
        console.error(listUrl + ' HTTP', r.status, r.statusText);
      }
      appRoutesCache = [];
      return appRoutesCache;
    }
    const body = await r.json();
    appRoutesCache = Array.isArray(body) ? body : [];
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(listUrl + ' fetch threw', err);
    appRoutesCache = [];
  }
  return appRoutesCache;
}

// fn-id → app-route rows, rebuilt only when `appRoutesCache` changes
// (identity-keyed). Like the service index, getAppRoutesForFnId runs ~3× per
// sidebar row per render; a `.filter` per call (which also allocates) is
// O(rows × app-routes). The Map makes it O(1). Built lazily.
let _appRoutesByFnId = null;
let _appRoutesMapSrc = null;
function appRouteIndex() {
  if (_appRoutesMapSrc !== appRoutesCache) {
    _appRoutesByFnId = new Map();
    for (const a of (Array.isArray(appRoutesCache) ? appRoutesCache : [])) {
      const k = a['handler-fn-id'];
      let arr = _appRoutesByFnId.get(k);
      if (!arr) { arr = []; _appRoutesByFnId.set(k, arr); }
      arr.push(a);
    }
    _appRoutesMapSrc = appRoutesCache;
  }
  return _appRoutesByFnId;
}

// Synchronous: every :app-route served by `fnId` (usually 0 or 1; a fn CAN
// back several labels). [] while unprimed / not tenancy.
function getAppRoutesForFnId(fnId) {
  if (!Array.isArray(appRoutesCache)) return [];
  return appRouteIndex().get(fnId) || [];
}

// Synchronous count of app routes — the sidebar apps lens-chip count. null
// while unprimed (no number on the chip rather than a lying 0).
function getAppRouteCount() {
  return Array.isArray(appRoutesCache) ? appRoutesCache.length : null;
}

// The app label an :app-route serves under (the `<label>.<apps-domain>`
// subdomain). The apps-domain isn't exposed client-side, so the marker
// tooltip shows the bare label — unambiguous, and the Apps panel renders
// the full public host.
function appRouteHost(route) {
  return route?.label || null;
}

function buildAppsSection() {
  if (!isAuthenticated()) return null;
  // Tenancy-only: no addon → no /api/orgs/apps route → no Apps section (avoids a
  // console 404 for /partials/apps-panel on single-tenant instances).
  if (!window.API?.api_orgs_apps) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-apps';
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo">'
    +   '<span class="ns-label">Apps</span>'
    + '</div>'
    + '<div class="ns-children" hx-get="/partials/apps-panel" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  return wrap;
}
