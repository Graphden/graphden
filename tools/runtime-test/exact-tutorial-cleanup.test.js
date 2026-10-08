'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const {trackExactTutorialFunctions} = require('../browser-test/exact-tutorial-cleanup');
const base = 'http://127.0.0.1:9960';
const ids = ['11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222'];
function fixture() {
  const page = new EventEmitter();
  const context = {origin: base, principal: {accountId: 'owner', orgId: 'org'}, branch: {id: 'main-id', name: 'main'}};
  const functions = new Map();
  const branches = new Map([['unrelated-30', {name: 'tutorial-30-owned-elsewhere'}]]);
  const deleted = [], snapshots = [];
  const transport = {context: async () => structuredClone(context),
    read: async id => ({fns: functions.has(id) ? [functions.get(id)] : []}),
    remove: async id => { deleted.push(id); functions.delete(id); return {ok: true, status: 200}; }};
  const cleanup = trackExactTutorialFunctions(page, {base, transport,
    persist: value => snapshots.push(structuredClone(value))});
  const create = async ({id = ids[0], name = 'tutorial-http-answer', path = '/api/entities/fn', receipt = true} = {}) => {
    const request = {method: () => 'POST', url: () => base + path,
      postData: () => new URLSearchParams({name, 'namespace-id': ''}).toString(), headers: () => ({})};
    page.emit('request', request);
    await new Promise(resolve => setImmediate(resolve));
    functions.set(id, {id, name, 'namespace-id': null});
    if (receipt) page.emit('response', {request: () => request, ok: () => true,
      headers: () => ({'x-graphden-created-id': id})});
    await new Promise(resolve => setImmediate(resolve));
    return request;
  };
  return {cleanup, context, functions, branches, deleted, snapshots, create, page, transport};
}
test('empty first cleanup is audited and does not mutate existing tutorial branches', async () => {
  const f = fixture();
  await f.cleanup();
  assert.deepEqual(f.deleted, []);
  assert(f.branches.has('unrelated-30'));
  assert.equal(f.snapshots.at(-1).receipts.length, 0);
});
test('only newly receipted UUID is deleted; same-name fn and retained lesson30 branch survive', async () => {
  const f = fixture();
  f.functions.set(ids[1], {id: ids[1], name: 'tutorial-http-answer', 'namespace-id': null});
  await f.create();
  assert.equal(f.snapshots.at(-1).receipts[0].receipt, 'created', 'canonical receipt saved before cleanup');
  await f.cleanup();
  assert.deepEqual(f.deleted, [ids[0]]);
  assert(f.functions.has(ids[1]));
  assert(f.branches.has('unrelated-30'));
  assert.equal(f.snapshots.at(-1).receipts[0].receipt, 'removed');
});
test('lost reply or missing success UUID stops cleanup without adopting a matching live row', async () => {
  for (const absentResponse of [true, false]) {
    const f = fixture();
    const request = await f.create({receipt: false});
    if (!absentResponse) f.page.emit('response', {request: () => request, ok: () => true, headers: () => ({})});
    await assert.rejects(f.cleanup(), /retained/);
    assert.deepEqual(f.deleted, []);
    assert(f.functions.has(ids[0]));
  }
});
test('principal, org, branch and origin changes cannot authorize cleanup', async () => {
  for (const change of [c => { c.principal.accountId = 'other'; }, c => { c.principal.orgId = 'other'; },
    c => { c.branch.id = 'other'; }, c => { c.origin = 'http://other'; }]) {
    const f = fixture();
    await f.create();
    change(f.context);
    await assert.rejects(f.cleanup(), /context changed/);
    assert.deepEqual(f.deleted, []);
  }
});
test('changed exact fn namespace or name retains its receipt', async () => {
  for (const field of ['name', 'namespace-id']) {
    const f = fixture();
    await f.create();
    f.functions.get(ids[0])[field] = 'other';
    await assert.rejects(f.cleanup(), /identity changed/);
    assert.deepEqual(f.deleted, []);
  }
});
test('unexpected namespace, service or branch creation fails and retains diagnostics', async () => {
  for (const path of ['/api/entities/ns', '/api/entities/service', '/api/branches']) {
    const f = fixture();
    await f.create({path});
    await assert.rejects(f.cleanup(), /retained/);
    assert.deepEqual(f.deleted, []);
    assert.equal(f.snapshots.at(-1).receipts[0].receipt, 'unsupported');
  }
});
test('dependency refusal is retried only after owned progress; other statuses remain fatal', async () => {
  const f = fixture();
  await f.create({id: ids[1], name: 'dependent'});
  await f.create({id: ids[0], name: 'dependency'});
  f.transport.remove = async id => {
    if (id === ids[0] && f.functions.has(ids[1])) return {ok: false, status: 409};
    f.deleted.push(id); f.functions.delete(id); return {ok: true, status: 200};
  };
  await f.cleanup();
  assert.deepEqual(f.deleted, [ids[1], ids[0]]);
  const blocked = fixture(); await blocked.create();
  blocked.transport.remove = async () => ({ok: false, status: 409});
  await assert.rejects(blocked.cleanup(), /dependencies retained/);
  const unknown = fixture(); await unknown.create();
  unknown.transport.remove = async () => ({ok: false, status: 500});
  await assert.rejects(unknown.cleanup(), /deletion refused/);
});
test('identity changes during the fresh lookup still prevent DELETE', async () => {
  const f = fixture(); await f.create();
  const read = f.transport.read;
  f.transport.read = async id => { const graph = await read(id); f.context.principal.accountId = 'other'; return graph; };
  await assert.rejects(f.cleanup(), /context changed/);
  assert.deepEqual(f.deleted, []);
});
test('duplicate success UUID cannot become two independent creation authorities', async () => {
  const f = fixture(); await f.create(); await f.create();
  await assert.rejects(f.cleanup(), /retained/);
  assert.deepEqual(f.deleted, []);
});
