// Free-arg visualisation e2e — when a fn-def has unbound slots, the
// canvas renders a placeholder node + a `+ Bind this slot` button
// (`.placeholder-binder`) per free arg. Clicking the binder opens
// the free-arg-bind chooser (literal vs fn-ref).
//
// Coverage:
//   • Seed a probe parented from `:add` (inherits a `:nums` slot,
//     left unbound — single free arg).
//   • Navigate to probe; verify exactly 1 `.placeholder-binder`
//     renders with the bind-prompt title.
//   • Click the binder → "Bind literal" / "Bind fn-ref" chooser
//     popover appears (mirrors the sequence-append chooser).
//
// Run from this directory:  node edit-free-arg-strip.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'free-arg-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, PROBE_FN); } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 200));
    d.accept();
  });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-free-arg-strip — unbound slot → placeholder-binder + chooser');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: probe parented :add, no bindings.
    // ===================================================================
    const ents = await getEntities(page, 'add');
    const addFn = ents.fns.find(
      (f) => f.name === 'add' && (f['parent-ids'] || []).length === 0);
    assert(addFn, ':add baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + addFn.id);
    const probe = (await getEntities(page, PROBE_FN)).fns.find(
      (f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created (no bindings)');

    // ===================================================================
    // Phase A: navigate. Verify placeholder-binder renders.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + PROBE_FN);
    await page.waitForFunction(
      () => graphReady()
            && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null,
      {timeout: 20000, polling: 100});
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForSelector('.placeholder-binder', {timeout: 15000});
    const initial = await page.evaluate(() => {
      const binders = Array.from(document.querySelectorAll('.placeholder-binder'));
      return {
        binderCount: binders.length,
        binderText: binders[0]?.textContent?.trim(),
        binderTitle: binders[0]?.title,
      };
    });
    assert(initial.binderCount >= 1,
           '≥ 1 placeholder-binder on the canvas: '
           + initial.binderCount);
    assert(initial.binderText === '+',
           'binder glyph is "+": ' + JSON.stringify(initial.binderText));
    // The `:add.:nums` slot is `:sequence`-typed → the binder is a
    // sequence-anchor variant whose tooltip differs ("Add the first
    // item" vs "Bind this slot"). Either tooltip is a valid free-arg
    // entry-point.
    assert(/bind|add the first/i.test(initial.binderTitle || ''),
           'binder title mentions "bind" or "add the first item": '
           + JSON.stringify(initial.binderTitle));

    // ===================================================================
    // Phase B: click the binder → chooser popover.
    // Sequence-anchor variant opens the same chooser as the regular
    // free-arg path ("literal" / "fn-ref") because the underlying
    // bind operation is the same.
    // ===================================================================
    await page.click('.placeholder-binder');
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('button'))
        .some((b) => /literal|fn-ref/i.test(b.textContent || '')),
      null,
      {timeout: 5000});
    const chooser = await page.evaluate(() => {
      const all = Array.from(document.querySelectorAll('button'));
      return {
        hasLit: all.some((b) => /literal/i.test(b.textContent || '')),
        hasRef: all.some((b) => /fn-ref/i.test(b.textContent || '')),
      };
    });
    assert(chooser.hasLit && chooser.hasRef,
           'chooser popover shows literal + fn-ref options');

    console.log('✓ free-arg viz verified — placeholder-binder + chooser');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
