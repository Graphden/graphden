// UUID-first creation tracking before an atomic view POST, including lost
// response persistence and definite rollback. No runtime or graph mocks.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const persisted = [];
const state = {created: [], activeBranch: 'main', principal: {accountId: 'account', orgId: 'org'}};
const context = vm.createContext({window: {}, _tourState: state,
  _tourStep: () => ({creates: {type: 'fn', name: 'tutorial-function-view'}}),
  _tourPrincipalMatches: () => true,
  _tourSaveState: () => persisted.push(JSON.parse(JSON.stringify(state))),
  getCurrentBranchName: () => 'main'});
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-tour-session.js'), 'utf8'), context);
// Use the production principal guard, defined in that module.
context.window.gdAccount = {id: 'account'};
context.graphdenCurrentOrg = 'org';
const command = {name: 'tutorial-function-view', 'create-id': 'fresh-uuid', 'namespace-id': null};
context._tourTrackGraphViewCreation(command);
assert.equal(persisted.at(-1).created[0].id, 'fresh-uuid');
assert.equal(persisted.at(-1).created[0]['namespace-id'], null);
assert.equal(persisted.at(-1).activeBranch, 'main');
// No success callback is needed: a lost transport response retains ownership.
assert.equal(state.created.length, 1);
context._tourRejectGraphViewCreation({...command, 'create-id': 'unrelated'});
assert.equal(state.created.length, 1);
context._tourRejectGraphViewCreation(command);
assert.equal(state.created.length, 0);
context.graphdenCurrentOrg = 'another-org';
context._tourTrackGraphViewCreation(command);
assert.equal(state.created.length, 0);
context.graphdenCurrentOrg = 'org';
context._tourTrackGraphViewCreation({...command, name: 'other-view'});
assert.equal(state.created.length, 0);
console.log('PASS: view creation tracked before response; rollback and identity scope guarded');
