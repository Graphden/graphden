// Editor Branch CONTEXT — which branch (and org, workspace, capabilities)
// every request carries.
//
// State sources (read
// precedence): URL `?branch=<name>` → localStorage → 'main'. Switching
// (`switchToBranch`) mutates BOTH and reloads the page — reload is the
// simplest "invalidate everything": the editor caches graph data, layout,
// lookup maps, and rebuilding them in place would mean touching every state
// owner. The backend's per-branch ctx cache means the new branch's first
// request pays a one-time compile, not every request.
//
// Every fetch to /api/* gets `X-Graphden-Branch: <name>` (when not main):
// `window.fetch` is wrapped AT LOAD (the IIFE below), so direct fetch sites
// pick up branch context without being touched; `authFetch` (editor-auth.js)
// stacks Authorization on top. htmx does not go through fetch — the
// `htmx:configRequest` listener bridges the same header for it. The response
// side captures `X-Graphden-Capabilities` / `-Workspace` / `-Org` into the
// `graphden*` accessors the rest of the editor gates on, and the workspace
// store (root namespaces in scope, ⊘-hidden paths) lives here too because the
// fetch wrapper stamps it.
//
// Loads BEFORE editor-branches.js (the chip + popover UI) — the wrap must be
// in place before the first request.

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
// A namespace path is in the workspace when it equals, or is a descendant
// of, one of the workspace roots. No workspace → false (nothing highlighted).
function graphdenInWorkspace(nsPath) {
  if (!graphdenWorkspace || graphdenWorkspace.size === 0 || !nsPath) return false;
  for (const w of graphdenWorkspace) {
    if (nsPath === w || nsPath.startsWith(w + '.')) return true;
  }
  return false;
}
// The reader's own namespace scope (roots + ⊘ exclusions) is the
// `namespaces` / `exclude` axes of the Explorer's filter set —
// editor-explorer-filters.js (it migrated the old `graphden.workspace.*`
// keys). What stays here is the tenancy addon's `X-Graphden-Workspace`
// HINT above: the org's home namespaces, highlighted in the tree.
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
window.graphdenInWorkspace = graphdenInWorkspace;
window.graphdenTenancyActive = graphdenTenancyActive;
window.graphdenHasCap = graphdenHasCap;
window.graphdenIsOrgOwner = graphdenIsOrgOwner;
window.graphdenIsFnOwned = graphdenIsFnOwned;

// A branch can disappear under a tab that is still standing on it — someone
// merges and deletes it, or the tour's own "Delete branch & return" runs in
// another window. The stored branch name then rides out on EVERY internal
// call and the server answers 400 "Unknown branch", so the editor renders
// nothing at all with no explanation: sidebar empty, panels empty, every
// action dead. Recover once, loudly: drop the stored branch and reload on
// the default one. Guarded by a flag so a burst of parallel 400s (boot fires
// a dozen) triggers exactly one reload.
let _branchRecoveryStarted = false;

// `sentBranch` is the branch the REQUEST named, not the one the tab stands
// on: the diff ghost and compare mode ask about another branch with an
// explicit header, and that branch being gone says nothing about ours.
function maybeRecoverFromDeletedBranch(resp, sentBranch) {
  if (_branchRecoveryStarted) return;
  if (resp?.status !== 400) return;
  const branch = getCurrentBranchName();
  if (!branch || branch === DEFAULT_BRANCH || sentBranch !== branch) return;
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
    let sentBranch = branch;
    let promise;
    if (!isInternal) {
      promise = origFetch(input, init);
    } else {
      const opts = Object.assign({}, init || {});
      const headers = new Headers(opts.headers || {});
      if (branch !== DEFAULT_BRANCH && !headers.has(BRANCH_HEADER)) {
        headers.set(BRANCH_HEADER, branch);
      }
      sentBranch = headers.get(BRANCH_HEADER) || DEFAULT_BRANCH;
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
          maybeRecoverFromDeletedBranch(resp, sentBranch);
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

window.getCurrentBranchName = getCurrentBranchName;
window.isOnDefaultBranch = isOnDefaultBranch;
window.switchToBranch = switchToBranch;
