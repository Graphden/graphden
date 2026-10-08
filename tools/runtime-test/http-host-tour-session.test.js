const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-tour-session.js'), 'utf8');

function fixture() {
  const revoked = [];
  const dialogs = [];
  const context = vm.createContext({
    window: {gdAccount: {id: 'owner'}}, graphdenCurrentOrg: null,
    crypto: {randomUUID: () => 'session-id'},
    getCurrentBranchName: () => 'main',
    _tourState: null, _tourSaveState() {}, _tourCopy: (_, fallback) => fallback,
    _tourDialog: opts => dialogs.push(opts),
    _tourDeleteHttpPublications: async rows => {revoked.push(...rows.map(row => row.id)); return [];},
    switchToBranch() {throw new Error('must not switch to a missing branch');},
  });
  vm.runInContext(source, context);
  return {context, revoked, dialogs};
}

test('ending a session revokes exact publications even when both lesson branches disappeared', async () => {
  const {context, revoked, dialogs} = fixture();
  const saved = {lessonId: '35', step: 5, phase: 'cleanup',
    activeBranch: 'deleted-child', sandboxBranch: 'deleted-parent',
    principal: {accountId: 'owner', orgId: null},
    created: [{type: 'http-publication', id: 'exact-lease-id'}]};
  assert.equal(await context._tourRestoreSession(saved, true), false);
  assert.deepEqual(revoked, ['exact-lease-id']);
  assert.equal(dialogs.at(-1).title, 'Tutorial branch unavailable');
});

test('a different principal cannot initiate publication cleanup', async () => {
  const {context, revoked} = fixture();
  await context._tourRestoreSession({lessonId: '35', phase: 'cleanup',
    principal: {accountId: 'another-owner', orgId: null},
    created: [{type: 'http-publication', id: 'foreign-lease-id'}]}, true);
  assert.deepEqual(revoked, []);
});

test('publication identity staging persists namespace, branch and owner before requests', () => {
  const {context} = fixture();
  let saves = 0;
  context._tourSaveState = () => {saves++;};
  context._tourState = {created: []};
  context._tourStep = () => ({creates: {type: 'http-publication', name: 'response'}});
  context._tourTrackHttpPublication('lease-id', {id: 'fn-id', name: 'response', 'namespace-id': 'ns-id'});
  const stored = JSON.parse(JSON.stringify(context._tourState.created[0]));
  assert.deepEqual(stored, {type: 'http-publication', id: 'lease-id', name: 'response',
    'fn-id': 'fn-id', 'namespace-id': 'ns-id', sessionId: 'session-id', branch: 'main',
    principal: {accountId: 'owner', orgId: null}});
  assert.equal(saves, 1);
  context._tourTrackHttpPublication('lease-id', {id: 'fn-id', name: 'response'});
  assert.equal(context._tourState.created.length, 1);
});
