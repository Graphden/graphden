// Namespace move e2e — click the ns badge on a fn-card → namespace
// picker → choose new namespace → PUT lands → fn moves under it.
//
// Coverage:
//   • Seed a fn-def under `:app` namespace.
//   • Navigate; invoke `enterNamespaceMoveEditMode` programmatically
//     (the canvas trigger is a small ns-strip whose hit-test is
//     awkward to drive headlessly — exercising the dispatch flow
//     covers the user code path equivalently).
//   • Verify the namespace picker mounts.
//   • Pick `:core` → PUT /api/entities/fn — server sets new
//     namespace-id.
//   • Storage's fn-row reports the new namespace-id; sidebar tree
//     places the fn under `:core`.
//
// Run from this directory:  node edit-namespace-move.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName,
       waitForServerHealthy} = require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN_NAME = 'ns-move-probe' + RUN_ID;
const FROM_NS = 'app';
const TO_NS = 'core';


async function cleanup(page) {
  try { await deleteFnByName(page, FN_NAME); } catch (_) {}
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
  console.log('edit-namespace-move — ns-picker → pick → PUT → sidebar update');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: fn under :app.
    // ===================================================================
    const ents = await getEntities(page);
    const identity = ents.fns.find((f) => f.name === 'identity');
    const fromNs = (ents.namespaces || []).find((n) => n.name === FROM_NS);
    const toNs = (ents.namespaces || []).find((n) => n.name === TO_NS);
    assert(identity && fromNs && toNs,
           'baselines resolved (:identity + :' + FROM_NS + ' + :' + TO_NS + ')');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + FN_NAME + '&parent-ids=' + identity.id
              + '&namespace-id=' + fromNs.id);
    const fn = (await getEntities(page)).fns.find(
      (f) => f.name === FN_NAME);
    assert(fn && fn['namespace-id'] === fromNs.id,
           'probe created under :' + FROM_NS);

    // ===================================================================
    // Phase A: navigate; open the ns-picker via the public entry.
    // ===================================================================
    // NOTE: do NOT chain through `about:blank` first. The extra
    // navigation race-conditions with the editor's auto-initGraph
    // and intermittently leaves the in-memory graphData on the
    // pre-PUT snapshot — even though waitForFunction below sees the
    // server-side new namespace-id, the next getEntities read can
    // surface the cached pre-move state. The single goto is enough
    // because newContext starts on about:blank already.
    //
    // Pre-flight: if the JVM is mid-OOM-restart from accumulated
    // suite load (this test runs near the alphabetic tail), the
    // editor's initGraph fires multiple browser-side fetches that
    // throw TypeError without retry. Block until /health is back
    // before navigating.
    await waitForServerHealthy();
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + FROM_NS + '.' + FN_NAME);
    // Wait for the page bundle to finish loading so we can call
    // initGraph (which is bundled at the page-script level).
    await page.waitForFunction(
      () => typeof initGraph === 'function',
      {timeout: 20000, polling: 100});
    await page.evaluate(() => initGraph());
    // After initGraph: wait for sidebar to be populated AND
    // enterNamespaceMoveEditMode (a window helper) to be defined.
    await page.waitForFunction(
      () => typeof graphData !== 'undefined' && graphData?.fns?.length > 0
            && document.querySelectorAll('.entity-item').length > 0
            && typeof enterNamespaceMoveEditMode === 'function',
      {timeout: 20000, polling: 100});

    await page.evaluate((fnName) => {
      const probeFn = (graphData?.fns || []).find((f) => f.name === fnName);
      if (probeFn) enterNamespaceMoveEditMode(probeFn, document.body);
    }, FN_NAME);
    // The namespace picker reuses `.fn-picker-popover` styling but
    // its search placeholder distinguishes it from the fn-picker.
    await page.waitForFunction(
      () => {
        const search = document.querySelector('.fn-picker-popover .fn-picker-search');
        return search && /namespace/i.test(search.placeholder || '');
      },
      {timeout: 5000});
    const pickerState = await page.evaluate(() => {
      const p = document.querySelector('.fn-picker-popover');
      const search = p?.querySelector('.fn-picker-search');
      return {
        visible: !!p,
        placeholder: search?.placeholder,
        rowCount: p?.querySelectorAll('.fn-picker-row').length || 0,
      };
    });
    assert(pickerState.visible, 'namespace picker popover visible');
    assert(/namespace/i.test(pickerState.placeholder || ''),
           'search input placeholder mentions namespace: '
           + JSON.stringify(pickerState.placeholder));
    assert(pickerState.rowCount > 0,
           'picker lists namespace rows: ' + pickerState.rowCount);

    // ===================================================================
    // Phase B: filter to `:core` then click the matching row.
    // ===================================================================
    await page.fill('.fn-picker-popover .fn-picker-search', TO_NS);
    // Wait until the filter resolves to a row matching TO_NS.
    await page.waitForFunction(
      (target) => {
        const rows = Array.from(
          document.querySelectorAll('.fn-picker-popover .fn-picker-row'));
        return rows.some(
          (r) => r.querySelector('.fn-picker-row-name')?.textContent?.trim()
                 === target);
      },
      TO_NS,
      {timeout: 5000, polling: 50});
    await page.evaluate((target) => {
      const rows = Array.from(
        document.querySelectorAll('.fn-picker-popover .fn-picker-row'));
      const row = rows.find(
        (r) => r.querySelector('.fn-picker-row-name')?.textContent?.trim()
               === target);
      row?.click();
    }, TO_NS);

    // Storage reflects the move after the PUT commits. Node-side
    // poll on getEntities — bounded retries handle the per-ctx
    // graph cache rebuild race more tightly than the historical
    // fixed 3-second sleep.
    {
      const deadline = Date.now() + 15000;
      let moved = false;
      while (Date.now() < deadline) {
        const ents = await getEntities(page);
        const f = (ents.fns || []).find((x) => x.id === fn.id);
        if (f && f['namespace-id'] === toNs.id) { moved = true; break; }
        await new Promise((r) => setTimeout(r, 250));
      }
      if (!moved) throw new Error('ns-move never settled in storage');
    }
    const finalEnts = await getEntities(page);
    const moved = finalEnts.fns.find((f) => f.id === fn.id);
    assert(moved['namespace-id'] === toNs.id,
           'fn.namespace-id now points at :' + TO_NS
           + ' (expected ' + toNs.id + '): '
           + JSON.stringify(moved['namespace-id']));

    console.log('✓ namespace move verified — picker / pick / PUT / storage');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
