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

// "Only services" filter — when true, sidebar tree is pruned to
// only fns that are :service targets. The fn-id Set is lazy-loaded
// the first time the user flips the checkbox on (and refreshed on
// every flip-on after that so newly-declared services show up).
let onlyServicesFilter = false;
let serviceFnIds = new Set();


// Drop everything except fns whose id is in `keepIds`. Mirrors the
// shape filterNsNode returns (children Map + fns array). Empty
// branches are pruned recursively so the user never sees an empty
// namespace row when nothing inside it survives the filter.
function filterToFnIds(node, keepIds) {
  const filteredChildren = new Map();
  for (const [childName, childNode] of node.children) {
    const filtered = filterToFnIds(childNode, keepIds);
    if (filtered) filteredChildren.set(childName, filtered);
  }
  const filteredFns = node.fns.filter((fn) => keepIds.has(fn.id));
  if (filteredChildren.size === 0 && filteredFns.length === 0) return null;
  return {children: filteredChildren, fns: filteredFns};
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
  if (typeof isSecretFn === 'function' && isSecretFn(fn)) {
    item.className += ' entity-secret';
  }
  item.dataset.fnId = fn.id;

  if (typeof isSecretFn === 'function' && isSecretFn(fn)) {
    const lock = document.createElement('span');
    lock.className = 'secret-lock-icon';
    lock.textContent = '🔒'; // 🔒
    lock.title = 'Secret — managed via the Secrets sidebar section';
    item.appendChild(lock);
  }

  const nameSpan = document.createElement('span');
  nameSpan.className = 'name';
  nameSpan.textContent = fn.displayName;
  item.appendChild(nameSpan);

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
    renderNsNode(childGroup, childName, childNode, nsPath);
  }

  // Fn items — split into "Types" and "Functions" sub-sections so
  // refinements / records / unions / variants / lists / primitives
  // don't clutter the function browse. The backend ships a `:role`
  // per fn (see crud/impls.clj compute-fn-role) so the sidebar
  // doesn't have to re-derive it.
  const sortedFns = [...node.fns].sort((a, b) => a.displayName.localeCompare(b.displayName));
  const TYPE_ROLES = new Set(['refinement', 'list', 'union', 'variant',
                              'record', 'fn-type', 'primitive']);
  const typeFns = [];
  const functionFns = [];
  for (const fn of sortedFns) {
    const role = (fn.role || '').replace(/^:/, '');
    if (TYPE_ROLES.has(role)) typeFns.push(fn);
    else functionFns.push(fn);
  }
  const renderSection = (label, fns) => {
    if (!fns.length) return;
    // Header only when BOTH sections have entries — for a namespace
    // with only fns or only types, the label adds noise.
    if (typeFns.length && functionFns.length) {
      const head = document.createElement('div');
      head.className = 'ns-section-label';
      head.textContent = label;
      childGroup.appendChild(head);
    }
    for (const fn of fns) childGroup.appendChild(buildFnItem(fn));
  };
  renderSection('Types', typeFns);
  renderSection('Functions', functionFns);

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


// "Only services" filter toggle handler — wired from the
// checkbox in #search-bar. Lazy-loads the fn-id set on first
// flip-on (and refreshes on every flip-on after so newly-added
// services appear without page reload).
async function setOnlyServicesFilter(checked) {
  onlyServicesFilter = !!checked;
  if (onlyServicesFilter && typeof loadAllServiceFnIds === 'function') {
    serviceFnIds = await loadAllServiceFnIds();
  }
  updateEntityList(graphData);
}

/**
 * Update the entity list in sidebar as a namespace tree
 */
function updateEntityList(data) {
  const list = document.getElementById('entity-list');
  list.innerHTML = '';

  let tree = buildNsTree(data);

  // Apply "only services" filter first — fn-id set match, no text.
  // Order with search filter: services first, then text — the result
  // is "services whose name contains the text" when both are active.
  if (onlyServicesFilter) {
    tree = filterToFnIds(tree, serviceFnIds) || {children: new Map(), fns: []};
  }
  // Apply search filter
  if (searchFilter) {
    tree = filterNsNode(tree, searchFilter, null) || { children: new Map(), fns: [] };
  }

  // Secrets section — collapsible block ABOVE the namespace tree.
  // Self-loading: when the user expands it the first time, it kicks
  // off `loadSecrets()` + a re-render. Skipped while a filter is
  // active so the section doesn't get pruned visually.
  if (!searchFilter && !onlyServicesFilter && typeof buildSecretsSection === 'function') {
    list.appendChild(buildSecretsSection());
  }

  // Render top-level namespaces (sorted)
  const sortedNs = [...tree.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [name, node] of sortedNs) {
    renderNsNode(list, name, node, '');
  }

  // Top-level fns without a namespace — usually the primitive type-
  // rows seeded at boot (any, bool, int, …) plus the occasional
  // user-created top-level fn. Wrap them in a collapsible `_types`
  // group (default collapsed) so they don't dwarf the namespace
  // tree visually. The expandedNamespaces machinery from
  // `renderNsNode` is reused via a synthesised path.
  const sortedFns = [...tree.fns].sort((a, b) => a.displayName.localeCompare(b.displayName));
  if (sortedFns.length > 0) {
    const groupPath = '_types';
    const isOpen = expandedNamespaces.has(groupPath);
    const header = document.createElement('div');
    header.className = 'ns-header ns-header-pseudo';
    const arrow = document.createElement('span');
    arrow.className = 'ns-arrow' + (isOpen ? '' : ' collapsed');
    arrow.textContent = isOpen ? '▼' : '▶';
    header.appendChild(arrow);
    const label = document.createElement('span');
    label.className = 'ns-label';
    label.textContent = 'types';
    header.appendChild(label);
    const count = document.createElement('span');
    count.className = 'ns-count';
    count.textContent = sortedFns.length;
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
      for (const fn of sortedFns) {
        childGroup.appendChild(buildFnItem(fn));
      }
      list.appendChild(childGroup);
    }
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

