// Sanctioned personal selection + persisted user HOF binding -> real root
// checker/source exporter -> real editor UI. Package-owned bindings are read
// only. No response interception; exact receipts clean up the throwaway branch.
'use strict';
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');
const {newContext, api, BASE, waitFor, waitForServerHealthy} = require('./edit-test-helpers');
const {undoConfiguration} = require('./configuration-undo-test-helpers');
const branchName = 'root-binding-' + randomUUID();
const branchApi = (method, path, body) => api(null, method, path, body, {'X-Graphden-Branch': branchName});
const mainApi = (method, path, body) => api(null, method, path, body, {'X-Graphden-Branch': 'main'});
const subtree = (call, id) => call('GET', '/api/graph/entities?scope=subtree&root-id=' + encodeURIComponent(id));
const argument = (fn, name) => fn.args.find(arg => arg.name === name)?.expr;
const keyOf = fn => argument(fn, 'key')?.value?.[2];
function menuView(plan) {
  const byId = new Map(plan.functions.map(fn => [fn.id, fn]));
  const field = plan.functions.find(fn => keyOf(fn) === 'account-menu');
  assert(field, 'real root exposes an account-menu record field');
  let component = byId.get(argument(field, 'value').fn);
  while (component && keyOf(component) !== 'view') component = byId.get(argument(component, 'map')?.fn);
  assert(component, 'account-menu has an ordinary retained view field');
  const value = component.args.find(arg => arg.name === 'value');
  assert.equal(value.expr.kind, 'closure');
  return {owner: component.id, slot: value.slot, target: value.expr.fn};
}
function successful(result, action) {
  assert(result.ok !== false && (!result.status || result.status < 400), action + ': ' + JSON.stringify(result));
  return result;
}
function slotsOf(graph, owner) {
  const slots = new Map(graph.slots.map(slot => [slot.id, slot]));
  return Object.fromEntries(graph['fn-slots'].filter(row => row['fn-id'] === owner)
    .map(row => [slots.get(row['slot-id']).name, row['slot-id']]));
}
async function checkedRoot(page) {
  // Wait only for the explicit pending type-recheck 409. Every checker/source
  // or ACL refusal fails immediately; this never substitutes an exported plan.
  let plan;
  const ready = await waitFor(async () => {
    const checked = await page.evaluate(async () => {
      const response = await authFetch(API.api_ui_root_plan, {cache: 'no-store'});
      if (response.status === 409) { await response.text(); return null; }
      const body = await response.json();
      if (!response.ok || body.ok !== true) throw new Error('Stored root export refused: HTTP ' + response.status);
      return body.plan;
    });
    if (!checked) return false;
    plan = checked;
    return true;
  }, 60000);
  assert(ready, 'stored root type recheck completed within 60 seconds');
  await page.evaluate(() => gdEditorRoot.reload());
  await page.waitForFunction(() => gdShellMenuGraph.ready && !!gdEditorRoot.componentNow('account-menu'));
  return plan;
}
async function viewTree(page) {
  return page.evaluate(() => {
    const g = GraphdenBrowser;
    const runtime = gdEditorRoot.componentNow('account-menu');
    const m = object => new Map(Object.entries(object).map(([key, value]) => [g.keyword(key), value]));
    const output = runtime.run('view', {state: runtime.run('initial', {}),
      theme: m({accent: '#336699', 'canvas-background': '#ffffff'}), server: m({phase: 'idle', label: '', 'branch-name': ''})});
    const plain = value => {
      if (Array.isArray(value) || g.isSequence(value)) return Array.from(value, plain);
      if (value instanceof Map) return Object.fromEntries([...value].map(([key, item]) => [key.name || key, plain(item)]));
      return value instanceof g.Keyword ? value.name : value;
    };
    const tree = plain(output.get(g.keyword('common-tree')));
    const replaceSettings = node => {
      if (!Array.isArray(node)) return false;
      if (node[0] === 'button' && node[1]?.['data-action'] === 'settings') {
        node.splice(2, node.length - 2, 'Persisted root settings');
        return true;
      }
      return node.some(replaceSettings);
    };
    if (!replaceSettings(tree)) throw new Error('No settings destination in actual view');
    return tree;
  });
}
(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [], custom = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  let branch, copy, activation, setup, mainBinding, shippedOwner;
  const createFn = async (parent, namespace, suffix, extra = {}) => {
    const name = 'root-' + suffix + '-' + randomUUID();
    successful(await branchApi('POST', '/api/entities/fn', new URLSearchParams({name,
      'parent-ids': parent, 'namespace-id': namespace, ...extra}).toString()), 'create user graph');
    const found = await branchApi('GET', '/api/graph/entities?scope=search&q=' + encodeURIComponent(name));
    const row = found.fns.find(row => row.name === name && row['namespace-id'] === namespace);
    assert(row?.id, 'exact user graph identity is readable');
    custom.push({id: row.id, name: row.name, 'namespace-id': namespace});
    return row;
  };
  const bind = async (fn, slot, fields) => {
    successful(await branchApi('POST', '/api/entities/binding', new URLSearchParams({
      'fn-id': fn, 'slot-id': slot, ...fields}).toString()), 'create user binding');
    const graph = await subtree(branchApi, fn);
    const row = graph.bindings.find(row => row['fn-id'] === fn && row['slot-id'] === slot);
    assert(row?.id, 'exact created binding is readable');
    return row;
  };
  try {
    branch = successful(await api(null, 'POST', '/api/branches', {name: branchName, 'base-branch-id': 'main'}), 'create isolated branch');
    await page.goto(BASE + '/?branch=' + encodeURIComponent(branchName) + '#core.logic.const');
    await page.waitForFunction(() => window.gdPrefsReady && window.gdPrefOwner && window.gdEditorRoot?.componentNow('account-menu'), null, {timeout: 120000});
    const originalPlan = await checkedRoot(page);
    shippedOwner = menuView(originalPlan).owner;
    const shipped = await subtree(mainApi, shippedOwner);
    mainBinding = shipped.bindings.find(row => row['fn-id'] === shippedOwner && row['slot-id'] === menuView(originalPlan).slot);
    assert(mainBinding?.['ref-fn-id'], 'shipped HOF reference is recorded read-only');
    await page.locator('#auth-lock-btn').click();
    const originalLabel = await page.locator('[role="menuitem"][data-action="settings"]').innerText();
    await page.keyboard.press('Escape');
    const replacementTree = await viewTree(page);

    setup = successful(await branchApi('POST', '/api/ui/configuration/prepare', {home: true}), 'prepare explicit personal home');
    const owner = await page.evaluate(() => gdPrefOwner);
    copy = successful(await branchApi('POST', '/api/ui/components/create/preview',
      {owner, 'namespace-id': null, component: 'account-menu'}), 'preview personal account menu');
    const applied = successful(await branchApi('POST', '/api/ui/components/create/apply', copy.request), 'apply exact copy receipt');
    assert.equal(applied.committed, true);
    assert.deepEqual(applied.manifest, copy.manifest, 'apply confirms the exact personal-copy receipt');
    const view = copy.manifest.roots['menu-id'];
    const graph = await subtree(branchApi, view);
    const parent = graph.fns.find(row => row.id === view);
    assert(copy.manifest.functions.some(row => row.id === view), 'view is owned by the exact personal-copy receipt');
    const viewSlots = slotsOf(graph, parent['parent-ids'][0]);
    const keysBinding = graph.bindings.find(row => row['fn-id'] === view && row['slot-id'] === viewSlots.keys);
    const valsBinding = graph.bindings.find(row => row['fn-id'] === view && row['slot-id'] === viewSlots.vals);
    const items = graph['list-items'];
    const treeKey = items.find(item => item['binding-id'] === keysBinding.id
      && ['common-tree', ':common-tree'].includes(item.value));
    assert(treeKey, 'view key sequence identifies the common-tree position');
    const vals = items.filter(item => item['binding-id'] === valsBinding.id).sort((a, b) => a.position - b.position);
    assert(vals.some(item => item.position === treeKey.position));
    const callbackA = await createFn(view, parent['namespace-id'], 'original-view', {'lambda-params': '[]'});
    const callbackB = await createFn(view, parent['namespace-id'], 'changed-view', {'lambda-params': '[]'});
    // A list binding appends to inherited items. Put the replacement values on
    // a fresh producer, then reference it from B so the inherited vals close.
    const list = originalPlan.primitives.find(primitive => primitive.op === 'list');
    assert(list, 'generic list ABI is present');
    const listSlots = slotsOf(await subtree(branchApi, list.id), list.id);
    const producer = await createFn(list.id, parent['namespace-id'], 'view-values');
    const overridden = await bind(producer.id, listSlots.items, {'list-append': 'true', 'list-closed': 'true'});
    assert.equal(overridden['list-append'], true, 'the fresh producer owns a sequence binding');
    for (const item of vals) {
      const field = item.position === treeKey.position ? {value: JSON.stringify(replacementTree)}
        : {'ref-fn-id': item['ref-fn-id']};
      assert(field.value || field['ref-fn-id'], 'every retained output comes from its actual source reference');
      successful(await branchApi('POST', '/api/entities/binding-list-item', new URLSearchParams({
        'binding-id': overridden.id, position: String(item.position), ...field}).toString()), 'create exact output sequence item');
    }
    const produced = await subtree(branchApi, producer.id);
    const ownItems = produced['list-items'].filter(item => item['binding-id'] === overridden.id)
      .sort((a, b) => a.position - b.position);
    assert.equal(ownItems.length, vals.length, 'replacement sequence has exactly the original output positions');
    for (let index = 0; index < vals.length; index++) {
      assert.equal(ownItems[index].position, vals[index].position);
      if (ownItems[index].position === treeKey.position) assert.deepEqual(ownItems[index].value, replacementTree);
      else assert.equal(ownItems[index]['ref-fn-id'], vals[index]['ref-fn-id']);
    }
    const replacement = await bind(callbackB.id, viewSlots.vals, {'ref-fn-id': producer.id});
    assert.equal(replacement['ref-fn-id'], producer.id, 'B replaces inherited vals with the fresh producer');
    const call = originalPlan.primitives.find(primitive => primitive.op === 'call');
    assert(call, 'generic call ABI is present');
    const callSlots = slotsOf(await subtree(branchApi, call.id), call.id);
    const wrapper = await createFn(call.id, parent['namespace-id'], 'view-call');
    const hof = await bind(wrapper.id, callSlots.func, {'ref-fn-id': callbackA.id});
    const arg = await bind(wrapper.id, callSlots.arg, {value: 'null'});
    assert.equal(arg['value-present'], true, 'the stored call argument is a pinned literal, including null');
    assert.equal(arg.value, null);
    assert.equal(arg['ref-fn-id'] ?? null, null);
    const callbacks = [];
    for (const callback of [callbackA, callbackB]) {
      const stored = (await subtree(branchApi, callback.id)).fns.find(row => row.id === callback.id);
      assert.deepEqual(stored['lambda-params'], [], 'the stored user callback explicitly captures all free inputs');
      callbacks.push({id: callback.id, 'lambda-params': stored['lambda-params']});
    }
    console.log('Stored user HOF fixture: ' + JSON.stringify({wrapper: wrapper.id, callbacks,
      arg: {slot: arg['slot-id'], 'value-present': arg['value-present'],
        'ref-fn-id': arg['ref-fn-id'] ?? null, 'value-is-null': arg.value === null}}));
    const manifest = await subtree(branchApi, copy.manifest.roots['configuration-id']);
    const viewIdentities = manifest.bindings.filter(row => row['ref-fn-id'] === view && row['type-override-fn-id']);
    assert.equal(viewIdentities.length, 1, 'personal manifest has one terminal view identity');
    assert(copy.manifest.functions.some(row => row.id === viewIdentities[0]['fn-id']), 'selection edit is user-owned');
    successful(await branchApi('PUT', '/api/entities/binding/' + viewIdentities[0].id,
      new URLSearchParams({'ref-fn-id': wrapper.id}).toString()), 'select user view wrapper in personal manifest');
    await page.evaluate(() => gdCaptureUIConfiguration(new AbortController().signal));
    const state = await page.evaluate(() => gdUIConfigurationState());
    const proposal = successful(await branchApi('POST', '/api/ui/configuration/preview', {operation: 'activate', command: {
      'selection-id': copy.manifest.roots['configuration-id'], 'expected-preference': state.preference,
      'expected-configuration-revision': state.revision}}), 'preview sanctioned personal selection');
    const activated = successful(await branchApi('POST', '/api/ui/configuration/apply', proposal.request), 'activate personal graph');
    assert.equal(activated.committed, true);
    activation = proposal;
    await page.evaluate(async () => { await loadGraphData(); gdRefreshUIConfiguration(); });
    const before = await checkedRoot(page);
    assert(before.functions.some(fn => fn.id === wrapper.id), 'actual root exporter includes selected ordinary user graph');
    assert.equal(argument(before.functions.find(fn => fn.id === wrapper.id), 'func').fn, callbackA.id);

    successful(await branchApi('PUT', '/api/entities/binding/' + hof.id,
      new URLSearchParams({'ref-fn-id': callbackB.id}).toString()), 'persist user-owned HOF reference');
    const stored = await subtree(branchApi, wrapper.id);
    assert.equal(stored.bindings.find(row => row.id === hof.id)['ref-fn-id'], callbackB.id);
    const changed = await checkedRoot(page);
    assert.equal(argument(changed.functions.find(fn => fn.id === wrapper.id), 'func').fn, callbackB.id,
      'actual checker/export returns the persisted HOF replacement');
    await page.locator('#auth-lock-btn').click();
    await page.getByRole('menuitem', {name: 'Persisted root settings', exact: true}).waitFor();
    await page.keyboard.press('Escape');
    successful(await branchApi('PUT', '/api/entities/binding/' + hof.id,
      new URLSearchParams({'ref-fn-id': callbackA.id}).toString()), 'restore original user HOF reference');
    await checkedRoot(page);
    await page.locator('#auth-lock-btn').click();
    await page.getByRole('menuitem', {name: originalLabel, exact: true}).waitFor();
    await page.keyboard.press('Escape');
    assert.deepEqual(errors, []);
  } finally {
    try {
      if (activation) assert.equal((await undoConfiguration(page, activation)).ok, true, 'exact activation receipt restores prior selection');
      await page.close();
      if (mainBinding) {
        const main = await subtree(mainApi, shippedOwner);
        assert.equal(main.bindings.find(row => row.id === mainBinding.id)['ref-fn-id'], mainBinding['ref-fn-id'], 'shipped main HOF binding was never changed');
      }
      const functions = [...(copy?.manifest.functions || []), ...custom];
      if (functions.length) {
        const removed = successful(await branchApi('POST', '/api/functions/delete-batch', {functions}), 'delete exact created graph cohort');
        const acknowledged = new Set([...(removed.deleted || []), ...(removed['already-absent'] || [])]);
        const expected = new Set(functions.map(row => row.id));
        assert.equal(acknowledged.size, expected.size);
        assert([...acknowledged].every(id => expected.has(id)), 'cleanup acknowledges only exact created identities');
      }
      for (const namespace of [...(copy?.manifest.namespaces || [])].reverse()) {
        const removed = await branchApi('DELETE', '/api/entities/ns/' + namespace.id);
        if (removed.status !== 404) successful(removed, 'delete exact copy namespace');
      }
      for (const id of setup?.['changed-namespaces'] || []) {
        assert.equal(id, setup.home.id, 'home cleanup uses only its explicit setup receipt');
        successful(await branchApi('DELETE', '/api/entities/ns/' + id), 'delete explicitly created empty home');
      }
      if (branch) {
        const exact = (await api(null, 'GET', '/api/branches')).branches.find(row => row.name === branchName);
        if (exact) successful(await api(null, 'DELETE', '/api/branches/' + exact.id), 'delete exact test branch');
        const remaining = (await api(null, 'GET', '/api/branches')).branches.find(row => row.name === branchName);
        assert.equal(remaining, undefined, 'the exact test branch is absent after cleanup');
      }
      console.log('Root fixture cleanup PASS: ' + JSON.stringify({branch: branchName,
        functions: functions.length, namespaces: copy?.manifest.namespaces.length || 0,
        activationRestored: !!activation, shippedBindingUnchanged: !!mainBinding}));
    } catch (error) {
      console.error('Root binding fixture cleanup requires review on branch ' + branchName);
      throw error;
    } finally { await browser.close(); }
  }
  console.log('PASS user-owned persisted HOF binding changes real root UI without package writes');
})().catch(error => { console.error(error); process.exitCode = 1; });
