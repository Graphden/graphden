'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../resources/packages/app/ui-preview');
const context = vm.createContext({Map, Set, WeakSet, WeakMap, Symbol});
context.window = context;
for (const file of ['browser-runtime.js', 'builtin-plans.js'])
  vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context, {filename: file});
const api = context.GraphdenBrowser;
const key = api.keyword;
const record = value => new Map(Object.entries(value).map(([name, item]) => [key(name), item]));
const runtime = api.createRuntime(context.GraphdenBuiltinPlans.plans.appearance, {operationLimit: 100000});
function output(extra = {}) {
  const inputs = record({hint: 'Built-in look', 'graph-status': 'Choose a graph',
    'has-graph': false, 'has-payload': false, 'can-create': true, editing: false,
    marketplace: true, saved: [], mode: 'light', scale: 100,
    'scale-min': '70', 'scale-max': '160',
    fonts: [record({id: 'ui', label: 'UI font', value: 'sans-serif'})],
    groups: [record({name: 'Ink', open: true,
      items: [record({name: '--gd-ink', label: 'Ink', value: '#111111'})]})],
    contrast: 'Ink on paper contrast 16:1 ✓', 'low-contrast': false, ...extra});
  return runtime.run('view', {inputs}).get(key('tree'));
}
function nodes(tree, result = []) {
  if (!Array.isArray(tree) && !api.isSequence(tree)) return result;
  if (Array.isArray(tree) && tree[0] instanceof api.Keyword) result.push(tree);
  for (const child of tree) nodes(child, result);
  return result;
}
const attrs = node => node[1] instanceof Map ? node[1] : new Map();
const byId = (tree, id) => nodes(tree).filter(node => attrs(node).get(key('id')) === id);
const text = tree => nodes(tree).flatMap(node => node.filter(item => typeof item === 'string')).join(' ');
const initial = output();
assert.equal(byId(initial, 'gd-theme-edit').length, 1);
assert.equal(byId(initial, 'gd-theme-editor').length, 0);
assert.equal(byId(initial, 'gd-theme-create-graph').length, 1);
const editing = output({editing: true, saved: [record({label: 'My theme@1.0.0', value: 'My theme@1.0.0', active: true})]});
assert.equal(byId(editing, 'gd-theme-editor').length, 1);
assert.equal(nodes(editing).filter(node => attrs(node).get(key('data-token')) === '--gd-ink').length, 1);
assert.equal(byId(editing, 'gd-theme-scale').length, 1);
assert.match(text(editing), /My theme@1\.0\.0/);
const graph = output({'has-graph': true, 'has-payload': true, 'can-create': false});
assert.equal(byId(graph, 'gd-theme-edit').length, 0);
assert.equal(byId(graph, 'gd-theme-detach-graph').length, 1);
console.log('PASS appearance graph: built-in, editor, saved version and selected graph');
