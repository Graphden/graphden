'use strict';

const assert = require('node:assert/strict');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');

const literal = (value) => ({kind: 'literal', value: graph.encode(value)});
const field = (name, expr) => ({key: graph.encode(graph.keyword(name)), expr});
const record = (...fields) => ({kind: 'record', fields});
const call = (fn) => ({kind: 'call', fn, renames: []});
const lookup = (coll, name, fallback = null) => ({
  coll, key: literal(graph.keyword(name)), default: literal(fallback),
});

function run(op, args, {format = 2, helpers = [], ...options} = {}) {
  const fn = (id, primitive, bindings) => ({
    id, primitive, env: [], aliases: [],
    args: Object.entries(bindings).map(([name, expr]) => ({name, expr})),
  });
  const plan = {
    format, primitiveAbi: 1, entries: {view: 'entry'},
    inputs: {entry: {accepted: [], required: [], destinations: []}},
    primitives: [...new Set([op, ...helpers.map((helper) => helper[1])])]
      .map((name) => ({id: name, op: name})),
    functions: [fn('entry', op, args), ...helpers.map(([id, name, bindings]) => fn(id, name, bindings))],
  };
  return graph.createRuntime(plan, options).run('view');
}

const fault = {kind: 'not-an-expression'};
const nilField = field('nil', literal(null));
const details = record(field('ready', literal(false)), field('unused', fault));
const root = record(field('details', details), nilField);
const readDetails = ['read-details', 'get', lookup(root, 'details')];

assert.equal(run('get', lookup(call('read-details'), 'ready', 'fallback'), {helpers: [readDetails]}), false);
assert.equal(run('get', lookup(root, 'nil', 'fallback')), null);
assert.equal(run('get', lookup(root, 'missing', 'fallback')), 'fallback');
assert.equal(run('count', {coll: details}), 2);
assert.throws(() => run('get', lookup(details, 'unused')), /Unsupported browser expression/);
assert.throws(() => run('const', {value: root}, {format: 1}), /Invalid browser input record/);
assert.throws(() => run('const', {value: root}, {format: 3}), /Unsupported browser graph format/);
assert.throws(() => run('count', {coll: record(nilField, nilField)}), /Duplicate browser input field/);

const serverExpr = call('compute');
const serverHelper = ['compute', 'server-call', {
  func: {kind: 'server', descriptor: 'single-read'}, arg: literal('input'),
}];
const attempts = [];
const view = run('const', {value: record(field('computed', serverExpr), nilField)}, {
  helpers: [serverHelper],
  serverCall(descriptor, value) { attempts.push([descriptor, value]); return false; },
});
const key = graph.keyword('computed');
assert.deepEqual([...view.keys()], [key, graph.keyword('nil')]);
assert.equal(attempts.length, 0);
assert.equal(view.get(key), false);
assert.deepEqual([...view.values()], [false, null]);
assert.deepEqual(attempts, [['single-read', 'input']]);
assert.throws(() => view.set(key, null), /Cannot mutate/);
assert.deepEqual(graph.decode(graph.encode(view)), new Map([[key, false], [graph.keyword('nil'), null]]));
const structuredCallback = {
  kind: 'checked-closure', fn: 'callback', slots: ['item'], required: ['item'],
  presence: [{id: 'item', kind: 'supplied', sources: []}],
};
const scopedFn = (id, frame, primitive, bindings) => ({
  id, frame, primitive, env: [], aliases: [],
  args: Object.entries(bindings).map(([name, expr]) => ({name, expr})),
});
const callback = scopedFn('callback', 'callback', 'equal?', {
  a: {kind: 'supplied', slot: 'item', type: 'any'},
  b: call('outer'),
});
const externalValue = scopedFn('outer', 'entry', 'const', {value: literal(null)});
function runCallback(entry) {
  return graph.createRuntime({
    format: 2, primitiveAbi: 1, entries: {view: 'entry'},
    inputs: {entry: {structured: {fields: [], presence: [], required: []}}},
    primitives: ['map', 'const', 'equal?'].map((op) => ({id: op, op})),
    functions: [entry, externalValue, callback],
  }).run('view');
}
const mapped = runCallback(scopedFn('entry', 'entry', 'map', {
  coll: literal([null, false, null]), func: structuredCallback,
}));
assert.deepEqual([...mapped], [true, false, true]);
const escaped = runCallback(scopedFn('entry', 'entry', 'const', {value: structuredCallback}));
assert.throws(() => graph.invoke(escaped, false), /outside its invocation/);
assert.throws(() => escaped.invoke(false), /outside its invocation/);
console.log('PASS deferred browser record fields, presence, and version admission');
