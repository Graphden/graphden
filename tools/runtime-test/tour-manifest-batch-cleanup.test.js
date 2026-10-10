'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-tour-cleanup.js'), 'utf8');
const id = n => '00000000-0000-0000-0000-' + String(n).padStart(12, '0');
const branch = id(9001), namespace = id(9002), root = id(9003);
function receipts(count = 2) {
  return Array.from({length: count}, (_, i) => ({type: 'fn', id: id(i + 1), name: 'value-' + i,
    'namespace-id': namespace, creation: 'create-only-manifest', receipt: 'created',
    'manifest-root-id': root, 'branch-id': branch, 'branch-name': 'main'}));
}
function fixture(rows, body, status = 200) {
  const calls = [], effects = {saved: 0, cleared: 0};
  const ctx = vm.createContext({API: {api_entities_fn_delete_batch: '/api/entities/fn/delete-batch'},
    selectedFnId: rows[0].id, getCurrentBranchName: () => 'main',
    _tourSaveState: () => effects.saved++, gdClearSelection: () => effects.cleared++,
    authFetch: async (url, options) => {
      calls.push({url, options});
      if (status === 'throw') throw new Error('Reply lost');
      return {ok: status === 200, json: async () => body};
    }});
  vm.runInContext(source, ctx);
  return {ctx, calls, effects, run: options => ctx._tourDeleteFns(rows, options)};
}
test('352 exact receipts use one branch-scoped request and retire only after a complete reply', async () => {
  const rows = receipts(352);
  const f = fixture(rows, {deleted: rows.map(row => row.id), 'already-absent': []});
  assert.equal((await f.run()).length, 0);
  assert.equal(f.calls.length, 1);
  const {url, options} = f.calls[0];
  assert.equal(url, '/api/entities/fn/delete-batch');
  assert.equal(options.method, 'POST');
  assert.equal(options.headers['X-Graphden-Branch'], branch);
  assert.equal(options.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(options.body).functions, rows.slice().reverse().map(row =>
    ({id: row.id, name: row.name, 'namespace-id': namespace})));
  assert(rows.every(row => row.receipt === 'removed'));
  assert.deepEqual(f.effects, {saved: 1, cleared: 1});
});
test('partial, duplicate, foreign and malformed replies retain all receipts and selection', async () => {
  for (const body of [{deleted: [id(1)], 'already-absent': []},
    {deleted: [id(1)], 'already-absent': [id(1)]},
    {deleted: [id(1), id(33)], 'already-absent': []},
    {deleted: [id(1), id(2)]}, null]) {
    const rows = receipts(), f = fixture(rows, body);
    assert.equal((await f.run()).length, 2);
    assert(rows.every(row => row.receipt === 'created'));
    assert.deepEqual(f.effects, {saved: 0, cleared: 0});
    assert.equal(f.calls.length, 1, 'no unguarded single DELETE fallback');
  }
});
test('conflict and ambiguous transport outcomes retain the exact set', async () => {
  for (const status of [400, 403, 409, 500, 'throw']) {
    const rows = receipts(), f = fixture(rows, {}, status);
    assert.equal((await f.run()).length, rows.length);
    assert(rows.every(row => row.receipt === 'created'));
    assert.equal(f.calls.length, 1);
    assert.deepEqual(f.effects, {saved: 0, cleared: 0});
  }
});
test('already absent rows reconcile while a surviving selection remains', async () => {
  const rows = receipts(), f = fixture(rows, {deleted: [id(1)], 'already-absent': [id(2)]});
  f.ctx.selectedFnId = id(77);
  assert.equal((await f.run()).length, 0);
  assert.deepEqual(f.effects, {saved: 1, cleared: 0});
});
test('a branch switch during the request does not clear that branch selection', async () => {
  const rows = receipts(), f = fixture(rows, {deleted: [id(1), id(2)], 'already-absent': []});
  f.ctx.getCurrentBranchName = () => 'other';
  assert.equal((await f.run()).length, 0);
  assert.deepEqual(f.effects, {saved: 1, cleared: 0});
});
test('ordinary lessons, mixed manifests and removed-sandbox routing keep ordinary cleanup', async () => {
  for (const change of [rows => { rows[0].creation = 'ordinary'; },
    rows => { rows[0]['manifest-root-id'] = id(88); }]) {
    const rows = receipts(), f = fixture(rows, {});
    change(rows);
    assert.equal(await f.ctx._tourDeleteManifestFns(rows), null);
    assert.equal(f.calls.length, 0);
  }
  const rows = receipts(), f = fixture(rows, {});
  assert.equal(await f.ctx._tourDeleteManifestFns(rows, {headers: {'X-Graphden-Branch': 'different-main'}}), null);
  assert.equal(f.calls.length, 0);
});
