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
}


// === API helpers ===========================================================

async function fetchServices() {
  try {
    const r = await authFetch('/api/services', { method: 'GET' });
    if (!r.ok) {
      if (r.status !== 401) {
        // eslint-disable-next-line no-console
        console.error('/api/services HTTP', r.status, r.statusText);
      }
      return null;
    }
    return await r.json();
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('/api/services fetch threw', err);
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


// Synchronous read of the cached service for `fnId` — used by the
// fn-card badge renderer. Returns the service row + running state,
// or null when no entry exists. Does NOT trigger a fetch (badge
// render happens hot, on every overlay rebuild); the cache must be
// primed via loadServicesEager() at editor startup.
function getServiceForFnId(fnId) {
  if (!servicesCache?.services) return null;
  return servicesCache.services.find((s) => s['fn-id'] === fnId) || null;
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
  await refreshServicesCache();
}


async function saveService(existingId, fnId, data) {
  // existing service → PUT; new → POST. Form-encoded body — same
  // shape parse-service-from-form accepts.
  const body = new URLSearchParams();
  body.set('fn-id', fnId);
  body.set('enabled?', data.enabled ? 'true' : 'false');
  body.set('restart-policy', data.restartPolicy);
  // `:branch-id` is optional on the wire: an empty string clears the
  // field (legacy no-branch-id behavior), a UUID scopes the run to
  // that branch's ExecutionContext. We always emit the key so a PUT
  // can switch a service from "any branch" to "this branch" and back.
  if (data.branchId) body.set('branch-id', data.branchId);
  const url = existingId
    ? '/api/entities/service/' + encodeURIComponent(existingId)
    : '/api/entities/service';
  return authFetch(url, {
    method: existingId ? 'PUT' : 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: body.toString(),
  });
}


async function deleteService(serviceId) {
  return authFetch('/api/entities/service/' + encodeURIComponent(serviceId),
                   {method: 'DELETE'});
}


async function reconcileServices() {
  return authFetch('/api/services/reconcile', {method: 'POST'});
}


// === Render ================================================================
//
// Body hiccup is server-rendered (`GET /partials/service-popover`).
// JS owns: outer mount lifecycle, anchored positioning, dismissal,
// and the save/delete handler logic. The partial emits stable
// `data-existing-service-id` (empty for new rows) on the save +
// delete buttons so the handler picks PUT/POST + which row id to
// target without a separate /api lookup.

async function showServicePopover(fnEntity, anchorEl) {
  if (!fnEntity || !anchorEl) return;
  const el = ensureServicePopoverEl();
  el.textContent = '';
  let resp;
  try {
    resp = await authFetch(
      '/partials/service-popover?fn-id=' + encodeURIComponent(fnEntity.id));
  } catch (err) {
    el.innerHTML = '<div class="service-popover-error">'
      + 'Failed to load service settings: ' + (err?.message || 'network error')
      + '</div>';
    el.classList.add('visible');
    anchorBelowClamped(el, anchorEl, {fallbackW: 320, fallbackH: 200});
    servicePopoverAnchor = anchorEl;
    return;
  }
  if (!resp.ok) {
    el.innerHTML = '<div class="service-popover-error">'
      + 'Failed to load service settings (HTTP ' + resp.status + ')'
      + '</div>';
    el.classList.add('visible');
    anchorBelowClamped(el, anchorEl, {fallbackW: 320, fallbackH: 200});
    servicePopoverAnchor = anchorEl;
    return;
  }
  el.innerHTML = await resp.text();
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
      // Save + reconcile are TWO independent calls with different
      // failure consequences (see prior version for full rationale).
      let resp;
      try {
        resp = await saveService(existingId, fnEntity.id,
                                 { enabled, restartPolicy: policy, branchId });
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
      servicesCache = null;
      hideServicePopover();
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
        servicesCache = null;
        hideServicePopover();
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
