'use strict';

// editor-problems.js — the problem lenses' caches, with the two JSON
// reads written down as fixtures: per-fn / per-namespace / total counts
// for failed runs and lint findings, and the "not primed until the API
// cache exists" guard. Runs under node's vm; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

const NS_APP = 'ns-app';
const NS_LIB = 'ns-lib';
const failures = [
  { 'fn-id': 'f1', 'fn-name': 'a', 'namespace-id': NS_APP, count: 2 },
  { 'fn-id': 'f2', 'fn-name': 'b', 'namespace-id': NS_APP, count: 1 },
  { 'fn-id': 'f3', 'fn-name': 'root-fn', 'namespace-id': null, count: 1 },
];
const lint = [
  { rule: 'duplicate-definition', 'fn-ids': ['f1', 'f4'],
    fns: [{ id: 'f1', name: 'a', ns: 'app' }, { id: 'f4', name: 'd', ns: 'lib' }] },
  { rule: 'unreferenced-private', 'fn-ids': ['f4'],
    fns: [{ id: 'f4', name: 'd', ns: 'lib' }] },
];

let apiReady = true;
const fetched = [];
const ctx = vm.createContext({
  console,
  window: {},
  graphData: { id: 1 },
  isAuthenticated: () => true,
  lookups: { nsPathMap: new Map([[NS_APP, 'app'], [NS_LIB, 'lib']]), nsTypeErrors: new Map([[NS_APP, 3]]) },
  authFetch: (url) => {
    fetched.push(url);
    const body = url.endsWith('/api/failures') ? failures : lint;
    return Promise.resolve({ ok: true, json: () => Promise.resolve(body) });
  },
});
ctx.window.API = { api_failures: '/api/failures', api_lint: '/api/lint' };
ctx.API = ctx.window.API;
vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-problems.js'), 'utf8'), ctx, { filename: 'editor-problems.js' });

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

(async () => {
  console.log(' before priming, every getter reads "unknown", not zero');
  assert(ctx.getFailureTotal() === null, 'failure total null before prime');
  assert(ctx.getLintTotal() === null, 'lint total null before prime');
  assert(ctx.getFailureCountForFnId('f1') === 0, 'per-fn failure count 0 before prime');

  console.log(' loadProblemCaches primes both reads');
  await ctx.loadProblemCaches();
  assert(fetched.length === 2, 'two fetches: ' + fetched.join(','));
  assert(ctx.getFailureTotal() === 4, 'failure total counts RUNS (2+1+1): ' + ctx.getFailureTotal());
  assert(ctx.getFailureCountForFnId('f1') === 2, 'per-fn failure count');
  assert(ctx.nsFailureCount(NS_APP) === 3, 'per-namespace failed runs (2+1)');
  assert(ctx.nsFailureCount(null) === 1, 'root bucket keyed by null');
  assert(ctx.failedNsIds().has(NS_APP) && ctx.failedNsIds().has(null), 'namespaces with failures');

  assert(ctx.getLintTotal() === 2, 'lint total counts FINDINGS');
  assert(ctx.getLintCountForFnId('f4') === 2, 'a fn in two findings counts twice');
  assert(ctx.getLintCountForFnId('f1') === 1, 'a fn in one finding');
  assert(ctx.nsLintCount(NS_LIB) === 1 && ctx.nsLintCount(NS_APP) === 1, 'per-namespace: fns with findings');
  assert(ctx.lintNsIds().has(NS_LIB) && ctx.lintNsIds().has(NS_APP), 'namespaces resolved from dotted paths');
  assert(ctx.getLintRowsForFnId('f4').length === 2, 'rows naming a fn');
  assert(ctx.getTypeErrorTotal() === 3, 'type-error total sums the tree payload');

  console.log(' primeProblemCachesOnce waits for the API cache, then primes once per graph');
  fetched.length = 0;
  ctx.window.API = null;
  ctx.API = null;
  ctx._problemsPrimedGraph = null;
  ctx.primeProblemCachesOnce();
  assert(fetched.length === 0, 'no API cache yet → no fetch, not marked primed');
  ctx.window.API = { api_failures: '/api/failures', api_lint: '/api/lint' };
  ctx.API = ctx.window.API;
  ctx.primeProblemCachesOnce();
  await new Promise((r) => setTimeout(r, 10));
  assert(fetched.length === 2, 'primes once the API cache exists');
  ctx.primeProblemCachesOnce();
  await new Promise((r) => setTimeout(r, 10));
  assert(fetched.length === 2, 'same graph → no second prime');

  console.log(fails === 0 ? 'PASS (' + passes + ' assertions)' : 'FAIL (' + fails + ' failed)');
  process.exitCode = fails === 0 ? 0 : 1;
})();
