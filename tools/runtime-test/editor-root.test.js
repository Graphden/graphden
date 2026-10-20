// Root lifetime regressions: old account/branch replies cannot activate an
// adapter, and source denials release the prior bootstrap receipt.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-root.js'), 'utf8');
function fixture() {
  const listeners = new Map(), requests = [], executed = [], nodes = [];
  const legacy = {dataset: {}, before(node) { nodes.push(node); }};
  const window = {
    gdPrefOwner: 'first', API: {api_ui_root_plan: '/api/ui/root/plan'},
    authFetch(url, options) { return new Promise(resolve => requests.push({url, options, resolve})); },
    addEventListener(name, callback) { listeners.set(name, callback); }, dispatchEvent() {},
    GraphdenBrowser: {keyword: value => value, isCallable: value => typeof value === 'function',
      invoke: (callback, input) => callback(input), createRuntime: plan => ({run: () => { executed.push(plan.label); return plan.output; }})},
  };
  const document = {
    readyState: 'loading', addEventListener() {}, getElementById: id => id === 'app' ? legacy : null,
    createElement: () => ({remove() {}, setAttribute() {}}),
  };
  const context = {window, document, Map, AbortController, DOMException, Event: class {}, CustomEvent: class {}, graphdenCurrentOrg: 'org',
    getCurrentBranchName: () => context.branch, branch: 'main'};
  vm.runInNewContext(source, context);
  const reply = (index, legacyMode, label) => requests[index].resolve({ok: true,
    json: async () => ({ok: true, 'argument-name': null,
      plan: {label, output: new Map([['legacy', legacyMode]])}})});
  return {window, requests, executed, legacy, listeners, reply, context};
}
(async () => {
  const f = fixture();
  const first = f.window.gdEditorRoot.reload();
  f.window.gdPrefOwner = 'second';
  const second = f.window.gdEditorRoot.reload();
  assert(f.requests[0].options.signal.aborted);
  f.reply(0, true, 'old account');
  f.reply(1, true, 'current account');
  await Promise.all([first, second]);
  assert.deepEqual(f.executed, ['current account']);
  assert.equal(f.legacy.dataset.gdRootAdapter, 'legacy');

  const pending = f.window.gdEditorRoot.reload();
  assert.equal(f.legacy.dataset.gdRootAdapter, undefined);
  f.context.branch = 'other';
  f.reply(2, false, 'stale branch');
  await pending;
  assert(!f.executed.includes('stale branch'));

  const denied = f.window.gdEditorRoot.reload();
  f.requests[3].resolve({ok: false, json: async () => ({ok: false})});
  await denied;
  assert.equal(f.legacy.dataset.gdRootAdapter, undefined);
  assert(!f.executed.includes('stale branch'));

  const legacy = f.window.gdEditorRoot.reload();
  f.reply(4, true, null);
  await legacy;
  assert.equal(f.legacy.dataset.gdRootAdapter, 'legacy');
  const callbacks = new Map([['initial', () => 'initial'], ['update', input => input.get('state')], ['view', () => 'replacement view']]);
  const components = f.window.gdEditorRoot.reload();
  f.requests[5].resolve({ok: true, json: async () => ({ok: true, 'argument-name': null,
    'component-selection-id': '00000000-0000-0000-0000-000000000001',
    plan: {label: 'components', output: new Map([['legacy', true], ['account-menu', callbacks], ['recents', callbacks]])}})});
  await components;
  const selected = await f.window.gdEditorRoot.component('recents');
  assert.equal(selected.run('view'), 'replacement view', 'the root field selects the rendered behavior');
  assert.equal(selected.run('update', {state: 'current'}), 'current');
  const root = f.window.gdEditorRoot;
  const selection = '00000000-0000-0000-0000-000000000001';
  assert.equal(root.componentIdentity('recents'), null, 'export alone does not claim host readiness');
  assert.equal(root.acceptComponent({run() {}}), false, 'preview runtimes cannot claim the installed selection');
  assert.equal(root.acceptComponent(selected), true);
  assert.equal(root.componentIdentity('recents'), selection);
  const replacement = root.componentNow('recents');
  root.acceptComponent(replacement);
  root.releaseComponent(selected);
  assert.equal(root.componentIdentity('recents'), selection, 'stale release cannot retire a newer host receipt');
  root.releaseComponent(replacement);
  assert.equal(root.componentIdentity('recents'), null, 'failed/disposed host releases its receipt');
  root.acceptComponent(selected);
  const reload = root.reload();
  assert.equal(root.componentIdentity('recents'), null, 'same-scope reload clears readiness immediately');
  assert.equal(root.acceptComponent(selected), false, 'old facade cannot accept during reload');
  const secondSelection = '00000000-0000-0000-0000-000000000002';
  const componentsReply = selection => ({ok: true, 'argument-name': null, 'component-selection-id': selection,
    plan: {output: new Map([['legacy', true], ['recents', callbacks]])}});
  f.requests[6].resolve({ok: true, json: async () => componentsReply(secondSelection)});
  await reload;
  assert.equal(root.acceptComponent(selected), false, 'old facade cannot accept after a new root');
  root.acceptComponent(root.componentNow('recents'));
  root.releaseComponent(selected);
  assert.equal(root.componentIdentity('recents'), secondSelection, 'stale A cannot replace or release installed B');
  const custom = root.reload();
  f.requests[7].resolve({ok: true, json: async () => componentsReply(null)});
  await custom;
  assert(root.acceptComponent(root.componentNow('recents')));
  assert.equal(root.componentIdentity('recents'), null, 'custom root has no selection witness even with a valid ready host');
  f.window.gdPrefOwner = 'third';
  assert.throws(() => selected.run('view'), /Editor root changed/);
  f.window.gdEditorRoot.dispose();
})().catch(error => { console.error(error); process.exitCode = 1; });
