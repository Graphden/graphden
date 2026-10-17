'use strict';
// Execute shipped plans plus the actual ordinal adapter/data projection. No
// mirrored JS grouping policy or network is involved in this contract test.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {performance} = require('node:perf_hooks');
const root = path.join(__dirname, '../..');
const graph = require(path.join(root, 'resources/packages/app/ui-preview/browser-runtime.js'));
const plan = require(path.join(root, 'resources/packages/app/ui-preview/builtin-plans.json')).plans.canvasInheritance;
const record = value => Array.isArray(value) ? value.map(record)
  : value && typeof value === 'object' && !(value instanceof Map)
    ? new Map(Object.entries(value).map(([key, item]) => [graph.keyword(key), record(item)])) : value;
let runs = 0;
const inputs = [];
const runtime = graph.createRuntime(plan, {operationLimit: 2048});
const ctx = vm.createContext({Map, Set, console, Number, Object, window: {},
  lookups: {fnMap: new Map(), bindingsByFn: new Map(), inheritanceLevelsCache: new Map()},
});
Object.assign(ctx.window, {GraphdenBrowser: graph, GraphdenBuiltinPlans: {plans: {canvasInheritance: plan}},
  gdCheckCanvasFlowStyles() {}, addEventListener() {},
  gdCreatePassiveCanvas(config) {
    return {mount() {}, dispose() {}, initialBatch(build) {
      return build((input, validate) => { runs++; inputs.push(input); return validate(runtime.run('initial', {inputs: record(input)})); });
    }};
  },
});
for (const file of ['editor-canvas-inheritance-graph.js', 'editor-data.js']) {
  vm.runInContext(fs.readFileSync(path.join(root, 'resources/packages/app/editor', file), 'utf8'), ctx);
}
const policy = ctx.window.gdCanvasInheritanceGraph;
const facts = (position, mi = false, clickable = false, duplicate = false) =>
  ({position, depth: position, mi, clickable, 'duplicate-next': duplicate});
const result = policy.groups([facts(0), facts(1, true, true), facts(2), facts(3), facts(4, false, true)], false);
assert.deepEqual(Array.from(result, row => [row.position, row.group, row.root, row['follows-mi']]),
  [[0, 0, true, -1], [1, 1, false, -1], [2, 2, false, 1], [3, 2, false, 1], [4, 3, false, -1]]);
const dedup = policy.groups([facts(0), facts(1, false, false, true), facts(2, false, true)], false);
assert.equal(dedup.length, 3, 'every original ordinal has exactly one receipt');
assert.equal(dedup[1].visible, false);
assert.equal(dedup[2].group, 1, 'hidden passive duplicate does not consume a group');
const paints = policy.paintBatch([
  {root: true, cell: true, clickable: false, within: false, 'partial-next': false, expanded: false},
  {root: true, cell: true, clickable: true, within: false, 'partial-next': true, expanded: true},
  {root: false, cell: false, clickable: true, within: true, 'partial-next': false, expanded: false},
]);
assert.deepEqual(Array.from(paints, value => [value.bg, value.bold]),
  [['var(--card-header-bg)', true], ['var(--card-row-highlight)', true], ['var(--card-bg)', false]]);
// Distinct namespace identities with the SAME label must never collapse.
for (const row of [{id: 'a', name: 'same', 'namespace-id': 'ns-a', 'parent-ids': ['b']},
  {id: 'b', name: 'same', 'namespace-id': 'ns-b', 'parent-ids': []},
  {id: 'anonymous', name: null, 'parent-ids': ['b']}]) ctx.lookups.fnMap.set(row.id, row);
ctx.layers = [['a'], ['b']];
assert.equal(vm.runInContext('buildAncestorLevels(layers).length', ctx), 2);
ctx.layers = [['root'], ['anonymous'], ['b']];
ctx.lookups.fnMap.set('root', {id: 'root', name: 'root', 'parent-ids': ['anonymous']});
assert.equal(vm.runInContext('buildAncestorLevels(layers).length', ctx), 3,
  'anonymous inherited label retains its distinct native row actions');
ctx.lookups.bindingsByFn.set('anonymous', [{value: 1}]);
assert.equal(vm.runInContext('buildAncestorLevels(layers).length', ctx), 3,
  'anonymous clickability/controls are never lost through display equivalence');
// Tight per-step budget is independent of topology length. Native traversal
// allocates one receipt per ordinal; no prefix arrays or growing accumulator.
let maxOperations = 0;
for (const input of inputs.slice(0, 16)) {
  let low = 0, high = 2048;
  while (low < high) {
    const limit = Math.floor((low + high) / 2);
    try { graph.createRuntime(plan, {operationLimit: limit}).run('initial', {inputs: record(input)}); high = limit; }
    catch (error) { assert.match(error.message, /operation limit/); low = limit + 1; }
  }
  maxOperations = Math.max(maxOperations, high);
}
assert(maxOperations < 512, 'actual finite policy fits a tight bound');
for (const length of [64, 512, 2048, 4096]) {
  const before = runs;
  const start = performance.now();
  const output = policy.groups(Array.from({length}, (_, index) => facts(index, index % 7 === 1, index % 5 === 1)), false);
  assert.equal(output.length, length);
  assert.equal(runs - before, length, 'one fixed-size graph step per original ordinal');
  assert.equal(output[length - 1].position, length - 1, 'no truncation at old proposed stress bounds');
  console.log(JSON.stringify({levels: length, graphSteps: length, operationsUpperBound: length * maxOperations,
    nativeReceiptWrites: length, elapsedMs: Math.round(performance.now() - start)}));
}
console.log('PASS canvas inheritance ordinal projection, grouping, paint, namespace identity and linear steps');
