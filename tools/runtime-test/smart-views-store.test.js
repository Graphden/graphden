// editor-smart-views.js — the saved-views store + popover form under
// mini-dom: save writes localStorage and applies, delete removes,
// apply announces the tree change, a clear announces the way back.
//
// The fetch is stubbed to a canned scope=view payload, so this pins the
// module's OWN contract (state, storage, announce), not the server's.
//
// Run:  node tools/runtime-test/smart-views-store.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}

function test(name, fn) {
  console.log(' ' + name);
  try { fn(); } catch (e) { failures += 1; console.error('  ✗ threw: ' + e.message); }
}

function viewsCtx() {
  const document = createDocument();
  const store = new Map();
  const announced = [];
  const updates = [];
  const ctx = vm.createContext({
    console,
    document,
    window: { gdAnnounce: (m) => announced.push(m) },
    localStorage: {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v)),
      removeItem: (k) => store.delete(k),
    },
    installPopoverDismiss: () => {},
    focusIntoDialog: () => {},
    returnFocusTo: () => {},
    anchorBelowClamped: () => {},
    updateEntityList: () => updates.push(1),
    graphData: {},
    API: { api_graph_entities: '/api/graph/entities' },
    fetch: () => Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ fns: [{ id: 'x', name: 'member' }],
                                    'truncated?': false }),
    }),
  });
  vm.runInContext(
    fs.readFileSync(path.join(EDITOR, 'editor-smart-views.js'), 'utf8'),
    ctx, { filename: 'editor-smart-views.js' });
  return { ctx, document, store, announced, updates };
}

function walk(el, out) {
  out.push(el);
  for (const c of (el.children || [])) {
    if (c.tagName !== undefined) walk(c, out);
  }
  return out;
}
function byClass(root, cls) {
  return walk(root, []).filter((e) => String(e.className || '').includes(cls));
}

test('saving through the form persists, applies and announces', async () => {
  const { ctx, document, store, announced } = viewsCtx();
  ctx.gdOpenSmartViewsPop(document.createElement('div'));
  const pop = byClass(document.body, 'gd-views-pop')[0];
  assert(pop, 'popover mounted');
  const inputs = byClass(pop, 'gd-views-input');
  inputs[0].value = 'io-stuff';
  inputs[1].value = 'effect:io';
  byClass(pop, 'gd-views-save')[0].click();
  await new Promise((r) => setTimeout(r, 10));
  const saved = JSON.parse(store.get('graphden.smartViews'));
  assert(saved.length === 1 && saved[0].name === 'io-stuff'
    && saved[0].rule === 'effect:io', 'view persisted to localStorage');
  assert(ctx.gdActiveSmartView()?.name === 'io-stuff', 'view is active');
  assert(Array.isArray(ctx.gdSmartViewResults())
    && ctx.gdSmartViewResults().length === 1, 'results fetched');
  assert(announced.some((m) => /View io-stuff — 1 functions/.test(m)),
    'tree change announced (' + JSON.stringify(announced) + ')');
});

test('clearing announces the way back and drops the active view', async () => {
  const { ctx, announced } = viewsCtx();
  ctx.gdApplySmartView({ name: 'v', rule: 'name:x' });
  await new Promise((r) => setTimeout(r, 10));
  ctx.gdClearSmartView();
  assert(ctx.gdActiveSmartView() === null, 'no active view after clear');
  assert(announced.includes('Whole tree'), 'clear announced');
});

test('deleting a saved view removes it; deleting the ACTIVE one clears it', async () => {
  const { ctx, document, store } = viewsCtx();
  ctx.gdApplySmartView({ name: 'gone', rule: 'name:z' });
  await new Promise((r) => setTimeout(r, 10));
  store.set('graphden.smartViews', JSON.stringify([{ name: 'gone', rule: 'name:z' }]));
  ctx.gdOpenSmartViewsPop(document.createElement('div'));
  const pop = byClass(document.body, 'gd-views-pop')[0];
  byClass(pop, 'gd-views-del')[0].click();
  assert(JSON.parse(store.get('graphden.smartViews')).length === 0,
    'view removed from storage');
  assert(ctx.gdActiveSmartView() === null,
    'deleting the active view also deactivates it');
});

(async () => {
  // tests above are sync-wrapped; give their awaits a beat to settle
  await new Promise((r) => setTimeout(r, 100));
  if (failures) {
    console.error('FAIL: ' + failures + ' failed, ' + passes + ' passed');
    process.exit(1);
  }
  console.log('PASS: ' + passes + ' assertions');
})();
