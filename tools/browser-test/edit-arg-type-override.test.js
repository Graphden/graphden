// Arg type-override e2e — click a type chip on an arg-overlay → type-
// edit popover → pick narrower type → save → binding row carries
// `:type-override-fn-id` pointing at the chosen type-row.
//
// Coverage:
//   • Seed an `:identity`-parented probe (`:value` slot is `:any`-
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
const {assert, newContext, api, getEntities, deleteFnByName, waitFor} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'arg-type-override-probe' + RUN_ID;


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
  console.log('edit-arg-type-override — type chip → select → save → :type-override-fn-id');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: probe parented :identity (:value slot is :any-typed).
    // ===================================================================
    const ents = await getEntities(page); // full-dump: two unrelated baselines (identity + int, no shared closure, probe not yet created)
    const identity = ents.fns.find((f) => f.name === 'identity');
    const intFn = ents.fns.find(
      (f) => f.name === 'int' && (f['parent-ids'] || []).length === 0
             && !f['impl-hash']);
    assert(identity && intFn,
           ':identity + :int baselines resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + identity.id);
    const probe = (await getEntities(page, PROBE_FN)).fns.find(
      (f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created');

    // ===================================================================
    // Phase A: navigate. Verify type chip reads "any".
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
    await page.waitForSelector('.arg-type-chip', {timeout: 15000});

    const initialChip = await page.evaluate(() => {
      const chip = document.querySelector('.arg-type-chip');
      return {chipText: chip?.textContent?.trim()};
    });
    assert(/any/i.test(initialChip.chipText || ''),
           'type chip reads "any": '
           + JSON.stringify(initialChip.chipText));

    // ===================================================================
    // Phase B: click chip → type-edit popover with <select>.
    // ===================================================================
    await page.evaluate(() => {
      document.querySelector('.arg-type-chip')?.click();
    });
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
    // Phase B2 (regression): the picker inside the graph-refresh window.
    // ===================================================================
    // `initGraph()` rebuilds `lookups` from `?scope=index` — fns and namespaces,
    // and NO slots. The slot rows arrive afterwards, per view, via
    // `ensureSubtreeFor()`. Open the type popover inside that window — the chip on
    // screen is still the previous render's — and the slot is unknown, which the
    // picker used to read as "nothing is compatible": it dropped its "loading…"
    // placeholder, offered the current type alone, and nothing ever re-ran it when
    // the subtree landed. A type picker that cannot change the type, with no error
    // to explain it.
    //
    // The suite hit this window for real whenever the host was busy — 5 failures in
    // 8 concurrent runs — and it had been "fixed" twice by raising the timeout it
    // was waiting on, for options that were never coming.
    //
    // Hold the subtree fetch back so the window is wide and this is a proof rather
    // than a race, then open the picker inside it. It must still fill.
    await page.route('**/api/graph/entities?scope=subtree*', async (route) => {
      await new Promise((r) => setTimeout(r, 3000));
      await route.continue();
    });
    await page.evaluate(() => {
      document.querySelector('.arg-value-edit-popover')?.remove();
      initGraph();                      // deliberately NOT awaited
    });
    // Wait for the window to actually OPEN — `lookups` rebuilt from the index
    // payload, so the slot map is empty — otherwise the click lands before the
    // rebuild, the old slots are still there, and the test proves nothing. (It
    // passed against the un-fixed build until this wait was added.)
    await page.waitForFunction(() => (lookups?.slotMap?.size ?? 1) === 0,
                               null, {timeout: 20000, polling: 20});
    // Poll instead of a blind chip click + wait: the chip can be
    // momentarily absent during the in-flight re-render this phase
    // deliberately provokes. Click only while no popover is up.
    assert(await waitFor(() => page.evaluate(() => {
      if (document.querySelector(
        '.arg-value-edit-popover select.arg-value-edit-input')) return true;
      if (!document.querySelector('.arg-value-edit-popover')) {
        document.querySelector('.arg-type-chip')?.click();
      }
      return false;
    }), 30000), 'type picker opened mid-refresh');
    await page.waitForFunction(
      () => {
        const sel = document.querySelector(
          '.arg-value-edit-popover select.arg-value-edit-input');
        return Array.from(sel?.options || []).some((o) => o.value === 'int');
      },
      null,
      {timeout: 20000});
    console.log('  ✓ picker still fills when opened during a graph refresh');
    await page.unroute('**/api/graph/entities?scope=subtree*');

    // Re-open on a settled graph for Phase C.
    await page.evaluate(() => document.querySelector('.arg-value-edit-popover')?.remove());
    await page.waitForFunction(() => (lookups?.slotMap?.size ?? 0) > 0,
                               null, {timeout: 20000});
    // Same click-poll as above — the settled graph can still re-render
    // overlays under the cursor while the chip is being clicked.
    assert(await waitFor(() => page.evaluate(() => {
      if (document.querySelector(
        '.arg-value-edit-popover select.arg-value-edit-input')) return true;
      if (!document.querySelector('.arg-value-edit-popover')) {
        document.querySelector('.arg-type-chip')?.click();
      }
      return false;
    }), 30000), 'type picker opened on the settled graph');
    await page.waitForFunction(
      () => {
        const sel = document.querySelector(
          '.arg-value-edit-popover select.arg-value-edit-input');
        return Array.from(sel?.options || []).some((o) => o.value === 'int');
      },
      null,
      {timeout: 20000});

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

    console.log('✓ arg type-override verified — chip → select → save → storage');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
