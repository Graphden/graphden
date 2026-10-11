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
const requests = [];
const mountedRuns = [];
const disposed = [];
const ctx = vm.createContext({console, Map, Set, Promise, AbortController, document: doc,
  lookups: {fnMap: new Map([['A', {id: 'A', name: 'a'}], ['B', {id: 'B', name: 'b'}]])},
  fetch(url, options) { return new Promise((resolve, reject) => requests.push({url, options, resolve, reject})); },
  isAuthenticated: () => true,
  gdMountRunPane(id, options) { mountedRuns.push({id, options}); },
  gdCode: {destroyWithin(body) { disposed.push([...body.children]); }},
});
ctx.window = ctx;
installInspectorGraph(ctx, doc);
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-inspector.js'), 'utf8'), ctx);
assert.equal(ctx.gdInspectorGraph.isOpen(), false);
assert.ok(doc.getElementById('gd-insp-chrome-host'), 'empty Inspector mounts before selection and preference activation');
assert.equal(ctx.GraphdenRenderer.ownsTarget(doc.getElementById('gd-insp-tabbody')), false);
const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };
const reply = (request, text) => request.resolve({ok: true, text: () => Promise.resolve(text)});
const click = tab => doc.getElementById('gd-insp-chrome-host').dispatch('click', {target: doc.querySelector('[data-insp-tab="' + tab + '"]')});
(async () => {
  ctx.gdInspectorRender('A'); const firstA = requests.at(-1);
  ctx.gdInspectorRender('B'); const firstB = requests.at(-1);
  ctx.gdInspectorRender('A'); const secondA = requests.at(-1);
  const body = doc.getElementById('gd-insp-tabbody');
  const chrome = doc.getElementById('gd-insp-chrome-host');
  assert.equal(ctx.GraphdenRenderer.ownsTarget(body), false);
  assert.equal(ctx.GraphdenRenderer.ownsTarget(chrome), true);
  assert.equal(firstA.options.signal.aborted, true);
  reply(secondA, '<p>current A</p>'); await flush();
  reply(firstA, '<p>stale A</p>'); firstB.reject(new Error('old B')); await flush();
  assert.equal(doc.getElementById('gd-insp-overview').innerHTML, '<p>current A</p>');
  click('bindings'); const oldBindings = requests.at(-1);
  click('overview'); click('bindings'); const newBindings = requests.at(-1);
  reply(newBindings, '<p>new bindings</p>'); await flush();
  reply(oldBindings, '<p>old bindings</p>'); await flush();
  assert.equal(doc.getElementById('gd-insp-detail').innerHTML, '<p>new bindings</p>');
  const priorChildren = [...body.children]; const priorRequests = requests.length;
  ctx.gdInspectorShowRuns('A', {preselectOnly: true});
  assert.deepEqual(body.children, priorChildren);
  assert.equal(requests.length, priorRequests);
  ctx.gdInspectorRender('A');
  assert.equal(mountedRuns.at(-1).options.autofocus, true);
  click('bindings');
  chrome.dispatch('keydown', {target: doc.querySelector('[data-insp-tab="bindings"]'), key: 'ArrowRight'});
  assert.equal(mountedRuns.at(-1).options.autofocus, false);
  assert.equal(doc.activeElement.id, 'gd-insp-tab-stats');
  assert.equal(body.getAttribute('aria-labelledby'), 'gd-insp-tab-stats');
  const activeRun = mountedRuns.at(-1);
  const nativeBeforeBadPlan = [...body.children];
  let refusals = 0;
  ctx.gdUIComponentFailed = () => { refusals++; };
  const builtin = ctx.GraphdenBrowser.createRuntime(ctx.GraphdenBuiltinPlans.plans.inspector);
  const key = ctx.GraphdenBrowser.keyword;
  for (const alter of [
    view => view.set(key('tab'), 'arbitrary-tab'),
    view => view.get(key('tree'))[2].push([key('div'), new Map([[key('id'), 'gd-insp-tabbody']])]),
    view => view.get(key('tree'))[2].push([key('div'), new Map([[key('id'), 'gd-insp-tab-overview']])]),
  ]) {
    ctx.gdLoadUIComponentRuntime = async () => ({run(entry, supplied) {
      const value = builtin.run(entry, supplied);
      if (entry === 'view') alter(value);
      return value;
    }});
    await ctx.gdInspectorGraph.reload();
    assert.deepEqual(body.children, nativeBeforeBadPlan, 'invalid personal chrome cannot replace native controls');
    assert.equal(doc.getElementById('gd-insp-tabbody'), body, 'native host cannot be shadowed by graph IDs');
  }
  assert.equal(refusals, 3);
  assert.equal(activeRun.options.isCurrent(), true);
  ctx.inspectorNarrow.matches = true;
  ctx.gdInspectorGraph.close();
  assert.equal(activeRun.options.isCurrent(), true, 'closing a sheet retains its Run form owner');
  assert.equal(activeRun.options.canFocus(), false, 'close invalidates the captured focus intent');
  assert.equal(doc.body.classList.contains('gd-insp-open'), false);
  ctx.inspectorNarrow.matches = false; ctx.inspectorNarrow.change();
  assert.equal(ctx.gdInspectorGraph.isOpen(), true, 'wide column remains visible after closing the narrow sheet');
  assert.equal(activeRun.options.isCurrent(), true, 'wide column retains the exact Run owner');
  assert.equal(activeRun.options.canFocus(), false, 'an old autofocus cannot resume after close');
  assert.deepEqual(body.children, nativeBeforeBadPlan, 'viewport resume must not remount controls');
  const runsHost = doc.getElementById('gd-insp-runs');
  const readOne = activeRun.options.captureRead(runsHost, () => {});
  const readTwo = activeRun.options.captureRead(runsHost, () => {});
  assert.equal(readOne.current(), false, 'new history read invalidates its older sibling');
  assert.equal(readOne.signal.aborted, true);
  assert.equal(readTwo.current(), true);
  readOne.finish();
  assert.equal(readTwo.current(), true, 'old completion cannot remove the newer read');
  readTwo.finish();
  chrome.dispatch('keydown', {target: doc.querySelector('[data-insp-tab="stats"]'), key: 'ArrowLeft'});
  assert.equal(doc.activeElement.id, 'gd-insp-tab-bindings', 'wide keyboard focus resumes without a new selection');
  click('overview');
  const interrupted = requests.at(-1);
  const interruptedHost = doc.getElementById('gd-insp-overview');
  const disposedBeforeClose = disposed.length;
  ctx.inspectorNarrow.matches = true;
  ctx.gdInspectorGraph.close();
  assert.equal(interrupted.options.signal.aborted, true);
  ctx.inspectorNarrow.matches = false; ctx.inspectorNarrow.change();
  const resumed = requests.at(-1);
  assert.notEqual(resumed, interrupted, 'wide viewport resumes the interrupted read');
  assert.equal(doc.getElementById('gd-insp-overview'), interruptedHost);
  assert.equal(disposed.length, disposedBeforeClose, 'resume does not dispose native controls');
  const resumedCount = requests.length;
  ctx.inspectorNarrow.change();
  assert.equal(requests.length, resumedCount, 'repeated viewport events do not duplicate an active read');
  reply(resumed, '<p>resumed overview</p>'); await flush();
  reply(interrupted, '<p>interrupted overview</p>'); await flush();
  assert.equal(interruptedHost.innerHTML, '<p>resumed overview</p>');
  ctx.gdInspectorRender('A');
  const beforeAuth = requests.at(-1);
  ctx.gdPrefOwner = 'other-account';
  ctx.dispatchEvent({type: 'gd-auth-changed'});
  assert.equal(beforeAuth.options.signal.aborted, true);
  assert.equal(inspector.dataset.fnId, '');
  assert.equal(body.children.length, 0);
  assert.ok(disposed.some(children => children.length > 0));
  console.log('Inspector graph: sibling ownership, A→B→A/tab generations, preset, roving focus and close PASS');
})().catch(error => { console.error(error); process.exitCode = 1; });
