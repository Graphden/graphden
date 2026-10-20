'use strict';
const assert = require('node:assert/strict');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const literal = value => ({kind: 'literal', value: graph.encode(value)});
const arg = (name, expr) => ({name, slot: name, expr});
const fn = (id, primitive, args) => ({id, primitive, args, aliases: [], env: []});
const plan = {
  format: 1, primitiveAbi: 1, entries: {root: 'root'},
  primitives: [{id: 'assoc', op: 'assoc'}, {id: 'const', op: 'const'}],
  inputs: {root: {accepted: [], required: [], destinations: []}},
  functions: [
    fn('root', 'assoc', [arg('map', literal(new Map())), arg('key', literal(graph.keyword('view'))),
      arg('value', {kind: 'closure', fn: 'view', lambdaParams: ['input'], translation: []})]),
    fn('view', 'const', [arg('value', {kind: 'read', name: 'input', slot: 'input'})]),
  ],
};
const runtime = graph.createRuntime(plan, {operationLimit: 20});
const root = runtime.run('root');
const view = root.get(graph.keyword('view'));
assert(graph.isCallable(view));
for (let index = 0; index < 100; index++) assert.equal(graph.invoke(view, index), index,
  'each owned host invocation receives a fresh local operation bound');
assert.throws(() => graph.invoke({invoke: () => 1}, null), /Expected a graph callback/);
assert.throws(() => graph.invoke(Object.create(Object.getPrototypeOf(view)), null), /Expected a graph callback/);
assert.throws(() => { view.invoke = () => 'forged'; }, TypeError);
assert.throws(() => graph.invoke(view, view), /Unsupported graph value/);
assert.throws(() => graph.encode(root), /Unsupported graph value/,
  'callables remain local values and cannot be serialized as authority');
assert.throws(() => graph.invoke(view, 1, {}), /Invalid browser batch budget token/);
const budget = graph.createOperationBudget(3);
assert.equal(graph.invoke(view, 'first', budget), 'first');
assert.throws(() => graph.invoke(view, 'second', budget), /batch operation limit/);
console.log('Root callable values: branded invocation, repeat bounds and shared budget OK');

const serverPlan = structuredClone(plan);
serverPlan.primitives.push({id: 'server', op: 'server-call'});
serverPlan.functions[1] = fn('view', 'server', [
  arg('func', {kind: 'server', descriptor: {token: 'opaque'}}),
  arg('arg', {kind: 'read', name: 'input', slot: 'input'}),
]);
const intent = {request: true};
const query = graph.createRuntime(serverPlan, {serverCall(descriptor, input) {
  assert.deepEqual(descriptor, {token: 'opaque'});
  assert.equal(input, 'query');
  return intent;
}}).run('root').get(graph.keyword('view'));
assert.equal(graph.invoke(query, 'query'), intent, 'retained query dispatches through the generic server hook');
assert.throws(() => graph.invoke(graph.createRuntime(serverPlan).run('root').get(graph.keyword('view')), null),
  /Server call transport is unavailable/);
