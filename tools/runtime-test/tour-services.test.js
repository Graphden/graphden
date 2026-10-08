'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const base = path.join(__dirname, '../../resources/packages/app/editor');
const uuid = '11111111-1111-4111-8111-111111111111';
const principal = {accountId: 'owner', orgId: 'org'};
let checks = 0;
function boot() {
  const state = {lessonId: '39', step: 0, principal, created: [
    {type: 'fn', name: 'worker', id: 'worker-id', receipt: 'created'},
    {type: 'fn', name: 'publisher', id: 'pub-id', receipt: 'created'},
    {type: 'fn', name: 'handler', id: 'handler-id', receipt: 'created'},
  ]};
  const seen = [];
  const ctx = vm.createContext({console, Date, Promise, Set, JSON,
    _tourState: state, step: {creates: {type: 'service', name: 'worker'}},
    _tourStep: () => ctx.step, _tourSaveState() {},
    _tourSessionBranch: () => 'lesson', _tourReceiptBranch: () => 'branch-id',
    _tourPrincipalMatches: saved => saved?.principal?.accountId === ctx.owner,
    owner: 'owner', crypto: {randomUUID: () => uuid},
    API: {api_execute_id: id => '/execute/' + id, api_entities_type_id: (type, id) => '/' + type + '/' + id},
    authFetch: async (url, options) => { seen.push([url, options]); throw Error('unexpected request'); },
    fetchServices: async () => ({services: ctx.services || []}),
    readServiceInstances: async () => ({count: ctx.instances || 0}),
    saveService: async (...args) => { seen.push(['save', args]); return {ok: true}; },
    reconcileServices: async () => { seen.push(['reconcile']); return {ok: true}; },
    deleteService: async id => { seen.push(['delete', id]); return {ok: true}; },
  });
  ctx.window = ctx;
  vm.runInContext(fs.readFileSync(path.join(base, 'editor-tour-receipts.js'), 'utf8'), ctx);
  // These tests own the existing sandbox, rather than booting the whole tour.
  ctx._tourReceiptBranch = () => 'branch-id';
  vm.runInContext(fs.readFileSync(path.join(base, 'editor-tour-services.js'), 'utf8'), ctx);
  return {ctx, state, seen};
}
function check(value, message) { assert.ok(value, message); checks++; }
(async () => {
  {
    const {ctx, state} = boot();
    assert.throws(() => ctx.gdTourBeginServiceCreation({id: 'worker-id', name: 'worker'}, 'main-id'));
    check(state.created.length === 3, 'wrong branch creates no receipt');
    const ticket = ctx.gdTourBeginServiceCreation({id: 'worker-id', name: 'worker'}, 'branch-id');
    check(state.created[3].id === uuid && state.created[3].receipt === 'pending', 'exact UUID staged before mutation');
    ctx.gdTourServiceCreationResult(ticket, {ok: false, status: 409});
    check(state.created[3].receipt === 'removed', 'collision cannot grant cleanup ownership');
  }
  {
    const {ctx, state, seen} = boot();
    ctx.step = {creates: {type: 'queue-message', name: 'publisher'}};
    assert.throws(() => ctx.gdTourBeginQueueRun('pub-id', false));
    ctx.gdTourBeginQueueRun('pub-id', true);
    const failed = await ctx._tourCleanupServices(state.created);
    check(failed.length === 1 && failed[0].type === 'queue-message', 'lost reply remains unresolved');
    check(seen.length === 0, 'unresolved publish never guesses by latest/name or deletes');
  }
  {
    const {ctx, state, seen} = boot();
    ctx.step = {creates: {type: 'queue-message', name: 'publisher'}};
    const ticket = ctx.gdTourBeginQueueRun('pub-id', true);
    ctx.gdTourQueueRunResult(ticket, {ok: true}, {'execution-id': 'run-id'});
    const run = {id: 'run-id', 'fn-id': 'other-id', status: 'succeeded', result: uuid, children: []};
    let message = {id: uuid, state: 'dead', attempts: 5};
    ctx.authFetch = async url => {
      seen.push(url);
      return {ok: true, status: 200, json: async () => url === '/execute/run-id' ? run : message};
    };
    await assert.rejects(() => ctx._tourReadQueueMessage(state.created[3]), /identity/);
    check(!state.created[3].id && seen.length === 1, 'wrong producer cannot claim message');
    run['fn-id'] = 'pub-id';
    check(await ctx._tourProbeServiceStep({kind: 'queue-state', name: 'publisher', state: 'dead'}, state), 'actual dead message observed');
    message = {id: uuid, state: 'pending', attempts: 0};
    check(await ctx._tourProbeServiceStep({kind: 'queue-state', name: 'publisher', state: 'pending'}, state), 'actual requeue observed');
    message = {};
    const ack = {kind: 'queue-state', name: 'publisher', state: 'acked', handler: 'handler'};
    check(!await ctx._tourProbeServiceStep(ack, state), 'deleted message alone is not ACK proof');
    run.children = [{'fn-id': 'handler-id', status: 'succeeded'}];
    check(await ctx._tourProbeServiceStep(ack, state), 'ACK has exact publisher and successful handler hop');
  }
  {
    const {ctx, state, seen} = boot();
    ctx.gdTourBeginServiceCreation({id: 'worker-id', name: 'worker'}, 'branch-id');
    ctx.services = [{id: uuid, 'fn-id': 'worker-id', 'branch-id': 'branch-id', 'enabled?': true}];
    ctx.instances = 1;
    check((await ctx._tourCleanupServices(state.created)).length === 1, 'registered worker blocks graph cleanup');
    check(seen[0][0] === 'save' && seen[0][1][2].enabled === false, 'disable precedes instance check');
    check(!seen.some(row => row[0] === 'delete'), 'running service is retained');
    ctx.instances = 0;
    ctx.services[0]['enabled?'] = false;
    check((await ctx._tourCleanupServices(state.created)).length === 0, 'stopped worker can be removed');
    check(seen.some(row => row[0] === 'delete' && row[1] === uuid), 'delete only exact service UUID');
  }
  console.log('tour-services: ' + checks + ' assertions passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
