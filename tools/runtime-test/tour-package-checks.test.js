// Package lesson checks use exact receipts and current branch-scoped state.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const editor = path.join(__dirname, '../../resources/packages/app/editor');
const scripts = ['editor-tour-receipts.js', 'editor-tour-checks.js']
  .map(name => fs.readFileSync(path.join(editor, name), 'utf8')).join('\n');
const pin = {id: 'pin', 'package-name': 'greetings', version: '1.0.0', 'branch-id': 'site-id'};
function boot() {
  const principal = {accountId: 'account', orgId: 'org'};
  let branch = 'site';
  const dom = new Map();
  const requests = [];
  let rows = [pin];
  const owner = {id: 'owner', name: 'welcome'};
  const foreign = {id: 'foreign', name: 'welcome', 'parent-ids': ['const']};
  const ctx = vm.createContext({console, window: {location: {search: '?branch=site'}}, URLSearchParams,
    _tourState: {lessonId: '41', step: 12, principal: {...principal}, created: [
      {type: 'fn', id: owner.id, name: owner.name, receipt: 'created'},
      {type: 'package-install', name: 'greetings', version: '1.0.0', id: pin.id,
        'branch-id': 'site-id', 'branch-name': 'site', receipt: 'created'},
      {type: 'ns', id: 'materialized', name: 'greetings@1-0-0', materialized: true,
        'package-name': 'greetings', version: '1.0.0', receipt: 'created'},
    ]},
    _tourSessionBranch: () => branch,
    _tourPrincipalMatches: state => state.principal.accountId === principal.accountId
      && state.principal.orgId === principal.orgId,
    API: {api_packages_installed: '/api/packages/installed'},
    authFetch: async (url, options) => {
      requests.push({url, options});
      return {ok: true, json: async () => rows};
    },
    graphData: {namespaces: [{id: 'materialized', name: 'greetings@1-0-0'}]},
    selectedFnId: owner.id,
    lookups: {fnMap: new Map([[foreign.id, foreign], [owner.id, owner], ['const', {id: 'const', name: 'const'}],
        ['installed', {id: 'installed', name: 'greet', 'namespace-id': 'materialized'}],
        ['same-name', {id: 'same-name', name: 'greet', 'namespace-id': 'foreign-ns'}]]),
      slotMap: new Map([['value', {id: 'value', name: 'value'}]]),
      bindingsByFn: new Map([['owner', [{id: 'binding', 'fn-id': 'owner', 'slot-id': 'value', 'ref-fn-id': 'installed'}]]])},
    document: {addEventListener() {},
      querySelector: selector => dom.get(selector) || null,
      querySelectorAll: selector => dom.has(selector)
        ? [{getBoundingClientRect: () => ({width: 10, height: 10})}] : []},
  });
  vm.runInContext(scripts, ctx);
  return {ctx, principal, dom, requests, setRows: value => {rows = value;},
    setBranch: value => {branch = value;},
    resetPin: () => vm.runInContext('_tourPinCheck = null', ctx),
    flush: async () => {for (let i = 0; i < 15; i++) await Promise.resolve();}};
}
(async () => {
  const check = {kind: 'package-pin', name: 'greetings', version: '1.0.0'};
  const t = boot();
  assert.equal(t.ctx._tourCheckPasses(check), false, 'saved receipt alone is not a live pin');
  await t.flush();
  assert.equal(t.ctx._tourCheckPasses(check), true);
  assert.equal(t.requests[0].options.headers['X-Graphden-Branch'], 'site-id');
  for (const changed of [{...pin, id: 'replacement'}, {...pin, version: '1.0.1'}, {...pin, 'branch-id': 'other'}]) {
    t.setRows([changed]); t.resetPin();
    assert.equal(t.ctx._tourCheckPasses(check), false);
    await t.flush();
    assert.equal(t.ctx._tourCheckPasses(check), false, 'different UUID/version/branch cannot pass');
  }
  t.setRows([pin]); t.resetPin();
  t.ctx._tourCheckPasses(check);
  t.principal.accountId = 'different';
  await t.flush();
  assert.equal(t.ctx._tourCheckPasses(check), false, 'late read cannot authorize a changed principal');
  t.principal.accountId = 'account'; t.setBranch('elsewhere');
  assert.equal(t.ctx._tourCheckPasses(check), false, 'saved other-branch pin cannot pass');

  const r = boot();
  const reference = {kind: 'package-reference', name: 'greetings', version: '1.0.0',
    owner: 'welcome', slot: 'value', fn: 'greet'};
  assert.equal(r.ctx._tourCheckPasses(reference), true);
  r.ctx.lookups.bindingsByFn.get('owner')[0]['ref-fn-id'] = 'same-name';
  assert.equal(r.ctx._tourCheckPasses(reference), false, 'same name in another namespace is not the version reference');
  r.ctx.lookups.bindingsByFn.get('owner')[0]['ref-fn-id'] = 'installed';
  r.ctx.lookups.bindingsByFn.get('owner')[0]['fn-id'] = 'foreign';
  assert.equal(r.ctx._tourCheckPasses(reference), false, 'inherited/foreign binding is not the owning use site');
  assert.equal(r.ctx._tourCheckPasses({kind: 'fn-parent', name: 'welcome', parent: 'const'}), true,
    'legacy checks keep their ordinary name behavior');
  assert.equal(r.ctx._tourCheckPasses({kind: 'fn-parent', name: 'welcome', parent: 'const', 'owned?': true}), false,
    'owned check cannot use another same-name graph');
  r.ctx.lookups.fnMap.get('owner')['parent-ids'] = ['const'];
  assert.equal(r.ctx._tourCheckPasses({kind: 'fn-parent', name: 'welcome', parent: 'const', 'owned?': true}), true);

  const run = boot();
  const host = {gdExecutionFnId: 'foreign'};
  run.dom.set('.execute-popover.visible .execute-result-pane', {});
  run.dom.set('.execute-popover.visible .execute-result-host', host);
  run.dom.set('.execute-popover.visible .execute-result-host .execute-result-scalar', {textContent: '1'});
  const result = {kind: 'package-run', name: 'welcome', value: '1'};
  assert.equal(run.ctx._tourCheckPasses(result), false, 'another graph’s same value cannot pass');
  host.gdExecutionFnId = 'owner';
  assert.equal(run.ctx._tourCheckPasses(result), true);
  delete host.gdExecutionFnId;
  assert.equal(run.ctx._tourCheckPasses(result), false, 'cleared pending generation cannot reuse its prior result');
  host.gdExecutionFnId = 'owner';
  run.dom.delete('.execute-popover.visible .execute-result-pane');
  assert.equal(run.ctx._tourCheckPasses(result), false, 'history/hidden results cannot pass the current run check');
  console.log('✓ tour-package-checks: live pin, owning ref UUID and current run receipt');
})().catch(error => {console.error(error); process.exitCode = 1;});
