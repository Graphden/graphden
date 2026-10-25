'use strict';

// Graph metrics share the same trace identities as the native canvas host.
// Raw captured values never cross into the graph, and a personal graph cannot
// unhide a secret frame or invent an unrelated function ID.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const packages = path.join(__dirname, '../../resources/packages');
const listeners = new Map();
const context = vm.createContext({Map, Set, WeakSet, Symbol, console,
  addEventListener(name, callback) { listeners.set(name, callback); }});
context.window = context;
for (const file of ['app/ui-preview/browser-runtime.js', 'app/ui-preview/builtin-plans.js']) {
  vm.runInContext(fs.readFileSync(path.join(packages, file), 'utf8'), context, {filename: file});
}
let candidate = null;
let failures = 0;
context.gdEditorRoot = {
  componentNow() { return candidate; },
  acceptComponent() { return true; },
  releaseComponent() {},
};
context.gdUIComponentFailed = () => { failures++; };
vm.runInContext(fs.readFileSync(path.join(packages, 'app/editor/editor-execution-path-graph.js'), 'utf8'),
  context, {filename: 'editor-execution-path-graph.js'});

const trace = [
  {'fn-id': 'f1', 'duration-ms': 2, value: {secret: 'never export'}},
  {'fn-id': 'f1', 'cache-hit?': true},
  {'fn-id': 'f1', 'duration-ms': 3},
  {'fn-id': 'f2', hidden: 'secret'},
];
const metrics = context.gdExecutionPathMetrics(trace);
assert.equal(metrics.get('f1').badge, '3× 5ms');
assert.equal(metrics.get('f1').maxMs, 3);
assert.match(metrics.get('f1').title, /2 fresh \(total 5 ms, max 3 ms\) · 1 cache hit/);
assert.equal(metrics.get('f2').hidden, true);
assert.equal(metrics.get('f2').badge, 'secret');
assert.equal(JSON.stringify([...metrics]).includes('never export'), false);

// The executor may retain 10,000 trace entries; all of them must fit the
// browser graph's bounded input and operation budgets.
const cappedTrace = Array.from({length: 10000}, () => ({'fn-id': 'f1', 'duration-ms': 1}));
const capped = context.gdExecutionPathMetrics(cappedTrace);
assert.equal(capped.get('f1').count, 10000);
assert.equal(capped.get('f1').totalMs, 10000);

const k = context.GraphdenBrowser.keyword;
candidate = {run() { return new Map([[k('rows'), [new Map([
  [k('fn-id'), 'f2'], [k('count'), 1], [k('fresh'), 1], [k('hits'), 0],
  [k('total-ms'), 0], [k('max-ms'), 0], [k('hidden'), false],
  [k('badge'), 'leaked'], [k('title'), 'leaked'],
])]]]); }};
listeners.get('gd-editor-root-changing')();
assert.equal(context.gdExecutionPathMetrics([trace[3]]).get('f2').badge, 'secret');
assert.equal(failures, 1, 'concealed personal trace is rejected and builtin survives');
assert.equal(context.gdExecutionPathMetrics([trace[3]]).get('f2').badge, 'secret');
assert.equal(failures, 1, 'rejected root is not retried until a root change');
console.log('PASS execution path graph: aggregation, value isolation and secret fallback');
