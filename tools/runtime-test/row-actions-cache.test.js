'use strict';

// editor-row-actions.js `loadRowActionsContent` — the cached-HTML fast path
// writes the shared popover host synchronously. It must claim the host
// (graphden-runtime.js `claimPartialHost`) like loadPartial does: otherwise a
// slower uncached load for row A, still in flight, lands over row B's cached
// actions and B's popover shows buttons that act on A.
// Runs the real runtime + row-actions sources under node's vm.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const ROOT = path.join(__dirname, '..', '..', 'resources', 'packages');
const RUNTIME = fs.readFileSync(path.join(ROOT, 'web', 'runtime', 'graphden-runtime.js'), 'utf8');
const RA = fs.readFileSync(path.join(ROOT, 'app', 'editor', 'editor-row-actions.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

(async () => {
  const doc = createDocument();
  const pending = [];
  const ctx = vm.createContext({
    console, Promise, JSON, Map, Number, String,
    document: doc,
    window: { API: {} },
    fetch: (url) => new Promise((resolve) => { pending.push({ url, resolve }); }),
  });
  vm.runInContext(RUNTIME, ctx);
  vm.runInContext(RA, ctx);
  // innerHTML: mini-dom does not parse HTML — record what was written.
  const host = doc.createElement('div');
  let html = '';
  Object.defineProperty(host, 'innerHTML', {
    get() { return html; },
    set(v) { html = v; },
  });
  const urlOf = (fnId) => '/partials/row-actions?fn-id=' + fnId + '&context=cell';
  vm.runInContext('_rowActionsHtmlCache', ctx).set(urlOf('B'), 'B-ACTIONS');

  console.log(' a cached row\'s actions survive an older in-flight load');
  ctx.loadRowActionsContent(host, 'A', 'cell', {});   // uncached → in flight
  await ctx.loadRowActionsContent(host, 'B', 'cell', {});
  assert(html === 'B-ACTIONS', 'B shown from cache, got ' + html);
  assert(pending.length === 1 && pending[0].url === urlOf('A'), 'A\'s fetch is the one in flight');
  pending[0].resolve({ ok: true, text: async () => 'A-ACTIONS' });
  await flush();
  assert(html === 'B-ACTIONS', 'A\'s late response did not land over B, got ' + html);

  console.log(' an uncached load still swaps in when nothing superseded it');
  ctx.loadRowActionsContent(host, 'C', 'cell', {});
  await flush();
  pending[1].resolve({ ok: true, text: async () => 'C-ACTIONS' });
  await flush();
  assert(html === 'C-ACTIONS', 'C swapped in, got ' + html);

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
