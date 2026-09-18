// Editor Sidebar — namespace tree + entity list + filter.
//
// Renders the left sidebar panel: a collapsible tree of namespaces,
// each containing fn entries with click→navigate. The filter input
// at the top hides namespaces / fns that don't match. Expanded-ns
// state persists across re-renders.
//
// Globals consumed: graphData, lookups, navigateToFn, filterText,
// selectedFnId, sidebarCollapsed.
//
// The lens (kind chips, visibility predicates, prime-once
// caches) is editor-sidebar-lens.js, the row builders (fn item / namespace
// node / child group) are editor-sidebar-rows.js, and the Organization /
// Platform surface sections are editor-sidebar-ops.js. This file is the
// tree WALK: state, search, root render, reveal/scroll, updateEntityList.

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
  nsByPath.forEach((_ns, path) => {
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


function announceSearch(query, total, truncated) {
  if (typeof window.gdAnnounce !== 'function') return;
  if (!query) { window.gdAnnounce('Search cleared'); return; }
  const n = total || 0;
  window.gdAnnounce('Search "' + query + '" — ' + n + (n === 1 ? ' match' : ' matches')
                    + (truncated ? ' (showing the first page)' : ''));
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


// Collapsible "(primitives)" node for namespace-less entities — the
// primitive type-rows seeded at boot (any, bool, int, …) plus the
// occasional top-level user fn. (The old "(root)" label was a
// developer-ism — users read "primitives", which is what ~all of its
// content is.) Filtered by the lens; hidden entirely when nothing inside
// is visible. Reuses the expandedNamespaces machinery via a synthesised
// path key.
function renderRootNode(list, rootFns, searchMode) {
  // Under a namespace filter the namespace-less "(primitives)" bucket is
  // outside every picked root — skip it. Search always spans everything.
  if (!searchMode && typeof window.gdNsFiltersActive === 'function'
      && window.gdNsFiltersActive()) {
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
      + ' in this namespace';
    header.appendChild(chip);
  }
  // Failed-runs / lint chips for the null bucket — same caches as the
  // namespace rows (editor-problems.js keys the root by null).
  const rootFailed = (typeof nsFailureCount === 'function') ? nsFailureCount(null) : 0;
  if (rootFailed > 0) {
    const chip = document.createElement('span');
    chip.className = 'ns-problem-chip ns-failed-chip';
    chip.textContent = '✕ ' + rootFailed;
    chip.title = rootFailed + ' unresolved failed run' + (rootFailed === 1 ? '' : 's') + ' in this namespace';
    header.appendChild(chip);
  }
  const rootLint = (typeof nsLintCount === 'function') ? nsLintCount(null) : 0;
  if (rootLint > 0) {
    const chip = document.createElement('span');
    chip.className = 'ns-problem-chip ns-lint-chip';
    chip.textContent = '⚐ ' + rootLint;
    chip.title = rootLint + ' fn' + (rootLint === 1 ? '' : 's') + ' with lint findings in this namespace';
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


function updateEntityList(data) {
  if (typeof renderRecentFns === 'function') renderRecentFns();
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
  primeProblemsOnce();

  // A server-evaluated filter (uses / effect / unused —
  // editor-explorer-filters.js) renders through the same force-expanded
  // pipeline as search: the tree IS the member list. A typed search takes
  // precedence while it lasts; the members resume when it clears.
  const viewActive = !searchFilter
    && (typeof gdServerAxesActive === 'function') && gdServerAxesActive();
  const searchMode = !!searchFilter || viewActive;

  // While a search query is in flight (debounce + round-trip) there are no
  // results yet — show a transient state rather than a misleading empty tree.
  if (searchFilter && _searchResults === null) {
    list.innerHTML = '<div class="loading">Searching…</div>';
    return;
  }
  if (viewActive && gdViewMembers() === null) {
    list.innerHTML = '<div class="loading">Applying filters…</div>';
    return;
  }

  // In search mode the tree is built from the server's matches only; the
  // normal (lazy) tree is built from whatever fn leaves have been loaded.
  const tree = searchMode
    ? buildNsTree({ namespaces: data.namespaces,
                    fns: (searchFilter ? _searchResults : gdViewMembers()) || [] })
    : buildNsTree(data);

  mountOpsSections(list, searchMode);

  // Search: pin EXACT name matches above the tree. Substring matching
  // alone buried `core.arithmetic.add` under dozens of `app.editor`
  // internals that merely contain "add" — the row the reader typed the
  // full name of must be first (tutorial finding). Internals
  // (`_`-private / anon) sort after public exact matches.
  if (searchMode && searchFilter) {
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
  // Search escapes the namespace filters on purpose (finding IS the
  // point) — but YOUR namespaces still deserve the top: results under the
  // picked roots sort before the rest, alphabetical within each half.
  // Outside search the order stays purely alphabetical (the filters
  // already narrow structurally there).
  const nsFiltered = typeof window.gdNsFiltersActive === 'function' && window.gdNsFiltersActive();
  const nsRank = (name) => {
    if (!searchMode || !nsFiltered) return 0;
    return (typeof window.gdNsIncluded === 'function' && window.gdNsIncluded(name)) ? 0 : 1;
  };
  const sortedNs = [...tree.children.entries()].sort((a, b) =>
    (nsRank(a[0]) - nsRank(b[0])) || a[0].localeCompare(b[0]));
  for (const [name, node] of sortedNs) {
    // Search: matched-only structural tree (no kind flip) → keep the skip.
    // Non-search: build all, kinds are a `hidden` overlay set in renderNsNode.
    if (searchMode && !nodeShouldShow(node, searchMode)) continue;
    // Namespace filters (editor-explorer-filters.js): show only the picked
    // roots; always drop excluded paths. Both are structural skips
    // (kind-independent). Search spans everything (above).
    if (!searchMode && typeof window.gdNsExcluded === 'function'
        && window.gdNsExcluded(name)) {
      continue;
    }
    if (!searchMode && typeof window.gdNsIncluded === 'function'
        && !window.gdNsIncluded(name)) {
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
