'use strict';

// editor-diff-ghost.js — the compared branch's subtree cache. Pinned:
//   * the read carries the branch in BRANCH_HEADER (the fetch wrap adds
//     the bearer — the module used to call an undefined getStoredToken);
//   * a success is cached per branch|fn, a FAILURE is not (it used to be
//     cached as null, so a transient error hid the ghost until reload);
//   * gdDiffGhostsReset / gdDiffGhostsDropCache empty the cache, so
//     re-entering or refreshing compare mode re-reads the branch.
// Runs under node's vm; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

const calls = [];
let failNext = false;
const ctx = vm.createContext({
  console,
  window: {},
  BRANCH_HEADER: 'X-Graphden-Branch',
  API: { api_graph_entities: '/api/graph/entities' },
  buildLookups: (sub) => ({ fnMap: new Map(sub.fns.map((f) => [f.id, f])) }),
  fetch: (url, init) => {
    calls.push({ url, headers: init?.headers || {} });
    if (failNext) {
      failNext = false;
      return Promise.resolve({ ok: false, status: 502 });
    }
    return Promise.resolve({ ok: true, json: () => Promise.resolve({ fns: [{ id: 'f1', name: 'bar' }] }) });
  },
});
vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-diff-ghost.js'), 'utf8'), ctx,
  { filename: 'editor-diff-ghost.js' });

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}
const tick = () => new Promise((r) => setTimeout(r, 0));

(async () => {
  console.log(' the read names the compared branch through BRANCH_HEADER');
  const lk = await ctx.gdDiffGhostSubtree('feature', 'f1');
  assert(lk && lk.fnMap.get('f1').name === 'bar', 'subtree indexed via buildLookups');
  assert(calls.length === 1 && calls[0].headers['X-Graphden-Branch'] === 'feature',
    'branch header set: ' + JSON.stringify(calls[0]?.headers));
  assert(!('Authorization' in calls[0].headers), 'no hand-made bearer (the fetch wrap owns it)');

  console.log(' a success is cached per branch|fn');
  await ctx.gdDiffGhostSubtree('feature', 'f1');
  assert(calls.length === 1, 'second read served from the cache');
  await ctx.gdDiffGhostSubtree('other', 'f1');
  assert(calls.length === 2, 'another branch is another key');

  console.log(' a failure is NOT cached — the next render retries');
  calls.length = 0;
  failNext = true;
  const bad = await ctx.gdDiffGhostSubtree('flaky', 'f1');
  assert(bad === null, 'failed read resolves null');
  await tick();
  const good = await ctx.gdDiffGhostSubtree('flaky', 'f1');
  assert(calls.length === 2 && good !== null, 'retry fetched again and succeeded');

  console.log(' reset / drop-cache empty the cache');
  calls.length = 0;
  ctx.gdDiffGhostsReset();
  await ctx.gdDiffGhostSubtree('feature', 'f1');
  assert(calls.length === 1, 're-read after gdDiffGhostsReset');
  ctx.gdDiffGhostsDropCache();
  await ctx.gdDiffGhostSubtree('feature', 'f1');
  assert(calls.length === 2, 're-read after gdDiffGhostsDropCache');

  console.log(`\n${passes} passed, ${fails} failed`);
  process.exit(fails ? 1 : 0);
})();
