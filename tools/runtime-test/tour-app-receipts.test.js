'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const dir = path.join(__dirname, '../../resources/packages/app/editor');

function setup() {
  const active = {accountId: 'owner', orgId: 'organization'};
  const principal = {...active};
  const world = {apps: [], unavailable: false, deleted: [], saves: 0};
  const ctx = vm.createContext({console, URLSearchParams,
    window: {crypto: {randomUUID: () => 'new-app-id'}, API: {
      api_orgs_apps: '/api/orgs/apps', api_orgs_apps_delete: '/api/orgs/apps/delete',
    }},
    _tourState: {lessonId: '14', principal, created: [
      {type: 'branch', id: 'lesson-branch', name: 'lesson'},
      {type: 'fn', id: 'handler-id', name: 'handler', receipt: 'created'},
    ]},
    _tourStep: () => ({creates: {type: 'app-route', name: 'handler'}}),
    _tourSessionBranch: () => 'lesson',
    _tourSaveState: () => { world.saves++; },
    _tourPrincipalMatches: saved => !!saved?.principal
      && saved.principal.accountId === active.accountId && saved.principal.orgId === active.orgId,
    refreshAppRoutesCache: async () => {},
    getAppRoutesForFnId: id => world.apps.filter(row => row['handler-fn-id'] === id),
    authFetch: async (url, options) => {
      if (world.unavailable) return {ok: false, json: async () => null};
      if (options?.method === 'POST') {
        const id = new URLSearchParams(options.body).get('id');
        world.deleted.push(id);
        world.apps = world.apps.filter(row => row.id !== id);
        // Ordinary storage delete DTO, not an invented {ok:true} response.
        return {ok: true, json: async () => ({id})};
      }
      return {ok: true, json: async () => world.apps};
    },
  });
  vm.runInContext(fs.readFileSync(path.join(dir, 'editor-tour-receipts.js'), 'utf8'), ctx);
  vm.runInContext(fs.readFileSync(path.join(dir, 'editor-tour-app-receipts.js'), 'utf8'), ctx);
  const form = {querySelector: selector => ({value: selector.includes('handler-fn-id') ? 'handler-id' : 'unique-label'})};
  const ticket = ctx.gdTourBeginAppCreation(form);
  const entry = ctx._tourState.created.at(-1);
  return {ctx, active, world, form, ticket, entry};
}

(async () => {
  {
    const {ctx, world, form, ticket, entry} = setup();
    assert.equal(entry.receipt, 'pending');
    assert.ok(world.saves > 0, 'persist exact UUID before submitting the ordinary form');
    form.gdTourAppTicket = ticket;
    assert.equal(ctx.gdTourBeginAppCreation(form), ticket, 'duplicate submit reuses one pending create-only UUID');
    assert.equal(ctx._tourState.created.length, 3);
    world.apps.push({id: entry.id, label: entry.label, 'handler-fn-id': 'handler-id'});
    await ctx.gdTourRecordAppCreation(ticket, {status: 200, getResponseHeader: () => 'different-id'});
    assert.equal(entry.receipt, 'pending', 'mismatched response never adopts an existing route');
    await ctx.gdTourRecordAppCreation(ticket, {status: 200, getResponseHeader: () => entry.id});
    assert.equal(entry.receipt, 'created');
    assert.equal(ctx.gdTourAppCreationPasses({name: 'handler'}), true);
    world.apps[0]['handler-fn-id'] = 'another-handler';
    assert.equal(ctx.gdTourAppCreationPasses({name: 'handler'}), false, 'retargeting invalidates the lesson gate');
    assert.equal((await ctx._tourDeleteAppRoutes([entry])).length, 1);
    assert.deepEqual(world.deleted, [], 'cleanup preserves a retargeted app');
  }
  {
    const {ctx, world, ticket, entry} = setup();
    await ctx.gdTourRecordAppCreation(ticket, {status: 500});
    assert.equal(entry.receipt, 'pending', 'ambiguous transport/server outcome retains recovery ownership');
    world.apps.push({id: entry.id, label: entry.label, 'handler-fn-id': 'handler-id'},
      {id: 'preexisting', label: entry.label, 'handler-fn-id': 'handler-id'});
    assert.equal((await ctx._tourDeleteAppRoutes([entry])).length, 0);
    assert.equal(entry.receipt, 'removed');
    assert.deepEqual(world.deleted, [entry.id], 'lost-response cleanup removes only the proposed create-only UUID');
    assert.equal(world.apps[0].id, 'preexisting', 'same-named preexisting app survives');
  }
  {
    const {ctx, world, ticket, entry} = setup();
    await ctx.gdTourRecordAppCreation(ticket, {status: 409});
    assert.ok(!ctx._tourState.created.includes(entry), 'definite create-only conflict discards only that attempt');
    assert.equal(ctx._tourState.created.length, 2, 'handler and branch receipts survive rejection');
    assert.deepEqual(world.deleted, []);
  }
  {
    const {ctx, active, world, ticket, entry} = setup();
    world.apps.push({id: entry.id, label: entry.label, 'handler-fn-id': 'handler-id'});
    active.orgId = 'different-organization';
    await ctx.gdTourRecordAppCreation(ticket, {status: 200, getResponseHeader: () => entry.id});
    assert.equal(entry.receipt, 'pending');
    assert.equal((await ctx._tourDeleteAppRoutes([entry])).length, 1);
    assert.deepEqual(world.deleted, [], 'changed principal cannot confirm or remove the saved app');
  }
  {
    const {ctx, world, entry} = setup();
    world.unavailable = true;
    assert.equal((await ctx._tourDeleteAppRoutes([entry])).length, 1,
      'inaccessible app list preserves the receipt instead of treating it as absent');
    vm.runInContext(fs.readFileSync(path.join(dir, 'editor-tour-cleanup.js'), 'utf8'), ctx);
    let touchedGraph = false;
    ctx._tourDeleteCreatedBranches = async () => { touchedGraph = true; return []; };
    ctx._tourDeleteFns = async () => { touchedGraph = true; return []; };
    const result = await ctx._tourDeleteCreated(ctx._tourState.created);
    assert.equal(touchedGraph, false, 'failed app cleanup blocks all handler/branch deletion');
    assert.equal(result.failed.length, 3, 'whole ledger remains available for retry');
  }
  console.log('✓ tour-app-receipts: exact identity, pending recovery, rejection, principal and dependency order');
})().catch(error => { console.error(error); process.exitCode = 1; });
