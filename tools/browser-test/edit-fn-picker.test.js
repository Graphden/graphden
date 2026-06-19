// Fn-picker popover e2e — the shared component opened by add-MI-
// parent, free-arg-bind fn-ref path, secrets ns-picker stub, etc.
//
// Coverage:
//   • Open the picker via `window.openFnPicker` with no expected
//     type. Verify the popover mounts with search input + a list
//     of candidate fns (graphData.fns filtered by has-name).
//   • Type a filter query → only matching rows visible.
//   • Click a row → onPick callback fires with the picked fn entity.
//   • Escape dismisses the popover.
//
// Run from this directory:  node edit-fn-picker.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-fn-picker — popover / search filter / row click / dismiss');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#identity');
    await page.waitForTimeout(2500);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForFunction(
      () => typeof openFnPicker === 'function'
            && typeof graphData !== 'undefined'
            && graphData?.fns?.length > 50,
      {timeout: 15000});

    // ===================================================================
    // Phase A: open the picker with onPick stub. Anchor doesn't matter
    // for popover positioning — use the body.
    // ===================================================================
    await page.evaluate(() => {
      window.__fnPicked = null;
      window.openFnPicker({
        anchorEl: document.body,
        onPick: (fn) => { window.__fnPicked = {id: fn.id, name: fn.name}; },
      });
    });
    await page.waitForSelector('.fn-picker-popover', {timeout: 5000});
    const initial = await page.evaluate(() => {
      const p = document.querySelector('.fn-picker-popover');
      const rows = p?.querySelectorAll('.fn-picker-list > *');
      const search = p?.querySelector('.fn-picker-search');
      return {
        visible: !!p,
        hasSearch: !!search,
        searchPlaceholder: search?.placeholder,
        rowCount: rows?.length || 0,
      };
    });
    assert(initial.visible, 'fn-picker-popover renders');
    assert(initial.hasSearch,
           'search input present: '
           + JSON.stringify(initial.searchPlaceholder));
    assert(initial.rowCount > 10,
           'picker lists many candidate fns: ' + initial.rowCount);

    // ===================================================================
    // Phase B: type filter "identity" → only matching rows visible.
    // ===================================================================
    await page.fill('.fn-picker-search', 'identity');
    await page.waitForTimeout(400);
    const filtered = await page.evaluate(() => {
      const p = document.querySelector('.fn-picker-popover');
      const allRows = Array.from(p?.querySelectorAll('.fn-picker-list > *') || []);
      const visible = allRows.filter((row) => {
        const style = window.getComputedStyle(row);
        return style.display !== 'none' && style.visibility !== 'hidden';
      });
      return {
        visibleCount: visible.length,
        anyText: visible.map((r) => r.textContent?.trim()).filter(Boolean).slice(0, 5),
      };
    });
    assert(filtered.visibleCount > 0,
           '≥ 1 row matches "identity": ' + filtered.visibleCount);
    assert(filtered.visibleCount < initial.rowCount,
           'filter narrows the list (was ' + initial.rowCount
           + ', now ' + filtered.visibleCount + ')');
    assert(filtered.anyText.every((t) => /identity/i.test(t)),
           'every visible row mentions "identity": '
           + JSON.stringify(filtered.anyText));

    // ===================================================================
    // Phase C: click a matching row → onPick fires with that fn.
    // ===================================================================
    await page.evaluate(() => {
      const p = document.querySelector('.fn-picker-popover');
      const row = Array.from(p?.querySelectorAll('.fn-picker-list > *') || [])
        .find((r) => {
          const style = window.getComputedStyle(r);
          return style.display !== 'none' && /identity/i.test(r.textContent || '');
        });
      row?.click();
    });
    // The picker closes on pick → popover gone + onPick stub captured the fn.
    await page.waitForFunction(
      () => !document.querySelector('.fn-picker-popover')
            && window.__fnPicked,
      {timeout: 5000});
    const picked = await page.evaluate(() => window.__fnPicked);
    assert(picked && /identity/i.test(picked.name || ''),
           'onPick fired with an :identity-named fn: '
           + JSON.stringify(picked));

    // ===================================================================
    // Phase D: re-open + Escape dismisses without picking.
    // ===================================================================
    await page.evaluate(() => {
      window.__fnPicked = null;
      window.openFnPicker({
        anchorEl: document.body,
        onPick: (fn) => { window.__fnPicked = {id: fn.id, name: fn.name}; },
      });
    });
    await page.waitForSelector('.fn-picker-popover', {timeout: 5000});
    await page.keyboard.press('Escape');
    await page.waitForFunction(
      () => !document.querySelector('.fn-picker-popover'),
      {timeout: 3000});
    const afterEsc = await page.evaluate(() => ({
      popoverGone: !document.querySelector('.fn-picker-popover'),
      pickedAfterEsc: window.__fnPicked,
    }));
    assert(afterEsc.popoverGone,
           'Escape removes the popover');
    assert(!afterEsc.pickedAfterEsc,
           'Escape does NOT fire onPick: '
           + JSON.stringify(afterEsc.pickedAfterEsc));

    console.log('✓ fn-picker verified — open / filter / pick / dismiss');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
