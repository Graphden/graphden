// Editor fn-version history — popover showing every :fn-version row
// for one fn, joined with the branch name it was authored on. Anchored
// below the ⌛ History action in the fn-card row-actions popover (see
// editor-overlay-fn.js).
//
// The popover CONTENT lives in the graph: `app/editor/fns.edn`
// renders the hiccup fragment, `GET /partials/fn-versions?fn-id=...`
// serves it as `text/html`. This module owns mount-point lifecycle,
// fetch, anchoring, dismissal, and the post-swap action wiring
// (switch / restore / row expansion). Per-row click handlers find
// their targets by the `data-fn-version-id` / `data-switch-to-branch`
// markers the server fragment carries.
//
// The lazy executions sub-panel (toggled on row click) still goes
// through `/api/executions` + JS rendering — keeping it in JS for
// this commit, separate POC migration when the next list is moved.

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
    popover.innerHTML = await resp.text();
  } catch (err) {
    popover.innerHTML = '<div class="fn-versions-error">'
      + 'Failed: ' + (err?.message || 'network error') + '</div>';
    return;
  }

  if (_fnVersionsFnId !== fnEntity.id) {
    // User opened another fn's history while this fetch was in flight.
    return;
  }

  bindFnVersionsActions(popover, fnEntity);
  if (typeof anchorBelowClamped === 'function') {
    anchorBelowClamped(popover, anchorEl, { fallbackW: 320, fallbackH: 180 });
  }
}

// Post-swap action wiring. The server fragment marks each switch /
// restore / row-expand target with a data-attribute; we find them
// by selector and bind handlers. Same contract the old JS-built
// markup had, so the e2e tests that select on the same classes
// keep passing.
function bindFnVersionsActions(popover, fnEntity) {
  popover.querySelectorAll('[data-switch-to-branch]').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const target = btn.getAttribute('data-switch-to-branch');
      if (target && typeof switchToBranch === 'function') switchToBranch(target);
    });
  });

  popover.querySelectorAll('.fn-versions-restore').forEach((btn) => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      await restoreFnVersion(fnEntity, btn.getAttribute('data-fn-version-id'));
    });
  });

  popover.querySelectorAll('.fn-versions-row[data-fn-version-id]').forEach((row) => {
    row.addEventListener('click', (e) => {
      if (e.target.closest('.fn-versions-switch')
          || e.target.closest('.fn-versions-restore')
          || e.target.closest('.fn-versions-execs')) return;
      toggleVersionExecutions(row, row.getAttribute('data-fn-version-id'));
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
      '/api/fns/' + encodeURIComponent(fnEntity.id) + '/versions');
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
    + ' (description, impl-hash, return-type, constraint, …) on the current'
    + ' branch. Bindings are NOT touched — see VERSIONING.md § Subtleties.';
  if (!confirm(msg)) return;
  const payload = {};
  for (const k of ['description', 'impl-hash', 'constraint',
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
      '/api/entities/fn/' + encodeURIComponent(fnEntity.id),
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


async function toggleVersionExecutions(rowEl, versionId) {
  const existing = rowEl.querySelector('.fn-versions-execs');
  if (existing) {
    existing.remove();
    rowEl.classList.remove('fn-versions-row-expanded');
    return;
  }
  const host = document.createElement('div');
  host.className = 'fn-versions-execs';
  host.innerHTML = '<div class="fn-versions-execs-loading">Loading runs…</div>';
  rowEl.appendChild(host);
  rowEl.classList.add('fn-versions-row-expanded');
  try {
    const resp = await window.authFetch(
      '/api/executions?fn-version-id=' + encodeURIComponent(versionId));
    if (resp.status === 401) {
      host.innerHTML = '<div class="fn-versions-execs-error">'
        + 'Sign in to view runs.</div>';
      return;
    }
    if (!resp.ok) {
      host.innerHTML = '<div class="fn-versions-execs-error">HTTP '
        + resp.status + '</div>';
      return;
    }
    const body = await resp.json();
    if (body?.ok === false) {
      host.innerHTML = '<div class="fn-versions-execs-error">'
        + escapeText(body.error || 'Failed') + '</div>';
      return;
    }
    renderVersionExecutions(host, body.executions || []);
  } catch (err) {
    host.innerHTML = '<div class="fn-versions-execs-error">'
      + escapeText(err?.message || 'network error') + '</div>';
  }
  if (typeof anchorBelowClamped === 'function' && _fnVersionsAnchor) {
    // Content grew — re-anchor so the popover stays on-screen.
    anchorBelowClamped(_fnVersionsPopover, _fnVersionsAnchor,
                       { fallbackW: 320, fallbackH: 240 });
  }
}


function renderVersionExecutions(host, executions) {
  if (executions.length === 0) {
    host.innerHTML = '<div class="fn-versions-execs-empty">'
      + 'No runs of this version.</div>';
    return;
  }
  const rows = executions.map(execRowHtml).join('');
  host.innerHTML = '<div class="fn-versions-execs-list">' + rows + '</div>';
}


function execRowHtml(e) {
  const status = e.status || '?';
  const started = shortTimestamp(e['started-at'] || '');
  const result = e.result !== undefined && e.result !== null
                 ? truncate(String(typeof e.result === 'string'
                                   ? e.result : JSON.stringify(e.result)), 32)
                 : (e.error ? truncate(String(e.error), 32) : '');
  return ''
    + '<div class="fn-versions-execs-row">'
    +   '<span class="fn-versions-execs-status fn-versions-execs-status-'
    +     escapeAttr(status) + '">'
    +     escapeText(status)
    +   '</span>'
    +   '<span class="fn-versions-execs-ts">' + escapeText(started) + '</span>'
    +   (result
        ? '<span class="fn-versions-execs-result">' + escapeText(result) + '</span>'
        : '')
    + '</div>';
}

function shortTimestamp(ts) {
  if (!ts) return '';
  const m = ts.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/);
  return m ? (m[1] + ' ' + m[2]) : ts;
}

function truncate(s, n) {
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

function escapeText(s) {
  const d = document.createElement('div');
  d.textContent = s || '';
  return d.innerHTML;
}

function escapeAttr(s) {
  return (s || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;')
    .replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

window.showFnVersionsPopover = showFnVersionsPopover;
