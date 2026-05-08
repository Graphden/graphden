// Type-system arg-value validation — both layers:
//
// 1. Backend save-time guard: PUT /api/entities/arg/:id rejects with
//    400 when the new literal violates the slot's expected type.
// 2. Editor live ✓/✗ status: the value-edit popover surfaces the same
//    classification before the user clicks Save.
//
// Run from this directory:  node edit-arg-value-validation.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, synthArgs, deleteFnByName} =
  require('./edit-test-helpers');

const TEST_NAME = 'test-arg-value-validation';

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('arg-value-validation — backend guard + live ✓/✗');
  try {
    await deleteFnByName(page, TEST_NAME);

    // Build a tiny test fn: parent = http-server (which has a
    // :port :int slot — refined to [:and [:>= 1] [:<= 65535]]).
    // Bind :port to 8080 (valid) so we have an arg-id to PUT to.
    const ents = await getEntities(page);
    const httpServer = ents.fns.find(f => f.name === 'http-server');
    const portArg = synthArgs(ents).find(
      a => a['fn-id'] === httpServer.id && a.name === 'port' && !a['source-id']);
    assert(httpServer && portArg, 'http-server.port baseline resolved');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + TEST_NAME + '&parent-ids=' + httpServer.id);
    const fn = (await getEntities(page)).fns.find(f => f.name === TEST_NAME);
    assert(fn, 'test fn created');

    await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + fn.id + '&slot-id=' + portArg['slot-id'] +
              '&value=' + encodeURIComponent('8080'));
    const port = synthArgs(await getEntities(page))
                   .find(a => a['fn-id'] === fn.id && a['slot-id'] === portArg['slot-id']);
    assert(port && port.value === 8080, 'port=8080 seeded');

    // === Backend guard ===

    // 8080 is a valid :port — PUT 22 should pass.
    const okResp = await api(page, 'PUT',
                             '/api/entities/binding/' + port['binding-id'],
                             'value=' + encodeURIComponent('22'));
    assert(!okResp.status || okResp.status === 200,
           'valid port (22) accepted: ' + JSON.stringify(okResp));

    // -1 is OUTSIDE 1..65535 — backend must reject with 400.
    const badResp = await api(page, 'PUT',
                              '/api/entities/binding/' + port['binding-id'],
                              'value=' + encodeURIComponent('-1'));
    assert(badResp.status === 400,
           'out-of-range port (-1) rejected with 400: '
           + JSON.stringify(badResp));
    assert(/Type mismatch|refine|port/.test(badResp.body || ''),
           'rejection body mentions the type / refinement');

    // String where :int expected — backend rejects.
    const wrongType = await api(page, 'PUT',
                                '/api/entities/binding/' + port['binding-id'],
                                'value=' + encodeURIComponent('"not-a-number"'));
    assert(wrongType.status === 400,
           ':text into :port slot rejected with 400');

    // After all the failed PUTs, the value should still be the last
    // VALID write (22), not whatever the failed PUTs tried.
    const afterBad = (await getEntities(page)).bindings.find(b => b.id === port['binding-id']);
    assert(afterBad.value === 22,
           'rejected writes left value at last valid (22)');

    // === Editor live ✓/✗ ===

    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#' + TEST_NAME);
    await page.waitForTimeout(2500);

    // Click the port arg-overlay to open the value-edit popover.
    // Overlay text reads `22int⋮⋮⋮` — value + type chip + drag handle.
    // The first child div carries the value text and the click-to-edit
    // handler (per createArgOverlay).
    const opened = await page.evaluate(() => {
      // Arg-overlay text is "<value><type-chip><drag-handle>", e.g.
      // "22int⋮⋮⋮". The clickable inner div carries just the value
      // and the click-to-edit handler.
      const overlay = Array.from(document.querySelectorAll('.node-overlay'))
        .find(el => {
          const inner = el.querySelector('div');
          return inner && (inner.textContent || '').trim() === '22';
        });
      if (!overlay) return false;
      overlay.querySelector('div').click();
      return true;
    });
    assert(opened, 'port arg-overlay clickable');
    await page.waitForTimeout(250);

    const popoverPresent = await page.evaluate(
      () => !!document.querySelector('.arg-value-edit-popover'));
    assert(popoverPresent, 'value-edit popover opened');

    // Hint line should mention the slot's expected type.
    const hint = await page.evaluate(() => {
      const h = document.querySelector('.arg-value-edit-hint');
      return h ? h.textContent : null;
    });
    assert(hint && /Expected/.test(hint),
           '"Expected: <type>" hint visible: ' + JSON.stringify(hint));

    // Type a valid value → ✓ status.
    await page.evaluate(() => {
      const i = document.querySelector('.arg-value-edit-input');
      i.value = '8080';
      i.dispatchEvent(new Event('input', {bubbles: true}));
    });
    await page.waitForTimeout(150);
    const okStatus = await page.evaluate(() => {
      const s = document.querySelector('.arg-value-edit-status');
      return {text: s && s.textContent, ok: s && s.classList.contains('ok'),
              err: s && s.classList.contains('err')};
    });
    assert(okStatus.ok && !okStatus.err,
           'valid value shows ✓: ' + JSON.stringify(okStatus));

    // Type an invalid value → ✗ status.
    await page.evaluate(() => {
      const i = document.querySelector('.arg-value-edit-input');
      i.value = '"hello"';
      i.dispatchEvent(new Event('input', {bubbles: true}));
    });
    await page.waitForTimeout(150);
    const errStatus = await page.evaluate(() => {
      const s = document.querySelector('.arg-value-edit-status');
      return {text: s && s.textContent, ok: s && s.classList.contains('ok'),
              err: s && s.classList.contains('err')};
    });
    assert(errStatus.err && !errStatus.ok,
           'invalid value shows ✗: ' + JSON.stringify(errStatus));
    assert(/text|int|number/.test(errStatus.text || ''),
           'error message mentions the type mismatch');

    // Close the popover (Escape).
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);
  } finally {
    await deleteFnByName(page, TEST_NAME).catch(() => {});
    await browser.close();
  }
  console.log('arg-value-validation — PASS');
})().catch(e => {
  console.error('arg-value-validation — FAIL:', e.message);
  process.exit(1);
});
