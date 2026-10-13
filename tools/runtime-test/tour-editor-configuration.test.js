'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-tour-ui-components.js'), 'utf8');
const clone = value => JSON.parse(JSON.stringify(value));
function fixture() {
  const state = {lessonId: '25', principal: {accountId: 'alice', orgId: 'acme'}, created: []};
  const calls = [];
  let refused = false;
  const ctx = vm.createContext({_tourState: state, graphdenCurrentOrg: 'acme', crypto,
    getCurrentBranchName: () => 'sandbox', gdPrefOwner: 'alice',
    _tourPrincipalMatches: saved => saved.principal?.accountId === ctx.gdPrefOwner && saved.principal?.orgId === 'acme',
    _tourSaveState() {}, gdRefreshUIConfiguration() {},
    API: {api_ui_configuration_reconcile: '/reconcile', api_ui_configuration_undo: '/undo',
      api_ui_configuration_reconcile_undo: '/reconcile-undo'},
    authFetch: async (route, options) => {
      const input = JSON.parse(options.body); calls.push({route, input});
      return {ok: !refused, json: async () => refused ? {ok: false} : route === '/reconcile'
        ? {ok: true, confirmed: true, 'configuration-revision': input['expected-configuration-revision'], 'cohort-revision': input['cohort-revision']}
        : {ok: true, deleted: input.functions.map(row => row.id)}};
    },
  });
  ctx.window = ctx; vm.runInContext(source, ctx);
  const request = {operation: 'personalize', nonce: 'nonce', scope: {'principal-id': 'alice', org: 'acme', 'branch-id': 'branch-id'}};
  const original = n => ({id: 'root-' + n, created: true, functions: ['root-' + n, 'child-' + n], namespaces: [],
    'configuration-revision': 'config-stamp-' + n, 'cohort-revision': 'cohort-stamp-' + n,
    'restore-id': n === 1 ? 'defaults' : 'root-' + (n - 1), 'restore-revision': 'restore-stamp-' + n,
    previous: n === 1 ? null : {id: 'root-' + (n - 1), name: '_editor-prior', 'namespace-id': 'home', revision: 'prior-stamp'},
    'function-receipts': [{id: 'root-' + n, name: 'editor', 'namespace-id': 'home'}, {id: 'child-' + n, name: 'view', 'namespace-id': 'home'}]});
  return {ctx, state, calls, request, original, refuse: () => { refused = true; }};
}
test('configuration staging persists exact original IDs and stamps before any response', () => {
  const f = fixture(), original = f.original(1);
  const ticket = f.ctx.gdTourStageEditorConfiguration(f.request, original);
  assert.equal(f.state.created.length, 2);
  assert(f.state.created.every(row => row.creation === 'editor-configuration' && row.receipt === 'pending'));
  original['cohort-revision'] = 'later-stamp';
  assert.equal(f.state.editorConfigurations[0].original['cohort-revision'], 'cohort-stamp-1');
  assert.equal(f.ctx.gdTourConfirmEditorConfiguration(ticket, original), false);
  assert.equal(f.state.editorConfigurations[0].receipt, 'pending');
});
test('confirmed original receipts undo strictly LIFO without refreshing revisions', async () => {
  const f = fixture();
  for (const n of [1, 2]) {
    const original = f.original(n), ticket = f.ctx.gdTourStageEditorConfiguration(f.request, original);
    assert.equal(f.ctx.gdTourConfirmEditorConfiguration(ticket, {...original, ok: true, committed: true}), true);
  }
  assert.equal((await f.ctx.gdTourUndoEditorConfigurations(f.state.created)).length, 0);
  assert.deepEqual(f.calls.map(row => [row.route, row.input['current-id'], row.input['cohort-revision']]),
    [['/undo', 'root-2', 'cohort-stamp-2'], ['/undo', 'root-1', 'cohort-stamp-1']]);
  assert(f.state.created.every(row => row.receipt === 'removed'));
});
test('uncertain APPLY is reconciled using original stamps before undo', async () => {
  const f = fixture(); f.ctx.gdTourStageEditorConfiguration(f.request, f.original(1));
  await f.ctx.gdTourUndoEditorConfigurations(f.state.created);
  assert.deepEqual(f.calls.map(row => row.route), ['/reconcile', '/undo']);
  assert.deepEqual(f.calls[0].input, f.calls[1].input);
  assert.equal(f.calls[0].input['expected-configuration-revision'], 'config-stamp-1');
});
test('changed uncertain cohort is never promoted or undone', async () => {
  const f = fixture(); f.ctx.gdTourStageEditorConfiguration(f.request, f.original(1)); f.refuse();
  const failed = await f.ctx.gdTourUndoEditorConfigurations(f.state.created);
  assert.equal(failed.length, 2); assert.deepEqual(f.calls.map(row => row.route), ['/reconcile']);
  assert(f.state.created.every(row => row.receipt === 'pending'));
});
test('stale top receipt retains every older configuration and stops deletion', async () => {
  const f = fixture();
  for (const n of [1, 2]) {
    const original = f.original(n), ticket = f.ctx.gdTourStageEditorConfiguration(f.request, original);
    f.ctx.gdTourConfirmEditorConfiguration(ticket, original);
  }
  f.refuse(); const failed = await f.ctx.gdTourUndoEditorConfigurations(f.state.created);
  assert.equal(failed.length, 2); assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].input['current-id'], 'root-2');
  assert(f.state.created.every(row => row.receipt === 'created'));
});
test('account switch prevents confirmation and cleanup of another principal receipt', async () => {
  const f = fixture(), original = f.original(1), ticket = f.ctx.gdTourStageEditorConfiguration(f.request, original);
  f.ctx.gdPrefOwner = 'bob';
  assert.equal(f.ctx.gdTourConfirmEditorConfiguration(ticket, original), false);
  assert.equal((await f.ctx.gdTourUndoEditorConfigurations(f.state.created)).length, 2);
  assert.equal(f.calls.length, 0);
});
test('definite rejection discards only its pending attempt', () => {
  const f = fixture(), first = f.ctx.gdTourStageEditorConfiguration(f.request, f.original(1));
  f.ctx.gdTourStageEditorConfiguration(f.request, f.original(2));
  f.ctx.gdTourRejectEditorConfiguration(first);
  assert.equal(f.state.editorConfigurations.length, 1);
  assert.deepEqual(clone(f.state.created).map(row => row.id), ['root-2', 'child-2']);
});

test('lost undo response uses the witness persisted before DELETE and confirms exact postconditions', async () => {
  const f = fixture(), original = f.original(1);
  f.ctx.gdTourConfirmEditorConfiguration(f.ctx.gdTourStageEditorConfiguration(f.request, original), original);
  const requests = [];
  f.ctx.authFetch = async (route, options) => {
    const input = JSON.parse(options.body); requests.push({route, input});
    if (route === '/undo') {
      assert.deepEqual(clone(f.state.editorConfigurations[0].undoAttempt.receipt), input);
      throw new Error('Response unavailable');
    }
    return {ok: true, json: async () => ({ok: true, confirmed: true, undone: true,
      id: 'defaults', 'restore-revision': input['restore-revision']})};
  };
  assert.equal((await f.ctx.gdTourUndoEditorConfigurations(f.state.created)).length, 0);
  assert.deepEqual(requests.map(row => row.route), ['/undo', '/reconcile-undo']);
  assert.equal(requests[1].input['restore-revision'], original['restore-revision']);
  assert.deepEqual(clone(f.state.editorConfigurations[0].original), original);
  assert(f.state.created.every(row => row.receipt === 'removed'));
});

test('uncertain undo with changed restored source stays pending and retries only read-only confirmation', async () => {
  const f = fixture(), original = f.original(1);
  f.ctx.gdTourConfirmEditorConfiguration(f.ctx.gdTourStageEditorConfiguration(f.request, original), original);
  const routes = [];
  f.ctx.authFetch = async route => {
    routes.push(route);
    if (route === '/undo') throw new Error('Response unavailable');
    return {ok: true, json: async () => ({ok: true, confirmed: true, undone: true,
      id: 'defaults', 'restore-revision': 'edited-restore'})};
  };
  for (const _ of [1, 2]) assert.equal((await f.ctx.gdTourUndoEditorConfigurations(f.state.created)).length, 2);
  assert.deepEqual(routes, ['/undo', '/reconcile-undo', '/reconcile-undo']);
  assert(f.state.created.every(row => row.receipt === 'created'));
  assert.equal(f.state.editorConfigurations[0].undoAttempt['restore-revision'], original['restore-revision']);
});

test('account change during an uncertain undo sends no postcondition request in the new context', async () => {
  const f = fixture(), original = f.original(1);
  f.ctx.gdTourConfirmEditorConfiguration(f.ctx.gdTourStageEditorConfiguration(f.request, original), original);
  const routes = [];
  f.ctx.authFetch = async route => { routes.push(route); f.ctx.gdPrefOwner = 'bob'; throw new Error('Response unavailable'); };
  assert.equal((await f.ctx.gdTourUndoEditorConfigurations(f.state.created)).length, 2);
  assert.deepEqual(routes, ['/undo']);
});

test('cleanup shows current exact names and submits the reviewed stamps only after consent', async () => {
  const f = fixture(), original = f.original(1);
  f.ctx.gdTourConfirmEditorConfiguration(f.ctx.gdTourStageEditorConfiguration(f.request, original), original);
  f.ctx.API.api_ui_configuration_cleanup_preview = '/cleanup-preview';
  let dialog, ended = 0;
  f.ctx._tourDialog = value => { dialog = value; };
  f.ctx._tourEnd = async () => { ended++; };
  f.ctx._tourKeepAndContinue = () => {};
  f.ctx._tourReport = () => {};
  const request = {'current-id': original.id, 'expected-configuration-revision': 'reviewed-config',
    'cohort-revision': 'reviewed-cohort', previous: null,
    functions: original['function-receipts'].map(row => ({...row, name: row.name + '-edited'}))};
  const routes = [];
  f.ctx.authFetch = async (route, options) => {
    const input = JSON.parse(options.body); routes.push({route, input});
    return {ok: true, json: async () => route === '/cleanup-preview'
      ? {ok: true, kind: 'editor-configuration-cleanup-review', request,
        'restore-revision': original['restore-revision'], delete: request.functions,
        restore: {id: 'defaults', name: 'Shared defaults'}}
      : {ok: true, deleted: original.functions}};
  };
  assert.equal(await f.ctx.gdTourReviewEditorConfigurationCleanup(null), true);
  assert.deepEqual(routes.map(row => row.route), ['/cleanup-preview']);
  assert.match(dialog.body, /view-edited/);
  assert.match(dialog.body, /including your edits/);
  assert.deepEqual(clone(f.state.editorConfigurations[0].original), original);
  await dialog.primary[1]();
  assert.deepEqual(routes.map(row => row.route), ['/cleanup-preview', '/undo']);
  assert.deepEqual(routes[1].input, request);
  assert.equal(ended, 1);
});

test('stale reviewed cleanup retains the original receipt and asks for a new visible review', async () => {
  const f = fixture(), original = f.original(1);
  f.ctx.gdTourConfirmEditorConfiguration(f.ctx.gdTourStageEditorConfiguration(f.request, original), original);
  f.ctx.API.api_ui_configuration_cleanup_preview = '/cleanup-preview';
  let dialog, reported = false;
  f.ctx._tourDialog = value => { dialog = value; };
  f.ctx._tourEnd = async () => { assert.fail('Stale cleanup must not advance'); };
  f.ctx._tourKeepAndContinue = () => {};
  f.ctx._tourReport = success => { reported = !success; };
  const request = {'current-id': original.id, 'expected-configuration-revision': 'reviewed-config',
    'cohort-revision': 'reviewed-cohort', previous: null, functions: original['function-receipts']};
  const routes = [];
  f.ctx.authFetch = async route => {
    routes.push(route);
    return {ok: route === '/cleanup-preview', json: async () => route === '/cleanup-preview'
      ? {ok: true, kind: 'editor-configuration-cleanup-review', request,
        'restore-revision': original['restore-revision'], delete: request.functions,
        restore: {id: 'defaults', name: 'Shared defaults'}} : {ok: false}};
  };
  await f.ctx.gdTourReviewEditorConfigurationCleanup(null);
  await dialog.primary[1]();
  assert.deepEqual(routes, ['/cleanup-preview', '/undo']);
  assert.equal(reported, true);
  assert.equal(f.state.editorConfigurations[0].undoAttempt, undefined);
  assert.deepEqual(clone(f.state.editorConfigurations[0].original), original);
  assert(f.state.created.every(row => row.receipt === 'created'));
});

test('reviewed cleanup stops after the one visible LIFO decision', async () => {
  const f = fixture();
  for (const n of [1, 2]) {
    const original = f.original(n);
    f.ctx.gdTourConfirmEditorConfiguration(f.ctx.gdTourStageEditorConfiguration(f.request, original), original);
  }
  const top = f.state.editorConfigurations[1];
  const review = {request: {'current-id': top.original.id, 'expected-configuration-revision': 'reviewed-config',
    'cohort-revision': 'reviewed-cohort', previous: top.original.previous, functions: top.original['function-receipts']},
  'restore-revision': top.original['restore-revision']};
  assert.equal((await f.ctx.gdTourUndoEditorConfigurations(f.state.created, {token: top.token, review})).length, 0);
  assert.deepEqual(f.calls.map(row => row.input['current-id']), ['root-2']);
  assert.equal(f.state.editorConfigurations[0].receipt, 'created');
  assert.equal(top.receipt, 'removed');
});
test('personal menu lesson requires its own receipt, binding and active tokens', () => {
  const f = fixture(), original = {...f.original(1), 'entry-id': 'child-1'};
  f.ctx.gdUIComponentsRoots = () => ({'menu-id': 'child-1', 'picker-id': 'shared-picker'});
  const request = {...f.request, command: {component: 'account-menu'}};
  const ticket = f.ctx.gdTourStageEditorConfiguration(request, original);
  f.ctx.gdTourConfirmEditorConfiguration(ticket, original);
  f.ctx.lookups = {};
  f.ctx.selectedFnId = 'child-1';
  f.ctx.gdUIComponentsSelection = () => ({'fn-id': 'root-1'});
  f.ctx.gdUIComponentRuntimeIdentity = () => 'root-1';
  assert.equal(f.ctx.gdTourUIComponentCheck({action: 'personalized', component: 'account-menu'}), true);
  assert.equal(f.ctx.gdTourUIComponentCheck({action: 'personal-open', component: 'account-menu'}), true);
  const check = {action: 'personal-theme', component: 'account-menu', accent: '#7c3aed', background: '#f5f3ff'};
  const value = {accent: check.accent, 'canvas-background': check.background};
  f.ctx.gdThemeTokenValue = key => key === '--gd-flow' ? check.accent : check.background;
  f.ctx.graphData = {bindings: [{'fn-id': 'shared-helper', value}]};
  assert.equal(f.ctx.gdTourUIComponentCheck(check), false, 'a shared helper does not prove an own capture');
  f.ctx.graphData.bindings.push({'fn-id': 'child-1', value});
  assert.equal(f.ctx.gdTourUIComponentCheck(check), true);
  f.ctx.gdThemeTokenValue = () => '#ffffff';
  assert.equal(f.ctx.gdTourUIComponentCheck(check), false, 'saved source must also project actual tokens');
  f.ctx.selectedFnId = 'shared-picker';
  assert.equal(f.ctx.gdTourUIComponentCheck({action: 'retained-open', root: 'picker-id'}), true);
  f.ctx.gdUIComponentsRoots = () => ({'menu-id': 'child-1', 'picker-id': 'changed-picker'});
  assert.equal(f.ctx.gdTourUIComponentCheck({action: 'retained-open', root: 'picker-id'}), false);
  f.ctx.gdPrefOwner = 'bob';
  assert.equal(f.ctx.gdTourUIComponentCheck({action: 'personal-open', component: 'account-menu'}), false);
});
test('an existing own menu is inspected without creation or mutation receipts', () => {
  const f = fixture();
  const roots = {'menu-id': 'existing-menu', 'picker-id': 'shared-picker'};
  f.ctx.gdUIComponentsRoots = () => roots;
  const result = {id: 'existing-editor', 'entry-id': 'existing-menu', created: false};
  f.ctx.gdTourObserveExistingUIComponent({...f.request, command: {component: 'account-menu'}}, result, roots);
  assert.equal(f.state.created.length, 0);
  assert.equal(f.state.editorConfigurations, undefined);
  f.ctx.lookups = {};
  f.ctx.selectedFnId = 'existing-menu';
  f.ctx.gdUIComponentsSelection = () => ({'fn-id': 'existing-editor'});
  f.ctx.gdUIComponentRuntimeIdentity = () => 'existing-editor';
  assert.equal(f.ctx.gdTourUIComponentCheck({action: 'personalized', component: 'account-menu'}), true);
  const authored = {title: 'Capture a personal theme input', check: {action: 'personal-theme', component: 'account-menu'}};
  const projected = f.ctx.gdTourUIComponentStep(authored);
  assert.equal(projected.check.kind, 'manual');
  assert.match(projected.body, /Keep your current values/);
  assert.equal(authored.check.kind, undefined, 'the shipped lesson definition remains unchanged');
  f.ctx.gdPrefOwner = 'bob';
  assert.equal(f.ctx.gdTourUIComponentStep(authored), authored, 'inspection mode cannot cross accounts');
  assert.equal(f.ctx.gdTourUIComponentCheck({action: 'personal-open', component: 'account-menu'}), false);
});
