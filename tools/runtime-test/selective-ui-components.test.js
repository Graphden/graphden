'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-ui-component-catalog.js'), 'utf8');
const id = n => String(n).padStart(8, '0') + '-1111-1111-1111-111111111111';
const revision = 'a'.repeat(64);
const viewKeys = {'account-menu': 'menu-id', 'fn-picker': 'picker-id', recents: 'recents-id', inspector: 'inspector-id', explorer: 'explorer-id'};
function fixture(component = 'recents') {
  const buttons = new Map();
  for (const name of ['create', 'choose', 'reset', 'label', 'status']) buttons.set('#gd-ui-components-' + name,
    {dataset: {}, isConnected: true, addEventListener(_name, callback) { this.click = callback; }});
  const group = {dataset: {uiComponent: component, uiLabel: component}, isConnected: true,
    addEventListener(_name, callback) { this.click = callback; }};
  const root = {dataset: {}, querySelector: key => buttons.get(key), querySelectorAll: key =>
    key === '[data-ui-component]' ? [group] : key === 'button' ? [...buttons.values(), group] : []};
  const selection = {'fn-id': id(90), 'branch-id': id(9), branch: 'develop', org: 'public'};
  const state = {selection, revision, defaults: id(91), preference: {present: false, value: null}, custom: {}};
  const roots = {'configuration-id': id(90), [viewKeys[component]]: id(95)};
  const receipt = {id: id(2), created: true, functions: [id(2), id(3)], namespaces: [],
    'configuration-revision': 'b'.repeat(64), 'cohort-revision': 'c'.repeat(64), 'restore-revision': revision,
    'restore-id': id(1), 'entry-id': id(3),
    'function-receipts': [{id: id(2), name: 'editor', 'namespace-id': id(1)},
      {id: id(3), name: '_view', 'namespace-id': id(1)}],
    previous: {id: id(90), name: '_editor-prior', 'namespace-id': id(1), revision}};
  const full = {namespaces: [{id: id(1), name: 'personal', path: 'personal', 'parent-id': null}],
    functions: Array.from({length: 6}, (_, n) => ({id: id(n + 20), name: 'part-' + n, 'namespace-id': id(1)})),
    roots: {'configuration-id': id(20), 'theme-id': id(21), 'menu-id': id(22), 'menu-update-id': id(23), 'picker-id': id(24)}};
  const requests = [], stages = [], confirms = [], rejects = [], themes = [], selections = [], events = new Map();
  let applyResolve;
  let failApply = false;
  let preference = null;
  const window = {gdPrefOwner: 'alice', gdPrefsReady: true,
    API: {api_editor_configuration_prepare: '/prepare', api_editor_configuration_preview: '/preview',
      api_editor_configuration_apply: '/apply', api_ui_components_create_preview: '/full-preview', api_ui_components_create_apply: '/full-apply'},
    gdPrefRead: () => preference, gdUIComponentsSelection: () => selection, gdUIComponentsRoots: () => roots,
    gdShellSurface() {}, gdUIConfigurationState: () => state, gdUIComponentsStatus: () => '', gdPrefOnChange() {},
    addEventListener: (name, callback) => events.set(name, callback), gdRefreshUIConfiguration() {},
    gdCaptureUIConfiguration: async () => state,
    gdPrefWrite: async () => assert.fail('ordinary configuration never writes a selector preference'),
    gdTourStageEditorConfiguration: (request, staged) => { stages.push(JSON.parse(JSON.stringify(staged))); return {token: 'exact'}; },
    gdTourConfirmEditorConfiguration: (_ticket, result) => { confirms.push(result); return true; },
    gdTourRejectEditorConfiguration: ticket => rejects.push(ticket), gdUseThemeGraph: async theme => themes.push(theme),
    authFetch: async (route, options) => {
      const body = JSON.parse(options.body); requests.push({route, body});
      const response = (value, ok = true, status = ok ? 200 : 409) => ({ok, status, json: async () => value});
      if (route === '/prepare') return response({ok: true, created: false});
      if (route === '/preview') return response({ok: true, receipt, request: {operation: body.operation, command: body.command,
        nonce: id(99), scope: {'principal-id': 'alice', org: 'public', 'branch-id': id(9)}, 'expected-proposal': 'd'.repeat(64)}});
      if (route === '/full-preview') return response({ok: true, manifest: full,
        request: {owner: 'alice', org: 'public', 'branch-id': id(9)}});
      if (route === '/full-apply') return response({ok: true, committed: true, manifest: full});
      return new Promise(resolve => { applyResolve = () => resolve(failApply
        ? response({ok: false, code: 'stale-configuration', reason: 'Configuration changed.'}, false)
        : response({ok: true, committed: true, ...receipt})); });
    },
  };
  const context = vm.createContext({window, document: {getElementById: () => root}, AbortController,
    graphData: {namespaces: []}, getCurrentBranchName: () => 'develop',
    openNamespacePicker: ({onPick}) => onPick(null), loadGraphData: async () => {},
    ensureSubtreeFor: async () => true, lookups: {fnMap: new Map([[id(95), {id: id(95)}]])},
    selectFn: value => selections.push(value)});
  vm.runInContext(source, context); window.gdRenderUIComponentCatalog();
  return {window, group, root, receipt, full, requests, stages, confirms, rejects, themes, selections, events,
    create: buttons.get('#gd-ui-components-create'), apply: () => applyResolve(),
    conflict: () => { failApply = true; }, own: () => { state.custom[component] = true; window.gdRenderUIComponentCatalog(); },
    changePreference: value => { preference = value; }, status: () => buttons.get('#gd-ui-components-status').textContent};
}
async function until(predicate) {
  for (let n = 0; n < 30; n++) { if (predicate()) return; await new Promise(setImmediate); }
  assert.fail('component action did not settle');
}
for (const component of Object.keys(viewKeys)) test(component + ' inherits current roles without copying a template or choosing a theme', async () => {
  const f = fixture(component); f.group.click();
  await until(() => f.requests.some(row => row.route === '/apply'));
  assert.deepEqual(f.requests.map(row => row.route), ['/prepare', '/preview', '/apply']);
  assert.deepEqual(f.requests[0].body, {home: true}, 'setup creates no baseline editor function');
  assert.deepEqual(f.requests[1].body, {operation: 'personalize', command: {component,
    'expected-preference': {present: false, value: null}, 'expected-configuration-revision': revision}});
  assert.equal(f.stages.length, 1, 'original exact receipt is persisted before APPLY completes');
  assert.equal(f.confirms.length, 0);
  f.apply(); await until(() => f.confirms.length === 1);
  assert.equal(f.themes.length, 0);
  assert.deepEqual(f.stages[0]['function-receipts'], JSON.parse(JSON.stringify(f.receipt['function-receipts'])));
});
for (const component of ['recents', 'inspector']) test('own writable ' + component + ' opens without writes', async () => {
  const f = fixture(component); f.own(); f.group.click();
  await until(() => f.selections.length === 1);
  assert.equal(f.selections[0], id(95)); assert.equal(f.requests.length, 0);
  assert.equal(f.group.textContent, 'Open ' + component);
});
test('shared custom view remains a Personalize action until server metadata proves own writable child', async () => {
  const f = fixture(); assert.equal(f.group.textContent, 'Personalize recents');
  f.group.click(); await until(() => f.requests.some(row => row.route === '/apply'));
  assert.equal(f.selections.length, 0); f.apply(); await until(() => f.confirms.length === 1);
});
test('stale source refusal retains original receipt and never writes a selector preference', async () => {
  const f = fixture(); f.conflict(); f.group.click();
  await until(() => f.requests.some(row => row.route === '/apply')); f.apply();
  await until(() => f.status() === 'Configuration changed.');
  assert.equal(f.confirms.length, 0); assert.equal(f.rejects.length, 1); assert.equal(f.themes.length, 0);
});
for (const [count, accepted] of [[512, true], [513, false]]) test('new cohort receipt bound ' + count, async () => {
  const f = fixture(); f.receipt.functions = Array.from({length: count}, (_, n) => id(n + 2));
  f.receipt['function-receipts'] = f.receipt.functions.map(value => ({id: value, name: 'part', 'namespace-id': id(1)}));
  f.receipt.previous = null; f.group.click();
  await until(() => accepted ? f.requests.some(row => row.route === '/apply') : f.status() === 'The configuration preview is unavailable.');
  assert.equal(f.requests.some(row => row.route === '/apply'), accepted);
  if (accepted) { f.apply(); await until(() => f.confirms.length === 1); }
});
for (const [count, accepted] of [[1000, true], [1001, false]]) test('full compatibility receipt bound ' + count, async () => {
  const f = fixture(); f.full.functions = Array.from({length: count}, (_, n) => ({id: id(n + 20), name: 'part-' + n, 'namespace-id': id(1)}));
  f.create.click({currentTarget: f.create});
  await until(() => accepted ? f.requests.some(row => row.route === '/apply') : f.status() === 'Creation manifest is unavailable.');
  assert.equal(f.requests.some(row => row.route === '/full-apply'), accepted);
  if (accepted) { f.apply(); await until(() => f.confirms.length === 1); }
});
for (const corrupt of [f => { f.receipt['function-receipts'][1].id = f.receipt.id; },
  f => { f.receipt.previous.id = f.receipt.id; }, f => { f.receipt['cohort-revision'] = 'invalid'; }]) {
  test('invalid receipt never reaches APPLY', async () => {
    const f = fixture(); corrupt(f); f.group.click();
    await until(() => f.status() === 'The configuration preview is unavailable.');
    assert.equal(f.requests.some(row => row.route === '/apply'), false); assert.equal(f.stages.length, 0);
  });
}

for (const [label, corrupt] of [
  ['duplicate identity', f => { f.full.functions[1].id = f.full.functions[0].id; }],
  ['foreign namespace', f => { f.full.functions[0]['namespace-id'] = id(700); }],
  ['missing root', f => { f.full.roots['menu-id'] = id(701); }],
]) test('invalid full compatibility receipt: ' + label, async () => {
  const f = fixture(); corrupt(f); f.create.click({currentTarget: f.create});
  await until(() => f.status() === 'Creation manifest is unavailable.');
  assert.equal(f.requests.some(row => row.route === '/full-apply'), false);
  assert.equal(f.requests.some(row => row.route === '/apply'), false);
  assert.equal(f.stages.length, 0); assert.equal(f.themes.length, 0);
});
