// Editor Sidebar — namespace tree + entity list + filter.
//
// Renders the left sidebar panel: a collapsible tree of namespaces,
// each containing fn entries with click→navigate. The filter input
// at the top hides namespaces / fns that don't match. Expanded-ns
// state persists across re-renders.
//
// Globals consumed: graphData, lookups, navigateToFn, filterText,
// selectedFnId, sidebarCollapsed.

// ============================================================================
// SIDEBAR / ENTITY LIST
// ============================================================================

// Expanded namespace state (persisted across updateEntityList calls).
// By default all namespaces are collapsed; only explicitly opened ones are expanded.
// graph-first-exception: same rationale as editor-namespace-picker — the
// tree/leaf/search DATA is server-fed (?scope=tree/namespace/search) and
// only walked + rendered here; the render is interactive (lazy expand,
// debounced search, per-row action gating) and re-paints on client-only
// state, so a server-rendered partial would refetch on every toggle.
const expandedNamespaces = new Set();

// Current search/filter text (raw; server search is case-insensitive).
let searchFilter = '';
// Server-side search state. The sidebar holds no full-fns mirror
// to filter client-side; typing in the box hits ?scope=search. `_searchResults`
// is null while a query is in flight (or no query active), else the matched
// light fn rows; `_searchSeq` drops stale responses that arrive out of order.
let _searchResults = null;
let _searchTruncated = false;
let _searchSeq = 0;
let _searchDebounce = null;

// Append a lazy-loading admin section (Grants / Users / Packages) and process
// it with HTMX. The section's `.ns-children` carries hx-get + hx-trigger="load";
// that trigger ONLY fires when htmx.process runs on a node already CONNECTED to
// the document — processing a detached node marks it processed but never fires
// load. So the section builders return an unprocessed node and we process here,
// after appendChild. `section` may be null (builder gated it out) → no-op.
// Built once, re-attached thereafter.
//
// renderSidebar wipes the list (`list.innerHTML = ''`) and rebuilt these
// sections from scratch on EVERY render. Each rebuild threw away a panel that
// had already loaded, put "Loading…" back, and made htmx re-fetch the partial —
// three times on a cold page load, measured. Besides the wasted round-trips it
// left a window where the panel showed "Loading…" although it had loaded
// moments earlier, which is exactly the window edit-packages-panel kept landing
// in. The test was right and the sidebar was wrong.
//
// So build each section once and re-attach the SAME node afterwards. htmx's
// `hx-trigger="load"` fires from `process()`, which we then call only on the
// first mount — a re-attached node keeps its loaded content and issues no
// request. The cache lives as long as the page: login, logout and branch
// switches all reload (editor-auth / switchToBranch), which is what clears it.
const _adminSections = new Map();
const _adminNavBtns = new Map();

// Human labels for the ops sections. Each mounts as ONE titled pane selected
// from a section list on the left — a clean settings layout, not a grid of
// look-alike tiles.
// graph-first-exception: pure nav chrome for the CLIENT-side section list —
// the pane bodies themselves are lazy hx-get server partials; only their menu
// entries live here, next to the mount wiring that owns which sections exist.
const OP_SECTION_LABELS = {
  grants: 'Grants', users: 'Members', roles: 'Roles', orgs: 'Organizations',
  packages: 'Packages', stats: 'Monitoring',
  errors: 'Errors', 'type-errors': 'Type errors', 'platform-access': 'Platform access',
  assets: 'Assets', tests: 'Tests', debug: 'Debug', executors: 'Executor',
};

// Show one section's pane on a surface and mark its nav item; hide the rest.
function activateOpSection(nav, pane, key) {
  [...pane.children].forEach((s) => { s.hidden = s.dataset.section !== key; });
  [...nav.children].forEach((b) => {
    b.setAttribute('aria-current', b.dataset.section === key ? 'page' : 'false');
  });
}

// Mount an ops/admin section as a selectable pane. `nav` is the section-list
// container (null → legacy fallback: append the card inline, always visible).
// The pane carries exactly ONE heading (the card title); the build's own
// header label is dropped since the nav already names the section.
function mountAdminSection(pane, nav, key, build) {
  let section = _adminSections.get(key);
  let navBtn = _adminNavBtns.get(key);
  if (!section) {
    const built = build();
    if (!built) return;            // not applicable (not an admin, etc.)
    const ownHdr = built.querySelector(':scope > .ns-header');
    if (ownHdr) ownHdr.remove();
    section = document.createElement('section');
    section.className = 'gd-op-card';
    section.dataset.section = key;
    const h = document.createElement('h2');
    h.className = 'gd-op-card-title';
    h.textContent = OP_SECTION_LABELS[key] || key;
    section.appendChild(h);
    section.appendChild(built);
    _adminSections.set(key, section);
    navBtn = document.createElement('button');
    navBtn.type = 'button';
    navBtn.className = 'gd-op-nav-btn';
    navBtn.dataset.section = key;
    navBtn.textContent = OP_SECTION_LABELS[key] || key;
    navBtn.addEventListener('click', () => activateOpSection(nav, pane, key));
    _adminNavBtns.set(key, navBtn);
  }
  if (nav) nav.appendChild(navBtn);
  else section.hidden = false;     // legacy fallback — no section list, show all
  pane.appendChild(section);
  // process() fires hx-trigger="load" and must run on a CONNECTED node.
  if (window.htmx && typeof window.htmx.process === 'function') window.htmx.process(section);
}

// The LIVE-diagnostics panels — type-errors + runtime errors + tests + debug —
// show state that changes as the user edits, but their mounted node is cached
// (built once, re-attached; htmx does NOT re-fire `hx-trigger="load"` on an
// already-processed node). So a diagnostic recorded AFTER the panel's first
// load never appeared: the type-errors panel sat empty while the fn card
// carried the ⚠ badge (the badge is re-fetched per navigation, the cached
// panel was not). Re-fetch a live panel each time its surface is SHOWN — that
// is exactly when the user is looking at it and wants current data. Rebuild
// the lazy-load child from the section's builder (fresh, UNPROCESSED) and
// htmx.process it so the hx-get re-fires. The static admin panels
// (grants / users / …) are untouched — their data doesn't drift within a
// session.
function reloadLiveSections(hostId, builders) {
  const host = document.getElementById(hostId);
  if (!host || !window.htmx || typeof window.htmx.process !== 'function') return;
  Object.keys(builders).forEach((key) => {
    const build = builders[key];
    if (!build) return;
    const section = host.querySelector(':scope > section[data-section="' + key + '"]');
    if (!section) return;
    const built = build();          // fresh shell carrying an unprocessed hx-get child
    if (!built) return;
    const fresh = built.querySelector('.ns-children') || built;
    const old = section.querySelector('.ns-children');
    if (old) old.replaceWith(fresh);
    else section.appendChild(fresh);
    window.htmx.process(fresh);      // fires hx-trigger="load" → current diagnostics
  });
}

// Operate's only live panel left is Assets (override rows change as the user
// saves) — the code diagnostics live in the Build drawer now. Exposed for
// editor-shell.js's gdRenderOperate.
function reloadDynamicOpsSections() {
  reloadLiveSections('gd-operate-panels', {
    assets: typeof buildAssetsSection === 'function' ? buildAssetsSection : null,
  });
}
window.reloadDynamicOpsSections = reloadDynamicOpsSections;

// The diagnostics-drawer sections — re-fetched each time the drawer OPENS
// (editor-diagnostics.js). All four are live: diagnostics land as the user
// edits, tests drift as runs/auto-runs land, the debug trap arms/fires/expires.
function reloadDiagnosticsSections() {
  reloadLiveSections('gd-diag-panels', {
    'type-errors': typeof buildTypeErrorsSection === 'function' ? buildTypeErrorsSection : null,
    errors: typeof buildErrorsSection === 'function' ? buildErrorsSection : null,
    tests: typeof buildTestsSection === 'function' ? buildTestsSection : null,
    debug: typeof buildDebugSection === 'function' ? buildDebugSection : null,
  });
}
window.reloadDiagnosticsSections = reloadDiagnosticsSections;

// Packages GOVERNANCE (packages spec §4) — the Organization surface's
// read-mostly view: who may publish (a static capability note; the holders
// are managed in Roles/Grants), the org's published catalog and an install
// audit, both server-rendered by /partials/packages-governance. NOT an
// install surface — install lives on the Build packages chip.
function buildPackagesGovernanceSection() {
  if (!isAuthenticated()) return null;
  // Optional registry package absent → no /api/packages/* in window.API →
  // no governance section (probe, never a name).
  if (!window.API?.api_packages_installed) return null;
  // Pure mount shell — the who-may-publish note ships INSIDE the partial
  // now (server branches on the same tenancy fact via :tenancy-active?).
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-packages-governance';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/packages-governance" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  return wrap;
}

// ── Per-kind visibility ────────────────────────────────────────────────
// Every entity is classified into EXACTLY ONE kind by priority
// secrets > types > services > fn (a service is structurally a normal fn and a
// secret is a fn too, so the priority makes each show under a single toggle).
// Each kind has an eye toggle in #kind-filters; hiding a kind drops those
// entities plus any namespace left with nothing visible — EXCEPT the currently
// selected fn, which fnKindVisible always keeps (so a deep-link can't collapse
// its namespace). State persists in localStorage.
const TYPE_ROLES = new Set(['refinement', 'list', 'union', 'variant',
                            'record', 'fn-type', 'primitive']);
// The LENS — focus-semantics kind filter (replaces the old hide-semantics
// eyes). Empty set = "All" (everything shows, rows carry kind markers);
// non-empty = show ONLY fns matching a selected kind. One click focuses a
// kind, a second click on it (or on "All") returns to everything; clicking
// further chips adds them to the selection (services+apps together, etc.).
// Tree structure, expansion and scroll position are untouched — the lens is
// the same client-side row filter the eyes used, with the semantics the
// actual task ("show me all my services / apps, let me click through them")
// needs.
const LENS_STORAGE = 'graphden.sidebarLens';

function loadLens() {
  try {
    const raw = localStorage.getItem(LENS_STORAGE);
    if (raw) return new Set(JSON.parse(raw));
  } catch (_) { /* private-mode / corrupt → All */ }
  return new Set();
}
const lensKinds = loadLens();
// The last render's namespace-less (root/primitives) fn list — applyLensVisibility
// re-renders that small bucket in place on a lens flip.
let _lastRootFns = null;
// Namespaces whose "internal N" group the user opened this session —
// survives tree rebuilds (which happen on every lens flip / lazy load).
const _internalOpenNs = new Set();
// The last-built namespace tree root. Incremental expand/refresh must read the
// CURRENT node (fresh `.fns`) from here, never a node captured at an earlier
// render — a lazy load appends to `graphData.fns`, so a stale captured node's
// `.fns` stays frozen-empty and its group would render blank. Rebuilt on every
// updateEntityList AND after each lazy load lands (see loadNamespaceFns .then).
let _lastTree = null;

// Walk `_lastTree` to the node at `nsPath` ("a.b.c"), or null. Cheap
// (path-depth Map lookups); the O(loaded-fns) tree build itself is done once
// per render / per lazy load, not per walk.
function treeNodeAt(nsPath) {
  let node = _lastTree;
  if (!node || !nsPath) return null;
  for (const part of nsPath.split('.')) {
    node = node.children?.get(part);
    if (!node) return null;
  }
  return node;
}

function saveLens() {
  try { localStorage.setItem(LENS_STORAGE, JSON.stringify([...lensKinds])); }
  catch (_) { /* best-effort */ }
}

// EVERY kind a fn-row belongs to. A fn can be several at once (an app's
// handler may also be a service), so membership is a set — the lens matches
// on ANY, and the row renders a marker per kind.
// graph-first-exception: the kind set drives the interactive lens filter —
// an in-place `hidden` flip over ~hundreds of already-rendered rows with no
// refetch (the workspace-popover class of client-cache-driven state). It
// combines one server field (role) with three server-primed caches
// (services / app-routes / secrets), so no reasoning is re-derived — only
// membership is assembled where the flip happens.
function fnKindSet(fn) {
  const kinds = new Set();
  if (typeof isSecretFn === 'function' && isSecretFn(fn)) kinds.add('secrets');
  const role = (fn.role || '').replace(/^:/, '');
  if (TYPE_ROLES.has(role)) kinds.add('types');
  if (typeof getServiceForFnId === 'function' && getServiceForFnId(fn.id)) kinds.add('services');
  if (typeof getAppRoutesForFnId === 'function' && getAppRoutesForFnId(fn.id).length > 0) kinds.add('apps');
  if (typeof isTestFn === 'function' && isTestFn(fn)) kinds.add('tests');
  if (kinds.size === 0) kinds.add('fn');
  return kinds;
}

// The lens hides a fn's ROW — but NEVER the fn the user is currently looking
// at. The selected fn always shows, so opening it by link can't leave its
// namespace empty-and-collapsed (the "openable ⟺ in the menu" invariant).
// Without this, deep-linking a service-fn — often the only leaf loaded in its
// namespace, since the sibling non-service fns load lazily on expand — hid it
// AND dropped the whole namespace via nodeShouldShow.
function fnKindVisible(fn) {
  if (fn && typeof selectedFnId !== 'undefined' && fn.id === selectedFnId) return true;
  if (lensKinds.size === 0) return true;
  const kinds = fnKindSet(fn);
  for (const k of lensKinds) {
    if (kinds.has(k)) return true;
  }
  return false;
}

// Does the namespace `nsId` hold at least one row of `kind`, WITHOUT
// its leaves being loaded? Namespace leaves lazy-load on expand, so an
// active lens can't classify unloaded rows — these per-kind signals
// stand in:
//   fn / types — the `:tree` counts payload (nsFnCounts / nsTypeCounts)
//   services   — /api/services rows' `namespace-id` (serviceNsIds)
//   apps       — /api/orgs/apps rows' `handler-namespace-id` (appRouteNsIds)
//   secrets    — /api/secrets rows' `namespace-id` (secretNsIds)
// `tests` is absent on purpose — test-ness is a namespace-PATH property
// with its own rule in nodeShouldShow. `nsId === undefined` → false;
// null is the (primitives) bucket and is a valid key.
function nsHoldsLensKind(kind, nsId, nsPath) {
  if (nsId === undefined) return false;
  switch (kind) {
    case 'types':
      return (lookups?.nsTypeCounts?.get(nsId) || 0) > 0;
    case 'fn':
      // Loaded test-ns rows classify as the `tests` kind, not `fn` —
      // keep the fn lens from surfacing (then re-hiding) test namespaces.
      if (nsPath && typeof isTestNsPath === 'function' && isTestNsPath(nsPath)) return false;
      return (lookups?.nsFnCounts?.get(nsId) || 0) > 0;
    case 'services':
      return typeof serviceNsIds === 'function' && serviceNsIds().has(nsId);
    case 'apps':
      return typeof appRouteNsIds === 'function' && appRouteNsIds().has(nsId);
    case 'secrets':
      return typeof secretNsIds === 'function' && secretNsIds().has(nsId);
    default:
      return false;
  }
}

function nodeHasActiveCreate(node) {
  if (node.nsId && typeof window.hasActiveCreateIn === 'function'
      && window.hasActiveCreateIn(node.nsId)) return true;
  for (const child of node.children.values()) {
    if (nodeHasActiveCreate(child)) return true;
  }
  return false;
}

// Entities under `node`, ignoring the kind filters. This is what tells
// "hidden because a filter took everything away" apart from "empty in
// the first place" — the two must not render the same.
function nodeEntityCount(node) {
  let n = node.fns.length;
  for (const child of node.children.values()) n += nodeEntityCount(child);
  return n;
}

// A namespace is shown when it still has something to show: a visible
// entity of its own, a child that is itself shown, or an in-progress
// inline-create (so the create row is never hidden out from under the
// user mid-type).
//
// Otherwise it is hidden ONLY if a filter is what emptied it. A namespace
// that holds nothing at all stays visible: `buildNsTree` deliberately
// pre-creates a node for every declared namespace so a just-created one
// appears immediately, and hiding it would make it impossible to put the
// first entity into it — you would create a namespace and watch it vanish.
function nodeShouldShow(node, searchMode) {
  // In search mode the tree is built from the server's matches only, so a
  // node shows iff it (or a descendant) actually holds a match. The
  // "empty → keep visible" rule below would otherwise surface every
  // namespace during a search.
  if (searchMode) return nodeEntityCount(node) > 0;
  if (node.fns.some(fnKindVisible)) return true;
  for (const child of node.children.values()) {
    if (nodeShouldShow(child, searchMode)) return true;
  }
  if (nodeHasActiveCreate(node)) return true;
  // Under an ACTIVE lens (focus on specific kinds), do NOT optimistically show
  // an unloaded/empty namespace. That rule exists so a just-created / not-yet-
  // fetched namespace stays visible in the normal (All) view — but under a
  // "services"/"secrets" focus it floods the tree with namespaces that hold
  // none of the focused kind, which then vanish the moment you expand them.
  // Focus should read as a crisp match list; keep the fallback only for All.
  // Exception — the tests lens: test-ness is knowable from the NAMESPACE
  // path alone, and test-ns leaves lazy-load on expand, so an unloaded
  // `tests` namespace must stay visible (else the lens shows nothing
  // until every ns was expanded once).
  if (lensKinds.has('tests') && node.path
      && typeof isTestNsPath === 'function' && isTestNsPath(node.path)) {
    return true;
  }
  // Same exception for every other kind: kind-presence of an UNLOADED
  // namespace is knowable without its leaves (see nsHoldsLensKind) —
  // without this, each lens showed only rows that happened to be loaded
  // (the types lens famously showed just the (primitives) bucket).
  for (const k of lensKinds) {
    if (nsHoldsLensKind(k, node.nsId, node.path)) return true;
  }
  if (lensKinds.size > 0) return false;
  // Genuinely empty (nothing loaded here) → keep visible: this covers both
  // a just-created empty namespace AND a collapsed namespace whose leaves
  // haven't been lazily fetched yet (they load on expand).
  return nodeEntityCount(node) === 0;
}

// Classification reads two caches via sync helpers (getServiceForFnId,
// isSecretFn/secret paths). Prime them once per graph load and re-render
// so the first paint is accurate.
//
// A prime lands on the NETWORK's schedule, not the user's, so its
// re-render can arrive at any moment — including mid-interaction. An open
// inline row (create OR rename) is user-owned, transient DOM: it holds the
// text being typed, the server's rejection message, and the very button
// the user is about to click. A full-tree repaint rebuilds the tree from
// scratch, so it wipes that state and detaches those nodes — a click then
// lands on an element that is no longer in the document.
//
// The guard is on the DOM rather than on a state flag on purpose: `create`
// and `rename` both mount `buildInlineInputRow`, and enumerating the
// transient states by name is how the rename case got missed the first
// time. One row, one check, and any future inline editor is covered.
//
// Nothing is lost by skipping: the interaction ends in initGraph → a fresh
// graphData → the prime re-fires and paints the classification it loaded.
function repaintAfterPrime() {
  if (document.querySelector('#entity-list .inline-input-row')) return;
  updateEntityList(graphData);
}
let _serviceCachePrimed = false;
function primeServiceCacheOnce() {
  if (_serviceCachePrimed || typeof loadAllServiceFnIds !== 'function') return;
  // Anonymous visitors get 401 on /api/services and have no service data
  // to classify against — skip the prime so we don't fire a redundant
  // (already-401'd by the badge eager-load) request.
  if (typeof isAuthenticated === 'function' && !isAuthenticated()) return;
  _serviceCachePrimed = true;
  loadAllServiceFnIds().then(repaintAfterPrime);
}
let _appsCachePrimed = false;
function primeAppsCacheOnce() {
  // Same shape as the service prime: the apps classification (▣ markers,
  // the apps lens, the chip count) reads the app-routes cache sync'ly.
  // No tenancy API on this deployment → nothing to prime (the chip hides).
  if (_appsCachePrimed || typeof refreshAppRoutesCache !== 'function') return;
  if (!(window.API && API.api_orgs_apps)) return;
  if (typeof isAuthenticated === 'function' && !isAuthenticated()) return;
  _appsCachePrimed = true;
  refreshAppRoutesCache().then(repaintAfterPrime);
}
let _testStatusesPrimedGraph = null;
function primeTestStatusesOnce() {
  // Same shape as the secrets prime: the ✓/✗ test dots + the `tests`
  // chip count read the status cache sync'ly; re-prime per graph load
  // so post-run/auto-run statuses land on the next refresh.
  if (typeof isAuthenticated !== 'function' || !isAuthenticated()) return;
  if (_testStatusesPrimedGraph === graphData || typeof loadTestStatuses !== 'function') return;
  if (!(window.API && API.api_tests_status)) return;
  _testStatusesPrimedGraph = graphData;
  loadTestStatuses().then(repaintAfterPrime);
}
let _secretsPrimedGraph = null;
function primeSecretsOnce() {
  if (typeof isAuthenticated !== 'function' || !isAuthenticated()) return;
  if (_secretsPrimedGraph === graphData || typeof loadSecrets !== 'function') return;
  _secretsPrimedGraph = graphData;
  loadSecrets().then(repaintAfterPrime);
}

/**
 * Build a tree from fns grouped by namespace path.
 * Returns: { children: Map<string, subtree>, fns: [fn, ...] }
 */
function buildNsTree(data) {
  const root = { children: new Map(), fns: [], description: null, nsId: null };

  // Build {ns-path → ns-entity} from the namespace entities so the
  // rendered tree nodes can carry both their description tooltip AND
  // their ns-id (needed by the per-namespace `+` button to set
  // `parent-id` when creating sub-entities).
  const nsByPath = new Map();
  (data.namespaces || []).forEach(ns => {
    const path = (lookups.nsPathMap?.get(ns.id)) || ns.name;
    if (path) nsByPath.set(path, ns);
  });

  // Pre-create tree nodes for every declared namespace, even ones that
  // have no fns yet — newly-created empty namespaces should show up in
  // the sidebar immediately, not only after their first fn is added.
  nsByPath.forEach((ns, path) => {
    const parts = path.split('.');
    let node = root;
    let cumulativePath = '';
    for (const part of parts) {
      cumulativePath = cumulativePath ? cumulativePath + '.' + part : part;
      if (!node.children.has(part)) {
        const entry = nsByPath.get(cumulativePath);
        node.children.set(part, {
          children: new Map(),
          fns: [],
          path: cumulativePath,
          description: entry ? entry.description : null,
          nsId: entry ? entry.id : null
        });
      }
      node = node.children.get(part);
    }
  });

  (data.fns || []).forEach(fn => {
    if (!fn.name) return; // skip anonymous/local fns
    const qname = getQualifiedFnName(fn);
    const parts = qname.split('.');
    const fnName = parts.pop();
    let node = root;
    let cumulativePath = '';
    for (const part of parts) {
      cumulativePath = cumulativePath ? cumulativePath + '.' + part : part;
      if (!node.children.has(part)) {
        const entry = nsByPath.get(cumulativePath);
        node.children.set(part, {
          children: new Map(),
          fns: [],
          path: cumulativePath,
          description: entry ? entry.description : null,
          nsId: entry ? entry.id : null
        });
      }
      node = node.children.get(part);
    }
    node.fns.push({ ...fn, displayName: displayLabel(fnName), rawName: fnName });
  });

  return root;
}

/**
 * Render a namespace tree node recursively into the container.
 */
// `level` is the ARIA depth (1-based). The tree is rendered FLAT — a
// namespace header and its `.ns-children` are siblings, not parent and
// child — so depth cannot be inferred from the DOM and has to be stated.
// Optional per-row DETAIL markers — informational only, orthogonal to
// the kind LENS (which decides visibility). Persisted per browser;
// each detail is individually toggleable so the tree never carries
// more than the user asked for. Today: `fx` — mark fns whose
// execution has an effect footprint (from the /api/types registry).
let treeDetails = { fx: false };
try {
  const raw = JSON.parse(localStorage.getItem('graphden.treeDetails') || 'null');
  if (raw && typeof raw === 'object') treeDetails = Object.assign(treeDetails, raw);
} catch (_) { /* malformed pref — defaults */ }

function toggleTreeDetail(key) {
  treeDetails[key] = !treeDetails[key];
  try { localStorage.setItem('graphden.treeDetails', JSON.stringify(treeDetails)); } catch (_) {}
  syncKindFilterBar();
  // Detail markers are baked into the rows at build time — rebuild.
  updateEntityList(graphData);
  announceLens('Effect markers ' + (treeDetails.fx ? 'on' : 'off'));
}
window.toggleTreeDetail = toggleTreeDetail;

function buildFnItem(fn, level = 1) {
  const item = document.createElement('div');
  item.className = 'entity-item';
  if (fn.id === selectedFnId) item.className += ' selected';
  item.setAttribute('role', 'treeitem');
  item.setAttribute('aria-level', String(level));
  item.setAttribute('aria-selected', fn.id === selectedFnId ? 'true' : 'false');
  // Roving tabindex: exactly one node in the tree is tabbable at a time,
  // and editor-tree-keys.js decides which.
  item.setAttribute('tabindex', '-1');
  const isSecret = typeof isSecretFn === 'function' && isSecretFn(fn);
  if (isSecret) item.className += ' entity-secret';
  item.dataset.fnId = fn.id;

  if (isSecret) {
    const lock = document.createElement('span');
    lock.className = 'secret-lock-icon';
    lock.textContent = '🔒';
    lock.title = 'Secret — value lives in the vault, never in the graph DB';
    item.appendChild(lock);
  }

  // Kind markers — so the mixed (All-lens) tree stays legible: a row says
  // WHAT it is at a glance (the lens chips use the same glyphs). Secrets
  // keep their 🔒 above; plain fns carry no marker (they're the default).
  const kinds = fnKindSet(fn);
  if (kinds.has('services')) {
    const svc = (typeof getServiceForFnId === 'function') ? getServiceForFnId(fn.id) : null;
    const state = (typeof serviceBadgeState === 'function') ? serviceBadgeState(svc) : null;
    const m = document.createElement('span');
    m.className = 'fn-kind-marker kind-marker-service' + (state ? ' svc-' + state : '');
    m.textContent = '⚙';
    m.title = 'Service' + (state ? ' — ' + state : '');
    item.appendChild(m);
  }
  if (kinds.has('apps')) {
    const routes = (typeof getAppRoutesForFnId === 'function') ? getAppRoutesForFnId(fn.id) : [];
    const hosts = routes.map((r) => (typeof appRouteHost === 'function') ? appRouteHost(r) : r.label)
      .filter(Boolean).join(', ');
    const m = document.createElement('span');
    m.className = 'fn-kind-marker kind-marker-app';
    m.textContent = '▣';
    m.title = hosts ? 'App — served at ' + hosts : 'App handler';
    item.appendChild(m);
  }
  if (kinds.has('types')) {
    const m = document.createElement('span');
    m.className = 'fn-kind-marker kind-marker-type';
    m.textContent = 'T';
    m.title = 'Type';
    item.appendChild(m);
  }
  if (kinds.has('tests')) {
    // Status dot: latest execution of the fn's CURRENT version —
    // passed / failed / no-run-yet (stale). Reads the primed
    // /api/tests/status cache (editor-tests.js).
    const st = (typeof getTestStatusForFnId === 'function') ? getTestStatusForFnId(fn.id) : null;
    const status = st?.status || null;
    const m = document.createElement('span');
    let cls = 'test-stale';
    let label = 'Test — not run for the current version';
    if (status === 'succeeded') { cls = 'test-passed'; label = 'Test — passed'; }
    else if (status === 'failed') { cls = 'test-failed'; label = 'Test — failed' + (st?.error ? ': ' + st.error : ''); }
    else if (status) { label = 'Test — ' + status; }
    m.className = 'fn-kind-marker kind-marker-test ' + cls;
    m.textContent = '●';
    m.title = label;
    item.appendChild(m);
  }

  // fx detail — the effect footprint, the strongest "what does running
  // this touch" signal the registry has. Off by default (most platform
  // web fns are effectful — a always-on marker would wallpaper the
  // tree); the `fx` chip next to the kind lens flips it.
  if (treeDetails.fx && typeof richTypes !== 'undefined' && fn.name) {
    const effs = richTypes?.[fn.name]?.effects;
    if (Array.isArray(effs) && effs.length) {
      const m = document.createElement('span');
      m.className = 'fn-kind-marker kind-marker-fx';
      m.textContent = 'fx';
      m.title = 'Effects: ' + effs.join(', ');
      item.appendChild(m);
    }
  }

  const nameSpan = document.createElement('span');
  nameSpan.className = 'name';
  nameSpan.textContent = fn.displayName;
  item.appendChild(nameSpan);

  // Secret rows show their vault path (parity with the old Secrets
  // section). `secretRecordForFn` reads the primed /api/secrets list.
  if (isSecret && typeof secretRecordForFn === 'function') {
    const rec = secretRecordForFn(fn.id);
    if (rec?.path) {
      const pathSpan = document.createElement('span');
      pathSpan.className = 'secret-path';
      pathSpan.textContent = rec.path;
      item.appendChild(pathSpan);
    }
  }

  // Right-edge action group — same shape as `.ns-row-actions`. Order:
  // ✎ rename (hover-only), ↗ open-in-new-tab (hover-only), `i`
  // description (hover-only). fns don't have a `+` button — they're
  // not containers.
  const actions = document.createElement('span');
  actions.className = 'ns-row-actions';
  if (typeof buildFnRowButtons === 'function') {
    buildFnRowButtons(actions, fn.id, fn.displayName);
  }
  // ↗ — open this fn's graph in a new tab without losing the current
  // view. Same helper as fn-overlay rows, but rendered inline (no
  // pinRight) so it sits in the .ns-row-actions flex group.
  const fullFn = lookups?.fnMap?.get(fn.id);
  const openInNew = (typeof createOpenInNewTabButton === 'function')
    ? createOpenInNewTabButton(fullFn || fn) : null;
  if (openInNew) {
    openInNew.classList.add('sidebar-action');
    actions.appendChild(openInNew);
  }
  // Always render the badge so the user has an entry point to ADD a
  // description to entities that don't have one yet.
  const desc = createDescriptionBadge(fn.description, {
    name: fn.displayName,
    namespace: getFnNamespace(lookups?.fnMap?.get(fn.id)),
    entityType: 'fn',
    entityId: fn.id
  });
  if (desc) actions.appendChild(desc);
  // Secret rows get Rotate + Delete. Auth-gated inside the helper.
  if (isSecret && typeof buildSecretRowActions === 'function') {
    buildSecretRowActions(actions, fn);
  }
  if (actions.children.length > 0) item.appendChild(actions);

  item.onclick = () => selectFn(fn.id);
  return item;
}


function renderNsNode(container, name, node, path, searchMode) {
  const nsPath = path ? path + '.' + name : name;
  // Personal workspace hide (redesign 2026-08): a namespace the user removed
  // from their view is structurally skipped at every depth (so hiding a
  // sub-namespace inside an in-scope project works too). Search spans all.
  if (!searchMode && typeof window.graphdenIsHidden === 'function'
      && window.graphdenIsHidden(nsPath)) {
    return;
  }
  // Search mode force-expands every matched branch so results are visible
  // without the user drilling in.
  const isCollapsed = searchMode ? false : !expandedNamespaces.has(nsPath);

  // Namespace header
  const header = document.createElement('div');
  header.className = 'ns-header';
  // Workspace highlight (§4.4): emphasise the namespaces the user works in.
  // No-op without the addon (no workspace header → graphdenInWorkspace false).
  if (typeof window.graphdenInWorkspace === 'function' && window.graphdenInWorkspace(nsPath)) {
    header.classList.add('ns-in-workspace');
  }
  header.dataset.nsPath = nsPath;

  // Lens visibility is a HIDDEN overlay, not a structural filter: the tree is
  // built lens-INDEPENDENTLY (every loaded node, regardless of the active
  // lens), so a lens-chip toggle is a cheap in-place `hidden` flip
  // (`applyLensVisibility`) instead of a full teardown+rebuild. Parity with a
  // full render holds by construction \u2014 both decide visibility with the SAME
  // `nodeShouldShow`; only the `hidden` bit differs. Store the node so the flip
  // can re-run `nodeShouldShow` without re-deriving the tree.
  const nodeVisible = nodeShouldShow(node, searchMode);
  header._treeNode = node;
  header.hidden = !nodeVisible;
  header.setAttribute('role', 'treeitem');
  header.setAttribute('aria-level', String(path ? path.split('.').length + 1 : 1));
  header.setAttribute('aria-expanded', isCollapsed ? 'false' : 'true');
  header.setAttribute('tabindex', '-1');

  const arrow = document.createElement('span');
  arrow.className = 'ns-arrow' + (isCollapsed ? ' collapsed' : '');
  arrow.textContent = isCollapsed ? '\u25B6' : '\u25BC';
  header.appendChild(arrow);

  const label = document.createElement('span');
  label.className = 'ns-label';
  label.textContent = name;
  header.appendChild(label);
  // Type-error chip (error-tolerance Phase 3) — server-computed
  // per-namespace count of recorded type diagnostics on the current
  // branch (`:type-error-count` on the `:tree` counts payload).
  const nsTypeErrs = node?.nsId != null
    ? (lookups?.nsTypeErrors?.get(node.nsId) || 0) : 0;
  if (nsTypeErrs > 0) {
    const chip = document.createElement('span');
    chip.className = 'ns-type-error-chip';
    chip.textContent = '⚠ ' + nsTypeErrs;
    chip.title = nsTypeErrs + ' type error' + (nsTypeErrs === 1 ? '' : 's')
      + ' in this namespace — see the Type errors panel';
    header.appendChild(chip);
  }
  // All three right-edge icons live in one group. Order:
  //   ✎ (rename, hover-only)  +  + (create-child, hover-only)  +  i (description, always)
  // The always-visible `i` sits LAST so the empty slots left by the
  // hover-only buttons (when not hovered) collapse to nothing visible
  // — otherwise the row would look like there's a useless gap to the
  // left of the `i`.
  const actions = document.createElement('span');
  actions.className = 'ns-row-actions';
  if (node?.nsId && typeof buildNsRowButtons === 'function') {
    buildNsRowButtons(actions, node.nsId, nsPath);
  }
  if (node?.nsId) {
    const desc = createDescriptionBadge(node.description, {
      name: nsPath,
      entityType: 'ns',
      entityId: node.nsId
    });
    if (desc) actions.appendChild(desc);
  }
  if (actions.children.length > 0) header.appendChild(actions);

  header.onclick = (e) => {
    e.stopPropagation();
    // Search mode is a server-fed, force-expanded tree — keep the full rebuild
    // there. Non-search: toggle just THIS namespace's subtree in place (the
    // sidebar's other big rebuild cost, ~800ms at scale) instead of tearing
    // down + rebuilding the whole tree.
    if (searchFilter) {
      if (expandedNamespaces.has(nsPath)) expandedNamespaces.delete(nsPath);
      else expandedNamespaces.add(nsPath);
      updateEntityList(graphData);
      return;
    }
    if (expandedNamespaces.has(nsPath)) {
      // Collapse: drop this namespace's children. Its own (and its ancestors')
      // visibility is unchanged — the node's tree data is the same — so no
      // resync is needed.
      expandedNamespaces.delete(nsPath);
      const cg = findNsChildGroup(nsPath);
      if (cg) cg.remove();
      arrow.classList.add('collapsed');
      arrow.textContent = '▶';
      // The arrow is the sighted cue; aria-expanded is the other half of it.
      // Setting it only at build time (as this did) leaves a screen reader —
      // and the keyboard navigation, which reads this attribute to decide
      // what Left/Right mean — describing the opposite of what is on screen.
      header.setAttribute('aria-expanded', 'false');
    } else {
      // Expand: build ONLY this subtree + insert after the header. The built
      // rows/namespaces set their own `hidden` overlay, so no global resync is
      // needed here — a lazy load (buildNsChildGroup) does its own resync.
      expandedNamespaces.add(nsPath);
      arrow.classList.remove('collapsed');
      arrow.textContent = '▼';
      header.setAttribute('aria-expanded', 'true');
      // Fresh node (current `.fns`), never the one captured when this header
      // was built — see refreshLoadedNamespace / treeNodeAt.
      header.after(buildNsChildGroup(treeNodeAt(nsPath) || node, nsPath, searchMode));
    }
  };

  container.appendChild(header);

  if (isCollapsed) return;

  container.appendChild(buildNsChildGroup(node, nsPath, searchMode));
}

// Build the `.ns-children` element for an expanded namespace (its child
// namespaces + own fn leaves), lens-INDEPENDENTLY with per-node `hidden`
// overlays. Shared by the initial render (renderNsNode) AND incremental expand
// (header.onclick), so the two can't diverge.
function buildNsChildGroup(node, nsPath, searchMode) {
  const childGroup = document.createElement('div');
  childGroup.className = 'ns-children';
  childGroup.dataset.nsChildren = nsPath;   // paired with the header
  childGroup.hidden = !nodeShouldShow(node, searchMode);

  const sortedChildren = [...node.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [childName, childNode] of sortedChildren) {
    // Non-search builds ALL children (lens is a `hidden` overlay); search keeps
    // the matched-only structural skip.
    if (searchMode && !nodeShouldShow(childNode, searchMode)) continue;
    renderNsNode(childGroup, childName, childNode, nsPath, searchMode);
  }

  // Own fn leaves — load lazily the first time this namespace opens.
  if (!searchMode && node.nsId != null
      && typeof isNamespaceLoaded === 'function' && !isNamespaceLoaded(node.nsId)) {
    const loading = document.createElement('div');
    loading.className = 'loading';
    loading.textContent = 'Loading…';
    childGroup.appendChild(loading);
    if (typeof loadNamespaceFns === 'function'
        && !(typeof isNamespaceLoading === 'function' && isNamespaceLoading(node.nsId))) {
      loadNamespaceFns(node.nsId)
        .then(() => refreshLoadedNamespace(nsPath, searchMode))
        .catch((err) => { console.error('loadNamespaceFns failed', err); });
    }
  } else {
    const sortedFns = [...node.fns].sort((a, b) => a.displayName.localeCompare(b.displayName));
    // INTERNAL rows — `_`-private fns and anonymous composites
    // (`anon-<hash>`) are implementation detail; listing them flat
    // drowned real fns (web.reitit showed 8 anon rows first). They
    // collapse under one "internal N" toggle per namespace. Search
    // stays flat (finding one by name must keep working), and the
    // group auto-opens when the SELECTED fn is inside it — the
    // "openable ⟺ visible in the menu" invariant.
    const isInternal = (fn) => (typeof fn.rawName === 'string' && fn.rawName.startsWith('_'))
                            || /^anon-/.test(fn.displayName || '');
    const publicFns = searchMode ? sortedFns : sortedFns.filter(f => !isInternal(f));
    const internalFns = searchMode ? [] : sortedFns.filter(isInternal);
    for (const fn of publicFns) {
      const el = buildFnItem(fn, nsPath.split('.').length + 1);
      el.hidden = !fnKindVisible(fn);
      childGroup.appendChild(el);
    }
    if (internalFns.length) {
      const open = _internalOpenNs.has(nsPath)
        || internalFns.some(f => typeof selectedFnId !== 'undefined' && f.id === selectedFnId);
      const toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.className = 'ns-internal-toggle';
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      toggle.title = 'Private (_-prefixed) and anonymous fns of this namespace';
      const holder = document.createElement('div');
      holder.className = 'ns-internal-group';
      for (const fn of internalFns) {
        const el = buildFnItem(fn, nsPath.split('.').length + 2);
        el.hidden = !fnKindVisible(fn);
        holder.appendChild(el);
      }
      toggle.addEventListener('click', (e) => {
        e.stopPropagation();
        const nowOpen = toggle.getAttribute('aria-expanded') !== 'true';
        toggle.setAttribute('aria-expanded', nowOpen ? 'true' : 'false');
        if (nowOpen) _internalOpenNs.add(nsPath); else _internalOpenNs.delete(nsPath);
        syncInternalToggle(toggle, holder);
      });
      // Label, visibility and the group's collapsed state all derive from
      // the rows' live hidden-state, through the same helper the lens uses.
      syncInternalToggle(toggle, holder);
      childGroup.appendChild(toggle);
      childGroup.appendChild(holder);
    }
  }

  // Active inline-create row rooted at THIS namespace.
  if (node?.nsId && typeof buildActiveCreateRow === 'function') {
    const createRow = buildActiveCreateRow(node.nsId, 0);
    if (createRow) childGroup.appendChild(createRow);
  }
  return childGroup;
}

// The `.ns-children` element for `nsPath`, or null when it's collapsed.
function findNsChildGroup(nsPath) {
  for (const cg of document.querySelectorAll('#entity-list .ns-children[data-ns-children]')) {
    if (cg.dataset.nsChildren === nsPath) return cg;
  }
  return null;
}

// After a namespace's fns lazy-load: rebuild JUST its child group in place
// (now populated) + resync visibility. The load appended to `graphData.fns`, so
// rebuild the tree first and re-derive the CURRENT node — a node captured at an
// earlier render still has a frozen-empty `.fns` and would rebuild blank. The
// fresh `.fns` also flips this namespace's / its ancestors' nodeShouldShow
// under an active lens.
function refreshLoadedNamespace(nsPath, searchMode) {
  const old = findNsChildGroup(nsPath);
  if (!old) return;   // collapsed again before the load landed
  _lastTree = buildNsTree(graphData);
  const node = treeNodeAt(nsPath);
  if (!node) return;
  old.replaceWith(buildNsChildGroup(node, nsPath, searchMode));
  applyLensVisibility();
}

/**
 * Search input handler — debounced server-side search (?scope=search);
 * the sidebar holds no full-fns list to filter client-side.
 */
function onSearchInput(value) {
  searchFilter = value.trim();
  if (!searchFilter) {
    _searchResults = null;
    _searchTruncated = false;
    _searchSeq++;              // cancel any in-flight query
    updateEntityList(graphData);
    announceSearch(null);
    return;
  }
  const seq = ++_searchSeq;
  clearTimeout(_searchDebounce);
  _searchDebounce = setTimeout(() => {
    if (typeof searchFns !== 'function') return;
    searchFns(searchFilter).then(({ fns, truncated }) => {
      if (seq !== _searchSeq) return;   // a newer keystroke superseded this
      _searchResults = fns;
      _searchTruncated = truncated;
      updateEntityList(graphData);
      // The server's own count, not a DOM tally — the tree only holds rows
      // for namespaces that happen to be expanded.
      announceSearch(searchFilter, fns.length, truncated);
    }).catch((err) => { console.error('sidebar search failed', err); });
  }, 180);
  // Repaint immediately so the box shows a "Searching…" state without
  // waiting for the debounce + round-trip.
  updateEntityList(graphData);
}

function clearSearch() {
  searchFilter = '';
  _searchResults = null;
  _searchTruncated = false;
  _searchSeq++;
  const input = document.getElementById('search-input');
  if (input) input.value = '';
  updateEntityList(graphData);
}


// Lens-chip click (from the #kind-filters buttons). "all" clears the lens;
// a kind chip toggles its membership; focusing down to the last selected
// kind and clicking it again also returns to All. Persists + re-renders.
// Exposed for the interactive tutorial: a step whose check names a fn the
// reader's lens is hiding would otherwise wait forever on a row they cannot
// see (editor-tour.js `_tourFnRowHidden`).
window.toggleKindLens = (kind) => toggleKind(kind);

function toggleKind(kind) {
  if (kind === 'all') lensKinds.clear();
  else if (lensKinds.has(kind)) lensKinds.delete(kind);
  else lensKinds.add(kind);
  saveLens();
  syncKindFilterBar();
  // A lens change is a VISIBILITY change only (the loaded set is identical), so
  // flip `hidden` over the existing DOM instead of tearing down + rebuilding the
  // whole tree — the sidebar's top scale cost (~2.4ms/row rebuilt). Parity with
  // a full rebuild is guaranteed: the tree is built lens-independently and both
  // paths decide visibility with the same nodeShouldShow / fnKindVisible. A
  // search box is active → fall back to a rebuild (the search tree is a
  // different, server-fed structure, not a lens overlay).
  if (searchFilter) updateEntityList(graphData);
  else applyLensVisibility();
  announceLens(lensKinds.size === 0
    ? 'All kinds'
    : 'Lens: ' + Array.from(lensKinds).join(', '));
}

/**
 * Say what just happened to the tree.
 *
 * A lens toggle or a search rewrites the list under a focus that has not
 * moved, so a screen reader is given no reason to re-read it — the change
 * is silent unless we say it.
 *
 * The wording is deliberate. Only EXPANDED namespaces have rows in the
 * DOM, so a count of visible rows is not a count of matching functions:
 * saying "1 function" with the tree collapsed would be worse than saying
 * nothing. Search knows its real total and reports it; the lens reports
 * rows, and says so.
 */
function announceLens(label) {
  if (typeof window.gdAnnounce !== 'function') return;
  const list = document.getElementById('entity-list');
  if (!list) return;
  // Count what the LENS governs, which is not the same as what is in the
  // DOM. A row can be out of sight for two unrelated reasons: the lens hid
  // it (`el.hidden`), or an ancestor is collapsed — a namespace, or the
  // "internal N" group (`offsetParent === null`). Only the first is the
  // lens's doing, so the denominator is "rows the lens could show":
  // currently visible, plus the ones it is hiding.
  const all = Array.from(list.querySelectorAll('.entity-item[data-fn-id]'));
  const rows = all.filter((el) => el.hidden || el.offsetParent !== null);
  const shown = rows.filter((el) => !el.hidden && el.offsetParent !== null).length;
  window.gdAnnounce(rows.length === shown
    ? label + ' — all ' + shown + ' shown'
    : label + ' — ' + shown + ' of ' + rows.length + ' shown');
}

function announceSearch(query, total, truncated) {
  if (typeof window.gdAnnounce !== 'function') return;
  if (!query) { window.gdAnnounce('Search cleared'); return; }
  const n = total || 0;
  window.gdAnnounce('Search "' + query + '" — ' + n + (n === 1 ? ' match' : ' matches')
                    + (truncated ? ' (showing the first page)' : ''));
}


// The "internal N" toggle advertises the private/anonymous rows behind it.
// N must be what the CURRENT lens would let through: the count captured at
// build time went stale the moment a lens flipped, so a types lens kept
// advertising "internal 658" over a group whose every row it was hiding.
// When the lens hides them all, the toggle and its group go with them —
// an affordance that can only ever reveal nothing is noise.
function syncInternalToggle(toggle, holder) {
  const visible = Array.from(holder.children)
    .filter((el) => el.classList?.contains('entity-item') && !el.hidden).length;
  const open = toggle.getAttribute('aria-expanded') === 'true';
  toggle.textContent = (open ? '▾ ' : '▸ ') + 'internal ' + visible;
  toggle.hidden = visible === 0;
  holder.hidden = visible === 0 || !open;
}

// In-place lens application: re-set the `hidden` overlay on the already-built
// tree DOM (fn rows via fnKindVisible, namespace header+children via
// nodeShouldShow over the node stored on the header), and re-render the small
// primitives bucket in place (its custom visibility + lazy-load make an
// overlay fiddly; re-running renderRootNode keeps it parity-correct). O(open
// rows) `hidden` writes, no teardown/rebuild.
function applyLensVisibility() {
  const list = document.getElementById('entity-list');
  if (!list) return;
  for (const el of list.querySelectorAll('.entity-item[data-fn-id]')) {
    const fn = lookups?.fnMap?.get(el.dataset.fnId);
    if (fn) el.hidden = !fnKindVisible(fn);
  }
  for (const toggle of list.querySelectorAll('.ns-internal-toggle')) {
    const holder = toggle.nextElementSibling;
    if (holder?.classList.contains('ns-internal-group')) {
      syncInternalToggle(toggle, holder);
    }
  }
  const cgByPath = new Map();
  for (const cg of list.querySelectorAll('.ns-children[data-ns-children]')) {
    cgByPath.set(cg.dataset.nsChildren, cg);
  }
  for (const header of list.querySelectorAll('.ns-header[data-ns-path]')) {
    const node = header._treeNode;
    const vis = node ? nodeShouldShow(node, false) : true;
    header.hidden = !vis;
    const cg = cgByPath.get(header.dataset.nsPath);
    if (cg) cg.hidden = !vis;
  }
  // Root/primitives bucket — re-render in place (small: ≤ the boot primitives).
  refreshRootNode();
  // Empty-lens hint. A lens with ZERO matching rows used to show either
  // a blank tree or only the force-shown selected fn — which read as
  // "this fn matches the lens" (a `router` row under the secrets lens
  // looked like router IS a secret). Say what's going on instead.
  let hint = list.querySelector('.lens-empty-hint');
  const lensSet = (typeof lensKinds !== 'undefined') ? lensKinds : new Set();
  let matches = 0;
  if (lensSet.size > 0 && lookups?.fnMap) {
    for (const fn of lookups.fnMap.values()) {
      const kinds = fnKindSet(fn);
      for (const k of lensSet) if (kinds.has(k)) { matches++; break; }
      if (matches) break;
    }
  }
  // Every lens can match through UNLOADED namespaces (the
  // nsHoldsLensKind exception in nodeShouldShow) — count those too,
  // else the hint claims "nothing matches" over a tree of visible
  // kind-bearing namespaces (or "No secrets yet" over real secrets).
  if (!matches && lensSet.size > 0 && lookups?.nsMap) {
    const nsIds = [null, ...lookups.nsMap.keys()];
    for (const k of lensSet) {
      if (matches) break;
      for (const nsId of nsIds) {
        const path = nsId ? (lookups.nsPathMap?.get(nsId) || null) : null;
        if (nsHoldsLensKind(k, nsId, path)) { matches++; break; }
      }
    }
  }
  if (lensSet.size > 0 && matches === 0) {
    if (!hint) {
      hint = document.createElement('div');
      hint.className = 'lens-empty-hint';
      list.appendChild(hint);
    }
    hint.textContent = lensSet.has('secrets')
      ? 'No secrets yet — create one with “+ New secret” above. (The selected fn stays visible regardless of the lens.)'
      : 'Nothing matches this lens yet. (The selected fn stays visible regardless.)';
    hint.hidden = false;
  } else if (hint) {
    hint.hidden = true;
  }
}

// Remove + re-render the namespace-less (primitives) bucket in place. Its
// custom visibility + lazy-load make a `hidden` overlay fiddly, so re-running
// renderRootNode keeps it parity-correct — and it's tiny. Used by the lens
// flip, its own expand/collapse toggle, AND the root lazy-load .then. Rebuild
// the tree first so a lazy load that just appended root fns to `graphData.fns`
// is reflected — `_lastRootFns` captured at an earlier render is frozen-empty.
function refreshRootNode() {
  const list = document.getElementById('entity-list');
  if (!list) return;
  _lastTree = buildNsTree(graphData);
  _lastRootFns = _lastTree.fns;
  const rootHeader = list.querySelector('.ns-header-pseudo');
  if (rootHeader) {
    const rootChildren = rootHeader.nextElementSibling?.classList.contains('ns-children')
      ? rootHeader.nextElementSibling : null;
    rootHeader.remove();
    if (rootChildren) rootChildren.remove();
  }
  if (_lastRootFns) renderRootNode(list, _lastRootFns, false);
}

// Sync the lens chips to the persisted state (active = in the lens; "All"
// active when the lens is empty), fill the services/apps counts from their
// primed caches, hide the apps chip when the deployment has no app routing
// (no tenancy API), + gate the "+ New secret" button on auth. Cheap
// (≤7 nodes); runs on every render.
function syncKindFilterBar() {
  document.querySelectorAll('.tree-detail-toggle').forEach((btn) => {
    btn.setAttribute('aria-pressed', String(!!treeDetails[btn.dataset.detail]));
  });
  document.querySelectorAll('#kind-filters .kind-toggle').forEach((btn) => {
    const kind = btn.dataset.kind;
    // The fx DETAIL toggle shares the chip row (and .kind-toggle skin)
    // but is not a lens — its aria-pressed was set above; skip it here
    // or this loop clobbers it back to "false" on every sync.
    if (!kind) return;
    const active = kind === 'all' ? lensKinds.size === 0 : lensKinds.has(kind);
    btn.setAttribute('aria-pressed', String(active));
    if (kind === 'apps') {
      btn.hidden = !(window.API && API.api_orgs_apps);
    }
    // Deployed-thing counts — the two "how many do I have running" kinds
    // whose caches hold the GLOBAL truth (services/app-routes lists). The
    // structural kinds (fn/types/secrets) load lazily, so a client count
    // would lie; they get no number.
    const countEl = btn.querySelector('.kind-count');
    if (countEl) {
      let n = null;
      if (kind === 'services' && typeof getAllServiceFnIdCount === 'function') {
        n = getAllServiceFnIdCount();
      } else if (kind === 'apps' && typeof getAppRouteCount === 'function') {
        n = getAppRouteCount();
      } else if (kind === 'tests' && typeof getTestStatusCount === 'function') {
        n = getTestStatusCount();
      }
      countEl.textContent = (n === null || n === undefined) ? '' : String(n);
    }
  });
  // "+ New secret" — a create action, not a filter. Shown only when the user is
  // authed AND focused on secrets (the `secrets` lens active), so it appears
  // right where a user manages secrets instead of sitting ambiguously in the
  // filter bar. "All"/other lenses hide it; the 🔒 chip is always there to get in.
  const addBtn = document.getElementById('secret-add-btn');
  if (addBtn) {
    const authed = typeof isAuthenticated === 'function' && isAuthenticated();
    addBtn.hidden = !(authed && lensKinds.has('secrets'));
  }
}

// Collapsible "(primitives)" node for namespace-less entities — the
// primitive type-rows seeded at boot (any, bool, int, …) plus the
// occasional top-level user fn. (The old "(root)" label was a
// developer-ism — users read "primitives", which is what ~all of its
// content is.) Filtered by the lens; hidden entirely when nothing inside
// is visible. Reuses the expandedNamespaces machinery via a synthesised
// path key.
function renderRootNode(list, rootFns, searchMode) {
  // Under an active workspace the namespace-less "(primitives)" bucket is out of
  // any project scope — skip it so a scoped explorer shows only the picked
  // projects. Search always spans everything.
  if (!searchMode && typeof window.graphdenWorkspaceActive === 'function'
      && window.graphdenWorkspaceActive()) {
    return;
  }
  const visible = [...rootFns].filter(fnKindVisible)
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
  // The root bucket's leaves (primitive type-rows + top-level fns) load
  // lazily like any namespace. Its total named-fn count comes from the
  // `:tree` `:counts` payload (nsHasChildFn keyed by the null bucket), so
  // the node still appears before its leaves are fetched.
  const loaded = searchMode
    || (typeof isNamespaceLoaded === 'function' && isNamespaceLoaded(null));
  const rootCount = (lookups?.nsHasChildFn?.get(null)) || 0;
  // In search mode show only when there are matched root fns; otherwise show
  // when it holds anything (loaded-visible, or count says so while unloaded).
  if (searchMode) { if (visible.length === 0) return; }
  else if (visible.length === 0 && !(rootCount > 0 && !loaded)) return;
  // Under an active lens, keep the bucket only when the null-keyed kind
  // signals say something inside matches (its rows are MOSTLY type-rows,
  // plus the odd top-level fn) — else hide it instead of an unopenable
  // "(primitives) N" (mirrors the namespace focus-prune above).
  else if (visible.length === 0 && lensKinds.size > 0
           && ![...lensKinds].some((k) => nsHoldsLensKind(k, null, null))) return;

  const groupPath = '__root__';
  const isOpen = searchMode || expandedNamespaces.has(groupPath);
  const header = document.createElement('div');
  header.className = 'ns-header ns-header-pseudo';
  header.setAttribute('role', 'treeitem');
  header.setAttribute('aria-level', '1');
  header.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
  header.setAttribute('tabindex', '-1');
  const arrow = document.createElement('span');
  arrow.className = 'ns-arrow' + (isOpen ? '' : ' collapsed');
  arrow.textContent = isOpen ? '▼' : '▶';
  header.appendChild(arrow);
  const label = document.createElement('span');
  label.className = 'ns-label';
  label.textContent = '(primitives)';
  label.title = 'Namespace-less entities — the boot-seeded primitive types, plus any top-level fn';
  header.appendChild(label);
  const count = document.createElement('span');
  count.className = 'ns-count';
  count.textContent = loaded ? visible.length : rootCount;
  header.appendChild(count);
  // Type-error chip for the null bucket — namespace-less fns' recorded
  // diagnostics land under the `null` key of the `:tree` counts payload.
  const rootTypeErrs = lookups?.nsTypeErrors?.get(null) || 0;
  if (rootTypeErrs > 0) {
    const chip = document.createElement('span');
    chip.className = 'ns-type-error-chip';
    chip.textContent = '⚠ ' + rootTypeErrs;
    chip.title = rootTypeErrs + ' type error' + (rootTypeErrs === 1 ? '' : 's')
      + ' in this namespace — see the Type errors panel';
    header.appendChild(chip);
  }
  header.onclick = (e) => {
    e.stopPropagation();
    if (isOpen) expandedNamespaces.delete(groupPath);
    else expandedNamespaces.add(groupPath);
    // Re-render just this small bucket in place (search stays a full rebuild).
    if (searchFilter) updateEntityList(graphData);
    else refreshRootNode();
  };
  list.appendChild(header);

  if (isOpen) {
    const childGroup = document.createElement('div');
    childGroup.className = 'ns-children';
    // Same pairing attr the real namespaces carry — compare mode's
    // ghost-row injection addresses groups by it.
    childGroup.dataset.nsChildren = groupPath;
    if (!loaded) {
      const loading = document.createElement('div');
      loading.className = 'loading';
      loading.textContent = 'Loading…';
      childGroup.appendChild(loading);
      if (typeof loadNamespaceFns === 'function'
          && !(typeof isNamespaceLoading === 'function' && isNamespaceLoading(null))) {
        loadNamespaceFns(null)
          .then(() => refreshRootNode())
          .catch((err) => { console.error('loadNamespaceFns(root) failed', err); });
      }
    } else {
      for (const fn of visible) childGroup.appendChild(buildFnItem(fn, 2));
    }
    list.appendChild(childGroup);
  }
}

/**
 * Update the entity list in sidebar as a namespace tree
 */
// Scroll the Explorer tree to a fn's row (if present) and flash it, so
// "Reveal in Explorer" lands the eye on the right entry.
function scrollTreeToFn(fnId) {
  requestAnimationFrame(() => {
    const row = document.querySelector('#entity-list .entity-item[data-fn-id="' + fnId + '"]');
    if (!row) return;
    window.scrollIntoViewMotionSafe(row, { block: 'center', behavior: 'smooth' });
    row.classList.add('gd-tree-flash');
    setTimeout(() => row.classList.remove('gd-tree-flash'), 1300);
  });
}

// Reveal a fn in the Explorer tree: expand its namespace (and every ancestor
// segment), load that namespace's leaves, re-render, then scroll to + flash the
// row. Lets the ns popover answer "where does this live / find it in the editor".
function revealFnInTree(fnId) {
  const fn = lookups?.fnMap?.get(fnId);
  const nsId = fn?.['namespace-id'];
  const nsPath = (nsId && lookups?.nsPathMap) ? lookups.nsPathMap.get(nsId) : null;
  if (nsPath) {
    const segs = nsPath.split('.');
    for (let i = 1; i <= segs.length; i++) expandedNamespaces.add(segs.slice(0, i).join('.'));
  }
  const finish = () => { updateEntityList(graphData); scrollTreeToFn(fnId); };
  if (nsId && typeof loadNamespaceFns === 'function') {
    loadNamespaceFns(nsId).then(finish).catch(finish);
  } else {
    finish();
  }
}

// Mount the Operate / Platform / Diagnostics panes: Grants, Members, Roles,
// Organizations, Platform access, Packages, Monitoring, Apps, Assets, plus
// the code diagnostics (Errors, Type errors, Tests, Debug). Each builder is
// a global from its own module and each returns null when it doesn't apply,
// so a section opts out by being absent rather than by being listed
// somewhere.
//
// Redesign 2026-08: these mount into surfaces, not the explorer, so the
// sidebar stays a clean namespace browser — `fallbackList` is used only when
// the operate pane isn't on the page. Cross-org / platform panels go to the
// PLATFORM surface; the code diagnostics go to the Build-surface DIAGNOSTICS
// drawer (#gd-diag-panels — so a fn link keeps the editor on screen);
// everything else (org RBAC + the org's operational panels) to Organization.
//
// Lifted out of `updateEntityList`, which had ninety lines of this in the
// middle of building the namespace tree — two surfaces, one function.
function mountOpsSections(fallbackList, searchMode) {
  if (searchMode) return;
  const opsPane = document.getElementById('gd-operate-panels');
  const opsNav = document.getElementById('gd-operate-nav');
  const platPane = document.getElementById('gd-platform-panels');
  const platNav = document.getElementById('gd-platform-nav');
  const diagPane = document.getElementById('gd-diag-panels');
  const diagNav = document.getElementById('gd-diag-nav');
  const opsHost = opsPane || fallbackList;
  const opsNavHost = opsPane ? opsNav : null;
  const platHost = platPane || opsHost;
  const platNavHost = platPane ? platNav : opsNavHost;
  // Code diagnostics (errors / type-errors / tests / debug) → the Build
  // drawer, so a fn link in a row navigates the canvas without a surface
  // switch; Operate keeps the admin panels.
  const diagHost = diagPane || opsHost;
  const diagNavHost = diagPane ? diagNav : opsNavHost;
  if (opsHost !== fallbackList) {
    opsPane.innerHTML = '';
    if (opsNavHost) opsNavHost.innerHTML = '';
  }
  if (platHost !== opsHost && platHost !== fallbackList) {
    platPane.innerHTML = '';
    if (platNavHost && platNavHost !== opsNavHost) platNavHost.innerHTML = '';
  }
  if (diagHost !== opsHost && diagHost !== fallbackList) {
    diagPane.innerHTML = '';
    if (diagNavHost && diagNavHost !== opsNavHost) diagNavHost.innerHTML = '';
  }
  if (typeof buildGrantsAdminSection === 'function') {
    mountAdminSection(opsHost, opsNavHost, 'grants', buildGrantsAdminSection);
  }
  if (typeof buildUsersAdminSection === 'function') {
    mountAdminSection(opsHost, opsNavHost, 'users', buildUsersAdminSection);
  }
  if (typeof buildRolesAdminSection === 'function') {
    mountAdminSection(opsHost, opsNavHost, 'roles', buildRolesAdminSection);
  }
  if (typeof buildExecutorsAdminSection === 'function') {
    // Org's executor status (hosted/byo, BYO liveness, run snippet).
    mountAdminSection(opsHost, opsNavHost, 'executors', buildExecutorsAdminSection);
  }
  if (typeof buildOrgsAdminSection === 'function') {
    // Cross-org registry → Platform surface.
    mountAdminSection(platHost, platNavHost, 'orgs', buildOrgsAdminSection);
  }
  if (typeof buildPlatformAccessSection === 'function') {
    // Platform-access delegation → Platform surface (manage-platform-access).
    mountAdminSection(platHost, platNavHost, 'platform-access', buildPlatformAccessSection);
  }
  // Packages (install/browse) live on the BUILD surface via the #gd-pkg-chip
  // context-bar chip → popover (editor-shell.js) — install is a build act.
  // What DOES belong here is the read-mostly GOVERNANCE view (packages spec
  // §4): catalog of what the org published, who may publish, install audit.
  mountAdminSection(opsHost, opsNavHost, 'packages', buildPackagesGovernanceSection);
  if (typeof buildStatsSection === 'function') {
    mountAdminSection(opsHost, opsNavHost, 'stats', buildStatsSection);
  }
  // (No Apps section: publishing a fn as an app is the ▣ row action on the
  // fn itself — editor-apps.js showFnAppsPopover; the apps LENS is the
  // org-wide overview. The Organization panel was retired 2026-08-30.)
  if (typeof buildErrorsSection === 'function') {
    mountAdminSection(diagHost, diagNavHost, 'errors', buildErrorsSection);
  }
  if (typeof buildTypeErrorsSection === 'function') {
    mountAdminSection(diagHost, diagNavHost, 'type-errors', buildTypeErrorsSection);
  }
  if (typeof buildTestsSection === 'function') {
    mountAdminSection(diagHost, diagNavHost, 'tests', buildTestsSection);
  }
  if (typeof buildDebugSection === 'function') {
    // «Catch next request» trap + last-captured trace (editor-debug.js).
    mountAdminSection(diagHost, diagNavHost, 'debug', buildDebugSection);
  }
  if (typeof buildAssetsSection === 'function') {
    // Frontend-asset overrides — self-host only (the builder returns null
    // under an active tenancy addon; writes there are system-only).
    mountAdminSection(opsHost, opsNavHost, 'assets', buildAssetsSection);
  }
  // Select a section on each surface so a pane is always showing — the one
  // the user is ALREADY on when there is one, the first otherwise. This
  // mount re-runs on every graph refresh (updateEntityList), and defaulting
  // unconditionally to the first section flipped an open panel back to
  // Packages under the reader whenever background state landed — a test
  // auto-run finishing was enough (caught by the lesson-26 tour: its
  // green-dot check never saw the Tests panel it had just opened).
  const activeOrFirst = (nav, pane) => {
    const cur = [...nav.children]
      .find((b) => b.getAttribute('aria-current') === 'page')?.dataset.section;
    return (cur && pane.querySelector(':scope > section[data-section="' + cur + '"]'))
      ? cur : nav.firstElementChild.dataset.section;
  };
  if (opsNavHost?.firstElementChild) {
    activateOpSection(opsNavHost, opsHost, activeOrFirst(opsNavHost, opsHost));
  }
  if (platNavHost && platNavHost !== opsNavHost && platNavHost.firstElementChild) {
    activateOpSection(platNavHost, platHost, activeOrFirst(platNavHost, platHost));
  }
  if (diagNavHost && diagNavHost !== opsNavHost && diagNavHost.firstElementChild) {
    activateOpSection(diagNavHost, diagHost, activeOrFirst(diagNavHost, diagHost));
    // Bar badges read the mounted panels — refresh after every (re)mount.
    if (typeof window.gdDiagUpdateBadges === 'function') window.gdDiagUpdateBadges();
  }
}


function updateEntityList(data) {
  // A search reply (or an early auth repaint) can land before the graph
  // data primes on a fresh tab — painting from null threw mid-function
  // and left the sidebar dead. An empty shape renders the transient
  // empty state instead; the post-prime repaint fills it in.
  if (!data) data = { namespaces: [], fns: [] };
  const list = document.getElementById('entity-list');
  list.innerHTML = '';

  // Keep the lens chips + secret-add in sync with persisted state, and
  // prime the caches classification depends on (services, app routes,
  // secrets).
  syncKindFilterBar();
  primeServiceCacheOnce();
  primeAppsCacheOnce();
  primeSecretsOnce();
  primeTestStatusesOnce();

  const searchMode = !!searchFilter;

  // While a search query is in flight (debounce + round-trip) there are no
  // results yet — show a transient state rather than a misleading empty tree.
  if (searchMode && _searchResults === null) {
    list.innerHTML = '<div class="loading">Searching…</div>';
    return;
  }

  // In search mode the tree is built from the server's matches only; the
  // normal (lazy) tree is built from whatever fn leaves have been loaded.
  const tree = searchMode
    ? buildNsTree({ namespaces: data.namespaces, fns: _searchResults || [] })
    : buildNsTree(data);

  mountOpsSections(list, searchMode);

  // Search: pin EXACT name matches above the tree. Substring matching
  // alone buried `core.arithmetic.add` under dozens of `app.editor`
  // internals that merely contain "add" — the row the reader typed the
  // full name of must be first (tutorial finding 2026-08-26). Internals
  // (`_`-private / anon) sort after public exact matches.
  if (searchMode) {
    const q = searchFilter.trim().toLowerCase();
    const exact = [];
    (function walk(node) {
      for (const fn of node.fns || []) {
        if ((fn.rawName || '').toLowerCase() === q
            || (fn.displayName || '').toLowerCase() === q) exact.push(fn);
      }
      for (const child of node.children.values()) walk(child);
    })(tree);
    if (exact.length) {
      const internal = (fn) => ((fn.rawName || '').startsWith('_')
                               || /^anon-/.test(fn.displayName || '')) ? 1 : 0;
      exact.sort((a, b) => internal(a) - internal(b)
                        || (a.displayName || '').localeCompare(b.displayName || ''));
      const sec = document.createElement('div');
      sec.className = 'search-exact-section';
      const lbl = document.createElement('div');
      lbl.className = 'search-exact-label';
      lbl.textContent = 'Exact match';
      sec.appendChild(lbl);
      for (const fn of exact.slice(0, 5)) {
        const el = buildFnItem(fn, 1);
        // Same lens overlay as the tree rows — an exact-match row that
        // ignored the lens read as "visible" to the tour's lens-clear
        // probe and broke the lesson-05 e2e (all=false).
        el.hidden = typeof fnKindVisible === 'function' ? !fnKindVisible(fn) : false;
        sec.appendChild(el);
      }
      list.appendChild(sec);
    }
  }

  // Top-level namespaces (sorted). Lens visibility is a `hidden` overlay set
  // inside renderNsNode (not a structural skip here), so a lens toggle flips in
  // place. Workspace-focus IS a structural skip — it's lens-independent (a lens
  // toggle never changes workspace scope), so out-of-scope namespaces need not
  // be in the DOM.
  const sortedNs = [...tree.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  const wsActive = !searchMode && typeof window.graphdenWorkspaceActive === 'function'
    && window.graphdenWorkspaceActive();
  for (const [name, node] of sortedNs) {
    // Search: matched-only structural tree (no lens flip) → keep the skip.
    // Non-search: build all, lens is a `hidden` overlay set in renderNsNode.
    if (searchMode && !nodeShouldShow(node, searchMode)) continue;
    // Workspaces (redesign 2026-08): when a workspace is active, show only its
    // included top-level roots; always drop personally-hidden namespaces. Both
    // are structural skips (lens-independent). Search spans everything (above).
    if (!searchMode && typeof window.graphdenIsHidden === 'function'
        && window.graphdenIsHidden(name)) {
      continue;
    }
    if (wsActive && typeof window.graphdenInWorkspaceScope === 'function'
        && !window.graphdenInWorkspaceScope(name)) {
      continue;
    }
    renderNsNode(list, name, node, '', searchMode);
  }

  // Namespace-less entities (primitive type-rows any/int/bool + top-level
  // fns) in a single collapsible "(root)" node, subject to the toggles.
  _lastRootFns = tree.fns;   // for applyLensVisibility's in-place root re-render
  _lastTree = tree;          // for incremental expand/refresh's fresh-node lookup
  renderRootNode(list, tree.fns, searchMode);

  // A truncated search result (server-side cap) — tell the user to refine
  // rather than silently hiding matches.
  if (searchMode && _searchTruncated) {
    const note = document.createElement('div');
    note.className = 'loading';
    note.textContent = 'Showing the first matches — refine to narrow.';
    list.appendChild(note);
  }

  if (list.children.length === 0) {
    list.innerHTML = '<div class="loading">No matches</div>';
  }

  // Root-level inline-create input row, when the user clicked the
  // bottom "+ New namespace" button. Appears between the tree and the
  // bottom button so the new entry shows up in place.
  const rootCreateRow = (typeof buildRootCreateRow === 'function')
                        ? buildRootCreateRow() : null;
  if (rootCreateRow) {
    list.appendChild(rootCreateRow);
  } else if (typeof buildRootCreateButton === 'function') {
    // Always-visible "+ New namespace" full-width button at the bottom
    // of the sidebar. Skipped while a root-create row is already
    // active (no point in offering both at once).
    list.appendChild(buildRootCreateButton());
  }
}

