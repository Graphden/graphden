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
console.log('PASS browser list/text limits and unsupported value rejection');
