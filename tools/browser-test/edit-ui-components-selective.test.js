// Compatibility selective copies, retained authored identities, and a real cross-tab
// activation conflict. Every cleanup operation uses exact creation receipts.
const {unexpectedSelectiveCopyErrors} = require('./selective-copy-errors');
const {unexpectedThemeRetryErrors} = require('./theme-retry-errors');
const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');
const {undoConfiguration} = require('./configuration-undo-test-helpers');
const {selectCreatedLeaf, editOwnValue} = require('./tutorial-ui-components-helpers');

function themeSelection(value) {
  const normalize = item => Array.isArray(item) ? item.map(normalize)
    : item && typeof item === 'object' ? Object.fromEntries(Object.keys(item).sort().map(key => [key, normalize(item[key])])) : item;
  const selected = structuredClone(value);
  if (selected?.graph) { delete selected.graph.name; delete selected.graph.qname; }
  return JSON.stringify(normalize(selected));
}
async function unchangedTheme(page, previous) {
  return themeSelection(await page.evaluate(() => gdPrefRead('theme'))) === themeSelection(previous);
}

async function appearance(page) {
  if (!await page.locator('#gd-ui-components-root').isVisible()) {
    await page.locator('.auth-avatar:visible, #auth-lock-btn:visible').first().click();
    await page.locator('.auth-menu-item[data-item="Settings"]').click();
  }
  await page.locator('#gd-ui-components-root').waitFor({state: 'visible'});
}
async function activate(page, selectionId) {
  await page.evaluate(() => gdCaptureUIConfiguration());
  const state = await page.evaluate(() => gdUIConfigurationState());
  const proposal = await api(page, 'POST', '/api/ui/configuration/preview', {operation: 'activate',
    command: {'selection-id': selectionId, 'expected-preference': state.preference,
      'expected-configuration-revision': state.revision}});
  assert(proposal.ok, 'compatibility selection receives an ordinary activation preview');
  const result = await api(page, 'POST', '/api/ui/configuration/apply', proposal.request);
  assert(result.ok && result.committed, 'compatibility selection activates by reference');
  await page.evaluate(async () => { await loadGraphData(); gdRefreshUIConfiguration(); });
  return proposal;
}
async function copy(page, component, receipts) {
  const owner = await page.evaluate(() => gdPrefOwner);
  const proposed = await api(page, 'POST', '/api/ui/components/create/preview',
    {owner, 'namespace-id': null, component});
  assert(proposed.ok && proposed.manifest.component === component, 'compatibility preview owns only the selected component');
  const receipt = {request: proposed.request, manifest: proposed.manifest};
  receipts.push(receipt);
  const result = await api(page, 'POST', '/api/ui/components/create/apply', proposed.request);
  assert(result.ok && result.committed && JSON.stringify(result.manifest) === JSON.stringify(proposed.manifest),
    'apply confirms the exact create-only receipt');
  receipt.activation = await activate(page, proposed.manifest.roots['configuration-id']);
  await page.waitForFunction(({root, component}) => gdUIComponentRuntimeIdentity(component) === root
    && gdUIComponentRuntimeIdentity('account-menu') === root && gdShellMenuGraph.ready,
  {root: receipt.activation.receipt.id, component});
  return {...proposed.manifest, activationId: receipt.activation.receipt.id};
}
async function editPickerAndSettle(page, leaf, value) {
  // Save detaches the form before its refresh publishes graph-changed. Menu
  // reauthorization deliberately closes an open menu; observe that actual
  // completion before starting a new Settings gesture, rather than sleeping.
  await page.evaluate(({leaf, value}) => {
    const menu = gdShellMenuGraph;
    const original = menu.reload;
    const proof = {started: 0, finished: 0, after: null};
    const changed = () => {
      if (graphData.bindings.some(row => row['fn-id'] === leaf && row.value === value)) proof.after = proof.started;
    };
    menu.reload = async function(...args) {
      const own = ++proof.started;
      try { return await original.apply(this, args); }
      finally { if (own === proof.started) proof.finished = own; }
    };
    addEventListener('gd-graph-changed', changed);
    window.selectiveEditRefresh = {proof, restore() { menu.reload = original; removeEventListener('gd-graph-changed', changed); }};
  }, {leaf, value});
  try {
    await editOwnValue(page, leaf, 'value', value);
    await page.waitForFunction(() => {
      const proof = selectiveEditRefresh.proof;
      return proof.after !== null && proof.started > proof.after && proof.finished === proof.started && gdShellMenuGraph.ready;
    });
  } finally {
    await page.evaluate(() => { selectiveEditRefresh.restore(); delete window.selectiveEditRefresh; });
  }
}

async function cleanup(page, receipts) {
  for (const receipt of [...receipts].reverse()) {
    if (!receipt.activation) continue;
    const undone = await undoConfiguration(page, receipt.activation);
    assert(undone.ok, 'compatibility cleanup restores each original root in LIFO order');
  }
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
  const replies = [], themeReplies = [], pendingReplies = [], requestSequences = new WeakMap();
  let sequence = 0;
  page.on('pageerror', error => failures.push({kind: 'pageerror', text: error.message}));
  page.on('console', message => { if (message.type() === 'error') failures.push({kind: 'console', text: message.text(), url: message.location().url}); });
  page.on('response', response => pendingReplies.push((async () => {
    const path = new URL(response.url()).pathname;
    if (response.request().method() === 'POST' && path.endsWith('/ui/theme/evaluate')) {
      const body = await response.json().catch(() => null);
      const command = response.request().postDataJSON();
      themeReplies.push({url: response.url(), sequence: requestSequences.get(response.request()),
        identity: typeof command?.['fn-id'] === 'string' && typeof command?.org === 'string'
          && typeof command?.owner === 'string' && typeof response.request().headers()['x-graphden-branch'] === 'string'
          ? JSON.stringify([command['fn-id'], command.org, command.owner, response.request().headers()['x-graphden-branch']]) : null,
        status: response.status(), code: body?.code, reason: body?.reason,
        retryable: body?.retryable === true, ok: body?.ok === true, payload: !!body?.payload});
      return;
    }
    if (response.request().method() !== 'POST' || !path.endsWith('/ui/configuration/plan')) return;
    const body = await response.json().catch(() => null);
    replies.push({kind: 'plan', url: response.url(), component: response.request().postDataJSON()?.component,
      sequence: requestSequences.get(response.request()), status: response.status(), code: body?.code,
      fallback: body?.fallback, retryable: body?.retryable === true, ok: body?.ok === true});
  })()));
  let previous;
  let phase = 'boot';
  page.on('request', request => {
    requestSequences.set(request, ++sequence);
  });
  try {
    await page.goto(require('./edit-test-helpers').BASE);
    await page.waitForFunction(() => window.gdPrefsReady && window.gdPrefOwner);
    await page.waitForFunction(() => gdUIConfigurationState()?.revision);
    assert((await api(page, 'POST', '/api/ui/configuration/prepare', {home: true})).ok, 'explicit home setup succeeds');
    previous = await page.evaluate(() => ({components: gdPrefRead('components'), theme: gdPrefRead('theme'), owner: gdPrefOwner}));
    await appearance(page);
    phase = 'copy-picker';
    const picker = await copy(page, 'fn-picker', receipts);
    phase = 'runtime-picker';
    await page.waitForFunction(id => gdUIComponentRuntimeIdentity('fn-picker') === id, picker.activationId);
    assert(await page.evaluate(() => gdShellMenuGraph.ready && gdUIComponentsRoots()['menu-id']),
      'partial compatibility manifests receive a working shared menu through graph composition');
    await page.evaluate(async id => { await ensureSubtreeFor(id); gdShellSurface('build'); selectFn(id); }, picker.roots['picker-id']);
    await page.waitForFunction(id => selectedFnId === id, picker.roots['picker-id']);
    const leaf = await selectCreatedLeaf(page, picker, 'picker', 'picker-active-background');
    await editPickerAndSettle(page, leaf, 'rgb(254, 215, 170)');
    phase = 'copy-recents';
    const recents = await copy(page, 'recents', receipts);
    phase = 'runtime-recents';
    await page.waitForFunction(id => gdUIComponentRuntimeIdentity('recents') === id, recents.activationId);
    assert(await page.evaluate(id => gdUIComponentsRoots()['picker-id'] === id, picker.roots['picker-id']),
      'adding Recents retains the authored picker view identity');
    assert(await page.evaluate(id => graphData.bindings.some(row => row['fn-id'] === id && row.value === 'rgb(254, 215, 170)'), leaf),
      'adding Recents preserves the edited picker value');
    const ownedPicker = new Set(picker.functions.map(row => row.id));
    assert(recents.functions.every(row => !ownedPicker.has(row.id)), 'new receipt excludes all retained picker identities');
    assert(await unchangedTheme(page, previous.theme),
      'selective copies do not activate a theme');

    phase = 'copy-inspector';
    const inspector = await copy(page, 'inspector', receipts);
    phase = 'runtime-inspector';
    await page.waitForFunction(id => gdUIComponentRuntimeIdentity('inspector') === id, inspector.activationId);
    assert(await page.evaluate(id => gdUIComponentsRoots()['picker-id'] === id, picker.roots['picker-id']),
      'adding Inspector retains the authored picker identity');
    assert(await page.evaluate(id => graphData.bindings.some(row => row['fn-id'] === id && row.value === 'rgb(254, 215, 170)'), leaf),
      'adding Inspector preserves the edited picker leaf');
    const retained = new Set([...picker.functions, ...recents.functions].map(row => row.id));
    assert(inspector.functions.every(row => !retained.has(row.id)), 'Inspector receipt excludes both retained component closures');
    assert(await unchangedTheme(page, previous.theme),
      'Inspector copy leaves the active theme unchanged');

    phase = 'copy-explorer';
    const explorer = await copy(page, 'explorer', receipts);
    phase = 'runtime-explorer';
    await page.waitForFunction(id => gdUIComponentRuntimeIdentity('explorer') === id, explorer.activationId);
    assert(await page.evaluate(id => gdUIComponentsRoots()['picker-id'] === id, picker.roots['picker-id']),
      'adding the Explorer toolbar retains the authored picker identity');
    assert(await page.evaluate(id => graphData.bindings.some(row => row['fn-id'] === id && row.value === 'rgb(254, 215, 170)'), leaf),
      'adding the Explorer toolbar preserves the edited picker leaf');
    const existing = new Set([...picker.functions, ...recents.functions, ...inspector.functions].map(row => row.id));
    assert(explorer.functions.every(row => !existing.has(row.id)), 'Explorer receipt contains only new graph identities');
    assert(await unchangedTheme(page, previous.theme),
      'Explorer copy leaves the active theme unchanged');
    await page.evaluate(() => gdShellSurface('build'));
    await page.locator('#kind-filters [data-kind="types"]').waitFor({state: 'visible'});
    await page.locator('#kind-filters [data-kind="types"]').click();
    assert(await page.locator('#kind-filters [data-kind="types"]').getAttribute('aria-pressed') === 'true',
      'the stored personal Explorer update executes its finite filter request');
    await page.locator('#kind-filters [data-kind="all"]').click();
    assert(await page.evaluate(() => !gdFiltersActive()), 'the stored personal All request preserves the native clear semantics');

    phase = 'copy-menu-conflict';
    const owner = previous.owner;
    const proposed = await api(page, 'POST', '/api/ui/components/create/preview',
      {owner, 'namespace-id': null, component: 'account-menu'});
    assert(proposed.ok, 'unselected menu copy previews');
    const menuReceipt = {request: proposed.request, manifest: proposed.manifest};
    receipts.push(menuReceipt);
    const created = await api(page, 'POST', '/api/ui/components/create/apply', proposed.request);
    assert(created.ok && created.committed, 'menu graphs exist before activation');
    const menu = proposed.manifest;
    await page.evaluate(() => gdCaptureUIConfiguration());
    const current = await page.evaluate(() => gdUIConfigurationState());
    const stale = await api(page, 'POST', '/api/ui/configuration/preview', {operation: 'activate', command: {
      'selection-id': menu.roots['configuration-id'], 'expected-preference': current.preference,
      'expected-configuration-revision': current.revision}});
    assert(stale.ok, 'the first tab previews menu activation');
    const external = await activate(page, picker.roots['configuration-id']);
    menuReceipt.activation = external;
    const rejected = await api(page, 'POST', '/api/ui/configuration/apply', stale.request);
    assert(rejected.status === 409, 'a stale ordinary-root activation cannot overwrite the second tab');
    const index = await api(page, 'GET', '/api/graph/entities?scope=index');
    const live = new Set(index.fns.map(row => row.id));
    assert(menu.functions.every(row => live.has(row.id)), 'activation conflict retains every newly created menu graph');
    assert(await unchangedTheme(page, previous.theme),
      'creating a menu also leaves the active theme unchanged');
    await cleanup(page, [receipts.pop()]);
    const remaining = await api(page, 'GET', '/api/graph/entities?scope=index');
    const remainingIds = new Set(remaining.fns.map(row => row.id));
    assert([...picker.functions, ...recents.functions, ...inspector.functions, ...explorer.functions].every(row => remainingIds.has(row.id)),
      'cleaning the new receipt preserves all previously authored copies');
    await Promise.all(pendingReplies);
    const unexpected = unexpectedSelectiveCopyErrors(unexpectedThemeRetryErrors(failures, themeReplies, true), replies, true);
    if (unexpected.length) console.error('Unclassified selective errors:', JSON.stringify({
      errors: unexpected.map(error => ({kind: error.kind, status: Number(error.text?.match(/status of (\d+)/)?.[1]) || null,
        path: error.url ? new URL(error.url).pathname : null})),
      replies: replies.map(({kind, component, status, code, retryable, ok, sequence}) => ({kind, component, status, code, retryable, ok, sequence})),
    }));
    assert(unexpected.length === 0, 'no unclassified browser errors');
    console.log('PASS selective UI choices, authored retention, builtin fallback, real CAS conflict, exact cleanup');
  } catch (error) {
    const state = await page.evaluate(() => ({surface: document.body.dataset.surface,
      catalogVisible: !!document.getElementById('gd-ui-components-root')?.getClientRects().length,
      menuVisible: !!document.querySelector('.auth-menu-item[data-item="Settings"]')?.getClientRects().length,
      pickerVisible: !!document.querySelector('.fn-picker-popover')?.getClientRects().length,
      ready: !!gdPrefsReady, authenticated: isAuthenticated()})).catch(() => null);
    console.error('Selective phase diagnostic:', JSON.stringify({phase, kind: require('./safe-diagnostics').errorKind(error), state,
      replies: replies.map(({component, status, code, retryable, ok}) => ({component, status, code, retryable, ok}))}));
    throw error;
  } finally {
    try {
      await page.unrouteAll({behavior: 'wait'});
      await cleanup(page, receipts);
    } finally {
      await browser.close();
    }
  }
})().catch(error => {console.error('FAIL selective UI proof;', require('./safe-diagnostics').errorKind(error), '; details withheld'); process.exitCode = 1;});
