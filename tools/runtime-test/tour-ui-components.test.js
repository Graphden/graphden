'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-tour-ui-components.js'), 'utf8');
function fixture() {
  const prefs = {components: {old: 'components'}, theme: {old: 'theme'}};
  let saving = true;
  const writes = [];
  const state = {lessonId: '25', principal: {accountId: 'alice', orgId: 'acme'}, created: []};
  const ctx = vm.createContext({_tourState: state, graphdenCurrentOrg: 'acme', crypto,
    getCurrentBranchName: () => 'sandbox',
    _tourPrincipalMatches: saved => saved.principal?.accountId === 'alice' && saved.principal?.orgId === 'acme',
    _tourStep: () => ({check: {kind: 'ui-component', action: 'created'}}), _tourSaveState() {},
    gdPrefOwner: 'alice', gdPrefRead: key => prefs[key],
    gdPrefWrite: async (key, value) => { prefs[key] = value; writes.push({key, value}); return saving; }});
  ctx.window = ctx;
  vm.runInContext(source, ctx);
  const request = {owner: 'alice', org: 'acme', 'branch-id': 'branch-id', 'root-id': 'root-id'};
  const manifest = {namespaces: [{id: 'root-id', name: 'ui-root', 'parent-id': null}],
    functions: [{id: 'config-id', name: 'ui', 'namespace-id': 'root-id'}, {id: 'theme-id', name: 'theme', 'namespace-id': 'root-id'}],
    roots: {'configuration-id': 'config-id', 'theme-id': 'theme-id'}};
  return {ctx, state, request, manifest, prefs, writes, saving: value => { saving = value; }};
}
(async () => {
  {
    const f = fixture();
    const ticket = f.ctx.gdTourStageUIComponentsManifest(f.request, f.manifest);
    assert.equal(f.state.created.length, 3);
    assert(f.state.created.every(row => row.receipt === 'pending' && row.creation === 'create-only-manifest'));
    assert.equal(f.state.created[0]['parent-id'], null, 'explicit rootless tuple retained');
    f.manifest.functions[0].name = 'changed';
    assert.equal(f.ctx.gdTourConfirmUIComponentsManifest(ticket, f.manifest), false, 'immutable staged manifest refuses changed receipt');
    assert(f.state.created.every(row => row.receipt === 'pending'), 'ambiguous outcome stays eligible for exact recovery');
    f.ctx.gdTourRejectUIComponentsManifest(ticket);
    assert.equal(f.state.created.length, 0, 'definite prewrite rejection discards only that attempt');
  }
  {
    const f = fixture();
    const ticket = f.ctx.gdTourStageUIComponentsManifest(f.request, f.manifest);
    assert.equal(f.ctx.gdTourConfirmUIComponentsManifest(ticket, f.manifest), true);
    f.prefs.components = {'fn-id': 'config-id'};
    f.prefs.theme = {graph: {'fn-id': 'theme-id'}};
    const created = [...f.state.created];
    const failed = [{type: 'fn', id: 'config-id', name: 'ui'}];
    await f.ctx.gdTourRestoreUIComponentPreferences(created, failed);
    assert.equal(f.prefs.components['fn-id'], 'config-id', 'failed exact config removal keeps its selection');
    assert.equal(f.prefs.theme.old, 'theme', 'successfully removed own theme restores its previous preference');
    f.saving(false);
    const refused = await f.ctx.gdTourRestoreUIComponentPreferences(created, []);
    assert.equal(refused.length, 1);
    assert.equal(f.prefs.components.old, 'components', 'preference owner applies optimistically even when server save fails');
    assert(f.state.created.some(row => row.type === 'preference'), 'retry survives an otherwise empty cleanup');
    f.saving(true);
    const retried = await f.ctx.gdTourRestoreUIComponentPreferences(created, []);
    assert.equal(retried.length, 0);
    assert.equal(f.writes.filter(row => row.key === 'components').length, 2, 'optimistic local state does not hide failed persistence on retry');
    assert(!f.state.created.some(row => row.type === 'preference'));
  }
  {
    const f = fixture();
    f.ctx.gdTourStageUIComponentsManifest(f.request, f.manifest);
    f.prefs.components = {'fn-id': 'foreign-user-choice'};
    f.prefs.theme = {graph: {'fn-id': 'foreign-theme'}};
    await f.ctx.gdTourRestoreUIComponentPreferences([...f.state.created], []);
    assert.equal(f.writes.length, 0, 'unrelated current choices are preserved');
    const ticket = f.ctx.gdTourStageUIComponentsManifest(f.request, f.manifest);
    f.ctx.gdPrefOwner = 'bob';
    assert.equal(f.ctx.gdTourConfirmUIComponentsManifest(ticket, f.manifest), false, 'late receipt cannot cross accounts');
  }
  {
    const f = fixture();
    f.manifest.namespaces.push({id: 'theme-ns', name: 'theme', 'parent-id': 'root-id'});
    f.manifest.functions.push({id: 'canvas-id', name: 'theme-canvas-color', 'namespace-id': 'theme-ns'});
    f.manifest.functions.push({id: 'recents-id', name: 'recents-view', 'namespace-id': 'root-id'});
    f.manifest.roots['recents-id'] = 'recents-id';
    const ticket = f.ctx.gdTourStageUIComponentsManifest(f.request, f.manifest);
    f.ctx.gdTourConfirmUIComponentsManifest(ticket, f.manifest);
    f.ctx.gdUIComponentsSelection = () => ({'fn-id': 'config-id'});
    f.ctx.lookups = {fnMap: new Map(), slotMap: new Map([['value-slot', {name: 'value'}]])};
    f.ctx.graphData = {bindings: [{'fn-id': 'neighbor-id', 'slot-id': 'value-slot', value: '#fff7ed'}]};
    f.ctx.gdThemeTokenValue = () => '#fff7ed';
    const check = {action: 'literal', group: 'theme', name: 'theme-canvas-color', slot: 'value', value: '#fff7ed', token: '--bg'};
    assert.equal(f.ctx.gdTourUIComponentCheck(check), false, 'a same-named neighboring graph never satisfies the owned lesson mutation');
    f.ctx.lookups.fnMap.set('canvas-id', {id: 'canvas-id', name: 'theme-canvas-color', 'namespace-id': 'theme-ns'});
    assert.equal(f.ctx.gdTourUIComponentCheck(check), false, 'selected graph metadata alone is not a changed binding');
    f.ctx.graphData.bindings.push({'fn-id': 'canvas-id', 'slot-id': 'value-slot', value: '#fff7ed'});
    assert.equal(f.ctx.gdTourUIComponentCheck(check), true);
    f.ctx.lookups.fnMap.get('canvas-id')['namespace-id'] = 'neighbor-ns';
    assert.equal(f.ctx.gdTourUIComponentCheck(check), false, 'moving the identity out of its created group invalidates the ownership tuple');
    f.ctx.lookups.fnMap.get('canvas-id')['namespace-id'] = 'theme-ns';
    f.ctx.gdThemeTokenValue = () => '#f8fafc';
    assert.equal(f.ctx.gdTourUIComponentCheck(check), false, 'saved source without its active theme effect cannot advance');
    f.ctx.gdShellMenuGraph = {ready: true};
    f.ctx.gdFnPickerGraph = {ready: true};
    f.ctx.gdUIComponentRuntimeIdentity = kind => kind === 'account-menu' ? 'config-id' : 'stale-config';
    assert.equal(f.ctx.gdTourUIComponentCheck({action: 'created'}), false, 'one stale mounted component does not prove the created configuration is active');
    f.ctx.gdUIComponentRuntimeIdentity = () => 'config-id';
    assert.equal(f.ctx.gdTourUIComponentCheck({action: 'created'}), true);
    f.ctx.gdUIComponentRuntimeIdentity = kind => kind === 'recents' ? 'stale-config' : 'config-id';
    assert.equal(f.ctx.gdTourUIComponentCheck({action: 'created'}), false, 'the new manifest waits for its personal recent-function graph');
  }
  console.log('PASS exact UI manifest recovery, selected preference restore and failed persistence retry');
})().catch(error => { console.error(error); process.exitCode = 1; });
