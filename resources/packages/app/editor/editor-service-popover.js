// Service settings popover — anchored to the ⚙ button in a root
// fn-card's row-actions popover. Lets the admin declare / edit /
// delete a :service row for the fn, and trigger reconciliation.
//
// Phase 1 contract: a service is a no-arg fn (every slot bound via
// the fn-graph). If the fn has free args the button is disabled
// upstream — we never reach this module for those.
//
// One popover at a time — same dismiss scaffold the execute /
// mismatch / provenance popovers use.

let servicePopoverEl = null;
let servicePopoverAnchor = null;
// The fn whose popover is CURRENTLY being opened. Set at the top of every
// open; after an await we compare against it and bail if the user has since
// opened another fn's popover — a slow response must not clobber the newer
// one with the wrong fn's service form. (Supersession guard, mirrors
// editor-fn-versions.js.)
let servicePopoverFnId = null;

// Cached snapshot of /api/services. Refreshed on every open; the
// row-actions popover hasn't been pinned long enough for staleness
// to matter, and we don't want a stale entry to misrepresent
// enabled?/restart-policy.
let servicesCache = null;


function ensureServicePopoverEl() {
  if (servicePopoverEl) return servicePopoverEl;
  const el = document.createElement('div');
  el.className = 'service-popover';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Service settings');
  document.body.appendChild(el);
  servicePopoverEl = el;
  return el;
}


function servicePopoverVisible() {
  return !!servicePopoverEl && servicePopoverEl.classList.contains('visible');
}


function hideServicePopover() {
  if (!servicePopoverEl) return;
  servicePopoverEl.classList.remove('visible');
  servicePopoverEl.style.display = 'none';
  if (servicePopoverAnchor) {
    try { servicePopoverAnchor.setAttribute('aria-expanded', 'false'); }
    catch (_) {}
  }
  servicePopoverAnchor = null;
  servicePopoverFnId = null; // an in-flight open now sees a mismatch and bails
}


// === Tenant mode ===========================================================
//
// In a multi-tenant deployment `:service` is tenant-forbidden, so the platform
// endpoints (`/api/services`, `/api/entities/service`) 403 for a tenant. A
// dedicated-tier tenant instead manages its services through the org-scoped
// `/api/orgs/services*` endpoints (create/list/update/delete via the base
// storage, `:org-id`-gated). We route every service call through those when the
// tenancy addon is active; the tier check is server-side (create 403s a
// non-dedicated org with an "upgrade" message the popover surfaces).
//
// LIMITATION (honest): the tenant list carries DESIRED state only — the
// reconciler's runtime `running` map lives on the tenant's dedicated pod, not
// on the platform handler serving this list, so cross-pod runtime status
// (running / failed) is not shown to a tenant yet (topology-dependent, deferred
// with the dedicated-shard provisioning). A tenant badge shows configured vs
// disabled, never running/failed.
//
// `graphdenTenancyActive()` (a capability header) is NOT enough to route: in a
// multi-tenant deploy the PLATFORM admin (public org) has it too, yet must use
// the platform endpoints. The reliable tenant signal is the quota plan slug —
// nil for the public org, a real slug for a tenant — primed once at startup.
let _tenantPlanTier; // undefined = not primed; null = public/single-tenant; slug otherwise

async function primeTenantPlan() {
  _tenantPlanTier = null;
  if (typeof API !== 'object' || typeof API.api_orgs_quota === 'undefined') return;
  try {
    const r = await authFetch(API.api_orgs_quota, { method: 'GET' });
    if (r?.ok) {
      const q = await r.json();
      _tenantPlanTier = q?.plan || null; // null body ≡ public org
    }
  } catch (_) { /* leave null → platform path; a quota blip must not break services */ }
}

// A real tenant (org ≠ public) — its services live under `:org-id`, reachable
// only through `/api/orgs/services*`. The badge/list layer uses this.
function isRealTenant() { return !!_tenantPlanTier; }

// May MANAGE services: only the dedicated tier (own cgroup-limited pod). The
// create/edit form + the write routes gate on this; a real-but-not-dedicated
// tenant gets an upgrade note instead of a form. Server enforces it regardless.
function tenantServiceMode() { return _tenantPlanTier === 'dedicated'; }


// === API helpers ===========================================================

async function fetchServices() {
  const listUrl = isRealTenant() ? API.api_orgs_services : API.api_services;
  try {
    const r = await authFetch(listUrl, { method: 'GET' });
    if (!r.ok) {
      if (r.status !== 401) {
        // eslint-disable-next-line no-console
        console.error(listUrl + ' HTTP', r.status, r.statusText);
      }
      return null;
    }
    const body = await r.json();
    // Normalise: the tenant endpoint returns a bare array of desired-state rows;
    // the platform endpoint returns `{services, running}`. Downstream readers
    // expect `{services}`, so wrap the tenant array (no runtime `running`).
    return Array.isArray(body) ? { services: body } : body;
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(listUrl + ' fetch threw', err);
    return null;
  }
}


// Side effect: also refreshes servicesCache so subsequent sync reads
// (`getServiceForFnId` for the badge, `loadAllServiceFnIds` for the
// sidebar filter) see the freshest data without a duplicate roundtrip.
async function refreshServicesCache() {
  servicesCache = await fetchServices();
  return servicesCache;
}


// Public — Set<fn-id> covered by ANY :service row (enabled or not).
// Used by the sidebar's "Only services" filter to decide which fn
// items survive the prune. Returns an empty set when the API is
// unreachable so callers see "no services" instead of crashing.
async function loadAllServiceFnIds() {
  const cache = servicesCache || await refreshServicesCache();
  if (!cache?.services) return new Set();
  return new Set(cache.services.map((s) => s['fn-id']));
}


// fn-id → service row index, rebuilt only when `servicesCache` changes
// (identity-keyed). getServiceForFnId is called ~3× per sidebar row on every
// tree render, so a linear `.find` per call is O(rows × services) — at scale
// (a cloud org with hundreds of services) that dominates. The Map makes each
// lookup O(1). Built lazily so it costs nothing until the first classify.
let _svcByFnId = null;
let _svcMapSrc = null;
function serviceIndex() {
  if (_svcMapSrc !== servicesCache) {
    _svcByFnId = new Map();
    for (const s of (servicesCache?.services || [])) _svcByFnId.set(s['fn-id'], s);
    _svcMapSrc = servicesCache;
  }
  return _svcByFnId;
}

// Synchronous read of the cached service for `fnId` — used by the
// fn-card badge renderer. Returns the service row + running state,
// or null when no entry exists. Does NOT trigger a fetch (badge
// render happens hot, on every overlay rebuild); the cache must be
// primed via loadServicesEager() at editor startup.
function getServiceForFnId(fnId) {
  if (!servicesCache?.services) return null;
  return serviceIndex().get(fnId) || null;
}


// Synchronous count of DISTINCT fns covered by a :service row — the
// sidebar services lens-chip count. null while the cache is unprimed
// (the chip shows no number rather than a lying 0).
function getAllServiceFnIdCount() {
  if (!servicesCache?.services) return null;
  return serviceIndex().size;
}


// Render-state classifier used by the badge. Returns one of:
//   'running'  — stopper-set + no give-up
//   'failed'   — start-failed-at recorded
//   'disabled' — :enabled? false (admin parked the row)
//   'pending'  — enabled but not yet started (no running entry)
function serviceBadgeState(svc) {
  if (!svc) return null;
  if (!svc['enabled?']) return 'disabled';
  const r = svc.running;
  if (r?.['start-failed-at']) return 'failed';
  if (r?.['stopper-set?']) return 'running';
  return 'pending';
}


// Called from editor-main.js once at startup so the badge has data
// to render against. Cheap (~30B per service); the cache is shared
// with the sidebar filter and per-fn popover so the page pays one
// HTTP roundtrip total.
async function loadServicesEager() {
  // Prime the plan tier BEFORE the first services fetch so `fetchServices`
  // routes to the tenant vs platform list endpoint correctly (a real tenant's
  // `/api/services` would 403).
  await primeTenantPlan();
  await refreshServicesCache();
}


async function saveService(existingId, fnId, data) {
  // existing service → PUT/update; new → POST/create. Form-encoded body — same
  // shape both the platform `parse-service-from-form` and the tenant seam's
  // coercion accept.
  const body = new URLSearchParams();
  body.set('fn-id', fnId);
  body.set('enabled?', data.enabled ? 'true' : 'false');
  body.set('restart-policy', data.restartPolicy);
  body.set('cardinality', data.cardinality);
  // `:pool-size` only matters for :cardinality pool. Emit it when set so a
  // PUT can change the pod count; a blank value clears the column (the
  // reconciler degrades a :pool row with no size to a singleton).
  if (data.poolSize) body.set('pool-size', data.poolSize);
  // `:branch-id` is optional on the wire: an empty string clears the
  // field (legacy no-branch-id behavior), a UUID scopes the run to
  // that branch's ExecutionContext. We always emit the key so a PUT
  // can switch a service from "any branch" to "this branch" and back.
  if (data.branchId) body.set('branch-id', data.branchId);
  let url;
  let method = 'POST';
  if (tenantServiceMode()) {
    // Tenant routes are all POST form (the bare reitit router can't co-locate
    // GET+POST, so update/delete have their own paths); the id rides the body.
    if (existingId) body.set('id', existingId);
    url = existingId ? API.api_orgs_services_update : API.api_orgs_services_create;
  } else {
    url = existingId
      ? API.api_entities_type_id('service', existingId)
      : API.api_entities_type('service');
    method = existingId ? 'PUT' : 'POST';
  }
  return authFetch(url, {
    method,
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: body.toString(),
  });
}


async function deleteService(serviceId) {
  if (tenantServiceMode()) {
    return authFetch(API.api_orgs_services_delete, {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: new URLSearchParams({ id: serviceId }).toString(),
    });
  }
  return authFetch(API.api_entities_type_id('service', serviceId),
                   {method: 'DELETE'});
}


async function reconcileServices() {
  // Tenant mode: no manual reconcile. The create/update/delete write fires a
  // NOTIFY that the tenant's own reconciler (on its dedicated pod) picks up;
  // the platform `/api/services/reconcile` is not a tenant endpoint. Report
  // success so the save/delete flow proceeds to its cache-refresh + re-render.
  if (isRealTenant()) return { ok: true };
  return authFetch(API.api_services_reconcile, {method: 'POST'});
}


// === Render ================================================================
//
// Body hiccup is server-rendered (`GET /partials/service-popover`).
// JS owns: outer mount lifecycle, anchored positioning, dismissal,
// and the save/delete handler logic. The partial emits stable
// `data-existing-service-id` (empty for new rows) on the save +
// delete buttons so the handler picks PUT/POST + which row id to
// target without a separate /api lookup.

// Cache of rendered popover HTML by fn-id. Re-opening the same fn's
// settings (common: comparing configs across fns) skips the ~30-150ms
// server render. Invalidated wholesale on ANY service mutation
// (`invalidateServicePopoverCache`) — a save/delete can shift sibling
// warnings + displacement across fns, so per-fn eviction isn't enough.
// A branch switch reloads the page, dropping this Map with it.
const _servicePopoverCache = new Map();

function invalidateServicePopoverCache() { _servicePopoverCache.clear(); }


// Tenant-mode popover body — client-rendered (the server partial reads the
// tenant-forbidden `:service` and is fleet-complex: cardinality / displacement /
// advisory-lock gating, none of which apply to a dedicated tenant on its own
// pod). Deliberately minimal: enabled? + restart-policy + save/delete. Emits the
// SAME class names + `data-existing-service-id` the server partial does, so the
// shared `wireServicePopoverHandlers` binds it unchanged — and the absent
// cardinality / branch / pool-size controls default to singleton / none there.
function escapeServiceHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) =>
    ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
}


function tenantServicePopoverHtml(fnEntity, svc) {
  const existingId = escapeServiceHtml(svc?.id || '');
  const enabled = svc ? !!svc['enabled?'] : true;
  const policy = svc?.['restart-policy'] || 'always';
  const name = escapeServiceHtml(fnEntity.name || '(anonymous)');
  const radio = (val, label) =>
    '<label><input type="radio" name="service-restart-policy" value="' + val + '"'
    + (policy === val ? ' checked' : '') + '> ' + label + '</label>';
  return ''
    + '<div class="service-popover-header">'
    +   '<span class="service-popover-title">Service — :' + name + '</span>'
    +   '<button class="service-popover-close" aria-label="Close">×</button>'
    + '</div>'
    + '<div class="service-popover-body">'
    +   '<label class="service-popover-enabled-row">'
    +     '<input type="checkbox" class="service-popover-enabled"'
    +     (enabled ? ' checked' : '') + '> Enabled'
    +   '</label>'
    +   '<div class="service-popover-policy" role="radiogroup" aria-label="Restart policy">'
    +     '<div class="service-popover-policy-label">Restart policy</div>'
    +     radio('always', 'Always') + radio('on-failure', 'On failure') + radio('never', 'Never')
    +   '</div>'
    +   '<p class="service-popover-note">Runs on your dedicated executor. '
    +     'Live run status is not shown here yet.</p>'
    + '</div>'
    + '<div class="service-popover-actions">'
    +   '<button class="service-popover-save-btn" data-existing-service-id="' + existingId + '">'
    +     (existingId ? 'Save' : 'Create service') + '</button>'
    +   (existingId
        ? '<button class="service-popover-delete-btn" data-existing-service-id="'
          + existingId + '">Delete</button>'
        : '')
    + '</div>';
}


async function showTenantServicePopover(el, fnEntity, anchorEl) {
  // Fresh desired-state read — the badge cache may be empty/stale, and the
  // popover must reflect the current row so a save is an update, not a dup.
  let svc = null;
  try { await refreshServicesCache(); svc = getServiceForFnId(fnEntity.id); }
  catch (_) { /* fall through — render the create form */ }
  if (servicePopoverFnId !== fnEntity.id) return; // superseded by a newer open
  el.innerHTML = tenantServicePopoverHtml(fnEntity, svc);
  wireServicePopoverHandlers(el, fnEntity);
  if (servicePopoverAnchor && servicePopoverAnchor !== anchorEl) {
    try { servicePopoverAnchor.setAttribute('aria-expanded', 'false'); } catch (_) {}
  }
  try { anchorEl.setAttribute('aria-expanded', 'true'); } catch (_) {}
  el.classList.add('visible');
  anchorBelowClamped(el, anchorEl, {fallbackW: 320, fallbackH: 200});
  servicePopoverAnchor = anchorEl;
}


function showTenantPopoverBody(el, anchorEl, bodyHtml) {
  el.innerHTML = bodyHtml;
  const close = el.querySelector('.service-popover-close');
  if (close) close.addEventListener('click', (e) => { e.stopPropagation(); hideServicePopover(); });
  if (servicePopoverAnchor && servicePopoverAnchor !== anchorEl) {
    try { servicePopoverAnchor.setAttribute('aria-expanded', 'false'); } catch (_) {}
  }
  try { anchorEl.setAttribute('aria-expanded', 'true'); } catch (_) {}
  el.classList.add('visible');
  anchorBelowClamped(el, anchorEl, {fallbackW: 320, fallbackH: 200});
  servicePopoverAnchor = anchorEl;
}


async function showServicePopover(fnEntity, anchorEl) {
  if (!fnEntity || !anchorEl) return;
  const el = ensureServicePopoverEl();
  servicePopoverFnId = fnEntity.id; // supersession token for the awaits below
  el.textContent = '';
  if (isRealTenant()) {
    // A tenant never reaches the platform server partial (it reads the
    // tenant-forbidden :service). Dedicated tier → the management form; any
    // other tenant tier → an upgrade note.
    if (tenantServiceMode()) { await showTenantServicePopover(el, fnEntity, anchorEl); return; }
    showTenantPopoverBody(el, anchorEl, ''
      + '<div class="service-popover-header">'
      +   '<span class="service-popover-title">Service</span>'
      +   '<button class="service-popover-close" aria-label="Close">×</button>'
      + '</div>'
      + '<div class="service-popover-body">'
      +   '<p class="service-popover-note">Persistent services need the dedicated '
      +     'plan — they run on your own executor. Upgrade to enable them.</p>'
      + '</div>');
    return;
  }
  let html = _servicePopoverCache.get(fnEntity.id);
  if (html == null) {
    let resp;
    try {
      resp = await authFetch(
        '/partials/service-popover?fn-id=' + encodeURIComponent(fnEntity.id));
    } catch (err) {
      if (servicePopoverFnId !== fnEntity.id) return; // superseded
      el.innerHTML = '<div class="service-popover-error">'
        + 'Failed to load service settings: ' + (err?.message || 'network error')
        + '</div>';
      el.classList.add('visible');
      anchorBelowClamped(el, anchorEl, {fallbackW: 320, fallbackH: 200});
      servicePopoverAnchor = anchorEl;
      return;
    }
    if (servicePopoverFnId !== fnEntity.id) return; // superseded
    if (!resp.ok) {
      el.innerHTML = '<div class="service-popover-error">'
        + 'Failed to load service settings (HTTP ' + resp.status + ')'
        + '</div>';
      el.classList.add('visible');
      anchorBelowClamped(el, anchorEl, {fallbackW: 320, fallbackH: 200});
      servicePopoverAnchor = anchorEl;
      return;
    }
    html = await resp.text();
    _servicePopoverCache.set(fnEntity.id, html);
  }
  if (servicePopoverFnId !== fnEntity.id) return; // superseded
  el.innerHTML = html;
  wireServicePopoverHandlers(el, fnEntity);

  // Position + show
  if (servicePopoverAnchor && servicePopoverAnchor !== anchorEl) {
    try { servicePopoverAnchor.setAttribute('aria-expanded', 'false'); }
    catch (_) {}
  }
  try { anchorEl.setAttribute('aria-expanded', 'true'); } catch (_) {}
  el.classList.add('visible');
  anchorBelowClamped(el, anchorEl, {fallbackW: 320, fallbackH: 200});
  servicePopoverAnchor = anchorEl;
}


// Bind close / save / delete handlers to the swapped partial body.
// The save and delete buttons carry `data-existing-service-id` —
// non-empty → PUT/DELETE that id, empty → POST a new row.
function wireServicePopoverHandlers(el, fnEntity) {
  const close = el.querySelector('.service-popover-close');
  if (close) {
    close.addEventListener('click', (e) => {
      e.stopPropagation();
      hideServicePopover();
    });
  }

  const saveBtn = el.querySelector('.service-popover-save-btn');
  if (saveBtn) {
    const existingId = saveBtn.dataset.existingServiceId || '';
    const originalLabel = saveBtn.textContent;
    saveBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      saveBtn.disabled = true;
      saveBtn.textContent = 'Saving…';
      const enabled = !!el.querySelector('.service-popover-enabled')?.checked;
      const branchId = el.querySelector('.service-popover-branch-select')?.value || null;
      const policy = el.querySelector('input[name="service-restart-policy"]:checked')?.value
                     || 'always';
      const cardinality = el.querySelector('input[name="service-cardinality"]:checked')?.value
                          || 'singleton';
      const poolSize = el.querySelector('input[name="service-pool-size"]')?.value?.trim() || null;
      // Save + reconcile are TWO independent calls with different
      // failure consequences (see prior version for full rationale).
      let resp;
      try {
        resp = await saveService(existingId, fnEntity.id,
                                 { enabled, restartPolicy: policy, cardinality, branchId, poolSize });
      } catch (err) {
        alert('Save failed (network error): ' + (err?.message || err));
        saveBtn.disabled = false;
        saveBtn.textContent = originalLabel;
        return;
      }
      if (!resp?.ok) {
        const text = resp ? await resp.text().catch(() => '') : 'network error';
        alert('Save failed (' + (resp?.status) + '): '
              + text.replace(/<[^>]+>/g, '').trim().slice(0, 300));
        saveBtn.disabled = false;
        saveBtn.textContent = originalLabel;
        return;
      }
      // Save succeeded. Reconcile failures are observability-grade —
      // post-edit hook restarts dependent services, periodic
      // reconcile catches anything else.
      try {
        const rec = await reconcileServices();
        if (rec && !rec.ok) {
          alert('Saved but reconcile failed — restart the pod or call '
                + 'POST /api/services/reconcile manually.');
        }
      } catch (_) { /* swallow — most often AbortError from navigation */ }
      // RE-PRIME the cache (don't just null it). `getServiceForFnId` is a
      // synchronous badge reader that returns null — and never fetches —
      // when the cache is null, so leaving it null made every service
      // badge (including the one just created) render empty until a reload
      // or filter toggle. Re-fetch, then rebuild overlays so the badge
      // appears immediately.
      try { await refreshServicesCache(); } catch (_) { servicesCache = null; }
      invalidateServicePopoverCache();
      hideServicePopover();
      if (typeof createNodeOverlays === 'function') createNodeOverlays();
    });
  }

  const delBtn = el.querySelector('.service-popover-delete-btn');
  if (delBtn) {
    const existingId = delBtn.dataset.existingServiceId || '';
    delBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      if (!confirm('Delete the :service for :' + (fnEntity.name || '(anonymous)')
                   + '? The running fn will stop on reconcile.')) return;
      delBtn.disabled = true;
      try {
        const r = await deleteService(existingId);
        if (!r?.ok) {
          alert('Delete failed (' + (r?.status) + ')');
          delBtn.disabled = false;
          return;
        }
        await reconcileServices();
        // Re-prime the cache + rebuild overlays (see the save handler) so
        // the removed badge disappears immediately instead of every badge
        // going blank until reload.
        try { await refreshServicesCache(); } catch (_) { servicesCache = null; }
        invalidateServicePopoverCache();
        hideServicePopover();
        if (typeof createNodeOverlays === 'function') createNodeOverlays();
      } catch (err) {
        alert('Delete failed (network error): ' + (err?.message || err));
        delBtn.disabled = false;
      }
    });
  }
}


installPopoverDismiss({
  getEl: () => servicePopoverEl,
  getAnchor: () => servicePopoverAnchor,
  isVisible: servicePopoverVisible,
  onDismiss: hideServicePopover,
});


window.showServicePopover = showServicePopover;
window.hideServicePopover = hideServicePopover;
window.loadAllServiceFnIds = loadAllServiceFnIds;
window.getServiceForFnId = getServiceForFnId;
window.serviceBadgeState = serviceBadgeState;
window.loadServicesEager = loadServicesEager;
window.refreshServicesCache = refreshServicesCache;

// May this session manage services at all? The dedicated tier can (own
// cgroup-limited pod); a free / network tenant cannot, and the platform or a
// single-tenant self-host always can. Exported because the tutorial gates its
// services lesson on it — a lesson nobody on this plan can finish is worse
// than one that says so up front.
window.gdServicesManageable = () => !isRealTenant() || tenantServiceMode();
