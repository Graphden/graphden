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
const expandedNamespaces = new Set();

// Current search/filter text (lowercase)
let searchFilter = '';

// Append a lazy-loading admin section (Grants / Users / Packages) and process
// it with HTMX. The section's `.ns-children` carries hx-get + hx-trigger="load";
// that trigger ONLY fires when htmx.process runs on a node already CONNECTED to
// the document — processing a detached node marks it processed but never fires
// load. So the section builders return an unprocessed node and we process here,
// after appendChild. `section` may be null (builder gated it out) → no-op.
function mountAdminSection(list, section) {
  if (!section) return;
  list.appendChild(section);
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
let kindVisible = loadKindPrefs();

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

// A namespace node is shown iff it has ≥1 currently-visible entity
// (recursively) OR an active inline-create rooted at it / a descendant
// (so an in-progress create row is never hidden out from under the user).
function nodeHasVisibleContent(node) {
  if (node.fns.some(fnKindVisible)) return true;
  for (const child of node.children.values()) {
    if (nodeHasVisibleContent(child)) return true;
  }
  return false;
}
function nodeHasActiveCreate(node) {
  if (node.nsId && typeof window.hasActiveCreateIn === 'function'
      && window.hasActiveCreateIn(node.nsId)) return true;
  for (const child of node.children.values()) {
    if (nodeHasActiveCreate(child)) return true;
  }
  return false;
}
function nodeShouldShow(node) {
  return nodeHasVisibleContent(node) || nodeHasActiveCreate(node);
}

// Classification reads two caches via sync helpers (getServiceForFnId,
// isSecretFn/secret paths). Prime them once per graph load and re-render
// so the first paint is accurate.
let _serviceCachePrimed = false;
function primeServiceCacheOnce() {
  if (_serviceCachePrimed || typeof loadAllServiceFnIds !== 'function') return;
  _serviceCachePrimed = true;
  loadAllServiceFnIds().then(() => updateEntityList(graphData));
}
let _secretsPrimedGraph = null;
function primeSecretsOnce() {
  if (typeof isAuthenticated !== 'function' || !isAuthenticated()) return;
  if (_secretsPrimedGraph === graphData || typeof loadSecrets !== 'function') return;
  _secretsPrimedGraph = graphData;
  loadSecrets().then(() => updateEntityList(graphData));
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
 * Filter a tree node, keeping only branches that contain matches.
 * A fn matches if its displayName contains the filter.
 * A ns matches if its name contains the filter OR any descendant matches.
 * When a ns name itself matches, all its descendants are included.
 * Returns null if nothing matches.
 */
function filterNsNode(node, filter, nsName) {
  const nsMatches = nsName?.toLowerCase().includes(filter);

  // If the namespace name matches, include the entire subtree unfiltered
  if (nsMatches) return node;

  // Filter children recursively
  const filteredChildren = new Map();
  for (const [childName, childNode] of node.children) {
    const filtered = filterNsNode(childNode, filter, childName);
    if (filtered) filteredChildren.set(childName, filtered);
  }

  // Filter fns — match against BOTH the display label and the raw
  // name so users can find a private fn by typing either `_router` or
  // `router`.
  const filteredFns = node.fns.filter(fn => {
    const f = filter.toLowerCase();
    return fn.displayName.toLowerCase().includes(f)
        || (fn.rawName?.toLowerCase().includes(f));
  });

  if (filteredChildren.size === 0 && filteredFns.length === 0) return null;

  return { children: filteredChildren, fns: filteredFns };
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
    if (rec && rec.path) {
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
  // Secret rows get Rotate + Delete — the CRUD that used to live in the
  // separate Secrets section. Auth-gated inside the helper.
  if (isSecret && typeof buildSecretRowActions === 'function') {
    buildSecretRowActions(actions, fn);
  }
  if (actions.children.length > 0) item.appendChild(actions);

  item.onclick = () => selectFn(fn.id);
  return item;
}


function renderNsNode(container, name, node, path) {
  const nsPath = path ? path + '.' + name : name;
  const isCollapsed = !expandedNamespaces.has(nsPath);

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

  // Child namespaces (sorted)
  const childGroup = document.createElement('div');
  childGroup.className = 'ns-children';

  const sortedChildren = [...node.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [childName, childNode] of sortedChildren) {
    if (!nodeShouldShow(childNode)) continue;
    renderNsNode(childGroup, childName, childNode, nsPath);
  }

  // Fn items — filtered by the kind toggles, rendered flat. Kind is now
  // a top-level filter (fn / types / secrets / services), not an
  // in-namespace Types/Functions grouping.
  const visibleFns = [...node.fns].filter(fnKindVisible)
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
  for (const fn of visibleFns) childGroup.appendChild(buildFnItem(fn));

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
 * Search input handler
 */
function onSearchInput(value) {
  searchFilter = value.trim().toLowerCase();
  updateEntityList(graphData);
}

function clearSearch() {
  searchFilter = '';
  const input = document.getElementById('search-input');
  if (input) input.value = '';
  updateEntityList(graphData);
}


// Eye-toggle click (from the #kind-filters buttons). Flips the kind's
// visibility, persists it, re-renders.
function toggleKind(kind, btn) {
  const next = !btn || btn.getAttribute('aria-pressed') !== 'true';
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
function renderRootNode(list, rootFns) {
  const visible = [...rootFns].filter(fnKindVisible)
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
  if (visible.length === 0) return;

  const groupPath = '__root__';
  const isOpen = expandedNamespaces.has(groupPath);
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
  count.textContent = visible.length;
  header.appendChild(count);
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
    for (const fn of visible) childGroup.appendChild(buildFnItem(fn));
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

  let tree = buildNsTree(data);

  // Text filter (name match). Composes with the kind toggles, which are
  // applied at render time (fnKindVisible / nodeShouldShow) below.
  if (searchFilter) {
    tree = filterNsNode(tree, searchFilter, null) || { children: new Map(), fns: [] };
  }

  // Admin sections (Grants / Users / Packages) — unchanged; hidden while
  // a text filter narrows the view. Each returns null unless applicable.
  if (!searchFilter && typeof buildGrantsAdminSection === 'function') {
    mountAdminSection(list, buildGrantsAdminSection());
  }
  if (!searchFilter && typeof buildUsersAdminSection === 'function') {
    mountAdminSection(list, buildUsersAdminSection());
  }
  if (!searchFilter && typeof buildPackagesSection === 'function') {
    mountAdminSection(list, buildPackagesSection());
  }

  // Top-level namespaces (sorted) — skip any with nothing visible under
  // the current toggles (unless an inline-create is rooted inside).
  const sortedNs = [...tree.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [name, node] of sortedNs) {
    if (!nodeShouldShow(node)) continue;
    renderNsNode(list, name, node, '');
  }

  // Namespace-less entities (primitive type-rows any/int/bool + top-level
  // fns) in a single collapsible "(root)" node, subject to the toggles.
  renderRootNode(list, tree.fns);

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

