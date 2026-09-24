'use strict';

// editor-literal-types.js — `findBindingOverrideChain` and `pathSegments`
// find a (fn, slot) binding through `lookups.bindingByFnSlot`, never by
// scanning every binding. `bindingMap` is keyed by binding ID; the old code
// probed it with `${fid}/${slotId}`, never hit, and fell back to a full scan
// per chain step — O(N²) per card render. Lookups are built by the real
// `buildLookups` so the key shape is the one the editor ships.
// Runs under node's vm; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

const ctx = vm.createContext({ console, document: createDocument(), richTypes: {}, lookups: null });
ctx.window = ctx;
for (const f of ['editor-data.js', 'editor-literal-types.js']) {
  vm.runInContext(fs.readFileSync(path.join(EDITOR, f), 'utf8'), ctx, { filename: f });
}

const data = {
  fns: [{ id: 'base', name: 'get-in' }, { id: 'mid', name: 'mid', 'parent-ids': ['base'] },
        { id: 'leaf', name: 'leaf', 'parent-ids': ['mid'] }],
  slots: [{ id: 's-path', name: 'path' }],
  'fn-slots': [{ 'fn-id': 'base', 'slot-id': 's-path', position: 0 }],
  bindings: [
    { id: 'b-mid', 'fn-id': 'mid', 'slot-id': 's-path', 'type-override-fn-id': 'T' },
    { id: 'b-leaf', 'fn-id': 'leaf', 'slot-id': 's-path', 'list-append': true },
  ],
  'list-items': [
    { id: 'i2', 'binding-id': 'b-leaf', position: 3, value: ':b' },
    { id: 'i1', 'binding-id': 'b-leaf', position: 1, value: ':a' },
  ],
};
ctx.lookups = ctx.buildLookups(data);
let scans = 0;
const values = ctx.lookups.bindingMap.values.bind(ctx.lookups.bindingMap);
ctx.lookups.bindingMap.values = () => { scans += 1; return values(); };

console.log(' findBindingOverrideChain: the ancestor override, found by index');
const chain = ctx.findBindingOverrideChain('leaf', 's-path');
assert(chain.length === 1 && chain[0].fnId === 'mid' && chain[0].overrideFnId === 'T',
  'mid carries the override, got ' + JSON.stringify(chain));

console.log(' pathSegments: the leaf binding\'s items in order, found by index');
const segs = ctx.pathSegments('leaf', 's-path');
assert(segs.map((s) => s.key).join() === 'a,b', 'ordered keys, got ' + JSON.stringify(segs));
assert(ctx.pathSegments('base', 's-path').length === 0, 'no binding → no segments');

assert(scans === 0, 'no scan over every binding, got ' + scans);

if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
console.log(`✓ ${passes} passed`);
