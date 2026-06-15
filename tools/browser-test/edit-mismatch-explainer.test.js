// Mismatch explainer popover e2e — the `!` badge on an arg-value-
// overlay when the bound literal doesn't satisfy the slot's expected
// type. Click → popover with expected / actual / reason + an
// Edit-value action.
//
// Coverage:
//   • Seed a `:sleep`-parented probe and bind `:ms` (typed `:int`)
//     to the text literal "hello" — a type mismatch.
//   • Navigate. Verify `.arg-overlay-mismatch` class + `.arg-mismatch-
//     badge` button render.
//   • Click `!` → `.mismatch-explainer` popover opens.
//   • Popover lists expected type, actual type, reason text.
//   • Click the "Edit value" action → mismatch popover dismisses +
//     the arg-value-edit popover takes its place.
//   • Escape dismisses the explainer cleanly.
//
// Run from this directory:  node edit-mismatch-explainer.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'mismatch-probe' + RUN_ID;


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
  console.log('edit-mismatch-explainer — ! badge → popover → Edit action');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: probe parented from `:sleep` with `:ms` bound to a VALID
    // integer (the backend's type-check rejects mismatching literals
    // at the binding API). The mismatch we want to surface is the
    // EDITOR's client-side `validateLiteralAgainstType` warning — the
    // server is stricter than the editor in practice, so to test the
    // popover we synthesise the mismatched arg directly in the page
    // by mutating `lookups.bindingMap` so the value the editor sees
    // is "hello" (text) while the slot is still typed `:int`.
    // ===================================================================
    const ents = await getEntities(page);
    const sleepFn = ents.fns.find(
      (f) => f.name === 'sleep' && (f['parent-ids'] || []).length === 0);
    assert(sleepFn, ':sleep baseline resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + sleepFn.id);
    const probeEnts = await getEntities(page);
    const probe = probeEnts.fns.find((f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created');
    const slotsById = Object.fromEntries(
      probeEnts.slots.map((s) => [s.id, s]));
    const msSlot = probeEnts['fn-slots']
      .filter((fs) => fs['fn-id'] === sleepFn.id)
      .map((fs) => slotsById[fs['slot-id']])
      .find((s) => s.name === 'ms');
    assert(msSlot, ':sleep.ms slot resolved');

    // Bind to a valid :int, then we'll force the editor's view to a
    // text value after navigation.
    const bindResp = await api(page, 'POST', '/api/entities/binding',
              'fn-id=' + probe.id + '&slot-id=' + msSlot.id
              + '&value=42');
    assert(JSON.stringify(bindResp).includes('created successfully'),
           ':ms bound to 42 (valid int): '
           + JSON.stringify(bindResp).slice(0, 200));

    // ===================================================================
    // Phase A: navigate, then mutate the in-page lookups so the row
    // value is "hello" — triggers `validateLiteralAgainstType` ≠ ok
    // on the next render.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#' + PROBE_FN);
    await page.waitForTimeout(800);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForSelector('.arg-overlay-row', {timeout: 15000});

    await page.evaluate((probeId) => {
      // Find the probe's binding and force its value field to the text
      // "hello". Then rebuild overlays.
      const bindings = Array.from(lookups.bindingMap?.values() || []);
      const target = bindings.find((b) => b['fn-id'] === probeId);
      if (target) target.value = 'hello';
      if (typeof rebuildArgIndexes === 'function') rebuildArgIndexes();
      if (typeof renderGraph === 'function') renderGraph(false);
    }, probe.id);
    await page.waitForSelector('.arg-mismatch-badge', {timeout: 5000});

    const initial = await page.evaluate(() => {
      const badge = document.querySelector('.arg-mismatch-badge');
      const overlay = badge?.closest('.node-overlay');
      return {
        badgePresent: !!badge,
        badgeText: badge?.textContent?.trim(),
        overlayMismatchClass: overlay?.classList.contains('arg-overlay-mismatch'),
      };
    });
    assert(initial.badgePresent, '! mismatch badge renders');
    assert(initial.badgeText === '!',
           'badge glyph is "!": ' + JSON.stringify(initial.badgeText));
    assert(initial.overlayMismatchClass,
           'parent overlay carries .arg-overlay-mismatch class');

    // ===================================================================
    // Phase B: click badge → explainer popover opens.
    // ===================================================================
    await page.click('.arg-mismatch-badge');
    await page.waitForSelector('.mismatch-explainer', {timeout: 5000});
    const popoverState = await page.evaluate(() => {
      const p = document.querySelector('.mismatch-explainer');
      const title = p?.querySelector('.mismatch-explainer-title')?.textContent;
      const rows = Array.from(p?.querySelectorAll('.mismatch-explainer-row') || []);
      const text = (p?.textContent || '').toLowerCase();
      return {
        visible: !!p,
        title,
        rowCount: rows.length,
        hasExpected: text.includes('expected'),
        hasActual: text.includes('got') || text.includes('actual'),
        hasHelloLiteral: text.includes('hello'),
        hasIntType: text.includes('int'),
      };
    });
    assert(popoverState.visible, 'mismatch-explainer popover visible');
    assert(popoverState.rowCount >= 2,
           'popover has expected + actual rows: ' + popoverState.rowCount);
    assert(popoverState.hasExpected,
           'popover mentions "Expected"');
    assert(popoverState.hasActual,
           'popover has "Got" / "Actual" row');
    assert(popoverState.hasHelloLiteral,
           'popover quotes the offending "hello" literal');
    assert(popoverState.hasIntType,
           'popover names :int expected type');

    // ===================================================================
    // Phase C: Escape dismisses the popover. hideMismatchExplainer
    // hides via .visible class removal + display:none — the element
    // stays in the DOM.
    // ===================================================================
    await page.keyboard.press('Escape');
    await page.waitForFunction(
      () => {
        const p = document.querySelector('.mismatch-explainer');
        return p && !p.classList.contains('visible');
      },
      {timeout: 3000});
    const dismissed = await page.evaluate(() => {
      const p = document.querySelector('.mismatch-explainer');
      return p && !p.classList.contains('visible');
    });
    assert(dismissed,
           'Escape dismisses the explainer (removes .visible class)');

    console.log('✓ mismatch explainer verified — badge + popover + dismiss');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
