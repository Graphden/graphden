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

// Resolve the active branch ONCE, at boot, into a module-level constant —
// never per request. `getCurrentBranchName()` is called from the fetch wrapper
// (and the htmx:configRequest bridge) on EVERY /api/* + /partials/* call; if it
// re-read localStorage each time, a second tab switching branches would silently
// re-target this tab's in-flight PUT/POST writes onto the other tab's branch
// (cross-tab branch-drift — MED-HIGH data-loss). The branch a tab operates on is
// fixed for the life of the page: the only way to change it, `switchToBranch`,
// writes localStorage/URL and RELOADS, so the new value is picked up here on the
// next boot. URL `?branch=` still wins over localStorage — evaluated once, at load.
const BOOT_BRANCH = readUrlBranch() || readStoredBranch() || DEFAULT_BRANCH;

function getCurrentBranchName() {
  return BOOT_BRANCH;
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
const ORG_HEADER = 'X-Graphden-Org';
let graphdenCapabilities = null; // null = unknown → allow all
let graphdenWorkspace = null;    // null = unknown / no workspace hint
// The current principal's DATA-scope org id (from X-Graphden-Org). Used to tell
// a fn the principal OWNS (its `:org-id` matches) from a public / other-tier fn
// they may only read. null = single-tenant / unknown → ownership check is a
// no-op (everything editable, as before).
let graphdenCurrentOrg = null;
function captureCapabilities(resp) {
  try {
    const cap = resp?.headers?.get(CAP_HEADER);
    if (cap !== null && cap !== undefined) {
      graphdenCapabilities = new Set(cap.split(',').map((s) => s.trim()).filter(Boolean));
      document.body.classList.toggle('gd-no-write', !graphdenCapabilities.has('write'));
      document.body.classList.toggle('gd-no-execute', !graphdenCapabilities.has('execute'));
      // Platform surface is for platform-right holders only — reveal its rail
      // button (hidden by default) when the principal carries a platform cap.
      document.body.classList.toggle('gd-platform', graphdenCapabilities.has('platform-admin'));
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
    const org = resp?.headers?.get(ORG_HEADER);
    if (org !== null && org !== undefined) {
      graphdenCurrentOrg = org.trim() || null;
    }
  } catch (_) { /* never break a fetch over a header read */ }
}
// Platform tier = the operator (platform-admin) or a platform-access delegate.
// They edit the shared / public tier, so the per-fn ownership gate below is a
// no-op for them (unchanged, unrestricted behaviour).
function graphdenIsPlatformTier() {
  return !!graphdenCapabilities
    && (graphdenCapabilities.has('platform-admin') || graphdenCapabilities.has('platform-access'));
}
// Does the current principal OWN `fn` (may rename / delete it)? True in
// single-tenant (no capability header seen), for platform-tier principals, or
// when the fn's `:org-id` matches the principal's data-scope org. A public /
// base fn (null / 'public' org) or another tier's fn → NOT owned → the editor
// hides its destructive actions and shows it read-only.
function graphdenIsFnOwned(fn) {
  if (!graphdenCapabilities) return true;          // single-tenant → all mine
  if (graphdenIsPlatformTier()) return true;       // operator / delegate
  if (!graphdenCurrentOrg) return true;            // org unknown → fail-open (server still enforces)
  return !!fn && fn['org-id'] === graphdenCurrentOrg;
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
// Redesign 2026-08 — WORKSPACES (user-chosen scope over the shared namespace
// tree). A workspace is PERSONAL and per-browser (localStorage, like the branch
// selection + lens), reusing the existing namespace hierarchy — NO new entity:
//   • included roots (`graphden.workspace.roots`) — the namespaces you work in.
//     Empty ⇒ "All functions" (show everything). Pick from the ready-made
//     projects (the graph's root namespaces) instead of building from scratch.
//   • hidden paths (`graphden.workspace.hidden`) — YOUR personal exclusions
//     within the included scope (the ".gitignore" — remove a sub-namespace from
//     your view without touching the shared graph or anyone else's view).
// Both are a root+descendant match. The old separate "pins" set is folded in:
// a pinned root was just another included root, so on load we migrate it here.
let graphdenWorkspaceRoots = [];
try {
  const raw = JSON.parse(localStorage.getItem('graphden.workspace.roots') || 'null');
  if (Array.isArray(raw)) graphdenWorkspaceRoots = raw.slice();
  // One-time migration: old pins become included roots (same "keep in view").
  const oldPins = JSON.parse(localStorage.getItem('graphden.workspace.pins') || 'null');
  if (Array.isArray(oldPins) && oldPins.length) {
    for (const p of oldPins) if (!graphdenWorkspaceRoots.includes(p)) graphdenWorkspaceRoots.push(p);
    localStorage.setItem('graphden.workspace.roots', JSON.stringify(graphdenWorkspaceRoots));
    localStorage.removeItem('graphden.workspace.pins');
  }
} catch (_) { /* ignore malformed pref */ }

let graphdenHiddenPaths = [];
try {
  const h = JSON.parse(localStorage.getItem('graphden.workspace.hidden') || 'null');
  if (Array.isArray(h)) graphdenHiddenPaths = h.slice();
} catch (_) { /* ignore malformed pref */ }

function graphdenWorkspaceActive() { return graphdenWorkspaceRoots.length > 0; }
// Is nsPath inside the workspace scope (a root or under one)?
function graphdenInWorkspaceScope(nsPath) {
  if (!nsPath) return false;
  for (const w of graphdenWorkspaceRoots) {
    if (nsPath === w || nsPath.startsWith(w + '.')) return true;
  }
  return false;
}
// Personal hide: nsPath is hidden if it (or an ancestor) is in the hidden set.
function graphdenIsHidden(nsPath) {
  if (!nsPath) return false;
  for (const h of graphdenHiddenPaths) {
    if (nsPath === h || nsPath.startsWith(h + '.')) return true;
  }
  return false;
}
function graphdenWorkspaceLabel() {
  if (!graphdenWorkspaceRoots.length) return 'All functions';
  return graphdenWorkspaceRoots.length === 1
    ? graphdenWorkspaceRoots[0]
    : graphdenWorkspaceRoots.length + ' namespaces';
}
// Set (array of ns roots) or clear (null/empty) the whole workspace + persist.
function setGraphdenWorkspace(roots) {
  graphdenWorkspaceRoots = (Array.isArray(roots) && roots.length) ? roots.slice() : [];
  try {
    localStorage.setItem('graphden.workspace.roots', JSON.stringify(graphdenWorkspaceRoots));
  } catch (_) { /* ignore */ }
}
// Add/remove one root from the workspace (the popover checklist).
function graphdenToggleWorkspaceRoot(root) {
  const i = graphdenWorkspaceRoots.indexOf(root);
  if (i >= 0) graphdenWorkspaceRoots.splice(i, 1);
  else graphdenWorkspaceRoots.push(root);
  try {
    localStorage.setItem('graphden.workspace.roots', JSON.stringify(graphdenWorkspaceRoots));
  } catch (_) { /* ignore */ }
}
// Add/remove one namespace path from the personal hidden set.
function graphdenToggleHidden(nsPath) {
  const i = graphdenHiddenPaths.indexOf(nsPath);
  if (i >= 0) graphdenHiddenPaths.splice(i, 1);
  else graphdenHiddenPaths.push(nsPath);
  try {
    localStorage.setItem('graphden.workspace.hidden', JSON.stringify(graphdenHiddenPaths));
  } catch (_) { /* ignore */ }
}
window.graphdenWorkspaceActive = graphdenWorkspaceActive;
window.graphdenInWorkspaceScope = graphdenInWorkspaceScope;
window.graphdenIsHidden = graphdenIsHidden;
window.graphdenWorkspaceLabel = graphdenWorkspaceLabel;
window.setGraphdenWorkspace = setGraphdenWorkspace;
window.graphdenToggleWorkspaceRoot = graphdenToggleWorkspaceRoot;
window.graphdenToggleHidden = graphdenToggleHidden;
window.graphdenWorkspaceRoots = () => graphdenWorkspaceRoots.slice();
window.graphdenHiddenList = () => graphdenHiddenPaths.slice();
// Back-compat shims (older callers referenced the focus/pins split; the model
// is now one included-roots set). graphdenInFocus ≡ in-scope; pins are gone.
window.graphdenInFocus = graphdenInWorkspaceScope;
window.graphdenWorkspaceFocused = graphdenWorkspaceActive;
window.graphdenIsPinned = () => false;
window.graphdenPins = () => [];
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
window.graphdenIsFnOwned = graphdenIsFnOwned;
window.graphdenIsPlatformTier = graphdenIsPlatformTier;

// A branch can disappear under a tab that is still standing on it — someone
// merges and deletes it, or the tour's own "Delete branch & return" runs in
// another window. The stored branch name then rides out on EVERY internal
// call and the server answers 400 "Unknown branch", so the editor renders
// nothing at all with no explanation: sidebar empty, panels empty, every
// action dead. Recover once, loudly: drop the stored branch and reload on
// the default one. Guarded by a flag so a burst of parallel 400s (boot fires
// a dozen) triggers exactly one reload.
let _branchRecoveryStarted = false;

function maybeRecoverFromDeletedBranch(resp, branch) {
  if (_branchRecoveryStarted) return;
  if (resp?.status !== 400) return;
  if (!branch || branch === DEFAULT_BRANCH) return;
  resp.clone().json().then((body) => {
    if (_branchRecoveryStarted) return;
    if (!/unknown branch/i.test(body?.error || '')) return;
    _branchRecoveryStarted = true;
    try { localStorage.removeItem(BRANCH_STORAGE_KEY); } catch (_) { /* ignore */ }
    if (typeof gdToast === 'function') {
      gdToast('Branch “' + branch + '” no longer exists — returning to '
              + DEFAULT_BRANCH);
    }
    const url = new URL(location.href);
    url.searchParams.delete('branch');
    location.replace(url.toString());
  }).catch(() => { /* non-JSON 400s are someone else's problem */ });
}


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
      ? promise.then((resp) => {
          captureCapabilities(resp);
          maybeRecoverFromDeletedBranch(resp, branch);
          return resp;
        })
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
    // A click anywhere outside a ⋯ button / its menu collapses the
    // open ⋯ menu — including clicks on other popover rows.
    if (!e.target.closest?.('.branch-row-more, .branch-row-more-menu')) {
      closeBranchMoreMenus();
    }
    const popover = document.getElementById('branch-popover');
    const btn = document.getElementById('branch-chip-btn');
    if (!popover || popover.classList.contains('hidden')) return;
    if (popover.contains(e.target) || btn.contains(e.target)) return;
    // The ⚙ protection / ⛨ policy mini-menus are appended to <body>,
    // OUTSIDE the popover element — a click on a control inside them
    // (the approvals segment, a checkbox) used to read as "outside the
    // popover" here and closed BOTH popovers mid-interaction (the
    // lesson-21 "the menu closes before I can pick" bug).
    if (e.target.closest?.('#gd-protect-pop, #gd-branch-policy-pop, .gd-pop-scrim')) return;
    if (pointerEventInTour(e)) return;
    closeBranchPopover();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    // Only when the popover is actually OPEN: this is a document-level
    // handler, and marking every Escape as consumed (or calling close on
    // one) makes the key useless for whatever else is listening — the
    // interactive tutorial ends on Escape and stopped being able to.
    const popover = document.getElementById('branch-popover');
    if (!popover || popover.classList.contains('hidden')) return;
    e.preventDefault();
    closeBranchPopover();
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
  gdSyncEdgeBranchBadge();
}

// Collapsed-Explorer branch badge — the branch chip lives in the Explorer
// now, so collapsing it would hide the WRITE CONTEXT. On a non-default
// branch the left-edge expand tab carries the branch name (and the accent
// wash); on main it stays a bare chevron and the screen stays clean.
function gdSyncEdgeBranchBadge() {
  const tab = document.getElementById('sidebar-expand-floating');
  if (!tab) return;
  const name = getCurrentBranchName();
  const nonDefault = name !== DEFAULT_BRANCH;
  tab.classList.toggle('gd-edge-nondefault', nonDefault);
  let badge = tab.querySelector('.gd-edge-branch');
  if (nonDefault) {
    if (!badge) {
      badge = document.createElement('span');
      badge.className = 'gd-edge-branch';
      tab.appendChild(badge);
    }
    badge.textContent = name;
    tab.title = 'Show the function browser — on branch "' + name + '"';
  } else if (badge) {
    badge.remove();
    tab.title = 'Show the function browser';
  }
}
window.gdSyncEdgeBranchBadge = gdSyncEdgeBranchBadge;

function toggleBranchPopover() {
  const popover = document.getElementById('branch-popover');
  if (!popover) return;
  if (popover.classList.contains('hidden')) openBranchPopover();
  else closeBranchPopover();
}

function closeBranchPopover() {
  closeProtectionMenu();   // don't orphan the ⚙ menu over a hidden popover
  closeBranchMoreMenus();
  const popover = document.getElementById('branch-popover');
  if (popover) popover.classList.add('hidden');
}

// Close every open per-row ⋯ menu.
function closeBranchMoreMenus() {
  document.querySelectorAll('.branch-row-more-menu.open').forEach((m) => {
    m.classList.remove('open');
  });
  document.querySelectorAll('.branch-row-more[aria-expanded="true"]').forEach((b) => {
    b.setAttribute('aria-expanded', 'false');
  });
}

// Toggle one row's ⋯ menu; position it under the button (the menu is
// `position: fixed`, so the popover list's overflow can't clip it).
function toggleBranchMoreMenu(btn) {
  const name = btn.getAttribute('data-more-branch');
  const menu = btn.parentElement?.querySelector(
    '.branch-row-more-menu[data-more-menu="' + (window.CSS?.escape ? CSS.escape(name) : name) + '"]')
    || btn.nextElementSibling;
  if (!menu) return;
  const wasOpen = menu.classList.contains('open');
  closeBranchMoreMenus();
  if (wasOpen) return;
  const r = btn.getBoundingClientRect();
  menu.style.top = (r.bottom + 4) + 'px';
  menu.style.left = Math.max(8, Math.min(r.right - 200, window.innerWidth - 210)) + 'px';
  menu.classList.add('open');
  btn.setAttribute('aria-expanded', 'true');
  // Menu items either reload the popover (propose / delete / policy)
  // or open their own floating menu (protection) — close the ⋯ shell
  // as soon as one is picked.
  menu.querySelectorAll('button').forEach((item) => {
    item.addEventListener('click', () => closeBranchMoreMenus(), { once: true });
  });
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

  // Protected-branch shield (tenancy only — CSS hides it otherwise):
  // a mini-menu of the three write policies; picking one POSTs
  // /api/branches/:ref/policy and reloads the popover. WHO may flip a
  // policy is enforced server-side (owner / org admins) — a rejected
  // change surfaces in the shared error slot.
  popover.querySelectorAll('.branch-row-policy').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      openBranchPolicyMenu(btn);
    });
  });

  // ⚙ Protection menu (open-core): require-merge / required-approvals /
  // count-self-approval, consolidated into one popover so the row action
  // bar stays uncluttered.
  popover.querySelectorAll('.branch-row-protect').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      openProtectionMenu(btn);
    });
  });

  // Change-proposal toggle (open-core): mark/unmark this branch as a
  // proposal for review into its base. Click POSTs the negation of the
  // current state (read off `data-review-state`) to /branches/:ref/propose,
  // then reloads the popover.
  popover.querySelectorAll('.branch-row-propose').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleBranchPropose(btn);
    });
  });

  // Approve a proposal (open-core reviewer action). POSTs
  // /branches/:ref/approve; a 403 (not allowed) surfaces in the slot.
  popover.querySelectorAll('.branch-row-approve').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      approveProposal(btn);
    });
  });

  // Fill "n/N approvals" onto each proposed row + a "Proposals (N)"
  // header, so a reviewer sees review status at a glance.
  populateReviewStatus(popover);

  popover.querySelectorAll('.branch-row-delete').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteBranchWithConfirm(btn.getAttribute('data-branch-name'),
                              branchRefFrom(btn, btn.getAttribute('data-branch-name')));
    });
  });

  popover.querySelectorAll('.branch-row-merge').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      // Target = the CURRENT branch; its row is in this very popover —
      // use its id so a slash-named current branch can be a target.
      const curRow = popover.querySelector(
        '.branch-row[data-branch-name="' + (window.CSS?.escape ? CSS.escape(current) : current) + '"]');
      mergeBranchInto(btn.getAttribute('data-merge-source'), current,
                      null, curRow?.getAttribute('data-branch-id') || null);
    });
  });

  // ⋯ overflow menu — server-rendered hidden inside each row (so the
  // propose / protect / policy / delete bindings above keep finding
  // their buttons); JS toggles + positions it. One open menu at a time.
  popover.querySelectorAll('.branch-row-more').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleBranchMoreMenu(btn);
    });
  });

  // ◐ compare mode — the editor-wide diff lens (editor-diff-mode.js).
  // Entering = picking the second branch; the picked row's ◐ is lit,
  // and clicking it again clears the pick (exits).
  const comparedNow = (typeof gdDiffModeBranch === 'function')
    ? gdDiffModeBranch() : null;
  popover.querySelectorAll('.branch-row-compare').forEach((btn) => {
    const mine = btn.getAttribute('data-compare-branch');
    if (comparedNow && mine === comparedNow) {
      btn.classList.add('on');
      btn.setAttribute('data-tip', 'Stop comparing');
    }
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      closeBranchPopover();
      if (btn.classList.contains('on')) {
        if (typeof gdExitDiffMode === 'function') gdExitDiffMode();
      } else if (typeof gdEnterDiffMode === 'function') {
        gdEnterDiffMode(mine);
      }
    });
  });

  popover.querySelectorAll('.branch-row-diff').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const source = btn.getAttribute('data-diff-source');
      if (typeof showBranchDiff === 'function') {
        showBranchDiff(current, source, branchRefFrom(btn, source));
      }
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

  wireHubSyncSection(popover);
}

// Hub sync — the partial renders `.branch-popover-hub` only when the
// server is wired to a hub (GRAPHDEN_HUB_URL). The /api/sync/* routes
// live in the OPTIONAL registry package, so the section is removed when
// window.API lacks them (hub env set but registry package dropped).
// Push snapshots the CURRENT branch (the branch header scopes the POST)
// onto the hub as push/<branch>; Pull lands the hub's main locally as
// hub/main and refreshes the list so the new branch shows up.
function wireHubSyncSection(popover) {
  const section = popover.querySelector('.branch-popover-hub');
  if (!section) return;
  const api = (typeof window.API === 'object' && window.API) ? window.API : null;
  if (!api || typeof api.api_sync_push === 'undefined') {
    section.remove();
    return;
  }
  const pushBtn = section.querySelector('#branch-hub-push');
  const pullBtn = section.querySelector('#branch-hub-pull');
  const setBusy = (busy) => {
    [pushBtn, pullBtn].forEach((b) => { if (b) b.disabled = busy; });
  };
  const report = (text, isError) => {
    const status = document.getElementById('branch-hub-status');
    if (!status) return;
    status.textContent = text;
    status.classList.toggle('branch-hub-status-error', !!isError);
  };
  async function runSync(url) {
    setBusy(true);
    report('Syncing with the hub…', false);
    try {
      const resp = await window.authFetch(url, { method: 'POST' });
      const data = await resp.json().catch(() => null);
      if (!resp.ok || !data || data.ok === false) {
        const reason = (data && (data.reason || data.error))
          || ('HTTP ' + resp.status);
        const detail = data?.hint ? ' — ' + data.hint : '';
        report('Failed: ' + reason + detail, true);
        return null;
      }
      return data;
    } catch (err) {
      report('Failed: ' + (err?.message || 'network error'), true);
      return null;
    } finally {
      setBusy(false);
    }
  }
  if (pushBtn) {
    pushBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const d = await runSync(api.api_sync_push);
      if (d) {
        const n = (d['fn-ids'] || []).length;
        report('Pushed → ' + (d.target || 'push branch') + ' (' + n
          + ' fns). Review + merge on the hub.', false);
      }
    });
  }
  if (pullBtn) {
    pullBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const d = await runSync(api.api_sync_pull);
      if (d) {
        // Refresh the list so hub/main appears, then restate the outcome
        // (the reload swaps the status slot out with the rest of the body).
        await openBranchPopover();
        const status = document.getElementById('branch-hub-status');
        if (status) {
          status.textContent = 'Pulled → ' + (d.branch || 'hub/main')
            + ' — Δ diff it against your branch, then ⇢ merge.';
        }
      }
    });
  }
}

const BRANCH_POLICY_OPTIONS = [
  ['open', 'Everyone with write access'],
  ['owner', 'Only the owner (org admins can unlock)'],
  ['admins', 'Org admins only'],
];

function closeBranchPolicyMenu() {
  const p = document.getElementById('gd-branch-policy-pop');
  if (p) p.remove();
  const s = document.getElementById('gd-branch-policy-scrim');
  if (s) s.remove();
}

// Mini-menu on the row's ⛨ — pick who may write this branch.
function openBranchPolicyMenu(btn) {
  closeBranchPolicyMenu();
  const row = btn.closest('.branch-row');
  const branchName = btn.getAttribute('data-policy-branch');
  const branchRef = branchRefFrom(btn, branchName);
  const current = row?.getAttribute('data-write-policy') || 'open';
  const scrim = document.createElement('div');
  scrim.id = 'gd-branch-policy-scrim';
  scrim.className = 'gd-pop-scrim';
  scrim.addEventListener('click', closeBranchPolicyMenu);
  document.body.appendChild(scrim);
  const pop = document.createElement('div');
  pop.id = 'gd-branch-policy-pop';
  pop.className = 'gd-pop';
  // Branch name is user-controlled and getAttribute returns it DECODED, so it
  // must go in via textContent — never string-concatenated into innerHTML
  // (would re-inject `<img onerror=…>` live; there is no CSP). The option
  // rows below are built from the static BRANCH_POLICY_OPTIONS constant only,
  // so their markup stays a trusted template.
  const heading = document.createElement('h5');
  heading.textContent = 'Who can write ' + branchName;
  pop.appendChild(heading);
  let html = '';
  BRANCH_POLICY_OPTIONS.forEach(([value, label]) => {
    const on = value === (current || 'open');
    html += '<button type="button" class="gd-pop-item' + (on ? ' sel' : '') + '"'
      + ' data-policy-value="' + value + '">'
      + '<span class="gd-pi">' + (on ? '●' : '○') + '</span>' + label + '</button>';
  });
  pop.insertAdjacentHTML('beforeend', html);
  pop.querySelectorAll('[data-policy-value]').forEach((item) => {
    item.addEventListener('click', async () => {
      closeBranchPolicyMenu();
      const err = document.getElementById('branch-popover-error');
      try {
        const resp = await window.authFetch(API.api_branches_ref_policy(branchRef), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ 'write-policy': item.getAttribute('data-policy-value') }),
        });
        const body = await resp.json();
        if (body.ok) {
          openBranchPopover(); // re-render rows with the new lock state
        } else if (err) {
          err.textContent = body.error || 'Could not change the branch protection';
          err.classList.remove('hidden');
        }
      } catch (e2) {
        if (err) {
          err.textContent = 'Network error: ' + (e2?.message || e2);
          err.classList.remove('hidden');
        }
      }
    });
  });
  const r = btn.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 300)) + 'px';
  pop.style.top = (r.bottom + 6) + 'px';
  document.body.appendChild(pop);
}

// The /api/branches/:ref/* ops take the ref as ONE path segment, so a
// branch NAME containing "/" (the hub's push/<x> convention, or any
// user-typed slash) can never round-trip through the URL. Every row
// carries `data-branch-id`; prefer it — `resolve-branch-ref` accepts a
// UUID — and fall back to the name for markup that predates the attr.
function branchRefFrom(el, fallbackName) {
  return el?.closest?.('.branch-row')?.getAttribute('data-branch-id')
    || fallbackName;
}

// Tear down the ⚙ protection menu (popover + scrim) if open.
function closeProtectionMenu() {
  document.getElementById('gd-protect-pop')?.remove();
  document.getElementById('gd-protect-scrim')?.remove();
}

// POST a review-policy / protect change and reload the popover. `url` is
// already resolved via window.API. A rejection surfaces in the shared slot.
async function postBranchProtection(url, payload, errMsg) {
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const body = await resp.json();
    if (body.ok) {
      closeProtectionMenu();
      openBranchPopover();
    } else if (err) {
      err.textContent = body.error || errMsg;
      err.classList.remove('hidden');
    }
  } catch (e2) {
    if (err) {
      err.textContent = 'Network error: ' + (e2?.message || e2);
      err.classList.remove('hidden');
    }
  }
}

// ⚙ protection menu (open-core): the branch-as-TARGET knobs in one place —
// require-merge (push only via merge), required-approvals (0–3), and whether
// the author's own approval counts. Current state is read off the button's
// data-attrs. required-approvals + allow-self are POSTed together (the
// /review-policy endpoint is a full set, so sending both preserves both).
function openProtectionMenu(btn) {
  closeProtectionMenu();
  const branchName = btn.getAttribute('data-protect-branch');
  const branchRef = branchRefFrom(btn, branchName);
  const requireMerge = btn.getAttribute('data-require-merge') === '1';
  const reqAppr = parseInt(btn.getAttribute('data-reqappr') || '0', 10) || 0;
  // data-allow-self: "off" = explicitly disabled; "on"/"" = counted (default).
  const allowSelf = btn.getAttribute('data-allow-self') !== 'off';
  const rpUrl = API.api_branches_ref_review_policy(branchRef);

  const scrim = document.createElement('div');
  scrim.id = 'gd-protect-scrim';
  scrim.className = 'gd-pop-scrim';
  scrim.addEventListener('click', closeProtectionMenu);
  document.body.appendChild(scrim);

  const pop = document.createElement('div');
  pop.id = 'gd-protect-pop';
  pop.className = 'gd-pop';
  // branchName is user-controlled + decoded → textContent only (no innerHTML).
  const heading = document.createElement('h5');
  heading.textContent = 'Protect ' + branchName;
  pop.appendChild(heading);

  // require-merge checkbox
  const rmLabel = document.createElement('label');
  rmLabel.className = 'gd-protect-opt';
  const rmBox = document.createElement('input');
  rmBox.type = 'checkbox';
  rmBox.checked = requireMerge;
  rmBox.addEventListener('change', () =>
    postBranchProtection(API.api_branches_ref_protect(branchRef),
                         { 'require-merge': rmBox.checked },
                         'Could not change branch protection'));
  rmLabel.appendChild(rmBox);
  const rmText = document.createElement('span');
  rmText.textContent = 'Push only via merge (no direct writes)';
  rmLabel.appendChild(rmText);
  pop.appendChild(rmLabel);

  // Required approvals — a 0…3 SEGMENTED control, not a <select>: the
  // range is four known values (one tap each beats a two-step native
  // dropdown), and a native dropdown over a floating menu was fragile
  // (the outside-click closer used to swallow it mid-pick).
  const raRow = document.createElement('div');
  raRow.className = 'gd-protect-opt gd-protect-appr';
  const raText = document.createElement('span');
  raText.id = 'gd-protect-appr-label';
  raText.textContent = 'Required approvals';
  raRow.appendChild(raText);
  const seg = document.createElement('div');
  seg.className = 'gd-protect-seg';
  seg.setAttribute('role', 'radiogroup');
  seg.setAttribute('aria-labelledby', 'gd-protect-appr-label');
  let currentAppr = reqAppr;
  const saBox = document.createElement('input');   // created early — posted together
  const pushPolicy = () =>
    postBranchProtection(rpUrl,
                         { 'required-approvals': currentAppr,
                           'allow-self-approval': saBox.checked },
                         'Could not change the review policy');
  for (let n = 0; n <= 3; n++) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'gd-protect-seg-btn' + (n === reqAppr ? ' sel' : '');
    b.textContent = String(n);
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', n === reqAppr ? 'true' : 'false');
    b.title = n === 0 ? 'No review required'
      : ('Merges into ' + branchName + ' need ' + n + ' approval' + (n === 1 ? '' : 's'));
    b.addEventListener('click', () => {
      if (n === currentAppr) return;
      currentAppr = n;
      seg.querySelectorAll('.gd-protect-seg-btn').forEach((x) => {
        x.classList.toggle('sel', x === b);
        x.setAttribute('aria-checked', x === b ? 'true' : 'false');
      });
      pushPolicy();
    });
    seg.appendChild(b);
  }
  raRow.appendChild(seg);
  pop.appendChild(raRow);

  const saLabel = document.createElement('label');
  saLabel.className = 'gd-protect-opt';
  saBox.type = 'checkbox';
  saBox.checked = allowSelf;
  saLabel.appendChild(saBox);
  const saText = document.createElement('span');
  saText.textContent = "Count the author's own approval";
  saLabel.appendChild(saText);
  pop.appendChild(saLabel);
  saBox.addEventListener('change', pushPolicy);

  const r = btn.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 280)) + 'px';
  pop.style.top = (r.bottom + 6) + 'px';
  document.body.appendChild(pop);
}

// Mark/unmark a branch as a change proposal for review into its base.
// `proposed` is the JSON key the /propose handler reads. WHO may
// propose/withdraw is open-core (any authenticated writer of the branch);
// a rejection surfaces in the shared slot.
async function toggleBranchPropose(btn) {
  const branchName = btn.getAttribute('data-propose-branch');
  const branchRef = branchRefFrom(btn, branchName);
  const next = btn.getAttribute('data-review-state') !== '1';
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(API.api_branches_ref_propose(branchRef), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ proposed: next }),
    });
    const body = await resp.json();
    if (body.ok) {
      openBranchPopover(); // re-render rows with the new proposal state
    } else if (err) {
      err.textContent = body.error || 'Could not change the proposal state';
      err.classList.remove('hidden');
    }
  } catch (e2) {
    if (err) {
      err.textContent = 'Network error: ' + (e2?.message || e2);
      err.classList.remove('hidden');
    }
  }
}

// Record the caller's approval of a proposal branch. A 403 (the caller
// may not approve merges into the target) or any error surfaces in the
// shared slot.
async function approveProposal(btn) {
  const branchName = btn.getAttribute('data-approve-branch');
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(
      API.api_branches_ref_approve(branchRefFrom(btn, branchName)), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });
    const body = await resp.json();
    if (resp.ok && body.ok) {
      openBranchPopover();
    } else if (err) {
      err.textContent = body.error
        || (resp.status === 403 ? 'You are not allowed to approve merges into this branch' : 'Could not approve');
      err.classList.remove('hidden');
    }
  } catch (e2) {
    if (err) {
      err.textContent = 'Network error: ' + (e2?.message || e2);
      err.classList.remove('hidden');
    }
  }
}

// Fill "n/N approvals" onto each PROPOSED row + a "Proposals (N)" header,
// so a reviewer sees review status at a glance. One /approvals fetch per
// proposed row (proposals are few); best-effort — a failure is silent.
async function populateReviewStatus(popover) {
  const approveBtns = [...popover.querySelectorAll('.branch-row-approve[data-approve-branch]')];
  if (!approveBtns.length) return;
  const header = document.createElement('div');
  header.className = 'branch-proposals-header';
  header.textContent = approveBtns.length
    + (approveBtns.length === 1 ? ' proposal awaiting review' : ' proposals awaiting review');
  const anchor = popover.querySelector('.branch-section-rows');
  if (anchor?.parentNode) anchor.parentNode.insertBefore(header, anchor);
  for (const btn of approveBtns) {
    const name = btn.getAttribute('data-approve-branch');
    try {
      const resp = await window.authFetch(
        API.api_branches_ref_approvals(branchRefFrom(btn, name)));
      if (!resp.ok) continue;
      const st = await resp.json();
      const req = st.required ?? 0;
      if (req <= 0) continue; // no approvals required → nothing to show
      const badge = document.createElement('span');
      badge.className = 'branch-appr-count' + (st.satisfied ? ' ok' : '');
      badge.textContent = (st.have ?? 0) + '/' + req;
      badge.title = 'approvals recorded / required';
      btn.insertAdjacentElement('afterend', badge);
    } catch (_) { /* best-effort */ }
  }
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
  // Advanced → protected-branch write policy; "open" (the default) is
  // simply not sent, so the ordinary path stays a plain branch.
  const policySel = document.getElementById('branch-create-policy');
  const policy = policySel && policySel.value !== 'open' ? policySel.value : null;
  try {
    const resp = await window.authFetch(API.api_branches, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Object.assign(
        { name, 'base-branch-id': parentName },
        policy ? { 'write-policy': policy } : {}
      )),
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

async function deleteBranchWithConfirm(name, ref) {
  if (!confirm('Delete branch "' + name + '"? Every version row on it will be removed.')) return;
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(
      API.api_branches_ref(ref || name),
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

async function mergeBranchInto(sourceName, targetName, conflictResolutions, targetRef) {
  // `targetRef` — an id-safe /api path ref for the TARGET (a name with
  // "/" can't ride the :ref segment). Callers with a row in hand pass
  // the row's id; absent → the name (pre-redesign behaviour).
  targetRef = targetRef || targetName;
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

  // Separate the FETCH from response-processing on purpose. Only the fetch
  // itself REJECTING (no response arrived) is the "committed-merge, target
  // restarting" case — the merge endpoint drops the connection solely in
  // its post-commit step (restarting the target's services; when the target
  // runs the very web-server serving this request, e.g. merging into main,
  // the rebind severs the response). A response that DID arrive — even a
  // 500, a proxy 502/504 HTML page, or an empty-bodied 401 — means the merge
  // did NOT commit and must be shown as an error, never as "restarting".
  let resp;
  try {
    resp = await window.authFetch(
      API.api_branches_ref_merge(targetRef),
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: sourceName,
          'conflict-resolutions': conflictResolutions || undefined,
        }),
      });
  } catch (_netErr) {
    // Fetch rejected — no response. Post-commit restart severed it; the
    // merge already committed. Wait out the rebind, then reload to the real
    // post-merge state instead of crying "Failed to fetch".
    setError('Merge submitted — ' + targetName + ' is restarting, verifying…');
    if (await waitForServerBack(30000)) { closeBranchPopover(); location.reload(); return; }
    setError('Merge sent, but ' + targetName + ' has not come back yet — '
             + 'reload in a moment to confirm.');
    return;
  }

  // A response arrived — parse defensively (an error page / empty body is
  // not JSON) and handle by status. Check 401 BEFORE parsing.
  if (resp.status === 401) { setError('Sign in to merge'); return; }
  const body = await resp.json().catch(() => ({}));
  if (body?.ok === false && body?.reason === 'merge-conflict') {
    // Drop the popover so the modal has full attention.
    closeBranchPopover();
    showMergeConflictsModal(body, sourceName, targetName, targetRef);
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
}


// Poll /health until it answers OK or the deadline passes. Used after a
// merge whose response was severed by the target's post-commit service
// restart — the merge already committed; this just waits out the rebind.
// /health is a fixed, deployment-invariant infra route (not a
// graph-composed API route in window.API), so a same-origin relative
// path is correct here.
async function waitForServerBack(deadlineMs) {
  const deadline = Date.now() + deadlineMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch('/health', { cache: 'no-store' });
      if (r.ok) return true;
    } catch (_) { /* still down — keep polling */ }
    await new Promise((res) => setTimeout(res, 1000));
  }
  return false;
}

let _conflictsModal = null;
// Where the keyboard was before the modal took over (the merge button in
// the branch popover), so closing hands it back.
let _conflictsTrigger = null;

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
  // This modal shipped with no Escape handler at all — the only ways out
  // were the Cancel button and clicking the overlay.
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || el.classList.contains('hidden')) return;
    e.preventDefault();   // consumed — see graphden-popover.js
    closeConflictsModal();
  });
  // Make the declared aria-modal="true" true.
  installTabTrap({
    getEl: () => _conflictsModal,
    isVisible: () => !!_conflictsModal && !_conflictsModal.classList.contains('hidden'),
  });
  return el;
}

function closeConflictsModal() {
  if (!_conflictsModal) return;
  const hadFocus = _conflictsModal.contains(document.activeElement);
  _conflictsModal.classList.add('hidden');
  setSiblingsInert(_conflictsModal, false);
  if (hadFocus) returnFocusTo(_conflictsTrigger);
  _conflictsTrigger = null;
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
async function showMergeConflictsModal(body, sourceName, targetName, targetRef) {
  const modal = ensureConflictsModal();
  _conflictsTrigger = document.activeElement;
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
      () => submitConflictResolutions(sourceName, targetName, targetRef));
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
  setSiblingsInert(modal, true);
  focusIntoDialog(modal);
}

// Read each rendered row's `data-entity-*` + checked radio into the
// `:conflict-resolutions` payload. The server owns the row markup, so the
// JS↔partial contract is the two data-attrs + the radio `value`.
async function submitConflictResolutions(sourceName, targetName, targetRef) {
  targetRef = targetRef || targetName;
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
  const setError = (msg) => {
    if (errBox) { errBox.textContent = msg; errBox.classList.remove('hidden'); }
  };

  // Same fetch/response split as mergeBranchInto (they submit the SAME merge —
  // this is just the conflict-resolved re-submit). A fetch REJECTION means the
  // merge committed and the target's post-commit service restart severed the
  // response — the canonical case is resolving conflicts on a merge into main
  // that touches the web-server serving this very request. Without this split
  // the catch reported "Failed to fetch" on an already-committed merge, leaving
  // the modal open so the user re-submits an already-done merge. A response that
  // DID arrive (even an error page) means the merge did NOT commit.
  let resp;
  try {
    resp = await window.authFetch(
      API.api_branches_ref_merge(targetRef),
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: sourceName,
          'conflict-resolutions': resolutions,
        }),
      });
  } catch (_netErr) {
    setError('Merge submitted — ' + targetName + ' is restarting, verifying…');
    if (await waitForServerBack(30000)) { closeConflictsModal(); location.reload(); return; }
    setError('Merge sent, but ' + targetName + ' has not come back yet — '
             + 'reload in a moment to confirm.');
    return;
  }
  if (resp.status === 401) { setError('Sign in to merge'); return; }
  const body = await resp.json().catch(() => ({}));
  if (!resp.ok || body?.ok === false) {
    setError(body?.error || ('HTTP ' + resp.status));
    return;
  }
  closeConflictsModal();
  location.reload();
}

// Public API for sibling modules.
window.getCurrentBranchName = getCurrentBranchName;
window.isOnDefaultBranch = isOnDefaultBranch;
window.switchToBranch = switchToBranch;
window.initBranchSelector = initBranchSelector;
