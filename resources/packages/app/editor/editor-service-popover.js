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


// Side effect: also refreshes servicesCache so the next loadServiceForFn
// returns the freshest entry without a duplicate HTTP roundtrip.
async function refreshServicesCache() {
  servicesCache = await fetchServices();
  return servicesCache;
}


// Branch-aware lookup. The same fn-id can have several `:service`
// rows — one per branch (`:branch-id` scopes each entry to its own
// `ExecutionContext`). When the user opens the popover from main,
// they expect to see the main-branch service; from feat → the feat
// service. Picking blindly via `services.find` returned whichever
// row the backend ordered first and silently mis-routed save/delete
// across branches. Preference order:
//   1. row whose `:branch-id` matches the editor's current branch
//   2. row with no `:branch-id` (legacy "(any)" entry — reconciler
//      falls back to the base ExecutionContext)
//   3. first match (defensive — shouldn't trigger in practice once
//      every row has either an explicit branch-id or null)
async function loadServiceForFn(fnId) {
  const cache = servicesCache || await refreshServicesCache();
  if (!cache?.services) return null;
  const matches = cache.services.filter((s) => s['fn-id'] === fnId);
  if (matches.length === 0) return null;
  // Resolve the current branch's id via the cached /api/branches
  // result that the popover already needs anyway. Falls through to
  // `null` when the branches list isn't primed yet, leaving the
  // null-branch + first-match fallback to do the right thing for
  // the legacy single-branch path.
  let currentBranchId = null;
  try {
    const branches = await fetchBranchesForPicker();
    const currentName = (typeof getCurrentBranchName === 'function')
      ? getCurrentBranchName() : 'main';
    currentBranchId = branches.find(
      (b) => b.name === currentName)?.id || null;
  } catch (_) {}
  if (currentBranchId) {
    const onCurrent = matches.find((s) => s['branch-id'] === currentBranchId);
    if (onCurrent) return onCurrent;
  }
  const legacy = matches.find((s) => !s['branch-id']);
  if (legacy) return legacy;
  return matches[0];
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
  // `:branch-id` is optional on the wire: an empty string clears the
  // field (legacy no-branch-id behavior), a UUID scopes the run to
  // that branch's ExecutionContext. We always emit the key so a PUT
  // can switch a service from "any branch" to "this branch" and back.
  if (data.branchId) body.set('branch-id', data.branchId);
  const url = existing
    ? '/api/entities/service/' + encodeURIComponent(existing.id)
    : '/api/entities/service';
  return authFetch(url, {
    method: existing ? 'PUT' : 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: body.toString(),
  });
}


// Cached branch list — refreshed once per popover open. The list is
// stable for the popover's lifetime (the user can't create a branch
// from inside the service popover) so a single fetch per open is
// enough.
async function fetchBranchesForPicker() {
  try {
    const r = await authFetch('/api/branches', { method: 'GET' });
    if (!r.ok) return [];
    const body = await r.json();
    return body?.branches || [];
  } catch (_) {
    return [];
  }
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


// Returns the OTHER services for this fn-id — same fn, different
// branch-id. Helpful as a guard against accidentally creating a
// duplicate service when forking + experimenting; an admin who
// already wired `:web-server` on main may not realise they're
// queueing up a sibling row on dev. Returns [] when the
// services-list isn't loaded yet (rare race during editor init).
function siblingServicesForFn(fnId, currentServiceId) {
  if (!servicesCache?.services) return [];
  return servicesCache.services.filter((s) =>
    s['fn-id'] === fnId
    && s.id !== currentServiceId);
}


async function showServicePopover(fnEntity, anchorEl) {
  if (!fnEntity || !anchorEl) return;
  const [existing, branches] = await Promise.all([
    loadServiceForFn(fnEntity.id),
    fetchBranchesForPicker(),
  ]);
  const siblings = siblingServicesForFn(fnEntity.id, existing?.id);
  const branchById = Object.fromEntries(branches.map((b) => [b.id, b]));
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

  // Cross-branch duplicate warning. When another `:service` row
  // already targets THIS fn-id on a different branch, surface the
  // list so the admin doesn't accidentally configure a second
  // sibling. Editing an existing row excludes itself (the row IS
  // already on its branch); creating a new row sees every existing
  // row.
  if (siblings.length > 0) {
    const warn = document.createElement('div');
    warn.className = 'service-popover-sibling-warn';
    const labels = siblings.map((s) => {
      const branchName = branchById[s['branch-id']]?.name
                         || (s['branch-id'] ? '<unknown branch>' : '(any)');
      const runState = s.running?.['stopper-set?'] ? 'running'
                     : s.running?.['start-failed-at'] ? 'failed'
                     : s['enabled?'] ? 'pending' : 'disabled';
      return branchName + ' (' + runState + ')';
    }).join(', ');
    warn.textContent = (siblings.length === 1
                       ? '⚠ Also a service on: '
                       : '⚠ Also services on: ') + labels;
    warn.title = 'Same fn-id, different branch. Reconciler keeps each branch\'s '
               + 'instance separate — verify this is intentional before adding '
               + 'another.';
    el.appendChild(warn);
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

  // Branch picker — :service.branch-id (nullable). Pre-fills with
  // the existing row's branch-id; for a brand-new service, default
  // to the editor's CURRENT branch (most natural for "make this fn
  // run on the branch I'm looking at"). Empty option = legacy no-
  // branch-id row → reconciler falls back to the base ExecutionContext.
  const branchWrap = document.createElement('div');
  branchWrap.className = 'service-popover-branch';
  const branchLbl = document.createElement('div');
  branchLbl.className = 'service-popover-branch-label';
  branchLbl.textContent = 'Branch:';
  branchWrap.appendChild(branchLbl);
  const branchSel = document.createElement('select');
  branchSel.className = 'service-popover-branch-select';
  const currentBranchName = (typeof getCurrentBranchName === 'function')
    ? getCurrentBranchName() : null;
  const currentBranchRow = branches.find((b) => b.name === currentBranchName);
  const defaultBranchId = existing
    ? (existing['branch-id'] || '')
    : (currentBranchRow?.id || '');
  // "Any" — legacy nullable row; reconciler picks base ctx. Keep it
  // available so admins can opt out of per-branch scoping for a
  // service that ought to run regardless of the active branch.
  const optAny = document.createElement('option');
  optAny.value = '';
  optAny.textContent = '(any — legacy)';
  branchSel.appendChild(optAny);
  for (const b of branches) {
    const opt = document.createElement('option');
    opt.value = b.id;
    opt.textContent = b.name;
    branchSel.appendChild(opt);
  }
  branchSel.value = defaultBranchId;
  branchWrap.appendChild(branchSel);
  el.appendChild(branchWrap);

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
    // The save and the follow-up reconcile are TWO independent calls
    // with different failure consequences. Saving failed → row didn't
    // persist, the user needs to retry. Reconcile failed → row IS
    // persisted, the post-edit hook + the next scheduled reconcile
    // pass will catch it up; the user doesn't need to retry. Earlier
    // a single try/catch wrapped both, so a transient reconcile abort
    // (e.g. caused by the page navigating to a fresh view right after
    // save) misleadingly showed "Save failed (network error): Failed
    // to fetch" even though the row was correctly stored.
    let saveResp;
    try {
      saveResp = await saveService(existing, fnEntity.id, {
        enabled: enabledCb.checked,
        restartPolicy: policy,
        branchId: branchSel.value || null,
      });
    } catch (err) {
      alert('Save failed (network error): ' + (err?.message || err));
      saveBtn.disabled = false;
      saveBtn.textContent = existing ? 'Save & reconcile' : 'Create & reconcile';
      return;
    }
    if (!saveResp?.ok) {
      const text = saveResp ? await saveResp.text().catch(() => '') : 'network error';
      alert('Save failed (' + (saveResp?.status) + '): '
            + text.replace(/<[^>]+>/g, '').trim().slice(0, 300));
      saveBtn.disabled = false;
      saveBtn.textContent = existing ? 'Save & reconcile' : 'Create & reconcile';
      return;
    }
    // Save succeeded. Reconcile failures are observability-grade, NOT
    // user-action-required — the post-edit hook in
    // `crud/entities.invalidate!` already restarts dependent services,
    // and the next periodic reconcile pass catches anything else.
    try {
      const rec = await reconcileServices();
      if (rec && !rec.ok) {
        alert('Saved but reconcile failed — restart the pod or call '
              + 'POST /api/services/reconcile manually.');
      }
    } catch (_) {
      // Most often an AbortError caused by navigating away (e.g.
      // a branch switch immediately after save). Row is safe; the
      // reconciler will pick it up. Silently swallow.
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
      // authFetch THROWS on network rejection — without try-catch the button
      // would stay disabled with no user feedback.
      try {
        const r = await deleteService(existing.id);
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
