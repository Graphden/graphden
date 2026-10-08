// The ordinary sandbox factory pre-stages ownership and keeps ambiguous commits recoverable.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const editor = path.join(__dirname, '../../resources/packages/app/editor');
function boot(outcome) {
  const saves = [], calls = [];
  let created = null;
  const ctx = vm.createContext({console, crypto, API: {api_branches: '/api/branches'},
    _tourState: null, _tourLessons: null, graphdenCurrentOrg: 'org', gdAccount: {id: 'account'},
    getCurrentBranchName: () => 'main',
    _tourFetchLessons: async () => ({lessons: [{id: '32', steps: [{check: {kind: 'manual'}}]}]}),
    _tourStep: () => null,
    _tourSaveState: () => {saves.push(JSON.parse(JSON.stringify(ctx._tourState)));},
    startTutorial: id => {calls.push('in-place ' + id); return true;},
    switchToBranch: branch => {calls.push('switch ' + branch);},
    gdToast: message => {calls.push('toast ' + message);},
    _tourEnd: async () => {calls.push('cleanup');},
    location: {search: '', hash: ''},
    authFetch: async (url, options) => {
      if (!options?.method) return {ok: true, json: async () => ({branches: [{id: 'main-id', name: 'main'}]})};
      const command = JSON.parse(options.body);
      assert.equal(saves.at(-1).created[0].id, command.id, 'persist UUID before POST can commit');
      assert.equal(saves.at(-1).created[0]['base-branch-id'], command['base-branch-id']);
      if (outcome === 'reject') return {ok: false, status: 409, json: async () => ({ok: false})};
      created = {id: command.id, name: command.name, 'base-branch-id': command['base-branch-id']};
      if (outcome === 'lost') throw new Error('Lost reply after commit');
      if (outcome === 'principal') ctx.gdAccount = {id: 'different'};
      return {ok: true, status: 200, json: async () => ({ok: true, branch: created})};
    },
  });
  ctx.window = ctx;
  for (const name of ['editor-tour-session.js', 'editor-tour-receipts.js']) {
    vm.runInContext(fs.readFileSync(path.join(editor, name), 'utf8'), ctx);
  }
  return {ctx, calls, saves, created: () => created};
}
(async () => {
  const successful = boot('success');
  assert.equal(await successful.ctx.startTutorialIsolated('32'), true);
  assert.equal(successful.ctx._tourState.sandboxBranchId, successful.created().id);
  assert.equal(successful.ctx._tourState.created[0].receipt, 'created');
  assert.equal(successful.ctx._tourState.activeBranch, successful.created().name);
  assert.ok(successful.calls.includes('switch ' + successful.created().name));
  for (const outcome of ['lost', 'principal']) {
    const t = boot(outcome);
    assert.equal(await t.ctx.startTutorialIsolated('32'), false);
    assert.equal(t.ctx._tourState.created[0].id, t.created().id);
    assert.equal(t.ctx._tourState.created[0].receipt, 'pending');
    assert.equal(t.ctx._tourState.cleanupBranch, 'main');
    assert.equal(t.ctx._tourState.principal.accountId, 'account', 'retain original principal provenance');
    assert.ok(!t.calls.some(call => call.startsWith('in-place ') || call.startsWith('switch ')),
      'an ambiguous committed branch cannot disappear behind in-place fallback');
    if (outcome === 'lost') assert.ok(t.calls.includes('cleanup'), 'offer exact pending cleanup');
  }
  const rejected = boot('reject');
  assert.equal(await rejected.ctx.startTutorialIsolated('32'), true);
  assert.equal(rejected.created(), null);
  assert.equal(rejected.ctx._tourState.created.length, 0, 'rejected identity conveys no ownership');
  assert.ok(rejected.calls.includes('in-place 32'), 'definite rejection preserves the old fallback');
  console.log('✓ tour-sandbox-receipts: create-only UUID, lost commit and principal recovery');
})().catch(error => {console.error(error); process.exitCode = 1;});
