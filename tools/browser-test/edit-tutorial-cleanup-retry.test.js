// A real external reference must keep in-place cleanup available for retry.
const {chromium} = require('playwright');
const {assert, newContext, api, waitForServerHealthy, BASE} = require('./edit-test-helpers');
const suffix = process.pid + '-' + Date.now().toString(36);
const branch = 'cleanup-retry-' + suffix;
const ownedName = 'cleanup-owned-' + suffix;
const externalName = 'cleanup-dependent-' + suffix;
const branchApi = (method, path, body) => api(null, method, path, body, {'X-Graphden-Branch': branch});
const search = (name) => branchApi('GET', '/api/graph/entities?scope=search&q=' + encodeURIComponent(name));

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  let branchCreated = false;
  try {
    await page.goto(BASE + '/');
    const created = await api(page, 'POST', '/api/branches', {name: branch, 'base-branch-id': 'main'});
    assert(created.ok, 'isolated cleanup branch created');
    branchCreated = true;
    await page.goto(BASE + '/?branch=' + branch + '#core.logic.const');
    await page.waitForFunction(() => !!selectedFnId && graph.nodes.size > 0,
      null, {timeout: 120000});
    const parent = (await search('const')).fns.find((fn) => fn.name === 'const');
    const entities = await branchApi('GET', '/api/graph/entities?scope=subtree&root-id=' + parent.id);
    const slots = new Map(entities.slots.map((slot) => [slot.id, slot]));
    const valueSlot = entities['fn-slots'].find((slot) => slot['fn-id'] === parent.id
      && slots.get(slot['slot-id'])?.name === 'value')['slot-id'];
    for (const name of [ownedName, externalName]) {
      await branchApi('POST', '/api/entities/fn', 'name=' + name + '&parent-ids=' + parent.id);
    }
    const owned = (await search(ownedName)).fns.find((fn) => fn.name === ownedName);
    const external = (await search(externalName)).fns.find((fn) => fn.name === externalName);
    await branchApi('POST', '/api/entities/binding',
      'fn-id=' + external.id + '&slot-id=' + valueSlot + '&ref-fn-id=' + owned.id);
    // Start at the end-dialog seam with one ledger entry. The other graph
    // represents work the reader kept outside this lesson and must survive.
    await page.evaluate(async (name) => {
      await _tourFetchLessons();
      _tourState = {lessonId: '18', step: 0, principal: _tourSessionPrincipal(),
        created: [{type: 'fn', name}]};
      await _tourEnd();
    }, ownedName);
    await page.getByRole('button', {name: 'Delete them', exact: true}).click();
    await page.waitForFunction((name) => document.body.textContent.includes('Kept: fn “' + name),
      ownedName, {timeout: 60000});
    assert(await page.evaluate(() => _tourState?.created.length === 1),
      'refusal retains the original cleanup ledger');
    assert(await page.getByRole('button', {name: 'Delete them', exact: true}).isVisible(),
      'the same cleanup action remains available');
    assert((await search(externalName)).fns.some((fn) => fn.id === external.id),
      'cleanup did not delete the external dependent');
    const removed = await branchApi('DELETE', '/api/entities/fn/' + external.id);
    assert(removed.ok !== false, 'external dependency removed separately');
    await page.getByRole('button', {name: 'Delete them', exact: true}).click();
    await page.waitForSelector('#gd-tour-pop', {state: 'detached', timeout: 60000});
    assert(!(await search(ownedName)).fns.some((fn) => fn.id === owned.id),
      'successful retry deleted the lesson graph');
    assert(await page.evaluate(() => _tourState === null), 'successful retry releases the session');
  } finally {
    try {
      if (branchCreated) {
        await page.goto(BASE + '/');
        const removed = await api(page, 'DELETE', '/api/branches/' + branch);
        assert(removed.ok || removed.reason === 'not-found', 'isolated cleanup branch removed');
      }
    } finally { await browser.close(); }
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
