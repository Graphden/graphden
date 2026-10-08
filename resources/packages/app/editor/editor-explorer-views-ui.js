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

function _chip(label, title, onRemove, cls, missing) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'kind-toggle gd-filter-chip' + (cls ? ' ' + cls : '') + (missing ? ' gd-filter-chip-missing' : '');
  b.setAttribute('aria-pressed', 'true');
  b.title = (missing ? 'No longer exists — this filter matches nothing. ' : '') + title + ' — click to remove';
  b.setAttribute('aria-label', 'Remove filter: ' + title + (missing ? ' (no longer exists)' : ''));
  const l = document.createElement('span');
  l.className = 'kind-label';
  l.textContent = (missing ? '⚠ ' : '') + label;
  const x = document.createElement('span');
  x.className = 'gd-filter-chip-x';
  x.setAttribute('aria-hidden', 'true');
  x.textContent = '×';
  b.appendChild(l);
  b.appendChild(x);
  b.addEventListener('click', onRemove);
  return b;
}

function gdRenderFilterChips() {
  const host = document.getElementById('gd-filter-chips');
  if (!host) return;
  host.replaceChildren();
  for (const p of gdFilters().namespaces) host.appendChild(_chip('in ' + p, 'Only namespace ' + p, () => gdToggleNamespace(p)));
  for (const p of gdFilters().exclude) host.appendChild(_chip('not ' + p, 'Hide namespace ' + p, () => gdToggleExclude(p)));
  const missing = (typeof gdViewMissing === 'function') ? gdViewMissing() : { uses: [], views: [] };
  for (const u of gdFilters().uses) host.appendChild(_chip('uses ' + u.name, 'Only fns using ' + u.name, () => gdRemoveUses(u.id), null, missing.uses.includes(u.id)));
  for (const e of gdFilters().effects) host.appendChild(_chip('fx ' + e, 'Only fns with effect ' + e, () => gdToggleEffect(e)));
  if (gdFilters().unused) host.appendChild(_chip('unused', 'Only unused fns', () => gdToggleUnused()));
  if (gdFilters().name) host.appendChild(_chip('name ' + gdFilters().name, 'Only names containing ' + gdFilters().name, () => gdSetName('')));
  for (const v of gdFilters().views) host.appendChild(_chip('view ' + v.name, 'Only fns in view ' + v.name, () => gdRemoveView(v.id), null, missing.views.includes(v.id)));
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

function gdCloseFilterAdd() {
  if (!_addPopEl) return;
  const hadFocus = _addPopEl.contains(document.activeElement);
  _addPopEl.remove();
  _addPopEl = null;
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

function gdOpenFilterAdd(anchorEl) {
  if (_addPopEl) { gdCloseFilterAdd(); return; }
  const el = document.createElement('div');
  el.className = 'gd-views-pop gd-filter-add-pop';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'false');
  el.setAttribute('aria-label', 'Add a filter');
  _renderFilterAdd(el, anchorEl);
  document.body.appendChild(el);
  _addPopEl = el;
  _addPopAnchor = anchorEl || null;
  if (typeof anchorBelowClamped === 'function' && anchorEl) anchorBelowClamped(el, anchorEl);
  if (typeof focusIntoDialog === 'function') focusIntoDialog(el);
}

function _section(el, title) {
  const cap = document.createElement('div');
  cap.className = 'gd-views-pop-cap';
  cap.textContent = title;
  el.appendChild(cap);
}

function _renderFilterAdd(el, anchorEl) {
  el.replaceChildren();
  const head = document.createElement('div');
  head.className = 'gd-views-pop-head';
  head.textContent = 'Add a filter';
  el.appendChild(head);
  const hint = document.createElement('div');
  hint.className = 'gd-views-pop-hint';
  hint.textContent = 'Filters combine (AND). Namespaces and kinds OR within their row.';
  el.appendChild(hint);

  // Namespaces — tick the roots you work in; ⊘ in the tree hides a path.
  _section(el, 'Namespaces — only these');
  const roots = _rootNamespaces();
  for (const n of roots) {
    const on = gdFilters().namespaces.includes(n.name);
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'gd-views-row gd-ws-opt' + (on ? ' sel' : '');
    b.setAttribute('role', 'checkbox');
    b.setAttribute('aria-checked', on ? 'true' : 'false');
    b.dataset.ws = n.name;
    b.title = n.desc || n.name;
    b.textContent = (on ? '☑ ' : '☐ ') + n.name;
    b.addEventListener('click', () => { gdToggleNamespace(n.name); _renderFilterAdd(el, anchorEl); });
    el.appendChild(b);
  }
  if (gdFilters().exclude.length) {
    _section(el, 'Hidden by you — restore');
    for (const p of gdFilters().exclude.slice().sort()) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'gd-views-row';
      b.dataset.restore = p;
      b.textContent = '↺ ' + p;
      b.addEventListener('click', () => { gdToggleExclude(p); _renderFilterAdd(el, anchorEl); });
      el.appendChild(b);
    }
  }

  // Uses — the fn picker (the Explorer's search, with type hints).
  _section(el, 'Uses a function');
  const usesBtn = document.createElement('button');
  usesBtn.type = 'button';
  usesBtn.className = 'gd-views-row';
  usesBtn.dataset.action = 'add-uses';
  usesBtn.textContent = '+ pick a fn — only what is built on it';
  usesBtn.addEventListener('click', () => {
    if (typeof openFnPicker !== 'function') return;
    // The picker takes the menu's place: anchored where the menu was, the
    // menu itself gone (two stacked dialogs left the picker half-covered).
    const anchor = _addPopAnchor || usesBtn;
    gdCloseFilterAdd();
    openFnPicker({
      anchorEl: anchor,
      title: 'Only fns that use…',
      onPick: (fn) => gdAddUses(fn),
    });
  });
  el.appendChild(usesBtn);

  // Effects
  _section(el, 'Effect');
  const fxRow = document.createElement('div');
  fxRow.className = 'gd-filter-fx-row';
  for (const k of EFFECT_KINDS) {
    const on = gdFilters().effects.includes(k);
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'kind-toggle';
    b.dataset.effect = k;
    b.setAttribute('aria-pressed', on ? 'true' : 'false');
    b.textContent = k;
    b.addEventListener('click', () => { gdToggleEffect(k); _renderFilterAdd(el, anchorEl); });
    fxRow.appendChild(b);
  }
  el.appendChild(fxRow);

  // Views saved in the graph — "also in view X". Loaded once per open;
  // the section appears when the fetch lands.
  const shared = (typeof gdSharedViewsCached === 'function') ? gdSharedViewsCached() : null;
  if (shared === null && typeof gdFetchSharedViews === 'function') {
    gdFetchSharedViews().then(() => { if (_addPopEl === el) _renderFilterAdd(el, anchorEl); });
  }
  if (shared?.length) {
    _section(el, 'In a graph view');
    for (const v of shared) {
      const on = gdFilters().views.some((x) => x.id === v.id);
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'gd-views-row' + (on ? ' sel' : '');
      b.setAttribute('role', 'checkbox');
      b.setAttribute('aria-checked', on ? 'true' : 'false');
      b.dataset.view = v.id;
      b.title = _summarise(v.filters);
      b.textContent = (on ? '☑ ' : '☐ ') + v.name;
      b.addEventListener('click', () => {
        if (on) gdRemoveView(v.id); else gdAddView(v);
        _renderFilterAdd(el, anchorEl);
      });
      el.appendChild(b);
    }
  }

  // Name — the saved form of the filter box.
  _section(el, 'Name contains');
  const nameRow = document.createElement('div');
  nameRow.className = 'gd-views-form';
  const nameIn = document.createElement('input');
  nameIn.type = 'text';
  nameIn.className = 'gd-views-input';
  nameIn.placeholder = 'part of a name — Enter to add the chip';
  nameIn.setAttribute('aria-label', 'Only names containing');
  nameIn.value = gdFilters().name || '';
  nameIn.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    gdSetName(nameIn.value);
    _renderFilterAdd(el, anchorEl);
  });
  nameRow.appendChild(nameIn);
  el.appendChild(nameRow);

  // Unused
  const un = document.createElement('button');
  un.type = 'button';
  un.className = 'gd-views-row';
  un.dataset.action = 'unused';
  un.setAttribute('role', 'checkbox');
  un.setAttribute('aria-checked', gdFilters().unused ? 'true' : 'false');
  un.textContent = (gdFilters().unused ? '☑ ' : '☐ ') + 'Unused — nothing references or extends it';
  un.addEventListener('click', () => { gdToggleUnused(); _renderFilterAdd(el, anchorEl); });
  el.appendChild(un);

  if (typeof ensurePopoverClose === 'function') ensurePopoverClose(el, gdCloseFilterAdd, 'Close');
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
    nameIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); if (window.API && API.api_view_save) share.click(); else trySave(); } });
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
    if (window.API && API.api_view_save) form.appendChild(share);
    form.appendChild(save);
    el.appendChild(form);
  }

  if (typeof ensurePopoverClose === 'function') ensurePopoverClose(el, gdCloseViewPop, 'Close views');
}

// graph-first-exception: the draft is the browser's active filter set. The
// atomic graph API owns validation, full replacement and publication.
let _graphViewSaveSeq = 0;
async function gdShareViewToGraph(name) {
  if (!(window.API && API.api_view_save)) throw new Error('Saving to the graph is not available here');
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
  const response = await authFetch(API.api_view_save, {method: 'POST',
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

// The filter model's own exports live in editor-explorer-filters.js.
window.gdOpenViewPop = gdOpenViewPop;
window.gdOpenFilterAdd = gdOpenFilterAdd;
window.gdRenderFilterChips = gdRenderFilterChips;
window.gdSyncViewChip = gdSyncViewChip;
