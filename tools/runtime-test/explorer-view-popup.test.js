'use strict';
// The real builtin Explorer plan drives the popup while an async graph-list
// refresh lands. Its native input, focused action and owner stay authoritative.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const {installInspectorGraph} = require('./inspector-graph-fixture');

const editor = path.join(__dirname, '../../resources/packages/app/editor');
const document = createDocument();
const store = new Map();
for (const id of ['kind-filters', 'gd-filter-chips', 'gd-filter-add']) {
  const node = document.createElement('div'); node.id = id; document.body.appendChild(node);
}
const chip = document.createElement('button'); chip.id = 'gd-ws-chip';
chip.appendChild(document.createElement('b')); document.body.appendChild(chip);
let releaseViews;
const context = vm.createContext({document, console, Map, Set, Promise, AbortController,
  crypto: require('node:crypto').webcrypto,
  graphData: {namespaces: []}, lensKinds: new Set(), treeDetails: {fx: false},
  API: {api_views: '/api/views', api_views_save: '/api/views/save', api_views_members: '/api/views/members'},
  localStorage: {getItem: key => store.get(key) || null, setItem: (key, value) => store.set(key, value),
    removeItem: key => store.delete(key)},
  isAuthenticated: () => true,
  gdLoadUIComponentRuntime: () => new Promise(() => {}),
  authFetch: url => url === '/api/views'
    ? new Promise(resolve => { releaseViews = resolve; })
    : Promise.resolve({ok: true, json: async () => ({fns: [], total: 0})}),
  installPopoverDismiss: () => {}, ensurePopoverClose: () => {},
  focusIntoDialog: el => { document.activeElement = el.querySelector('.gd-views-input') || el; },
  returnFocusTo: el => { document.activeElement = el; }, anchorBelowClamped: () => {},
  updateEntityList: () => {}, applyKindFilters: () => {}, syncKindFilterBar: () => {},
});
context.window = context;
installInspectorGraph(context, document);
for (const file of ['editor-explorer-compare.js', 'editor-explorer-graph.js', 'editor-explorer-filters.js', 'editor-explorer-views-ui.js']) {
  vm.runInContext(fs.readFileSync(path.join(editor, file), 'utf8'), context, {filename: file});
}

(async () => {
  context.gdToggleNamespace('core');
  context.gdOpenViewPop(chip);
  const pop = document.getElementById('gd-ws-pop');
  const input = pop.querySelector('.gd-views-input');
  const baseFacts = {filtersActive: true, editing: false, authenticated: true,
    shareAvailable: true, pending: false, name: '  café  '};
  for (const [changes, expected] of [
    [{}, {visible: true, mode: 'save', 'device-available': true, 'graph-visible': true,
      'graph-available': true, 'enter-target': 'graph', 'name-valid': true}],
    [{editing: true, filtersActive: false}, {visible: true, mode: 'edit'}],
    [{authenticated: false}, {'graph-available': false, 'enter-target': 'graph'}],
    [{shareAvailable: false}, {'graph-visible': false, 'graph-available': false, 'enter-target': 'device'}],
    [{pending: true}, {'device-available': false, 'graph-available': false, 'enter-target': 'none'}],
    [{name: ' \t '}, {'name-valid': false, 'validation-message': 'Give the view a name'}],
  ]) {
    const policy = context.gdExplorerGraph.formPolicy({...baseFacts, ...changes});
    for (const [key, value] of Object.entries(expected)) assert.equal(policy[key], value, key);
  }
  input.value = 'draft-café';
  document.activeElement = input;
  assert.equal(pop.querySelector('.gd-views-pop-head').textContent, 'Views');
  assert.equal(pop.querySelector('[data-ws-all]').textContent, '◍ All functions — no filters');
  assert.equal(pop.querySelector('.gd-views-save.gd-views-share').textContent, 'Save in graph');
  assert.equal(pop.querySelectorAll('style').length, 0, 'no style per view item');

  releaseViews({ok: true, json: async () => [{id: 'shared-id', name: 'shared', filters: {name: 'needle'}}]});
  for (let i = 0; i < 12; i++) await Promise.resolve();
  assert.equal(pop.querySelector('.gd-views-input'), input, 'shared refresh preserves exact input node');
  assert.equal(input.value, 'draft-café', 'shared refresh preserves unfinished name');
  assert.equal(document.activeElement, input, 'shared refresh preserves focus');
  assert.equal(pop.querySelector('[data-view-row="graph:shared-id"] .gd-views-apply').textContent, 'shared');

  input.dispatch('compositionstart');
  input.dispatch('keydown', {key: 'Enter', isComposing: true});
  assert.equal(context.gdReadViews().length, 0, 'IME Enter cannot save a partial draft');
  input.dispatch('compositionend');
  const save = pop.querySelectorAll('.gd-views-save').find(node => !node.classList.contains('gd-views-share'));
  save.click();
  assert.equal(context.gdReadViews()[0].name, 'draft-café', 'typed native save receives exact current draft');
  assert.equal(pop.querySelector('.gd-views-input'), input, 'saving a device view keeps the input node');
  const row = pop.querySelector('[data-view-row="device:draft-café"]');
  assert.equal(row.querySelector('.gd-views-apply').textContent, '● draft-café');
  pop._viewForm.pending = true;
  context._renderViewForm(pop);
  context._renderViewForm(pop); // A shared-list refresh cannot re-enable a pending write.
  assert(pop._viewForm.share.disabled && pop._viewForm.save.disabled && input.disabled,
    'graph form policy keeps all controls unavailable through a pending refresh');
  pop._viewForm.pending = false;
  context._renderViewForm(pop);
  assert(!pop._viewForm.share.disabled && !pop._viewForm.save.disabled && !input.disabled,
    'the same controls become available after the pending write settles');
  assert.equal(pop.querySelector('.gd-views-input'), input);
  const oldRow = pop.querySelector('[data-view-row="graph:shared-id"]');
  context.gdOpenViewPop(chip); // Toggle closes the old owner and disposes its bodies.
  assert(!oldRow.isConnected, 'closed popup cannot keep an old shared row connected');
  context.gdInvalidateSharedViews();
  context.authFetch = url => url === '/api/views'
    ? new Promise(resolve => { releaseViews = resolve; })
    : Promise.resolve({ok: true, json: async () => ({fns: [], total: 0})});
  context.gdOpenViewPop(chip);
  const focusedPop = document.getElementById('gd-ws-pop');
  focusedPop.appendChild(oldRow);
  let replayed = 0;
  const selectView = context.gdExplorerGraph.selectView;
  context.gdExplorerGraph.selectView = () => { replayed++; };
  oldRow.querySelector('.gd-views-apply').click();
  context.gdExplorerGraph.selectView = selectView;
  oldRow.remove();
  assert.equal(replayed, 0, 'a reattached old-owner action cannot replay in a new popup');
  const focusedButton = focusedPop.querySelector('[data-view-row="device:draft-café"] .gd-views-apply');
  document.activeElement = focusedButton;
  releaseViews({ok: true, json: async () => [{id: 'shared-id', name: 'shared', filters: {}}]});
  for (let i = 0; i < 12; i++) await Promise.resolve();
  assert.equal(focusedPop.querySelector('[data-view-row="device:draft-café"] .gd-views-apply'), focusedButton,
    'shared refresh preserves the exact focused device action');
  assert.equal(document.activeElement, focusedButton, 'shared refresh does not steal action focus');
  const plan = context.GraphdenBuiltinPlans.plans.explorer;
  const baseRuntime = context.GraphdenBrowser.createRuntime(plan);
  const fallbackSections = [];
  context.gdUIComponentSectionFallback = (_component, section) => fallbackSections.push(section);
  context.gdLoadUIComponentRuntime = async () => ({run(entry, supplied) {
    if (entry === 'initial' && supplied.inputs.get(context.GraphdenBrowser.keyword('surface')) === 'view-form') {
      return new Map([[context.GraphdenBrowser.keyword('handled'), false]]); // Existing personal initial shape.
    }
    const accepted = new Set(plan.inputs[plan.entries[entry]].accepted);
    return baseRuntime.run(entry, Object.fromEntries(Object.entries(supplied).filter(([name]) => accepted.has(name))));
  }});
  context.gdExplorerGraph.refresh();
  await context.gdExplorerGraph.reload();
  context._renderViewForm(focusedPop);
  assert.equal(focusedPop.querySelector('[data-view-row="device:draft-café"] .gd-views-apply'), focusedButton,
    'personal view fallback retains the native focused action');
  assert.equal(focusedButton.textContent, '● draft-café', 'view body returns to the built-in presentation');
  assert.equal(fallbackSections.join(','), 'views', 'old personal initial falls back only in the view section');
  context.gdOpenViewPop(chip);

  context.gdInvalidateSharedViews();
  context.gdOpenViewPop(chip);
  context.gdAccount = {id: 'next-account'};
  context.dispatchEvent({type: 'gd-auth-changed'});
  assert.equal(document.getElementById('gd-ws-pop'), null, 'auth change closes the old popup immediately');
  releaseViews({ok: true, json: async () => [{id: 'old-id', name: 'old-private-view', filters: {}}]});
  for (let i = 0; i < 12; i++) await Promise.resolve();
  assert.equal(document.getElementById('gd-ws-pop'), null, 'old account result cannot remount the popup');
  console.log('PASS Explorer view popup graph, stable native draft and owner teardown');
})().catch(error => { console.error(error); process.exitCode = 1; });
