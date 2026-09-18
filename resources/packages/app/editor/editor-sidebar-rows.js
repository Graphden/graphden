// Editor Sidebar — building the ROWS: one fn item, one namespace node, one
// child group.
//
// `buildFnItem` (the fn row with
// its kind glyph, badges, test dot, `fx` mark and ⋯ trigger), `renderNsNode`
// (a namespace header + lazy children — `loadNamespaceFns` → `?scope=namespace`
// on first expand) and `buildNsChildGroup` (the `.ns-children` container the
// diff ghosts and the tree keys walk). Pure DOM construction over
// `graphData` / `lookups`; the lens predicates it consults live in
// editor-sidebar-lens.js, the tree walk that calls it in editor-sidebar.js.

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

  // Problem markers — always on: a fn that fails, mistypes or duplicates
  // says so where you work, not only in a drawer. Each carries its count.
  const problems = fnProblemSet(fn);
  if (problems.has('failed')) {
    const n = getFailureCountForFnId(fn.id);
    const m = document.createElement('span');
    m.className = 'fn-kind-marker kind-marker-failed';
    m.textContent = '✕' + n;
    m.title = n + ' unresolved failed run' + (n === 1 ? '' : 's');
    item.appendChild(m);
  }
  if (problems.has('type-errors')) {
    const n = fn['type-error-count'];
    const m = document.createElement('span');
    m.className = 'fn-kind-marker kind-marker-type-error';
    m.textContent = '⚠' + n;
    m.title = n + ' type error' + (n === 1 ? '' : 's');
    item.appendChild(m);
  }
  if (problems.has('lint')) {
    const n = getLintCountForFnId(fn.id);
    const m = document.createElement('span');
    m.className = 'fn-kind-marker kind-marker-lint';
    m.textContent = '⚐' + n;
    m.title = n + ' lint finding' + (n === 1 ? '' : 's') + ' — see the Lint tab or the Inspector';
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
      + ' in this namespace';
    header.appendChild(chip);
  }
  // Failed-runs / lint chips — the same per-namespace idea over the two
  // primed caches (editor-problems.js): fns with problems in this namespace.
  if (node?.nsId !== undefined) {
    const nsFailed = (typeof nsFailureCount === 'function') ? nsFailureCount(node.nsId) : 0;
    if (nsFailed > 0) {
      const chip = document.createElement('span');
      chip.className = 'ns-problem-chip ns-failed-chip';
      chip.textContent = '✕ ' + nsFailed;
      chip.title = nsFailed + ' unresolved failed run' + (nsFailed === 1 ? '' : 's') + ' in this namespace';
      header.appendChild(chip);
    }
    const nsLint = (typeof nsLintCount === 'function') ? nsLintCount(node.nsId) : 0;
    if (nsLint > 0) {
      const chip = document.createElement('span');
      chip.className = 'ns-problem-chip ns-lint-chip';
      chip.textContent = '⚐ ' + nsLint;
      chip.title = nsLint + ' fn' + (nsLint === 1 ? '' : 's') + ' with lint findings in this namespace';
      header.appendChild(chip);
    }
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
