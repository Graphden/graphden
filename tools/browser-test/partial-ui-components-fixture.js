'use strict';
const {assert, api, BASE} = require('./edit-test-helpers');
const {undoConfiguration} = require('./configuration-undo-test-helpers');

// A controlled prior partial manifest makes lesson25's restore/fallback
// contract independent of which editor spec happened to run before it.
async function preparePickerOnlySelection(page, remember, homeSetup) {
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
    assert((await homeSetup.prepare()).ok, 'fixture home setup succeeds');
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

// A failed lesson can still own the active selection. Restore its exact
// committed cohort before undoing the outer picker fixture that it replaced.
async function restoreLessonSelection(page, lesson) {
  if (!lesson) return;
  const {request, original} = lesson;
  const rows = original['function-receipts'];
  assert(original.created === true && rows.length > 0
    && new Set(rows.map(row => row.id)).size === rows.length
    && rows.some(row => row.id === original.id), 'lesson cleanup has an exact created cohort');
  const context = await page.evaluate(() => ({owner: gdPrefOwner,
    org: typeof graphdenCurrentOrg === 'string' && graphdenCurrentOrg ? graphdenCurrentOrg : 'public',
    branch: getCurrentBranchName()}));
  assert(request.scope['principal-id'] === context.owner && request.scope.org === context.org
    && lesson['branch-name'] === context.branch, 'lesson cleanup retains its owner, organization and branch');
  const entities = await api(page, 'GET', '/api/graph/entities?scope=index', undefined,
    {'X-Graphden-Branch': request.scope['branch-id']});
  const byId = new Map(entities.fns.map(row => [row.id, row]));
  const command = {'current-id': original.id,
    'expected-configuration-revision': original['configuration-revision'],
    'cohort-revision': original['cohort-revision'], previous: original.previous, functions: rows};
  const proof = {receipt: command, scope: request.scope, 'restore-revision': original['restore-revision']};
  const headers = {'X-Graphden-Branch': request.scope['branch-id']};
  if (rows.every(row => !byId.has(row.id))) {
    const confirmed = await api(page, 'POST', '/api/ui/configuration/reconcile-undo', proof, headers);
    assert(confirmed.ok && confirmed.confirmed === true && confirmed.undone === true,
      'completed lesson cleanup retains the exact original restore witness');
    return;
  }
  assert(rows.every(row => byId.get(row.id)?.name === row.name
    && byId.get(row.id)?.['namespace-id'] === row['namespace-id']), 'lesson cleanup matches every exact owned function');
  const review = await api(page, 'POST', '/api/ui/configuration/cleanup-preview', proof, headers);
  assert(review.ok && review.kind === 'editor-configuration-cleanup-review'
    && review.request?.['current-id'] === original.id
    && JSON.stringify(review.request.previous) === JSON.stringify(original.previous)
    && JSON.stringify(review.delete.map(row => row.id).sort()) === JSON.stringify(rows.map(row => row.id).sort()),
  'lesson cleanup review contains exactly its owned cohort and original restore anchor');
  const result = await undoConfiguration(page, {request, receipt: original}, review.request);
  assert(result.ok, 'failed lesson restores its exact previous configuration atomically');
}

async function restorePickerOnlySelection(page, fixture) {
  if (!fixture) return;
  if (fixture.activation) {
    const restored = await undoConfiguration(page, fixture.activation);
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

module.exports = {preparePickerOnlySelection, restorePickerOnlySelection, restoreLessonSelection};
