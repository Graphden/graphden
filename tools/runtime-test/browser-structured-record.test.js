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
function runCallback(entry, supplied = {}, callbackPlan = callback) {
  return graph.createRuntime({
    format: 2, primitiveAbi: 1, entries: {view: 'entry'},
    inputs: {entry: {structured: {
      fields: [{name: 'items', slot: 'items'}],
      presence: [{id: 'items', kind: 'supplied', sources: []}],
      required: [],
    }}},
    primitives: ['map', 'const', 'equal?'].map((op) => ({id: op, op})),
    functions: [entry, externalValue, callbackPlan],
  }).run('view', supplied);
}
const namedMap = scopedFn('entry', 'entry', 'map', {
  coll: {kind: 'supplied', slot: 'items', type: {listOf: {anyOf: ['null', 'bool']}}},
  func: structuredCallback,
});
const mapped = runCallback(namedMap, {items: [null, false, null]});
assert.deepEqual([...mapped], [true, false, true]);
assert.throws(() => runCallback(namedMap, {items: [7]}), /does not match/);
const suppliedDefault = (fallback) => scopedFn('entry', 'entry', 'const', {
  value: {kind: 'supplied-default', slot: 'items', fallback, type: {anyOf: ['null', 'text']}},
});
assert.equal(runCallback(suppliedDefault(['string', 'Ada'])), 'Ada');
assert.equal(runCallback(suppliedDefault(['string', 'Ada']), {items: null}), null);
assert.equal(runCallback(suppliedDefault(['nil'])), null);
assert.throws(() => runCallback(suppliedDefault({kind: 'call', fn: 'other'})), /Invalid browser field default/);
assert.throws(() => runCallback(suppliedDefault(['int', 3])), /Invalid browser field default/);
const localDefault = {...suppliedDefault(['string', 'Ada']), id: 'callback', frame: 'callback'};
assert.throws(() => [...runCallback(namedMap, {items: [false]}, localDefault)], /Unsupported browser expression/);
const opaqueMap = scopedFn('entry', 'entry', 'const', {
  value: {kind: 'supplied', slot: 'items', type: {mapOf: ['keyword', 'any']}},
});
const dictionary = new Map([[graph.keyword('ref'), 'data'], [graph.keyword('value'), false]]);
assert.equal(runCallback(opaqueMap, {items: dictionary}), dictionary);
assert.throws(() => runCallback(opaqueMap, {items: new Map([['ref', 'data']])}), /does not match/);
const escaped = runCallback(scopedFn('entry', 'entry', 'const', {value: structuredCallback}));
assert.throws(() => graph.invoke(escaped, false), /outside its invocation/);
assert.throws(() => escaped.invoke(false), /outside its invocation/);
function runRecordInput(supplied, requiredFields, required = ['config']) {
  const label = {kind: 'supplied', slot: 'label', type: 'text'};
  const config = {...field('config', record({...field('label', label), present: 'label'})), present: 'config'};
  return graph.createRuntime({
    format: 2, primitiveAbi: 1, entries: {view: 'entry'},
    inputs: {entry: {structured: {
      fields: [{name: 'config', slot: 'config', fields: [
        {name: 'label', slot: 'label', key: graph.encode(graph.keyword('label'))},
      ]}],
      presence: [
        {id: 'label', kind: 'supplied', sources: []},
        {id: 'config', kind: 'supplied-record', sources: ['label'], 'required-fields': requiredFields},
      ],
      required,
    }}},
    primitives: [{id: 'const', op: 'const'}],
    functions: [scopedFn('entry', 'entry', 'const', {value: record(config)})],
  }).run('view', supplied);
}
const configKey = graph.keyword('config');
const emptyConfig = {config: new Map()};
assert.throws(() => runRecordInput({}, []), /Missing graph input field/);
assert.deepEqual([...runRecordInput(emptyConfig, []).get(configKey).keys()], []);
assert.deepEqual([...runRecordInput({}, [], []).keys()], []);
assert.throws(() => runRecordInput(emptyConfig, ['label'], []), /Missing graph input field/);
assert.equal(runRecordInput({config: new Map([[graph.keyword('label'), 'Ada']])}, ['label'], [])
  .get(configKey).get(graph.keyword('label')), 'Ada');


const recordSlot = (name, slot, fields) => ({key: graph.encode(graph.keyword(name)), slot, fields});
const recordCallback = {
  kind: 'checked-closure', fn: 'callback', slots: [], required: ['item'],
  record: {slot: 'item', fields: [
    recordSlot('value', 'value'), recordSlot('unused', 'unused'),
    recordSlot('details', 'details', [recordSlot('flag', 'flag')]),
  ]},
  presence: [
    {id: 'value', kind: 'supplied', sources: []},
    {id: 'unused', kind: 'supplied', sources: []},
    {id: 'flag', kind: 'supplied', sources: []},
    {id: 'details', kind: 'supplied-record', sources: ['flag'], 'required-fields': ['flag']},
    {id: 'item', kind: 'supplied-record', sources: ['value', 'unused', 'details'], 'required-fields': ['value']},
  ],
};
const readRecord = scopedFn('callback', 'callback', 'const', {value: {kind: 'supplied', slot: 'value', type: 'any'}});
const recordMap = scopedFn('entry', 'entry', 'map', {func: recordCallback, coll: {kind: 'supplied', slot: 'items', type: 'any'}});
const recordValue = (fields) => new Map(fields.map(([name, value]) => [graph.keyword(name), value]));
assert.deepEqual([...runCallback(recordMap, {items: [
  recordValue([['value', null]]), recordValue([['value', false], ['details', recordValue([['flag', null]])]]),
]}, readRecord)], [null, false]);
assert.throws(() => [...runCallback(recordMap, {items: [recordValue([])]}, readRecord)], /Missing graph input field/);
assert.throws(() => [...runCallback(recordMap, {items: [recordValue([['value', null], ['details', recordValue([])]])]}, readRecord)], /Missing graph input field/);
assert.throws(() => [...runCallback(recordMap, {items: [recordValue([['unknown', null]])]}, readRecord)], /Unknown graph input field/);
const lazyMap = scopedFn('entry', 'entry', 'map', {
  func: recordCallback,
  coll: {kind: 'seq', items: [record(field('value', literal('kept')), field('unused', fault))]},
});
assert.deepEqual([...runCallback(lazyMap, {}, readRecord)], ['kept']);

console.log('PASS deferred browser record fields, presence, and version admission');
