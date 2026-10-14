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
let _viewPopOwner = null;

function _viewPopAlive(el) {
  return _viewPopEl === el && _viewPopOwner === _filterOwner();
}

function gdCloseViewPop() {
  if (!_viewPopEl) return;
  const hadFocus = _viewPopEl.contains(document.activeElement);
  window.gdExplorerGraph.disposeViews(_viewPopEl);
  _viewPopEl.remove();
  _viewPopEl = null;
  _viewPopOwner = null;
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
  document.body.appendChild(el);
  _viewPopEl = el;
  _viewPopAnchor = anchorEl || null;
  _viewPopOwner = _filterOwner();
  _buildViewPop(el);
  if (typeof anchorBelowClamped === 'function' && anchorEl) anchorBelowClamped(el, anchorEl);
  if (typeof focusIntoDialog === 'function') focusIntoDialog(el);
  gdFetchSharedViews().then(() => { if (_viewPopAlive(el)) _renderViewRows(el); });
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

function _viewBody(parent, axis, name = '', selected = false, missing = false) {
  const body = document.createElement('span');
  body.className = 'gd-view-graph-host';
  parent.appendChild(body);
  window.gdExplorerGraph.mountViewItem(body, {axis, name, selected, missing});
  return body;
}

function _updateViewBody(body, axis, name = '', selected = false, missing = false) {
  window.gdExplorerGraph.updateViewItem(body, {axis, name, selected, missing});
}

function _viewChoice(parent, {cls, axis, kind, value, name = '', selected = false, missing = false,
  title, ariaLabel, execute}) {
  const ownerPop = _viewPopEl;
  const button = document.createElement('button');
  button.type = 'button';
  button.className = cls;
  button.dataset.viewKind = kind;
  if (typeof value === 'string') button.dataset.viewValue = value;
  if (title) button.title = title;
  if (ariaLabel) button.setAttribute('aria-label', ariaLabel);
  parent.appendChild(button);
  _viewBody(button, axis, name, selected, missing);
  button.addEventListener('click', () => {
    if (!_viewPopAlive(ownerPop) || !ownerPop.contains(button) || button.disabled) return;
    const chosen = typeof value === 'function' ? value() : button.dataset.viewValue;
    window.gdExplorerGraph.selectView(button.dataset.viewKind, chosen, execute);
  });
  return button;
}

function _viewSection(parent, axis, name = '') {
  const section = document.createElement('div');
  section.className = 'gd-views-pop-cap';
  parent.appendChild(section);
  _viewBody(section, axis, name);
  return section;
}

function _viewRow(el, key, view) {
  const row = document.createElement('div');
  row.className = 'gd-views-row';
  row.dataset.viewRow = key;
  row._view = view;
  el.querySelector('[data-view-rows]').appendChild(row);
  const graph = key.startsWith('graph:');
  const apply = _viewChoice(row, {cls: 'gd-views-apply', axis: graph ? 'graph-apply' : 'device-apply',
    kind: graph ? 'apply-graph-view' : 'apply-device-view', value: graph ? view.id : view.name,
    name: view.name, selected: graph ? gdActiveViewId() === view.id : !gdActiveViewId() && gdActiveViewName() === view.name,
    missing: graph && !!view.unsupported?.length,
    title: _summarise(view.filters), ariaLabel: 'Apply view ' + view.name + (graph ? ' from the graph' : ''),
    execute: () => { gdApplyView(row._view); gdCloseViewPop(); }});
  if (graph) {
    const edit = _viewChoice(row, {cls: 'gd-views-del', axis: 'graph-edit', kind: 'edit-graph-view', value: view.id,
      missing: !!view.unsupported?.length, execute: async () => {
        const owner = _viewPopOwner;
        if (!(await gdBeginGraphViewEdit(row._view)) || !_viewPopAlive(el) || owner !== _viewPopOwner) return;
        const anchor = _viewPopAnchor;
        gdCloseViewPop(); gdOpenFilterAdd(anchor);
      }});
    edit.dataset.editView = view.id;
    edit.disabled = !!view.unsupported?.length;
    edit.title = edit.disabled ? 'Computed filters must be edited on the graph.' : 'Change this graph view using typed filters';
    _viewChoice(row, {cls: 'gd-views-del', axis: 'graph-open', kind: 'open-graph-view', value: view.id,
      title: 'Open the view fn on the canvas', ariaLabel: 'Open view fn ' + view.name,
      execute: () => { const current = row._view; gdCloseViewPop(); if (typeof gdNavigateToFn === 'function') gdNavigateToFn(current.id, current.name); }});
  } else {
    _viewChoice(row, {cls: 'gd-views-del', axis: 'device-delete', kind: 'delete-device-view', value: view.name,
      ariaLabel: 'Delete view ' + view.name,
      execute: () => { gdDeleteView(row._view.name); _renderViewRows(el); }});
  }
  apply.disabled = graph && !!view.unsupported?.length;
  return row;
}

function _syncViewRow(row, view) {
  row._view = view;
  const graph = row.dataset.viewRow.startsWith('graph:');
  const [apply, secondary, open] = row.querySelectorAll('button');
  const unsupported = graph && !!view.unsupported?.length;
  apply.disabled = unsupported;
  apply.title = unsupported ? 'Computed filters — edit the graph to preserve them.' : _summarise(view.filters);
  apply.setAttribute('aria-label', 'Apply view ' + view.name + (graph ? ' from the graph' : ''));
  _updateViewBody(apply.firstChild, graph ? 'graph-apply' : 'device-apply', view.name,
    graph ? gdActiveViewId() === view.id : !gdActiveViewId() && gdActiveViewName() === view.name, unsupported);
  if (graph) {
    secondary.disabled = unsupported;
    secondary.title = unsupported ? 'Computed filters must be edited on the graph.' : 'Change this graph view using typed filters';
    _updateViewBody(secondary.firstChild, 'graph-edit', '', false, unsupported);
    open.setAttribute('aria-label', 'Open view fn ' + view.name);
  } else secondary.setAttribute('aria-label', 'Delete view ' + view.name);
}

function _renderViewRows(el) {
  if (!_viewPopAlive(el)) return;
  const host = el.querySelector('[data-view-rows]');
  const old = new Map([...host.children].map(node => [node.dataset.viewRow, node]));
  const wanted = [];
  const add = (key, create, view) => {
    const node = old.get(key) || create();
    old.delete(key);
    if (view) _syncViewRow(node, view);
    wanted.push(node);
  };
  const mine = gdReadViews();
  const shared = gdSharedViewsCached() || [];
  add('all', () => {
    const all = _viewChoice(host, {cls: 'gd-views-row', axis: 'all', kind: 'clear-view', value: '',
      execute: () => { gdClearFilters(); gdCloseViewPop(); }});
    all.dataset.wsAll = '1'; all.dataset.viewRow = 'all';
    return all;
  });
  const all = wanted[0];
  all.classList.toggle('sel', !gdFiltersActive());
  if (mine.length) add('device-section', () => {
    const node = _viewSection(host, 'device-section'); node.dataset.viewRow = 'device-section'; return node;
  });
  for (const view of mine) add('device:' + view.name, () => _viewRow(el, 'device:' + view.name, view), view);
  if (shared.length) add('graph-section', () => {
    const node = _viewSection(host, 'graph-section'); node.dataset.viewRow = 'graph-section'; return node;
  });
  for (const view of shared) add('graph:' + view.id, () => _viewRow(el, 'graph:' + view.id, view), view);
  for (const node of old.values()) { window.gdExplorerGraph.disposeViews(node); node.remove(); }
  for (let index = 0; index < wanted.length; index++) {
    if (wanted[index] !== host.children[index]) host.insertBefore(wanted[index], host.children[index] || null);
  }
}

function _viewFormPolicy(el) {
  const edit = gdEditingGraphView();
  return window.gdExplorerGraph.formPolicy({filtersActive: gdFiltersActive(), editing: !!edit,
    authenticated: typeof isAuthenticated === 'function' && isAuthenticated(),
    shareAvailable: !!(window.API && API.api_views_save), pending: !!el._viewForm?.pending,
    name: el._viewForm?.input.value ?? edit?.name ?? gdActiveViewName() ?? ''});
}

function _applyViewFormPolicy(form, policy) {
  form.policy = policy;
  form.save.disabled = !policy['device-available'];
  form.share.hidden = !policy['graph-visible'];
  form.share.disabled = !policy['graph-available'];
  form.input.disabled = !policy['device-available'];
  form.share.title = form.pending ? 'Saving graph view'
    : !policy['graph-available'] ? 'Sign in to save a graph view'
    : (policy.mode === 'edit' ? 'Update this graph view for everyone on the branch'
      : 'Save a versioned view for everyone on the branch');
}

function _renderViewForm(el) {
  const policy = _viewFormPolicy(el);
  const form = el._viewForm;
  if (!policy.visible) { if (form) { window.gdExplorerGraph.disposeViews(form.section); window.gdExplorerGraph.disposeViews(form.root); form.section.remove(); form.root.remove(); el._viewForm = null; } return; }
  if (form) {
    const edit = gdEditingGraphView();
    _updateViewBody(form.section.firstChild, policy.mode === 'edit' ? 'edit-section' : 'save-section', edit?.name || '');
    _updateViewBody(form.share.firstChild, policy.mode === 'edit' ? 'save-graph-edit' : 'save-graph', '', false,
      policy['graph-visible'] && !policy['graph-available'] && !form.pending);
    _applyViewFormPolicy(form, policy);
    return;
  }
  const edit = gdEditingGraphView();
  const section = _viewSection(el, policy.mode === 'edit' ? 'edit-section' : 'save-section', edit?.name || '');
  const root = document.createElement('div'); root.className = 'gd-views-form';
  el.appendChild(root);
  const input = document.createElement('input');
  input.type = 'text'; input.placeholder = 'View name'; input.className = 'gd-views-input';
  input.setAttribute('aria-label', 'View name');
  input.value = edit?.name || gdActiveViewName() || '';
  const msg = document.createElement('div'); msg.className = 'gd-views-form-msg';
  msg.setAttribute('role', 'status'); msg.hidden = true;
  const invalid = current => { msg.textContent = current['validation-message']; msg.hidden = false; input.focus(); };
  const save = _viewChoice(root, {cls: 'gd-views-save', axis: 'save-device', kind: 'save-device-view', value: () => input.value.trim(),
    title: 'Save only in this browser; this does not change a graph view.', execute: () => {
      const current = _viewFormPolicy(el);
      if (!current['device-available'] || el._viewForm.pending) return;
      const name = input.value.trim();
      if (!current['name-valid']) { invalid(current); return; }
      gdSaveView(name); _renderViewRows(el); _renderViewForm(el);
    }});
  const share = _viewChoice(root, {cls: 'gd-views-save gd-views-share', axis: policy.mode === 'edit' ? 'save-graph-edit' : 'save-graph',
    kind: 'save-graph-view', value: () => input.value.trim(), missing: typeof isAuthenticated === 'function' && !isAuthenticated(),
    execute: async () => {
      const current = _viewFormPolicy(el);
      if (!current['graph-available'] || el._viewForm.pending || !(window.API && API.api_views_save)
        || (typeof isAuthenticated === 'function' && !isAuthenticated())) return;
      const name = input.value.trim();
      if (!current['name-valid']) { invalid(current); return; }
      const owner = _viewPopOwner;
      el._viewForm.pending = true;
      _renderViewForm(el);
      try {
        await gdShareViewToGraph(name);
        if (_viewPopAlive(el) && owner === _viewPopOwner) { _renderViewRows(el); _renderViewForm(el); }
      } catch (error) {
        if (_viewPopAlive(el) && owner === _viewPopOwner) { msg.textContent = error.message; msg.hidden = false; }
      } finally {
        if (_viewPopAlive(el) && owner === _viewPopOwner) {
          el._viewForm.pending = false;
          _renderViewForm(el);
        }
      }
    }});
  input.addEventListener('keydown', event => {
    if (event.key !== 'Enter' || event.isComposing || input.composing) return;
    event.preventDefault();
    const target = _viewFormPolicy(el)['enter-target'];
    if (target === 'graph') share.click();
    else if (target === 'device') save.click();
  });
  input.addEventListener('compositionstart', () => { input.composing = true; });
  input.addEventListener('compositionend', () => { input.composing = false; });
  input.addEventListener('input', () => { msg.hidden = true; _renderViewForm(el); });
  root.insertBefore(share, save);
  root.insertBefore(msg, share);
  root.insertBefore(input, msg);
  el._viewForm = {section, root, input, msg, save, share, pending: false};
  _applyViewFormPolicy(el._viewForm, policy);
}

function _buildViewPop(el) {
  const head = document.createElement('div'); head.className = 'gd-views-pop-head';
  el.appendChild(head); _viewBody(head, 'head');
  const hint = document.createElement('div'); hint.className = 'gd-views-pop-hint';
  el.appendChild(hint); _viewBody(hint, 'hint');
  const rows = document.createElement('div'); rows.dataset.viewRows = '1'; el.appendChild(rows);
  _renderViewRows(el);
  _renderViewForm(el);
  if (typeof ensurePopoverClose === 'function') ensurePopoverClose(el, gdCloseViewPop, 'Close views');
}

// graph-first-exception: the draft is the browser's active filter set. The
// atomic graph API owns validation, full replacement and publication.
let _graphViewSaveSeq = 0;
async function gdShareViewToGraph(name) {
  if (!(window.API && API.api_views_save)) throw new Error('Saving to the graph is not available here');
  const owner = _filterOwner();
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
  if (owner !== _filterOwner()) throw new Error('View context changed before save');
  const response = await authFetch(API.api_views_save, {method: 'POST',
    headers: {'Content-Type': 'application/json'}, body: JSON.stringify(command)});
  const result = await response.json();
  if (owner !== _filterOwner()) throw new Error('View context changed while saving');
  if (!response.ok || !result.ok || !result.committed || !result.view?.id) {
    if ((result.committed === false || (response.status >= 400 && response.status < 500)) && typeof _tourRejectGraphViewCreation === 'function') _tourRejectGraphViewCreation(command);
    throw new Error((result.reason || result.error || 'Could not save the view')
      + (edit ? ' Open Edit graph to change inherited or computed clauses.' : ''));
  }
  let reloadFailed = false;
  if (typeof initGraph === 'function' && (typeof getCurrentBranchName !== 'function' || getCurrentBranchName() === branch)) {
    try { await initGraph(); } catch (_) { reloadFailed = true; }
  }
  if (owner !== _filterOwner()) return result.view;
  gdInvalidateSharedViews();
  await gdFetchSharedViews(true);
  if (owner !== _filterOwner()) return result.view;
  const saved = gdSharedViewsCached().find(view => view.id === result.view.id);
  // A late successful write must not replace a newer draft or another branch.
  if (seq === _graphViewSaveSeq && owner === _filterOwner() && gdFilterRevision() === revision && gdEditingGraphView() === edit
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
