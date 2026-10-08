// Actual creation responses, persisted pending attempts, and principal changes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-tour-receipts.js'), 'utf8');
function context() {
  let saves = 0;
  const principal = {accountId: 'account', orgId: 'org'};
  const ctx = vm.createContext({console, window: {},
    _tourState: {lessonId: '41', step: 0, principal, created: [
      {type: 'branch', id: 'site-id', name: 'site'},
      {type: 'ns', id: 'source-id', name: 'source', 'parent-id': null, receipt: 'created'},
    ]},
    _tourStep: () => ({creates: {type: 'package-version', name: 'greetings', version: '1.0.0'}}),
    _tourSessionBranch: () => 'site',
    _tourSaveState: () => { saves++; },
    _tourPrincipalMatches: saved => saved.principal.accountId === principal.accountId
      && saved.principal.orgId === principal.orgId,
  });
  vm.runInContext(source, ctx);
  return {ctx, principal, saves: () => saves};
}
const release = {ok: true, id: 'release-id', name: 'greetings', version: '1.0.0', 'content-hash': 'hash'};
const installed = {ok: true, name: 'greetings', version: '1.0.0', 'package-version-id': 'release-id',
  'content-hash': 'hash', pin: {id: 'pin-id', 'package-name': 'greetings', version: '1.0.0', 'branch-id': 'site-id'},
  'created-namespaces': [{id: 'materialized-id', name: 'source@1-0-0', 'parent-id': null}]};
{
  const {ctx, saves} = context();
  const pending = ctx.gdTourBeginPackagePublish({name: 'greetings', version: '1.0.0', 'ns-root': 'source'});
  assert.equal(ctx._tourState.created.at(-1).receipt, 'pending');
  assert.ok(saves() > 0, 'persist before publication; reload cannot lose an unknown result');
  assert.equal(ctx.gdTourRecordPackagePublish(pending, {...release, id: null}), false);
  assert.equal(ctx.gdTourRecordPackagePublish(pending, {...release, version: '1.0.1'}), false);
  assert.equal(ctx._tourState.created.at(-1).receipt, 'pending', 'do not infer a successful creation by name');
  assert.equal(ctx.gdTourRecordPackagePublish(pending, release), true);
  assert.equal(ctx._tourState.created.at(-1)['source-namespace-id'], 'source-id');
  const ticket = ctx.gdTourBeginPackageInstall({name: 'greetings', version: '1.0.0'});
  assert.equal(ctx.gdTourRecordPackageInstall(ticket, {...installed, pin: {...installed.pin, 'branch-id': 'another'}}), false);
  assert.equal(ctx.gdTourRecordPackageInstall(ticket, {...installed, 'content-hash': 'different'}), false);
  assert.equal(ctx.gdTourRecordPackageInstall(ticket, installed), true);
  assert.equal(ctx._tourState.created.find(row => row.type === 'package-install').id, 'pin-id');
  assert.equal(ctx._tourState.created.find(row => row.materialized).id, 'materialized-id');
  const retry = ctx.gdTourBeginPackageInstall({name: 'greetings', version: '1.0.0'});
  assert.equal(ctx.gdTourRecordPackageInstall(retry, {...installed, 'created-namespaces': []}), true);
  assert.equal(ctx._tourState.created.filter(row => row.type === 'package-install').length, 1);
  assert.equal(ctx._tourState.created.filter(row => row.materialized).length, 1, 'retry never claims existing namespaces');
}
{
  const {ctx, principal} = context();
  assert.equal(ctx.gdTourBeginPackagePublish({name: 'greetings', version: '1.0.0', 'ns-root': 'existing'}), null);
  const ticket = ctx.gdTourBeginPackagePublish({name: 'greetings', version: '1.0.0', 'ns-root': 'source'});
  const before = JSON.stringify(ctx._tourState.created);
  principal.accountId = 'other';
  assert.equal(ctx.gdTourRecordPackagePublish(ticket, release), false);
  assert.equal(JSON.stringify(ctx._tourState.created), before, 'logout/principal change retains the original whole ledger');
}
console.log('✓ tour-receipts: exact success, lost response, retry and principal ownership');
(async () => {
  const {ctx} = context();
  ctx.window.crypto = {randomUUID: () => '00000000-0000-4000-8000-000000000041'};
  ctx.API = {api_branches: '/api/branches'};
  ctx._tourStep = () => ({creates: {type: 'branch', name: 'tutorial-site'}});
  ctx.authFetch = async () => ({ok: true, json: async () => ({branches: [{id: 'main-id', name: 'main'}]})});
  const ticket = await ctx.gdTourBeginBranchCreation('tutorial-site', 'main');
  const pending = ctx._tourState.created.at(-1);
  assert.equal(ticket.branchId, pending.id, 'persist the create-only UUID before the branch POST');
  assert.equal(ticket.baseBranchId, 'main-id', 'capture actual base UUID, not a later name lookup');
  assert.equal(pending.receipt, 'pending');
  ctx.gdTourRejectBranchCreation(ticket, {status: 500, ok: false}, {error: 'unknown'});
  assert.equal(ctx._tourState.created.at(-1), pending, 'ambiguous postcommit/transport failure remains recoverable');
  assert.equal(ctx.gdTourRecordBranchReceipt(ticket,
    {id: ticket.branchId, name: 'tutorial-site', 'base-branch-id': 'other'}), false);
  assert.equal(ctx.gdTourRecordBranchReceipt(ticket,
    {id: 'another', name: 'tutorial-site', 'base-branch-id': 'main-id'}), false);
  assert.equal(ctx.gdTourRecordBranchReceipt(ticket,
    {id: ticket.branchId, name: 'tutorial-site', 'base-branch-id': 'main-id'}), true);
  assert.equal(pending.receipt, 'created');
  const rejected = await ctx.gdTourBeginBranchCreation('tutorial-site', 'main');
  ctx.gdTourRejectBranchCreation(rejected, {status: 409, ok: false}, {ok: false});
  assert.equal(ctx._tourState.created.some(row => row.token === rejected.token), false,
    'a definite create-only refusal removes only the rejected attempt');
  assert.equal(ctx._tourState.created.includes(pending), true, 'confirmed older ownership survives rejection');
  console.log('✓ tour-receipts: proposed branch UUID, captured base and definite rejection');
})().catch(error => {console.error(error); process.exitCode = 1;});
