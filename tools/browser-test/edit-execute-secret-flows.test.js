// The Runs tab's "Secret flows" chip: it narrows the history to the
// audit-trail rows through a re-fetch (`?secrets=1`), renders active,
// and its state survives switching to another fn's Runs tab for the
// page's life. Server side (the SQL filter, the 🔒 marker) is covered
// by editor_shell_partials_test; this is the click path.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

async function openRunsFor(page, fnNameHash) {
  await page.goto('about:blank');
  await page.goto(BASE + '/#' + fnNameHash);
  await page.waitForFunction(
    () => graphReady() && !!document.querySelector('button.more-actions-trigger') && !graph.animating,
    null, {timeout: 30000, polling: 100});
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  await page.waitForSelector('.row-actions-popover button', {timeout: 30000});
  const opened = await page.evaluate(() => {
    const runBtn = Array.from(document.querySelectorAll('.row-actions-popover button'))
      .find((b) => b.textContent.trim() === '▶');
    if (!runBtn) return false;
    runBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    return true;
  });
  if (!opened) throw new Error('▶ button not surfaced in row-actions');
  // The history panel mounts below the form with its rollup strip + chip.
  await page.waitForSelector('#gd-insp-runs .execute-history-secrets-toggle', {timeout: 20000});
}

function chipState(page) {
  return page.evaluate(() => {
    const chip = document.querySelector('#gd-insp-runs .execute-history-secrets-toggle');
    return chip ? {secrets: chip.getAttribute('data-secrets'),
                   active: chip.classList.contains('execute-history-secrets-toggle-active')} : null;
  });
}

(async () => {
  const {browser, page} = await newContext(chromium);
  try {
    console.log('edit-execute-secret-flows — the Runs tab chip');
    await openRunsFor(page, 'add');
    let s = await chipState(page);
    assert(s && s.secrets === '0' && !s.active, 'chip starts inactive (got ' + JSON.stringify(s) + ')');

    // Click → the panel re-fetches narrowed and the chip renders active.
    await page.click('#gd-insp-runs .execute-history-secrets-toggle');
    await page.waitForFunction(
      () => document.querySelector('#gd-insp-runs .execute-history-secrets-toggle')?.getAttribute('data-secrets') === '1',
      null, {timeout: 15000, polling: 100});
    s = await chipState(page);
    assert(s.active, 'chip renders active after the click');
    const narrowed = await page.evaluate(() => ({
      rows: document.querySelectorAll('#gd-insp-runs .execute-history-row').length,
      locks: document.querySelectorAll('#gd-insp-runs .execute-history-lock').length,
    }));
    assert(narrowed.rows === narrowed.locks,
           'every row of the narrowed list carries the 🔒 (rows ' + narrowed.rows + ', locks ' + narrowed.locks + ')');

    // Another fn's Runs tab keeps the narrowed view.
    await openRunsFor(page, 'sub');
    s = await chipState(page);
    assert(s && s.secrets === '1' && s.active, 'the chip state survives switching fns (got ' + JSON.stringify(s) + ')');

    // Click again → back to every run.
    await page.click('#gd-insp-runs .execute-history-secrets-toggle');
    await page.waitForFunction(
      () => document.querySelector('#gd-insp-runs .execute-history-secrets-toggle')?.getAttribute('data-secrets') === '0',
      null, {timeout: 15000, polling: 100});
    s = await chipState(page);
    assert(!s.active, 'chip renders inactive after the second click');
    console.log('  PASS');
  } catch (e) {
    console.log('FAIL: ' + (e && e.stack || e));
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
