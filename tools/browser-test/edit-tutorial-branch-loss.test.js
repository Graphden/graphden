// A deleted lesson branch must stay tied to its exact UUID, even if another
// branch reuses the name. Restart creates a fresh lesson; Exit stops tracking.
const {chromium} = require('playwright');
const {assert, newContext, api, BASE} = require('./edit-test-helpers');

const suffix = process.pid + '-' + Date.now().toString(36);
const oldName = 'tutorial-loss-' + suffix;
const created = [];

const branches = async () => {
  const payload = await api(null, 'GET', '/api/branches');
  return Array.isArray(payload) ? payload : payload.branches;
};

(async () => {
  const {browser, page} = await newContext(chromium);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.waitForFunction(() => typeof _tourSessionPrincipal === 'function'
      && typeof openTutorialMenu === 'function');
    const old = await api(page, 'POST', '/api/branches',
      {name: oldName, 'base-branch-id': 'main'});
    assert(old.ok && old.branch?.id && old.branch['base-branch-id'],
      'old lesson branch created with exact receipt');
    created.push(old.branch);
    await page.evaluate((receipt) => {
      localStorage.setItem('graphden.tour', JSON.stringify({
        lessonId: '01', step: 1, sandboxBranch: receipt.name,
        sandboxBranchId: receipt.id, sandboxBaseBranchId: receipt['base-branch-id'],
        activeBranch: receipt.name, principal: _tourSessionPrincipal(),
        created: [{type: 'fn', id: 'old-function-id', name: 'old-example'}],
      }));
    }, old.branch);
    const removed = await api(page, 'DELETE', '/api/branches/' + old.branch.id);
    assert(removed.ok, 'old lesson branch deleted externally');
    const stranger = await api(page, 'POST', '/api/branches',
      {name: oldName, 'base-branch-id': 'main'});
    assert(stranger.ok && stranger.branch?.id !== old.branch.id,
      'another branch reused the deleted name');
    created.push(stranger.branch);
    await page.reload();
    await page.getByText('Tutorial branch was deleted', {exact: true})
      .waitFor({timeout: 120000});
    assert(await page.evaluate(() => _tourState?.step === 1
      && _tourState.created[0].id === 'old-function-id'),
    'recovery retains the old step and exact ledger');
    await page.getByRole('button', {name: 'Restart lesson', exact: true}).click();
    await page.waitForFunction(() => _tourState?.lessonId === '01'
      && _tourState.step === 0 && _tourState.sandboxBranchId
      && _tourState.sandboxBranchId !== 'old-function-id'
      && getCurrentBranchName() === _tourState.sandboxBranch, null,
    {timeout: 120000});
    const fresh = await page.evaluate(() => ({
      id: _tourState.sandboxBranchId, name: _tourState.sandboxBranch,
      base: _tourState.sandboxBaseBranchId,
      created: _tourState.created,
    }));
    assert(fresh.id !== old.branch.id && fresh.id !== stranger.branch.id,
      'restart uses a new branch identity');
    assert(fresh.created.length === 1 && fresh.created[0].id === fresh.id,
      'restart does not adopt old function UUIDs');
    created.push({id: fresh.id, name: fresh.name, 'base-branch-id': fresh.base});
    assert((await branches()).some(row => row.id === stranger.branch.id),
      'stranger branch survives restart');
    await page.evaluate(() => {
      _tourState.phase = 'cleanup';
      _tourSaveState();
    });
    const removedFresh = await api(null, 'DELETE', '/api/branches/' + fresh.id);
    assert(removedFresh.ok, 'fresh lesson branch deleted during cleanup');
    await page.evaluate(() => authFetch(API.api_graph_entities + '?scope=tree')
      .catch(() => null)).catch(() => {}); // the expected branch-recovery reload can destroy this frame
    await page.getByText('Tutorial branch was deleted', {exact: true})
      .waitFor({timeout: 120000});
    assert(await page.evaluate(() => _tourState?.phase === 'cleanup'
      && _tourState.created[0].id === _tourState.sandboxBranchId),
    'cleanup recovery retains the exact fresh branch ledger');
    await page.getByRole('button', {name: 'Exit lesson', exact: true}).click();
    assert(await page.evaluate(() => !localStorage.getItem('graphden.tour')),
      'Exit clears the session without deleting another branch');
    assert((await branches()).some(row => row.id === stranger.branch.id),
      'stranger remains after Exit');
    assert(errors.length === 0, 'browser has no uncaught errors: ' + errors.join('; '));
  } finally {
    try {
      await page.goto(BASE + '/?branch=main');
      await page.evaluate(() => localStorage.removeItem('graphden.tour'));
      for (const receipt of created.slice().reverse()) {
        const actual = (await branches()).find(row => row.id === receipt.id);
        if (!actual) continue;
        assert(actual.name === receipt.name
          && actual['base-branch-id'] === receipt['base-branch-id'],
        'fixture cleanup uses exact branch identity');
        assert((await api(page, 'DELETE', '/api/branches/' + receipt.id)).ok,
          'fixture branch removed');
      }
    } finally { await browser.close(); }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
