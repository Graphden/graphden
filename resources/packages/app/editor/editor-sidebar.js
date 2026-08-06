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

function mountAdminSection(list, key, build) {
  let section = _adminSections.get(key);
  if (section) {
    list.appendChild(section);   // already loaded — no rebuild, no refetch
    return;
  }
  section = build();
  if (!section) return;          // not applicable (not an admin, etc.)
  _adminSections.set(key, section);
  list.appendChild(section);
  // Only now — process() is what fires hx-trigger="load", and it must fire on a
  // CONNECTED node, so this has to come after appendChild.
  if (window.htmx && typeof window.htmx.process === 'function') window.htmx.process(section);
}

// ── Per-kind visibility ────────────────────────────────────────────────
// Every entity is classified into EXACTLY ONE kind by priority
// secrets > types > services > fn (a service is structurally a normal
// fn and a secret is a fn too, so the priority makes each show under a
// single toggle). Each kind has an eye toggle in #kind-filters; hiding a
// kind drops those entities plus any namespace left with nothing
// visible. State persists in localStorage.
const TYPE_ROLES = new Set(['refinement', 'list', 'union', 'variant',
                            'record', 'fn-type', 'primitive']);
const KIND_PREFS_STORAGE = 'graphden.sidebarKinds';

function loadKindPrefs() {
  const def = { fn: true, types: true, secrets: true, services: true };
  try {
    const raw = localStorage.getItem(KIND_PREFS_STORAGE);
    if (raw) return { ...def, ...JSON.parse(raw) };
  } catch (_) { /* private-mode / corrupt → defaults */ }
  return def;
}
const kindVisible = loadKindPrefs();

function saveKindPrefs() {
  try { localStorage.setItem(KIND_PREFS_STORAGE, JSON.stringify(kindVisible)); }
  catch (_) { /* best-effort */ }
}

// Classify a fn-row into one visibility bucket (priority order above).
function classifyFnKind(fn) {
  if (typeof isSecretFn === 'function' && isSecretFn(fn)) return 'secrets';
  const role = (fn.role || '').replace(/^:/, '');
  if (TYPE_ROLES.has(role)) return 'types';
  if (typeof getServiceForFnId === 'function' && getServiceForFnId(fn.id)) return 'services';
  return 'fn';
}
function fnKindVisible(fn) { return kindVisible[classifyFnKind(fn)] !== false; }

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
function buildFnItem(fn) {
  const item = document.createElement('div');
  item.className = 'entity-item';
  if (fn.id === selectedFnId) item.className += ' selected';
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
    if (isCollapsed) {
      expandedNamespaces.add(nsPath);
    } else {
      expandedNamespaces.delete(nsPath);
    }
    updateEntityList(graphData);
  };

  container.appendChild(header);

  if (isCollapsed) return;

  // Child namespaces (sorted). These render independently of this node's
  // own leaves — a child loads its own fns when IT expands.
  const childGroup = document.createElement('div');
  childGroup.className = 'ns-children';

  const sortedChildren = [...node.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [childName, childNode] of sortedChildren) {
    if (!nodeShouldShow(childNode, searchMode)) continue;
    renderNsNode(childGroup, childName, childNode, nsPath, searchMode);
  }

  // Own fn leaves. Outside search mode they load lazily the first time this
  // namespace is expanded — show a placeholder, fetch, re-render. In search
  // mode the leaves are the server's matches and are already in `node.fns`.
  if (!searchMode && node.nsId != null
      && typeof isNamespaceLoaded === 'function' && !isNamespaceLoaded(node.nsId)) {
    const loading = document.createElement('div');
    loading.className = 'loading';
    loading.textContent = 'Loading…';
    childGroup.appendChild(loading);
    // Trigger the fetch (with its re-render) only if one isn't already in
    // flight — re-rendering while it loads must not attach another `.then`.
    if (typeof loadNamespaceFns === 'function'
        && !(typeof isNamespaceLoading === 'function' && isNamespaceLoading(node.nsId))) {
      loadNamespaceFns(node.nsId)
        .then(() => updateEntityList(graphData))
        .catch((err) => { console.error('loadNamespaceFns failed', err); });
    }
  } else {
    // Fn items — filtered by the kind toggles, rendered flat. Kind is a
    // top-level filter (fn / types / secrets / services), not an
    // in-namespace Types/Functions grouping.
    const visibleFns = [...node.fns].filter(fnKindVisible)
      .sort((a, b) => a.displayName.localeCompare(b.displayName));
    for (const fn of visibleFns) childGroup.appendChild(buildFnItem(fn));
  }

  // If the user has an active inline-create rooted at THIS namespace,
  // append the input row inside `childGroup` so it sits where the new
  // entity will appear once submitted.
  if (node?.nsId && typeof buildActiveCreateRow === 'function') {
    const createRow = buildActiveCreateRow(node.nsId, 0);
    if (createRow) childGroup.appendChild(createRow);
  }

  container.appendChild(childGroup);
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


// Eye-toggle click (from the #kind-filters buttons). Flips the kind's
// visibility, persists it, re-renders.
function toggleKind(kind, btn) {
  const next = btn?.getAttribute('aria-pressed') !== 'true';
  if (btn) btn.setAttribute('aria-pressed', String(next));
  setKindVisible(kind, next);
}
function setKindVisible(kind, visible) {
  kindVisible[kind] = !!visible;
  saveKindPrefs();
  updateEntityList(graphData);
}

// Sync the static eye buttons to the persisted state + gate the "+ New
// secret" button on auth. Cheap (≤5 nodes); runs on every render.
function syncKindFilterBar() {
  document.querySelectorAll('#kind-filters .kind-toggle').forEach((btn) => {
    btn.setAttribute('aria-pressed', String(kindVisible[btn.dataset.kind] !== false));
  });
  const addBtn = document.getElementById('secret-add-btn');
  if (addBtn) addBtn.hidden = !(typeof isAuthenticated === 'function' && isAuthenticated());
}

// Collapsible "(root)" node for namespace-less entities — the primitive
// type-rows seeded at boot (any, bool, int, …) plus the occasional
// top-level user fn. Filtered by the kind toggles; hidden entirely when
// nothing inside is visible. Reuses the expandedNamespaces machinery via
// a synthesised path key.
function renderRootNode(list, rootFns, searchMode) {
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

  const groupPath = '__root__';
  const isOpen = searchMode || expandedNamespaces.has(groupPath);
  const header = document.createElement('div');
  header.className = 'ns-header ns-header-pseudo';
  const arrow = document.createElement('span');
  arrow.className = 'ns-arrow' + (isOpen ? '' : ' collapsed');
  arrow.textContent = isOpen ? '▼' : '▶';
  header.appendChild(arrow);
  const label = document.createElement('span');
  label.className = 'ns-label';
  label.textContent = '(root)';
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
    updateEntityList(graphData);
  };
  list.appendChild(header);

  if (isOpen) {
    const childGroup = document.createElement('div');
    childGroup.className = 'ns-children';
    if (!loaded) {
      const loading = document.createElement('div');
      loading.className = 'loading';
      loading.textContent = 'Loading…';
      childGroup.appendChild(loading);
      if (typeof loadNamespaceFns === 'function'
          && !(typeof isNamespaceLoading === 'function' && isNamespaceLoading(null))) {
        loadNamespaceFns(null)
          .then(() => updateEntityList(graphData))
          .catch((err) => { console.error('loadNamespaceFns(root) failed', err); });
      }
    } else {
      for (const fn of visible) childGroup.appendChild(buildFnItem(fn));
    }
    list.appendChild(childGroup);
  }
}

/**
 * Update the entity list in sidebar as a namespace tree
 */
function updateEntityList(data) {
  const list = document.getElementById('entity-list');
  list.innerHTML = '';

  // Keep the eye buttons + secret-add in sync with persisted state, and
  // prime the caches classification depends on (service cache + secrets).
  syncKindFilterBar();
  primeServiceCacheOnce();
  primeSecretsOnce();

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

  // Admin sections (Grants / Users / Packages) — hidden while searching.
  // Each returns null unless applicable.
  if (!searchMode && typeof buildGrantsAdminSection === 'function') {
    mountAdminSection(list, 'grants', buildGrantsAdminSection);
  }
  if (!searchMode && typeof buildUsersAdminSection === 'function') {
    mountAdminSection(list, 'users', buildUsersAdminSection);
  }
  if (!searchMode && typeof buildRolesAdminSection === 'function') {
    mountAdminSection(list, 'roles', buildRolesAdminSection);
  }
  if (!searchMode && typeof buildOrgsAdminSection === 'function') {
    mountAdminSection(list, 'orgs', buildOrgsAdminSection);
  }
  if (!searchMode && typeof buildPackagesSection === 'function') {
    mountAdminSection(list, 'packages', buildPackagesSection);
  }
  if (!searchMode && typeof buildStatsSection === 'function') {
    mountAdminSection(list, 'stats', buildStatsSection);
  }
  if (!searchMode && typeof buildAppsSection === 'function') {
    mountAdminSection(list, 'apps', buildAppsSection);
  }
  if (!searchMode && typeof buildErrorsSection === 'function') {
    mountAdminSection(list, 'errors', buildErrorsSection);
  }
  if (!searchMode && typeof buildTypeErrorsSection === 'function') {
    mountAdminSection(list, 'type-errors', buildTypeErrorsSection);
  }

  // Top-level namespaces (sorted) — skip any with nothing visible under
  // the current toggles (unless an inline-create is rooted inside).
  const sortedNs = [...tree.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [name, node] of sortedNs) {
    if (!nodeShouldShow(node, searchMode)) continue;
    renderNsNode(list, name, node, '', searchMode);
  }

  // Namespace-less entities (primitive type-rows any/int/bool + top-level
  // fns) in a single collapsible "(root)" node, subject to the toggles.
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

