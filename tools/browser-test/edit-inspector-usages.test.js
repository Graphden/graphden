// Real authorized usages responses exercise the graph-owned section and the
// Inspector's shared read owner. Held responses are released by request phase.
const {chromium} = require('playwright');
const {assert, newContext, waitFor, BASE} = require('./edit-test-helpers');
let phase = 'boot';
(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  const releases = new Set();
  page.on('pageerror', error => {
    errors.push(error.name);
    console.log('[Used-by frames]', JSON.stringify(error.stack.split('\n').slice(1, 5).map(line => {
      const match = line.match(/at ([\w.$<> ]+).*:(\d+):(\d+)/);
      return match ? {frame: match[1].trim(), line: Number(match[2]), column: Number(match[3])} : {frame: 'unknown'};
    })));
  });
  page.on('console', message => { if (message.type() === 'error') errors.push('console-error'); });
  try {
    const emptyUsages = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/fns/usages');
    await page.goto(BASE + '/#web-server');
    await page.waitForFunction(() => typeof lookups !== 'undefined' && lookups?.fnMap?.size > 1
      && typeof graphReady === 'function' && graphReady() && !graph.animating);
    const emptyPayload = await (await emptyUsages).json();
    assert(emptyPayload.usages.length > 0
      && emptyPayload.usages.every(row => row.kind === 'service-root' || row.kind === 'route-root'),
    'the app root has no fn callers but its enabled service is an incoming root reference');
    assert(emptyPayload.reachability?.state === 'reachable'
      && emptyPayload.reachability.path?.length === 1
      && emptyPayload.reachability.path[0]['fn-name'] === 'web-server',
    'the enabled service root is reachable from itself despite having no callers');
    await page.locator('.gd-insp-reachability').waitFor({state: 'visible'});
    assert(await page.locator('.gd-insp-usages').isVisible(),
      'root reachability stays visible with only an entry reference');
    phase = 'authorized-data';
    const selection = await page.evaluate(async () => {
      const priorities = ['const', 'get', 'if', 'list', 'str', 'zipmap'];
      const candidates = [...lookups.fnMap.values()].filter(fn => priorities.includes(fn.name))
        .sort((a, b) => priorities.indexOf(a.name) - priorities.indexOf(b.name));
      for (const fn of candidates) {
        const response = await authFetch(API.api_fns_usages, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({'fn-id': fn.id})});
        if (!response.ok) continue;
        const payload = await response.json();
        const unique = new Set(payload.usages?.map(row => [row.kind, row['fn-id'], row['slot-name'] || ''].join('|')));
        if (payload.ok !== false && unique.size > 30
          && payload.reachability?.state === 'reachable' && payload.reachability.path?.length > 1) {
          return {id: fn.id, count: payload.count};
        }
      }
      return null;
    });
    assert(!!selection, 'a real authorized dependency has more than thirty usages');
    await page.evaluate(id => gdInspectorRender(id), selection.id);
    const filter = page.locator('.gd-insp-usage-filter');
    await filter.waitFor({state: 'visible'});
    assert(await page.locator('.gd-insp-usages-count').textContent() === String(selection.count), 'full authorized count is retained');
    assert(await page.evaluate(() => [...document.querySelectorAll('.gd-insp-usage-group')].every(group => group.querySelectorAll('.gd-insp-usage-row').length <= 20)), 'each group retains its twenty-row cap');
    assert(await page.evaluate(() => GraphdenRenderer.ownsTarget(document.querySelector('[data-gd-usages-host]'))
      && !GraphdenRenderer.ownsTarget(document.getElementById('gd-insp-overview'))), 'Used by owns only its empty sibling host');
    await filter.fill('no-such-used-by-name-2718');
    assert(await page.locator('.gd-insp-usage-row:visible').count() === 0, 'graph filtering hides unmatched rows');
    await page.evaluate(() => { window.usagesFilter = document.querySelector('.gd-insp-usage-filter'); window.usagesRow = document.querySelector('.gd-insp-usage-row'); });
    await page.evaluate(() => gdInspectorGraph.reload());
    assert(await page.evaluate(() => window.usagesFilter === document.querySelector('.gd-insp-usage-filter')
      && window.usagesFilter.value === 'no-such-used-by-name-2718'
      && window.usagesFilter === document.activeElement), 'shared runtime refresh preserves filter identity, query and focus');
    await filter.press('Escape');
    assert(await filter.inputValue() === '' && await filter.evaluate(el => el === document.activeElement), 'first Escape clears and retains filter focus');
    assert(await page.evaluate(() => window.usagesFilter === document.querySelector('.gd-insp-usage-filter') && window.usagesRow === document.querySelector('.gd-insp-usage-row')), 'filter and row retain exact DOM identities');
    await filter.press('Escape');
    assert(await filter.evaluate(el => el !== document.activeElement), 'second Escape leaves the filter');
    phase = 'settings';
    await page.locator('.auth-avatar:visible, #auth-lock-btn:visible').first().click();
    await page.locator('.auth-menu-item[data-item="Settings"]').click();
    await page.locator('#gd-settings').waitFor({state: 'visible'});
    assert(await page.evaluate(() => document.querySelector('[data-gd-usages-host]').closest('[inert]') !== null && window.usagesFilter.isConnected), 'Settings makes the retained section inert');
    await page.keyboard.press('Escape');
    await page.locator('#gd-settings').waitFor({state: 'hidden'});
    phase = 'read-resume';
    const held = [];
    const routePath = '**/api/fns/usages';
    await page.route(routePath, async route => {
      const response = await route.fetch();
      let release;
      const ready = new Promise(resolve => { release = resolve; });
      held.push({release}); releases.add(release);
      await ready; releases.delete(release);
      await route.fulfill({response}).catch(() => {});
    });
    await page.setViewportSize({width: 1000, height: 850});
    await page.evaluate(id => gdInspectorRender(id), selection.id);
    assert(await waitFor(() => held.length === 1, 30000), 'real usages read reaches the controlled response boundary');
    await page.evaluate(() => { window.usagesHost = document.querySelector('[data-gd-usages-host]'); gdInspectorGraph.close(); });
    await page.setViewportSize({width: 1400, height: 900});
    assert(await waitFor(() => held.length === 2, 30000), 'wide viewport resumes the same owner with a fresh read');
    held[1].release();
    await filter.waitFor({state: 'visible'});
    await filter.fill('retained-query-3141');
    await page.evaluate(() => { window.resumedFilter = document.querySelector('.gd-insp-usage-filter'); });
    held[0].release();
    assert(await page.evaluate(() => window.usagesHost === document.querySelector('[data-gd-usages-host]')
      && window.resumedFilter === document.querySelector('.gd-insp-usage-filter')
      && window.resumedFilter.value === 'retained-query-3141'), 'late canceled read preserves exact host, input and query');
    await filter.press('Escape');
    phase = 'stale-context';
    const other = await page.evaluate(id => [...lookups.fnMap.values()].find(fn => fn.id !== id && fn.name)?.id, selection.id);
    assert(!!other, 'a second actual function identity is available');
    await page.evaluate(id => gdInspectorRender(id), selection.id);
    assert(await waitFor(() => held.length === 3, 30000), 'next selection starts a fresh owned read');
    await page.evaluate(id => gdInspectorRender(id), other);
    assert(await waitFor(() => held.length === 4, 30000), 'B starts its own authorized usages read');
    await page.evaluate(id => gdInspectorRender(id), selection.id);
    assert(await waitFor(() => held.length === 5, 30000), 'newest A gets a distinct read generation');
    held[4].release();
    await filter.waitFor({state: 'visible'});
    await page.evaluate(() => { window.newestUsages = document.querySelector('[data-gd-usages-host]'); });
    held[2].release(); held[3].release();
    assert(await page.evaluate(() => window.newestUsages === document.querySelector('[data-gd-usages-host]')
      && document.getElementById('gd-inspector').dataset.fnId !== ''), 'late A/B responses cannot replace the newest A host');
    await page.evaluate(id => gdInspectorRender(id), selection.id);
    assert(await waitFor(() => held.length === 6, 30000), 'context-reset read reaches its controlled boundary');
    await page.evaluate(() => { window.disposedUsages = document.querySelector('[data-gd-usages-host]'); gdInspectorContextChanged(); });
    held[5].release();
    assert(await page.evaluate(() => !window.disposedUsages.isConnected && !document.querySelector('[data-gd-usages-host]')), 'context reset disposes the pending section before detach');
    await page.evaluate(id => gdInspectorRender(id), selection.id);
    assert(await waitFor(() => held.length === 7, 30000), 'account-reset read reaches its controlled boundary');
    await page.evaluate(() => { window.accountUsages = document.querySelector('[data-gd-usages-host]'); window.dispatchEvent(new Event('gd-auth-changed')); });
    held[6].release();
    assert(await page.evaluate(() => !window.accountUsages.isConnected && !document.querySelector('[data-gd-usages-host]')), 'account change disposes its pending owner');
    await page.unroute(routePath);
    // Auth replacement clears the old graph cache. Re-prime the server tree
    // before exercising navigation under the new (test) session.
    await page.evaluate(() => initGraph());
    await page.evaluate(id => ensureSubtreeFor(id), selection.id);
    phase = 'navigation';
    await page.evaluate(id => gdInspectorRender(id), selection.id);
    await filter.waitFor({state: 'visible'});
    const row = page.locator('.gd-insp-usage-row:visible').first();
    const target = await row.getAttribute('data-usage-id');
    assert(!!await row.getAttribute('aria-label'), 'real row has its graph-authored accessible label');
    await row.click();
    await page.waitForFunction(id => document.getElementById('gd-inspector').dataset.fnId === id, target);
    assert(await page.locator('#gd-inspector').getAttribute('data-fn-id') === target, 'finite navigation selects the actual authorized caller identity');
    phase = 'root-path-navigation';
    await page.evaluate(id => gdInspectorRender(id), selection.id);
    const rootStep = page.locator('.gd-insp-path-step').first();
    await rootStep.waitFor({state: 'visible'});
    const rootId = await rootStep.getAttribute('data-usage-id');
    assert(!!rootId && rootId !== selection.id, 'the graph renders a navigable root-to-target path');
    await rootStep.click();
    await page.waitForFunction(id => document.getElementById('gd-inspector').dataset.fnId === id, rootId);
    assert(await page.locator('#gd-inspector').getAttribute('data-fn-id') === rootId,
      'root-path navigation selects the authorized root identity');
    assert(errors.length === 0, 'Used-by lifecycle produces no page or console errors');
  } catch (error) {
    console.error('Inspector Used-by proof failed', JSON.stringify({phase, type: error.name}));
    process.exitCode = 1;
  } finally {
    for (const release of releases) release();
    await browser.close();
  }
})();
