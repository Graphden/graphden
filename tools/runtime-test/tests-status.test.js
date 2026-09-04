// editor-tests.js — the ✓ tests lens's status cache: total / failed counts
// the chip and the row dots read sync'ly, and the live stream gating (opens
// only while the lens is active and the user is signed in). Runs under
// node's vm; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

const rows = [
  { 'fn-id': 't1', 'fn-name': 'passes', status: 'succeeded' },
  { 'fn-id': 't2', 'fn-name': 'fails', status: 'failed', error: 'assert-eq failed' },
  { 'fn-id': 't3', 'fn-name': 'never-ran', status: null },
  { 'fn-id': 't4', 'fn-name': 'also-fails', status: 'failed' },
];
const fetched = [];
let repaints = 0;
const lensKinds = new Set();
const ctx = vm.createContext({
  console,
  window: {},
  document: { addEventListener: () => {} },
  setTimeout: (f) => { f(); return 0; },
  graphData: { id: 1 },
  lensKinds,
  isAuthenticated: () => true,
  repaintAfterPrime: () => { repaints += 1; },
  authFetch: (url) => {
    fetched.push(url);
    if (url === '/partials/tests-stream') return new Promise(() => {});  // never resolves — a stream
    return Promise.resolve({ ok: true, json: () => Promise.resolve(rows) });
  },
});
ctx.window.API = { api_tests_status: '/api/tests/status', api_tests_run: '/api/tests/run' };
ctx.API = ctx.window.API;
vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-tests.js'), 'utf8'), ctx, { filename: 'editor-tests.js' });

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

(async () => {
  console.log(' before priming the counts read "unknown"');
  assert(ctx.getTestStatusCount() === null, 'total null before prime');
  assert(ctx.getTestFailedCount() === null, 'failed null before prime');
  assert(ctx.getTestStatusForFnId('t2') === null, 'per-fn status null before prime');

  console.log(' loadTestStatuses primes total, failed and per-fn status');
  await ctx.loadTestStatuses();
  assert(ctx.getTestStatusCount() === 4, 'total counts every test (got ' + ctx.getTestStatusCount() + ')');
  assert(ctx.getTestFailedCount() === 2, 'failed counts the `failed` rows only (got ' + ctx.getTestFailedCount() + ')');
  assert(ctx.getTestStatusForFnId('t2').error === 'assert-eq failed', 'per-fn row carries the error');
  assert(ctx.getTestStatusForFnId('t3').status === null, 'a never-run test keeps a null status (stale dot)');

  console.log(' the test-ns predicate is a SEGMENT match');
  assert(ctx.isTestNsPath('app.tests') && ctx.isTestNsPath('tests.unit'), 'tests segment anywhere in the path');
  assert(!ctx.isTestNsPath('app.contests'), 'substring is not a segment');

  console.log(' the live stream opens only while the lens is active');
  const before = fetched.length;
  ctx.ensureTestsStream();
  assert(fetched.length === before, 'lens off → no stream request');
  lensKinds.add('tests');
  ctx.ensureTestsStream();
  await Promise.resolve();
  assert(fetched.includes('/partials/tests-stream'), 'lens on → the ping stream is opened');
  const streams = fetched.filter((u) => u === '/partials/tests-stream').length;
  ctx.ensureTestsStream();
  assert(fetched.filter((u) => u === '/partials/tests-stream').length === streams, 'a second ensure does not open a second stream');
  // the re-prime runs through a fetch chain — let the microtasks drain
  for (let i = 0; i < 5; i++) await new Promise((r) => setImmediate(r));
  assert(repaints >= 1, 'the prime repainted the tree');

  console.log(fails === 0 ? 'PASS — ' + passes + ' assertions' : 'FAIL — ' + fails + ' failed');
  process.exit(fails === 0 ? 0 : 1);
})();
