// Free-arg bind via fn-ref e2e — complements edit-free-arg-strip
// (which exercised the literal path through the sequence-anchor
// branch) with the regular non-sequence "Bind fn-ref" flow.
//
// Coverage:
//   • Seed a probe parented from `:identity` (`:value :any` slot —
//     non-sequence, eligible for either literal or fn-ref bind).
//   • Click the placeholder-binder → chooser popover with "Bind
//     literal" / "Bind fn-ref" buttons.
//   • Click "Bind fn-ref" → fn-picker opens.
//   • Pick `:current-time-ms` → fn-picker closes + binding written
//     with `:ref-fn-id` pointing at the chosen fn.
//   • Storage's binding row has the ref + no value.
//
// Run from this directory:  node edit-free-arg-fn-ref.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName, waitFor} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'free-arg-fnref-probe' + RUN_ID;


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
  console.log('edit-free-arg-fn-ref — binder → "Bind fn-ref" → picker → ref written');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: probe parented :identity (:value slot is :any, non-seq).
    // ===================================================================
    // full-dump: reads two unrelated baseline fns (:identity +
    // :current-time-ms); neither is in the other's subtree closure.
    const ents = await getEntities(page);
    const identity = ents.fns.find((f) => f.name === 'identity');
    const ctmFn = ents.fns.find((f) => f.name === 'current-time-ms');
    assert(identity && ctmFn,
           ':identity + :current-time-ms baselines resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + identity.id);
    const probe = (await getEntities(page, PROBE_FN)).fns.find(
      (f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created (no bindings)');

    // ===================================================================
    // Phase A: navigate, verify placeholder-binder.
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
    const binderTitle = await page.evaluate(() =>
      document.querySelector('.placeholder-binder')?.title);
    assert(/bind/i.test(binderTitle || ''),
           'binder title mentions "bind" (non-sequence anchor): '
           + JSON.stringify(binderTitle));

    // ===================================================================
    // Phase B: click → chooser opens with "Bind fn-ref" button.
    // ===================================================================
    await page.click('.placeholder-binder');
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('button'))
        .some((b) => /Bind fn-ref/.test(b.textContent || '')),
      null,
      {timeout: 5000});

    // ===================================================================
    // Phase C: click "Bind fn-ref" → fn-picker opens.
    // ===================================================================
    await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll('button'))
        .find((b) => /Bind fn-ref/.test(b.textContent || ''));
      btn?.click();
    });
    await page.waitForSelector('.fn-picker-popover', {timeout: 5000});
    // The type-compatible candidate set now loads from the server
    // (/api/types/candidates) async — wait for it to populate before
    // probing the row count.
    await page.waitForFunction(
      () => (document.querySelector('.fn-picker-popover')
        ?.querySelectorAll('.fn-picker-list > *').length || 0) > 5,
      null,
      {timeout: 15000, polling: 50});
    const pickerState = await page.evaluate(() => {
      const p = document.querySelector('.fn-picker-popover');
      return {
        visible: !!p,
        hasSearch: !!p?.querySelector('.fn-picker-search'),
        rowCount: p?.querySelectorAll('.fn-picker-list > *').length || 0,
      };
    });
    assert(pickerState.visible, 'fn-picker opens');
    assert(pickerState.rowCount > 5,
           'picker lists candidate fns: ' + pickerState.rowCount);

    // ===================================================================
    // Phase D: filter to current-time-ms + click → ref written.
    // ===================================================================
    await page.fill('.fn-picker-popover .fn-picker-search', 'current-time-ms');
    // Filter debounces; wait until exactly the matching row is the
    // sole visible candidate instead of guessing 400 ms.
    await page.waitForFunction(() => {
      const rows = Array.from(document.querySelectorAll(
        '.fn-picker-popover .fn-picker-row'));
      const visible = rows.filter(r => {
        const st = window.getComputedStyle(r);
        return st.display !== 'none' && st.visibility !== 'hidden';
      });
      return visible.length >= 1
             && visible.every(r => /current-time-ms/.test(r.textContent || ''));
    },null,  {timeout: 2000, polling: 50});
    // Programmatic .click() inside page.evaluate doesn't reliably
    // bubble through cytoscape's hover-state machinery; use the real
    // pointer click via Playwright.
    await page.click('.fn-picker-popover .fn-picker-row');
    // Wait for the picker to close — saveArgRef completes its PUT
    // before calling initGraph, so popover-gone implies storage-written.
    await page.waitForFunction(
      () => !document.querySelector('.fn-picker-popover'),
      null,
      {timeout: 10000});

    // ===================================================================
    // Phase E: storage — binding row has :ref-fn-id pointing at CTM
    //          and no :value.
    // ===================================================================
    // saveArgRef → writeBindingFields → POST /api/entities/binding
    // + initGraph re-fetch. Poll for the binding instead of a fixed
    // 800 ms wait — under e2e suite load the chain regularly takes
    // longer than 1 s, which produced the "probe has 1 binding row: 0"
    // flake.
    let probeBindings;
    const bound = await waitFor(async () => {
      const finalEnts = await getEntities(page, probe.id);
      probeBindings = (finalEnts.bindings || [])
        .filter((b) => b['fn-id'] === probe.id);
      return probeBindings.length === 1;
    }, 5000);
    assert(bound,
           'probe has 1 binding row: '
           + (probeBindings ? probeBindings.length : 'undefined'));
    const binding = probeBindings[0];
    assert(binding['ref-fn-id'] === ctmFn.id,
           'binding :ref-fn-id points at :current-time-ms: '
           + JSON.stringify(binding['ref-fn-id'])
           + ' (expected ' + ctmFn.id + ')');
    assert(binding.value == null,
           'binding :value is null (it\'s a fn-ref, not a literal): '
           + JSON.stringify(binding.value));

    console.log('✓ free-arg fn-ref bind verified — chooser / picker / pick / storage');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
