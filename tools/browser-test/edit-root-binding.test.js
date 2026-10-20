// Persisted HOF binding -> checked/source-authorized endpoint -> real editor UI.
// Run against an isolated combined B instance; every write is on one exact
// throwaway branch. No request interception or browser-plan substitution.
'use strict';
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');
const {newContext, api, BASE, waitForServerHealthy} = require('./edit-test-helpers');
const branchName = 'root-binding-' + randomUUID();
const childName = 'root-view-' + randomUUID();
const branchApi = (method, path, body) => api(null, method, path, body, {'X-Graphden-Branch': branchName});
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
async function checkedRoot(page) {
  // CRUD may leave a bounded asynchronous type recheck pending. Wait only for
  // its explicit 409; every checker/source/ACL refusal fails immediately.
  await page.waitForFunction(async () => {
    const response = await authFetch(API.api_ui_root_plan, {cache: 'no-store'});
    if (response.status === 409) return false;
    const body = await response.json();
    if (!response.ok || body.ok !== true) throw new Error('Stored root export refused: HTTP ' + response.status);
    return true;
  }, null, {polling: 1000, timeout: 60000});
  const [response] = await Promise.all([
    page.waitForResponse(response => response.url().includes('/api/ui/root/plan') && response.request().method() === 'GET'),
    page.evaluate(() => gdEditorRoot.reload()),
  ]);
  const body = await response.json();
  assert.equal(response.status(), 200);
  assert.equal(body.ok, true);
  await page.waitForFunction(() => gdShellMenuGraph.ready && !!gdEditorRoot.componentNow('account-menu'));
  return body.plan;
}
(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  let branch, binding, child, valueBinding;
  try {
    branch = successful(await api(null, 'POST', '/api/branches', {name: branchName, 'base-branch-id': 'main'}), 'create isolated branch');
    await page.goto(BASE + '/?branch=' + encodeURIComponent(branchName) + '#core.logic.const');
    await page.waitForFunction(() => typeof gdEditorRoot !== 'undefined' && !!gdEditorRoot.componentNow('account-menu'), null, {timeout: 120000});
    const originalPlan = await checkedRoot(page);
    const selected = menuView(originalPlan);
    const graph = await subtree(branchApi, selected.owner);
    binding = graph.bindings.find(row => row['fn-id'] === selected.owner && row['slot-id'] === selected.slot);
    assert(binding?.['ref-fn-id'], 'returned callable field has an actual stored ref binding');
    const originalTarget = binding['ref-fn-id'];
    const parent = graph.fns.find(row => row.id === originalTarget);
    assert(parent, 'view binding resolves to a stored ordinary graph');
    const parentNode = originalPlan.functions.find(fn => fn.id === originalTarget);
    assert(parentNode, 'stored view source is present in the checked plan');
    const valueSlot = parentNode.args.find(arg => arg.name === 'value')?.slot;
    assert(valueSlot, 'view inherits the generic assoc value slot');
    const mainBefore = await subtree((method, path, body) => api(null, method, path, body, {'X-Graphden-Branch': 'main'}), selected.owner);
    const mainBinding = mainBefore.bindings.find(row => row.id === binding.id);
    assert.equal(mainBinding?.['ref-fn-id'], originalTarget, 'branch starts with the unchanged main binding');

    await page.locator('#auth-lock-btn').click();
    const originalLabel = await page.locator('[role="menuitem"][data-action="settings"]').innerText();
    await page.keyboard.press('Escape');
    const tree = await page.evaluate(() => {
      const g = GraphdenBrowser;
      const runtime = gdEditorRoot.componentNow('account-menu');
      const m = object => new Map(Object.entries(object).map(([key, value]) => [g.keyword(key), value]));
      const output = runtime.run('view', {state: runtime.run('initial', {}),
        theme: m({accent: '#336699', 'canvas-background': '#ffffff'}), server: m({phase: 'idle', label: '', 'branch-name': ''})});
      // Hiccup tag keywords become equivalent tag strings. Attribute maps keep
      // their native action identities; only the visible text child changes.
      const plain = value => {
        if (g.isSequence(value)) return Array.from(value, plain);
        if (value instanceof Map) return Object.fromEntries([...value].map(([key, item]) => [key.name || key, plain(item)]));
        if (value instanceof g.Keyword) return value.name;
        return value;
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
    assert(JSON.stringify(tree).includes('Persisted root settings'));
    successful(await branchApi('POST', '/api/entities/fn', new URLSearchParams({name: childName,
      'parent-ids': originalTarget, 'namespace-id': parent['namespace-id'] || ''}).toString()), 'create ordinary view descendant');
    const created = await branchApi('GET', '/api/graph/entities?scope=search&q=' + encodeURIComponent(childName));
    child = created.fns.find(row => row.name === childName);
    assert(child?.id, 'exact created descendant identity is readable');
    successful(await branchApi('POST', '/api/entities/binding', new URLSearchParams({
      'fn-id': child.id, 'slot-id': valueSlot, value: JSON.stringify(tree)}).toString()), 'store alternate Hiccup value');
    const own = await subtree(branchApi, child.id);
    valueBinding = own.bindings.find(row => row['fn-id'] === child.id && row['slot-id'] === valueSlot);
    assert(valueBinding?.id, 'exact created literal binding is readable');

    successful(await branchApi('PUT', '/api/entities/binding/' + binding.id,
      new URLSearchParams({'ref-fn-id': child.id}).toString()), 'persist the root component HOF reference');
    const stored = await subtree(branchApi, selected.owner);
    assert.equal(stored.bindings.find(row => row.id === binding.id)?.['ref-fn-id'], child.id);
    const changedPlan = await checkedRoot(page);
    assert.equal(menuView(changedPlan).target, child.id, 'real checker/export returns the newly stored HOF binding');
    await page.locator('#auth-lock-btn').click();
    const changed = page.getByRole('menuitem', {name: 'Persisted root settings', exact: true});
    await changed.waitFor();
    assert.equal(await changed.isVisible(), true, 'actual editor renders the stored replacement view');
    await page.keyboard.press('Escape');

    successful(await branchApi('PUT', '/api/entities/binding/' + binding.id,
      new URLSearchParams({'ref-fn-id': originalTarget}).toString()), 'restore the original root reference');
    const restored = await checkedRoot(page);
    assert.equal(menuView(restored).target, selected.target);
    await page.locator('#auth-lock-btn').click();
    await page.getByRole('menuitem', {name: originalLabel, exact: true}).waitFor();
    await page.keyboard.press('Escape');
    const mainAfter = await subtree((method, path, body) => api(null, method, path, body, {'X-Graphden-Branch': 'main'}), selected.owner);
    assert.equal(mainAfter.bindings.find(row => row.id === binding.id)?.['ref-fn-id'], mainBinding['ref-fn-id']);
    assert.deepEqual(errors, []);
  } finally {
    await page.close();
    try {
      if (binding) successful(await branchApi('PUT', '/api/entities/binding/' + binding.id,
        new URLSearchParams({'ref-fn-id': binding['ref-fn-id']}).toString()), 'cleanup restores exact original reference');
      if (valueBinding) successful(await branchApi('DELETE', '/api/entities/binding/' + valueBinding.id), 'cleanup removes created value binding');
      if (child) successful(await branchApi('DELETE', '/api/entities/fn/' + child.id), 'cleanup removes created descendant');
      if (branch) {
        const rows = await api(null, 'GET', '/api/branches');
        const exact = rows.branches.find(row => row.name === branchName);
        if (exact) successful(await api(null, 'DELETE', '/api/branches/' + exact.id), 'cleanup deletes exact test branch');
      }
    } finally { await browser.close(); }
  }
  console.log('PASS persisted root HOF binding changes real editor UI and restores branch state');
})().catch(error => { console.error(error); process.exitCode = 1; });
