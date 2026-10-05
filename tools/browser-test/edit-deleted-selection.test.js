// Deleting the current branch must not replay its branch-only function hash
// against main. Ordinary branch switching still preserves selection.
const {chromium} = require('playwright');
const {assert, newContext, api, openBranchPopover, waitForServerHealthy, BASE} = require('./edit-test-helpers');
const {extendViaRowActions} = require('./tutorial-tour-helpers');
const suffix = process.pid + '-' + Date.now().toString(36);
const branch = 'deleted-selection-' + suffix;
const child = 'deleted-selection-fn-' + suffix;

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('dialog', (dialog) => { dialog.accept(); });
  try {
    await page.goto(BASE + '/');
    const created = await api(page, 'POST', '/api/branches', {name: branch, 'base-branch-id': 'main'});
    assert(created.ok !== false, 'isolated feature branch created');
    await page.goto(BASE + '/?branch=' + encodeURIComponent(branch) + '#core.logic.const');
    await page.waitForFunction(() => !!selectedFnId && graph.nodes.size > 0, null, {timeout: 120000});
    await extendViaRowActions(page, child, 'const');
    await page.waitForFunction((name) => decodeURIComponent(location.hash).includes(name), child, {timeout: 30000});
    assert(await openBranchPopover(page), 'current branch menu opens');
    // The menu hides deletion of the active branch. Exercise its existing
    // deletion handler directly to cover cleanup initiated outside that menu.
    await page.evaluate((name) => deleteBranchWithConfirm(name), branch);
    await page.waitForURL((url) => !url.searchParams.has('branch') && !url.hash, {timeout: 60000});
    await page.waitForSelector('#graph-empty-state', {state: 'visible', timeout: 120000});
    assert(await page.evaluate(() => selectedFnId === null && graph.nodes.size === 0 && graph.edges.size === 0),
      'main starts with empty selection and graph');
    assert(!/Function not found/.test(await page.locator('body').innerText()), 'expected deletion produces no missing-function toast');
    assert(errors.length === 0, 'branch deletion produces no uncaught browser errors');
    await page.screenshot({path: '/tmp/deleted-selection-empty.png'});
  } finally {
    try { await page.goto(BASE + '/'); await api(page, 'DELETE', '/api/branches/' + encodeURIComponent(branch)); } catch (_) {}
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
