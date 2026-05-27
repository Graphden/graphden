// Editor fn-version history — popover showing every :fn-version row
// for one fn, joined with the branch name it was authored on. Anchored
// below the ⌛ History action in the fn-card row-actions popover (see
// editor-overlay-fn.js). Read-only timeline: each row shows when the
// version landed + which branch it lives on, with a "Switch" link
// that jumps the editor to that branch via the existing
// `switchToBranch` plumbing.
//
// Backed by GET /api/fns/:fn-id/versions (auth-required) — see
// graphden.crud.branches/list-fn-versions.

let _fnVersionsPopover = null;
let _fnVersionsAnchor = null;
let _fnVersionsFnId = null;

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
  }
}

async function showFnVersionsPopover(fnEntity, anchorEl) {
  if (!fnEntity || !fnEntity.id) return;
  const popover = ensureFnVersionsPopover();
  _fnVersionsAnchor = anchorEl;
  _fnVersionsFnId = fnEntity.id;

  popover.innerHTML = '<div class="fn-versions-loading">Loading history…</div>';
  popover.classList.remove('hidden');
  if (typeof anchorBelowClamped === 'function') {
    anchorBelowClamped(popover, anchorEl, { fallbackW: 320, fallbackH: 180 });
  }

  let body;
  try {
    const resp = await window.authFetch(
      '/api/fns/' + encodeURIComponent(fnEntity.id) + '/versions');
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
    body = await resp.json();
  } catch (err) {
    popover.innerHTML = '<div class="fn-versions-error">'
      + 'Failed: ' + (err?.message || 'network error') + '</div>';
    return;
  }

  if (_fnVersionsFnId !== fnEntity.id) {
    // User opened another fn's history while this fetch was in flight.
    return;
  }

  renderFnVersionsBody(popover, fnEntity, body);
  if (typeof anchorBelowClamped === 'function') {
    anchorBelowClamped(popover, anchorEl, { fallbackW: 320, fallbackH: 180 });
  }
}

function renderFnVersionsBody(popover, fnEntity, body) {
  const versions = body?.versions || [];
  const currentBranch = (typeof getCurrentBranchName === 'function')
    ? getCurrentBranchName() : 'main';
  const title = fnEntity.name || '(anonymous)';
  if (versions.length === 0) {
    popover.innerHTML = '<div class="fn-versions-header">'
      + escapeText(title) + '</div>'
      + '<div class="fn-versions-empty">No version history rows yet.</div>';
    return;
  }
  const rows = versions.map((v) => fnVersionRowHtml(v, currentBranch)).join('');
  popover.innerHTML =
    '<div class="fn-versions-header">'
    + escapeText(title)
    + ' · ' + versions.length + ' version' + (versions.length === 1 ? '' : 's')
    + '</div>'
    + '<div class="fn-versions-list" role="list">' + rows + '</div>';
  popover.querySelectorAll('[data-switch-to-branch]').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const target = btn.getAttribute('data-switch-to-branch');
      if (target && typeof switchToBranch === 'function') switchToBranch(target);
    });
  });

  // Restore — write a new version on the CURRENT branch with the
  // historic version's data. Bindings aren't touched (see S1 caveat).
  popover.querySelectorAll('.fn-versions-restore').forEach((btn) => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      await restoreFnVersion(fnEntity, btn.getAttribute('data-fn-version-id'));
    });
  });

  // Click on a row body → expand inline executions for that version.
  // Lazy-fetch so users who only want to glance at the timeline pay
  // no per-version cost.
  popover.querySelectorAll('.fn-versions-row[data-fn-version-id]').forEach((row) => {
    row.addEventListener('click', (e) => {
      if (e.target.closest('.fn-versions-switch')
          || e.target.closest('.fn-versions-execs')) return;
      toggleVersionExecutions(row, row.getAttribute('data-fn-version-id'));
    });
  });
}


async function restoreFnVersion(fnEntity, versionId) {
  if (!fnEntity || !versionId) return;
  // The version data is in the loaded list — find it. Avoids a
  // round-trip to fetch what we already have.
  const list = document.getElementById('fn-versions-popover');
  // Cached body data is on the popover element via WeakMap-like
  // closure — we keep it accessible by re-fetching here on demand.
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
  // Build the PATCH payload — only fn-level versioned fields.
  const payload = {};
  for (const k of ['description', 'impl-hash', 'constraint',
                   'base-fn-id', 'element-fn-id', 'return-type-fn-id',
                   'anonymous-hash', 'expects-effects']) {
    if (target[k] !== undefined) payload[k] = target[k];
  }
  try {
    // The entity-update handler accepts form-encoded body; use that.
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
      alert('Restore failed: HTTP ' + resp.status
            + '\n' + text.replace(/<[^>]+>/g, '').trim().slice(0, 200));
      return;
    }
    // Success — reload so the editor re-fetches the fn and shows the
    // restored state. The fn-versions popover will rebuild on next open
    // and pick up the new version row.
    location.reload();
  } catch (err) {
    alert('Restore failed: ' + (err?.message || 'network error'));
  }
  // Silence unused warning — host of the list cache is intentional.
  void list;
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

function fnVersionRowHtml(v, currentBranch) {
  const branchName = v['branch-name'] || '(unknown)';
  const ts = v['created-at'] || '';
  const onCurrent = branchName === currentBranch;
  const changed = describeVersionContent(v);
  const execCount = v['execution-count'] || 0;
  const execBadge = execCount > 0
    ? '<span class="fn-versions-runs" title="Click row to expand runs">'
      + execCount + ' run' + (execCount === 1 ? '' : 's') + '</span>'
    : '';
  // Restore button — write a new version on CURRENT branch with this
  // historic row's data, effectively reverting the fn-level fields
  // (description, impl-hash, return-type, constraint, …). Doesn't
  // touch bindings — those are versioned separately; documented in
  // VERSIONING.md § Subtleties. Hidden on the row that already IS
  // current-branch latest (`onCurrent` covers the branch but the
  // version-row may be older than current latest; the API rejects
  // a no-op restore so we leave the button regardless for "rewind
  // within the same branch" use cases).
  const restoreBtn = '<button class="fn-versions-restore"'
    + ' data-fn-version-id="' + escapeAttr(v.id || '') + '"'
    + ' title="Restore this version’s fn-level fields on the current branch">'
    + 'restore</button>';
  return ''
    + '<div class="fn-versions-row" role="listitem"'
    + ' data-fn-version-id="' + escapeAttr(v.id || '') + '">'
    + '<div class="fn-versions-row-top">'
    +   '<span class="fn-versions-branch'
    +     (onCurrent ? ' fn-versions-branch-current' : '') + '">'
    +     escapeText(branchName)
    +   '</span>'
    +   '<span class="fn-versions-ts">' + escapeText(shortTimestamp(ts)) + '</span>'
    +   execBadge
    +   restoreBtn
    +   (onCurrent
        ? ''
        : '<button class="fn-versions-switch"'
          + ' data-switch-to-branch="' + escapeAttr(branchName) + '"'
          + ' title="Switch the editor to ' + escapeAttr(branchName) + '">'
          + 'switch</button>')
    + '</div>'
    + (changed
        ? '<div class="fn-versions-row-meta">' + escapeText(changed) + '</div>'
        : '')
    + '</div>';
}

function shortTimestamp(ts) {
  // The API returns SQL-shaped timestamps like
  // "2026-05-24 06:21:21.103753" or ISO "2026-05-24T06:35Z" — keep
  // YYYY-MM-DD HH:MM for compact display.
  if (!ts) return '';
  const m = ts.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/);
  return m ? (m[1] + ' ' + m[2]) : ts;
}

function describeVersionContent(v) {
  // Compact summary of the version's loaded fields — what changed
  // structurally. The API returns the full version-data-fields slice;
  // we surface the most user-visible ones (description, impl-hash,
  // return-type, anonymous-hash) so the row says "this was the rename
  // from x→y" or "this is the impl-hash bump".
  const parts = [];
  if (v.description) parts.push('desc=' + truncate(v.description, 40));
  if (v['impl-hash']) parts.push('impl-hash');
  if (v.constraint) parts.push('constraint');
  if (v['return-type-fn-id']) parts.push('return-type');
  if (v['anonymous-hash']) parts.push('anonymous');
  if (v['deleted-at']) parts.push('DELETED on this branch');
  return parts.join(', ');
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
