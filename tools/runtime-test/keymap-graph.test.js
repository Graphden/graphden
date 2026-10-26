'use strict';

// The shipped graph decides conflicts and markup; the host only supplies the
// live registry and performs the resulting actions.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '../../resources/packages/app/ui-preview');
const context = vm.createContext({Map, Set, WeakSet, WeakMap, Symbol});
context.window = context;
for (const file of ['browser-runtime.js', 'builtin-plans.js']) {
  vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context, {filename: file});
}
const api = context.GraphdenBrowser;
const key = api.keyword;
const record = value => new Map(Object.entries(value).map(([name, item]) => [key(name), item]));
const runtime = api.createRuntime(context.GraphdenBuiltinPlans.plans.keymap, {operationLimit: 100000});
const entry = (id, keys) => record({id, keys, 'key-parts': keys.split(' '), group: 'Graph',
  description: id, leader: true, 'default-keys': id, 'default-leader': true,
  active: true, overridden: false});
function output(entries, extra = {}) {
  const input = record({entries, 'source-name': '', 'source-version': '',
    'change-count': 0, marketplace: false, 'recording-id': '', 'recording-keys': '', ...extra});
  return runtime.run('view', {inputs: input}).get(key('tree'));
}
function nodes(tree, result = []) {
  if (!Array.isArray(tree) && !api.isSequence(tree)) return result;
  if (Array.isArray(tree) && tree[0] instanceof api.Keyword) result.push(tree);
  for (const child of tree) nodes(child, result);
  return result;
}
function attrs(node) { return node[1] instanceof Map ? node[1] : new Map(); }
function byClass(tree, name) {
  return nodes(tree).filter(node => String(attrs(node).get(key('class')) || '').split(' ').includes(name));
}
function text(tree, result = []) {
  if (typeof tree === 'string') result.push(tree);
  else if (Array.isArray(tree) || api.isSequence(tree)) for (const child of tree) text(child, result);
  return result.join('');
}
const normal = output([entry('fit', 'g f'), entry('zoom', 'g z')]);
assert.equal(byClass(normal, 'gd-km-row').length, 2);
assert.equal(byClass(normal, 'gd-km-conflict').length, 0);
assert.equal(byClass(normal, 'gd-key-cap').length, 6);
assert.match(text(normal), /default bindings/);
const conflict = output([entry('fit', 'g f'), entry('zoom', 'g f')],
  {'source-name': 'Vim', 'source-version': '1.0.0', marketplace: true,
    'recording-id': 'zoom', 'recording-keys': 'p'});
assert.equal(byClass(conflict, 'is-conflict').length, 2);
assert.equal(byClass(conflict, 'gd-km-conflict').length, 1);
assert.equal(byClass(conflict, 'gd-km-recording').length, 1);
assert.equal(byClass(conflict, 'gd-theme-save').length, 1);
assert.match(text(conflict), /Based on Vim@1\.0\.0/);
assert.match(text(conflict), /p  \(Enter to keep/);
assert.equal(byClass(output(Array.from({length: 40}, (_, index) => entry('item-' + index, 'g ' + index))),
  'gd-km-row').length, 40);
console.log('PASS keymap graph: rows, conflicts, source and recording');
