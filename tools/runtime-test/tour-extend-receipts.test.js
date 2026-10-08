'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const dir = path.join(__dirname, '../../resources/packages/app/editor');

function setup(outcome = 'success') {
  const active = {accountId: 'owner', orgId: 'org'};
  let popover;
  const ctx = vm.createContext({console,
    window: {}, document: {createElement: () => ({}), addEventListener: () => {}},
    _tourState: {lessonId: '14', principal: {...active}, created: []},
    _tourStep: () => ({creates: {type: 'fn', name: 'handler'}}),
    _tourPrincipalMatches: state => state?.principal?.accountId === active.accountId
      && state?.principal?.orgId === active.orgId,
    _tourSaveState: () => {},
    openInlineEditPopover: config => { popover = config; },
    postEntity: async (type, fields) => {
      assert.equal(type, 'fn');
      assert.equal(fields.name, 'handler');
      assert.equal(fields['parent-ids'], 'parent-id');
      const pending = ctx._tourState.created.at(-1);
      assert.equal(pending.receipt, 'pending', 'ownership is persisted before the mutation');
      assert.equal(pending['namespace-id'], 'chosen-namespace');
      assert.equal(pending.id, undefined, 'a proposed name is not an identity');
      if (outcome === 'lost') throw new Error('transport failed');
      if (outcome === 'switch-user') active.accountId = 'other';
      return {status: outcome === 'refused' ? 403 : 201, ok: outcome !== 'refused',
        headers: {get: () => outcome === 'no-header' ? null : 'actual-created-id'}};
    },
    gdRememberLastNs: () => {
      if (outcome === 'success') {
        assert.equal(ctx._tourState.created.at(-1).id, 'actual-created-id',
          'the exact receipt exists before navigation or editor refresh');
      }
    },
  });
  for (const filename of ['editor-tour-receipts.js', 'editor-edit-modes-fn.js']) {
    vm.runInContext(fs.readFileSync(path.join(dir, filename), 'utf8'), ctx);
  }
  vm.runInContext("buildNsChooser = () => ({sub: {}, row: {}, resolve: async () => ({ok: true, nsId: 'chosen-namespace'})})", ctx);
  ctx.enterExtendEditMode({id: 'parent-id', name: 'html-response', 'namespace-id': 'source-namespace'}, {});
  popover.makeControl({insertBefore: () => {}, firstChild: null});
  return {ctx, save: () => popover.doSave({value: 'handler'})};
}

(async () => {
  const success = setup();
  assert.equal(await success.save(), true);
  assert.equal(success.ctx._tourState.created.at(-1).receipt, 'created');
  assert.equal(success.ctx._tourState.created.at(-1).id, 'actual-created-id');
  for (const outcome of ['lost', 'refused', 'switch-user', 'no-header']) {
    const test = setup(outcome);
    assert.equal(await test.save(), !['lost', 'refused'].includes(outcome));
    assert.equal(test.ctx._tourState.created.at(-1).receipt, 'pending', outcome + ' cannot confer cleanup ownership');
    assert.equal(test.ctx._tourState.created.at(-1).id, undefined);
  }
  console.log('PASS: Extend records actual creation identity before refresh; ambiguous, refused and changed-principal replies remain unconfirmed');
})().catch(error => { console.error(error); process.exitCode = 1; });
