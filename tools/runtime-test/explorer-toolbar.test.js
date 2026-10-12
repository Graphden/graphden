'use strict';
// Actual ordinary Explorer plan and fixed host effects; no entity-count limit
// or second interpretation of saved graph-view bindings is introduced.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const {installInspectorGraph} = require('./inspector-graph-fixture');
function fixture() {
  const document = createDocument();
  for (const id of ['kind-filters', 'secret-add-btn', 'failed-ack-all-btn', 'tests-run-all-btn']) {
    const el = document.createElement(id === 'kind-filters' ? 'div' : 'button');
    el.id = id; document.body.appendChild(el);
  }
  let authenticated = true;
  let failed = 2;
  let count = 12000;
  const calls = [];
  const loading = [];
  const rejected = [];
  const failures = [];
  const context = vm.createContext({document, console, Map, Set, Promise, AbortController,
    lensKinds: new Set(), treeDetails: {fx: false}, API: {api_orgs_apps: '/apps'},
    isAuthenticated: () => authenticated,
    getAllServiceFnIdCount: () => count, getAppRouteCount: () => 7,
    getTestStatusCount: () => count, getTestFailedCount: () => failed,
    getFailureTotal: () => count, getTypeErrorTotal: () => 9, getLintTotal: () => 6,
    toggleKind(kind) { calls.push(['kind', kind]); context.lensKinds.has(kind) ? context.lensKinds.delete(kind) : context.lensKinds.add(kind); context.gdExplorerGraph.refresh(); },
    toggleTreeDetail(kind) { calls.push(['detail', kind]); context.treeDetails[kind] = !context.treeDetails[kind]; context.gdExplorerGraph.refresh(); },
    gdLoadUIComponentRuntime() { return new Promise((resolve, reject) => { loading.push(resolve); rejected.push(reject); }); },
    gdUIComponentFailed(component) { failures.push(component); },
  });
  context.window = context;
  installInspectorGraph(context, document);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-explorer-graph.js'), 'utf8'), context);
  context.gdExplorerGraph.refresh();
  const host = document.getElementById('kind-filters');
  const button = kind => host.querySelectorAll('button').find(el => el.dataset.kind === kind || el.dataset.detail === kind);
  return {context, document, host, button, calls, loading, rejected, failures,
    auth(value) { authenticated = value; context.dispatchEvent({type: 'gd-auth-changed'}); },
    failed(value) { failed = value; context.gdExplorerGraph.refresh(); },
    count(value) { count = value; context.gdExplorerGraph.refresh(); },
  };
}
(async () => {
  const f = fixture();
  assert.equal(f.host.querySelectorAll('button').length, 11);
  assert.equal(f.button('all').getAttribute('aria-pressed'), 'true');
  assert.equal(f.button('tests').querySelector('.kind-count').textContent, '12000 · 2✗');
  assert.equal(f.button('tests').getAttribute('title'), 'Only tests (fns in a `tests` namespace) — 2 failed');
  f.host.dispatch('click', {target: f.button('tests')});
  assert.deepEqual(f.calls, [['kind', 'tests']]);
  assert.equal(f.button('all').getAttribute('aria-pressed'), 'false');
  assert.equal(f.document.getElementById('tests-run-all-btn').hidden, false);
  const pending = f.document.getElementById('tests-run-all-btn');
  pending.disabled = true; pending.textContent = 'Running…';
  f.context.lensKinds.clear(); f.context.gdExplorerGraph.refresh();
  assert.equal(pending.hidden, true);
  f.context.lensKinds.add('tests'); f.failed(0);
  assert.equal(f.document.getElementById('tests-run-all-btn'), pending);
  assert.equal(pending.disabled, true); assert.equal(pending.textContent, 'Running…');
  assert.equal(f.button('tests').querySelector('.kind-count').textContent, '12000');
  f.host.dispatch('click', {target: f.button('fx')});
  assert.equal(f.button('fx').getAttribute('aria-pressed'), 'true');
  assert.deepEqual(f.calls.at(-1), ['detail', 'fx']);
  f.count(0);
  assert.equal(pending.hidden, true);
  f.count(null);
  assert.equal(pending.hidden, true, 'unknown cache counts cannot advertise an available action');
  assert.equal(f.button('tests').querySelector('.kind-count').textContent, '');
  f.count(12000); f.auth(false);
  assert.equal(pending.hidden, true); assert.equal(pending.disabled, true);
  const before = f.host.children[0];
  f.loading[0]({run() { throw new Error('old account must not execute'); }});
  for (let i = 0; i < 8; i++) await Promise.resolve();
  assert.equal(f.host.children[0], before, 'auth-old personal plan is ignored');
  delete f.context.API.api_orgs_apps; f.context.gdExplorerGraph.refresh();
  assert.equal(f.button('apps').getAttribute('hidden'), 'true');
  const changed = fixture();
  changed.context.gdPrefOwner = 'other-owner';
  changed.rejected[0](new Error('old-context-refusal'));
  for (let i = 0; i < 8; i++) await Promise.resolve();
  assert.deepEqual(changed.failures, [], 'a late refusal from another context cannot clear current component identity');
  console.log('PASS Explorer toolbar projection, finite effects and owner guards');
})().catch(error => { console.error(error); process.exitCode = 1; });
