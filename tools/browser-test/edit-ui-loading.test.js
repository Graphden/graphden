// Held plan replies must explain unavailable personal presentation without
// blocking the already rendered native editor. No graph mutations or fixtures.
const {chromium} = require('playwright');
const {assert, newContext, BASE, waitForServerHealthy} = require('./edit-test-helpers');
const {waitForEditorReady} = require('./editor-readiness');
const path = require('node:path');
const gate = () => { let release; return {promise: new Promise(resolve => { release = resolve; }), release: () => release()}; };
(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const root = gate(), components = gate();
  let denyValues = true, denyRoot = false;
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/ui/root/plan', async route => {
    await root.promise;
    if (denyRoot) await route.fulfill({status: 403, contentType: 'application/json', body: '{"ok":false}'});
    else await route.continue();
  });
  await page.route('**/api/ui/configuration/plan', async route => {
    await components.promise;
    const body = route.request().postDataJSON();
    if (denyValues && (body.component === 'canvas-values' || body.components?.includes('canvas-values')))
      await route.fulfill({status: 403, contentType: 'application/json', body: '{"ok":false}'});
    else await route.continue();
  });
  try {
    await page.goto(BASE + '/#web-server');
    await waitForEditorReady(page);
    await page.waitForFunction(() => document.querySelector('[data-ui-loading-component="canvas-values"][data-ui-loading]'));
    const rootStatus = page.locator('#gd-ui-loading-editor');
    assert(await rootStatus.getAttribute('aria-busy') === 'true', 'held root has an individual loading status');
    const values = page.locator('[data-ui-loading-component="canvas-values"][data-ui-loading]').first();
    assert(await values.isVisible(), 'pending value presentation is marked on its actual visible host');
    assert(await page.evaluate(() => document.querySelector('#gd-a11y-announcer')?.textContent.includes('loading')),
      'loading is announced outside all aria-busy nodes');
    assert(await page.evaluate(() => {
      const host = document.querySelector('[data-ui-loading-component="canvas-values"][data-ui-loading]');
      const spinner = getComputedStyle(host, '::after');
      return spinner.content !== 'none' && spinner.animationName === 'editor-busy-spin';
    }), 'the local loading marker has a real CSS progress indicator');
    await page.locator('#search-input').fill('web-server');
    assert(await page.locator('#search-input').inputValue() === 'web-server', 'native search works while personal views wait');
    await page.evaluate(() => {
      window.loadingNativeProof = {search: document.getElementById('search-input'),
        action: document.querySelector('.arg-value-editable, .arg-type-chip')};
    });
    if (process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR)
      await page.screenshot({path: path.join(process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR, 'loading.png')});
    root.release();
    await rootStatus.waitFor({state: 'detached', timeout: 60000});
    assert(await values.getAttribute('data-ui-loading') !== null, 'root completion does not clear the independently pending values');
    components.release();
    const valuesStatus = page.locator('#gd-ui-loading-canvas-values');
    await page.waitForFunction(() => document.getElementById('gd-ui-loading-canvas-values')?.dataset.phase === 'error', null, {timeout: 60000});
    assert(await valuesStatus.getAttribute('aria-busy') === 'false', 'denied values stop being busy and show error separately');
    assert(await page.evaluate(() => document.querySelector('[data-ui-loading-component="canvas-values"][data-ui-load-error]')),
      'the failed component has a local error marker');
    assert(await page.evaluate(() => loadingNativeProof.search === document.getElementById('search-input')
      && loadingNativeProof.search.isConnected && !loadingNativeProof.search.disabled
      && !loadingNativeProof.search.closest('[inert]')
      && (!loadingNativeProof.action || loadingNativeProof.action.isConnected)), 'failure preserves the original native controls');
    if (process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR)
      await page.screenshot({path: path.join(process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR, 'error.png')});
    denyValues = false;
    await valuesStatus.getByRole('button', {name: 'Retry values and types'}).click();
    await valuesStatus.waitFor({state: 'detached', timeout: 60000});
    await page.waitForFunction(() => !document.querySelector('[data-ui-loading]'), null, {timeout: 60000});
    denyRoot = true;
    await page.evaluate(() => { void gdEditorRoot.reload(); });
    await page.waitForFunction(() => document.getElementById('gd-ui-loading-editor')?.dataset.phase === 'error');
    assert(await rootStatus.getAttribute('aria-busy') === 'false', 'root denial has an error instead of a permanent spinner');
    denyRoot = false;
    await rootStatus.getByRole('button', {name: 'Retry editor controls'}).click();
    await rootStatus.waitFor({state: 'detached', timeout: 60000});
    await page.waitForFunction(() => document.querySelector('.gd-ui-loading')?.hidden === true);
    assert(errors.length === 0, 'loading, denial and retry produce no page errors');
    console.log('PASS nonblocking component loading, local markers, truthful errors and retry');
  } finally {
    root.release(); components.release();
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
