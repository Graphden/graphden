'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-ui-component-catalog.js'), 'utf8');
const id = n => String(n).padStart(8, '0') + '-1111-1111-1111-111111111111';
function fixture(component = 'recents', save = true) {
  const buttons = new Map();
  for (const name of ['create', 'choose', 'reset', 'label', 'status']) buttons.set('#gd-ui-components-' + name,
    {dataset: {}, isConnected: true, addEventListener(_name, callback) { this.click = callback; }});
  const group = {dataset: {uiComponent: component, uiLabel: 'recent functions'}, isConnected: true,
    addEventListener(_name, callback) { this.click = callback; }};
  const root = {dataset: {}, querySelector: key => buttons.get(key), querySelectorAll: key =>
    key === '[data-ui-component]' ? [group] : key === 'button' ? [...buttons.values(), group] : []};
  let preference = null;
  let roots = {};
  let selectCount = 0;
  let applyResolve;
  const requests = [], writes = [], themes = [];
  const manifest = {component, 'expected-preference': {present: false, value: null},
    namespaces: [{id: id(1), name: 'personal', path: 'personal', 'parent-id': null}],
    functions: ['ui', 'initial', 'update', 'view'].map((name, index) => ({id: id(index + 2), name, 'namespace-id': id(1)})),
    roots: {'configuration-id': id(2), 'recents-initial-id': id(3), 'recents-update-id': id(4), 'recents-id': id(5)}};
  const viewKey = {'account-menu': 'menu-id', 'fn-picker': 'picker-id', recents: 'recents-id', inspector: 'inspector-id'}[component];
  manifest.roots[viewKey] = id(5);
  if (component === 'inspector') Object.assign(manifest.roots, {'inspector-initial-id': id(3), 'inspector-update-id': id(4)});
  if (component === 'account-menu') Object.assign(manifest.roots, {'menu-initial-id': id(3), 'menu-update-id': id(4)});
  const request = {owner: 'alice', org: 'public', 'branch-id': id(9), component};
  const window = {gdPrefOwner: 'alice', gdPrefsReady: true, API: {api_ui_components_create_preview: '/preview', api_ui_components_create_apply: '/apply'},
    gdPrefRead: () => preference, gdUIComponentsSelection: () => preference, gdUIComponentsRoots: () => roots,
    gdUIComponentsStatus: () => '', gdPrefOnChange() {}, addEventListener() {}, gdShellSurface() {},
    gdPrefWrite: async (...args) => { writes.push(args); if (save) { preference = args[1]; roots = preference.roots; } return save; },
    gdUseThemeGraph: async theme => themes.push(theme),
    authFetch: async (route, options) => {
      requests.push({route, body: JSON.parse(options.body)});
      const response = body => ({ok: true, json: async () => body});
      if (route === '/preview') return response({ok: true, request, manifest});
      return new Promise(resolve => { applyResolve = () => resolve(response({ok: true, committed: true, manifest})); });
    },
  };
  const context = vm.createContext({window, document: {getElementById: () => root}, AbortController,
    graphData: {namespaces: []}, getCurrentBranchName: () => 'develop',
    openNamespacePicker: ({onPick}) => onPick(null), loadGraphData: async () => {},
    ensureSubtreeFor: async () => true, lookups: {fnMap: new Map([[id(5), {id: id(5)}]])},
    selectFn: () => { selectCount++; }});
  vm.runInContext(source, context);
  window.gdRenderUIComponentCatalog();
  return {group, window, requests, writes, themes, manifest, apply: () => applyResolve(),
    change: value => { preference = value; roots = value?.roots || {}; }, selections: () => selectCount,
    status: () => buttons.get('#gd-ui-components-status').textContent};
}
async function until(predicate) {
  for (let n = 0; n < 30; n++) { if (predicate()) return; await new Promise(setImmediate); }
  assert.fail('component action did not settle');
}

test('missing group copies only its chosen template and activates with the server preference comparison', async () => {
  const f = fixture(); f.group.click();
  await until(() => f.requests.length === 2); f.apply();
  await until(() => f.writes.length === 1);
  assert.equal(f.requests[0].body.component, 'recents');
  assert.equal(f.requests[0].body['namespace-id'], null);
  assert.deepEqual(JSON.parse(JSON.stringify(f.writes[0][2])), {expected: {present: false, value: null}});
  assert.equal(f.writes[0][1]['fn-id'], f.manifest.roots['configuration-id']);
  assert.equal(f.themes.length, 0, 'creating recents leaves the active theme unchanged');
});

test('selection changed during creation leaves committed graphs available without overwriting the new choice', async () => {
  const f = fixture(); f.group.click();
  await until(() => f.requests.length === 2);
  f.change({'fn-id': id(8)}); f.apply();
  await until(() => f.status().includes('selection changed'));
  assert.equal(f.writes.length, 0);
  assert.equal(f.themes.length, 0);
});

test('an existing personal group opens its own identity instead of creating another copy', async () => {
  const f = fixture(); f.change({'fn-id': id(2), branch: 'develop', roots: {'recents-id': id(5)}});
  f.window.gdRenderUIComponentCatalog(); f.group.click();
  await until(() => f.selections() === 1);
  assert.equal(f.requests.length, 0);
  assert.equal(f.group.textContent, 'Open recent functions');
});

for (const component of ['account-menu', 'fn-picker', 'inspector']) {
  test(component + ' uses its selected template without activating a theme', async () => {
    const f = fixture(component); f.group.click();
    await until(() => f.requests.length === 2); f.apply();
    await until(() => f.writes.length === 1);
    assert.equal(f.requests[0].body.component, component);
    assert.equal(Object.hasOwn(f.requests[0].body, 'roots'), false, 'client roots are not selection authority');
    assert.deepEqual(JSON.parse(JSON.stringify(f.requests[1].body)),
      {owner: 'alice', org: 'public', 'branch-id': id(9), component});
    assert.equal(f.themes.length, 0);
  });
}

test('a rejected conditional activation leaves the created graphs available and reports the conflict', async () => {
  const f = fixture('recents', false); f.group.click();
  await until(() => f.requests.length === 2); f.apply();
  await until(() => f.status().includes('were created'));
  assert.equal(f.writes.length, 1);
  assert.equal(f.themes.length, 0);
  assert.match(f.status(), /selection changed or could not be saved/);
});

test('an existing Inspector opens its retained view without issuing creation or preference writes', async () => {
  const f = fixture('inspector'); f.change({'fn-id': id(2), branch: 'develop', roots: {'inspector-id': id(5)}});
  f.window.gdRenderUIComponentCatalog(); f.group.click();
  await until(() => f.selections() === 1);
  assert.equal(f.requests.length, 0);
  assert.equal(f.writes.length, 0);
});
