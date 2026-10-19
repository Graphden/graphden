// Arg type-override e2e — click a type chip on an arg-overlay → type-
// edit popover → pick narrower type → save → binding row carries
// `:type-override-fn-id` pointing at the chosen type-row.
//
// Coverage:
//   • Seed an `:const`-parented probe (`:value` slot is `:any`-
//     typed — broad enough to allow narrowing without binding-value
//     conflicts).
//   • Navigate; verify the arg-overlay's type chip currently reads
//     "any".
//   • Click the chip → arg-value-edit-popover (aria-label "Change
//     arg type") with a <select> pre-filled with "any" + async-
//     populated compatible types.
//   • Pick `:int` from the dropdown and save → binding row gains
//     `:type-override-fn-id` pointing at the `:int` type-row.
//
// Run from this directory:  node edit-arg-type-override.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'arg-type-override-probe' + RUN_ID;


const TYPE_CHIP = '.edge-label-overlay[data-arg-name="value"] .arg-type-chip';

async function freshTypeChip(page) {
  // initGraph awaits the subtree; the layout can replace the old disabled
  // chip afterwards. Presence alone is not an actionable current contract.
  await page.waitForFunction(selector => {
    const chip = document.querySelector(selector);
    return graphTypesReady() && !graph.animating && chip
      && chip.getAttribute('aria-disabled') !== 'true'
      && chip.getAttribute('tabindex') === '0';
  }, TYPE_CHIP, {timeout: 20000});
  return page.locator(TYPE_CHIP).first();
}


async function cleanup(page) {
  try { await deleteFnByName(page, PROBE_FN); } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  let releaseSubtree;
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 200));
    d.accept();
  });
  console.log('edit-arg-type-override — type chip → select → save → :type-override-fn-id');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: probe parented :const (:value slot is :any-typed).
    // ===================================================================
    const ents = await getEntities(page); // full-dump: two unrelated baselines (const + int, no shared closure, probe not yet created)
    const constFn = ents.fns.find((f) => f.name === 'const');
    const intFn = ents.fns.find(
      (f) => f.name === 'int' && (f['parent-ids'] || []).length === 0
             && !f['impl-hash']);
    assert(constFn && intFn,
           ':const + :int baselines resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + constFn.id);
    const probe = (await getEntities(page, PROBE_FN)).fns.find(
      (f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created');

    // ===================================================================
    // Phase A: navigate. Verify type chip reads "any".
    // ===================================================================
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')+'/#' + PROBE_FN);
    await page.waitForFunction(
      () => graphReady()
            && !!document.querySelector('button.more-actions-trigger')
            && !graph.animating,
      null,
      {timeout: 20000, polling: 100});
    await page.evaluate(() => initGraph && initGraph());
    await freshTypeChip(page);

    const initialChip = await page.evaluate(() => {
      const chip = document.querySelector('.arg-type-chip');
      return {chipText: chip?.querySelector(':scope > .gd-canvas-body-host')?.textContent?.trim()};
    });
    assert(/any/i.test(initialChip.chipText || ''),
           'type chip reads "any": '
           + JSON.stringify(initialChip.chipText));

    // ===================================================================
    // Phase B: click chip → type-edit popover with <select>.
    // ===================================================================
    await (await freshTypeChip(page)).click();
    await page.waitForSelector(
      '.arg-value-edit-popover select.arg-value-edit-input',
      {timeout: 5000});
    const popoverState = await page.evaluate(() => {
      const p = document.querySelector('.arg-value-edit-popover');
      const sel = p?.querySelector('select');
      return {
        ariaLabel: p?.getAttribute('aria-label'),
        currentValue: sel?.value,
      };
    });
    assert(/type/i.test(popoverState.ariaLabel || ''),
           'aria-label contains "type": '
           + JSON.stringify(popoverState.ariaLabel));
    assert(popoverState.currentValue === 'any',
           '<select> pre-fills with "any": '
           + JSON.stringify(popoverState.currentValue));

  // The compatible-option list arrives from ONE server call
  // (GET /partials/compatible-type-options — the per-name
  // /api/types/compatible fan-out is gone); options stream into the
  // select when the response lands.
    await page.waitForFunction(
      () => {
        const sel = document.querySelector(
          '.arg-value-edit-popover select.arg-value-edit-input');
        const opts = Array.from(sel?.options || []);
        return opts.some((o) => o.value === 'int');
      },
      null,
      {timeout: 30000});

    // ===================================================================
    // Phase B2: stale chips stay unavailable until the fresh subtree lands.
    // ===================================================================
    const heldSubtree = new Promise(resolve => { releaseSubtree = resolve; });
    await page.route('**/api/graph/entities?scope=subtree*', async route => {
      await heldSubtree;
      await route.continue();
    }, {times: 1});
    const requested = page.waitForRequest(request => {
      const url = new URL(request.url());
      return url.pathname === '/api/graph/entities' && url.searchParams.get('scope') === 'subtree';
    });
    await page.evaluate(() => {
      closeInlineEdit();
      void initGraph();
    });
    await requested;
    await page.waitForFunction(() => !graphTypesReady() && lookups?.slotMap?.size === 0,
                               null, {timeout: 20000});
    const staleChip = page.locator(TYPE_CHIP).first();
    assert(await staleChip.getAttribute('aria-disabled') === 'true', 'the stale type action is disabled during refresh');
    assert(await staleChip.getAttribute('tabindex') === '-1', 'the stale type action leaves keyboard tab order');
    await staleChip.dispatchEvent('click');
    assert(await page.locator('.arg-value-edit-popover').count() === 0,
      'the stale chip cannot open a type picker while its subtree is held');
    releaseSubtree(); releaseSubtree = null;

    const freshChip = await freshTypeChip(page);
    await freshChip.focus();
    await freshChip.press('Enter');
    await page.waitForFunction(() => {
      const select = document.querySelector('.arg-value-edit-popover select.arg-value-edit-input');
      return Array.from(select?.options || []).some(option => option.value === 'int');
    }, null, {timeout: 20000});
    console.log('  ✓ fresh type picker opens by keyboard and fills after the held refresh');

    // Reopen through the same lifecycle on a coherent graph for the save.
    await page.evaluate(() => closeInlineEdit());
    await (await freshTypeChip(page)).click();
    await page.waitForFunction(() => {
      const select = document.querySelector('.arg-value-edit-popover select.arg-value-edit-input');
      return Array.from(select?.options || []).some(option => option.value === 'int');
    }, null, {timeout: 20000});

    // ===================================================================
    // Phase C: pick "int" → save → binding gets :type-override-fn-id.
    // ===================================================================
    await page.evaluate(() => {
      const sel = document.querySelector(
        '.arg-value-edit-popover select.arg-value-edit-input');
      sel.value = 'int';
      sel.dispatchEvent(new Event('change', {bubbles: true}));
    });
    await page.evaluate(() => {
      const p = document.querySelector('.arg-value-edit-popover');
      const btn = Array.from(p?.querySelectorAll('.arg-value-edit-btn') || [])
        .find((b) => !b.classList.contains('arg-value-edit-btn-secondary')
                  && !b.classList.contains('arg-value-edit-btn-danger'));
      btn?.click();
    });
    // initGraph fires after save → wait for the popover to dismiss
    // AND for storage to reflect the new :type-override-fn-id.
    await page.waitForFunction(
      () => !document.querySelector('.arg-value-edit-popover'),
      null,
      {timeout: 10000});
    {
      const deadline = Date.now() + 15000;
      let landed = false;
      while (Date.now() < deadline) {
        const ents = await getEntities(page, probe.id);
        const b = (ents.bindings || []).find((x) => x['fn-id'] === probe.id);
        if (b && b['type-override-fn-id']) { landed = true; break; }
        await new Promise((r) => setTimeout(r, 200));
      }
      if (!landed) throw new Error(':type-override-fn-id never landed in storage');
    }

    // ===================================================================
    // Phase D: storage — probe's binding row has :type-override-fn-id
    // pointing at the :int type-row.
    // ===================================================================
    const finalEnts = await getEntities(page, probe.id);
    const probeBindings = (finalEnts.bindings || [])
      .filter((b) => b['fn-id'] === probe.id);
    assert(probeBindings.length === 1,
           'probe has exactly 1 binding row: ' + probeBindings.length);
    const binding = probeBindings[0];
    assert(binding['type-override-fn-id'] === intFn.id,
           'binding :type-override-fn-id points at :int: '
           + JSON.stringify(binding['type-override-fn-id'])
           + ' (expected ' + intFn.id + ')');

    assert(errors.length === 0, 'type-override editing has no browser exceptions');
    console.log('✓ arg type-override verified — chip → select → save → storage');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    releaseSubtree?.();
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
