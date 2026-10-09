// Interrupt a real sandbox, return via Lessons, reload a refused cleanup,
// then retry. A separate retained tutorial-* branch must remain untouched.
const {chromium} = require('playwright');
const {assert, newContext, api, nodeApi, BASE} = require('./edit-test-helpers');

const suffix = process.pid + '-' + Date.now().toString(36);
const sandbox = 'tutorial-recover-' + suffix;
const child = sandbox + '-child';
const retained = 'tutorial-retained-' + suffix;
const nsName = 'recovery-ns-' + suffix;
const ownedName = 'recovery-owned-' + suffix;
const keptName = 'recovery-kept-' + suffix;
const ownedBranches = [];
const branchApi = (branch, method, path, body) => {
  const receipt = ownedBranches.find(row => row.name === branch);
  assert(receipt?.id, 'fixture API uses its captured exact branch UUID');
  return api(null, method, path, body, {'X-Graphden-Branch': receipt.id});
};
const createEntity = async (branch, type, body) => {
  const receipt = ownedBranches.find(row => row.name === branch);
  assert(receipt?.id, 'creation is scoped to the exact owned branch');
  const response = await nodeApi('POST', '/api/entities/' + type, body,
    {'X-Graphden-Branch': receipt.id});
  const id = response.headers.get('X-Graphden-Created-Id');
  assert(response.ok && id, 'successful ' + type + ' creation has a canonical UUID receipt');
  return id;
};
const branches = async () => {
  const payload = await api(null, 'GET', '/api/branches');
  return Array.isArray(payload) ? payload : payload.branches;
};
const identity = (page) => page.evaluate(() => ({
  account: window.gdAccount?.id || null,
  owner: window.gdPrefOwner || null,
  auth: localStorage.getItem('graphden.auth.password'),
}));

(async () => {
  const {browser, page} = await newContext(chromium);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  let nsId;
  let ownedId;
  let keptId;
  try {
    await page.evaluate(async () => { await window.gdAccountsReady; await gdPrefsRefresh(); });
    const before = await identity(page);
    for (const name of [retained, sandbox]) {
      const created = await api(page, 'POST', '/api/branches', {name, 'base-branch-id': 'main'});
      assert(created.ok && created.branch?.id && created.branch.name === name
        && created.branch['base-branch-id'], 'isolated recovery fixture branch created');
      ownedBranches.push({...created.branch, type: 'branch', receipt: 'created'});
    }
    const fork = await api(page, 'POST', '/api/branches', {name: child, 'base-branch-id': sandbox});
    assert(fork.ok && fork.branch?.id && fork.branch.name === child
      && fork.branch['base-branch-id'] === ownedBranches.find(row => row.name === sandbox).id,
      'recovery fixture child created');
    ownedBranches.push({...fork.branch, type: 'branch', receipt: 'created'});
    const parent = (await api(page, 'GET', '/api/graph/entities?scope=search&q=const'))
      .fns.find((fn) => fn.name === 'const');
    assert(parent, 'const is available to the fixture');
    nsId = await createEntity(sandbox, 'ns', 'name=' + nsName);
    ownedId = await createEntity(sandbox, 'fn',
      'name=' + ownedName + '&namespace-id=' + nsId + '&parent-ids=' + parent.id);
    keptId = await createEntity(retained, 'fn', 'name=' + keptName + '&parent-ids=' + parent.id);
    const sandboxReceipt = ownedBranches.find(row => row.name === sandbox);
    const childReceipt = ownedBranches.find(row => row.name === child);
    await page.evaluate(({sandboxReceipt, childReceipt, nsName, ownedName, nsId, ownedId}) => {
      localStorage.setItem('graphden.tour', JSON.stringify({
        lessonId: '01', step: 0, sandboxBranch: sandboxReceipt.name,
        sandboxBranchId: sandboxReceipt.id, sandboxBaseBranchId: sandboxReceipt['base-branch-id'],
        activeBranch: sandboxReceipt.name, principal: _tourSessionPrincipal(),
        created: [{type: 'ns', id: nsId, name: nsName, 'parent-id': null, receipt: 'created'},
          {type: 'fn', id: ownedId, name: ownedName, 'namespace-id': nsId, receipt: 'created'},
          childReceipt],
      }));
    }, {sandboxReceipt, childReceipt, nsName, ownedName, nsId, ownedId});
    await page.goto(BASE + '/?branch=main');
    await page.waitForFunction(() => typeof openTutorialMenu === 'function' && !!graphData);
    assert(await page.evaluate(() => !_tourState), 'returning on main does not run sandbox checks there');
    await page.locator('#auth-lock-btn').click();
    await page.getByRole('menuitem', {name: /Interactive tutorial/}).click();
    await page.getByRole('button', {name: 'End lesson & clean up', exact: true}).click();
    await page.waitForFunction((branch) => getCurrentBranchName() === branch
      && !!document.querySelector('#gd-tour-pop .gd-tour-title')
      && _tourState?.phase === 'cleanup', sandbox, {timeout: 120000});
    assert(await page.getByRole('button', {name: 'Delete branch & return', exact: true}).isVisible(),
      'Lessons restores the sandbox rollback dialog');
    await page.route('**/api/branches/' + sandboxReceipt.id, async (route) => {
      if (route.request().method() === 'DELETE') await route.fulfill({
        status: 200, contentType: 'application/json', body: '{"ok":false}'});
      else await route.continue();
    });
    await page.getByRole('button', {name: 'Delete branch & return', exact: true}).click();
    await page.waitForFunction(() => /could not be deleted/.test(document.body.innerText));
    assert((await branches()).some((branch) => branch.name === sandbox),
      'a refusal retains the sandbox');
    assert(!(await branches()).some((branch) => branch.name === child),
      'the first cleanup already removed the dependent child');
    await page.reload();
    await page.getByRole('button', {name: 'Delete branch & return', exact: true})
      .waitFor({timeout: 120000});
    assert(await page.evaluate(() => _tourState.created.length === 3),
      'reload retains the complete ledger after partial cleanup');
    await page.screenshot({path: '/tmp/tutorial-session-recovery.png'});
    await page.unroute('**/api/branches/' + sandboxReceipt.id);
    await page.getByRole('button', {name: 'Delete branch & return', exact: true}).click();
    await page.waitForFunction(() => getCurrentBranchName() === 'main'
      && typeof graph !== 'undefined' && graph.nodes.size === 0, null, {timeout: 120000});
    assert(new URL(page.url()).hash === '', 'successful recovery returns with an empty selection');
    const remaining = await branches();
    assert(!remaining.some((branch) => branch.name === sandbox || branch.name === child),
      'retry removes the sandbox and accepts the already deleted child');
    assert(!(await api(page, 'GET', '/api/graph/entities?scope=tree'))
      .namespaces.some((ns) => ns.id === nsId), 'recovery removes the sandbox namespace identity');
    assert(remaining.some((branch) => branch.name === retained), 'intentionally retained branch survives');
    await page.goto(BASE + '/?branch=' + retained);
    await page.waitForFunction(() => typeof startTutorial === 'function' && !!graphData);
    await page.evaluate(async () => { await startTutorial('01'); });
    assert(await page.evaluate(() => !_tourState.sandboxBranch),
      'starting a lesson on a retained tutorial-* branch does not claim it');
    await page.evaluate(async () => { await _tourEnd(); });
    assert(!(await page.getByRole('button', {name: 'Delete branch & return', exact: true}).count()),
      'ending that lesson never offers the retained branch for rollback');
    const keptRows = await branchApi(retained, 'GET',
      '/api/graph/entities?scope=search&q=' + encodeURIComponent(keptName));
    assert(keptRows.fns.some((fn) => fn.name === keptName), 'retained graph data survives recovery and a new lesson');
    await page.evaluate(async () => { await window.gdAccountsReady; await gdPrefsRefresh(); });
    assert(JSON.stringify(await identity(page)) === JSON.stringify(before),
      'recovery preserves the current account, owner and authentication');
    assert(errors.length === 0, 'recovery has no uncaught browser errors: ' + errors.join('; '));
  } finally {
    try {
      await page.evaluate(() => { localStorage.removeItem('graphden.tour'); });
      await page.goto(BASE + '/?branch=main');
      for (const id of [ownedId, keptId].filter(Boolean)) {
        const response = await nodeApi('DELETE', '/api/entities/fn/' + id);
        assert(response.ok || response.status === 404, 'exact owned fixture function cleanup');
      }
      if (nsId) {
        const response = await nodeApi('DELETE', '/api/entities/ns/' + nsId);
        assert(response.ok || response.status === 404, 'exact owned fixture namespace cleanup');
      }
      for (const receipt of ownedBranches.slice().reverse()) {
        const actual = (await branches()).find(row => row.id === receipt.id);
        if (!actual) continue;
        assert(actual.name === receipt.name && actual['base-branch-id'] === receipt['base-branch-id'],
          'exact owned fixture branch identity unchanged');
        assert((await api(page, 'DELETE', '/api/branches/' + receipt.id)).ok,
          'exact owned fixture branch cleanup');
      }
    } finally { await browser.close(); }
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
