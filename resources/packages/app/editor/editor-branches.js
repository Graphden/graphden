// Editor Branches — current-branch state, fetch interception, top-bar
// selector + branch-CRUD popover.
//
// State sources (read precedence):
//   1. URL ?branch=<name>          — shareable / explicit override
//   2. localStorage                — persists across reloads
//   3. 'main'                      — default
//
// Switching branches mutates BOTH localStorage AND the URL, then
// reloads the page. Reload is the simplest "invalidate everything"
// strategy — the editor caches graph data, layout positions, lookup
// maps, etc.; rebuilding them in-place would require touching every
// state owner. The backend's per-branch ctx cache means the new
// branch's first request pays a one-time compile, not every request.
//
// Every fetch to /api/* gets `X-Graphden-Branch: <name>` (when not
// main). We monkey-patch `window.fetch` at load time so direct
// fetch calls (editor-main, editor-layout, editor-value-form, …)
// pick up branch context without each call site being touched. The
// matching authFetch in editor-auth.js stacks Authorization on top
// of this wrapped fetch.

const BRANCH_STORAGE_KEY = 'graphden.branch';
const BRANCH_HEADER = 'X-Graphden-Branch';
const DEFAULT_BRANCH = 'main';

function readUrlBranch() {
  try { return new URLSearchParams(location.search).get('branch'); }
  catch (_) { return null; }
}

function readStoredBranch() {
  try { return localStorage.getItem(BRANCH_STORAGE_KEY); }
  catch (_) { return null; }
}

function getCurrentBranchName() {
  return readUrlBranch() || readStoredBranch() || DEFAULT_BRANCH;
}

function isOnDefaultBranch() {
  return getCurrentBranchName() === DEFAULT_BRANCH;
}

// Switch to a different branch — persist + sync URL + reload.
function switchToBranch(name) {
  const target = name || DEFAULT_BRANCH;
  try {
    if (target === DEFAULT_BRANCH) localStorage.removeItem(BRANCH_STORAGE_KEY);
    else localStorage.setItem(BRANCH_STORAGE_KEY, target);
  } catch (_) {}
  const url = new URL(location.href);
  if (target === DEFAULT_BRANCH) url.searchParams.delete('branch');
  else url.searchParams.set('branch', target);
  // Reload picks up branch context for every cached read (graph,
  // layout, types). hash component preserved.
  location.href = url.toString();
}

// Wrap `window.fetch` to add the branch header on every /api/* call.
// Backend routes default to main when header is absent — emitting the
// header only when on a non-default branch keeps wire shape stable
// for anyone running the legacy single-branch backend.
(function wrapFetchWithBranch() {
  const origFetch = window.fetch.bind(window);
  window.fetch = function branchAwareFetch(input, init) {
    const branch = getCurrentBranchName();
    if (branch === DEFAULT_BRANCH) return origFetch(input, init);
    // Only add to same-origin /api/* requests — third-party fetches
    // (CDN scripts, etc.) shouldn't see our internal header.
    const url = typeof input === 'string' ? input : (input?.url || '');
    if (!url.startsWith('/api/')) return origFetch(input, init);
    const opts = Object.assign({}, init || {});
    const headers = new Headers(opts.headers || {});
    if (!headers.has(BRANCH_HEADER)) headers.set(BRANCH_HEADER, branch);
    opts.headers = headers;
    return origFetch(input, opts);
  };
})();

// ============================================================================
// UI — top-bar branch chip + branch CRUD popover
// ============================================================================

const BRANCH_ICON_SVG =
  '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
  + '<line x1="6" y1="3" x2="6" y2="15"/>'
  + '<circle cx="18" cy="6" r="3"/>'
  + '<circle cx="6" cy="18" r="3"/>'
  + '<path d="M18 9a9 9 0 0 1-9 9"/>'
  + '</svg>';

function initBranchSelector() {
  const mount = document.getElementById('branch-mount');
  if (!mount) return;
  mount.innerHTML =
    '<button id="branch-chip-btn" class="branch-chip-btn" title="Switch branch">'
    + BRANCH_ICON_SVG
    + '<span id="branch-chip-name"></span>'
    + '</button>'
    + '<div id="branch-popover" class="branch-popover hidden" role="dialog"'
    + ' aria-label="Switch branch"></div>';

  renderBranchChip();
  document.getElementById('branch-chip-btn').addEventListener('click', toggleBranchPopover);
  document.addEventListener('click', (e) => {
    const popover = document.getElementById('branch-popover');
    const btn = document.getElementById('branch-chip-btn');
    if (!popover || popover.classList.contains('hidden')) return;
    if (popover.contains(e.target) || btn.contains(e.target)) return;
    closeBranchPopover();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeBranchPopover();
  });
}

function renderBranchChip() {
  const name = getCurrentBranchName();
  const label = document.getElementById('branch-chip-name');
  const btn = document.getElementById('branch-chip-btn');
  if (!label || !btn) return;
  label.textContent = name;
  btn.classList.toggle('branch-chip-non-default', name !== DEFAULT_BRANCH);
  btn.title = name === DEFAULT_BRANCH
    ? 'On main — click to switch branch'
    : 'On "' + name + '" — click to switch';
}

function toggleBranchPopover() {
  const popover = document.getElementById('branch-popover');
  if (!popover) return;
  if (popover.classList.contains('hidden')) openBranchPopover();
  else closeBranchPopover();
}

function closeBranchPopover() {
  const popover = document.getElementById('branch-popover');
  if (popover) popover.classList.add('hidden');
}

function positionBranchPopover() {
  const popover = document.getElementById('branch-popover');
  const btn = document.getElementById('branch-chip-btn');
  if (!popover || !btn) return;
  // Reparent to <body> on first open: the popover's mount point
  // lives inside #side-menu, whose `transform: translateX(0)` (used
  // for the collapse slide-out animation) makes #side-menu the
  // containing block for `position: fixed` descendants — clipping
  // the popover to the sidebar's bounds and trapping it underneath
  // the sidebar's opaque background. Same fix as editor-auth.js.
  if (popover.parentElement !== document.body) {
    document.body.appendChild(popover);
  }
  if (typeof anchorBelowClamped === 'function') {
    anchorBelowClamped(popover, btn);
  }
}

async function openBranchPopover() {
  const popover = document.getElementById('branch-popover');
  if (!popover) return;
  popover.classList.remove('hidden');
  // Loading state first; replaced once /api/branches responds.
  popover.innerHTML = '<div class="branch-popover-loading">Loading branches…</div>';
  positionBranchPopover();
  try {
    const branches = await fetchBranches();
    renderBranchPopover(popover, branches);
  } catch (err) {
    popover.innerHTML = '<div class="branch-popover-error">'
      + 'Failed to load branches: ' + (err?.message || 'unknown error')
      + '</div>';
  }
  // Re-anchor — content size changed after the loading → list swap.
  positionBranchPopover();
}

async function fetchBranches() {
  // authFetch — /api/branches is auth-required. When not signed in we
  // still want to render at least the current branch + a sign-in hint.
  const resp = await window.authFetch('/api/branches');
  if (resp.status === 401) {
    const err = new Error('Sign in to manage branches');
    err.code = 'unauth';
    throw err;
  }
  if (!resp.ok) throw new Error('HTTP ' + resp.status);
  const body = await resp.json();
  if (!body || body.ok === false) throw new Error(body?.error || 'Bad response');
  return body.branches || [];
}

function renderBranchPopover(popover, branches) {
  const current = getCurrentBranchName();
  const byName = Object.fromEntries(branches.map((b) => [b.name, b]));
  const sorted = branches.slice().sort((a, b) => {
    // main first, current second, then alphabetic.
    if (a.name === DEFAULT_BRANCH) return -1;
    if (b.name === DEFAULT_BRANCH) return 1;
    if (a.name === current) return -1;
    if (b.name === current) return 1;
    return a.name.localeCompare(b.name);
  });

  const rows = sorted.map((b) => branchRowHtml(b, current, byName)).join('');

  popover.innerHTML =
    '<div class="branch-popover-list" role="listbox" aria-label="Branches">'
    + rows
    + '</div>'
    + '<div class="branch-popover-create">'
    +   '<input id="branch-create-input" type="text" placeholder="New branch name (forks from ' + current + ')" autocomplete="off">'
    +   '<button id="branch-create-btn" class="branch-popover-btn">Create</button>'
    + '</div>'
    + '<div id="branch-popover-error" class="branch-popover-error hidden"></div>';

  // Row clicks switch branch. Action buttons inside the row stop
  // propagation themselves so they don't double-fire as a switch.
  // Clicking the CURRENT row is a no-op for switching but still
  // dismisses the popover — otherwise the user is stuck with no
  // visible affordance to close it (chip is now obscured behind the
  // popover itself, and "click outside" isn't obvious).
  popover.querySelectorAll('.branch-row[data-branch-name]').forEach((row) => {
    row.addEventListener('click', (e) => {
      if (e.target.closest('.branch-row-actions')) return;
      const name = row.getAttribute('data-branch-name');
      if (name !== current) {
        switchToBranch(name); // navigates → popover closes via page reload
      } else {
        closeBranchPopover();
      }
    });
  });

  // Delete buttons.
  popover.querySelectorAll('.branch-row-delete').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const name = btn.getAttribute('data-branch-name');
      deleteBranchWithConfirm(name);
    });
  });

  // Merge buttons.
  popover.querySelectorAll('.branch-row-merge').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const source = btn.getAttribute('data-merge-source');
      mergeBranchInto(source, current);
    });
  });

  // Diff buttons.
  popover.querySelectorAll('.branch-row-diff').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const source = btn.getAttribute('data-diff-source');
      if (typeof showBranchDiff === 'function') {
        showBranchDiff(current, source);
      }
    });
  });

  const createInput = document.getElementById('branch-create-input');
  const createBtn = document.getElementById('branch-create-btn');
  createBtn.addEventListener('click', () => createBranchFromInput(current));
  createInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') createBranchFromInput(current);
  });
}

function branchRowHtml(branch, current, byName) {
  const isCurrent = branch.name === current;
  const isMain = branch.name === DEFAULT_BRANCH;
  const hasChildren = Object.values(byName).some(
    (b) => b['base-branch-id'] === branch.id);
  const parent = branch['base-branch-id']
    ? Object.values(byName).find((b) => b.id === branch['base-branch-id'])
    : null;
  const parentText = parent ? ' forked from ' + parent.name : '';

  // Diff button — visible for non-current rows. Shows the
  // resolved-view diff between THAT branch and the CURRENT branch.
  // Sits before merge so the natural left-to-right reading is
  // "see what's different, then merge if you want it".
  const diffBtn = isCurrent ? ''
    : '<button class="branch-row-diff" data-diff-source="'
      + escapeAttr(branch.name) + '"'
      + ' title="Show what differs between ' + escapeAttr(branch.name)
      + ' and ' + escapeAttr(current) + '"'
      + ' aria-label="Diff ' + escapeAttr(branch.name) + ' vs ' + escapeAttr(current)
      + '">Δ</button>';

  // Merge button — visible for non-current rows. Merges THAT branch
  // INTO the CURRENT branch (you're viewing main → click a feature
  // row's merge button → feature gets pulled into main).
  const mergeBtn = isCurrent ? ''
    : '<button class="branch-row-merge" data-merge-source="'
      + escapeAttr(branch.name) + '"'
      + ' title="Merge ' + escapeAttr(branch.name) + ' INTO ' + escapeAttr(current) + '"'
      + ' aria-label="Merge ' + escapeAttr(branch.name) + ' into ' + escapeAttr(current)
      + '">⇢</button>';

  // Delete button — main and branches with children are protected.
  const deleteBtn = isMain || hasChildren || isCurrent ? ''
    : '<button class="branch-row-delete" data-branch-name="'
      + escapeAttr(branch.name)
      + '" title="Delete branch" aria-label="Delete branch ' + escapeAttr(branch.name)
      + '">×</button>';

  return ''
    + '<div class="branch-row' + (isCurrent ? ' branch-row-current' : '') + '"'
    + ' data-branch-name="' + escapeAttr(branch.name) + '"'
    + ' role="option" aria-selected="' + (isCurrent ? 'true' : 'false') + '"'
    + ' tabindex="0">'
    + '<span class="branch-row-name">' + escapeText(branch.name) + '</span>'
    + (isCurrent ? '<span class="branch-row-tag">current</span>' : '')
    + '<span class="branch-row-meta">' + escapeText(parentText) + '</span>'
    + '<span class="branch-row-actions">'
    +   diffBtn
    +   mergeBtn
    +   deleteBtn
    + '</span>'
    + '</div>';
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

async function createBranchFromInput(parentName) {
  const input = document.getElementById('branch-create-input');
  const err = document.getElementById('branch-popover-error');
  const name = input.value.trim();
  if (!name) {
    err.textContent = 'Branch name is required';
    err.classList.remove('hidden');
    return;
  }
  err.classList.add('hidden');
  try {
    const resp = await window.authFetch('/api/branches', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, 'base-branch-id': parentName }),
    });
    const body = await resp.json();
    if (resp.status === 401) {
      err.textContent = 'Sign in to create branches';
      err.classList.remove('hidden');
      return;
    }
    if (!resp.ok || body.ok === false) {
      err.textContent = body?.error || ('HTTP ' + resp.status);
      err.classList.remove('hidden');
      return;
    }
    // Switch immediately — the user just created this; almost certainly
    // they want to start working on it.
    switchToBranch(name);
  } catch (e) {
    err.textContent = e?.message || 'Create failed';
    err.classList.remove('hidden');
  }
}

async function deleteBranchWithConfirm(name) {
  if (!confirm('Delete branch "' + name + '"? Every version row on it will be removed.')) return;
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(
      '/api/branches/' + encodeURIComponent(name),
      { method: 'DELETE' });
    const body = await resp.json();
    if (resp.status === 401) {
      err.textContent = 'Sign in to delete branches';
      err.classList.remove('hidden');
      return;
    }
    if (!resp.ok || body.ok === false) {
      const detail = body?.['child-branch-ids']
        ? ' (' + body['child-branch-ids'].length + ' child branch(es) block deletion)'
        : '';
      err.textContent = (body?.error || ('HTTP ' + resp.status)) + detail;
      err.classList.remove('hidden');
      return;
    }
    // If we just deleted the current branch, fall back to main.
    if (name === getCurrentBranchName()) {
      switchToBranch(DEFAULT_BRANCH);
      return;
    }
    // Otherwise just re-render the popover with the updated list.
    openBranchPopover();
  } catch (e) {
    err.textContent = e?.message || 'Delete failed';
    err.classList.remove('hidden');
  }
}

// ============================================================================
// MERGE — with conflict-resolution modal
// ============================================================================

async function mergeBranchInto(sourceName, targetName, conflictResolutions) {
  if (!sourceName || !targetName) return;
  if (!conflictResolutions
      && !confirm('Merge "' + sourceName + '" INTO "' + targetName + '"?'
                  + (targetName === DEFAULT_BRANCH
                     ? ' This affects main — every viewer will see these changes.'
                     : ''))) {
    return;
  }
  const errBox = document.getElementById('branch-popover-error');
  const setError = (msg) => {
    if (errBox) { errBox.textContent = msg; errBox.classList.remove('hidden'); }
  };
  try {
    const resp = await window.authFetch(
      '/api/branches/' + encodeURIComponent(targetName) + '/merge',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: sourceName,
          'conflict-resolutions': conflictResolutions || undefined,
        }),
      });
    const body = await resp.json();
    if (resp.status === 401) { setError('Sign in to merge'); return; }
    if (body?.ok === false && body?.reason === 'merge-conflict') {
      // Drop the popover so the modal has full attention.
      closeBranchPopover();
      showMergeConflictsModal(body, sourceName, targetName);
      return;
    }
    if (!resp.ok || body?.ok === false) {
      setError(body?.error || ('HTTP ' + resp.status));
      return;
    }
    // Success — drop everything and reload so caches refresh and the
    // editor picks up the new resolved view on the current branch.
    closeBranchPopover();
    location.reload();
  } catch (err) {
    setError(err?.message || 'Merge failed');
  }
}

let _conflictsModal = null;

function ensureConflictsModal() {
  if (_conflictsModal) return _conflictsModal;
  const el = document.createElement('div');
  el.id = 'merge-conflicts-modal';
  el.className = 'merge-conflicts-modal hidden';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-label', 'Resolve merge conflicts');
  document.body.appendChild(el);
  _conflictsModal = el;
  return el;
}

function closeConflictsModal() {
  if (_conflictsModal) _conflictsModal.classList.add('hidden');
}

function showMergeConflictsModal(body, sourceName, targetName) {
  const modal = ensureConflictsModal();
  modal.classList.remove('hidden');
  const conflicts = body.conflicts || [];
  const rows = conflicts.map((c, i) => conflictRowHtml(c, i)).join('');
  modal.innerHTML =
    '<div class="merge-conflicts-overlay"></div>'
    + '<div class="merge-conflicts-card">'
    +   '<div class="merge-conflicts-header">'
    +     'Resolve ' + conflicts.length + ' conflict' + (conflicts.length === 1 ? '' : 's')
    +     ' merging <strong>' + escapeText(sourceName)
    +     '</strong> → <strong>' + escapeText(targetName) + '</strong>'
    +   '</div>'
    +   '<div class="merge-conflicts-help">'
    +     'For each entity, pick which side wins. '
    +     '<em>source</em> = ' + escapeText(sourceName)
    +     ', <em>target</em> = ' + escapeText(targetName) + '.'
    +   '</div>'
    +   '<div class="merge-conflicts-rows">' + rows + '</div>'
    +   '<div class="merge-conflicts-error hidden" id="merge-conflicts-error"></div>'
    +   '<div class="merge-conflicts-actions">'
    +     '<button id="merge-conflicts-cancel" class="branch-popover-btn merge-conflicts-cancel">Cancel</button>'
    +     '<button id="merge-conflicts-submit" class="branch-popover-btn">Apply merge</button>'
    +   '</div>'
    + '</div>';

  modal.querySelector('.merge-conflicts-overlay')
    .addEventListener('click', closeConflictsModal);
  modal.querySelector('#merge-conflicts-cancel')
    .addEventListener('click', closeConflictsModal);
  modal.querySelector('#merge-conflicts-submit')
    .addEventListener('click', () => submitConflictResolutions(conflicts, sourceName, targetName));
}

function conflictRowHtml(c, i) {
  const labelId = 'mc-' + i;
  const sourcePreview = previewVersion(c['source-version']);
  const targetPreview = previewVersion(c['target-version']);
  return ''
    + '<div class="merge-conflict-row" data-conflict-idx="' + i + '">'
    +   '<div class="merge-conflict-row-head">'
    +     '<span class="merge-conflict-entity">' + escapeText(c['entity-name']) + '</span>'
    +     '<span class="merge-conflict-id">' + escapeText(c['entity-id']) + '</span>'
    +   '</div>'
    +   '<div class="merge-conflict-choice">'
    +     '<label><input type="radio" name="' + labelId + '" value="source" checked>'
    +     ' <strong>source</strong> <code>' + escapeText(sourcePreview) + '</code></label>'
    +     '<label><input type="radio" name="' + labelId + '" value="target">'
    +     ' <strong>target</strong> <code>' + escapeText(targetPreview) + '</code></label>'
    +   '</div>'
    + '</div>';
}

function previewVersion(v) {
  if (!v) return '(deleted)';
  // Pick the most user-facing fields. Mirrors the version-data-fields
  // set in graphden.versioning.storage.resolution/entity-config.
  const parts = [];
  if (v.name) parts.push(v.name);
  if (v.description) parts.push('"' + truncate(v.description, 32) + '"');
  if (v.position !== undefined) parts.push('pos=' + v.position);
  if (v.value !== undefined && v.value !== null) {
    const s = typeof v.value === 'string' ? v.value : JSON.stringify(v.value);
    parts.push('value=' + truncate(s, 32));
  }
  return parts.join(' ') || '(empty)';
}

function truncate(s, n) {
  s = String(s);
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

async function submitConflictResolutions(conflicts, sourceName, targetName) {
  const resolutions = conflicts.map((c, i) => {
    const chosen = document.querySelector('input[name="mc-' + i + '"]:checked');
    return {
      'entity-name': c['entity-name'],
      'entity-id': c['entity-id'],
      choice: chosen?.value || 'source',
    };
  });
  const errBox = document.getElementById('merge-conflicts-error');
  try {
    const resp = await window.authFetch(
      '/api/branches/' + encodeURIComponent(targetName) + '/merge',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: sourceName,
          'conflict-resolutions': resolutions,
        }),
      });
    const body = await resp.json();
    if (!resp.ok || body?.ok === false) {
      if (errBox) {
        errBox.textContent = body?.error || ('HTTP ' + resp.status);
        errBox.classList.remove('hidden');
      }
      return;
    }
    closeConflictsModal();
    location.reload();
  } catch (err) {
    if (errBox) {
      errBox.textContent = err?.message || 'Merge failed';
      errBox.classList.remove('hidden');
    }
  }
}

// Public API for sibling modules.
window.getCurrentBranchName = getCurrentBranchName;
window.isOnDefaultBranch = isOnDefaultBranch;
window.switchToBranch = switchToBranch;
window.initBranchSelector = initBranchSelector;
