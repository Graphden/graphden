// Editor Sidebar — the LENS: which kinds of rows the Explorer shows.
//
// Split out of editor-sidebar.js (2026-09-13). The kind-chip bar
// (`#kind-filters`): `loadLens` / `saveLens` persist the focused kinds in
// localStorage (`graphden.sidebarLens`; empty set = All), `toggleKind` flips a
// chip, `applyLensVisibility` applies the lens as an in-place `hidden` overlay
// over the already-built tree (no teardown — only an active search rebuilds),
// `syncKindFilterBar` mirrors the state onto the chips and the
// `nodeShouldShow` / `fnKindVisible` / `nsHoldsLensKind` predicates are what
// the tree builders in editor-sidebar-rows.js consult per row. The `prime*Once`
// helpers warm the caches the problem lenses read (services / apps / tests /
// failed runs / lint / secrets) once per graph load. The trailing `fx marks`
// chip is a DETAIL toggle, not a lens (`toggleTreeDetail`, `graphden.treeDetails`).
//
// Loads BEFORE editor-sidebar.js: `lensKinds` / `treeDetails` are read at
// build time by the rows. Tree memo state (`_lastTree`, `treeNodeAt`) stays in
// editor-sidebar.js — it belongs to the render, the lens only reads it.

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
// PROBLEM kinds — an overlay over the structural kinds above, never
// exclusive with them: a fn that fails is still a plain fn for the fn
// lens. Counts come from the row itself (`type-error-count`) and the
// two primed caches (editor-problems.js).
function fnProblemSet(fn) {
  const kinds = new Set();
  if (!fn) return kinds;
  if ((fn['type-error-count'] || 0) > 0) kinds.add('type-errors');
  if (typeof getFailureCountForFnId === 'function' && getFailureCountForFnId(fn.id) > 0) kinds.add('failed');
  if (typeof getLintCountForFnId === 'function' && getLintCountForFnId(fn.id) > 0) kinds.add('lint');
  return kinds;
}

function fnKindVisible(fn) {
  if (fn && typeof selectedFnId !== 'undefined' && fn.id === selectedFnId) return true;
  if (lensKinds.size === 0) return true;
  const kinds = fnKindSet(fn);
  const problems = fnProblemSet(fn);
  for (const k of lensKinds) {
    if (kinds.has(k) || problems.has(k)) return true;
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
    // Problem lenses — per-namespace signals: the tree payload's type-error
    // sums, and the two primed caches (editor-problems.js).
    case 'type-errors':
      return (lookups?.nsTypeErrors?.get(nsId) || 0) > 0;
    case 'failed':
      return typeof failedNsIds === 'function' && failedNsIds().has(nsId);
    case 'lint':
      return typeof lintNsIds === 'function' && lintNsIds().has(nsId);
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
// The problem lenses' caches (failed runs / lint) — same per-graph-load
// prime; the counts they feed are read sync'ly by the row markers.
function primeProblemsOnce() {
  if (typeof primeProblemCachesOnce === 'function') primeProblemCachesOnce();
}
let _secretsPrimedGraph = null;
function primeSecretsOnce() {
  if (typeof isAuthenticated !== 'function' || !isAuthenticated()) return;
  if (_secretsPrimedGraph === graphData || typeof loadSecrets !== 'function') return;
  _secretsPrimedGraph = graphData;
  loadSecrets().then(repaintAfterPrime);
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
  // Before the first graph load there is no tree to flip: the lens is
  // saved and the chips are synced; the boot's own render applies it.
  // (A click here used to throw on `null.namespaces` from buildNsTree.)
  if (!graphData) return;
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
        // The failed half rides the same span, in red — the old Tests
        // panel's "N tests · F failed" line, without the panel.
        const failed = typeof getTestFailedCount === 'function' ? getTestFailedCount() : 0;
        countEl.classList.toggle('kind-count-bad', (failed || 0) > 0);
        countEl.dataset.failed = failed ? String(failed) : '';
        btn.title = 'Only tests (fns in a `tests` namespace)'
          + (failed ? ' — ' + failed + ' failed' : '');
      } else if (kind === 'failed' && typeof getFailureTotal === 'function') {
        n = getFailureTotal();
      } else if (kind === 'type-errors' && typeof getTypeErrorTotal === 'function') {
        n = getTypeErrorTotal();
      } else if (kind === 'lint' && typeof getLintTotal === 'function') {
        n = getLintTotal();
      }
      countEl.textContent = (n === null || n === undefined) ? '' : String(n)
        + (countEl.dataset.failed ? ' · ' + countEl.dataset.failed + '✗' : '');
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
  // "✕ Dismiss all" — the failed lens's action, same contextual rule.
  const ackAll = document.getElementById('failed-ack-all-btn');
  if (ackAll) {
    const authed = typeof isAuthenticated === 'function' && isAuthenticated();
    const any = typeof getFailureTotal === 'function' && (getFailureTotal() || 0) > 0;
    ackAll.hidden = !(authed && lensKinds.has('failed') && any);
  }
  // "▶ Run all" — the tests lens's action; the lens also keeps the
  // live status stream open while active (editor-tests.js).
  const runAll = document.getElementById('tests-run-all-btn');
  if (runAll) {
    const authed = typeof isAuthenticated === 'function' && isAuthenticated();
    const any = typeof getTestStatusCount === 'function' && (getTestStatusCount() || 0) > 0;
    runAll.hidden = !(authed && lensKinds.has('tests') && any);
  }
  if (typeof ensureTestsStream === 'function') ensureTestsStream();
}
