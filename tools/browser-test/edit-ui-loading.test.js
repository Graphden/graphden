// Held plan replies must explain unavailable personal presentation without
// blocking the already rendered native editor. No graph mutations or fixtures.
const {chromium} = require('playwright');
const {assert, newContext, BASE, waitForServerHealthy} = require('./edit-test-helpers');
const {waitForEditorReady} = require('./editor-readiness');
const path = require('node:path');
const fs = require('node:fs');
const gate = () => { let release; return {promise: new Promise(resolve => { release = resolve; }), release: () => release()}; };
(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const root = gate(), components = gate();
  let denyValues = true, denyRoot = false;
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    window.loadingAnnouncementProof = [];
    let announce;
    Object.defineProperty(window, 'gdAnnounce', {configurable: true, get: () => announce, set(fn) {
      announce = function(message, ...args) {
        const result = fn.call(this, message, ...args);
        if (typeof message === 'string' && message.includes(' — loading…')) {
          const node = document.getElementById('gd-a11y-announcer');
          loadingAnnouncementProof.push(!!node && !node.closest('[aria-busy="true"]')
            && node.getAttribute('aria-live') === 'polite' && node.getAttribute('role') === 'status');
        }
        return result;
      };
    }});
  });
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
  async function audit(name) {
    if (!process.env.GRAPHDEN_A11Y_SOURCE) return;
    await page.addScriptTag({path: process.env.GRAPHDEN_A11Y_SOURCE});
    const result = await page.evaluate(async () => {
      const context = {include: [['.gd-ui-loading'], ['[data-ui-loading-component]']]};
      const runOnly = {type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']};
      const full = await window.axe.run(context, {runOnly});
      // Existing canvas text colors are reported intact. The loading region
      // must pass contrast too; all annotated hosts must pass structural ARIA.
      const status = await window.axe.run('.gd-ui-loading', {runOnly});
      const aria = await window.axe.run(context, {runOnly, rules: {'color-contrast': {enabled: false}}});
      return {full, statusViolations: status.violations, ariaViolations: aria.violations};
    });
    if (process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR) {
      fs.writeFileSync(path.join(process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR, name + '-a11y.json'), JSON.stringify(result, null, 2));
      fs.writeFileSync(path.join(process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR, name + '-aria.yml'),
        await page.locator('.gd-ui-loading').ariaSnapshot());
    }
    assert(result.statusViolations.length === 0 && result.ariaViolations.length === 0,
      name + ' status passes WCAG contrast and local hosts pass ARIA checks');
  }
  const layoutProof = {};
  async function assertLayout(phase) {
    const geometry = await page.evaluate(() => {
      const main = document.getElementById('main-container'), explorer = document.getElementById('side-menu');
      const saved = explorer.scrollTop;
      explorer.scrollTop = 80;
      const scrollInside = explorer.scrollTop > 0;
      explorer.scrollTop = saved;
      return {viewport: innerHeight, mainBottom: main.getBoundingClientRect().bottom,
        explorerBottom: explorer.getBoundingClientRect().bottom, scrollHeight: explorer.scrollHeight,
        clientHeight: explorer.clientHeight, overflow: getComputedStyle(explorer).overflowY, scrollInside,
        namespaces: document.querySelectorAll('#entity-list .ns-header').length};
    });
    layoutProof[phase] = geometry;
    if (process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR)
      fs.writeFileSync(path.join(process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR, 'layout.json'), JSON.stringify(layoutProof, null, 2));
    assert(geometry.mainBottom <= geometry.viewport + 1 && geometry.explorerBottom <= geometry.viewport + 1
      && geometry.scrollHeight > geometry.clientHeight && geometry.scrollInside
      && ['auto', 'scroll'].includes(geometry.overflow), phase + ' full catalogue stays inside the viewport and scrolls within Explorer');
  }
  try {
    await page.goto(BASE + '/#web-server');
    await waitForEditorReady(page);
    await page.waitForFunction(() => document.querySelector('[data-ui-loading-component="canvas-values"][data-ui-loading][data-ui-loading-indicator]'));
    const rootStatus = page.locator('#gd-ui-loading-editor');
    assert(await page.locator('#gd-ui-loading-canvas-bindings').isHidden(), 'an unopened binding popover adds no visible loading row');
    assert(await rootStatus.getAttribute('aria-busy') === 'true', 'held root has an individual loading status');
    const values = page.locator('[data-ui-loading-component="canvas-values"][data-ui-loading][data-ui-loading-indicator]').first();
    assert(await page.locator('#gd-ui-loading-canvas-values').isVisible(), 'late-mounted values appear in the visible summary');
    assert(await values.isVisible(), 'pending value presentation is marked on its actual visible host');
    assert(await page.evaluate(() => loadingAnnouncementProof.length > 0 && loadingAnnouncementProof.every(Boolean)),
      'loading is announced outside all aria-busy nodes');
    assert(await page.evaluate(() => {
      const host = document.querySelector('[data-ui-loading-component="canvas-values"][data-ui-loading][data-ui-loading-indicator]');
      const spinner = getComputedStyle(host, '::after');
      return spinner.content !== 'none' && spinner.animationName === 'editor-busy-spin';
    }), 'the local loading marker has a real CSS progress indicator');
    await assertLayout('loading');
    await page.locator('#search-input').fill('web-server');
    assert(await page.locator('#search-input').inputValue() === 'web-server', 'native search works while personal views wait');
    await page.evaluate(() => {
      window.loadingNativeProof = {search: document.getElementById('search-input'),
        action: document.querySelector('.arg-value-editable, .arg-type-chip')};
    });
    if (process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR)
      await page.screenshot({path: path.join(process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR, 'loading.png')});
    await audit('loading');
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
    await audit('error');
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
    await page.locator('#search-input').fill('');
    await page.waitForFunction(() => !document.querySelector('#entity-list .search-exact-section')
      && !document.getElementById('entity-list').textContent.includes('Searching…'));
    await assertLayout('ready');
    if (process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR)
      await page.screenshot({path: path.join(process.env.GRAPHDEN_LOADING_SCREENSHOT_DIR, 'ready.png')});
    assert(errors.length === 0, 'loading, denial and retry produce no page errors');
    console.log('PASS nonblocking component loading, local markers, truthful errors and retry');
  } finally {
    root.release(); components.release();
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
