// Editor — Explorer FILTERS and VIEWS: the one model behind every way the
// tree is narrowed.
//
// A FILTER is one typed predicate over fn rows — a chip under the filter
// box. Axes:
//   kinds       fn / types / secrets / services / apps / tests   (OR within)
//   problems    failed / type-errors / lint                       (OR within)
//   namespaces  include these roots (OR)   exclude  hide these paths
//   uses        the fn every member transitively extends/references (AND)
//   effects     effect kinds the member's footprint carries (AND)
//   unused      nothing references or extends it
//   name        the filter box (substring of the qualified name)
// Axes AND. A VIEW is a named, saved filter set — personal (this browser)
// or SAVED IN THE GRAPH as an fn-def extending `:explorer-view`, which the
// server lists back (`GET /api/views`).
//
// The active set is what the reader sees; it persists per browser like
// every other Explorer preference, so "the namespaces I work in" is just
// the set you leave on. Evaluation:
//   • kinds / problems / namespaces / exclude — client overlays on the lazy
//     tree (`editor-sidebar-lens.js` reads `lensKinds` + the ns predicates
//     this module exports), no fetch;
//   • name alone — `?scope=search` (editor-sidebar.js, unchanged);
//   • uses / effects / unused — `POST /api/views/members`, rendered through
//     the same force-expanded pipeline search uses (`gdViewMembers`).
//
// Supersedes the kind LENS state, the WORKSPACE (roots + ⊘ hidden) and the
// SMART VIEWS text rules — their localStorage keys migrate once on load.
//
// This file is the MODEL (state, storage + migration, the predicates the
// tree reads, the members fetch, saved views); the chips, the "+ filter"
// menu, the view chip's popover and "Save in the graph…" are
// editor-explorer-views-ui.js.
//
// Globals consumed: lensKinds (editor-sidebar-lens.js — kept as the
// derived kinds+problems set the overlays read), updateEntityList,
// applyKindFilters, syncKindFilterBar, graphData, lookups, authFetch,
// API, gdAnnounce, gdToast, searchFns, mergeKnownFns, getQualifiedFnName.

const FILTERS_KEY = 'graphden.explorer.filters';
const VIEWS_KEY = 'graphden.explorer.views';

const KIND_AXIS = ['fn', 'types', 'secrets', 'services', 'apps', 'tests'];
const PROBLEM_AXIS = ['failed', 'type-errors', 'lint'];

function gdEmptyFilters() {
  return { kinds: [], problems: [], namespaces: [], exclude: [], uses: [], effects: [], unused: false };
}

// ---------------------------------------------------------------------------
// State + persistence (with the one-time migration of the three old stores)
// ---------------------------------------------------------------------------

let _filters = gdEmptyFilters();
let _viewName = null;           // the saved view the active set came from, if any
let _viewMembers = null;        // [{id,name,…}] | null while loading | undefined when no server axis
let _viewSeq = 0;
let _sharedViews = null;        // GET /api/views cache: [{id,name,filters}] | null

function _normFilters(raw) {
  const f = gdEmptyFilters();
  if (!raw || typeof raw !== 'object') return f;
  const arr = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x) : []);
  f.kinds = arr(raw.kinds).filter((k) => KIND_AXIS.includes(k));
  f.problems = arr(raw.problems).filter((k) => PROBLEM_AXIS.includes(k));
  f.namespaces = arr(raw.namespaces);
  f.exclude = arr(raw.exclude);
  f.uses = (Array.isArray(raw.uses) ? raw.uses : [])
    .filter((u) => u && typeof u.id === 'string')
    .map((u) => ({ id: u.id, name: String(u.name || u.id) }));
  f.effects = arr(raw.effects);
  f.unused = !!raw.unused;
  return f;
}

function _saveFilters() {
  try {
    localStorage.setItem(FILTERS_KEY, JSON.stringify({ filters: _filters, view: _viewName }));
  } catch (_) { /* private mode */ }
}

function gdReadViews() {
  try {
    const raw = localStorage.getItem(VIEWS_KEY);
    const arr = raw ? JSON.parse(raw) : [];
    return Array.isArray(arr)
      ? arr.filter((v) => v && typeof v.name === 'string').map((v) => ({ name: v.name, filters: _normFilters(v.filters), legacyRule: v.legacyRule || null }))
      : [];
  } catch (_) { return []; }
}

function gdWriteViews(views) {
  try { localStorage.setItem(VIEWS_KEY, JSON.stringify(views)); } catch (_) { /* private mode */ }
}

// One-time migration: the lens set, the workspace roots/hidden and the smart
// views (text rules) become the new stores. Runs only while the new key is
// absent, so a reader keeps exactly the tree they had.
function _migrateOldStores() {
  let had = false;
  try { had = localStorage.getItem(FILTERS_KEY) !== null; } catch (_) { return; }
  if (had) return;
  const f = gdEmptyFilters();
  const read = (k) => { try { return JSON.parse(localStorage.getItem(k) || 'null'); } catch (_) { return null; } };
  const lens = read('graphden.sidebarLens');
  if (Array.isArray(lens)) {
    f.kinds = lens.filter((k) => KIND_AXIS.includes(k));
    f.problems = lens.filter((k) => PROBLEM_AXIS.includes(k));
  }
  const roots = read('graphden.workspace.roots');
  if (Array.isArray(roots)) f.namespaces = roots.filter((x) => typeof x === 'string');
  const hidden = read('graphden.workspace.hidden');
  if (Array.isArray(hidden)) f.exclude = hidden.filter((x) => typeof x === 'string');
  _filters = f;
  _saveFilters();
  // Smart views carried a rule STRING; a `uses:` names a fn that has to be
  // resolved against the graph, so keep the rule and resolve on first apply.
  const smart = read('graphden.smartViews');
  if (Array.isArray(smart) && smart.length && !gdReadViews().length) {
    gdWriteViews(smart
      .filter((v) => v && typeof v.name === 'string' && typeof v.rule === 'string')
      .map((v) => ({ name: v.name, filters: _ruleToFilters(v.rule), legacyRule: v.rule })));
  }
  for (const k of ['graphden.sidebarLens', 'graphden.workspace.roots', 'graphden.workspace.hidden',
    'graphden.workspace.pins', 'graphden.smartViews']) {
    try { localStorage.removeItem(k); } catch (_) { /* ignore */ }
  }
}

// The old rule grammar → a filter set. `uses:<name>` cannot be resolved
// here (no graph yet) — it is kept as `{name}` with no id and resolved
// through `?scope=search` the first time the view is applied.
function _ruleToFilters(rule) {
  const f = gdEmptyFilters();
  const bare = [];
  for (const tok of String(rule || '').trim().split(/\s+/).filter(Boolean)) {
    const m = /^([a-z-]+):(.*)$/.exec(tok);
    if (!m) { bare.push(tok); continue; }
    const [, k, v] = m;
    if (k === 'uses' && v) f.uses.push({ id: null, name: v });
    else if (k === 'effect' && v) f.effects.push(v);
    else if (k === 'ns' && v) f.namespaces.push(v.replace(/\//g, '.'));
    else if (k === 'unused') f.unused = /^(true|yes|1)$/i.test(v);
    else if (k === 'name' && v) bare.push(v);
  }
  if (bare.length) f.name = bare.join(' ');
  return f;
}

function _loadFilters() {
  _migrateOldStores();
  try {
    const raw = JSON.parse(localStorage.getItem(FILTERS_KEY) || 'null');
    if (raw && typeof raw === 'object') {
      _filters = _normFilters(raw.filters);
      _viewName = typeof raw.view === 'string' ? raw.view : null;
    }
  } catch (_) { /* defaults */ }
}
_loadFilters();

// ---------------------------------------------------------------------------
// Read side — what the tree modules ask
// ---------------------------------------------------------------------------

function gdFilters() { return _filters; }
function gdActiveViewName() { return _viewName; }

// Chip-level count and "anything on?" for the trail / clear button.
function gdFilterCount(f = _filters) {
  return f.kinds.length + f.problems.length + f.namespaces.length + f.exclude.length
    + f.uses.length + f.effects.length + (f.unused ? 1 : 0);
}
function gdFiltersActive() { return gdFilterCount() > 0; }

// The axes the SERVER evaluates; when any is on, the tree is the member
// list (`gdViewMembers`) rendered force-expanded, like a search.
function gdServerAxesActive(f = _filters) {
  return f.uses.length > 0 || f.effects.length > 0 || !!f.unused;
}
function gdViewMembers() { return _viewMembers; }

// Namespace predicates the tree's structural skips read (editor-sidebar.js
// and editor-sidebar-rows.js) — a root+descendant match, `/`-tolerant.
function _underAny(path, list) {
  if (!path || !list.length) return false;
  const p = String(path).toLowerCase();
  return list.some((w) => {
    const ww = String(w).toLowerCase().replace(/\//g, '.');
    return p === ww || p.startsWith(ww + '.');
  });
}
function gdNsIncluded(nsPath) {
  return _filters.namespaces.length === 0 || _underAny(nsPath, _filters.namespaces);
}
function gdNsExcluded(nsPath) { return _underAny(nsPath, _filters.exclude); }
function gdNsFiltersActive() { return _filters.namespaces.length > 0; }

// Mirror the kinds + problems into `lensKinds`, the set the row overlays
// (fnKindVisible / nodeShouldShow / the chip aria-pressed sync) read.
function _syncLensSet() {
  if (typeof lensKinds === 'undefined') return;
  lensKinds.clear();
  for (const k of _filters.kinds) lensKinds.add(k);
  for (const k of _filters.problems) lensKinds.add(k);
}
_syncLensSet();

// ---------------------------------------------------------------------------
// Write side
// ---------------------------------------------------------------------------

// `kindsOnly` — the change touched kinds/problems alone: the cheap in-place
// overlay pass (applyKindFilters) is enough; anything structural (namespaces,
// a server axis) rebuilds the tree.
function _afterChange(announce, kindsOnly) {
  _syncLensSet();
  _saveFilters();
  if (typeof syncKindFilterBar === 'function') syncKindFilterBar();
  if (typeof gdRenderFilterChips === 'function') gdRenderFilterChips();
  if (typeof gdSyncViewChip === 'function') gdSyncViewChip();
  if (gdServerAxesActive()) _fetchViewMembers();
  else {
    _viewMembers = undefined;
    if (kindsOnly && typeof applyKindFilters === 'function') applyKindFilters();
    else if (typeof graphData !== 'undefined' && graphData && typeof updateEntityList === 'function') {
      updateEntityList(graphData);
    }
  }
  if (announce && typeof window.gdAnnounce === 'function') window.gdAnnounce(announce);
}

// Any edit of the set detaches it from the view it came from — the view
// stays saved; the chip reads "N filters" until you save again.
function _detachView() { _viewName = null; }

function gdToggleKind(kind) {
  const axis = KIND_AXIS.includes(kind) ? 'kinds' : PROBLEM_AXIS.includes(kind) ? 'problems' : null;
  if (!axis) return;
  const i = _filters[axis].indexOf(kind);
  if (i >= 0) _filters[axis].splice(i, 1); else _filters[axis].push(kind);
  _detachView();
  _afterChange(null, true);
}

function gdToggleNamespace(path) {
  const i = _filters.namespaces.indexOf(path);
  if (i >= 0) _filters.namespaces.splice(i, 1); else _filters.namespaces.push(path);
  _detachView();
  _afterChange((i >= 0 ? 'Namespace ' + path + ' off' : 'Only ' + _filters.namespaces.join(', ')));
}

function gdToggleExclude(path) {
  const i = _filters.exclude.indexOf(path);
  if (i >= 0) _filters.exclude.splice(i, 1); else _filters.exclude.push(path);
  _detachView();
  _afterChange((i >= 0 ? path + ' restored' : path + ' hidden'));
}

function gdAddUses(fn) {
  if (!fn?.id || _filters.uses.some((u) => u.id === fn.id)) return;
  _filters.uses.push({ id: fn.id, name: (typeof getQualifiedFnName === 'function' ? getQualifiedFnName(fn) : fn.name) || fn.id });
  _detachView();
  _afterChange('Only fns using ' + _filters.uses[_filters.uses.length - 1].name);
}

function gdRemoveUses(id) {
  _filters.uses = _filters.uses.filter((u) => u.id !== id);
  _detachView();
  _afterChange(null);
}

function gdToggleEffect(kind) {
  const i = _filters.effects.indexOf(kind);
  if (i >= 0) _filters.effects.splice(i, 1); else _filters.effects.push(kind);
  _detachView();
  _afterChange(null);
}

function gdToggleUnused() {
  _filters.unused = !_filters.unused;
  _detachView();
  _afterChange(_filters.unused ? 'Only unused fns' : null);
}

// "◍ all" — every filter off, the view detached. The one gesture that
// always brings the whole tree back.
function gdClearFilters() {
  _filters = gdEmptyFilters();
  _viewName = null;
  _afterChange('All functions');
}

// Apply a saved filter set (a personal or graph view) as the active set.
async function gdApplyView(view) {
  const f = _normFilters(view.filters);
  // A view migrated from a text rule may carry `uses` by NAME — resolve
  // once against the graph and persist the id.
  if (f.uses.some((u) => !u.id) && typeof searchFns === 'function') {
    const resolved = [];
    for (const u of f.uses) {
      if (u.id) { resolved.push(u); continue; }
      const rows = await searchFns(u.name.replace(/\//g, '.')).catch(() => []);
      const want = u.name.toLowerCase().replace(/\//g, '.');
      const hit = (rows || []).find((r) => {
        const q = (typeof getQualifiedFnName === 'function' ? getQualifiedFnName(r) : r.name) || '';
        return q.toLowerCase() === want || String(r.name).toLowerCase() === want;
      });
      if (hit) resolved.push({ id: hit.id, name: (typeof getQualifiedFnName === 'function' ? getQualifiedFnName(hit) : hit.name) });
      else if (typeof gdToast === 'function') gdToast('View "' + view.name + '": no fn named ' + u.name + ' — that filter was dropped');
    }
    f.uses = resolved;
    if (!view.shared) {
      gdWriteViews(gdReadViews().map((v) => (v.name === view.name ? { name: v.name, filters: f } : v)));
    }
  }
  _filters = f;
  _viewName = view.name;
  _afterChange('View ' + view.name);
}

// Save the active set under a name (personal). Replaces a same-named view.
function gdSaveView(name) {
  const views = gdReadViews().filter((v) => v.name !== name);
  views.unshift({ name, filters: JSON.parse(JSON.stringify(_filters)) });
  gdWriteViews(views);
  _viewName = name;
  _saveFilters();
  if (typeof gdSyncViewChip === 'function') gdSyncViewChip();
  if (typeof window.gdAnnounce === 'function') window.gdAnnounce('Saved view ' + name);
}

function gdDeleteView(name) {
  gdWriteViews(gdReadViews().filter((v) => v.name !== name));
  if (_viewName === name) { _viewName = null; _saveFilters(); if (typeof gdSyncViewChip === 'function') gdSyncViewChip(); }
}

// ---------------------------------------------------------------------------
// Server axes — POST /api/views/members
// ---------------------------------------------------------------------------

function _fetchViewMembers() {
  const seq = ++_viewSeq;
  _viewMembers = null;
  if (typeof graphData !== 'undefined' && graphData && typeof updateEntityList === 'function') {
    updateEntityList(graphData);   // "Computing view…"
  }
  if (!(window.API && API.api_views_members) || typeof authFetch !== 'function') { _viewMembers = []; return; }
  const body = {
    uses: _filters.uses.map((u) => u.id),
    effects: _filters.effects,
    unused: _filters.unused,
    // The client already narrows by these; sending them too keeps the
    // member list (and its truncation) honest about what is on screen.
    kinds: _filters.kinds,
    namespaces: _filters.namespaces,
    exclude: _filters.exclude,
  };
  authFetch(API.api_views_members, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status))))
    .then((d) => {
      if (seq !== _viewSeq) return;
      _viewMembers = d?.fns || [];
      if (typeof mergeKnownFns === 'function') mergeKnownFns(_viewMembers);
      if (d?.['truncated?'] && typeof gdToast === 'function') {
        gdToast('More than 500 fns match — add a filter to narrow it');
      }
      if (typeof window.gdAnnounce === 'function') {
        window.gdAnnounce(_viewMembers.length + ' functions match');
      }
      if (typeof updateEntityList === 'function') updateEntityList(graphData);
    })
    .catch(() => {
      if (seq !== _viewSeq) return;
      _viewMembers = [];
      if (typeof gdToast === 'function') gdToast('Could not evaluate the filters — check your connection');
      if (typeof updateEntityList === 'function') updateEntityList(graphData);
    });
}

// Re-evaluate after a graph write while server axes are on (a new fn may
// now use the target).
function gdRefreshViewMembers() {
  if (gdServerAxesActive()) _fetchViewMembers();
}

// ---------------------------------------------------------------------------
// Views saved in the graph — GET /api/views
// ---------------------------------------------------------------------------

async function gdFetchSharedViews(force) {
  if (_sharedViews && !force) return _sharedViews;
  if (!(window.API && API.api_views) || typeof authFetch !== 'function') { _sharedViews = []; return _sharedViews; }
  try {
    const r = await authFetch(API.api_views);
    const rows = r.ok ? await r.json() : [];
    _sharedViews = (rows || []).map((v) => {
      const f = gdEmptyFilters();
      const src = v.filters || {};
      f.kinds = (src.kinds || []).map(String).filter((k) => KIND_AXIS.includes(k));
      f.namespaces = (src.namespaces || []).map(String);
      f.exclude = (src.exclude || []).map(String);
      f.effects = (src.effects || []).map(String);
      f.unused = !!src.unused;
      f.uses = (src.uses || []).map((id) => {
        const fn = (typeof lookups !== 'undefined') ? lookups?.fnMap?.get(id) : null;
        return { id, name: fn ? ((typeof getQualifiedFnName === 'function' ? getQualifiedFnName(fn) : fn.name) || id) : id };
      });
      return { name: v.name, id: v.id, filters: f, shared: true, also: src.also || [] };
    });
  } catch (_) { _sharedViews = []; }
  return _sharedViews;
}
function gdInvalidateSharedViews() { _sharedViews = null; }
function gdSharedViewsCached() { return _sharedViews; }

// A view just saved in the graph under `name` IS the active set — name it
// on the chip without touching the filters.
function gdMarkViewApplied(name) {
  _viewName = name;
  _saveFilters();
  if (typeof gdSyncViewChip === 'function') gdSyncViewChip();
}

// ---------------------------------------------------------------------------
// Exports — the read side the tree modules consult, the write side the
// chips / popovers (editor-explorer-views-ui.js) and the tour drive.
// ---------------------------------------------------------------------------

window.gdFilters = gdFilters;
window.gdFiltersActive = gdFiltersActive;
window.gdFilterCount = gdFilterCount;
window.gdServerAxesActive = gdServerAxesActive;
window.gdViewMembers = gdViewMembers;
window.gdNsIncluded = gdNsIncluded;
window.gdNsExcluded = gdNsExcluded;
window.gdNsFiltersActive = gdNsFiltersActive;
window.gdToggleKind = gdToggleKind;
window.gdToggleNamespace = gdToggleNamespace;
window.gdToggleExclude = gdToggleExclude;
window.gdAddUses = gdAddUses;
window.gdRemoveUses = gdRemoveUses;
window.gdToggleEffect = gdToggleEffect;
window.gdToggleUnused = gdToggleUnused;
window.gdClearFilters = gdClearFilters;
window.gdApplyView = gdApplyView;
window.gdSaveView = gdSaveView;
window.gdDeleteView = gdDeleteView;
window.gdReadViews = gdReadViews;
window.gdWriteViews = gdWriteViews;
window.gdActiveViewName = gdActiveViewName;
window.gdRefreshViewMembers = gdRefreshViewMembers;
window.gdFetchSharedViews = gdFetchSharedViews;
window.gdInvalidateSharedViews = gdInvalidateSharedViews;
window.gdSharedViewsCached = gdSharedViewsCached;
window.gdMarkViewApplied = gdMarkViewApplied;
window.gdEmptyFilters = gdEmptyFilters;
