'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../resources/packages/app/editor/editor-tour-cleanup.js'), 'utf8');
function fixture(overrides = {}) {
  const ctx = vm.createContext({...overrides});
  vm.runInContext(source, ctx);
  return ctx;
}
test('owned dependents precede parent, binding and list dependencies; outside IDs never enter cleanup', () => {
  const ctx = fixture();
  const entries = ['leaf', 'middle', 'root'].map(id => ({id}));
  const graph = {fns: [{id: 'root', 'parent-ids': ['middle', 'outside']}],
    bindings: [{id: 'b', 'fn-id': 'middle', 'ref-fn-id': 'leaf'}],
    'list-items': [{'binding-id': 'b', 'ref-fn-id': 'outside'}]};
  const ordered = ctx._tourOrderFnDeletes(entries, graph);
  assert.deepEqual(Array.from(ordered, x => x.id), ['root', 'middle', 'leaf']);
  assert(ordered.every(x => entries.includes(x)), 'the hint cannot manufacture deletion authority');
});
test('cycles and missing data retain exact entries for ordinary retry/refusal', () => {
  const ctx = fixture();
  const entries = [{id: 'a'}, {id: 'b'}];
  assert.equal(ctx._tourOrderFnDeletes(entries, {}), entries);
  const ordered = ctx._tourOrderFnDeletes(entries, {fns: [{id: 'a', 'parent-ids': ['b']},
    {id: 'b', 'parent-ids': ['a']}], bindings: [], 'list-items': []});
  assert.deepEqual(Array.from(ordered, x => x.id), ['a', 'b']);
});
test('saved views and fn-ref adapters share one exact dependent-first cleanup order', () => {
  const ctx = fixture();
  const entries = ['second', 'first', 'composed', 'adapter-first', 'adapter-second'].map(id => ({id}));
  const graph = {fns: entries, bindings: [
    {id: 'list', 'fn-id': 'composed'},
    {id: 'first-ref', 'fn-id': 'adapter-first', 'ref-fn-id': 'first', 'type-override-fn-id': 'fn-ref-type'},
    {id: 'second-ref', 'fn-id': 'adapter-second', 'ref-fn-id': 'second', 'type-override-fn-id': 'fn-ref-type'},
    {id: 'direct', 'fn-id': 'second', 'ref-fn-id': 'first'},
    {id: 'unrelated-literal', 'fn-id': 'first', value: 'composed'},
  ], 'list-items': [
    {'binding-id': 'list', 'ref-fn-id': 'adapter-first'},
    {'binding-id': 'list', 'ref-fn-id': 'adapter-second'},
  ]};
  const ordered = Array.from(ctx._tourOrderFnDeletes(entries, graph), entry => entry.id);
  for (const [consumer, target] of [['composed', 'adapter-first'], ['composed', 'adapter-second'],
    ['adapter-first', 'first'], ['adapter-second', 'second'], ['second', 'first']]) {
    assert(ordered.indexOf(consumer) < ordered.indexOf(target));
  }
  assert.deepEqual(new Set(ordered), new Set(entries.map(entry => entry.id)));
  assert.equal(ordered[0], 'composed', 'an arbitrary matching literal is not a fn-ref edge');
});
test('one owned scoped read changes order but every DELETE retains fresh receipt identity guards', async () => {
  const reads = [], deletes = [];
  const entries = [{type: 'fn', id: 'dependency', name: 'dependency', 'namespace-id': 'ns',
    creation: 'create-only-manifest', 'cleanup-order-root-id': 'root'},
  {type: 'fn', id: 'root', name: 'root', 'namespace-id': 'ns',
    creation: 'create-only-manifest', 'cleanup-order-root-id': 'root'}];
  const ctx = fixture({API: {api_graph_entities: '/graph', api_entities_type_id: (_type, id) => '/fn/' + id},
    authFetch: async url => {
      reads.push(url);
      return {ok: true, json: async () => ({fns: entries.map(x => ({...x, 'parent-ids': x.id === 'root' ? ['dependency'] : []})), bindings: [], 'list-items': []})};
    }, authMutate: async (_method, url) => { deletes.push(url); return {ok: true}; }});
  assert.equal((await ctx._tourDeleteFns(entries)).length, 0);
  assert.deepEqual(deletes, ['/fn/root', '/fn/dependency']);
  assert.equal(reads.length, 3, 'one ordering read plus each ordinary identity read');
  assert(reads.every(x => x.startsWith('/graph?scope=subtree&root-id=')));
});
