// Root HOF bootstrap in the existing editor. The server boundary is covered
// separately by browser-snapshot-integration-test; this fixture also verifies
// that an unsupported adapter cannot replace the active legacy editor.
// GRAPHDEN_URL=http://localhost:<port> node tools/browser-test/edit-root-hof.test.js
// Exit 0 = PASS, 1 = FAIL. No graph writes or persistent browser artifacts.
'use strict';
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const {newContext} = require('./edit-test-helpers');
(async () => {
  const {browser, page} = await newContext(chromium);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  const endpoint = '**/api/ui/root/plan*';
  try {
    const result = await page.evaluate(async () => {
      const response = await window.authFetch(window.API.api_ui_root_plan, {cache: 'no-store'});
      return {status: response.status, body: await response.json()};
    });
    assert.equal(result.status, 200);
    assert.equal(result.body.ok, true);
    assert(result.body.plan.primitives.some(primitive => primitive.op === 'call'));
    await page.evaluate(() => window.gdEditorRoot.reload());
    assert.equal(await page.locator('#app').isVisible(), true);

    assert.equal(await page.locator('#app').getAttribute('data-gd-root-adapter'), 'legacy');
    const forged = await page.evaluate(async () => {
      const response = await window.authFetch(window.API.api_ui_root_plan + '?target=00000000-0000-0000-0000-000000000000&principal=forged');
      return await response.json();
    });
    assert.equal(forged.plan.entries.root, result.body.plan.entries.root);

    const alternate = structuredClone(result.body);
    const operations = new Map(alternate.plan.primitives.map(primitive => [primitive.id, primitive.op]));
    const body = alternate.plan.functions.find(fn => operations.get(fn.primitive) === 'const');
    assert(body, 'static root callback is part of the checked graph');
    body.args.find(arg => arg.name === 'value').expr.value = ['map', [[['keyword', null, 'legacy'], ['bool', false]]]];
    await page.route(endpoint, route => route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(alternate)}));
    await page.evaluate(() => window.gdEditorRoot.reload());
    await page.getByRole('status').filter({hasText: 'Editor configuration could not be loaded.'}).waitFor();
    assert.equal(await page.locator('#app').isVisible(), true);
    assert.equal(await page.locator('#app').getAttribute('data-gd-root-adapter'), null);
    await page.setViewportSize({width: 390, height: 844});
    await page.screenshot({path: '/tmp/graphden-root-hof.png'});

    await page.unroute(endpoint);
    await page.evaluate(() => window.gdEditorRoot.reload());
    assert.equal(await page.locator('#app').getAttribute('data-gd-root-adapter'), 'legacy');
    assert.equal(await page.locator('#app').isVisible(), true);
    assert.deepEqual(errors, []);
  } finally {
    await page.unroute(endpoint);
    await page.evaluate(() => window.gdEditorRoot?.dispose()).catch(() => {});
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
