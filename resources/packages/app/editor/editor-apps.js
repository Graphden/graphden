// Editor — Apps (the org's NAMED APPS: `:app-route` rows, a subdomain label +
// the fn it serves).
//
// Two halves live here:
//   - the app-routes CACHE the sidebar reads synchronously (▣ kind-markers,
//     the apps lens + its chip count);
//   - the PER-FN Apps popover — ▣ in the root-row actions menu, content
//     server-rendered by the tenancy addon's GET /partials/fn-apps partial
//     (graph-first: list + create form + per-row delete are fn-defs returning
//     hiccup; create/delete are real <form hx-post>s swapping the refreshed
//     block back into [data-fn-apps]).
//
// The old Organization "Apps" panel is retired (2026-08-30): publishing
// starts from the fn — same model as declaring a :service — and the lens is
// the org-wide overview.
//
// Tenancy-only: /partials/fn-apps lives in the addon-only tenancy-admin
// package, so on a single-tenant instance it 404s. We gate on
// window.API.api_orgs_apps (the /api/orgs/apps route, present only when
// tenancy-admin is loaded) — the same "is tenancy active" signal the
// org-switcher uses (api_memberships) — and editor-row-actions.js hides the
// ▣ menu row off the same probe, so a single-tenant editor never fetches it.
//
// Globals consumed: window.API, htmx, authFetch, anchorBelowClamped,
// installPopoverDismiss, focusIntoDialog, updateEntityList, graphData.

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

// Namespace-ids holding at least one app-route handler fn — from the
// rows' `handler-namespace-id` (the tenancy addon joins it off the
// handler fn row). The apps LENS uses this to keep a not-yet-loaded
// namespace visible. Empty on addon versions predating the field —
// the lens then simply stays load-dependent for apps (graceful).
function appRouteNsIds() {
  const ids = new Set();
  for (const a of (Array.isArray(appRoutesCache) ? appRoutesCache : [])) {
    if (a['handler-namespace-id']) ids.add(a['handler-namespace-id']);
  }
  return ids;
}

// The app label an :app-route serves under (the `<label>.<apps-domain>`
// subdomain). The apps-domain isn't exposed client-side, so the marker
// tooltip shows the bare label — unambiguous, and the per-fn Apps popover
// renders the full public host.
function appRouteHost(route) {
  return route?.label || null;
}

// === Per-fn Apps popover =====================================================
// ▣ in the root-row actions menu (the Organization Apps panel is retired —
// publishing starts from the fn, like declaring a service does). Content is
// the tenancy addon's server partial GET /partials/fn-apps?fn-id= — this fn's
// :app-route rows + a create form; create/delete are hx-post forms inside the
// partial that swap the refreshed block back into [data-fn-apps]. This module
// owns only the popover lifecycle (anchor, dismiss scaffold, focus) and
// refreshes the client apps cache after every swap so the ▣ tree markers,
// the apps lens and its chip count stay true immediately.

let fnAppsPopoverEl = null;
let fnAppsPopoverAnchor = null;
// Supersession token — a slow response for fn A must not clobber a popover
// the user has since opened for fn B (mirrors editor-service-popover.js).
let fnAppsPopoverFnId = null;

function ensureFnAppsPopoverEl() {
  if (fnAppsPopoverEl) return fnAppsPopoverEl;
  const el = document.createElement('div');
  el.className = 'fn-apps-popover';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Apps');
  el.addEventListener('htmx:afterSwap', () => {
    // A create/delete just landed: the swap replaced [data-fn-apps] (focus
    // fell to <body>) and the org's app set changed.
    Promise.resolve(refreshAppRoutesCache()).then(() => {
      if (typeof updateEntityList === 'function'
          && typeof graphData !== 'undefined' && graphData) {
        updateEntityList(graphData);
      }
    });
    if (typeof focusIntoDialog === 'function') focusIntoDialog(el);
  });
  document.body.appendChild(el);
  fnAppsPopoverEl = el;
  return el;
}

function fnAppsPopoverVisible() {
  return !!fnAppsPopoverEl && fnAppsPopoverEl.classList.contains('visible');
}

function hideFnAppsPopover() {
  if (!fnAppsPopoverEl) return;
  fnAppsPopoverEl.classList.remove('visible');
  if (fnAppsPopoverAnchor) {
    try { fnAppsPopoverAnchor.setAttribute('aria-expanded', 'false'); } catch (_) {}
  }
  fnAppsPopoverAnchor = null;
  fnAppsPopoverFnId = null;
}

async function showFnAppsPopover(fnEntity, anchorEl) {
  if (!fnEntity || !anchorEl) return;
  // Tenancy-only (the ▣ menu row is hidden without the addon; this is the
  // belt to that suspender).
  if (!window.API?.api_orgs_apps) return;
  const el = ensureFnAppsPopoverEl();
  fnAppsPopoverFnId = fnEntity.id;
  el.textContent = '';
  try {
    const resp = await authFetch('/partials/fn-apps?fn-id=' + encodeURIComponent(fnEntity.id));
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const html = await resp.text();
    if (fnAppsPopoverFnId !== fnEntity.id) return; // superseded
    el.innerHTML = html;
    if (window.htmx && typeof window.htmx.process === 'function') window.htmx.process(el);
  } catch (err) {
    if (fnAppsPopoverFnId !== fnEntity.id) return; // superseded
    const msg = document.createElement('div');
    msg.className = 'fn-apps-error';
    msg.textContent = 'Failed to load apps: ' + (err?.message || 'network error');
    el.replaceChildren(msg);
  }
  if (fnAppsPopoverAnchor && fnAppsPopoverAnchor !== anchorEl) {
    try { fnAppsPopoverAnchor.setAttribute('aria-expanded', 'false'); } catch (_) {}
  }
  try { anchorEl.setAttribute('aria-expanded', 'true'); } catch (_) {}
  el.classList.add('visible');
  anchorBelowClamped(el, anchorEl, { fallbackW: 320, fallbackH: 200 });
  fnAppsPopoverAnchor = anchorEl;
  if (typeof focusIntoDialog === 'function') focusIntoDialog(el);
}

installPopoverDismiss({
  getEl: () => fnAppsPopoverEl,
  getAnchor: () => fnAppsPopoverAnchor,
  isVisible: fnAppsPopoverVisible,
  onDismiss: hideFnAppsPopover,
  trapFocus: true,
  getReturnFocus: () => fnAppsPopoverAnchor,
});

window.showFnAppsPopover = showFnAppsPopover;
window.hideFnAppsPopover = hideFnAppsPopover;
