'use strict';
const {assert, api, BASE} = require('./edit-test-helpers');

// A controlled prior partial manifest makes lesson25's restore/fallback
// contract independent of which editor spec happened to run before it.
async function preparePickerOnlySelection(page, remember) {
  const pageErrors = [];
  const capturePageError = () => pageErrors.push(true);
  page.on('pageerror', capturePageError);
  try {
    await page.goto(BASE);
    await page.waitForFunction(() => window.gdPrefsReady && window.gdPrefOwner && window.gdUIConfigurationState?.()?.revision);
    const previous = await page.evaluate(() => ({components: gdPrefRead('components'), owner: gdPrefOwner,
      menu: gdUIComponentsRoots()['menu-id']}));
    const fixture = {previous};
    remember(fixture);
    assert((await api(page, 'POST', '/api/ui/configuration/prepare', {home: true})).ok, 'fixture home setup succeeds');
    await page.evaluate(() => gdCaptureUIConfiguration());
    const destinationPath = process.env.GRAPHDEN_COMPONENT_NAMESPACE || '(root)';
    const destinationIds = await page.evaluate(path => path === '(root)' ? [null]
      : [...lookups.nsPathMap].filter(([, value]) => value === path).map(([id]) => id), destinationPath);
    assert(destinationIds.length === 1, 'fixture destination resolves one authorized namespace');
    const proposed = await api(page, 'POST', '/api/ui/components/create/preview',
      {owner: previous.owner, 'namespace-id': destinationIds[0], component: 'fn-picker'});
    assert(proposed.ok, 'picker-only fixture preview succeeds');
    fixture.receipt = {request: proposed.request, manifest: proposed.manifest};
    remember(fixture);
    const created = await api(page, 'POST', '/api/ui/components/create/apply', proposed.request);
    assert(created.ok && created.committed, 'picker-only fixture create commits');
    const state = await page.evaluate(() => gdUIConfigurationState());
    const activation = await api(page, 'POST', '/api/ui/configuration/preview', {operation: 'activate',
      command: {'selection-id': proposed.manifest.roots['configuration-id'],
        'expected-preference': state.preference, 'expected-configuration-revision': state.revision}});
    assert(activation.ok && activation.receipt.created, 'partial manifest activates through an ordinary editor graph');
    fixture.activation = activation;
    remember(fixture);
    const activated = await api(page, 'POST', '/api/ui/configuration/apply', activation.request);
    assert(activated.ok && activated.committed, 'ordinary fixture activation commits');
    await page.evaluate(() => gdRefreshUIConfiguration());
    await page.waitForFunction(({root, picker}) => gdUIComponentRuntimeIdentity('fn-picker') === root
      && gdUIComponentsRoots()['picker-id'] === picker,
    {root: activated.id, picker: proposed.manifest.roots['picker-id']});
    await page.waitForFunction(({root, menu}) => gdShellMenuGraph.ready
      && gdUIComponentRuntimeIdentity('account-menu') === root
      && gdUIComponentsRoots()['menu-id'] === menu, {root: activated.id, menu: previous.menu});
    assert(await page.evaluate(menu => gdShellMenuGraph.ready
      && gdUIComponentsRoots()['menu-id'] === menu, previous.menu),
    'the partial manifest receives its shared menu through graph composition');
    assert(pageErrors.length === 0, 'partial-manifest preparation has no page errors');
  } finally { page.off('pageerror', capturePageError); }
}

async function restorePickerOnlySelection(page, fixture) {
  if (!fixture) return;
  if (fixture.activation) {
    const receipt = fixture.activation.receipt;
    const restored = await api(page, 'POST', '/api/ui/configuration/undo', {
      'current-id': receipt.id, 'expected-configuration-revision': receipt['configuration-revision'],
      'cohort-revision': receipt['cohort-revision'], previous: receipt.previous,
      functions: receipt['function-receipts']},
    {'X-Graphden-Branch': fixture.activation.request.scope['branch-id']});
    assert(restored.ok, 'fixture restores its original ordinary configuration atomically');
    await page.evaluate(() => gdRefreshUIConfiguration());
  }
  if (!fixture.receipt) return;
  const {request, manifest} = fixture.receipt;
  const headers = {'X-Graphden-Branch': request['branch-id']};
  const result = await api(page, 'POST', '/api/functions/delete-batch', {functions: manifest.functions}, headers);
  const acknowledged = [...(result.deleted || []), ...(result['already-absent'] || [])];
  const expected = new Set(manifest.functions.map(row => row.id));
  assert(acknowledged.length === expected.size && new Set(acknowledged).size === expected.size
    && acknowledged.every(id => expected.has(id)), 'fixture cleanup acknowledges exactly its own created functions');
  for (const namespace of [...manifest.namespaces].reverse()) {
    const removed = await api(page, 'DELETE', '/api/entities/ns/' + namespace.id, undefined, headers);
    assert(removed.ok || removed.status === 200 || removed.status === 404, 'fixture removes only its receipt namespaces');
  }
}

module.exports = {preparePickerOnlySelection, restorePickerOnlySelection};
