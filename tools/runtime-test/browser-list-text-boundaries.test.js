'use strict';

const assert = require('node:assert/strict');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');

function run(op, inputs, options) {
  const names = Object.keys(inputs);
  const plan = {
    format: 1, primitiveAbi: 1, entries: {view: 'entry'},
    primitives: [{id: 'primitive', op}],
    inputs: {entry: {accepted: names, required: names,
      destinations: names.map((name) => ({name, slots: [name]}))}},
    functions: [{id: 'entry', primitive: 'primitive', aliases: [], env: [],
      args: names.map((name) => ({name, slot: name, expr: {kind: 'read', name, slot: name}}))}],
  };
  return graph.createRuntime(plan, options).run('view', inputs);
}

assert.throws(() => run('str', {parts: [new Map()]}), /scalar parts/);
assert.throws(() => run('str', {parts: ['a'.repeat(1048576), 'b']}), /Invalid graph string/);
assert.throws(() => run('str', {parts: Array(100).fill('')}, {operationLimit: 20}), /operation limit/);
assert.throws(() => run('concat', {colls: Array(100).fill([])}, {operationLimit: 20}), /operation limit/);
assert.throws(() => run('concat', {colls: [Array(50001).fill(null)]}, {operationLimit: 100000}), /value limit/);
assert.throws(() => run('take', {count: 1.5, coll: []}), /safe integer/);
assert.throws(() => run('str-starts-with?', {string: 'a', prefix: null}), /Invalid graph string/);
assert.equal(run('str-starts-with?', {string: null, prefix: null}), false);
assert.deepEqual(run('take', {count: 0, coll: [1, 2]}), []);
for (const nums of [null, []]) assert.throws(() => run('lte', {nums}), /at least one/);
assert.equal(run('lte', {nums: [-Number.MAX_SAFE_INTEGER, 0, Number.MAX_SAFE_INTEGER]}), true);
assert.equal(run('lte', {nums: [3, 3, 2]}), false);
for (const nums of [[null], ['1'], [true], [1.5], [Number.MAX_SAFE_INTEGER + 1]]) {
  assert.throws(() => run('lte', {nums}), /safe integer/);
}
assert.throws(() => run('lte', {nums: Array(100).fill(1)}, {operationLimit: 30}), /operation limit/);
for (const limit of [-1, null, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
  assert.throws(() => run('str-clip', {string: 'abc', limit}), /non-negative|safe integer/);
}
for (const string of [null, false, 12, 'x'.repeat(1048577)]) {
  assert.throws(() => run('str-clip', {string, limit: 0}), /Invalid graph string/);
}
assert.equal(run('str-clip', {string: '🙂x', limit: 1}), '\uD83D');
assert.equal(run('str-clip', {string: '🙂x', limit: 2}), '🙂');
assert.equal(run('str-clip', {string: 'e\u0301x', limit: 1}), 'e');
assert.equal(run('str-clip', {string: 'abc', limit: Number.MAX_SAFE_INTEGER}), 'abc');
console.log('PASS browser list/text limits and unsupported value rejection');
