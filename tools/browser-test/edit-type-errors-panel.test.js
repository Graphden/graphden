// Type-errors editor surfaces (Error Tolerance, Phase 3):
//
// 1. A type-breaking binding write (tolerated + recorded since Phase 2)
//    makes GET /partials/type-errors list the fn.
// 2. The editor shows it: the Type-errors sidebar section renders the
//    server partial with the fn's row, and the fn-card root row carries
//    the ⚠ type-error badge (server-computed `:type-error-count` on the
//    subtree payload).
// 3. Fixing the binding clears the diagnostic — the partial empties.
//
// Run from this directory:  node edit-type-errors-panel.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, nodeApi,
       deleteFnByName, waitFor} = require('./edit-test-helpers');

const TEST_NAME = 'test-type-errors-panel';

async function typeErrorsPartial() {
  const r = await nodeApi('GET', '/partials/type-errors');
  return r.text();
}

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('type-errors-panel — record → panel row + card badge → fix → empty');
  try {
    await deleteFnByName(page, TEST_NAME);

    // Build the broken fn: parent = http-server (:port is a refined
    // :int), bind :port to a string → the post-write aggregate check
    // fails, the write is KEPT and a per-branch diagnostic recorded.
    const ents = await getEntities(page, 'http-server');
    const httpServer = ents.fns.find(f => f.name === 'http-server');
    const portArg = synthArgs(ents).find(
      a => a['fn-id'] === httpServer.id && a.name === 'port' && !a['source-id']);
    assert(httpServer && portArg, 'http-server.port baseline resolved');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + TEST_NAME + '&parent-ids=' + httpServer.id);
    const fn = (await getEntities(page, TEST_NAME)).fns.find(f => f.name === TEST_NAME);
    assert(fn, 'test fn created');

    const bindResp = await api(page, 'POST', '/api/entities/binding',
                               'fn-id=' + fn.id + '&slot-id=' + portArg['slot-id'] +
                               '&value=' + encodeURIComponent('"oops"'));
    const warnings = bindResp['type-warnings']
      || (bindResp.body && bindResp.body[0] === '{'
          && JSON.parse(bindResp.body)['type-warnings']);
    assert(Array.isArray(warnings) && warnings.length > 0,
           'type-breaking binding saved WITH warnings: '
           + JSON.stringify(bindResp).slice(0, 200));

    // === Server partial lists the fn ===
    const listed = await waitFor(async () => {
      const body = await typeErrorsPartial();
      return body.includes('#' + TEST_NAME);
    }, 10000);
    assert(listed, '/partials/type-errors lists ' + TEST_NAME);

    // === Editor surfaces ===
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/#' + TEST_NAME);
    await page.waitForFunction(
      () => graphReady()
            && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null,
      {timeout: 30000, polling: 100});

    // Card root row carries the ⚠ badge (subtree payload's
    // :type-error-count > 0).
    const badgeSeen = await waitFor(
      () => page.evaluate(() => !!document.querySelector('.type-error-badge')),
      15000);
    assert(badgeSeen, 'type-error badge visible on the fn card root row');

    // The Type-errors panel lives in the Build diagnostics drawer. Open its
    // tab the way a reader does — opening the drawer is also what re-fetches
    // the live panels (reloadDiagnosticsSections), so the row's presence is
    // deterministic rather than riding the boot-mount fetch.
    await page.waitForSelector('#gd-diag-nav button[data-section="type-errors"]',
      {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('#gd-diag-nav button[data-section="type-errors"]').click();
    });
    const panelRow = await waitFor(
      () => page.evaluate((name) => {
        const section = document.querySelector('.sidebar-type-errors');
        if (!section) return false;
        const row = section.querySelector('.type-errors-row .type-errors-fn');
        return !!row && row.textContent.includes(name);
      }, TEST_NAME),
      20000);
    assert(panelRow, 'Type errors sidebar panel lists the fn');

    // === Fix → the diagnostic clears, the panel empties ===
    const port = synthArgs(await getEntities(page, fn.id))
                   .find(a => a['fn-id'] === fn.id && a['slot-id'] === portArg['slot-id']);
    assert(port && port['binding-id'], 'broken binding located for the fix');
    const fixResp = await api(page, 'PUT',
                              '/api/entities/binding/' + port['binding-id'],
                              'value=' + encodeURIComponent('8080'));
    assert(!fixResp.status || fixResp.status === 200,
           'fix write accepted: ' + JSON.stringify(fixResp).slice(0, 120));

    const cleared = await waitFor(async () => {
      const body = await typeErrorsPartial();
      return !body.includes('#' + TEST_NAME);
    }, 10000);
    assert(cleared, 'fixed fn no longer listed in /partials/type-errors');

    console.log('PASS');
    process.exitCode = 0;
  } catch (e) {
    console.error('FAIL:', e.message);
    process.exitCode = 1;
  } finally {
    try { await deleteFnByName(page, TEST_NAME); } catch (_) { /* best-effort */ }
    await browser.close();
  }
})();
