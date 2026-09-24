'use strict';

// editor-branch-context.js — the deleted-branch recovery in the fetch wrap.
// Pinned: a 400 "Unknown branch" reloads onto main ONLY when the request was
// about the branch the tab stands on. The diff ghost and compare mode ask
// about ANOTHER branch with an explicit X-Graphden-Branch header; that branch
// being gone must not kick the user off theirs (it used to: comparing
// feature-a against a deleted feature-b sent the tab to main).
// Runs under node's vm; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-branch-context.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

function boot(currentBranch) {
  const seen = { replaced: null, toasts: [], sent: [] };
  const store = new Map([['graphden.branch', currentBranch]]);
  const unknown = {
    status: 400, ok: false,
    headers: { get: () => null },
    clone() { return { json: () => Promise.resolve({ error: 'Unknown branch' }) }; },
  };
  const ctx = vm.createContext({
    console, URL, URLSearchParams, Headers, Promise,
    location: { search: '', href: 'http://x/?branch=' + currentBranch,
                replace(u) { seen.replaced = u; } },
    localStorage: { getItem: (k) => store.get(k) ?? null, removeItem: (k) => store.delete(k),
                    setItem: (k, v) => store.set(k, v) },
    document: { body: { addEventListener() {}, classList: { toggle() {}, add() {} } } },
    gdToast: (m) => seen.toasts.push(m),
  });
  ctx.window = ctx;
  ctx.fetch = (_url, init) => {
    seen.sent.push(new Headers(init?.headers || {}).get('X-Graphden-Branch'));
    return Promise.resolve(unknown);
  };
  vm.runInContext(SRC, ctx);
  return { ctx, seen };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

(async () => {
  console.log(' explicit header for ANOTHER (deleted) branch → no recovery');
  {
    const { ctx, seen } = boot('feature-a');
    await ctx.window.fetch('/api/graph/entities', { headers: { 'X-Graphden-Branch': 'feature-b' } });
    await tick();
    assert(seen.sent[0] === 'feature-b', 'the explicit header is kept, got ' + seen.sent[0]);
    assert(seen.replaced === null, 'tab must stay on feature-a, got reload ' + seen.replaced);
    assert(seen.toasts.length === 0, 'no "no longer exists" toast');
  }

  console.log(' the tab\'s own branch is gone → recovers once onto main');
  {
    const { ctx, seen } = boot('feature-a');
    await ctx.window.fetch('/api/graph/entities');
    await ctx.window.fetch('/api/types');
    await tick();
    assert(seen.sent[0] === 'feature-a', 'the current branch is stamped');
    assert(seen.replaced !== null && !seen.replaced.includes('branch='), 'reloads without ?branch');
    assert(seen.toasts.length === 1 && seen.toasts[0].includes('feature-a'), 'one toast naming feature-a');
  }

  console.log(' explicit header naming the current branch → still recovers');
  {
    const { ctx, seen } = boot('feature-a');
    await ctx.window.fetch('/api/types', { headers: { 'x-graphden-branch': 'feature-a' } });
    await tick();
    assert(seen.replaced !== null, 'recovery runs');
  }

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
