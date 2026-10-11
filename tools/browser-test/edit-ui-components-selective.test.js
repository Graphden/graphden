// Visible selective copies, retained authored identities, and a real cross-tab
// activation conflict. Every cleanup operation uses exact creation receipts.
const {unexpectedSelectiveCopyErrors} = require('./selective-copy-errors');
const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {selectCreatedLeaf, editOwnValue} = require('./tutorial-ui-components-helpers');

async function appearance(page) {
  if (!await page.locator('#gd-ui-components-root').isVisible()) {
    await page.locator('.auth-avatar:visible, #auth-lock-btn:visible').first().click();
    await page.locator('.auth-menu-item[data-item="Settings"]').click();
  }
  await page.locator('#gd-ui-components-root').waitFor({state: 'visible'});
}
async function copy(page, component, receipts) {
  await appearance(page);
  await page.locator('[data-ui-component="' + component + '"]').click();
  const picker = page.locator('.fn-picker-popover[aria-label="Pick a namespace"]');
  const preview = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/ui/components/create/preview'));
  const applied = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/ui/components/create/apply'));
  await picker.getByRole('option', {name: '(root)', exact: true}).click();
  const proposed = await (await preview).json();
  assert(proposed.ok && proposed.manifest.component === component, 'preview owns only the selected component');
  receipts.push({request: proposed.request, manifest: proposed.manifest});
  const result = await (await applied).json();
  assert(result.ok && result.committed && JSON.stringify(result.manifest) === JSON.stringify(proposed.manifest),
    'apply confirms the exact create-only receipt');
  await page.waitForFunction(() => !document.querySelector('#gd-ui-components-create').disabled);
  return proposed.manifest;
}
async function cleanup(page, receipts) {
  const functions = receipts.flatMap(receipt => receipt.manifest.functions);
  if (functions.length) {
    const result = await api(page, 'POST', '/api/functions/delete-batch', {functions},
      {'X-Graphden-Branch': receipts[0].request['branch-id']});
    const acknowledged = [...(result.deleted || []), ...(result['already-absent'] || [])];
    const expected = new Set(functions.map(row => row.id));
    assert(acknowledged.length === expected.size && new Set(acknowledged).size === expected.size
      && acknowledged.every(id => expected.has(id)), 'cleanup acknowledges only every exact newly created identity');
  }
  for (const receipt of [...receipts].reverse()) {
    for (const namespace of [...receipt.manifest.namespaces].reverse()) {
      const result = await api(page, 'DELETE', '/api/entities/ns/' + namespace.id, undefined,
        {'X-Graphden-Branch': receipt.request['branch-id']});
      assert(result.ok || result.status === 200 || result.status === 404, 'only the receipt namespace is removed');
    }
  }
}
(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const receipts = [];
  const failures = [];
  const replies = [], pendingReplies = [], requestSequences = new WeakMap();
  let sequence = 0;
  page.on('pageerror', error => failures.push({kind: 'pageerror', text: error.message}));
  page.on('console', message => { if (message.type() === 'error') failures.push({kind: 'console', text: message.text(), url: message.location().url}); });
  page.on('response', response => pendingReplies.push((async () => {
    const path = new URL(response.url()).pathname;
    if (response.request().method() !== 'POST' || !path.endsWith('/ui/components/plan')) return;
    const body = await response.json().catch(() => null);
    replies.push({kind: 'plan', url: response.url(), component: response.request().postDataJSON()?.component,
      sequence: requestSequences.get(response.request()), status: response.status(), code: body?.code,
      fallback: body?.fallback, retryable: body?.retryable === true, ok: body?.ok === true});
  })()));
  let previous;
  let conflict = null;
  let copies = 0;
  page.on('request', request => {
    requestSequences.set(request, ++sequence);
    if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/ui/components/create/preview')) copies++;
  });
  try {
    await page.goto(require('./edit-test-helpers').BASE);
    await page.waitForFunction(() => window.gdPrefsReady && window.gdPrefOwner);
    previous = await page.evaluate(() => ({components: gdPrefRead('components'), theme: gdPrefRead('theme'), owner: gdPrefOwner}));
    await appearance(page);
    if (previous.components) {
      await page.locator('#gd-ui-components-reset').click();
      await page.waitForFunction(() => gdPrefRead('components') === null);
    }
    const picker = await copy(page, 'fn-picker', receipts);
    await page.waitForFunction(id => gdUIComponentRuntimeIdentity('fn-picker') === id, picker.roots['configuration-id']);
    const firstPreference = await page.evaluate(() => gdPrefRead('components'));
    assert(await page.evaluate(() => gdUIComponentRuntimeIdentity('account-menu') === null && gdShellMenuGraph.ready),
      'missing menu override keeps the builtin component available');
    await appearance(page);
    await page.locator('[data-ui-component="fn-picker"]').click();
    await page.waitForFunction(id => selectedFnId === id, picker.roots['picker-id']);
    assert(copies === 1, 'existing group opens its exact identity without another copy');
    const leaf = await selectCreatedLeaf(page, picker, 'picker', 'picker-active-background');
    await editOwnValue(page, leaf, 'value', 'rgb(254, 215, 170)');
    const recents = await copy(page, 'recents', receipts);
    await page.waitForFunction(id => gdUIComponentRuntimeIdentity('recents') === id, recents.roots['configuration-id']);
    assert(await page.evaluate(id => gdUIComponentsRoots()['picker-id'] === id, picker.roots['picker-id']),
      'adding Recents retains the authored picker view identity');
    assert(await page.evaluate(id => graphData.bindings.some(row => row['fn-id'] === id && row.value === 'rgb(254, 215, 170)'), leaf),
      'adding Recents preserves the edited picker value');
    const ownedPicker = new Set(picker.functions.map(row => row.id));
    assert(recents.functions.every(row => !ownedPicker.has(row.id)), 'new receipt excludes all retained picker identities');
    assert(await page.evaluate(value => JSON.stringify(gdPrefRead('theme')) === JSON.stringify(value), previous.theme),
      'selective copies do not activate a theme');

    const inspector = await copy(page, 'inspector', receipts);
    await page.waitForFunction(id => gdUIComponentRuntimeIdentity('inspector') === id, inspector.roots['configuration-id']);
    assert(await page.evaluate(id => gdUIComponentsRoots()['picker-id'] === id, picker.roots['picker-id']),
      'adding Inspector retains the authored picker identity');
    assert(await page.evaluate(id => graphData.bindings.some(row => row['fn-id'] === id && row.value === 'rgb(254, 215, 170)'), leaf),
      'adding Inspector preserves the edited picker leaf');
    const retained = new Set([...picker.functions, ...recents.functions].map(row => row.id));
    assert(inspector.functions.every(row => !retained.has(row.id)), 'Inspector receipt excludes both retained component closures');
    assert(await page.evaluate(value => JSON.stringify(gdPrefRead('theme')) === JSON.stringify(value), previous.theme),
      'Inspector copy leaves the active theme unchanged');

    const prefPath = await page.evaluate(() => new URL(API.api_prefs_key('components'), location.href).pathname);
    const preferenceRoute = url => url.pathname === prefPath;
    await page.route(preferenceRoute, async route => {
      if (route.request().method() !== 'PUT' || !route.request().postDataJSON()?.expected) {
        await route.continue(); return;
      }
      // A second writer uses the real preference API while this tab's
      // conditional activation is paused; no response is synthesized.
      const external = await api(page, 'PUT', prefPath, {owner: previous.owner, value: firstPreference});
      assert(external.ok === true, 'another tab commits its newer selection');
      const response = await route.fetch();
      conflict = {status: response.status(), body: await response.json()};
      replies.push({kind: 'activation', url: route.request().url(), status: conflict.status,
        code: conflict.body.reason, noValue: !Object.hasOwn(conflict.body, 'value')});
      await route.fulfill({response});
    });
    const menu = await copy(page, 'account-menu', receipts);
    await page.unroute(preferenceRoute);
    assert(conflict?.status === 409 && conflict.body.reason === 'stale-preference'
      && !Object.hasOwn(conflict.body, 'value'), 'activation CAS rejects the stale selection without leaking its value');
    await page.waitForFunction(id => gdPrefRead('components')['fn-id'] === id, picker.roots['configuration-id']);
    const index = await api(page, 'GET', '/api/graph/entities?scope=index');
    const live = new Set(index.fns.map(row => row.id));
    assert(menu.functions.every(row => live.has(row.id)), 'activation conflict retains every newly created menu graph');
    assert(await page.evaluate(value => JSON.stringify(gdPrefRead('theme')) === JSON.stringify(value), previous.theme),
      'creating a menu also leaves the active theme unchanged');
    assert(await page.locator('#gd-ui-components-status').textContent().then(text => text.includes('were created')),
      'the editor explains that unselected graphs remain available');
    await cleanup(page, [receipts.pop()]);
    const remaining = await api(page, 'GET', '/api/graph/entities?scope=index');
    const remainingIds = new Set(remaining.fns.map(row => row.id));
    assert([...picker.functions, ...recents.functions, ...inspector.functions].every(row => remainingIds.has(row.id)),
      'cleaning the new receipt preserves all previously authored copies');
    await Promise.all(pendingReplies);
    assert(unexpectedSelectiveCopyErrors(failures, replies, true).length === 0, 'no unclassified browser errors');
    console.log('PASS selective UI choices, authored retention, builtin fallback, real CAS conflict, exact cleanup');
  } finally {
    try {
      await page.unrouteAll({behavior: 'wait'});
      if (previous) await api(page, 'PUT', '/api/prefs/components', {owner: previous.owner, value: previous.components});
      await cleanup(page, receipts);
    } finally {
      await browser.close();
    }
  }
})().catch(() => {console.error('FAIL selective UI proof; details withheld'); process.exitCode = 1;});
