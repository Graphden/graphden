'use strict';

// Exercise the graph-backed trail inside the actual editor, without DB writes.
const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  const errors = [];
  let stage = 'load';
  page.on('pageerror', () => errors.push('pageerror'));
  try {
    await page.waitForFunction(() => typeof lookups !== 'undefined' && lookups?.fnMap?.size
      && typeof gdLoadRecents === 'function');
    stage = 'fixtures';
    const entries = await page.evaluate(async () => {
      const namespace = [...lookups.nsPathMap].find(([, path]) => path === 'core.arithmetic');
      if (!namespace) throw new Error('Missing shipped navigation namespace');
      await loadNamespaceFns(namespace[0]);
      const rows = [...lookups.fnMap.values()].filter(fn => fn.name && !fn.name.startsWith('_')
        && getQualifiedFnName(fn).startsWith('core.arithmetic.')).slice(0, 3);
      if (rows.length !== 3) throw new Error('Missing shipped navigation fixtures');
      const entries = rows.map(fn => ({id: fn.id, name: fn.name, qname: getQualifiedFnName(fn)}));
      localStorage.setItem('graphden.recentFns', JSON.stringify(entries));
      localStorage.setItem('graphden.pinnedFns', '[]');
      renderRecentFns();
      return entries;
    });
    stage = 'render';
    const host = page.locator('#gd-recent-fns');
    await host.waitFor({state: 'visible'});
    assert(await host.locator('.gd-recent-row').count() === 3, 'shipped graph renders recent destinations');
    const pin = host.locator('.gd-recent-pin').first();
    await pin.focus();
    await pin.press('Enter');
    assert(await host.locator('.gd-recent-pin').first().getAttribute('title') === 'Unpin', 'pin transition updates the graph');
    assert(await host.locator('.gd-recent-row').count() === 3, 'pinned function is not duplicated in recent rows');
    assert(await host.locator('.gd-recent-pin').first().evaluate(el => el === document.activeElement), 'keyed row retains keyboard focus');
    const persisted = await page.evaluate(() => JSON.parse(localStorage.getItem('graphden.pinnedFns')));
    assert(persisted[0]?.id === entries[0].id && persisted[0]?.name === entries[0].name, 'graph values persist with their fields');
    await host.locator('.gd-recent-row').nth(1).click();
    await page.waitForFunction(id => selectedFnId === id, entries[1].id);
    await page.waitForFunction(id => !document.querySelector('#gd-recent-fns .gd-recent-row[data-fn-id="' + id + '"]'), entries[1].id);
    assert(await host.locator('.gd-recent-row').count() === 2, 'navigation excludes the current unpinned function');
    await page.evaluate(() => { searchFilter = 'trail-test'; renderRecentFns(); });
    assert(await host.isHidden(), 'search leaves the match list in control');
    await page.evaluate(() => { searchFilter = ''; renderRecentFns(); });
    assert(await host.isVisible(), 'clearing search restores the trail');
    await page.screenshot({path: '/tmp/graphden-recents-editor.png'});
    assert(errors.length === 0, 'no browser execution errors');
    console.log('PASS graph-backed recents in the editor');
  } catch (error) {
    console.error(JSON.stringify({failure: 'editor-recents', stage, kind: error.name}));
    await page.screenshot({path: '/tmp/graphden-recents-editor-failure.png'}).catch(() => {});
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
