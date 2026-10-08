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
// Uses a freshly-created fn parented to `:const` (smallest possible
// graph; just one fn-card with one `⋯` trigger).
//
// Run from this directory:  node edit-row-actions-pin.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, api, getEntities, newContext, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'row-actions-pin-probe' + RUN_ID;
const PROBE_DESC = 'Description text long enough to wrap across several lines. '.repeat(24).trim();


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
  const {browser, page} = await newContext(chromium, {boot: false});
  console.log('edit-row-actions-pin — toggle / outside-click / Escape dismiss');

  try {
    await cleanup(page);

    const ents = await getEntities(page, 'const');
    // `const` may have a parent-ids chain in the test e2e baseline;
    // just pick whichever entry matches by name.
    const constFn = ents.fns.find((f) => f.name === 'const');
    assert(constFn, ':const baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + constFn.id
              + '&description=' + encodeURIComponent(PROBE_DESC));

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
    await page.waitForSelector('.row-actions-popover button', {timeout: 20000});
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
    await page.waitForSelector('.row-actions-popover button', {timeout: 20000});
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
    await page.waitForSelector('.row-actions-popover button', {timeout: 20000});
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

    // Description owns Escape before its parent row menu and the active tour.
    await page.evaluate(() => startTutorial('26'));
    await page.waitForSelector('#gd-tour-pop', {state: 'visible'});
    const tourTitle = await page.locator('#gd-tour-pop .gd-tour-title').textContent();
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    const description = page.locator('.row-actions-popover [data-action="description"]');
    await description.click();
    await page.waitForSelector('.description-tooltip-close', {state: 'visible'});
    await description.click();
    assert(await page.locator('.description-tooltip').isHidden(),
           'second Description activation hides the pinned tooltip');

    await description.click();
    const edit = page.locator('.description-tooltip-btn').filter({hasText: 'Edit'});
    // Keyboard activation retains the parent menu; clicking outside it would
    // independently dismiss it through its pointer handler.
    await edit.focus();
    await edit.press('Enter');
    await page.fill('.description-tooltip-textarea', 'draft to discard');
    await page.locator('.description-tooltip-textarea').evaluate((el) => { el.style.height = '400px'; });
    await page.setViewportSize({width: 390, height: 240});
    const tooltipInViewport = () => {
      const r = document.querySelector('.description-tooltip').getBoundingClientRect();
      return r.left >= 11 && r.right <= innerWidth - 11
        && r.top >= 11 && r.bottom <= innerHeight - 11;
    };
    await page.waitForFunction(tooltipInViewport);
    assert(await page.evaluate(tooltipInViewport),
           'resizing to 390x240 keeps the tall description editor inside the viewport');
    await page.locator('.description-tooltip-btn-secondary').focus();
    assert(await page.locator('.description-tooltip-btn-secondary').evaluate((el) => {
      const r = el.getBoundingClientRect();
      return r.top >= 12 && r.bottom <= innerHeight - 12;
    }), 'Cancel remains reachable through the short editor\'s scroll area');
    await page.setViewportSize({width: 1400, height: 900});
    await page.waitForFunction(() => document.querySelector(
      '.description-tooltip').getBoundingClientRect().left > 390);
    await description.click();
    assert(await page.locator('.description-tooltip-textarea').inputValue() === 'draft to discard',
           'activating Description while editing retains the pinned draft');
    // Hold the actual save request: Escape must obey disabled Cancel until
    // the server responds, rather than pretending to cancel an ongoing write.
    let pendingSaveRoute;
    let signalSave;
    const saveRequested = new Promise((resolve) => { signalSave = resolve; });
    await page.route('**/api/entities/fn/*', (route) => {
      if (route.request().method() !== 'PUT') return route.continue();
      pendingSaveRoute = route;
      signalSave();
    });
    const save = page.locator('.description-tooltip-btn').filter({hasText: /^Save$/});
    await save.focus();
    await save.press('Enter');
    await saveRequested;
    await page.locator('.description-tooltip-textarea').focus();
    await page.keyboard.press('Escape');
    assert(await page.locator('.description-tooltip-textarea').inputValue() === 'draft to discard',
           'Escape retains the draft while its save is pending');
    assert(await page.locator('.description-tooltip-btn-secondary').isDisabled(),
           'pending save keeps Cancel disabled');
    assert(await popoverVisible(page), 'pending-save Escape leaves the parent menu open');
    assert(await page.locator('#gd-tour-pop .gd-tour-title').textContent() === tourTitle,
           'pending-save Escape leaves the tour on the same step');
    await pendingSaveRoute.fulfill({status: 403, contentType: 'text/html',
      body: '<p class="error">Controlled save refusal</p>'});
    await page.waitForFunction(() => !document.querySelector(
      '.description-tooltip-btn-secondary').disabled);
    await page.unroute('**/api/entities/fn/*');
    await page.setViewportSize({width: 390, height: 240});
    await page.waitForFunction(tooltipInViewport);
    await page.keyboard.press('Escape');
    assert(await page.locator('.description-tooltip-textarea').count() === 0,
           'first Escape discards the description draft');
    assert(await page.locator('.description-tooltip-body').textContent() === PROBE_DESC,
           'Escape restores the saved description');
    assert(await edit.evaluate((el) => el === document.activeElement),
           'Escape returns focus to Edit after replacing the textarea');
    assert(await page.evaluate(tooltipInViewport),
           'long read mode after cancellation retains viewport margins at 390x240');
    assert(await edit.evaluate((el) => {
      const r = el.getBoundingClientRect();
      return r.top >= 12 && r.bottom <= innerHeight - 12;
    }), 'Edit remains visible after focus scrolls the long read surface');
    assert(await popoverVisible(page), 'description Escape leaves its parent row menu open');
    assert(await page.locator('#gd-tour-pop .gd-tour-title').textContent() === tourTitle,
           'cancelling the draft leaves the running tour on the same step');

    await page.keyboard.press('Escape');
    assert(await page.locator('.description-tooltip').isHidden(),
           'second Escape closes the pinned description');
    assert(await popoverVisible(page), 'second Escape belongs only to the description');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => getComputedStyle(
      document.querySelector('.row-actions-popover')).display === 'none');
    assert(await page.locator('#gd-tour-pop .gd-tour-title').textContent() === tourTitle,
           'closing the remaining row menu still leaves the tour running');
    await page.evaluate(() => _tourTeardown());

    console.log('✓ row-actions pin verified — toggle / outside / Escape');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
