'use strict';
const assert = require('node:assert/strict');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const input = {accepted: ['value'], required: ['value'], destinations: [{name: 'value', slots: ['value']}]};
const plan = {
  format: 1, primitiveAbi: 1,
  entries: {view: 'view', encode: 'encode'},
  primitives: [{id: 'view', op: 'as-ui-view'}, {id: 'encode', op: 'encode-view'}],
  inputs: {view: input, encode: input},
  functions: [
    {id: 'view', primitive: 'view', aliases: [], env: [], args: [
      {name: 'hiccup', slot: 'value', expr: {kind: 'read', name: 'value', slot: 'value'}},
    ]},
    {id: 'encode', primitive: 'encode', aliases: [], env: [], args: [
      {name: 'view', slot: 'value', expr: {kind: 'read', name: 'value', slot: 'value'}},
    ]},
  ],
};
const runtime = graph.createRuntime(plan);
const representation = ['span', '#aabbcc'];
assert.deepEqual(runtime.run('view', {value: representation}), representation);
assert.deepEqual(runtime.run('encode', {value: representation}), representation);
console.log('PASS UiView browser ABI preserves ordinary representation');
