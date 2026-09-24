'use strict';

// editor-overlay-type-expand.js — the type panel's "Used by N" back-link.
// Pinned: the usages are asked for by the TYPE row's id, resolved through
// `resolveTypeFnIdByName` (the parent-less type-fn of that name) — not the
// first fnMap row that happens to share the name, which could be any fn in
// any namespace (ADR-identity-model) — and cached by that id.
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-overlay-type-expand.js'), 'utf8');

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
  const asked = [];
  const ctx = vm.createContext({
    console, Promise, JSON,
    document: doc,
    API: { api_types_usages: '/api/types/usages' },
    // A composed fn named `port` in some namespace sits FIRST in fnMap; the
    // type row of that name is the parent-less one.
    lookups: { fnMap: new Map([['x-port', { id: 'x-port', name: 'port', 'parent-ids': ['p'] }],
                               ['t-port', { id: 't-port', name: 'port' }]]) },
    resolveTypeFnIdByName: async (name) => (name === 'port' ? 't-port' : ''),
    fetch: async (_url, opts) => {
      asked.push(JSON.parse(opts.body)['type-fn-id']);
      return { ok: true, json: async () => ({ ok: true, usages: [{ kind: 'slot-of', 'fn-id': 'f', 'fn-name': 'srv' }] }) };
    },
  });
  ctx.window = ctx;
  vm.runInContext(SRC, ctx);

  console.log(' usages are fetched for the type row\'s id, and cached by it');
  const host = doc.createElement('div');
  ctx.appendTypeUsagesSection(host, 'port');
  await flush();
  assert(asked.join() === 't-port', 'asked for t-port, got ' + asked.join());
  assert(host.querySelector('.type-inline-usages-head').textContent === 'Used by 1', 'count rendered');
  const again = doc.createElement('div');
  ctx.appendTypeUsagesSection(again, 'port');
  await flush();
  assert(asked.length === 1, 'second panel served from the cache');
  assert(again.querySelector('.type-inline-usages-head').textContent === 'Used by 1', 'cached count rendered');
  assert(vm.runInContext('typeUsagesCache.has("t-port") && !typeUsagesCache.has("port")', ctx), 'keyed by id');

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
