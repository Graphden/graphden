// The retained account query crosses the real HTTP boundary and changes the
// existing menu. Read-only: no route interception, graph writes or fixtures.
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const {newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  const calls = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  page.on('response', response => {
    if (new URL(response.url()).pathname === '/api/ui/server-call') calls.push(response);
  });
  try {
    await page.goto(BASE);
    await page.waitForFunction(() => window.gdShellMenuGraph?.ready, null, {timeout: 120000});
    await page.locator('#auth-lock-btn').click();
    const status = page.locator('#auth-popover .auth-menu-status');
    await status.waitFor();
    await page.waitForFunction(() => document.querySelector('#auth-popover .auth-menu-status')?.textContent.startsWith('Branch · '),
      null, {timeout: 120000});
    const branch = await page.evaluate(() => getCurrentBranchName());
    assert.equal(await status.textContent(), 'Branch · ' + branch);
    assert(calls.length > 0, 'menu query uses the real server-call endpoint');
    const response = calls.at(-1);
    assert.equal(response.status(), 200);
    const request = response.request().postDataJSON();
    assert.deepEqual(Object.keys(request).sort(), ['descriptor', 'input']);
    assert.deepEqual(Object.keys(request.descriptor), ['token']);
    assert.equal(typeof request.descriptor.token, 'string');
    const result = await response.json();
    assert.equal(result.ok, true);
    const decoded = await page.evaluate(value => Object.fromEntries([...window.GraphdenBrowser.decode(value)]
      .map(([key, item]) => [key.name, item])), result.value);
    assert.deepEqual(decoded, {label: 'Branch', 'branch-name': branch});
    await page.screenshot({path: '/tmp/b3-server-call.png'});
    assert.deepEqual(errors, []);
    console.log('Account server query: real HTTP typed result rendered in the existing menu PASS');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
