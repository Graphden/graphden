// Type-errors editor surfaces (Error Tolerance, Phase 3 → lenses):
//
// 1. A type-breaking binding write (tolerated + recorded since Phase 2)
//    makes the fn's row carry `:type-error-count` — the ⚠ lens's fact.
// 2. The editor shows it: the fn-card root row carries the ⚠ badge, the
//    Explorer's ⚠ type errors chip counts it, the fn's row carries ⚠1 and
//    the ⚠ lens keeps it.
// 3. Fixing the binding clears the diagnostic — chip, marker and badge go.
//
// Run from this directory:  node edit-type-errors-lens.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, nodeApi,
       deleteFnByName, waitFor} = require('./edit-test-helpers');

const TEST_NAME = 'test-type-errors-lens';

// The per-fn count the lens reads, off the light row the tree scopes
// project (`:type-error-count`, omitted when zero).
async function typeErrorCount() {
  const r = await nodeApi('GET', '/api/graph/entities?scope=search&q=' + TEST_NAME);
  const j = await r.json();
  const f = (j.fns || []).find(x => x.name === TEST_NAME);
  return f ? (f['type-error-count'] || 0) : 0;
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

    // === The row carries the count ===
    const listed = await waitFor(async () => (await typeErrorCount()) >= 1, 10000);
    assert(listed, 'the fn row carries :type-error-count after the tolerated write');

    // === Editor surfaces ===
    // Reload: the context's page booted before the diagnostic existed, and a
    // hash-only goto keeps that document (and its tree counts).
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/#' + TEST_NAME);
    await page.reload();
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

    // The ⚠ lens: chip count, row marker, focus.
    const chipCount = () => page.evaluate(() => {
      const c = document.querySelector('#kind-filters .kind-toggle[data-kind="type-errors"] .kind-count');
      return c ? parseInt(c.textContent || '0', 10) : NaN;
    });
    const chipSeen = await waitFor(async () => (await chipCount()) >= 1, 15000);
    assert(chipSeen, '⚠ type errors chip counts the diagnostic: ' + await chipCount());
    await page.evaluate(() => {
      const label = [...document.querySelectorAll('#entity-list .ns-label')].find(el => el.textContent === '(primitives)');
      const header = label?.parentElement;
      if (header && header.getAttribute('aria-expanded') !== 'true') header.click();
    });
    const rowMarker = () => page.evaluate((n) => {
      const row = [...document.querySelectorAll('#entity-list .entity-item[data-fn-id]')]
        .find(el => el.querySelector('.name')?.textContent === n);
      return row?.querySelector('.kind-marker-type-error')?.textContent || null;
    }, TEST_NAME);
    const markerSeen = await waitFor(async () => (await rowMarker()) === '⚠1', 20000);
    assert(markerSeen, 'the fn row carries the ⚠1 marker: ' + await rowMarker());
    await page.evaluate(() => toggleKind('type-errors'));
    const focused = await waitFor(() => page.evaluate((n) =>
      [...document.querySelectorAll('#entity-list .entity-item[data-fn-id]')]
        .some(el => el.querySelector('.name')?.textContent === n && !el.hidden), TEST_NAME), 10000);
    assert(focused, 'the ⚠ lens keeps the mistyped fn');
    await page.evaluate(() => toggleKind('all'));

    // === Fix → the diagnostic clears, the panel empties ===
    const port = synthArgs(await getEntities(page, fn.id))
                   .find(a => a['fn-id'] === fn.id && a['slot-id'] === portArg['slot-id']);
    assert(port && port['binding-id'], 'broken binding located for the fix');
    const fixResp = await api(page, 'PUT',
                              '/api/entities/binding/' + port['binding-id'],
                              'value=' + encodeURIComponent('8080'));
    assert(!fixResp.status || fixResp.status === 200,
           'fix write accepted: ' + JSON.stringify(fixResp).slice(0, 120));

    const cleared = await waitFor(async () => (await typeErrorCount()) === 0, 10000);
    assert(cleared, 'fixed fn no longer carries a type-error count');

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
