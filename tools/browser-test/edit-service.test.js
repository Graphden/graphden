// Service registry UI smoke — pinned scope until :schedule lands.
//
//   Phase A: discoverability — ⚙ button visible in row-actions popover
//            for a no-free-args fn
//   Phase B: form renders — click ⚙ opens popover with "Make service"
//            title + "Create & reconcile" save button
//   Phase C: :process validation — Save on :current-time-ms (no
//            :process effect declared) triggers the server-side
//            rejection alert dialog
//
// Uses :current-time-ms — zero free args, NOT :process-eligible
// (the test exercises the rejection path on purpose).
//
// TODO: restore the full lifecycle (create + badge + edit + sidebar
// filter + delete) once the cron commit lands the :schedule base-fn,
// providing a :process-declaring callable for the test to target
// without conflicting with the bound web-server port.
//
// Run from this directory:  node edit-service.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, waitFor} = require('./edit-test-helpers');


const SERVICE_FN = 'core.system.current-time-ms';


async function openRowActionsPopover(page, fnHash) {
  await page.goto('about:blank');
  await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + fnHash);
  // Wait for the card to be rendered with its `⋯` trigger. Cytoscape
  // also animates the post-mount fit, which moves overlays — wait for
  // the animation queue to drain before clicking, otherwise playwright's
  // stability autowait can race.
  await page.waitForFunction(
    () => graphReady()
          && !!document.querySelector('button.more-actions-trigger')
          && !graph.animating,
    null,
    {timeout: 20000, polling: 100});
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  await page.waitForSelector('.row-actions-popover', {timeout: 5000});
}


async function clickGearButton(page) {
  return page.evaluate(() => {
    const popover = document.querySelector('.row-actions-popover');
    const gear = Array.from(popover?.querySelectorAll('button') || [])
      .find((b) => b.textContent.trim() === '⚙');
    if (!gear || gear.getAttribute('aria-disabled') === 'true') return false;
    gear.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    return true;
  });
}


async function deleteAnyExistingServiceFor(page, fnName) {
  // Defensive cleanup — if a prior failed run left a stale service
  // row, remove it before the test sets up its own state.
  return page.evaluate(async (name) => {
    const list = await authFetch('/api/services', {method: 'GET'});
    if (!list.ok) return 'fetch-failed';
    const body = await list.json();
    const target = body.services?.find((s) => s['fn-name'] === name);
    if (!target) return 'none';
    await authFetch('/api/entities/service/' + encodeURIComponent(target.id),
                    {method: 'DELETE'});
    await authFetch('/api/services/reconcile', {method: 'POST'});
    return 'cleaned';
  }, fnName);
}


(async () => {
  const {browser, page} = await newContext(chromium);
  // Auto-accept any window.confirm — the delete button asks for
  // confirmation.
  page.on('dialog', (d) => {
    console.log('  (dialog: ' + d.type() + ': ' + d.message().slice(0, 200) + ')');
    d.accept();
  });
  page.on('console', (m) => {
    if (m.type() === 'error') console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
  });
  console.log('edit-service — ⚙ button, create / badge / sidebar filter / delete');
  try {
    // Defensive cleanup before we start.
    const cleanup = await deleteAnyExistingServiceFor(page, 'current-time-ms');
    console.log('  (pre-test cleanup: ' + cleanup + ')');

    // === Phase A: ⚙ visible in row-actions popover ===
    // :current-time-ms has no :process effect declared, so the server
    // refuses to create a service row. The save button click triggers
    // an alert dialog whose message names the missing effect — we
    // assert that copy so the test still catches a regression where
    // some earlier guard swallows the rejection silently.
    let rejectionAlertSeen = false;
    page.on('dialog', (d) => {
      if (d.type() === 'alert' && d.message().includes(':process effect')) {
        rejectionAlertSeen = true;
      }
    });

    await openRowActionsPopover(page, SERVICE_FN);
    const gearState = await page.evaluate(() => {
      const popover = document.querySelector('.row-actions-popover');
      const gear = Array.from(popover?.querySelectorAll('button') || [])
        .find((b) => b.textContent.trim() === '⚙');
      return gear
        ? {exists: true,
           disabled: gear.getAttribute('aria-disabled') === 'true',
           title: gear.title}
        : {exists: false};
    });
    assert(gearState.exists, '⚙ button rendered in row-actions popover');
    assert(!gearState.disabled,
           '⚙ button enabled — :current-time-ms has zero free args');

    // === Phase B: open service popover ===
    const opened = await clickGearButton(page);
    assert(opened, '⚙ click dispatched');
    // Popover loads through htmx fetch of /partials/service-popover —
    // 500ms is not enough under e2e contention.
    await page.waitForSelector('.service-popover.visible', {timeout: 5000});

    const popoverState = await page.evaluate(() => {
      const p = document.querySelector('.service-popover.visible');
      if (!p) return {visible: false};
      const title = p.querySelector('.service-popover-title')?.textContent;
      const saveBtn = p.querySelector('.service-popover-save-btn');
      const deleteBtn = p.querySelector('.service-popover-delete-btn');
      return {visible: true, title,
              saveLabel: saveBtn?.textContent,
              deleteBtnExists: !!deleteBtn};
    });
    assert(popoverState.visible, 'service popover opened');
    assert(popoverState.title?.includes('Make service'),
           'title reads "Make service:" (no existing row, got '
           + popoverState.title + ')');
    assert(popoverState.saveLabel === 'Create & reconcile',
           'save button shows the create variant (got '
           + popoverState.saveLabel + ')');
    assert(!popoverState.deleteBtnExists,
           'no Delete button — nothing to delete yet');

    // === Phase C: :process validation rejects on save ===
    // :current-time-ms has no :process effect declared, so the server
    // refuses to create a service row. The save button click triggers
    // an alert dialog (caught by our handler above).
    await page.evaluate(() => {
      document.querySelector('.service-popover-save-btn').click();
    });
    // Poll the Node-side flag set by the dialog handler — bounded
    // 10s is enough for POST + 400 + alert under e2e load.
    await waitFor(() => rejectionAlertSeen, 10000);
    assert(rejectionAlertSeen,
           ':process validation rejection alert appeared');

    // Confirm via API that no row was created.
    const persistedCheck = await page.evaluate(async () => {
      const r = await authFetch('/api/services', {method: 'GET'});
      const body = await r.json();
      return body.services?.find((s) => s['fn-name'] === 'current-time-ms');
    });
    assert(!persistedCheck,
           'no :service row was created (validation rejected the request)');

    console.log('✓ service registry UI smoke verified '
                + '(scope pinned until :schedule lands — see file header TODO)');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
    // Best-effort cleanup so a fail doesn't leave the docker DB
    // contaminated for the next run.
    try {
      await deleteAnyExistingServiceFor(page, 'current-time-ms');
      console.error('  (post-fail cleanup ran)');
    } catch (_) {}
  } finally {
    await browser.close();
  }
})();
