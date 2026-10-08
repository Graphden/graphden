// Lesson 38: a stored fn-ref resolves to a real public URL. The browser
// receives both graph revisions, then verifies exact publication revocation.
'use strict';
const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');
const {handlerPreviewTestOptions} = require('./handler-preview-test-options');
const {
  hardCleanup, waitTourTitle, clickTourButton, filterAndSelect,
  extendViaRowActions, finishAndDelete, bindNamedPlaceholder,
  runWithEffectAck, editBoundValue, tourWhere,
} = require('./tutorial-tour-helpers');
const {
  trackPublications, createPublication, openPublicResponse, stopPublication,
} = require('./tutorial-http-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {...handlerPreviewTestOptions(), boot: false});
  page.on('dialog', d => { d.accept().catch(() => {}); });
  const cleanup = trackPublications(page);
  let failed = false;
  const base = process.env.GRAPHDEN_URL || 'http://localhost:9002';
  try {
    await hardCleanup(page);
    await page.goto(base + '/?tutorial=38');
    await waitTourTitle(page, 'The graph keeps the identity', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 38 introduction');
    const publishedUrl = await createPublication(page, 'tutorial-contract-answer', 'hello');
    await waitTourTitle(page, 'Open service-endpoint');
    await filterAndSelect(page, 'service-endpoint', 'service-endpoint');
    await waitTourTitle(page, 'Make your own caller');
    await extendViaRowActions(page, 'tutorial-http-address', 'service-endpoint');
    await waitTourTitle(page, 'Bind the handler identity', 150000);
    await bindNamedPlaceholder(page, 'service', 'fn-ref', 'tutorial-contract-answer');
    await waitTourTitle(page, 'Resolve the public address');
    await runWithEffectAck(page, undefined, 'tutorial-http-address');
    const raw = '.execute-popover.visible .execute-result-host .execute-result-raw pre';
    await page.waitForFunction(selector => {
      const el = document.querySelector(selector);
      try { return !!JSON.parse(el?.textContent).url; } catch (_) { return false; }
    }, raw, {timeout: 60000});
    const result = JSON.parse(await page.textContent(raw));
    assert(result.url === publishedUrl, 'fn-ref resolves the exact branch publication URL');
    assert(await clickTourButton(page, 'Next'), 'address Run finished');
    await waitTourTitle(page, 'Read the first response');
    const external = await openPublicResponse(page, result.url, 'hello');
    try {
      assert(await clickTourButton(page, 'Next'), 'first actual response read');
      await waitTourTitle(page, 'Open tutorial-contract-answer');
      await filterAndSelect(page, 'tutorial-contract-answer', 'tutorial-contract-answer');
      await waitTourTitle(page, 'Change the response graph');
      await editBoundValue(page, 'updated');
      assert(await clickTourButton(page, 'Next'), 'changed graph saved');
      await waitTourTitle(page, 'Read the changed response');
      const changed = await external.reload();
      assert(changed.status() === 200, 'same URL remains available');
      assert((await external.locator('body').innerText()).trim() === 'updated',
        'real HTTP response follows the graph change');
      assert(await clickTourButton(page, 'Next'), 'changed response read');
      await stopPublication(page, 'tutorial-contract-answer', external);
      await finishAndDelete(page);
    } finally {
      await external.close();
    }
    console.log('PASS: lesson 38 resolved, requested, edited, requested again, stopped and cleaned');
  } catch (error) {
    failed = true;
    console.error(error.stack || error);
    console.error('Tour at failure: ' + await tourWhere(page));
  } finally {
    try { await cleanup(); await hardCleanup(page); }
    catch (error) { failed = true; console.error(error); }
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
