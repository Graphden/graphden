// Read navigation stays useful with real metadata/service responses held.
// Auth invalidation and delayed name resolution must not restore old state.
const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  let releaseTypes, releaseServices, releaseName;
  const typesGate = new Promise(resolve => { releaseTypes = resolve; });
  const servicesGate = new Promise(resolve => { releaseServices = resolve; });
  const nameGate = new Promise(resolve => { releaseName = resolve; });
  let typesRequests = 0;
  let serviceRequests = 0;
  let nameHeld = false;
  let writes = 0;
  page.on('request', request => {
    if (/\/api\/entities\//.test(request.url()) && ['POST', 'PUT', 'DELETE'].includes(request.method())) writes++;
  });
  await page.route('**/api/types', async route => {
    if (++typesRequests !== 1) return route.continue();
    await typesGate;
    // A deliberately recognizable obsolete response: authorization was
    // invalidated before release, so this must never enter richTypes.
    await route.fulfill({status: 200, contentType: 'application/json', body: '{"obsoleteBoot":{}}'});
  });
  await page.route(/\/api\/(?:orgs\/)?services$/, async route => {
    if (++serviceRequests !== 1) return route.continue();
    await servicesGate;
    await route.fulfill({status: 200, contentType: 'application/json', body: '{"services":[{"id":"obsolete-service"}]}'});
  });
  await page.route('**/api/graph/entities?*', async route => {
    const url = new URL(route.request().url());
    if (url.searchParams.get('scope') === 'search' && url.searchParams.get('q') === 'add' && !nameHeld) {
      nameHeld = true;
      await nameGate;
    }
    await route.continue();
  });
  try {
    const serviceArrived = page.waitForRequest(request => /\/api\/(?:orgs\/)?services$/.test(new URL(request.url()).pathname), {timeout: 60000});
    await page.goto(BASE + '/', {waitUntil: 'domcontentloaded'});
    await page.waitForSelector('#entity-list .ns-header', {timeout: 60000});
    assert(await page.evaluate(() => !document.getElementById('entity-list').inert && !gdGraphEditsReady()),
      'Explorer navigation is available while metadata is held');
    const wasExpanded = await page.locator('#entity-list .ns-header').first().getAttribute('aria-expanded');
    await page.locator('#entity-list .ns-header').first().click();
    assert(await page.locator('#entity-list .ns-header').first().getAttribute('aria-expanded') !== wasExpanded,
      'namespace expansion works before metadata');
    const nameArrived = page.waitForRequest(request => {
      const url = new URL(request.url());
      return url.searchParams.get('scope') === 'search' && url.searchParams.get('q') === 'add';
    }, {timeout: 30000});
    await page.evaluate(() => { window.__oldNavigation = selectFnByName('core.arithmetic/add'); });
    await nameArrived;
    await page.waitForFunction(() => document.getElementById('search-input'));
    await page.fill('#search-input', 'sub');
    await page.waitForFunction(() => graphData.fns.some(fn => fn.name === 'sub'));
    const selected = await page.evaluate(() => graphData.fns.find(fn => fn.name === 'sub'
      && lookups.nsPathMap.get(fn['namespace-id']) === 'core.arithmetic')?.id);
    assert(selected, 'search resolves the intended arithmetic function');
    // Exact search matches also appear in the namespace tree; use the pinned match.
    const row = page.locator('#entity-list .entity-item[aria-level="1"][data-fn-id="' + selected + '"]');
    await row.click();
    assert(await page.evaluate(id => selectedFnId === id && !gdGraphEditsReady(), selected),
      'function selection works without enabling editing');
    await row.focus();
    await page.keyboard.press('.');
    assert(await page.evaluate(() => !document.activeElement.closest('.ns-row-actions')),
      'keyboard cannot enter pending row mutation controls');
    await page.evaluate(() => startRootCreate());
    assert(writes === 0 && await page.locator('#entity-list input').count() === 0,
      'direct create command stays closed before metadata');
    releaseName();
    await page.evaluate(() => window.__oldNavigation);
    assert(await page.evaluate(id => selectedFnId === id, selected), 'late old name resolution cannot steal selection');

    // Same-principal invalidation also matters (logout/login ABA or changed
    // grants): an epoch, not just equal account IDs, must reject old replies.
    await serviceArrived;
    await page.evaluate(() => {
      window.dispatchEvent(new Event('gd-auth-changed'));
      window.__newBootReady = false;
      void initGraph({progressive: true}).then(() => { window.__newBootReady = true; });
    });
    await page.waitForFunction(() => window.__newBootReady && graphTypesReady(), null, {timeout: 60000});
    assert(await page.evaluate(id => selectedFnId === id && gdGraphEditsReady(), selected),
      'fresh metadata and current selection release the editing fence');
    const oldTypesFinished = page.waitForResponse(response => new URL(response.url()).pathname === '/api/types');
    const oldServicesFinished = page.waitForResponse(response => /\/api\/(?:orgs\/)?services$/.test(new URL(response.url()).pathname));
    releaseTypes();
    releaseServices();
    await Promise.all([(await oldTypesFinished).finished(), (await oldServicesFinished).finished()]);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
    assert(await page.evaluate(() => !richTypes.obsoleteBoot && !servicesCache?.services?.some(s => s.id === 'obsolete-service')),
      'late metadata and services cannot repopulate the invalidated session');
    assert(writes === 0, 'navigation and pending commands made no mutation requests');
  } finally {
    releaseName(); releaseTypes(); releaseServices();
    await browser.close();
  }
  console.log('PASS progressive Explorer, delayed selection and authorization epochs');
})().catch(error => { console.error(error); process.exitCode = 1; });
