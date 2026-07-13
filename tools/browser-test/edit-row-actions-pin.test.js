// Row-actions popover sticky + dismiss e2e.
//
// Coverage:
//   • Click ⋯ trigger → popover opens (sticky / pinned).
//   • Click trigger AGAIN → popover hides
//     (toggleRowActionsPopoverSticky returns to non-pinned).
//   • Click ⋯ + click outside → popover hides (document mousedown
//     dismiss handler).
//   • Click ⋯ + Escape → popover hides, anchor aria-expanded back
//     to "false".
//
// Uses a freshly-created fn parented to `:identity` (smallest possible
// graph; just one fn-card with one `⋯` trigger).
//
// Run from this directory:  node edit-row-actions-pin.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, api, getEntities, newContext, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'row-actions-pin-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, PROBE_FN); } catch (_) {}
}


async function popoverVisible(page) {
  return page.evaluate(() => {
    const p = document.querySelector('.row-actions-popover');
    if (!p) return false;
    const style = window.getComputedStyle(p);
    return style.display !== 'none' && style.visibility !== 'hidden';
  });
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-row-actions-pin — toggle / outside-click / Escape dismiss');

  try {
    await cleanup(page);

    const ents = await getEntities(page);
    // `identity` may have a parent-ids chain in the test e2e baseline;
    // just pick whichever entry matches by name.
    const identity = ents.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + identity.id);

    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')
                    + '/#' + PROBE_FN);
    await page.waitForFunction(
      () => graphReady()
            && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null,
      {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A: click ⋯ → popover opens, anchor aria-expanded=true.
    // ===================================================================
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover', {timeout: 5000});
    assert(await popoverVisible(page),
           'popover visible after first ⋯ click');
    const ariaA = await page.evaluate(() =>
      document.querySelector('button.more-actions-trigger')
        .getAttribute('aria-expanded'));
    assert(ariaA === 'true',
           'trigger aria-expanded="true" after first click: ' + ariaA);

    // ===================================================================
    // Phase B: click trigger AGAIN → toggleSticky → hide.
    // ===================================================================
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForFunction(
      () => {
        const p = document.querySelector('.row-actions-popover');
        if (!p) return true;
        const style = window.getComputedStyle(p);
        return style.display === 'none' || style.visibility === 'hidden';
      },
      null,
      {timeout: 3000, polling: 50});
    assert(!(await popoverVisible(page)),
           'popover hidden after second ⋯ click (sticky toggle)');
    const ariaB = await page.evaluate(() =>
      document.querySelector('button.more-actions-trigger')
        .getAttribute('aria-expanded'));
    assert(ariaB === 'false',
           'trigger aria-expanded="false" after toggle off: ' + ariaB);

    // ===================================================================
    // Phase C: outside-click dismiss.
    // ===================================================================
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover', {timeout: 5000});
    assert(await popoverVisible(page),
           'popover visible after Phase C re-open');
    // Click body somewhere safe — top-left corner avoids any overlay
    // anchored to the fn-card. capture=true so the document mousedown
    // handler picks it up before any other listener.
    await page.mouse.click(2, 2);
    await page.waitForFunction(
      () => {
        const p = document.querySelector('.row-actions-popover');
        if (!p) return true;
        const style = window.getComputedStyle(p);
        return style.display === 'none' || style.visibility === 'hidden';
      },
      null,
      {timeout: 3000, polling: 50});
    assert(!(await popoverVisible(page)),
           'popover dismissed by outside click');

    // ===================================================================
    // Phase D: Escape dismiss.
    // ===================================================================
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover', {timeout: 5000});
    assert(await popoverVisible(page),
           'popover visible after Phase D re-open');
    await page.keyboard.press('Escape');
    await page.waitForFunction(
      () => {
        const p = document.querySelector('.row-actions-popover');
        if (!p) return true;
        const style = window.getComputedStyle(p);
        return style.display === 'none' || style.visibility === 'hidden';
      },
      null,
      {timeout: 3000, polling: 50});
    assert(!(await popoverVisible(page)),
           'popover dismissed by Escape');
    const ariaD = await page.evaluate(() =>
      document.querySelector('button.more-actions-trigger')
        .getAttribute('aria-expanded'));
    assert(ariaD === 'false',
           'trigger aria-expanded="false" after Escape: ' + ariaD);

    console.log('✓ row-actions pin verified — toggle / outside / Escape');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
