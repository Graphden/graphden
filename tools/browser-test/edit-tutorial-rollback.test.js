// A tutorial rollback must remove its child branches and drop the qualified
// selection before reloading main. Otherwise the next page tries to open a
// function that existed only in the deleted sandbox.
const {chromium} = require('playwright');
const {assert, newContext, nodeApi, nodeApiJson} = require('./edit-test-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
const RUN_ID = process.pid.toString(36) + '-' + Date.now().toString(36);
const BRANCH = 'tutorial-rollback-probe-' + RUN_ID;
const CHILD = BRANCH + '-child';

(async () => {
  const {browser, page} = await newContext(chromium);
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  try {
    const sandbox = (await nodeApiJson('POST', '/api/branches',
      {name: BRANCH, 'base-branch-id': 'main'})).branch;
    const childBranch = (await nodeApiJson('POST', '/api/branches',
      {name: CHILD, 'base-branch-id': BRANCH})).branch;
    assert(sandbox?.id && sandbox['base-branch-id'] && childBranch?.id
      && childBranch['base-branch-id'] === sandbox.id,
    'rollback fixture captures exact sandbox and child branch receipts');
    await page.goto(BASE + '/?branch=' + BRANCH);
    await page.waitForFunction((name) => typeof _tourEnd === 'function'
      && document.querySelector('#branch-chip-name')?.textContent.trim() === name, BRANCH);
    await page.evaluate(async ({sandbox, childBranch}) => {
      _tourLessons = {lessons: [{id: '01', steps: [{}]}]};
      _tourState = {lessonId: '01', step: 1, sandboxBranch: sandbox.name,
        sandboxBranchId: sandbox.id, sandboxBaseBranchId: sandbox['base-branch-id'],
        activeBranch: sandbox.name, principal: _tourSessionPrincipal(),
        created: [{type: 'branch', id: childBranch.id, name: childBranch.name,
          'base-branch-id': childBranch['base-branch-id'], receipt: 'created'},
        {type: 'fn', name: 'created'}]};
      history.replaceState(null, '', location.pathname + location.search + '#tutorial.created');
      await _tourEnd();
    }, {sandbox, childBranch});
    // The first attempt removes the child but the parent refuses. The second
    // must accept that the child is already gone and finish the same rollback.
    await page.route('**/api/branches/' + sandbox.id, async (route) => {
      if (route.request().method() === 'DELETE') await route.fulfill({
        status: 200, contentType: 'application/json', body: '{"ok":false}'});
      else await route.continue();
    });
    await page.getByRole('button', {name: 'Delete branch & return', exact: true}).click();
    await page.waitForFunction(() => /could not be deleted/.test(document.body.innerText));
    assert(new URL(page.url()).searchParams.get('branch') === BRANCH,
      'refused cleanup keeps the reader on the sandbox');
    await page.screenshot({path: '/tmp/tutorial-rollback-refusal.png'});
    await page.unroute('**/api/branches/' + sandbox.id);
    await page.getByRole('button', {name: 'Delete branch & return', exact: true}).click();
    await page.waitForFunction(() => !location.search.includes('branch=')
      && document.querySelector('#branch-chip-name')?.textContent.trim() === 'main');
    assert(new URL(page.url()).hash === '', 'rollback clears the qualified selection');
    const rows = await nodeApiJson('GET', '/api/branches');
    const branches = Array.isArray(rows) ? rows : rows.branches;
    assert(!branches.some((b) => b.name === BRANCH || b.name === CHILD),
      'sandbox and child branch are both removed');
    await page.waitForFunction(() => typeof graph !== 'undefined'
      && graph.nodes.size === 0 && graph.edges.size === 0);
    assert(true, 'main has no orphan nodes or edges');
    assert(!/Function not found/i.test(await page.locator('body').innerText()),
      'expected rollback does not report a missing function');
    assert(errors.length === 0, 'rollback has no uncaught browser errors: ' + errors.join('; '));
  } finally {
    await nodeApi('DELETE', '/api/branches/' + CHILD).catch(() => {});
    await nodeApi('DELETE', '/api/branches/' + BRANCH).catch(() => {});
    await browser.close();
  }
})().catch((e) => { console.error(e); process.exitCode = 1; });
