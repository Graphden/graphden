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
// Personal branches carry a `~` prefix (see :_bp-is-personal?-cb in
// branches/fns.edn) — the durable "Mine" marker, since :branch has no
// owner column. The create form's mode toggle decides whether a new
// branch gets the prefix; default is a shared/team branch.
const PERSONAL_PREFIX = '~';
let branchCreateMode = 'shared'; // 'shared' | 'personal' — reset per popover open

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

// Wrap `window.fetch` to add the branch header on every /api/* and
// /partials/* call. Backend routes default to main when header is
// absent — emitting the header only when on a non-default branch
// keeps wire shape stable for anyone running the legacy single-branch
// backend. /partials/* is in the same boat as /api/*: server-rendered
// fragments that read per-branch graph state.
// Capability signal. The tenancy addon stamps
// `X-Graphden-Capabilities` (a comma-list like "write,execute") on every
// /api response. We read it off each response and toggle body classes the
// editor CSS uses to hide affordances a tenant isn't granted. The header is
// ABSENT without the addon (single-tenant) — capabilities then stay unknown
// and everything is allowed, so the editor is unchanged.
const CAP_HEADER = 'X-Graphden-Capabilities';
const WORKSPACE_HEADER = 'X-Graphden-Workspace';
let graphdenCapabilities = null; // null = unknown → allow all
let graphdenWorkspace = null;    // null = unknown / no workspace hint
function captureCapabilities(resp) {
  try {
    const cap = resp?.headers?.get(CAP_HEADER);
    if (cap !== null && cap !== undefined) {
      graphdenCapabilities = new Set(cap.split(',').map((s) => s.trim()).filter(Boolean));
      document.body.classList.toggle('gd-no-write', !graphdenCapabilities.has('write'));
      document.body.classList.toggle('gd-no-execute', !graphdenCapabilities.has('execute'));
      // Tenancy addon is active once a capability header arrives — gates
      // addon-only affordances like the ⌂ set-as-app-handler button (§3.4 4b).
      document.body.classList.add('gd-tenancy');
    }
    // Workspace (§4.4): the namespaces the user works in, for sidebar
    // highlighting. Empty / absent → no hint → nothing highlighted.
    const ws = resp?.headers?.get(WORKSPACE_HEADER);
    if (ws !== null && ws !== undefined) {
      graphdenWorkspace = new Set(ws.split(',').map((s) => s.trim()).filter(Boolean));
    }
  } catch (_) { /* never break a fetch over a header read */ }
}
function graphdenCanWrite() { return !graphdenCapabilities || graphdenCapabilities.has('write'); }
function graphdenCanExecute() { return !graphdenCapabilities || graphdenCapabilities.has('execute'); }
// A namespace path is in the workspace when it equals, or is a descendant
// of, one of the workspace roots. No workspace → false (nothing highlighted).
function graphdenInWorkspace(nsPath) {
  if (!graphdenWorkspace || graphdenWorkspace.size === 0 || !nsPath) return false;
  for (const w of graphdenWorkspace) {
    if (nsPath === w || nsPath.startsWith(w + '.')) return true;
  }
  return false;
}
// Redesign 2026-08 — WORKSPACE FOCUS (user-chosen scope). Distinct from the
// server highlight above: focus HIDES out-of-scope namespaces in the explorer.
// A workspace is just a set of namespace roots (reuses the same root+descendant
// test), persisted locally — no new entity. null = no focus (show everything).
let graphdenFocusRoots = null;
try {
  const raw = JSON.parse(localStorage.getItem('graphden.workspace.roots') || 'null');
  if (Array.isArray(raw) && raw.length) graphdenFocusRoots = raw.slice();
} catch (_) { /* ignore malformed pref */ }
function graphdenInFocus(nsPath) {
  if (!graphdenFocusRoots || !nsPath) return false;
  for (const w of graphdenFocusRoots) {
    if (nsPath === w || nsPath.startsWith(w + '.')) return true;
  }
  return false;
}
function graphdenWorkspaceFocused() { return !!graphdenFocusRoots; }
function graphdenWorkspaceLabel() {
  if (!graphdenFocusRoots) return 'All functions';
  return graphdenFocusRoots.length === 1
    ? graphdenFocusRoots[0]
    : graphdenFocusRoots.length + ' namespaces';
}
// Set (array of ns roots) or clear (null/empty) the workspace focus + persist.
function setGraphdenWorkspace(roots) {
  graphdenFocusRoots = (Array.isArray(roots) && roots.length) ? roots.slice() : null;
  try {
    localStorage.setItem('graphden.workspace.roots', JSON.stringify(graphdenFocusRoots || []));
  } catch (_) { /* ignore */ }
}
window.graphdenInFocus = graphdenInFocus;
window.graphdenWorkspaceFocused = graphdenWorkspaceFocused;
window.graphdenWorkspaceLabel = graphdenWorkspaceLabel;
window.setGraphdenWorkspace = setGraphdenWorkspace;
window.graphdenWorkspaceRoots = () => (graphdenFocusRoots ? graphdenFocusRoots.slice() : []);
// Pinned namespaces stay visible in the explorer even when a workspace focus
// would otherwise hide them — the "shared library" convention (core/web/… you
// always want in view). Persisted locally; same root+descendant test.
let graphdenPinnedRoots = [];
try {
  const p = JSON.parse(localStorage.getItem('graphden.workspace.pins') || 'null');
  if (Array.isArray(p)) graphdenPinnedRoots = p.slice();
} catch (_) { /* ignore malformed pref */ }
function graphdenIsPinned(nsPath) {
  if (!nsPath) return false;
  for (const w of graphdenPinnedRoots) {
    if (nsPath === w || nsPath.startsWith(w + '.')) return true;
  }
  return false;
}
function graphdenTogglePin(root) {
  const i = graphdenPinnedRoots.indexOf(root);
  if (i >= 0) graphdenPinnedRoots.splice(i, 1);
  else graphdenPinnedRoots.push(root);
  try {
    localStorage.setItem('graphden.workspace.pins', JSON.stringify(graphdenPinnedRoots));
  } catch (_) { /* ignore */ }
}
window.graphdenIsPinned = graphdenIsPinned;
window.graphdenTogglePin = graphdenTogglePin;
window.graphdenPins = () => graphdenPinnedRoots.slice();
// The tenancy addon is active iff we've seen a capability header (absent in
// single-tenant). Used to gate addon-only UI like the Grants admin section.
function graphdenTenancyActive() { return graphdenCapabilities !== null; }
// Org-RBAC (org-management) capability gate for the admin panels: true only when
// the tenancy addon is active AND the header carries `cap` (one of manage-users
// / manage-grants / manage-roles / manage-apps, or `org-owner`). Unknown caps
// (single-tenant) → false, so an addon-less editor shows no org-admin panel.
function graphdenHasCap(cap) {
  return graphdenCapabilities?.has(cap) ?? false;
}
// The current user owns their org (may transfer ownership) — the `org-owner`
// signal in the capabilities header.
function graphdenIsOrgOwner() { return graphdenHasCap('org-owner'); }
window.graphdenCanWrite = graphdenCanWrite;
window.graphdenCanExecute = graphdenCanExecute;
window.graphdenInWorkspace = graphdenInWorkspace;
window.graphdenTenancyActive = graphdenTenancyActive;
window.graphdenHasCap = graphdenHasCap;
window.graphdenIsOrgOwner = graphdenIsOrgOwner;

(function wrapFetchWithBranch() {
  const origFetch = window.fetch.bind(window);
  window.fetch = function branchAwareFetch(input, init) {
    // Only touch same-origin /api/* and /partials/* — third-party fetches
    // (CDN scripts, etc.) shouldn't see our internal header or be sniffed.
    const url = typeof input === 'string' ? input : (input?.url || '');
    const isInternal = url.startsWith('/api/') || url.startsWith('/partials/'); // api-url-drift-allow: prefix discriminator, not a URL we fetch
    const branch = getCurrentBranchName();
    let promise;
    if (!isInternal) {
      promise = origFetch(input, init);
    } else {
      const opts = Object.assign({}, init || {});
      const headers = new Headers(opts.headers || {});
      if (branch !== DEFAULT_BRANCH && !headers.has(BRANCH_HEADER)) {
        headers.set(BRANCH_HEADER, branch);
      }
      // Attach the stored bearer to every internal call that doesn't carry
      // one already (authFetch sets its own → left untouched). The graph-data
      // reads are auth-required now (the anonymous view was removed), and the
      // boot path + sidebar lazy loads go through PLAIN fetch — without this,
      // a signed-in (or landing-demo) session still hit 401 on boot because
      // only authFetch/HTMX carried the token.
      const pw = (typeof getAuthPassword === 'function') ? getAuthPassword() : null;
      if (pw && !headers.has('Authorization')) {
        headers.set('Authorization', 'Bearer ' + pw);
      }
      opts.headers = headers;
      promise = origFetch(input, opts);
    }
    // Read the capability header off internal responses (headers only — the
    // body is untouched, so no clone needed).
    return isInternal
      ? promise.then((resp) => { captureCapabilities(resp); return resp; })
      : promise;
  };
})();

// HTMX does NOT go through `window.fetch` — 2.x issues XMLHttpRequests, so the
// wrapper above never sees them and `editor-auth.js` had to bridge the Authorization
// header separately. The branch header was never bridged with it, and every
// htmx-driven MUTATION therefore wrote to the default branch no matter which branch
// the user was standing on: install a package from the Packages panel while on
// `feature-x` and its fns, its namespaces and its pin all landed on `main` — the
// exact opposite of the branch-scoped pins the panel is built around. Same for
// uninstall / update / fork / publish, and for the partial GETs, which showed
// `main`'s pins while the branch chip said otherwise.
//
// Found by moving the packages e2e onto a throwaway branch: the copies kept turning
// up on the default branch after the branch was deleted.
//
// Same rules as the fetch wrapper — internal paths only, nothing to say on `main`.
document.body.addEventListener('htmx:configRequest', (evt) => {
  const path = evt.detail?.path || '';
  const isInternal = path.startsWith('/api/') || path.startsWith('/partials/'); // api-url-drift-allow: prefix discriminator, not a URL we fetch
  const branch = getCurrentBranchName();
  if (isInternal && branch !== DEFAULT_BRANCH) {
    evt.detail.headers[BRANCH_HEADER] = branch;
  }
});

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
  popover.innerHTML = '<div class="branch-popover-loading">Loading branches…</div>';
  positionBranchPopover();
  try {
    const resp = await window.authFetch('/partials/branch-popover');
    if (resp.status === 401) {
      throw Object.assign(new Error('Sign in to manage branches'), { code: 'unauth' });
    }
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    popover.innerHTML = await resp.text();
    wireBranchPopoverHandlers(popover, getCurrentBranchName());
  } catch (err) {
    popover.innerHTML = '<div class="branch-popover-error">'
      + 'Failed to load branches: ' + (err?.message || 'unknown error')
      + '</div>';
  }
  // Re-anchor — content size changed after the loading → list swap.
  positionBranchPopover();
}

// Bind row + action click handlers to the swapped partial body. The
// graph renders `data-branch-name` / `data-merge-source` /
// `data-diff-source` attrs on every interactive element; this fn
// translates those into the corresponding navigations / mutations.
function wireBranchPopoverHandlers(popover, current) {
  // Row clicks switch branch. Buttons inside `.branch-row-actions`
  // stop propagation so they don't double-fire as a switch. Clicking
  // the CURRENT row is a no-op for switching but still dismisses the
  // popover.
  popover.querySelectorAll('.branch-row[data-branch-name]').forEach((row) => {
    row.addEventListener('click', (e) => {
      if (e.target.closest('.branch-row-actions')) return;
      const name = row.getAttribute('data-branch-name');
      if (name !== current) switchToBranch(name);
      else closeBranchPopover();
    });
  });

  popover.querySelectorAll('.branch-row-delete').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteBranchWithConfirm(btn.getAttribute('data-branch-name'));
    });
  });

  popover.querySelectorAll('.branch-row-merge').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      mergeBranchInto(btn.getAttribute('data-merge-source'), current);
    });
  });

  popover.querySelectorAll('.branch-row-diff').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const source = btn.getAttribute('data-diff-source');
      if (typeof showBranchDiff === 'function') showBranchDiff(current, source);
    });
  });

  const createInput = document.getElementById('branch-create-input');
  const createBtn = document.getElementById('branch-create-btn');
  if (createBtn && createInput) {
    createBtn.addEventListener('click', () => createBranchFromInput(current));
    createInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') createBranchFromInput(current);
    });
  }

  // Create-mode toggle (Shared | Mine). Fresh partial → reset to the
  // default shared mode, then reflect the graph's initial `.active`.
  branchCreateMode = 'shared';
  const modeBtns = popover.querySelectorAll('.branch-create-mode-btn[data-create-mode]');
  modeBtns.forEach((btn) => {
    btn.addEventListener('click', () => {
      setBranchCreateMode(btn.getAttribute('data-create-mode'), current);
      if (createInput) createInput.focus();
    });
  });

  // "New personal branch" CTA in the Mine section header — a shortcut
  // to personal mode + the create input.
  const personalCta = popover.querySelector('[data-create-personal]');
  if (personalCta) {
    personalCta.addEventListener('click', () => {
      setBranchCreateMode('personal', current);
      if (createInput) createInput.focus();
    });
  }
}

// Switch the create form between shared/personal: track the mode, move
// the `.active` class, and swap the input placeholder so the `~` prefix
// is discoverable before submit.
function setBranchCreateMode(mode, current) {
  branchCreateMode = mode === 'personal' ? 'personal' : 'shared';
  document.querySelectorAll('.branch-create-mode-btn[data-create-mode]').forEach((btn) => {
    btn.classList.toggle('active', btn.getAttribute('data-create-mode') === branchCreateMode);
  });
  const input = document.getElementById('branch-create-input');
  if (input) {
    input.placeholder = branchCreateMode === 'personal'
      ? 'Personal branch name (~ added, forks from ' + current + ')'
      : 'New branch name (forks from ' + current + ')';
  }
}

async function createBranchFromInput(parentName) {
  const input = document.getElementById('branch-create-input');
  const err = document.getElementById('branch-popover-error');
  const raw = input.value.trim();
  // In personal mode, guarantee exactly one `~` prefix (tolerate a user
  // who typed it themselves). The bare word must be non-empty.
  const base = branchCreateMode === 'personal'
    ? raw.replace(/^~+/, '')
    : raw;
  if (!base) {
    err.textContent = 'Branch name is required';
    err.classList.remove('hidden');
    return;
  }
  const name = branchCreateMode === 'personal' ? PERSONAL_PREFIX + base : base;
  err.classList.add('hidden');
  try {
    const resp = await window.authFetch(API.api_branches, {
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
      API.api_branches_ref(name),
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
      API.api_branches_ref_merge(targetName),
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
    // Surface the audit-log when the resolver kept entries scoped
    // to their origin branch (sticky-local fns — see
    // graphden.versioning.branch-local). The alert is intentionally
    // synchronous + simple: the user just clicked "Merge", we want
    // them to KNOW these entries didn't propagate before the page
    // reloads. The diff modal's 📍 badge already showed them ahead
    // of time; this is the post-merge confirmation.
    const skipped = body?.skipped?.['branch-local'] || [];
    if (skipped.length > 0) {
      const names = skipped.map((s) => ':' + (s['fn-name'] || s['entity-id']))
                           .join(', ');
      alert(skipped.length + ' branch-local fn'
            + (skipped.length === 1 ? '' : 's')
            + ' did NOT propagate to ' + targetName + ': ' + names
            + '. (Marked with 📍 in the branch-diff modal.)');
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

// The resolution card (header + help + batch toolbar + per-conflict
// rows + previews + actions) is server-rendered hiccup at
// `POST /partials/merge-conflicts` — we POST the `{conflicts, source,
// target}` the failed merge just returned and the server renders it (it
// owns all markup + escaping, mirroring the other editor partials). JS
// here owns only the modal chrome (the full-viewport overlay div, which
// must be a flex sibling of the card so it can't come from the
// single-root partial) and the radio/apply lifecycle. `body` is the
// merge response — its `conflicts` array is what we render.
async function showMergeConflictsModal(body, sourceName, targetName) {
  const modal = ensureConflictsModal();
  // Build fully, THEN reveal. The modal is read the instant it becomes
  // visible (a user tabbing in — and the e2e — expect the rows to be
  // there), so `.hidden` stays on until the server-rendered card is
  // mounted; dropping it before the `await` below would expose an empty
  // shell during the fetch.
  modal.innerHTML = '';
  const overlay = document.createElement('div');
  overlay.className = 'merge-conflicts-overlay';
  overlay.addEventListener('click', closeConflictsModal);
  modal.appendChild(overlay);

  let cardHtml;
  try {
    const resp = await window.authFetch('/partials/merge-conflicts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conflicts: body?.conflicts || [],
        source: sourceName,
        target: targetName,
      }),
    });
    cardHtml = resp.ok
      ? await resp.text()
      : '<div class="merge-conflicts-card"><div class="merge-conflicts-header">'
        + 'Failed to load conflicts (HTTP ' + resp.status + ')</div></div>';
  } catch (_err) {
    cardHtml = '<div class="merge-conflicts-card"><div class="merge-conflicts-header">'
      + 'Failed to load conflicts</div></div>';
  }
  const wrap = document.createElement('div');
  wrap.innerHTML = cardHtml;
  const card = wrap.firstElementChild;
  if (card) modal.appendChild(card);

  const cancel = modal.querySelector('#merge-conflicts-cancel');
  if (cancel) cancel.addEventListener('click', closeConflictsModal);
  const submit = modal.querySelector('#merge-conflicts-submit');
  if (submit) {
    submit.addEventListener('click',
      () => submitConflictResolutions(sourceName, targetName));
  }
  const pickAll = (choice) => {
    modal.querySelectorAll('.merge-conflict-row input[type="radio"]')
      .forEach((r) => {
        if (r.value === choice) {
          r.checked = true;
          r.dispatchEvent(new Event('change', {bubbles: true}));
        }
      });
  };
  const pickSrc = modal.querySelector('#merge-conflicts-pick-all-source');
  if (pickSrc) pickSrc.addEventListener('click', () => pickAll('source'));
  const pickTgt = modal.querySelector('#merge-conflicts-pick-all-target');
  if (pickTgt) pickTgt.addEventListener('click', () => pickAll('target'));

  modal.classList.remove('hidden');
}

// Read each rendered row's `data-entity-*` + checked radio into the
// `:conflict-resolutions` payload. The server owns the row markup, so the
// JS↔partial contract is the two data-attrs + the radio `value`.
async function submitConflictResolutions(sourceName, targetName) {
  const rows = Array.from(document.querySelectorAll(
    '.merge-conflicts-modal .merge-conflict-row'));
  const resolutions = rows.map((row) => {
    const chosen = row.querySelector('input[type="radio"]:checked');
    return {
      'entity-name': row.getAttribute('data-entity-name'),
      'entity-id': row.getAttribute('data-entity-id'),
      choice: chosen?.value || 'source',
    };
  });
  const errBox = document.getElementById('merge-conflicts-error');
  try {
    const resp = await window.authFetch(
      API.api_branches_ref_merge(targetName),
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
