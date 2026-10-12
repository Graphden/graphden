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
    await page.waitForFunction(() => window.gdPrefsReady && window.gdPrefOwner);
    const previous = await page.evaluate(() => ({components: gdPrefRead('components'), owner: gdPrefOwner}));
    const fixture = {previous};
    remember(fixture);
    assert(await page.evaluate(() => gdPrefWrite('components', null)), 'fixture selection reset commits');
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
    const value = {'fn-id': proposed.manifest.roots['configuration-id'], org: proposed.request.org,
      'branch-id': proposed.request['branch-id']};
    assert(await page.evaluate(({value, expected}) => gdPrefWrite('components', value, {expected}),
      {value, expected: proposed.manifest['expected-preference']}), 'picker-only activation commits');
    await page.waitForFunction(id => gdUIComponentRuntimeIdentity('fn-picker') === id,
      proposed.manifest.roots['configuration-id']);
    assert(await page.evaluate(() => gdUIComponentRuntimeIdentity('account-menu') === null && gdShellMenuGraph.ready),
      'the missing menu has a working builtin host');
    assert(pageErrors.length === 0, 'partial-manifest preparation has no page errors');
  } finally { page.off('pageerror', capturePageError); }
}

async function restorePickerOnlySelection(page, fixture) {
  if (!fixture) return;
  assert(await page.evaluate(value => gdPrefWrite('components', value), fixture.previous.components),
    'fixture restores its original authoritative selection before deletion');
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
