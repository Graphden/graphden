// Editor fn-version history — popover showing every :fn-version row
// for one fn, joined with the branch name it was authored on. Anchored
// below the ⌛ History action in the fn-card row-actions popover (see
// editor-overlay-fn.js).
//
// Server owns the HTML projection (`/partials/fn-versions` returns
// hiccup composed in `app/editor/fns.edn`). Each row carries `hx-*`
// attributes that lazy-fetch the executions sub-list on click via
// `/partials/fn-version-executions` — no JS expansion code needed.
//
// This module owns: mount-point lifecycle, fetch (auth-aware), HTMX
// process-after-swap, anchoring, dismissal, plus the post-swap
// binding for switch + restore actions (those need page navigation /
// confirm() — wiring them through HTMX is a separate POC).

let _fnVersionsPopover = null;
let _fnVersionsAnchor = null;
let _fnVersionsFnId = null;
let _fnVersionsFnEntity = null;

function ensureFnVersionsPopover() {
  if (_fnVersionsPopover) return _fnVersionsPopover;
  const el = document.createElement('div');
  el.id = 'fn-versions-popover';
  el.className = 'fn-versions-popover hidden';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Version history');
  document.body.appendChild(el);
  _fnVersionsPopover = el;
  if (typeof installPopoverDismiss === 'function') {
    installPopoverDismiss({
      getEl: () => _fnVersionsPopover,
      getAnchor: () => _fnVersionsAnchor,
      isVisible: () => _fnVersionsPopover
        && !_fnVersionsPopover.classList.contains('hidden'),
      onDismiss: closeFnVersionsPopover,
    });
  }
  return el;
}

function closeFnVersionsPopover() {
  if (_fnVersionsPopover) {
    _fnVersionsPopover.classList.add('hidden');
    _fnVersionsAnchor = null;
    _fnVersionsFnId = null;
    _fnVersionsFnEntity = null;
  }
}

// HTMX-aware swap: replace innerHTML and ALSO run htmx.process so the
// fresh `hx-*` attributes inside the swapped content get bound. Without
// this, HTMX only auto-binds at page load; subsequent innerHTML writes
// stay inert until we tell HTMX about them.
function swapAndProcess(el, html) {
  el.innerHTML = html;
  if (window.htmx && typeof window.htmx.process === 'function') {
    window.htmx.process(el);
  }
}

async function showFnVersionsPopover(fnEntity, anchorEl) {
  if (!fnEntity?.id) return;
  const popover = ensureFnVersionsPopover();
  _fnVersionsAnchor = anchorEl;
  _fnVersionsFnId = fnEntity.id;
  _fnVersionsFnEntity = fnEntity;

  popover.innerHTML = '<div class="fn-versions-loading">Loading history…</div>';
  popover.classList.remove('hidden');
  if (typeof anchorBelowClamped === 'function') {
    anchorBelowClamped(popover, anchorEl, { fallbackW: 320, fallbackH: 180 });
  }

  const currentBranch = (typeof getCurrentBranchName === 'function')
    ? getCurrentBranchName() : 'main';
  const title = fnEntity.name || '(anonymous)';
  const url = '/partials/fn-versions?fn-id=' + encodeURIComponent(fnEntity.id)
    + '&current-branch=' + encodeURIComponent(currentBranch)
    + '&title=' + encodeURIComponent(title);

  try {
    const resp = await window.authFetch(url);
    if (resp.status === 401) {
      popover.innerHTML = '<div class="fn-versions-error">'
        + 'Sign in to view version history.</div>';
      return;
    }
    if (!resp.ok) {
      popover.innerHTML = '<div class="fn-versions-error">HTTP '
        + resp.status + '</div>';
      return;
    }
    const html = await resp.text();
    // Supersession check BEFORE the swap: opening another fn's history
    // while this fetch was in flight must NOT clobber the newer popover
    // content. (Previously the guard ran AFTER swapAndProcess, so a slow
    // response overwrote the fast one with wrong rows + dead buttons.)
    if (_fnVersionsFnId !== fnEntity.id) return;
    swapAndProcess(popover, html);
  } catch (err) {
    popover.innerHTML = '<div class="fn-versions-error">'
      + 'Failed: ' + (err?.message || 'network error') + '</div>';
    return;
  }

  bindFnVersionsActions(popover, fnEntity);
  if (typeof anchorBelowClamped === 'function') {
    anchorBelowClamped(popover, anchorEl, { fallbackW: 320, fallbackH: 180 });
  }
}

// Post-swap action wiring. Only switch + restore stay here — both
// need behavior HTMX doesn't replicate cleanly (page navigation,
// confirm() dialog). Row-expand-into-executions is HTMX-driven via
// the row's `hx-get` attributes; nothing to bind for it.
function bindFnVersionsActions(popover, fnEntity) {
  popover.querySelectorAll('[data-switch-to-branch]').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();   // don't trigger row-top hx-get
      const target = btn.getAttribute('data-switch-to-branch');
      if (target && typeof switchToBranch === 'function') switchToBranch(target);
    });
  });

  popover.querySelectorAll('.fn-versions-restore').forEach((btn) => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();   // don't trigger row-top hx-get
      await restoreFnVersion(fnEntity, btn.getAttribute('data-fn-version-id'));
    });
  });
}


async function restoreFnVersion(fnEntity, versionId) {
  if (!fnEntity || !versionId) return;
  // Fetch the version row from the JSON API to pull the historic
  // field values — the partial is render-only, the data is in
  // `/api/fns/:id/versions`.
  let target;
  try {
    const resp = await window.authFetch(
      API.api_fns_fn_id_versions(fnEntity.id));
    if (!resp.ok) {
      alert('Restore failed: HTTP ' + resp.status);
      return;
    }
    const body = await resp.json();
    target = (body.versions || []).find((v) => v.id === versionId);
  } catch (err) {
    alert('Restore failed: ' + (err?.message || 'network error'));
    return;
  }
  if (!target) {
    alert('Restore failed: version not found anymore (was it cleaned up?).');
    return;
  }
  const branchName = (typeof getCurrentBranchName === 'function')
    ? getCurrentBranchName() : 'main';
  const msg = 'Restore fn "' + (fnEntity.name || '(anonymous)')
    + '" on branch "' + branchName + '" to the state from '
    + (target['branch-name'] || '?') + ' @ ' + shortTimestamp(target['created-at']) + '?'
    + '\n\nThis writes a new version row with the historic fn-level fields'
    + ' (description, return-type, constraint, …) on the current'
    + ' branch. Bindings are NOT touched — see VERSIONING.md § Subtleties.';
  if (!confirm(msg)) return;
  const payload = {};
  for (const k of ['description', 'constraint',
                   'base-fn-id', 'element-fn-id', 'return-type-fn-id',
                   'anonymous-hash', 'expects-effects']) {
    if (target[k] !== undefined) payload[k] = target[k];
  }
  try {
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(payload)) {
      params.set(k, typeof v === 'string' ? v : JSON.stringify(v));
    }
    const resp = await window.authFetch(
      API.api_entities_type_id('fn', fnEntity.id),
      { method: 'PUT',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString() });
    if (!resp.ok) {
      const text = await resp.text();
      alert('Restore failed: HTTP ' + resp.status + ' — ' + text);
      return;
    }
    closeFnVersionsPopover();
    if (typeof applyGraphDataRefresh === 'function') applyGraphDataRefresh();
  } catch (err) {
    alert('Restore failed: ' + (err?.message || 'network error'));
  }
}


function shortTimestamp(ts) {
  if (!ts) return '';
  const m = ts.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/);
  return m ? (m[1] + ' ' + m[2]) : ts;
}

window.showFnVersionsPopover = showFnVersionsPopover;
