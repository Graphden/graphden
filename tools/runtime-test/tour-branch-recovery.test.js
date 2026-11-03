'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');

const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-tour-session.js'), 'utf8');

function fixture() {
  const dialogs = [];
  const switches = [];
  let rows = [];
  let listStatus = 200;
  let post = null;
  let saves = 0;
  const context = vm.createContext({
    window: {API: {api_branches: '/api/branches'}, gdAccount: {id: 'owner'},
      gdAccountsReady: Promise.resolve(true)},
    API: {api_branches: '/api/branches'},
    graphdenCurrentOrg: null,
    crypto: {randomUUID: () => 'fresh-branch-id'},
    document: {removeEventListener() {}},
    _tourState: null, _tourTimer: null, _tourStartGeneration: 0,
    _tourStartTicket: () => ({}), _tourStartCurrent: () => true,
    _tourSaveState: () => {saves++;}, _tourStopFollowing() {}, _tourOnKey() {},
    _tourDialog: options => dialogs.push(options),
    _tourTeardown: () => {context._tourState = null;},
    _tourFetchLessons: async () => ({lessons: [{id: '01', version: 3}]}),
    _tourReadBranchBase: async () => ({id: 'main-id'}),
    getCurrentBranchName: () => 'main',
    switchToBranch: name => switches.push(name),
    fetch: async () => ({ok: true, json: async () => ({account: {id: 'owner'}})}),
    authFetch: async (_url, options) => options?.method === 'POST'
      ? post?.(JSON.parse(options.body))
      : {ok: listStatus === 200, status: listStatus,
        json: async () => ({branches: rows})},
  });
  vm.runInContext(source, context);
  return {context, dialogs, switches, get saves() {return saves;},
    setRows: value => {rows = value;}, setStatus: value => {listStatus = value;},
    setPost: value => {post = value;}};
}

function saved() {
  return {lessonId: '01', step: 4, sandboxBranch: 'tutorial-old',
    sandboxBranchId: 'old-id', sandboxBaseBranchId: 'main-id',
    activeBranch: 'tutorial-old', principal: {accountId: 'owner', orgId: null},
    created: [{type: 'fn', id: 'old-function-id', name: 'old-example'}]};
}

test('branch absence requires a successful listing and exact identity', async () => {
  const {context, setRows, setStatus} = fixture();
  const state = saved();
  setRows([{id: 'stranger-id', name: 'tutorial-old', 'base-branch-id': 'main-id'}]);
  assert.equal(await context._tourBranchStatus(state, 'tutorial-old'), 'missing');
  setStatus(403);
  assert.equal(await context._tourBranchStatus(state, 'tutorial-old'), 'unavailable');
  setStatus(503);
  assert.equal(await context._tourBranchStatus(state, 'tutorial-old'), 'unavailable');
  setStatus(200);
  setRows([{id: 'old-id', name: 'tutorial-old', 'base-branch-id': 'main-id'}]);
  assert.equal(await context._tourBranchStatus(state, 'tutorial-old'), 'available');
});

test('confirmed deletion keeps the lesson until Restart or Exit', () => {
  const f = fixture();
  f.context._tourMissingBranch(saved(), 'tutorial-old');
  assert.equal(f.context._tourState.step, 4);
  assert.equal(f.context._tourState.created[0].id, 'old-function-id');
  assert.equal(f.context._tourState.recoveryBranch, 'tutorial-old');
  assert.equal(f.context._tourStartGeneration, 1);
  assert.equal(f.dialogs.at(-1).title, 'Tutorial branch was deleted');
  assert(f.saves > 0);
  f.dialogs.at(-1).quiet[1]();
  assert.equal(f.context._tourState, null);
});

test('lost restart reply reconciles its UUID and never adopts old functions', async () => {
  const f = fixture();
  f.setRows([]);
  f.setPost(async body => {
    f.setRows([{id: body.id, name: body.name,
      'base-branch-id': body['base-branch-id']}]);
    throw new Error('reply lost after commit');
  });
  f.context._tourMissingBranch(saved(), 'tutorial-old');
  assert.equal(await f.dialogs.at(-1).primary[1](), true);
  assert.equal(f.context._tourState.step, 0);
  assert.equal(f.context._tourState.lessonVersion, 3);
  assert.equal(f.context._tourState.sandboxBranchId, 'fresh-branch-id');
  assert.deepEqual(JSON.parse(JSON.stringify(f.context._tourState.created)),
    [{type: 'branch', name: f.context._tourState.sandboxBranch,
      id: 'fresh-branch-id', 'base-branch-id': 'main-id', receipt: 'created'}]);
  assert.deepEqual(f.switches, [f.context._tourState.sandboxBranch]);
});

test('a rejected restart keeps the old session and starts no in-place lesson', async () => {
  const f = fixture();
  f.setRows([]);
  f.setPost(async () => ({ok: false, status: 403,
    json: async () => ({ok: false})}));
  f.context._tourMissingBranch(saved(), 'tutorial-old');
  assert.equal(await f.dialogs.at(-1).primary[1](), false);
  assert.equal(f.context._tourState.step, 4);
  assert.equal(f.context._tourState.restartPending, undefined);
  assert.deepEqual(f.switches, []);
});

test('a replaced recovery state cannot start an old request', async () => {
  const f = fixture();
  f.context._tourMissingBranch(saved(), 'tutorial-old');
  const restart = f.dialogs.at(-1).primary[1];
  f.context._tourState = {lessonId: '02'};
  assert.equal(await restart(), false);
  assert.deepEqual(f.switches, []);
});
