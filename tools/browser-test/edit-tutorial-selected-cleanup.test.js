// Real in-place tutorial cleanup of the selected creation on main. The normal
// no-selection canvas and inspector must replace the deleted graph immediately.
const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName, nodeApi, BASE} = require('./edit-test-helpers');
const suffix = process.pid + '-' + Date.now().toString(36);
const nsName = 'cleanup-selected-' + suffix;
const name = 'created-' + suffix;

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  let nsId;
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await api(page, 'POST', '/api/entities/ns', 'name=' + nsName);
    const tree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    nsId = tree.namespaces.find(row => row.name === nsName && !row['parent-id'])?.id;
    assert(nsId, 'owned fixture namespace exists');
    const entities = await getEntities(page, 'const');
    const parent = entities.fns.find(row => row.name === 'const' && !row['parent-ids']?.length);
    await api(page, 'POST', '/api/entities/fn', new URLSearchParams({
      name, 'namespace-id': nsId, 'parent-ids': parent.id,
    }).toString());
    const created = (await api(page, 'GET', '/api/graph/entities?scope=search&q=' + name))
      .fns.find(row => row.name === name && row['namespace-id'] === nsId);
    assert(created, 'created fixture identity exists');
    await page.goto(BASE + '/#' + nsName + '.' + name);
    await page.waitForFunction(id => selectedFnId === id && graph.nodes.size > 0, created.id,
      {timeout: 120000});
    await page.evaluate(async createdName => {
      await _tourFetchLessons();
      _tourState = {lessonId: '18', step: 0, principal: _tourSessionPrincipal(),
        created: [{type: 'fn', name: createdName}]};
      await _tourEnd();
    }, name);
    await page.getByRole('button', {name: 'Delete them', exact: true}).click();
    await page.waitForSelector('#gd-tour-pop', {state: 'detached', timeout: 60000});
    await page.waitForFunction(() => selectedFnId === null && graph.nodes.size === 0,
      null, {timeout: 60000});
    assert(await page.locator('#graph-empty-state').isVisible(), 'standard empty-canvas prompt is visible');
    assert(await page.locator('.gd-insp-empty').isVisible(), 'inspector shows its standard selection placeholder');
    assert(await page.evaluate(() => !location.hash || location.hash === '#'), 'deleted selection hash is cleared');
    assert(await page.evaluate(() => !new URL(location.href).searchParams.has('branch')),
      'in-place cleanup remains on main');
    assert(!(await api(page, 'GET', '/api/graph/entities?scope=search&q=' + name)).fns
      .some(row => row.id === created.id), 'authoritative API confirms deletion');
    assert(errors.length === 0, 'no browser errors: ' + errors.join('; '));
    await page.screenshot({path: '/tmp/graphden-tutorial-selected-cleanup.png'});
  } finally {
    await deleteFnByName(page, name);
    if (nsId) assert((await nodeApi('DELETE', '/api/entities/ns/' + nsId)).ok,
      'fixture namespace cleaned');
    await browser.close();
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
