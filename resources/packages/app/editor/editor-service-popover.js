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
    if (!r.ok) return null;
    return await r.json();
  } catch (_) { return null; }
}


// Side effect: also refreshes servicesCache so the next loadServiceForFn
// returns the freshest entry without a duplicate HTTP roundtrip.
async function refreshServicesCache() {
  servicesCache = await fetchServices();
  return servicesCache;
}


async function loadServiceForFn(fnId) {
  const cache = servicesCache || await refreshServicesCache();
  if (!cache?.services) return null;
  return cache.services.find((s) => s['fn-id'] === fnId) || null;
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


async function saveService(existing, fnId, data) {
  // existing service → PUT; new → POST. Form-encoded body — same
  // shape parse-service-from-form accepts.
  const body = new URLSearchParams();
  body.set('fn-id', fnId);
  body.set('enabled?', data.enabled ? 'true' : 'false');
  body.set('restart-policy', data.restartPolicy);
  const url = existing
    ? '/api/entities/service/' + encodeURIComponent(existing.id)
    : '/api/entities/service';
  return authFetch(url, {
    method: existing ? 'PUT' : 'POST',
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

function renderStatusLine(running) {
  if (!running) return 'Not yet started (reconcile to apply).';
  if (running['start-failed-at']) {
    return 'Start failed — exhausted ' + (running['start-attempts'] || 1)
      + ' retries at ' + running['start-failed-at'].slice(11, 19) + ' UTC.';
  }
  if (running['stopper-set?']) {
    const since = running['started-at']
      ? ' since ' + running['started-at'].slice(11, 19) + ' UTC'
      : '';
    return 'Running' + since + '.';
  }
  return 'Tracked but no active stopper.';
}


async function showServicePopover(fnEntity, anchorEl) {
  if (!fnEntity || !anchorEl) return;
  const existing = await loadServiceForFn(fnEntity.id);
  const el = ensureServicePopoverEl();
  el.textContent = '';

  const head = document.createElement('div');
  head.className = 'service-popover-header';
  const title = document.createElement('span');
  title.className = 'service-popover-title';
  title.textContent = (existing ? 'Service: :' : 'Make service: :')
    + (fnEntity.name || '(anonymous)');
  head.appendChild(title);
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'service-popover-close';
  close.setAttribute('aria-label', 'Close service popover');
  close.textContent = '×';
  close.addEventListener('click', (e) => {
    e.stopPropagation();
    hideServicePopover();
  });
  head.appendChild(close);
  el.appendChild(head);

  if (existing) {
    const status = document.createElement('div');
    status.className = 'service-popover-status';
    status.textContent = renderStatusLine(existing.running);
    el.appendChild(status);
  }

  // Enabled toggle
  const enabledLabel = document.createElement('label');
  enabledLabel.className = 'service-popover-option';
  const enabledCb = document.createElement('input');
  enabledCb.type = 'checkbox';
  enabledCb.className = 'service-popover-enabled';
  enabledCb.checked = existing ? existing['enabled?'] : true;
  enabledLabel.appendChild(enabledCb);
  enabledLabel.appendChild(document.createTextNode(' Enabled'));
  el.appendChild(enabledLabel);

  // Restart policy radios
  const policyWrap = document.createElement('div');
  policyWrap.className = 'service-popover-policy';
  const policyLbl = document.createElement('div');
  policyLbl.className = 'service-popover-policy-label';
  policyLbl.textContent = 'Restart policy:';
  policyWrap.appendChild(policyLbl);
  const currentPolicy = existing
    ? String(existing['restart-policy']).replace(/^:/, '')
    : 'always';
  for (const p of ['always', 'on-failure', 'never']) {
    const lbl = document.createElement('label');
    lbl.className = 'service-popover-policy-option';
    const radio = document.createElement('input');
    radio.type = 'radio';
    radio.name = 'service-restart-policy';
    radio.value = p;
    radio.checked = (p === currentPolicy);
    lbl.appendChild(radio);
    lbl.appendChild(document.createTextNode(' ' + p));
    policyWrap.appendChild(lbl);
  }
  el.appendChild(policyWrap);

  // Action bar
  const actions = document.createElement('div');
  actions.className = 'service-popover-actions';
  const saveBtn = document.createElement('button');
  saveBtn.type = 'button';
  saveBtn.className = 'service-popover-save-btn';
  saveBtn.textContent = existing ? 'Save & reconcile' : 'Create & reconcile';
  saveBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    saveBtn.disabled = true;
    saveBtn.textContent = 'Saving…';
    const policy = el.querySelector('input[name="service-restart-policy"]:checked')?.value
                   || 'always';
    const r = await saveService(existing, fnEntity.id, {
      enabled: enabledCb.checked,
      restartPolicy: policy,
    });
    if (!r || !r.ok) {
      const text = r ? await r.text().catch(() => '') : 'network error';
      alert('Save failed (' + (r?.status) + '): '
            + text.replace(/<[^>]+>/g, '').trim().slice(0, 300));
      saveBtn.disabled = false;
      saveBtn.textContent = existing ? 'Save & reconcile' : 'Create & reconcile';
      return;
    }
    const rec = await reconcileServices();
    if (!rec || !rec.ok) {
      alert('Saved but reconcile failed — restart the pod or call '
            + 'POST /api/services/reconcile manually.');
    }
    // Invalidate cache so the next open shows fresh state.
    servicesCache = null;
    hideServicePopover();
  });
  actions.appendChild(saveBtn);

  if (existing) {
    const delBtn = document.createElement('button');
    delBtn.type = 'button';
    delBtn.className = 'service-popover-delete-btn';
    delBtn.textContent = 'Delete service';
    delBtn.title = 'Removes the :service row; reconcile stops the running fn.';
    delBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      if (!confirm('Delete the :service for :' + fnEntity.name
                   + '? The running fn will stop on reconcile.')) return;
      delBtn.disabled = true;
      const r = await deleteService(existing.id);
      if (!r || !r.ok) {
        alert('Delete failed (' + (r?.status) + ')');
        delBtn.disabled = false;
        return;
      }
      await reconcileServices();
      servicesCache = null;
      hideServicePopover();
    });
    actions.appendChild(delBtn);
  }
  el.appendChild(actions);

  // Position + show
  if (servicePopoverAnchor && servicePopoverAnchor !== anchorEl) {
    try { servicePopoverAnchor.setAttribute('aria-expanded', 'false'); }
    catch (_) {}
  }
  try { anchorEl.setAttribute('aria-expanded', 'true'); } catch (_) {}
  el.classList.add('visible');
  anchorBelowClamped(el, anchorEl,
                     {fallbackW: 320, fallbackH: 200});
  servicePopoverAnchor = anchorEl;
}


installPopoverDismiss({
  getEl: () => servicePopoverEl,
  getAnchor: () => servicePopoverAnchor,
  isVisible: servicePopoverVisible,
  onDismiss: hideServicePopover,
});


window.showServicePopover = showServicePopover;
window.hideServicePopover = hideServicePopover;
// Public for the row-actions button — checks if a service exists so
// the glyph can flip between "Make service" and "Service settings".
window.loadServiceForFn = loadServiceForFn;
window.loadAllServiceFnIds = loadAllServiceFnIds;
window.getServiceForFnId = getServiceForFnId;
window.serviceBadgeState = serviceBadgeState;
window.loadServicesEager = loadServicesEager;
window.refreshServicesCache = refreshServicesCache;
