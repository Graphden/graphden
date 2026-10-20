// Root lifetime regressions: old account/branch replies cannot activate an
// adapter, and source denials release the prior bootstrap receipt.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('resources/packages/app/editor/editor-root.js', 'utf8');
function fixture() {
  const listeners = new Map(), requests = [], executed = [], nodes = [];
  const legacy = {dataset: {}, before(node) { nodes.push(node); }};
  const window = {
    gdPrefOwner: 'first', API: {api_ui_root_plan: '/api/ui/root/plan'},
    authFetch(url, options) { return new Promise(resolve => requests.push({url, options, resolve})); },
    addEventListener(name, callback) { listeners.set(name, callback); }, dispatchEvent() {},
    GraphdenBrowser: {keyword: value => value, createRuntime: plan => ({run: () => { executed.push(plan.label); return plan.output; }})},
  };
  const document = {
    readyState: 'loading', addEventListener() {}, getElementById: id => id === 'app' ? legacy : null,
    createElement: () => ({remove() {}, setAttribute() {}}),
  };
  const context = {window, document, Map, AbortController, CustomEvent: class {}, graphdenCurrentOrg: 'org',
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
  f.window.gdEditorRoot.dispose();
})().catch(error => { console.error(error); process.exitCode = 1; });
