// Graph authoring must change both ordinary consumers of the shared token.
// Keep the edit in an ephemeral branch; main's package value is untouched.
const {chromium} = require('playwright');
const {execFileSync} = require('node:child_process');
const path = require('node:path');
const {assert, newContext, api, waitForServerHealthy, AUTH, BASE} = require('./edit-test-helpers');
const {editBoundValue, removeUseSiteBinding, bindPlaceholderOn} = require('./tutorial-tour-helpers');
const {captureFixtureNamespaces, cleanupGraphFixture} = require('./ui-graph-fixture-cleanup');
const branch = 'ui-theme-' + process.pid + '-' + Date.now().toString(36);

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  let prepared;
  let namespaceBaseline;
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('dialog', (dialog) => dialog.accept());
  try {
    namespaceBaseline = await captureFixtureNamespaces(page);
    await page.goto(BASE + '/');
    prepared = JSON.parse(execFileSync('bb', ['-cp', 'src', 'tools/ui_preview/prepare.clj',
      BASE, branch, 'user.ui-preview'], {encoding: 'utf8', timeout: 120000,
      cwd: path.resolve(__dirname, '../..'), env: {...process.env, AUTH_TOKEN: AUTH}}));
    assert(prepared.branch === branch, 'temporary editable graph branch created');
    const query = '?branch=' + encodeURIComponent(branch);
    await page.goto(BASE + '/' + query + '#' + prepared.namespace + '.accent-color');
    await page.waitForFunction(() => !!selectedFnId && graph.nodes.size > 0,
      null, {timeout: 120000});
    await editBoundValue(page, '#22c55e');
    await page.goto(prepared.url);
    await page.waitForFunction(() => !!window.uiGraphPreview,
      null, {timeout: 120000});
    for (const host of ['#ui-preview-first', '#ui-preview-second']) {
      const colors = await page.locator(host + ' [data-phase]').evaluate((element) => ({
        menu: element.style.getPropertyValue('--ui-accent'),
        button: element.style.getPropertyValue('--ui-button'),
      }));
      assert(colors.menu === '#22c55e' && colors.button === '#22c55e',
        'edited graph token feeds both consumers in ' + host);
    }
    await page.goto(BASE + '/' + query + '#' + prepared.namespace + '.button-accent-color');
    await page.waitForSelector('.node-overlay[data-fn-name="selected-accent-color"]', {timeout: 120000});
    await removeUseSiteBinding(page, 'selected-accent-color');
    await bindPlaceholderOn(page, 'button-accent-color', 'value', 'literal', '#fb7185');
    await page.goto(prepared.url);
    await page.waitForFunction(() => !!window.uiGraphPreview, null, {timeout: 120000});
    const localColors = await page.locator('#ui-preview-first [data-phase]').evaluate((element) => ({
      menu: element.style.getPropertyValue('--ui-accent'),
      button: element.style.getPropertyValue('--ui-button'),
    }));
    assert(localColors.menu === '#22c55e' && localColors.button === '#fb7185',
      'replacing one graph connection changes only that consumer: ' + JSON.stringify(localColors));
    await page.goto(BASE + '/' + query + '#' + prepared.namespace + '._menu-key-map');
    await page.waitForSelector('.arg-value-editable', {timeout: 120000});
    assert((await page.locator('.arg-value-editable').first().textContent()).includes('ArrowUp'),
      'the graph exposes the first keyboard mapping as an editable value');
    await editBoundValue(page, 'k');
    await page.goto(prepared.url);
    await page.waitForFunction(() => !!window.uiGraphPreview, null, {timeout: 120000});
    const first = page.locator('#ui-preview-first');
    await first.locator('[data-event="open"]').click();
    await page.waitForFunction(() => document.querySelector('#ui-preview-first [data-phase]').dataset.phase === 'open');
    await first.locator('[data-index="0"]').press('ArrowUp');
    assert(await first.locator('[data-index="0"]').getAttribute('data-active') === 'true',
      'the replaced key no longer changes the active item');
    await first.locator('[data-index="0"]').press('k');
    assert(await first.locator('[data-index="2"]').evaluate((element) => element === document.activeElement),
      'editing the graph key mapping changes browser behavior');
    const originalAccent = execFileSync('bb', ['-cp', 'src', '-e',
      `(require '[graphden.packages.records.ids :as ids]) (print (str (ids/fn-id "app.ui-preview" :accent-color)))`],
    {encoding: 'utf8'}).trim();
    const mainAccent = await api(page, 'POST', '/api/execute?branch=main',
      {'fn-id': originalAccent, args: {}, 'timeout-ms': 15000});
    assert(mainAccent.result === '#14b8a6', 'main retains its original graph token');
    assert(errors.length === 0, 'graph editing and reload produce no browser errors');
  } finally {
    try {
      if (prepared) {
        await page.goto(BASE + '/');
        await cleanupGraphFixture(page, prepared, namespaceBaseline);
      }
    } finally { await browser.close(); }
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
