// «Catch next request» trap on a service fn's Runs tab (2026-09-04 —
// the diagnostics drawer under the canvas is gone; the trap block is
// composed into the Runs history of a fn that is a SERVICE on the
// branch):
//
//   1. GET /api/services → a service fn on the stack (the demo's web
//      server); select it, open the Inspector's Runs tab → the trap
//      block is there with [Catch next request].
//   2. Arm → the block shows the armed line with Cancel; cancel → the
//      arm form is back.
//   3. A fn that is not a service (add) has no block on its Runs tab.
//   4. The drawer is gone: no #gd-diag-drawer on the page.
//
// Run from this directory:  node edit-run-trap.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api} = require('./edit-test-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

async function openRuns(page, name) {
  await page.goto(BASE + '/#' + name);
  await page.reload();
  await page.waitForFunction(
    () => graphReady() && !!document.querySelector('button.more-actions-trigger') && !graph.animating,
    null, {timeout: 30000, polling: 100});
  await page.waitForSelector('[data-insp-tab="stats"]', {timeout: 15000});
  await page.evaluate(() => document.querySelector('[data-insp-tab="stats"]').click());
  await page.waitForSelector('#gd-insp-runs .execute-history-panel', {timeout: 30000});
}

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => d.accept());
  console.log('edit-run-trap — the catch-next-request trap on a service fn\'s Runs tab');
  try {
    const services = await api(page, 'GET', '/api/services');
    const rows = Array.isArray(services) ? services : (services?.services || []);
    const svc = rows.find((s) => s['fn-name']);
    assert(svc, 'the stack has at least one service fn (' + JSON.stringify(rows).slice(0, 120) + ')');

    await openRuns(page, svc['fn-name']);
    await page.waitForSelector('#gd-insp-runs .gd-run-trap #gd-debug-arm', {timeout: 30000});
    const noDrawer = await page.evaluate(() => !document.getElementById('gd-diag-drawer'));
    assert(noDrawer, 'the diagnostics drawer is gone from the page');

    // Make sure no trap is armed from an earlier run, then arm.
    await api(page, 'POST', '/api/debug/catch/cancel');
    await page.evaluate(() => document.querySelector('#gd-insp-runs .gd-run-trap #gd-debug-arm').click());
    await page.waitForSelector('#gd-insp-runs .gd-run-trap .debug-armed-line', {timeout: 30000});
    const armed = await page.evaluate(() =>
      document.querySelector('#gd-insp-runs .gd-run-trap .debug-armed-line')?.textContent || '');
    assert(/Armed/.test(armed), 'arming shows the armed line (' + armed.slice(0, 60) + ')');
    const status = await api(page, 'GET', '/api/debug/catch/status');
    assert(status && status.armed === true, 'the server reports the trap armed (' + JSON.stringify(status) + ')');

    await page.evaluate(() => document.querySelector('#gd-insp-runs .gd-run-trap #gd-debug-cancel').click());
    await page.waitForSelector('#gd-insp-runs .gd-run-trap #gd-debug-arm', {timeout: 30000});
    const after = await api(page, 'GET', '/api/debug/catch/status');
    assert(after && after.armed === false, 'cancel disarms it (' + JSON.stringify(after) + ')');

    await openRuns(page, 'add');
    const noBlock = await page.evaluate(() => !document.querySelector('#gd-insp-runs .gd-run-trap'));
    assert(noBlock, 'a fn that is not a service has no trap block on its Runs tab');

    console.log('PASS');
  } catch (e) {
    console.error('FAIL: ' + e.message);
    process.exitCode = 1;
  } finally {
    try { await api(page, 'POST', '/api/debug/catch/cancel'); } catch (_) {}
    await browser.close();
  }
})();
