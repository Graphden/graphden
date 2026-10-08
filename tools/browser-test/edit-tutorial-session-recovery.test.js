// Interrupt a real sandbox, return via Lessons, reload a refused cleanup,
// then retry. A separate retained tutorial-* branch must remain untouched.
const {chromium} = require('playwright');
const {assert, newContext, api, BASE} = require('./edit-test-helpers');

const suffix = process.pid + '-' + Date.now().toString(36);
const sandbox = 'tutorial-recover-' + suffix;
const child = sandbox + '-child';
const retained = 'tutorial-retained-' + suffix;
const nsName = 'recovery-ns-' + suffix;
const ownedName = 'recovery-owned-' + suffix;
const keptName = 'recovery-kept-' + suffix;
const branchApi = (branch, method, path, body) => api(null, method, path, body,
  {'X-Graphden-Branch': branch});
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
  try {
    await page.evaluate(async () => { await window.gdAccountsReady; await gdPrefsRefresh(); });
    const before = await identity(page);
    for (const name of [retained, sandbox]) {
      const created = await api(page, 'POST', '/api/branches', {name, 'base-branch-id': 'main'});
      assert(created.ok, 'isolated recovery fixture branch created');
    }
    const fork = await api(page, 'POST', '/api/branches', {name: child, 'base-branch-id': sandbox});
    assert(fork.ok, 'recovery fixture child created');
    const parent = (await api(page, 'GET', '/api/graph/entities?scope=search&q=const'))
      .fns.find((fn) => fn.name === 'const');
    assert(parent, 'const is available to the fixture');
    await branchApi(sandbox, 'POST', '/api/entities/ns', 'name=' + nsName);
    nsId = (await branchApi(sandbox, 'GET', '/api/graph/entities?scope=tree'))
      .namespaces.find((ns) => ns.name === nsName && !ns['parent-id']).id;
    await branchApi(sandbox, 'POST', '/api/entities/fn',
      'name=' + ownedName + '&namespace-id=' + nsId + '&parent-ids=' + parent.id);
    await branchApi(retained, 'POST', '/api/entities/fn',
      'name=' + keptName + '&parent-ids=' + parent.id);
    await page.evaluate(({sandbox, child, nsName, ownedName}) => {
      localStorage.setItem('graphden.tour', JSON.stringify({
        lessonId: '01', step: 0, sandboxBranch: sandbox, activeBranch: sandbox,
        principal: _tourSessionPrincipal(),
        created: [{type: 'ns', name: nsName}, {type: 'fn', name: ownedName},
          {type: 'branch', name: child}],
      }));
    }, {sandbox, child, nsName, ownedName});
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
    await page.route('**/api/branches/' + sandbox, async (route) => {
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
    await page.unroute('**/api/branches/' + sandbox);
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
      for (const name of [child, sandbox, retained]) await api(page, 'DELETE', '/api/branches/' + name);
      if (nsId) await api(page, 'DELETE', '/api/entities/ns/' + nsId);
    } finally { await browser.close(); }
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
