// Editor — problem caches for the Explorer's PROBLEM lenses (failed runs /
// type errors / lint), the per-namespace chips and the per-row / card
// count markers.
//
// The tree payload stays at its one-SQL budget (docs/PERF_BUDGETS.md), so
// the two problem kinds that need their own read are primed like the
// tests lens is: one JSON fetch each, cached here, re-primed per graph
// load and after a run.
//
//   GET /api/failures  →  [{fn-id, fn-name, namespace-id, count} …]
//                         the branch view's UNRESOLVED failures per fn
//                         (same predicate as the Failed runs panel)
//   GET /api/lint      →  [{rule, message, fn-ids, fns:[{id,name,ns}]} …]
//                         the branch's graph-lint warnings (docs/GRAPH_LINT.md)
//
// Type errors need no fetch: `:type-error-count` rides on every fn row the
// tree scopes project, and the `:tree` counts payload carries the
// per-namespace sum (lookups.nsTypeErrors).
//
// Globals consumed: authFetch, isAuthenticated, lookups, graphData,
// repaintAfterPrime (editor-sidebar.js), syncKindFilterBar, gdDiagUpdateBadges.

let _failureCounts = null;   // Map fn-id → {count, name, nsId}
let _lintRows = null;        // the warnings array
let _lintCounts = null;      // Map fn-id → {count, nsId} — findings naming the fn
let _lintNsIds = null;       // Set of namespace ids with a finding member

function getFailureCountForFnId(fnId) {
  return _failureCounts ? (_failureCounts.get(fnId)?.count || 0) : 0;
}

function getLintCountForFnId(fnId) {
  return _lintCounts ? (_lintCounts.get(fnId)?.count || 0) : 0;
}

// Per-namespace tallies for the namespace-row chips: failed RUNS in the
// namespace; fns with lint findings in the namespace. `null` is the root
// bucket.
function nsFailureCount(nsId) {
  if (!_failureCounts) return 0;
  let n = 0;
  _failureCounts.forEach((v) => { if (v.nsId === nsId) n += v.count; });
  return n;
}

function nsLintCount(nsId) {
  if (!_lintCounts) return 0;
  let n = 0;
  _lintCounts.forEach((v) => { if (v.nsId === nsId) n += 1; });
  return n;
}

function getLintRowsForFnId(fnId) {
  if (!_lintRows) return [];
  return _lintRows.filter((r) => (r['fn-ids'] || []).includes(fnId));
}

// Namespace ids holding at least one fn with a problem — the lens's
// "keep this unloaded namespace visible" signal (see nsHoldsLensKind).
function failedNsIds() {
  const s = new Set();
  if (_failureCounts) _failureCounts.forEach((v) => { s.add(v.nsId); });
  return s;
}

function lintNsIds() {
  return _lintNsIds || new Set();
}

// Chip totals. Failed runs count RUNS (what the panel lists); lint counts
// FINDINGS (one row per group); type errors count DIAGNOSTICS (the
// per-namespace sums the tree payload already carries).
function getFailureTotal() {
  if (!_failureCounts) return null;
  let n = 0;
  _failureCounts.forEach((v) => { n += v.count; });
  return n;
}

function getLintTotal() {
  return _lintRows ? _lintRows.length : null;
}

function getTypeErrorTotal() {
  const m = (typeof lookups !== 'undefined') ? lookups?.nsTypeErrors : null;
  if (!m) return null;
  let n = 0;
  m.forEach((v) => { n += v; });
  return n;
}

function loadFailureCounts() {
  if (!(window.API && API.api_failures)) return Promise.resolve(null);
  return authFetch(API.api_failures)
    .then((r) => (r.ok ? r.json() : []))
    .then((rows) => {
      _failureCounts = new Map((rows || []).map((row) => [row['fn-id'],
        { count: row.count || 0, name: row['fn-name'], nsId: row['namespace-id'] ?? null }]));
      return _failureCounts;
    })
    .catch(() => null);
}

function loadLintRows() {
  if (!(window.API && API.api_lint)) return Promise.resolve(null);
  return authFetch(API.api_lint)
    .then((r) => (r.ok ? r.json() : []))
    .then((rows) => {
      _lintRows = rows || [];
      _lintCounts = new Map();
      _lintNsIds = new Set();
      const pathMap = (typeof lookups !== 'undefined' && lookups?.nsPathMap) ? lookups.nsPathMap : null;
      _lintRows.forEach((row) => {
        (row.fns || []).forEach((f) => {
          // The JSON carries the dotted path; the tree keys namespaces by
          // id — resolve through the path map when it is loaded.
          const nsId = pathMap ? nsIdForPath(f.ns) : null;
          const cur = _lintCounts.get(f.id) || { count: 0, nsId };
          cur.count += 1;
          _lintCounts.set(f.id, cur);
          if (nsId !== undefined) _lintNsIds.add(nsId);
        });
      });
      return _lintRows;
    })
    .catch(() => null);
}

// Reverse of lookups.nsPathMap (id → path): path → id; '' / null = the
// root bucket (null nsId).
function nsIdForPath(path) {
  if (!path) return null;
  for (const [id, p] of lookups.nsPathMap) {
    if (p === path) return id;
  }
  return undefined;
}

// Prime both caches, then repaint what reads them (tree markers, chip
// counts, the drawer badges).
function loadProblemCaches() {
  return Promise.all([loadFailureCounts(), loadLintRows()]).then(() => {
    if (typeof syncKindFilterBar === 'function') syncKindFilterBar();
    if (typeof window.gdDiagUpdateBadges === 'function') window.gdDiagUpdateBadges();
    return true;
  });
}

// Per-graph-load prime (same shape as primeTestStatusesOnce): the tree
// markers read the caches sync'ly, so re-prime whenever the graph reloads.
let _problemsPrimedGraph = null;
function primeProblemCachesOnce() {
  if (typeof isAuthenticated !== 'function' || !isAuthenticated()) return;
  if (typeof graphData === 'undefined' || _problemsPrimedGraph === graphData) return;
  // Mark primed only once the route-path cache can answer — the first
  // sidebar paint can run before `window.API` lands, and a prime marked
  // then would never retry (the tests prime guards the same way).
  if (!(window.API && API.api_failures && API.api_lint)) return;
  if (typeof authFetch !== 'function') return;
  _problemsPrimedGraph = graphData;
  loadProblemCaches().then(() => {
    if (typeof repaintAfterPrime === 'function') repaintAfterPrime();
  });
}

// A run just finished (or the drawer re-fetched): failures may have
// changed without a graph write.
function refreshProblemCaches() {
  if (typeof isAuthenticated !== 'function' || !isAuthenticated()) return Promise.resolve(null);
  return loadProblemCaches().then(() => {
    if (typeof repaintAfterPrime === 'function') repaintAfterPrime();
  });
}
window.refreshProblemCaches = refreshProblemCaches;
