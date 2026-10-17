'use strict';
const assert = require('node:assert/strict');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const read = name => ({kind: 'read', name, slot: name});
const entry = (id, primitive, args) => ({id, primitive, args, aliases: [], env: []});
const arg = (name, expr) => ({name, slot: name, expr});
const plan = {
  format: 1, primitiveAbi: 1,
  entries: {compare: 'compare', nested: 'nested', lazy: 'lazy'},
  primitives: [{id: 'lte', op: 'lte'}, {id: 'map', op: 'map'}, {id: 'list', op: 'list'}],
  inputs: {
    compare: {accepted: ['nums'], required: ['nums'], destinations: [{name: 'nums', slots: ['nums']}]},
    nested: {accepted: ['rows'], required: ['rows'], destinations: [{name: 'rows', slots: ['rows']}]},
    lazy: {accepted: ['item'], required: ['item'], destinations: [{name: 'item', slots: ['item']}]},
  },
  functions: [
    entry('compare', 'lte', [arg('nums', read('nums'))]),
    entry('nested', 'map', [
      arg('func', {kind: 'closure', fn: 'compare', lambdaParams: ['nums'], translation: []}),
      arg('coll', read('rows')),
    ]),
    entry('lazy', 'list', [arg('items', {kind: 'seq', items: Array(10).fill(read('item'))})]),
  ],
};
const runtime = graph.createRuntime(plan, {operationLimit: 150000});
for (const limit of [0, -1, null, NaN, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
  assert.throws(() => graph.createOperationBudget(limit), /Invalid browser batch budget/);
}
assert.throws(() => runtime.run('compare', {nums: () => 1}, {}), /Invalid browser batch budget token/,
  'token validation precedes input codec and execution');
const measured = graph.createOperationBudget(1000);
assert.equal(runtime.run('compare', {nums: [1, 2, 3]}, measured), true);
const cost = 1000 - measured.remaining;
assert(cost > 3);
assert.throws(() => { measured.remaining = 999; }, TypeError);
assert.throws(() => graph.encode(measured), /Unsupported graph value/);
const shared = graph.createOperationBudget(cost * 2);
assert.equal(runtime.run('compare', {nums: [1, 2, 3]}, shared), true);
assert.equal(runtime.run('compare', {nums: [1, 2, 3]}, shared), true);
assert.equal(shared.remaining, 0);
assert.throws(() => runtime.run('compare', {nums: [1]}, shared), /batch operation limit/);
assert.equal(shared.remaining, 0, 'exhausted budget cannot be reset by another invocation');

const nested = graph.createOperationBudget(1000);
assert.deepEqual(graph.encode(runtime.run('nested', {rows: [[1, 2], [2, 3]]}, nested)),
  ['vector', [['bool', true], ['bool', true]]]);
const nestedCost = 1000 - nested.remaining;
const tooSmall = graph.createOperationBudget(nestedCost - 1);
assert.throws(() => runtime.run('nested', {rows: [[1, 2], [2, 3]]}, tooSmall), /batch operation limit/);
assert(tooSmall.remaining < 0, 'captured callbacks consume the same root counter');
const perRun = graph.createOperationBudget(1000);
assert.throws(() => graph.createRuntime(plan, {operationLimit: 1}).run('compare', {nums: [1, 2]}, perRun),
  /graph operation limit/);
assert(perRun.remaining < 1000, 'per-run exhaustion also charges the aggregate counter');
const lazyBudget = graph.createOperationBudget(5);
const lazy = runtime.run('lazy', {item: 7}, lazyBudget);
assert(lazyBudget.remaining > 0);
assert.throws(() => graph.encode(lazy), /batch operation limit/,
  'deferred sequence expressions retain the invocation and aggregate budget');
assert.equal(runtime.run('compare', {nums: [1]}, graph.createOperationBudget(1000)), true);
for (const durationMs of [0, -1, 1.5, Infinity]) {
  assert.throws(() => graph.createOperationBudget(100, {durationMs}), /Invalid browser batch duration/);
}
let clock = 0;
const expired = graph.createOperationBudget(1000, {durationMs: 50, now: () => clock});
clock = 50;
assert.throws(() => runtime.run('compare', {nums: () => 'invalid'}, expired), /batch time limit/,
  'deadline validation precedes the input codec');
clock = 0;
const afterCodec = graph.createOperationBudget(1000, {durationMs: 50, now: () => clock});
assert.throws(() => runtime.run('compare', {get nums() { clock = 50; return [1]; }}, afterCodec),
  /batch time limit/);
assert.equal(afterCodec.remaining, 1000, 'codec time is checked before executing graph operations');
const times = [0, 0, 0, 50];
const afterRun = graph.createOperationBudget(1000, {durationMs: 50, now: () => times.shift()});
assert.throws(() => runtime.run('compare', {nums: [1]}, afterRun), /batch time limit/,
  'short invocations check deadline after returning, even before the tick sample boundary');
clock = 0;
const sampled = graph.createOperationBudget(10000, {durationMs: 50, now: () => (clock += 20)});
assert.throws(() => runtime.run('compare', {nums: Array(1000).fill(1)}, sampled), /batch time limit/);
assert(sampled.remaining > 9000, 'clock sample aborts within a long invocation instead of waiting for its end');
clock = 10;
const backwards = graph.createOperationBudget(1000, {durationMs: 50, now: () => clock});
clock = 5;
assert.throws(() => runtime.run('compare', {nums: [1]}, backwards), /Invalid browser batch clock/);
console.log('PASS shared browser operation budget: monotonic, closures, deferred work and per-run ceiling', {cost, nestedCost});
