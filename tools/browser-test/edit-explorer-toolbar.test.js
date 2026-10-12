// Real finite toolbar ownership: counts, filters, focus and pending native
// actions across refresh/auth changes. No artificial limit on Explorer rows.
const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');
(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  page.on('pageerror', error => errors.push(error.name));
  page.on('console', message => { if (message.type() === 'error') errors.push('console-error'); });
  let release;
  try {
    await page.goto(BASE + '/#web-server');
    await page.waitForFunction(() => graphData && lookups?.fnMap?.size > 1
      && document.querySelector('#kind-filters .gd-explorer-kinds')
      && document.querySelector('.node-overlay[data-original-fn-id]'));
    await page.evaluate(() => gdClearFilters());
    assert(await page.locator('#kind-filters button').count() === 11, 'finite toolbar contains every existing kind and detail toggle');
    assert(await page.evaluate(() => GraphdenRenderer.ownsTarget(document.getElementById('kind-filters'))
      && !GraphdenRenderer.ownsTarget(document.getElementById('entity-list'))
      && !GraphdenRenderer.ownsTarget(document.getElementById('tests-run-all-btn'))), 'renderer owns only its empty toolbar host');
    assert(await page.locator('#kind-filters button').evaluateAll(buttons => buttons.every(button =>
      button.type === 'button' && ['true', 'false'].includes(button.getAttribute('aria-pressed'))
      && (button.getAttribute('aria-label') || button.textContent || button.title).trim())), 'every toggle keeps an accessible name and pressed state');
    await page.screenshot({path: '/tmp/gd-explorer-toolbar-light.png'});
    await page.evaluate(() => document.body.classList.add('theme-dark'));
    await page.screenshot({path: '/tmp/gd-explorer-toolbar-dark.png'});
    await page.evaluate(() => document.body.classList.remove('theme-dark'));
    await page.evaluate(() => {
      window.explorerToggle = document.querySelector('#kind-filters [data-kind="types"]');
      window.explorerSelected = document.querySelector('#entity-list .entity-item.selected');
    });
    await page.locator('#kind-filters [data-kind="types"]').focus();
    await page.keyboard.press('Space');
    assert(await page.locator('#kind-filters [data-kind="types"]').getAttribute('aria-pressed') === 'true', 'keyboard uses the existing filter model');
    assert(await page.evaluate(() => explorerToggle === document.activeElement
      && explorerToggle === document.querySelector('#kind-filters [data-kind="types"]')
      && (!explorerSelected || explorerSelected.isConnected)), 'toggle refresh preserves exact focus and in-place selected row');
    await page.locator('#kind-filters [data-kind="all"]').click();
    await page.evaluate(() => { gdSetName('web-server'); gdSaveView('toolbar-proof-view'); });
    assert(await page.locator('#gd-ws-chip b').textContent() === 'toolbar-proof-view', 'saved-view meaning remains owned by the existing model');
    await page.locator('#kind-filters [data-kind="all"]').click();
    assert(await page.evaluate(() => !gdFiltersActive() && gdActiveViewName() === null), 'All clears every filter and detaches the applied view');
    await page.evaluate(() => gdDeleteView('toolbar-proof-view'));
    await page.locator('#kind-filters [data-detail="fx"]').click();
    assert(await page.locator('#kind-filters [data-detail="fx"]').getAttribute('aria-pressed') === 'true', 'effect detail remains distinct from the filter set');
    await page.locator('#kind-filters [data-detail="fx"]').click();
    // Existing POST adapter owns pending labels/disabled. Hold one real request
    // by request phase; toolbar changes must leave its exact control intact.
    await page.evaluate(() => {
      window.originalTestCount = getTestStatusCount;
      getTestStatusCount = () => 12000;
      gdToggleKind('tests');
      window.explorerRunButton = document.getElementById('tests-run-all-btn');
    });
    assert(await page.locator('#kind-filters [data-kind="tests"] .kind-count').textContent().then(text => text.startsWith('12000')), 'large counts render without a picker or Used-by cap');
    let entered;
    const pendingRequest = new Promise(resolve => { entered = resolve; });
    await page.route('**/api/tests/run', async route => {
      entered();
      await new Promise(resolve => { release = resolve; });
      await route.continue();
    });
    await page.locator('#tests-run-all-btn').click();
    await page.waitForFunction(() => document.getElementById('tests-run-all-btn').disabled);
    await pendingRequest;
    await page.locator('#kind-filters [data-kind="all"]').click();
    await page.evaluate(() => gdToggleKind('tests'));
    assert(await page.evaluate(() => explorerRunButton === document.getElementById('tests-run-all-btn')
      && explorerRunButton.disabled && explorerRunButton.querySelector('.kind-label').textContent === 'Running…'), 'hide/reveal retains the exact pending operation label/control');
    await page.evaluate(() => {
      window.explorerAuthentication = isAuthenticated;
      isAuthenticated = () => false;
      gdExplorerGraph.refresh();
    });
    assert(await page.evaluate(() => explorerRunButton.hidden && explorerRunButton.disabled
      && explorerRunButton === document.getElementById('tests-run-all-btn')), 'signed-out projection hides the pending action without replacing it');
    await page.evaluate(() => { isAuthenticated = explorerAuthentication; gdExplorerGraph.refresh(); });
    assert(await page.evaluate(() => !explorerRunButton.hidden && explorerRunButton.disabled), 'authenticated projection reveals the same pending action');
    release(); release = null;
    await page.waitForFunction(() => !document.getElementById('tests-run-all-btn').disabled, null, {timeout: 90000});
    await page.unroute('**/api/tests/run');
    await page.evaluate(() => { getTestStatusCount = originalTestCount; gdClearFilters(); });
    assert(errors.length === 0, 'no browser errors');
    console.log('PASS Explorer toolbar ownership, filtering, focus, saved-view meaning and pending actions');
  } finally {
    release?.();
    await page.unrouteAll({behavior: 'wait'});
    await browser.close();
  }
})().catch(() => {console.error('FAIL Explorer toolbar; payloads withheld'); process.exitCode = 1;});
