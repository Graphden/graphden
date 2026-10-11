'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const {installInspectorGraph, installHtmlParser} = require('./inspector-graph-fixture');
const doc = createDocument();
installHtmlParser(doc);
const inspector = doc.createElement('div'); inspector.id = 'gd-inspector'; doc.body.appendChild(inspector);
const shells = [];
const histories = [];
const frames = [];
const forms = [];
const renderedForms = [];
const ctx = vm.createContext({console, Map, Set, Promise, AbortController, document: doc,
  lookups: {fnMap: new Map([['A', {id: 'A', name: 'a'}], ['B', {id: 'B', name: 'b'}]])},
  isAuthenticated: () => true,
  fetchValueForm: () => new Promise(resolve => forms.push(resolve)),
  fetch: () => new Promise(() => {}),
  authFetch: () => new Promise(resolve => shells.push(resolve)),
  renderValueForm(host, payload, options) {
    renderedForms.push({host, options});
    const input = doc.createElement('textarea'); input.value = payload.value; host.appendChild(input);
  },
  renderSubmitSpinner() { const el = doc.createElement('div'); el.className = 'loading'; return el; },
  buildHistoryPanel(_fn, _result, options) { return new Promise(resolve => histories.push({resolve, options})); },
  requestAnimationFrame(fn) { frames.push(fn); },
  setTimeout() {}, clearTimeout() {},
});
ctx.window = ctx;
installInspectorGraph(ctx, doc);
for (const file of ['editor-execute.js', 'editor-inspector.js']) {
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor', file), 'utf8'), ctx);
}
const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };
const shell = '<div class="execute-popover-body"><div class="execute-arg-form" data-slot-id="slot" data-slot-name="arg"></div></div>'
  + '<button class="execute-run-btn"></button><button class="execute-cancel-btn"></button>'
  + '<input class="execute-persist-checkbox"></input><div class="execute-result-host"></div>';
const panel = label => { const el = doc.createElement('div'); el.textContent = label; return el; };
(async () => {
  ctx.inspectorNarrow.matches = true;
  ctx.gdInspectorShowRuns('A');
  const runHost = doc.getElementById('gd-insp-run-host');
  const pane = runHost.children[0];
  ctx.gdInspectorGraph.close();
  shells.shift()({ok: true, text: async () => shell}); await flush();
  assert.equal(forms.length, 1, 'hidden owner may continue loading its argument form');
  forms.shift()({ok: true, value: 'initial'}); await flush();
  assert.equal(renderedForms[0].options.autofocus, false, 'Run argument forms leave focus to the guarded pane RAF');
  assert.equal(runHost.children[0], pane, 'hidden initial shell completes in its original owner');
  assert.equal(pane.classList.contains('visible'), true, 'hidden forms reach readiness');
  assert.equal(histories.length, 0, 'hidden history is registered for resume without fetching');
  const input = pane.querySelector('textarea'); input.value = 'retained draft';
  let focused = 0; input.focus = () => { focused++; };
  ctx.inspectorNarrow.matches = false; ctx.inspectorNarrow.change(); await flush();
  assert.equal(histories.length, 1, 'wide viewport starts exactly one interrupted history');
  const query = pane.querySelector.bind(pane);
  pane.querySelector = selector => selector.includes('input:not') ? input : query(selector);
  frames.shift()();
  assert.equal(focused, 0, 'pre-close autofocus stays invalid after wide resume');
  assert.equal(pane.querySelector('textarea'), input);
  assert.equal(input.value, 'retained draft');
  const runsHost = doc.getElementById('gd-insp-runs');
  histories[0].resolve(panel('initial history')); await flush();
  runsHost.dispatch('gd-refresh-runs', {});
  runsHost.dispatch('gd-refresh-runs', {});
  const old = histories[1]; const newest = histories[2];
  assert.equal(old.options.isCurrent(), false, 'second visible refresh supersedes the first');
  newest.resolve(panel('newest history')); await flush();
  const newestPanel = runsHost.children[0];
  old.resolve(panel('old history')); await flush();
  assert.equal(runsHost.children[0], newestPanel);
  assert.equal(newestPanel.textContent, 'newest history');
  ctx.inspectorNarrow.matches = true; ctx.gdInspectorGraph.close();
  runsHost.dispatch('gd-refresh-runs', {});
  assert.equal(histories.length, 3, 'hidden refresh retains a continuation without replacing loaded history');
  assert.equal(runsHost.children[0], newestPanel);
  ctx.inspectorNarrow.matches = false; ctx.inspectorNarrow.change(); await flush();
  assert.equal(histories.length, 4);
  assert.equal(runHost.children[0], pane);
  assert.equal(input.value, 'retained draft');
  ctx.gdInspectorRender('B');
  assert.equal(histories[3].options.isCurrent(), false, 'new selection invalidates the old body owner');
  histories[3].resolve(panel('old owner')); await flush();
  assert.equal(pane.isConnected, false);
  const branchPane = doc.getElementById('gd-insp-run-host').children[0];
  shells.shift()({ok: true, text: async () => shell}); await flush();
  const rendersBeforeReset = renderedForms.length;
  ctx.gdInspectorContextChanged();
  forms.shift()({ok: true, value: 'old branch form'}); await flush();
  assert.equal(renderedForms.length, rendersBeforeReset, 'detached value-form response cannot render or enhance controls');
  assert.equal(branchPane.isConnected, false, 'branch context reset disposes the previous Run body');
  assert.equal(doc.getElementById('gd-insp-tabbody').children.length, 0);
  ctx.gdInspectorShowRuns('A');
  const accountPane = doc.getElementById('gd-insp-run-host').children[0];
  ctx.gdPrefOwner = 'other-account'; ctx.dispatchEvent({type: 'gd-auth-changed'});
  shells.shift()({ok: true, text: async () => shell}); await flush();
  assert.equal(accountPane.isConnected, false, 'late initial form cannot revive an old account owner');
  assert.equal(doc.getElementById('gd-insp-tabbody').children.length, 0);
  console.log('Inspector Run owner: hidden shell, retained draft, read resume/latest-wins and focus epoch PASS');
})().catch(error => { console.error(error); process.exitCode = 1; });
