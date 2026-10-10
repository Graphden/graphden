// Real browser verification of the shipped graph/host without a server stack.
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');
(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    const page = await browser.newPage({viewport: {width: 500, height: 240}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('http://recents.test/', route => route.fulfill({contentType: 'text/html', body: '<html><body><div id="gd-recent-fns"></div></body></html>'}));
    await page.goto('http://recents.test/');
    await page.addStyleTag({path: path.resolve('resources/packages/app/editor/editor-styles.css')});
    await page.evaluate(() => {
      window.searchFilter = '';
      window.selectedFnId = 'a';
      window.gdNavigateToFn = (id, qname) => { window.destination = [id, qname]; };
      localStorage.setItem('graphden.recentFns', JSON.stringify([{id: 'a', name: 'add', qname: 'core.add'}, {id: 'b', name: 'map', qname: 'core.map'}]));
    });
    for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js', 'app/ui-preview/graph-styles.js', 'app/ui-preview/graph-renderer.js', 'app/ui-preview/builtin-plans.js', 'app/editor/editor-recents.js']) {
      await page.addScriptTag({path: path.resolve('resources/packages', file)});
    }
    await page.evaluate(() => renderRecentFns());
    const pin = page.locator('.gd-recent-pin');
    await pin.focus();
    await page.evaluate(() => { window.focusedPin = document.activeElement; });
    await pin.click();
    assert.equal(await page.locator('.gd-recent-row').textContent(), '★ map');
    assert.equal(await page.evaluate(() => focusedPin === document.activeElement), true, 'keyed pin survives moving from recent to pinned');
    await page.locator('.gd-recent-row').click();
    assert.deepEqual(await page.evaluate(() => destination), ['b', 'core.map']);
    assert.equal(await pin.getAttribute('aria-label'), 'Unpin core.map');
    assert.equal(await pin.evaluate(node => getComputedStyle(node).opacity), '1');
    await page.screenshot({path: '/tmp/recents-graph-local.png'});
    await page.evaluate(() => { searchFilter = 'add'; renderRecentFns(); });
    assert.equal(await page.locator('#gd-recent-fns').evaluate(node => node.hidden), true);
    await page.evaluate(() => gdDisposeRecents());
    assert.equal(await page.locator('[data-gd-ui-styles]').count(), 0, 'styles released');
    assert.deepEqual(errors, []);
    console.log('PASS real browser recents focus, navigation, scoped styles and cleanup');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
