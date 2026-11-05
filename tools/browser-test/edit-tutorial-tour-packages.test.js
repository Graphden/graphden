// Lesson 31 reuses a loaded package in its owned sandbox. Source package
// loading and Docker setup remain operator workflows in the written lessons.
const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');
const {
  waitTourTitle, clickTourButton, openTutorialCatalog, filterAndSelect,
  extendViaRowActions, bindPlaceholderOn, runViaRowActions, finishAndDelete,
  tourWhere,
} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  let failed = false;
  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/?branch=main');
    await openTutorialCatalog(page);
    await page.locator('[data-lesson-id="31"] .gd-tour-btn-primary').click();
    await waitTourTitle(page, 'Explore an already loaded package', 150000);
    const sandbox = await page.evaluate(() => {
      const state = _tourState;
      const receipt = state.created.find(row => row.type === 'branch'
        && row.id === state.sandboxBranchId && row.name === state.sandboxBranch
        && row.receipt === 'created');
      return receipt && _tourPrincipalMatches(state)
        && _tourSessionBranch() === receipt.name ? {id: receipt.id, name: receipt.name} : null;
    });
    assert(sandbox?.id, 'chooser created and activated the exact owned sandbox');
    assert(await clickTourButton(page, 'Next'), 'start package exploration');
    await waitTourTitle(page, 'Find the package function');
    await filterAndSelect(page, 'str-upper', 'str-upper');
    await waitTourTitle(page, 'Reuse without editing the package');
    await extendViaRowActions(page, 'tutorial-package-upper', 'str-upper');
    await waitTourTitle(page, 'Supply your own input');
    await bindPlaceholderOn(page, 'tutorial-package-upper', 'string', 'literal', 'package reuse');
    await waitTourTitle(page, 'Execute the inherited implementation');
    await runViaRowActions(page);
    // The lesson advances only after a successful result-value check.
    await waitTourTitle(page, 'Return to the package function');
    await filterAndSelect(page, 'str-upper', 'str-upper');
    // Package-owned base fns are read-only: their free input is a Run field,
    // not a writable placeholder binder. Verify the selected source card.
    await page.waitForSelector('.node-overlay[data-fn-name="str-upper"] .ancestor-line[data-level="0"]',
      {timeout: 30000});
    await page.waitForFunction(() => window.graphView?.edgeList().some(
      (edge) => edge.data?.argName === 'string' && edge.data?.isUnset),
    null, {timeout: 30000, polling: 100});
    // The final `selected` check advances straight to cleanup once the
    // source card renders; there is no separate Finish button on this path.
    await finishAndDelete(page, 20000, {alreadyFinished: true});
    console.log('PASS: lesson31 package reuse + owned sandbox cleanup');
  } catch (error) {
    failed = true;
    console.error('FAIL:', error.message);
    console.error('Tour:', await tourWhere(page).catch(() => null));
    await page.screenshot({path: '/tmp/tour-packages-fail.png'}).catch(() => {});
  } finally {
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
