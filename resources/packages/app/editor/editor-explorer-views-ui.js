// Editor — Explorer FILTERS and VIEWS, the UI half: the dynamic chip row
// under the kind chips (one chip per active namespace / uses / effect /
// unused filter, each with ×), the "+ filter" menu (namespaces checklist,
// the fn picker for "uses", effects, unused), the VIEW chip (`#gd-ws-chip`
// — the active set's name) and its popover (your views, the views saved
// in the graph, Save view, "Save in the graph…"). The model — state,
// storage, predicates, the members fetch — is editor-explorer-filters.js;
// this file only calls its exported verbs and re-renders.
//
// Globals consumed: gdFilters, gdFilterCount, gdFiltersActive,
// gdActiveViewName, gdReadViews, gdToggle* / gdAddUses / gdRemoveUses /
// gdClearFilters / gdApplyView / gdSaveView / gdDeleteView,
// gdFetchSharedViews, gdInvalidateSharedViews (the model); graphData,
// openFnPicker, installPopoverDismiss, ensurePopoverClose,
// focusIntoDialog, returnFocusTo, anchorBelowClamped, postEntity,
// authMutate, authFetch, API, resolveJustCreatedFn, gdLastUsedNs,
// initGraph, selectJustCreatedFn, gdToast, gdAnnounce.

// ---------------------------------------------------------------------------
// The chip row — one chip per active filter (kind chips stay as the fixed
// toggles in #kind-filters; this renders the DYNAMIC ones after them).
// ---------------------------------------------------------------------------

function _chip(host, axis, name, title, kind, value, onRemove, missing) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'kind-toggle gd-filter-chip' + (missing ? ' gd-filter-chip-missing' : '');
  b.setAttribute('aria-pressed', 'true');
  b.title = (missing ? 'No longer exists — this filter matches nothing. ' : '') + title + ' — click to remove';
  b.setAttribute('aria-label', 'Remove filter: ' + title + (missing ? ' (no longer exists)' : ''));
  b.dataset.filterKind = kind;
  b.dataset.filterValue = value;
  const body = document.createElement('span');
  body.className = 'gd-filter-graph-host';
  b.appendChild(body);
  host.appendChild(b);
  window.gdExplorerGraph.mountFilter(body, 'filter-chip', {axis, name, selected: true, missing: !!missing});
  b.addEventListener('click', () => {
    if (host.contains(b)) window.gdExplorerGraph.selectFilter(kind, value, onRemove);
  });
  return b;
}

function gdRenderFilterChips() {
  const host = document.getElementById('gd-filter-chips');
  if (!host) return;
  const active = document.activeElement;
  const buttons = [...host.children];
  const focusIndex = buttons.indexOf(active);
  const focusKind = active?.dataset?.filterKind;
  const focusValue = active?.dataset?.filterValue;
  window.gdExplorerGraph.disposeFilters(host);
  host.replaceChildren();
  if (typeof graphData === 'undefined' || !graphData) return;
  for (const p of gdFilters().namespaces) _chip(host, 'namespace', p, 'Only namespace ' + p,
    'remove-namespace', p, () => gdToggleNamespace(p));
  for (const p of gdFilters().exclude) _chip(host, 'exclude', p, 'Hide namespace ' + p,
    'remove-exclude', p, () => gdToggleExclude(p));
  const missing = (typeof gdViewMissing === 'function') ? gdViewMissing() : { uses: [], views: [] };
  for (const u of gdFilters().uses) _chip(host, 'uses', u.name, 'Only fns using ' + u.name,
    'remove-uses', u.id, () => gdRemoveUses(u.id), missing.uses.includes(u.id));
  for (const e of gdFilters().effects) _chip(host, 'effect', e, 'Only fns with effect ' + e,
    'remove-effect', e, () => gdToggleEffect(e));
  if (gdFilters().unused) _chip(host, 'unused', 'unused', 'Only unused fns',
    'remove-unused', '', () => gdToggleUnused());
  if (gdFilters().name) _chip(host, 'name', gdFilters().name, 'Only names containing ' + gdFilters().name,
    'remove-name', '', () => gdSetName(''));
  for (const v of gdFilters().views) _chip(host, 'view', v.name, 'Only fns in view ' + v.name,
    'remove-view', v.id, () => gdRemoveView(v.id), missing.views.includes(v.id));
  if (focusIndex >= 0) {
    const next = [...host.children].find(button => button.dataset.filterKind === focusKind
      && button.dataset.filterValue === focusValue)
      || host.children[Math.min(focusIndex, host.children.length - 1)]
      || document.getElementById('gd-filter-add');
    next?.focus?.();
  }
  const addBtn = document.getElementById('gd-filter-add');
  if (addBtn) addBtn.hidden = false;
}

// The context chip (`#gd-ws-chip`) — names the view, else the set.
function gdSyncViewChip() {
  const b = document.querySelector('#gd-ws-chip b');
  const k = document.querySelector('#gd-ws-chip .gd-ctx-k');
  if (k) k.textContent = 'view';
  if (!b) return;
  const n = gdFilterCount();
  b.textContent = gdActiveViewName() ? gdActiveViewName() : (n === 0 ? 'All functions' : n + (n === 1 ? ' filter' : ' filters'));
  const chip = document.getElementById('gd-ws-chip');
  if (chip) {
    chip.classList.toggle('gd-ctx-chip-active', n > 0);
    // The applied view by name — what the tour's / a test's check reads.
    if (gdActiveViewName()) chip.setAttribute('data-view', gdActiveViewName()); else chip.removeAttribute('data-view');
    if (gdActiveViewId()) chip.setAttribute('data-view-id', gdActiveViewId()); else chip.removeAttribute('data-view-id');
  }
}

// ---------------------------------------------------------------------------
// "+ filter" — the picker menu for the axes that need a value
// ---------------------------------------------------------------------------

let _addPopEl = null;
let _addPopAnchor = null;
let _addPopOwner = null;

function _filterOwner() {
  return JSON.stringify([window.gdPrefOwner || null, window.gdAccount?.id || null,
    typeof graphdenCurrentOrg === 'string' ? graphdenCurrentOrg : null,
    typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : null]);
}

function gdCloseFilterAdd() {
  if (!_addPopEl) return;
  const hadFocus = _addPopEl.contains(document.activeElement);
  window.gdExplorerGraph.disposeFilters(_addPopEl);
  _addPopEl.remove();
  _addPopEl = null;
  _addPopOwner = null;
  if (hadFocus && typeof returnFocusTo === 'function') returnFocusTo(_addPopAnchor);
}

function _rootNamespaces() {
  const out = [];
  const nss = (typeof graphData !== 'undefined' && graphData) ? (graphData.namespaces || []) : [];
  nss.forEach((n) => { if (!n['parent-id'] && n.name) out.push({ name: n.name, desc: n.description || '' }); });
  out.sort((a, b) => a.name.localeCompare(b.name));
  return out;
}

const EFFECT_KINDS = ['io', 'db', 'network', 'state', 'time', 'random', 'env', 'process', 'raw-sql'];

function _section(el, title) {
  const cap = document.createElement('div');
  cap.className = 'gd-views-pop-cap';
  cap.textContent = title;
  el.appendChild(cap);
}

function _filterOptionSection(el, title, key, cls) {
  const section = document.createElement('div');
  section.dataset.filterSection = key;
  if (title) _section(section, title);
  const host = document.createElement('div');
  host.dataset.filterOptions = key;
  if (cls) host.className = cls;
  section.appendChild(host);
  el.appendChild(section);
  return {section, host};
}

function _buildFilterAdd(el) {
  const head = document.createElement('div');
  head.className = 'gd-views-pop-head';
  head.textContent = 'Add a filter';
  el.appendChild(head);
  const hint = document.createElement('div');
  hint.className = 'gd-views-pop-hint';
  hint.textContent = 'Filters combine (AND). Namespaces and kinds OR within their row.';
  el.appendChild(hint);

  _filterOptionSection(el, 'Namespaces — only these', 'namespaces');
  _filterOptionSection(el, 'Hidden by you — restore', 'exclude');
  _filterOptionSection(el, 'Uses a function', 'uses');
  _filterOptionSection(el, 'Effect', 'effects', 'gd-filter-fx-row');
  _filterOptionSection(el, 'In a graph view', 'views');

  _section(el, 'Name contains');
  const nameRow = document.createElement('div');
  nameRow.className = 'gd-views-form';
  const nameIn = document.createElement('input');
  nameIn.type = 'text';
  nameIn.className = 'gd-views-input';
  nameIn.placeholder = 'part of a name — Enter to add the chip';
  nameIn.setAttribute('aria-label', 'Only names containing');
  nameIn.value = gdFilters().name || '';
  nameIn.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    gdSetName(nameIn.value);
    if (_addPopEl === el) _refreshFilterAdd(el);
  });
  nameRow.appendChild(nameIn);
  el.appendChild(nameRow);
  _filterOptionSection(el, '', 'unused');
  if (typeof ensurePopoverClose === 'function') ensurePopoverClose(el, gdCloseFilterAdd, 'Close');
}

function _filterOption(el, host, option) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = option.cls || 'gd-views-row';
  button.dataset.filterKind = option.kind;
  button.dataset.filterValue = option.value;
  if (option.data) button.dataset[option.data.key] = option.data.value;
  if (option.title) button.title = option.title;
  if (option.role) {
    button.setAttribute('role', option.role);
    button.setAttribute('aria-checked', option.selected ? 'true' : 'false');
  }
  if (option.pressed) button.setAttribute('aria-pressed', option.selected ? 'true' : 'false');
  const body = document.createElement('span');
  body.className = 'gd-filter-graph-host';
  button.appendChild(body);
  host.appendChild(button);
  window.gdExplorerGraph.mountFilter(body, 'filter-option', {
    axis: option.axis, name: option.name, selected: !!option.selected, missing: false,
  });
  button.addEventListener('click', () => {
    if (_addPopEl !== el || _addPopOwner !== _filterOwner() || !el.contains(button)) return;
    window.gdExplorerGraph.selectFilter(option.kind, option.value, () => {
      option.execute();
      if (_addPopEl === el) _refreshFilterAdd(el);
    });
  });
}

function _renderFilterOptions(el, key, options) {
  const section = el.querySelector('[data-filter-section="' + key + '"]');
  const host = section?.querySelector('[data-filter-options="' + key + '"]');
  if (!host) return;
  section.hidden = options.length === 0 && (key === 'exclude' || key === 'views');
  const prior = document.activeElement;
  const buttons = [...host.children];
  const index = buttons.indexOf(prior);
  const kind = prior?.dataset?.filterKind;
  const value = prior?.dataset?.filterValue;
  window.gdExplorerGraph.disposeFilters(host);
  host.replaceChildren();
  for (const option of options) _filterOption(el, host, option);
  if (index >= 0) {
    const next = [...host.children].find(button => button.dataset.filterKind === kind
      && button.dataset.filterValue === value)
      || host.children[Math.min(index, host.children.length - 1)]
      || el.querySelector('[data-filter-options="uses"] button');
    next?.focus?.();
  }
}

function _refreshFilterAdd(el) {
  if (_addPopEl !== el || _addPopOwner !== _filterOwner()
      || typeof graphData === 'undefined' || !graphData) return;
  const filters = gdFilters();
  _renderFilterOptions(el, 'namespaces', _rootNamespaces().map(n => ({
    axis: 'namespace', name: n.name, selected: filters.namespaces.includes(n.name),
    kind: 'select-namespace', value: n.name,
    cls: 'gd-views-row gd-ws-opt' + (filters.namespaces.includes(n.name) ? ' sel' : ''),
    role: 'checkbox', title: n.desc || n.name, data: {key: 'ws', value: n.name},
    execute: () => gdToggleNamespace(n.name),
  })));
  _renderFilterOptions(el, 'exclude', filters.exclude.slice().sort().map(path => ({
    axis: 'restore', name: path, kind: 'restore-exclude', value: path,
    data: {key: 'restore', value: path}, execute: () => gdToggleExclude(path),
  })));
  _renderFilterOptions(el, 'uses', [{
    axis: 'uses-picker', name: 'pick a fn — only what is built on it',
    kind: 'pick-uses', value: '', data: {key: 'action', value: 'add-uses'},
    execute: () => {
      if (typeof openFnPicker !== 'function') return;
      const anchor = _addPopAnchor || el.querySelector('[data-filter-options="uses"] button');
      gdCloseFilterAdd();
      openFnPicker({anchorEl: anchor, title: 'Only fns that use…', onPick: fn => gdAddUses(fn)});
    },
  }]);
  _renderFilterOptions(el, 'effects', EFFECT_KINDS.map(effect => ({
    axis: 'effect', name: effect, selected: filters.effects.includes(effect),
    kind: 'select-effect', value: effect, cls: 'kind-toggle', pressed: true,
    data: {key: 'effect', value: effect}, execute: () => gdToggleEffect(effect),
  })));
  const shared = typeof gdSharedViewsCached === 'function' ? gdSharedViewsCached() : null;
  _renderFilterOptions(el, 'views', (shared || []).map(view => ({
    axis: 'view', name: view.name, selected: filters.views.some(item => item.id === view.id),
    kind: 'select-view', value: view.id,
    cls: 'gd-views-row' + (filters.views.some(item => item.id === view.id) ? ' sel' : ''),
    role: 'checkbox', title: _summarise(view.filters), data: {key: 'view', value: view.id},
    execute: () => {
      if (gdFilters().views.some(item => item.id === view.id)) gdRemoveView(view.id);
      else gdAddView(view);
    },
  })));
  _renderFilterOptions(el, 'unused', [{
    axis: 'unused', name: 'Unused — nothing references or extends it',
    selected: filters.unused, kind: 'select-unused', value: '', role: 'checkbox',
    data: {key: 'action', value: 'unused'}, execute: () => gdToggleUnused(),
  }]);
}

function gdOpenFilterAdd(anchorEl) {
  if (_addPopEl) { gdCloseFilterAdd(); return; }
  if (typeof graphData === 'undefined' || !graphData) return;
  const el = document.createElement('div');
  el.className = 'gd-views-pop gd-filter-add-pop';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'false');
  el.setAttribute('aria-label', 'Add a filter');
  _buildFilterAdd(el);
  document.body.appendChild(el);
  _addPopEl = el;
  _addPopAnchor = anchorEl || null;
  _addPopOwner = _filterOwner();
  _refreshFilterAdd(el);
  if (typeof anchorBelowClamped === 'function' && anchorEl) anchorBelowClamped(el, anchorEl);
  if (typeof focusIntoDialog === 'function') focusIntoDialog(el);
  gdFetchSharedViews().then(() => {
    if (_addPopEl === el && _addPopOwner === _filterOwner()) _refreshFilterAdd(el);
  });
}

// ---------------------------------------------------------------------------
// The view chip popover — saved views (personal + in the graph), save,
// share, clear.
// ---------------------------------------------------------------------------

let _viewPopEl = null;
let _viewPopAnchor = null;

function gdCloseViewPop() {
  if (!_viewPopEl) return;
  const hadFocus = _viewPopEl.contains(document.activeElement);
  _viewPopEl.remove();
  _viewPopEl = null;
  if (hadFocus && typeof returnFocusTo === 'function') returnFocusTo(_viewPopAnchor);
}

function gdOpenViewPop(anchorEl) {
  if (_viewPopEl) { gdCloseViewPop(); return; }
  const el = document.createElement('div');
  el.id = 'gd-ws-pop';
  el.className = 'gd-views-pop gd-view-pop';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'false');
  el.setAttribute('aria-label', 'Views');
  _renderViewPop(el);
  document.body.appendChild(el);
  _viewPopEl = el;
  _viewPopAnchor = anchorEl || null;
  if (typeof anchorBelowClamped === 'function' && anchorEl) anchorBelowClamped(el, anchorEl);
  if (typeof focusIntoDialog === 'function') focusIntoDialog(el);
  gdFetchSharedViews().then(() => { if (_viewPopEl === el) _renderViewPop(el); });
}

function _summarise(f) {
  const parts = [];
  if (f.namespaces.length) parts.push('in ' + f.namespaces.join(', '));
  if (f.exclude.length) parts.push('not ' + f.exclude.join(', '));
  if (f.kinds.length) parts.push(f.kinds.join('/'));
  if (f.problems?.length) parts.push(f.problems.join('/'));
  for (const u of f.uses) parts.push('uses ' + u.name);
  for (const e of f.effects) parts.push('fx ' + e);
  if (f.unused) parts.push('unused');
  if (f.name) parts.push('name ' + f.name);
  for (const v of f.views || []) parts.push('view ' + v.name);
  return parts.join(' · ') || 'everything';
}

function _renderViewPop(el) {
  el.replaceChildren();
  const head = document.createElement('div');
  head.className = 'gd-views-pop-head';
  head.textContent = 'Views';
  el.appendChild(head);
  const hint = document.createElement('div');
  hint.className = 'gd-views-pop-hint';
  hint.textContent = 'Save in the graph to share on this branch. Device only stays in this browser.';
  el.appendChild(hint);

  const all = document.createElement('button');
  all.type = 'button';
  all.className = 'gd-views-row' + (gdFiltersActive() ? '' : ' sel');
  all.dataset.wsAll = '1';
  all.textContent = '◍ All functions — no filters';
  all.addEventListener('click', () => { gdClearFilters(); gdCloseViewPop(); });
  el.appendChild(all);

  const mine = gdReadViews();
  if (mine.length) _section(el, 'Device only');
  for (const v of mine) {
    const row = document.createElement('div');
    row.className = 'gd-views-row';
    const apply = document.createElement('button');
    apply.type = 'button';
    apply.className = 'gd-views-apply';
    apply.textContent = (!gdActiveViewId() && gdActiveViewName() === v.name ? '● ' : '') + v.name;
    apply.title = _summarise(v.filters);
    apply.setAttribute('aria-label', 'Apply view ' + v.name);
    apply.addEventListener('click', () => { gdApplyView(v); gdCloseViewPop(); });
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'gd-views-del';
    del.textContent = '×';
    del.setAttribute('aria-label', 'Delete view ' + v.name);
    del.addEventListener('click', () => { gdDeleteView(v.name); _renderViewPop(el); });
    row.appendChild(apply);
    row.appendChild(del);
    el.appendChild(row);
  }

  const shared = gdSharedViewsCached() || [];
  if (shared.length) _section(el, 'In the graph');
  for (const v of shared) {
    const row = document.createElement('div');
    row.className = 'gd-views-row';
    const apply = document.createElement('button');
    apply.type = 'button';
    apply.className = 'gd-views-apply';
    apply.textContent = (gdActiveViewId() === v.id ? '● ' : '') + v.name;
    apply.title = _summarise(v.filters);
    apply.setAttribute('aria-label', 'Apply view ' + v.name + ' from the graph');
    apply.disabled = !!v.unsupported?.length;
    if (apply.disabled) apply.title = 'Computed filters — edit the graph to preserve them.';
    apply.addEventListener('click', () => { gdApplyView(v); gdCloseViewPop(); });
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'gd-views-del';
    open.textContent = 'Edit graph';
    open.title = 'Open the view fn on the canvas';
    open.setAttribute('aria-label', 'Open view fn ' + v.name);
    open.addEventListener('click', () => {
      gdCloseViewPop();
      if (typeof gdNavigateToFn === 'function') gdNavigateToFn(v.id, v.name);
    });
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'gd-views-del';
    edit.textContent = 'Edit filters';
    edit.dataset.editView = v.id;
    edit.disabled = !!v.unsupported?.length;
    edit.title = edit.disabled ? 'Computed filters must be edited on the graph.' : 'Change this graph view using typed filters';
    edit.addEventListener('click', async () => {
      if (!(await gdBeginGraphViewEdit(v))) return;
      const anchor = _viewPopAnchor;
      gdCloseViewPop();
      gdOpenFilterAdd(anchor);
    });
    row.appendChild(apply);
    row.appendChild(edit);
    row.appendChild(open);
    el.appendChild(row);
  }

  // Save the active set.
  if (gdFiltersActive() || gdEditingGraphView()) {
    _section(el, gdEditingGraphView() ? 'Edit ' + gdEditingGraphView().name : 'Save the current filters');
    const form = document.createElement('div');
    form.className = 'gd-views-form';
    const nameIn = document.createElement('input');
    nameIn.type = 'text';
    nameIn.placeholder = 'View name';
    nameIn.className = 'gd-views-input';
    nameIn.setAttribute('aria-label', 'View name');
    if (gdEditingGraphView()) nameIn.value = gdEditingGraphView().name;
    else if (gdActiveViewName()) nameIn.value = gdActiveViewName();
    const msg = document.createElement('div');
    msg.className = 'gd-views-form-msg';
    msg.setAttribute('role', 'status');
    msg.hidden = true;
    const save = document.createElement('button');
    save.type = 'button';
    save.className = 'gd-views-save';
    save.textContent = 'Device only';
    save.title = 'Save only in this browser; this does not change a graph view.';
    const trySave = () => {
      const name = nameIn.value.trim();
      if (!name) { msg.textContent = 'Give the view a name'; msg.hidden = false; nameIn.focus(); return; }
      gdSaveView(name);
      _renderViewPop(el);
    };
    save.addEventListener('click', trySave);
    nameIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); if (window.API && API.api_views_save) share.click(); else trySave(); } });
    nameIn.addEventListener('input', () => { msg.hidden = true; });
    const share = document.createElement('button');
    share.type = 'button';
    share.className = 'gd-views-save gd-views-share';
    share.textContent = gdEditingGraphView() ? 'Save changes' : 'Save in graph';
    share.disabled = typeof isAuthenticated === 'function' && !isAuthenticated();
    share.title = share.disabled ? 'Sign in to save a graph view'
      : (gdEditingGraphView() ? 'Update this graph view for everyone on the branch' : 'Save a versioned view for everyone on the branch');
    share.addEventListener('click', async () => {
      if (!nameIn.value.trim()) { msg.textContent = 'Give the view a name'; msg.hidden = false; nameIn.focus(); return; }
      share.disabled = save.disabled = nameIn.disabled = true;
      try {
        await gdShareViewToGraph(nameIn.value.trim());
        if (_viewPopEl === el) _renderViewPop(el);
      } catch (error) {
        msg.textContent = error.message;
        msg.hidden = false;
      } finally { save.disabled = nameIn.disabled = false; share.disabled = typeof isAuthenticated === 'function' && !isAuthenticated(); }
    });
    form.appendChild(nameIn);
    form.appendChild(msg);
    if (window.API && API.api_views_save) form.appendChild(share);
    form.appendChild(save);
    el.appendChild(form);
  }

  if (typeof ensurePopoverClose === 'function') ensurePopoverClose(el, gdCloseViewPop, 'Close views');
}

// graph-first-exception: the draft is the browser's active filter set. The
// atomic graph API owns validation, full replacement and publication.
let _graphViewSaveSeq = 0;
async function gdShareViewToGraph(name) {
  if (!(window.API && API.api_views_save)) throw new Error('Saving to the graph is not available here');
  const seq = ++_graphViewSaveSeq;
  const edit = gdEditingGraphView();
  const revision = gdFilterRevision();
  const branch = typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : null;
  const filters = gdFilters();
  const command = {name, filters: {...filters,
    uses: filters.uses.map(row => row.id), views: filters.views.map(row => row.id)}};
  if (edit) command.id = edit.id;
  else {
    command['create-id'] = crypto.randomUUID();
    command['namespace-id'] = typeof gdLastUsedNs === 'function' ? gdLastUsedNs() || null : null;
    if (typeof _tourTrackGraphViewCreation === 'function') _tourTrackGraphViewCreation(command);
  }
  const response = await authFetch(API.api_views_save, {method: 'POST',
    headers: {'Content-Type': 'application/json'}, body: JSON.stringify(command)});
  const result = await response.json();
  if (!response.ok || !result.ok || !result.committed || !result.view?.id) {
    if ((result.committed === false || (response.status >= 400 && response.status < 500)) && typeof _tourRejectGraphViewCreation === 'function') _tourRejectGraphViewCreation(command);
    throw new Error((result.reason || result.error || 'Could not save the view')
      + (edit ? ' Open Edit graph to change inherited or computed clauses.' : ''));
  }
  let reloadFailed = false;
  if (typeof initGraph === 'function' && (typeof getCurrentBranchName !== 'function' || getCurrentBranchName() === branch)) {
    try { await initGraph(); } catch (_) { reloadFailed = true; }
  }
  gdInvalidateSharedViews();
  await gdFetchSharedViews(true);
  const saved = gdSharedViewsCached().find(view => view.id === result.view.id);
  // A late successful write must not replace a newer draft or another branch.
  if (seq === _graphViewSaveSeq && gdFilterRevision() === revision && gdEditingGraphView() === edit
      && (typeof getCurrentBranchName !== 'function' || getCurrentBranchName() === branch)) {
    if (saved) await gdApplyView(saved);
    else gdMarkViewApplied(result.view.name, result.view.id);
  }
  if (typeof gdToast === 'function') gdToast(reloadFailed || !saved || result['publication-warnings']?.length
    ? 'View saved. Refresh to reload derived state.' : 'View saved in the graph');
  return result.view;
}

// ---------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------

if (typeof installPopoverDismiss === 'function') {
  installPopoverDismiss({
    getEl: () => _addPopEl, getAnchor: () => _addPopAnchor,
    isVisible: () => !!_addPopEl, onDismiss: gdCloseFilterAdd,
    trapFocus: true, getReturnFocus: () => _addPopAnchor,
  });
  installPopoverDismiss({
    getEl: () => _viewPopEl, getAnchor: () => _viewPopAnchor,
    isVisible: () => !!_viewPopEl, onDismiss: gdCloseViewPop,
    trapFocus: true, getReturnFocus: () => _viewPopAnchor,
  });
}

function installExplorerFilters() {
  const chip = document.getElementById('gd-ws-chip');
  if (chip) chip.addEventListener('click', () => gdOpenViewPop(chip));
  const add = document.getElementById('gd-filter-add');
  if (add) add.addEventListener('click', () => gdOpenFilterAdd(add));
  gdRenderFilterChips();
  gdSyncViewChip();
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', installExplorerFilters);
else installExplorerFilters();

window.addEventListener('gd-auth-changed', () => {
  gdCloseFilterAdd();
  gdCloseViewPop();
  const host = document.getElementById('gd-filter-chips');
  if (host) {
    window.gdExplorerGraph.disposeFilters(host);
    host.replaceChildren();
  }
  const chip = document.getElementById('gd-ws-chip');
  if (chip) {
    const label = chip.querySelector('b');
    if (label) label.textContent = 'Loading views…';
    chip.removeAttribute('data-view');
    chip.removeAttribute('data-view-id');
  }
});

// The filter model's own exports live in editor-explorer-filters.js.
window.gdOpenViewPop = gdOpenViewPop;
window.gdOpenFilterAdd = gdOpenFilterAdd;
window.gdRenderFilterChips = gdRenderFilterChips;
window.gdSyncViewChip = gdSyncViewChip;
