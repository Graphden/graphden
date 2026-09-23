'use strict';

// editor-feedback.js — what the "Report a problem" form attaches. The
// signed-in "Recent failed executions" box used to fetch
// /partials/error-log, a route retired on 2026-09-04: the fetch 404'd and
// the report silently carried nothing. It now reads the failed-runs lens's
// own JSON (GET /api/failures) and attaches fn names + counts only.
// Runs under node's vm; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

const failures = [
  { 'fn-id': 'f1', 'fn-name': 'fetch-user', 'namespace-id': 'ns', count: 3 },
  { 'fn-id': 'f2', 'fn-name': 'render', 'namespace-id': null, count: 1 },
];

const fetched = [];
const ctx = vm.createContext({
  console,
  window: { addEventListener() {} },
  document: { body: { classList: { contains: () => false } } },
  installTabTrap() {},
  fetch: () => Promise.reject(new Error('offline')),
  authFetch: (url) => {
    fetched.push(url);
    if (url === '/api/failures') {
      return Promise.resolve({ ok: true, json: () => Promise.resolve(failures) });
    }
    return Promise.resolve({ ok: false, status: 404, text: () => Promise.resolve('') });
  },
});
ctx.window.API = { api_failures: '/api/failures' };
ctx.API = ctx.window.API;
vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-feedback.js'), 'utf8'), ctx,
  { filename: 'editor-feedback.js' });

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

(async () => {
  console.log(' ticked box → the failed-runs JSON is attached as names + counts');
  const on = await ctx._fbBuildContext({ failures: true });
  assert(fetched.length === 1 && fetched[0] === '/api/failures',
    'reads GET /api/failures (and nothing else): ' + fetched.join(','));
  assert(Array.isArray(on.failures) && on.failures.length === 2, 'two rows attached');
  assert(on.failures[0].fn === 'fetch-user' && on.failures[0].count === 3, 'name + count kept');
  assert(!('fn-id' in on.failures[0]) && !('namespace-id' in on.failures[0]),
    'ids are not shipped to the intake');
  assert(!fetched.some((u) => u.includes('/partials/error-log')), 'the retired partial is never read');

  console.log(' unticked box → nothing fetched, nothing attached');
  fetched.length = 0;
  const off = await ctx._fbBuildContext({ failures: false });
  assert(fetched.length === 0, 'no fetch when unticked');
  assert(!('failures' in off), 'no failures key when unticked');

  console.log(' API cache missing → sent without the section, no throw');
  ctx.window.API = null;
  ctx.API = null;
  const noApi = await ctx._fbBuildContext({ failures: true });
  assert(!('failures' in noApi), 'no failures key without the API cache');

  console.log(`\n${passes} passed, ${fails} failed`);
  process.exit(fails ? 1 : 0);
})();
