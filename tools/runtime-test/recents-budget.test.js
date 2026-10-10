'use strict';
// A valid maximal state must fit both installed runtime and renderer budgets.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const artifact = JSON.parse(fs.readFileSync('resources/packages/app/ui-preview/builtin-plans.json', 'utf8'));
const value = item => Array.isArray(item) ? item.map(value)
  : item && typeof item === 'object' ? new Map(Object.entries(item).map(([key, field]) => [graph.keyword(key), value(field)])) : item;
const entries = (count, prefix) => Array.from({length: count}, (_, index) => ({
  id: prefix + String(index).padStart(12, '0'), name: 'fn' + index, qname: 'core.fn' + index,
}));
const pins = entries(1000, '11111111-1111-4111-8111-');
const trail = entries(6, '22222222-2222-4222-8222-');
const inputs = value({pins, trail, selected: null, searching: false});
const runtime = graph.createRuntime(artifact.plans.recents, {operationLimit: 1000000});
const state = runtime.run('initial', {inputs});
graph.encode(state);
const view = runtime.run('view', {state, inputs});
graph.encode(view);
const update = runtime.run('update', {state, inputs, event: value({kind: 'toggle-pin', entry: pins[0]})});
graph.encode(update);
assert.equal([...update.get(graph.keyword('state')).get(graph.keyword('pins'))].length, 999);
const ctx = vm.createContext({Map, Set, WeakSet, console});
ctx.window = ctx;
ctx.GraphdenBrowser = graph;
ctx.GraphdenStyles = {};
for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/graph-renderer.js']) {
  vm.runInContext(fs.readFileSync('resources/packages/' + file, 'utf8'), ctx);
}
ctx.GraphdenRenderer.vnode(view.get(graph.keyword('tree')), null, 10000);
console.log('PASS recents 1000 pins + 6 trail: initial/view/update and 10000-node renderer budget');
